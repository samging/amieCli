package org.example.project.components.terminal.window.controller.modules

import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.MemoryWatch
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.console.openSessions
import org.example.project.components.terminal.window.controller.modelLoaders.ManagedSession
import org.example.project.components.terminal.window.controller.models.KIND_ONNX
import org.example.project.components.terminal.window.controller.models.KIND_SHARED
import org.example.project.components.terminal.window.controller.models.SessionInfo
import org.example.project.components.terminal.window.controller.modelLoaders.imported.DjlModel
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackageSession
import org.example.project.components.terminal.window.controller.runtime.ModelSession
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the blocks of a script hold: `ortClosable { }` closes what its block opened when the block ends, however it
 * ends. The others keep what their block opens across runs, under a name, until `ortScopes.free("name")`,
 * `ortScopes.freeAll()` or Terminate: `ortStatic("name") { }` for good, `ortRequired("name") { }` while anything else
 * is in session (freed once nothing else is open, opened again by its block when next run), `ortLimited("name", max) { }`
 * at most [max] sessions, the oldest closed first when one more is opened. The timed kind, freed once nothing has used
 * it for a while, is the app's own now (the assistant's model), no longer a script function. What is opened outside every block stays
 * open as it always has.
 *
 * The block in force belongs to the thread: blocks nest, the innermost decides, and work another thread does inside
 * a block is not tracked, so it stays open like a bare initMod, which is the safe direction. A template started in
 * a block is the exception, since it is the script's own work carried on elsewhere: its thread starts inside that
 * block ([carrying]), what it opens is the block's, and when an ortClosable block ends the templates started in it
 * end first ([startedIn]), then what they and the block opened is closed. An ortAsync block is the same on a thread
 * of its own: an ortClosable there, inside the block it was started in, ending with it ([OrtJobs]). Only the script's own
 * calls report here ([opened]), never ModelSessions.acquire or anything lower: a listener opens its model on the
 * calling thread too, and serve("x") inside a closable must not lose it.
 *
 * A name is one scope: asking for it again with another block function, or other settings, makes that scope the new
 * kind with what it holds, and says so. The timed and required scopes share one daemon timer, set to the nearest
 * deadline, or a couple of seconds on while a required scope holds something: a timed scope whose sessions ran since
 * the last look counts as used and gets its time again; a required scope whose holdings are all that is in session,
 * none of them running, is freed.
 *
 * When memory runs short, MemoryWatch asks for the oldest idle session of the timed and limited scopes, one at a time
 * ([freeOldestIdle]). Those only keep something to save opening it again; a static scope was told to keep it.
 */
object OrtScopes {
    private enum class Kind(val word: String, val call: String, val keeps: Boolean, val yields: Boolean = false) {
        CLOSABLE("closable", "ortClosable", false),
        STATIC("static", "ortStatic", true),
        TIMED("timed", "timed scope", true, yields = true),
        LIMITED("limited", "ortLimited", true, yields = true),
        REQUIRED("required", "ortRequired", true);

        /** Whether the timer looks at scopes of this kind: a timed one for its deadline, a required one for what else is open */
        val looks: Boolean get() = this == TIMED || this == REQUIRED
    }

    /** How often a required scope looks whether anything else is still in session */
    private const val REQUIRED_LOOK_MS = 2000L

    private class Scope(var kind: Kind, val name: String, var idleSeconds: Int = 0, var max: Int = 0) {
        // What ends the templates started in an ortClosable block; the last started goes first
        val runs = CopyOnWriteArrayList<() -> Unit>()
        @Volatile var ended = false
        /** How many threads are inside its block now: a block going on is using what it holds */
        val inside = AtomicInteger()
        /** When the scope was last used: its block entered, or a session it holds run (seen when the timer looks) */
        var usedAt: Long = System.currentTimeMillis()
        val deadline: Long get() = usedAt + idleSeconds * 1000L
        fun secondsLeft(now: Long = System.currentTimeMillis()): Long = ((deadline - now + 999) / 1000).coerceAtLeast(0)

        /** "closable", or "static search" for one with a name of its own */
        val named: String get() = if (name == kind.word) kind.word else "${kind.word} $name"

