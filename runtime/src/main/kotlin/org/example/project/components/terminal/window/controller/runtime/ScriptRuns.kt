package org.example.project.components.terminal.window.controller.runtime

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.script.ScriptTemplates
import org.example.project.components.terminal.window.controller.script.StartedRuns
import org.example.project.components.terminal.window.controller.script.TemplateRuns
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modules.SessionMod
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * A script from when it starts until nothing of it is left: while it runs, and after that for as long as a session it
 * opened stays open. The CPU time and the allocation are those of the thread the script runs on, which the JVM can
 * tell apart; threads the script starts and ONNX Runtime's own are not in them (what the sessions spent is on the
 * sessions).
 */
class ScriptRun internal constructor(
    val id: Int,
    /** The template the script is, word for word, else main.kts */
    val name: String,
    val template: Boolean,
    private val thread: Thread,
) {
    val startedAt: Instant = Instant.now()
    @Volatile var endedAt: Instant? = null
        internal set

    // The keys of what it opened, as SessionInfo has them
    private val held = CopyOnWriteArraySet<String>()
    private val shared = CopyOnWriteArrayList<SessionMod>()

    /** The background runs it started: Stop cancels them, and when it ends its template(...) values are closed */
    internal val started = StartedRuns()

    // What the thread had used before this script: a run gets a thread of the pool, not one of its own
    private val cpuBefore = threadCpu()
    private val allocatedBefore = threadAllocated()
    @Volatile private var cpuAtEnd: Long? = null
    @Volatile private var allocatedAtEnd: Long? = null

    val running: Boolean get() = endedAt == null

    /** The keys of the sessions it opened, those closed since included */
    val sessionKeys: Set<String> get() = held

    /** CPU time on its thread so far, null where the platform can't say */
    val cpuNanos: Long? get() = cpuAtEnd ?: if (running) used(threadCpu(), cpuBefore) else null

    /** Bytes allocated on its thread so far, which isn't what it holds: most of it is garbage soon */
    val allocatedBytes: Long? get() = allocatedAtEnd ?: if (running) used(threadAllocated(), allocatedBefore) else null

    internal fun holds(key: String) { held += key }

    internal fun inherit(from: ScriptRun) { held += from.held }

    internal fun keep(model: SessionMod) { shared += model }

    // On the script's own thread, while the figures of the thread are still the script's
    internal fun end() {
        cpuAtEnd = used(threadCpu(), cpuBefore)
        allocatedAtEnd = used(threadAllocated(), allocatedBefore)
        endedAt = Instant.now()
        shared.forEach { it.close() }
        shared.clear()
        started.closeDelegated()
    }

    private fun used(now: Long?, before: Long?): Long? = if (now == null || before == null) null else (now - before).coerceAtLeast(0)

    private fun threadCpu(): Long? = runCatching { threads.getThreadCpuTime(thread.id).takeIf { it >= 0 } }.getOrNull()

    private fun threadAllocated(): Long? = runCatching { (threads as? com.sun.management.ThreadMXBean)?.getThreadAllocatedBytes(thread.id)?.takeIf { it >= 0 } }.getOrNull()

    private companion object {
        val threads = ManagementFactory.getThreadMXBean()
    }
}

/** The scripts running now and the ones that left sessions open, for the Sessions tab */
object ScriptRuns {
    private const val MAIN = "main.kts"
    private val ids = AtomicInteger()
    private val runs = CopyOnWriteArrayList<ScriptRun>()
    private val onThread = ThreadLocal<ScriptRun?>()

    /** The runs in the order they began; [forget] is what drops those with nothing left */
    val all: List<ScriptRun> get() = runs.toList()

    /**
     * The run on this thread, else the only one running: a script may call the API from a thread of its own, and only
     * a script calls it. Two at once and a thread neither started: nobody's, so it isn't put down to the wrong one.
     */
    private fun current(): ScriptRun? = onThread.get() ?: runs.filter { it.running }.singleOrNull()

    /** The name a run of [code] goes by: the template it is word for word, else main.kts */
    private fun nameOf(code: String): Pair<String, Boolean> {
        val script = code.trim()
        val template = runCatching { ScriptTemplates.list().firstOrNull { ScriptTemplates.code(it.id)?.trim() == script } }
            .onFailure { logInternal("script runs: the templates could not be read: ${it.message}", LogLevel.ERROR) }.getOrNull()
        return if (template != null) template.name to true else MAIN to false
    }

    /**
     * Runs [block], which runs the script [code], as one run. Run again, a script takes over what its last run left
     * open, so a script has one row however often it runs. The [SessionMod]s it took go when it ends; what it opened
     * with initMod stays open, as ever, and with it the row.
     */
    fun <T> track(code: String, block: () -> T): T {
        val (name, template) = nameOf(code)
        val run = ScriptRun(ids.incrementAndGet(), name, template, Thread.currentThread())
        runs.filter { it.name == name && !it.running }.forEach { before ->
            run.inherit(before)
            runs -= before
        }
        runs += run
        onThread.set(run)
        try {
            return block()
        } finally {
            onThread.remove()
            run.end()
        }
    }

    /** Puts the session of [key] down to the script calling; what no script opened stays nobody's */
    internal fun opened(key: String) {
        // A background template's script is its own run, and what it opens is its, not whichever script happens to be the only one going
        TemplateRuns.onThread.get()?.let { it.holds(key); return }
        current()?.holds(key)
    }

    /** The same for a shared model, which also goes when the run ends */
    internal fun keep(model: SessionMod): SessionMod = model.also {
        TemplateRuns.onThread.get()?.let { run -> run.holds(model.sessionKey); return@also }
        current()?.run {
            holds(model.sessionKey)
            keep(model)
        }
    }

    /** The run the calling thread is, null when no script is calling */
    internal fun callingRun(): ScriptRun? = current()

    /** What the script calling has started, to put a new background run down to; null when no script is calling */
    internal fun startedByCaller(): StartedRuns? = current()?.started

    /** Runs [block] on this thread as [run]'s, as the thread of an ortAsync block works for its script; null leaves it as it is */
    internal fun <T> carrying(run: ScriptRun?, block: () -> T): T {
        if (run == null) return block()
        val before = onThread.get()
        onThread.set(run)
        try {
            return block()
        } finally {
            if (before == null) onThread.remove() else onThread.set(before)
        }
    }

    /** Stop on a script: everything the running scripts started ends, nested runs too, whether or not the script stops at once */
    fun stopStarted() = runs.filter { it.running }.forEach { it.started.cancel() }

    /** Drops the runs that ended with nothing in [open] left of what they opened */
    internal fun forget(open: Set<String>) {
        runs.filter { !it.running && it.sessionKeys.none(open::contains) }.forEach { runs -= it }
    }
}