        /** How a session's row says it: "static search", "timed search, 40s left", "limited pool, 2 of 3" */
        fun label(held: Int): String = when (kind) {
            Kind.TIMED -> "$named, ${secondsLeft()}s left"
            Kind.LIMITED -> "$named, $held of $max"
            Kind.REQUIRED -> "$named, while anything else is in session"
            else -> named
        }

        /** What the scope is, for the line that says it changed: "static", "timed (idle 40s)", "limited (3 at most)" */
        val settings: String get() = when (kind) {
            Kind.TIMED -> "${kind.word} (idle ${idleSeconds}s)"
            Kind.LIMITED -> "${kind.word} ($max at most)"
            else -> kind.word
        }
    }

    /** One thing a scope answers for; [ort] is its ONNX Runtime session when it is listed as a bare one */
    private class Held(
        val scope: Scope,
        val what: String,
        val handle: Any,
        val close: () -> Unit,
        val closed: () -> Boolean,
        val ort: () -> ManagedSession?,
        val runs: () -> Int,
        val busy: () -> Boolean,
    ) {
        /** The runs the timer saw last; more since means the session was used */
        var seenRuns: Int = runs()

        // The same for memory pressure, which looks on a clock of its own
        private var lookedAtRuns: Int = seenRuns

        /** Not running, and it didn't run since the last look: a script between two calls of it is using it */
        fun quiet(): Boolean {
            val now = runs()
            return (now == lookedAtRuns && !busy()).also { lookedAtRuns = now }
        }
    }

    // Oldest first, across every scope: a session is held by one scope at most
    private val entries = ArrayList<Held>()

    // The kept scopes by name, which outlive their blocks. A closable lives only as long as its block
    private val kept = HashMap<String, Scope>()

    // The blocks the thread is inside, the innermost on top; nothing is set on a thread outside every block
    private val blocks = ThreadLocal<ArrayDeque<Scope>>()

    // The one timer of the timed scopes, a daemon thread made when the first of them holds something
    private val timer by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ortScopes-timer").apply { isDaemon = true } } }
    private var alarm: ScheduledFuture<*>? = null

    internal fun <T> closable(name: String, block: () -> T): T {
        val scope = Scope(Kind.CLOSABLE, name.trim().ifEmpty { Kind.CLOSABLE.word })
        try {
            return within(scope, block)
        } finally {
            // A stopped script gets here interrupted, which would cut short the waits that keep a close from what still runs
            val stopped = Thread.interrupted()
            // The templates first: they may still be using what is closed next
            scope.runs.asReversed().forEach { end ->
                runCatching(end).onFailure { logInternal("ortClosable '${scope.name}': a template it started could not be ended: ${it.message}", LogLevel.ERROR) }
            }
            scope.ended = true
            close(take { it.scope === scope })
            if (stopped) Thread.currentThread().interrupt()
        }
    }

    /** The block in force on this thread, for a template started here to carry to its own; null outside every block */
    internal fun inForce(): Any? = blocks.get()?.peek()

    /** [end] runs when [scope], from [inForce], ends, if it is an ortClosable block; the kept scopes keep sessions, not runs */
    internal fun startedIn(scope: Any?, end: () -> Unit) {
        if (scope !is Scope || scope.kind != Kind.CLOSABLE) return
        scope.runs += end
        if (scope.ended) end()
    }

    /** Runs [block] inside [scope], from [inForce], as a template's thread starts inside the block that started it */
    internal fun <T> carrying(scope: Any?, block: () -> T): T = if (scope is Scope) within(scope, block) else block()

    internal fun <T> static(name: String, block: () -> T): T = within(kept(Kind.STATIC, name), block)

    internal fun <T> timed(name: String, idleSeconds: Int, block: () -> T): T {
        require(idleSeconds > 0) { "timed scope '$name': idleSeconds must be at least 1, not $idleSeconds" }
        return within(kept(Kind.TIMED, name, idleSeconds = idleSeconds), block)
    }

    /**
     * Kept like static while anything else is in session: a model, package, group, template or background job the
     * required scopes don't hold themselves. Once nothing else is open, what it holds is freed, and the block opens it
     * again when next run
     */
    internal fun <T> required(name: String, block: () -> T): T = within(kept(Kind.REQUIRED, name), block)

    internal fun <T> limited(name: String, max: Int, block: () -> T): T {
        require(max > 0) { "ortLimited '$name': max must be at least 1, not $max" }
        val scope = kept(Kind.LIMITED, name, max = max)
        evict(scope)
        return within(scope, block)
    }

    /** The scope of [name], made or made the kind and settings asked for, and used now */
    private fun kept(kind: Kind, name: String, idleSeconds: Int = 0, max: Int = 0): Scope {
        val wanted = name.trim().ifEmpty { kind.word }
        var said: String? = null
        val scope = synchronized(this) {
            val scope = kept.getOrPut(wanted) { Scope(kind, wanted, idleSeconds, max) }
            if (scope.kind != kind || scope.idleSeconds != idleSeconds || scope.max != max) {
                val was = scope.settings
                scope.kind = kind
                scope.idleSeconds = idleSeconds
                scope.max = max
                said = "'$wanted' was $was, it is ${scope.settings} now, with what it holds"
            }
            scope.usedAt = System.currentTimeMillis()
            rearm()
            scope
        }
        said?.let { logInternal("${kind.call} $it") }
        return scope
    }

    private fun <T> within(scope: Scope, block: () -> T): T {
        val stack = blocks.get() ?: ArrayDeque<Scope>().also { blocks.set(it) }
        stack.push(scope)
        scope.inside.incrementAndGet()
        try {
            return block()
        } finally {
            scope.inside.decrementAndGet()
            stack.pop()
            if (stack.isEmpty()) blocks.remove()
        }
    }

    /** A model's session from initMod; [new] is whether this call opened it */
    internal fun opened(session: ModelSession, new: Boolean): ModelSession = session.also {
        hold(new, it, "model ${it.name} #${it.id}", it::close, { null }, { it.runs }, { it.running > 0 }) { it.closed }
    }

    /** A group's swarm from group(); [new] is whether this call opened it */
    internal fun opened(swarm: ModelSwarm, new: Boolean): ModelSwarm = swarm.also {
        hold(new, it, "group ${it.name} #${it.id}", it::close, { null }, { it.runs }, { it.running > 0 }) { it.closed }
    }

    /** A package session, which is new on every initMod, or a speech model's, which [new] says */
    internal fun opened(session: JarPackageSession, new: Boolean = true): JarPackageSession = session.also {
        hold(new, it, "${if (it.speechModel == null) "package" else "speech model"} ${it.shownName} #${it.id}", it::close, { null }, { it.runs }, { it.running > 0 }) { it.closed }
    }

    /** A TorchScript or TensorFlow model from a file or an imported folder, new on every call */
    internal fun opened(model: DjlModel): DjlModel = model.also {
        hold(true, it, "${it.engine} ${it.name} #${it.id}", it::close, { null }, { it.runs }, { it.running > 0 }) { it.closed }
    }

    /** A model that is its one ONNX Runtime session, importModelOnnx's or the default model, new on every call */
    internal fun <M : InteractionModel> opened(model: M, what: String, ort: () -> ManagedSession?): M = model.also {
        hold(true, it, what, it::close, ort, { ort()?.runs ?: 0 }, { (ort()?.running ?: 0) > 0 }) { ort()?.isCancelled != false }
    }

    private fun hold(new: Boolean, handle: Any, what: String, close: () -> Unit, ort: () -> ManagedSession?, runs: () -> Int, busy: () -> Boolean, closed: () -> Boolean) {
        val scope = blocks.get()?.peek()?.takeUnless { it.ended } ?: return
        val said = synchronized(this) {
            val before = entries.firstOrNull { it.handle === handle }
            when {
                before?.scope === scope -> null
                scope.kind.keeps -> {
                    entries.removeAll { it.handle === handle }
                    entries += Held(scope, what, handle, close, closed, ort, runs, busy)
                    when {
                        before != null -> "$what moved here from ${before.scope.named}, which no longer closes it"
                        new && scope.kind == Kind.REQUIRED -> "$what is kept while anything else is in session"
                        new -> "$what is kept until it is freed or terminated"
                        else -> "$what was open already, it is kept here now"
                    }
                }
                new -> {
                    entries += Held(scope, what, handle, close, closed, ort, runs, busy)
                    null
                }
                else -> "$what was open already, so it is used and left open"
            }.also { if (scope.kind.looks || before?.scope?.kind?.looks == true) rearm() }
        }
        said?.let { logInternal("${scope.kind.call} '${scope.name}': $it") }
        evict(scope)
        if (scope.kind.yields) MemoryWatch.ensureRunning()
    }

    // A limited scope over its max lets the oldest go, outside the lock like every close
    private fun evict(scope: Scope) {
        if (scope.kind != Kind.LIMITED) return
        val over = synchronized(this) {
            val mine = entries.filter { it.scope === scope }
            if (mine.size <= scope.max) emptyList() else take { it in mine.take(mine.size - scope.max) }
        }
        if (over.isEmpty()) return
        close(over)
        logInternal("ortLimited '${scope.name}': ${scope.max} kept at most, so ${over.joinToString { it.what }} closed")
    }

    private fun take(which: (Held) -> Boolean): List<Held> = synchronized(this) {
        entries.filter(which).also { taken ->
            entries.removeAll { it in taken }
            if (taken.any { it.scope.kind.looks }) rearm()
        }
    }

    // The last opened first. Every one is closed, whatever became of the one before it, and only what this closes
    // counts; a model cancelled on the page still has its own close to run, which does nothing twice. Never inside
    // the lock: a close waits for the runs in flight, and the Models page reads the scopes meanwhile
    private fun close(held: List<Held>): Int = held.asReversed().count { h ->
        val open = runCatching { !h.closed() }.getOrDefault(false)
        ModelJobs.endOn(h.handle)
        val done = runCatching { h.close() }
            .onFailure { logInternal("ortScopes: ${h.what} could not be closed: ${it.message}", LogLevel.ERROR) }
        done.isSuccess && open
    }

    // Terminate on the Models page doesn't come through here: what a kept scope holds that is gone since is forgotten
    // when the scopes are next read. A closable keeps its own until its block ends, when its close finds them closed
    private fun forgetClosed() {
        entries.removeAll { it.scope.kind.keeps && it.closed() }
    }

    // Sets the one alarm to the nearest deadline of the timed scopes that hold something, or the next look of a
    // required scope that does; under the lock
    private fun rearm() {
        alarm?.cancel(false)
        alarm = null
        val timed = entries.filter { it.scope.kind == Kind.TIMED }.minOfOrNull { it.scope.deadline }
        val required = if (entries.any { it.scope.kind == Kind.REQUIRED }) System.currentTimeMillis() + REQUIRED_LOOK_MS else null
        val next = listOfNotNull(timed, required).minOrNull() ?: return
        alarm = timer.schedule(::expire, (next - System.currentTimeMillis()).coerceAtLeast(0) + 50, TimeUnit.MILLISECONDS)
    }

    /**
     * Whether a session the required scopes don't hold themselves, [required], is open: what they stay up for. Outside
     * the lock, since the listing reads every registry of sessions, each under a lock of its own
     */
    private fun othersInSession(required: List<Held>): Boolean {
        fun own(session: SessionInfo) = required.any { it.handle === session.handle || it.ort() === session.handle }
        // A shared encoder, and the BERT encoder session an intent model runs on, are open for the models that take
        // them, never on their own
        fun part(session: SessionInfo) = session.kind == KIND_SHARED || session.kind == KIND_ONNX && session.owner.startsWith("bert:")
        return openSessions().any { !part(it) && !own(it) }
    }

    // The alarm: a timed scope whose sessions ran since the last look, or that is in use now, was used; one idle past
    // its time is freed. Whatever goes wrong here, the next alarm is set, or no timed scope is ever freed again
    private fun expire() {
        val now = System.currentTimeMillis()
        try {
            val due = synchronized(this) {
                forgetClosed()
                entries.filter { it.scope.kind == Kind.TIMED }.forEach { h ->
                    val runs = h.runs()
                    if (runs != h.seenRuns || h.busy() || h.scope.inside.get() > 0 || ModelJobs.touch(h.handle)) {
                        h.seenRuns = runs
                        h.scope.usedAt = now
                    }
                }
                entries.filter { it.scope.kind == Kind.TIMED && it.scope.deadline <= now }.map { it.scope }.distinct()
                    .map { scope -> scope to take { it.scope === scope } }
            }
            due.forEach { (scope, held) ->
                val closed = close(held)
                logInternal("timed scope '${scope.name}': nothing used it for ${scope.idleSeconds}s, $closed closed")
            }
            // A required scope stays while anything else is in session; otherwise it goes, unless its block is still
            // going, or what it holds runs or has a job on it
            val required = synchronized(this) { forgetClosed(); entries.filter { it.scope.kind == Kind.REQUIRED } }
            val orphaned = if (required.isEmpty() || othersInSession(required)) emptyList() else synchronized(this) {
                entries.filter { it.scope.kind == Kind.REQUIRED }.groupBy { it.scope }
                    .filter { (scope, held) -> scope.inside.get() == 0 && held.none { it.busy() || ModelJobs.touch(it.handle) } }
                    .keys.map { scope -> scope to take { it.scope === scope } }
            }
            orphaned.forEach { (scope, held) ->
                val closed = close(held)
                logInternal("ortRequired '${scope.name}': nothing else is in session, $closed closed; its block opens it again when next run")
            }
        } finally {
            synchronized(this) { rearm() }
        }
    }

    /** Whether a timed or limited scope holds something, which memory pressure could close: what the watch stays up for */
    internal fun holdsYielding(): Boolean = synchronized(this) {
        forgetClosed()
        entries.any { it.scope.kind.yields }
    }

    /**
     * Closes the oldest session of a timed or limited scope that nothing uses, because of [why], with one console line;
     * false when there is none. Left alone are what runs, what a block still going holds, and what a job waits for or
     * asks. Every entry is looked at each time, so a run since the last look counts for all of them
     */
    internal fun freeOldestIdle(why: String): Boolean {
        val oldest = synchronized(this) {
            forgetClosed()
            val idle = entries.filter { it.scope.kind.yields && it.quiet() }
            idle.firstOrNull { it.scope.inside.get() == 0 && !ModelJobs.touch(it.handle) }?.also { h -> take { it === h } }
        } ?: return false
        close(listOf(oldest))
        logInternal("${oldest.scope.kind.call} '${oldest.scope.name}': $why, so ${oldest.what}, its oldest idle session, closed")
        return true
    }

    /** One line per kept scope that holds something, also printed to the console */
    fun print(): List<String> {
        val lines = synchronized(this) {
            forgetClosed()
            entries.filter { it.scope.kind.keeps }.groupBy { it.scope }
                .map { (scope, held) ->
                    "${scope.name}: ${held.joinToString { it.what }}" + when (scope.kind) {
                        Kind.TIMED -> " (timed, ${scope.secondsLeft()}s left)"
                        Kind.LIMITED -> " (limited, ${held.size} of ${scope.max})"
                        Kind.REQUIRED -> " (required: freed once nothing else is in session)"
                        else -> ""
                    }
                }
        }
        logInternal(
            if (lines.isEmpty()) "ortScopes: nothing is kept; ortStatic(\"name\") { initMod(\"x\") } keeps what it opens"
            else "ortScopes (${lines.size}):\n" + lines.joinToString("\n") { "  $it" }
        )
        return lines
    }

    /** Closes what the kept scope holds, the last opened first; how many were closed */
    fun free(name: String): Int {
        val wanted = name.trim()
        val scope = synchronized(this) { kept[wanted] }
            ?: return 0.also { logInternal("ortScopes: no static, required or limited scope '$wanted'", LogLevel.ERROR) }
        return close(take { it.scope === scope }).also { logInternal("ortScopes: '$wanted' freed, $it closed") }
    }

    /** Closes what every kept scope holds, the last opened first; how many were closed */
    fun freeAll(): Int =
        close(take { it.scope.kind.keeps }).also { logInternal("ortScopes: every kept scope freed, $it closed") }

    /** The words the row of [session] gives what holds it, such as `static search` or `closable`; null when nothing does */
    internal fun scopeOf(session: Any): String? = synchronized(this) {
        forgetClosed()
        entries.firstOrNull { it.handle === session }?.let(::label)
    }

    /** [scopeOf] for a row that lists the ONNX Runtime session itself */
    internal fun scopeOfOrt(session: ManagedSession): String? = synchronized(this) {
        entries.firstOrNull { it.ort() === session }?.let(::label)
    }

    private fun label(held: Held): String = held.scope.label(entries.count { it.scope === held.scope })
}
