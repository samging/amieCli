package org.example.project.components.terminal.window.controller.script

import org.example.project.components.terminal.integrations.amiePilot.BundledModels
import org.example.project.components.terminal.integrations.amiePilot.SharedEncoders
import org.example.project.components.terminal.window.controller.export.ScriptScan
import org.example.project.components.terminal.window.controller.modules.ModelJobs
import org.example.project.components.terminal.window.controller.modules.OrtScopes
import org.example.project.components.terminal.window.controller.runtime.RunMeter
import org.example.project.components.terminal.window.controller.runtime.ScriptRuns
import org.example.project.components.terminal.window.controller.util.SystemStats
import org.example.project.components.terminal.window.controller.util.bytes
import org.example.project.components.terminal.window.controller.runtime.openSessionNames
import org.example.project.components.terminal.window.controller.modules.readModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.example.project.components.terminal.window.controller.envBuilder.DEFAULT_ENV
import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironment
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.short
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.ResponseDto
import org.example.project.components.terminal.window.controller.console.MemoryWatch
import org.example.project.components.terminal.window.controller.console.RunUse
import org.example.project.components.terminal.window.controller.console.ThreadUsage
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.console.logToConsole
import org.example.project.components.terminal.window.controller.logger
import org.example.project.components.terminal.window.controller.sandbox.SANDBOX_SOURCE
import org.jetbrains.kotlin.cli.common.repl.IReplStageState
import org.jetbrains.kotlin.cli.common.repl.KOTLIN_SCRIPT_STATE_BINDINGS_KEY
import java.time.Duration
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.script.Bindings
import javax.script.Compilable
import javax.script.ScriptContext
import javax.script.ScriptEngineManager
import kotlin.reflect.KProperty

/*
 * Saved templates run in the background: coroutineTemplate("reader") in main.kts or the Background button of the
 * Templates pane starts one and goes on. A run is a coroutine that blocks in engine.eval, and main.kts talks to it over
 * two channels, what it sends into input() and what output() hands to receive().
 *
 * Each run has an engine of its own, dropped when it ends. The engine keeps its state in the bindings and holds that
 * state's lock for a whole eval, so a second eval on the same bindings waits for main.kts to end, which would never
 * come while main.kts waits in receive(). Fresh bindings on one engine don't wait, but the engine has one field for
 * the script context an eval is using and the first eval to end clears it for the other.
 *
 * Only the running script can be interrupted, never its compile. Every compile in the app reads the classpath through
 * one shared FastJarFileSystem, and a read interrupted there closes the shared file and leaves that jar cached as
 * empty, so the compiles beside it, main.kts included, stop finding classes. A cancel during a compile takes effect
 * when the compile ends.
 *
 * Runs know who started them. A cancel goes down to everything a run started, however deep, and so does Stop on a
 * script; a template can't start itself, directly or through another, and they nest four deep at most. A run ending
 * by itself closes the template(...) values it started and leaves what coroutineTemplate started going, as that says.
 * Started inside an ortClosable block, either kind ends with that block: see OrtScopes.
 */

/** How a run stands. Printed it reads running, finished, cancelled, or failed: and the reason */
data class TemplateState(val name: String, val reason: String? = null) {
    val running: Boolean get() = this == RUNNING
    val failed: Boolean get() = reason != null

    override fun toString(): String = if (reason == null) name else "$name: $reason"

    companion object {
        val RUNNING = TemplateState("running")
        val QUEUED = TemplateState("queued")
        val FINISHED = TemplateState("finished")
        val CANCELLED = TemplateState("cancelled")
        fun failed(reason: String) = TemplateState("failed", reason)
    }
}

/**
 * A template running in the background, what coroutineTemplate returns: main.kts gives it input with [send] and reads
 * what it answers with [receive]. It keeps running after main.kts ends, until it ends or is cancelled.
 */
class TemplateRun internal constructor(
    val id: Int,
    val name: String,
    /** The template's file, which its errors name */
    internal val file: String,
    /** The env it runs in, the current one when it started */
    internal val env: ScriptEnvironment,
    input: List<String>,
    /** The run that started this one; null when a script or the pane did */
    internal val parent: TemplateRun? = null,
    /** Started with template(...): closed for whoever started it when that ends. coroutineTemplate's goes on */
    internal val closesWithOwner: Boolean = false,
    /** The block of ortClosable, ortStatic and the like it was started in, OrtScopes.inForce(); its thread works inside it */
    private val startedInScope: Any? = null,
    /** A line typed in the console: it says only what the script says, and input() reads the console */
    internal val typed: Boolean = false,
) {
    /** How the open sessions list it */
    internal val sessionName: String get() = "$name #$id (background)"

    /** The runs from the outermost down to this one */
    internal val chain: List<TemplateRun> = parent?.chain.orEmpty() + this

    /** The runs it started */
    internal val started = StartedRuns()

    val startedAt: Instant = Instant.now()

    /** When it ended, null while it runs */
    @Volatile var endedAt: Instant? = null
        private set

    /** What its script's thread used: cpu and allocation, kept after the thread ends */
    internal val usage = ThreadUsage()

    // The keys of the sessions it opened, as SessionInfo has them
    private val held = java.util.concurrent.CopyOnWriteArraySet<String>()
    internal fun holds(key: String) { held += key }

    /** What it used and opened, for the strip and the Templates pane */
    internal fun use(): RunUse = RunUse(usage.cpuNanos, usage.allocatedBytes, held.toSet())

    /** Runs [block], the script itself, on this thread as this run's: what it uses is counted to the run, and what it opens is the run's */
    internal fun <T> working(block: () -> T): T = usage.track {
        TemplateRuns.onThread.set(this)
        try { OrtScopes.carrying(startedInScope, block) } finally { TemplateRuns.onThread.remove() }
    }

    /** The meter of the run while it goes, what its row on the Sessions tab reads; null before it starts and once it ended */
    @Volatile internal var meter: RunMeter? = null

    // Sending never waits: what main.kts sends stays until the template reads it
    private val inbox = Channel<String>(Channel.UNLIMITED).also { box -> input.forEach { box.trySend(it) } }

    // A run nobody reads, as one the Background button starts, must neither stop at output nor fill the memory, so
    // only the newest answers are kept
    private val outbox = Channel<String>(OUTPUT_KEPT, BufferOverflow.DROP_OLDEST) { overflowed() }
    private val warned = AtomicBoolean(false)

    private val states = MutableStateFlow(TemplateState.RUNNING)
    private val stopRequested = AtomicBoolean(false)

    // Set before anyone can reach the run
    internal lateinit var job: Job

    private val source = "$SANDBOX_SOURCE/templates/$file"

    /** Running, finished, failed with the reason, or cancelled */
    val state: TemplateState get() = states.value

    /** Cancelled, and not ended yet: a busy script stops only at its next API call */
    internal val stopping: Boolean get() = stopRequested.get() && state.running

    /** Gives the template a line of input; false when it can't get it, input being closed or the run ended */
    fun send(text: String): Boolean = inbox.trySend(text).isSuccess

    /** The template's next output, waiting for it; null once it has ended and everything it said was read */
    fun receive(): String? = runBlocking { outbox.receiveCatching().getOrNull() }

    /** The same, waiting [timeoutMillis] at most; null then too, and [state] tells the two apart */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun receive(timeoutMillis: Long): String? = runBlocking {
        // A select takes the output or the timeout, never both, so an output arriving as time runs out isn't lost
        select {
            outbox.onReceiveCatching { it.getOrNull() }
            onTimeout(timeoutMillis) { null }
        }
    }

    /** No more input is coming: the template's input() is null once it has read what was sent */
    fun closeInput() {
        inbox.close()
    }

    /** Waits for the end; how it ended */
    fun await(): TemplateState = runBlocking { states.first { !it.running } }

    /**
     * Ends the run and everything it started, also through a run that ended already: a blocked input() or sleep ends
     * now, a busy script at its next log, input or output
     */
    fun cancel() {
        started.cancel()
        if (!stopRequested.compareAndSet(false, true) || !state.running) return
        inbox.cancel()
        job.cancel(CancellationException("template '$name' was cancelled"))
        // A typed line that goes on regardless mustn't hold the lines typed after it
        if (typed) ConsoleSession.stopped(this)
        TemplateRuns.publish()
    }

    /** Whoever started it with template(...) ended: no more input comes, and a run that goes on regardless is cancelled after [ownerGraceMillis] */
    internal fun closeForOwner() {
        if (!state.running) return
        closeInput()
        TemplateRuns.watch {
            if (withTimeoutOrNull(ownerGraceMillis) { states.first { !it.running } } == null) {
                say("whoever called this template has ended and it goes on, so it is cancelled")
                cancel()
            }
        }
    }

    /** Ended by the ortClosable block it was started in; a template value called after that starts it again */
    @Volatile internal var endedByBlock = false
        private set

    /**
     * The ortClosable block it was started in has ended: no more input comes, and the block waits for the end, so what
     * it closes next is no longer in use. A run that goes on regardless is cancelled after [ownerGraceMillis]
     */
    internal fun endWithBlock() {
        endedByBlock = true
        if (!state.running) return
        closeInput()
        try {
            runBlocking {
                if (withTimeoutOrNull(ownerGraceMillis) { states.first { !it.running } } == null) {
                    say("the ortClosable block that called this template has ended and it goes on, so it is cancelled")
                    cancel()
                    withTimeoutOrNull(BLOCK_CANCEL_WAIT_MS) { states.first { !it.running } }
                }
            }
        } catch (e: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
        }
    }

    override fun toString(): String = "#$id $name $state ${took().short()}"

    /** How long it ran, or has been running */
    internal fun took(now: Instant = Instant.now()): Duration = Duration.between(startedAt, endedAt ?: now)

    internal fun say(message: String, level: LogLevel = LogLevel.INFO) {
        logToConsole(ResponseDto(time = Instant.now(), message = if (typed) message else "$name: $message", level = level, source = source))
    }

    /** What the run says about itself: a typed line is only what it logs */
    internal fun note(message: String, level: LogLevel = LogLevel.INFO) {
        if (!typed) say(message, level)
    }

    /** Throws once the run is cancelled, at each of the API calls a busy script is stopped by */
    internal fun stopIfCancelled() {
        if (stopRequested.get()) throw CancellationException("template '$name' was cancelled")
    }

    // Blocked in input() this moment
    @Volatile private var waitingForInput = false
    @Volatile private var toldUnfed = false

    /**
     * For a bundle whose script has ended, where nothing could ever send this run a line: one waiting for input with
     * no run left over it is told so in the console and gets the null of a closed input, instead of waiting for good
     */
    internal fun closeIfUnfed() {
        if (!waitingForInput || toldUnfed || parent?.state?.running == true) return
        toldUnfed = true
        say("waits for input and the script has ended, so nobody can give it any; its input is closed and input() gives null. Send its lines before the script ends", LogLevel.ERROR)
        closeInput()
    }

    internal fun nextInput(): String? {
        stopIfCancelled()
        waitingForInput = true
        val text = try {
            runBlocking { inbox.receiveCatching().getOrNull() }
        } catch (e: InterruptedException) {
            throw CancellationException("template '$name' was cancelled").apply { initCause(e) }
        } finally {
            waitingForInput = false
        }
        // A cancel closes the input too, which is not the null of a template told that no more is coming
        stopIfCancelled()
        return text
    }

    internal fun emit(text: String) {
        outbox.trySend(text)
    }

    /** The first ending counts; false when it had ended */
    @Synchronized
    internal fun end(outcome: TemplateState): Boolean {
        if (!state.running) return false
        endedAt = Instant.now()
        states.value = outcome
        inbox.cancel()
        outbox.close()
        return true
    }

    private fun overflowed() {
        if (warned.compareAndSet(false, true)) say("more output than was read; only the newest $OUTPUT_KEPT answers are kept for receive()")
    }

    internal companion object {
        const val OUTPUT_KEPT = 1024

        /** How long a run of template(...) gets to end by itself once its input is closed for it; tests shorten it */
        @Volatile var ownerGraceMillis = 10_000L

        /** How long an ortClosable block waits for a run it cancelled: a script busy elsewhere stops at its next log */
        const val BLOCK_CANCEL_WAIT_MS = 2_000L
    }
}

/** The runs a script or a run started: what a cancel reaches, and what is closed for it when it ends */
internal class StartedRuns {
    private val runs = CopyOnWriteArrayList<TemplateRun>()
    @Volatile private var cancelled = false

    /** Whether a stop has come: a model job started in here after it ends at once */
    val stopped: Boolean get() = cancelled

    private val TemplateRun.over: Boolean get() = !state.running && !started.going

    private val going: Boolean get() = runs.any { !it.over }

    /** Kept while it or anything it started goes on; one added after [cancel] is cancelled at once */
    fun add(run: TemplateRun) {
        runs.removeIf { it.over }
        runs += run
        if (cancelled) run.cancel()
    }

    fun cancel() {
        cancelled = true
        runs.forEach { it.cancel() }
        ModelJobs.endOf(this)
    }

    /** Whoever started these ended: nobody is left to read a model job's answer, so that ends too */
    fun closeDelegated() {
        runs.filter { it.closesWithOwner }.forEach { it.closeForOwner() }
        ModelJobs.endOf(this)
    }

    /** Whether a run of template(...) in here, or one it holds, still goes: they are being closed, not left open */
    fun delegatedGoing(): Boolean = runs.any { it.closesWithOwner && (it.state.running || it.started.delegatedGoing()) }

    /** How the runs of template(...) in here, and those they hold, are listed among the open sessions */
    fun delegatedSessionNames(): Set<String> = runs.filter { it.closesWithOwner }.flatMap { setOf(it.sessionName) + it.started.delegatedSessionNames() }.toSet()
}

/**
 * A saved template to call like a function, what `template("name")` gives a script: `val reader by template("name")`,
 * then `reader("text")` is the template's next output. Nothing starts before the first call, which goes through
 * [TemplateRuns.start] with its checks; a start that was refused is tried again by the next call. Later calls use the
 * same run. A template that failed makes the call throw, with its name and its error.
 */
class TemplateDelegate internal constructor(private val start: () -> TemplateRun) : AutoCloseable {
    private val asking = Any()
    @Volatile private var current: TemplateRun? = null

    /** The run behind it, started now if no call has yet */
    val run: TemplateRun get() = synchronized(asking) { current ?: start().also { current = it } }

    operator fun invoke(text: String): String = synchronized(asking) {
        // The ortClosable block it was started in has ended: this call is a new start, in the block it is made from
        if (current?.endedByBlock == true) current = null
        val job = run
        job.send(text)
        job.receive() ?: throw IllegalStateException(ended(job))
    }

    /** No more input is coming: the template's input() turns null, and this waits for the end */
    override fun close() {
        val job = current ?: return
        job.closeInput()
        job.await()
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): TemplateDelegate = this

    override fun toString(): String = current?.let { "template ${it.name}: ${it.state}" } ?: "template, not started"

    private fun ended(job: TemplateRun): String = when {
        job.state.failed -> "Template '${job.name}' failed: ${job.state.reason}"
        job.state == TemplateState.CANCELLED -> "Template '${job.name}' was cancelled"
        else -> "Template '${job.name}' ended without an answer"
    }
}

/**
 * What a background run gets as `amie`: the script API of [parent], with the console lines carrying the template's
 * name, input and output on the run's channels, and every line a place where a cancelled run stops. Members `by parent`
 * reach the parent's own log, so info, error and data are sent back through this one. The console's interpreter keeps
 * one for all its lines and points [run] at the line running now (ConsoleSession).
 */
internal class BackgroundLogger(parent: ScriptLogger, run: TemplateRun) : ScriptLogger by parent {
    @Volatile internal var run: TemplateRun = run

    override fun log(message: String, level: LogLevel) {
        run.stopIfCancelled()
        run.say(message, level)
    }

    override fun info(message: String) = log(message, LogLevel.INFO)
    override fun error(message: String) = log(message, LogLevel.ERROR)
    override fun data(message: String) = log(message, LogLevel.DATA)
    // Delegated, these would print on the parent's console and skip the run
    override fun log(message: Any?, level: LogLevel) = log(consoleText(message), level)
    override fun info(message: Any?) = info(consoleText(message))
    override fun error(message: Any?) = error(consoleText(message))
    override fun data(message: Any?) = data(consoleText(message))
    override fun output(value: Any?) = output(consoleText(value))

    override fun input(): String? {
        run.stopIfCancelled()
        return if (run.typed) ScriptInput.next() else run.nextInput()
    }

    override fun output(text: String) {
        log(text)
        run.emit(text)
    }

    // The env was settled before the run began, and a run never changes it under a script that is running
    override fun import(notation: String) {
        if (!TemplateRuns.holds(run.env, notation)) log("import: env '${run.env.name}' has no $notation and a background run doesn't add to it", LogLevel.ERROR)
    }

    override fun implementation(notation: String) = import(notation)

    // Members `by parent` would start them as the parent's, not as this run's
    override fun coroutineTemplate(name: String, input: List<String>): TemplateRun = TemplateRuns.start(name, input, this)
    override fun template(name: String): TemplateDelegate = TemplateDelegate { TemplateRuns.start(name, parent = this, closesWithOwner = true) }
}

/** What is free for a run, in bytes: of the heap, and of the machine's RAM when the platform says */
internal data class FreeMemory(val heapBytes: Long, val machineBytes: Long? = null)

/** A run as the panes show it; a new one each time the run changes, so they follow it */
internal data class RunShown(val run: TemplateRun, val state: TemplateState, val stopping: Boolean)

/** The runs in the background: `templateRuns` in a script, the Templates pane's fold and the Sessions tab read them */
object TemplateRuns {
    /** How many can run at once on a heap large enough, so a template that starts itself can't take the machine down */
    const val MAX_RUNS = 8

    /**
     * What one run is counted as holding on the heap: its own Kotlin script engine and the state of a small script,
     * measured at 32 to 49 MB (StartupCostTest), taken as more here since the script allocates too and the rest of
     * the app shares the heap with the runs
     */
    const val RUN_HEAP_BYTES = 64L shl 20

    /** How many can run at once here: [MAX_RUNS] on a heap of 512 MB or more, fewer on a smaller one */
    val maxRuns: Int get() = maxRunsFor(Runtime.getRuntime().maxMemory())

    /** One run per [RUN_HEAP_BYTES] of heap, between 1 and [MAX_RUNS]: 8 at 512 MB, 4 at 256 MB, 2 at 128 MB */
    internal fun maxRunsFor(maxHeapBytes: Long): Int = (maxHeapBytes / RUN_HEAP_BYTES).toInt().coerceIn(1, MAX_RUNS)

    /** How deep templates can start templates: the one a script or the pane started is the first */
    const val MAX_DEPTH = 4

    /**
     * What is free right now. The first reading is a cheap one; before a start is refused on it comes a [settled] one,
     * since the heap holds garbage until it is collected and the machine's free figure leaves out what it would give back.
     * Tests set this
     */
    internal var freeMemory: (settled: Boolean) -> FreeMemory = ::readFreeMemory

    private fun readFreeMemory(settled: Boolean): FreeMemory {
        fun heap() = Runtime.getRuntime().run { maxMemory() - totalMemory() + freeMemory() }
        if (settled && heap() < RUN_HEAP_BYTES) System.gc()
        // The first reading comes every second while the watch is on, so it forks nothing, as a full read does on a Mac
        val machine = if (settled) SystemStats.availableBytes(SystemStats.read()) else SystemStats.machineFreeBytes()
        return FreeMemory(heap(), machine.takeIf { it > 0 })
    }

    /** The size of the encoders the script would load: nothing for one that opens no model, or when they are loaded already */
    internal fun encoderBytes(code: String): Long {
        val needs = ScriptScan.scan(code)
        if (!needs.usesEncoder) return 0
        val paths = needs.models.mapNotNull { name -> runCatching { readModule(name)?.second?.encoder }.getOrNull() }.ifEmpty { listOf(BundledModels.encoder) }
        return paths.map(BundledModels::resolveEncoder).distinct().filter { SharedEncoders.loadedOn(it) == null }.sumOf { File(it, "model.onnx").length() }
    }

    /**
     * What is short for one more run of [code], as the line that says so; null when it fits. The run's engine is on the
     * heap, [RUN_HEAP_BYTES]; an encoder loads outside the heap, where ONNX Runtime keeps it, so it counts against the
     * machine's RAM and not against the heap.
     */
    internal fun memoryShort(code: String): String? {
        val encoder = encoderBytes(code)
        val ramNeeded = RUN_HEAP_BYTES + encoder
        fun short(free: FreeMemory): String? {
            val machine = free.machineBytes
            return when {
                free.heapBytes < RUN_HEAP_BYTES -> "Not started: ${bytes(free.heapBytes.coerceAtLeast(0))} of heap free, a run needs ${bytes(RUN_HEAP_BYTES)}; stop a template or free a scope"
                machine != null && machine < ramNeeded -> "Not started: ${bytes(machine)} of RAM free, a run needs ${bytes(ramNeeded)}" + (if (encoder > 0) " with the encoder" else "") + "; close a session or another program"
                else -> null
            }
        }
        return short(freeMemory(false))?.let { short(freeMemory(true)) }
    }

    private const val LINE_NAME = "console"
    private const val LINE_FILE = "console.kts"

    /** How many ended runs the Templates pane keeps showing: a failed one has its reason there, since the pane hides the console */
    private const val ENDED_SHOWN = 3

    /**
     * In a compiled bundle, the class each template was compiled to, by the template's id (null when the bundle holds
     * no such template); null in the app, where a run compiles the template's text with an engine of its own. The
     * class is made as ScriptRunner.runCompiled takes it: its constructor takes the ScriptLogger and runs the script.
     */
    @Volatile
    var compiledTemplates: ((templateId: String) -> Class<*>?)? = null

    // Its IO threads are daemons, so a run in progress doesn't hold the app back from closing
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicInteger()

    /** The run whose script this thread is executing, so what the script opens is put down to it */
    internal val onThread = ThreadLocal<TemplateRun?>()
    private var runs = emptyList<TemplateRun>()
    private val shownRuns = MutableStateFlow<List<RunShown>>(emptyList())

    internal val shown: StateFlow<List<RunShown>> = shownRuns.asStateFlow()

    /** The runs that haven't ended, the oldest first */
    @get:Synchronized
    val active: List<TemplateRun> get() = runs.filter { it.state.running }

    /**
     * Starts the template [name] and returns while it starts up. [input] waits for it to read. The run answers through
     * [parent]'s script API; without one (the Background button) it gets a sandbox logger of its own. It runs in the
     * current env and changes nothing of it, so a template that asks for another env or for a package this one doesn't
     * have is refused here, with what to do instead. So is a run past [maxRuns], one [memoryShort] says doesn't fit, and
     * one that would loop or nest past [MAX_DEPTH]; those say so in the console too. [closesWithOwner] is for template(...).
     */
    @Synchronized
    fun start(name: String, input: List<String> = emptyList(), parent: ScriptLogger? = null, closesWithOwner: Boolean = false): TemplateRun {
        val caller = (parent as? BackgroundLogger)?.run
        caller?.stopIfCancelled()
        val template = find(name)
        val code = ScriptTemplates.code(template.id) ?: throw IllegalArgumentException("The code of template '${template.name}' is gone from AMP/templates")
        val env = ScriptEnvironments.current ?: ScriptEnvironments.peek(DEFAULT_ENV)
        val file = "${template.id}.kts"
        refuseLoop(template, file, caller)
        refuseOtherEnv(template, code, env)
        refuseWithoutRoom(template.name, file, "Template '${template.name}'", code)
        val owner = caller?.started ?: if (parent != null) ScriptRuns.startedByCaller() else null
        val block = OrtScopes.inForce()
        val run = TemplateRun(ids.incrementAndGet(), template.name, file, env, input, caller, closesWithOwner, block)
        launch(run, code, parent, owner, block)
        return run
    }

    /**
     * Runs [code], a line or a block typed in the console, beside everything else, so the console stays free however
     * long it takes. The lines share one interpreter (ConsoleSession): what one defines is there for the next, and
     * each waits for the one before it. Terminate ends it.
     */
    @Synchronized
    fun startLine(code: String): TemplateRun {
        val env = ScriptEnvironments.current ?: ScriptEnvironments.peek(DEFAULT_ENV)
        refuseWithoutRoom(LINE_NAME, LINE_FILE, "This line", code)
        val run = TemplateRun(ids.incrementAndGet(), LINE_NAME, LINE_FILE, env, emptyList(), typed = true)
        ConsoleSession.queued(run)
        launch(run, code, null, null, null)
        return run
    }

    private fun launch(run: TemplateRun, code: String, parent: ScriptLogger?, owner: StartedRuns?, block: Any?) {
        run.job = scope.launch(start = CoroutineStart.LAZY) { execute(run, code, parent) }
        // Also for a run cancelled before its coroutine got to start, which then never runs at all
        run.job.invokeOnCompletion {
            run.end(TemplateState.CANCELLED)
            if (run.typed) ConsoleSession.left(run)
            publish()
        }
        runs = runs + run
        publish()
        // Before it starts, so a cancel of whoever started it can't miss it
        owner?.add(run)
        OrtScopes.startedIn(block, run::endWithBlock)
        run.job.start()
        MemoryWatch.ensureRunning()
    }

    /** Runs [block] beside everything else, for what has to wait for a run without holding anyone up */
    internal fun watch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** One console line, where the run's own lines go, and the error the caller gets */
    private fun refuse(name: String, file: String, why: String): Nothing {
        logToConsole(ResponseDto(time = Instant.now(), message = "$name: $why", level = LogLevel.ERROR, source = "$SANDBOX_SOURCE/templates/$file"))
        throw IllegalStateException(why)
    }

    private fun refuseWithoutRoom(name: String, file: String, who: String, code: String) {
        val cap = maxRuns
        if (active.size >= cap) refuse(name, file,
            "$who can't start: $cap templates run in the background already, the most there can be" +
                (if (cap < MAX_RUNS) " on a heap of ${bytes(Runtime.getRuntime().maxMemory())} (each run holds a compiler of its own)" else "") +
                "; templateRuns.print() lists them and templateRuns.cancelAll() stops them")
        memoryShort(code)?.let { refuse(name, file, it) }
    }

    /** A template that gets to start itself never ends, and neither does the chain of what it holds */
    private fun refuseLoop(template: ScriptTemplate, file: String, caller: TemplateRun?) {
        val chain = caller?.chain.orEmpty()
        val names = (chain.map { it.name } + template.name).joinToString(" > ")
        if (chain.any { it.file == file }) refuse(template.name, file, "Not started: template '${template.name}' would be calling itself: $names")
        if (chain.size >= MAX_DEPTH) refuse(template.name, file, "Not started: templates nest $MAX_DEPTH deep at most: $names")
    }

    /** Lists the runs, what ended lately included, as console lines; the lines are returned too */
    @Synchronized
    fun print(): List<String> {
        val lines = runs.map { it.toString() }.ifEmpty { listOf("no templates in the background") }
        lines.forEach { logInternal(it) }
        return lines
    }

    /** Cancels every run; how many there were */
    fun cancelAll(): Int = active.onEach { it.cancel() }.size

    /** Whether [env] has [notation]: declared in it as written, or a package it resolved, a dependency's included */
    internal fun holds(env: ScriptEnvironment, notation: String): Boolean = notation in env.declared || notation in env.packages

    private fun find(name: String): ScriptTemplate {
        val wanted = name.trim()
        val all = ScriptTemplates.list()
        return all.firstOrNull { it.name.equals(wanted, ignoreCase = true) } ?: all.firstOrNull { it.id == wanted }
            ?: throw IllegalArgumentException("There is no template '$wanted'; " + (if (all.isEmpty()) "none are saved yet" else "the templates are ${all.joinToString { it.name }}"))
    }

    /**
     * Another env's classloader could be had without touching `current`, but the API a template calls looks everything
     * up in `current` (group(), jars), so it would run half in one env and half in the other.
     */
    private fun refuseOtherEnv(template: ScriptTemplate, code: String, env: ScriptEnvironment) {
        val plan = EnvPlan.scan(code)
        require(!EnvPlan.namesEnv(code) || plan.envName == env.name) {
            "Template '${template.name}' asks for env '${plan.envName}' and the current env is '${env.name}'; a background run stays in the current env, so load the template and Run it, or run main.kts in env '${plan.envName}' first"
        }
        val missing = plan.imports.filterNot { holds(env, it) }
        require(missing.isEmpty()) {
            "Template '${template.name}' imports ${missing.joinToString()}, which env '${env.name}' doesn't have, and a background run doesn't change the env under a running script; load the template and Run it once, then it is there"
        }
    }

    private suspend fun execute(run: TemplateRun, code: String, parent: ScriptLogger?) {
        run.note("started in the background")
        // The run itself, and the runs that hold it, are not open sessions it left behind: it is ending
        val meter = RunMeter(owner = run, openSessions = { openSessionNames() - run.chain.map { it.sessionName }.toSet() }).start()
        run.meter = meter
        val compiled = compiledTemplates
        // A typed line goes to the console's interpreter as it was typed, after the header it was given once
        val typedLine = run.typed && compiled == null
        val assembled = if (typedLine) code else assembleScript(code, providedAmie = compiled != null)
        val outcome = try {
            val loader = run.env.classLoader(ScriptEngineCache.classLoader)
            if (typedLine) {
                ConsoleSession.shown(ConsoleSession.eval(run, code, scope))?.let { run.say(it) }
            } else if (compiled != null) {
                // A compiled bundle: the template's class, from the bundle's scripts.jar, run as main is
                val scriptClass = compiled(run.file.removeSuffix(".kts")) ?: error("template '${run.name}' was not compiled into this bundle")
                val logger = BackgroundLogger(parent ?: SandboxLogger(run.env, null, scope), run)
                runInterruptible { run.working { withContextClassLoader(scriptClass.classLoader) { ScriptRunner.instantiate(scriptClass, logger) } } }
            } else {
                val logger = BackgroundLogger(parent ?: SandboxLogger(run.env, ScriptEngineCache.gradleEngine, scope), run)
                // One engine for one run, made as ScriptEngineCache.engineFor makes one, under the env's classloader
                val engine = withContextClassLoader(loader) { ScriptEngineManager(loader).getEngineByExtension("kts") } ?: error("Couldn't find Kotlin script engine")
                val bindings = engine.createBindings().apply { put("logger", logger) }
                // The engine is this run's alone, so its own context takes the bindings through both steps
                engine.setBindings(bindings, ScriptContext.ENGINE_SCOPE)
                try {
                    val compiledScript = withContextClassLoader(loader) { (engine as Compilable).compile(assembled) }
                    // A cancel interrupts the thread from here on, which ends a blocked input() and a sleep
                    runInterruptible { run.working { withContextClassLoader(loader) { compiledScript.eval() } } }
                } finally {
                    releaseEngine(bindings)
                }
            }
            run.note("finished in ${run.took().short()}")
            TemplateState.FINISHED
        } catch (e: Throwable) {
            // Whatever a cancelled script ended with (the engine wraps what it throws) is the cancel, not a failure
            if (run.stopping) TemplateState.CANCELLED.also { run.say("cancelled after ${run.took().short()}") }
            else failure(run, e, code, assembled, if (typedLine) emptyList() else movedImports(code))
        }
        run.started.closeDelegated()
        meter.stop().lines().forEach { run.note(it) }
        run.meter = null
        run.end(outcome)
    }

    /** What the engine holds is the compiler environment it keeps in [bindings]: disposed here, not at some later GC */
    internal fun releaseEngine(bindings: Bindings) {
        (bindings[KOTLIN_SCRIPT_STATE_BINDINGS_KEY] as? IReplStageState<*>)?.dispose()
    }

    /** Each thing it got wrong as an error line naming the template's own line, as a failed main.kts is told */
    private fun failure(run: TemplateRun, e: Throwable, code: String, assembled: String, moved: List<MovedImport>): TemplateState {
        val problems = ScriptErrors.problems(e, ScriptErrors.headerLines(code, assembled), code.lines().size, moved)
        problems.forEach { run.say(it.textIn(run.file), it.level) }
        run.note("failed after ${run.took().short()}", LogLevel.ERROR)
        return TemplateState.failed(ScriptErrors.summary(problems, run.file))
    }

    /** Drops what ended long ago and tells the panes how the runs stand now */
    @Synchronized
    internal fun publish() {
        val ended = runs.filter { !it.state.running }.sortedByDescending { it.endedAt }.take(ENDED_SHOWN)
        runs = runs.filter { it.state.running || it in ended }
        // What runs first, the oldest on top, then what ended
        shownRuns.value = runs.sortedBy { !it.state.running }.map { RunShown(it, it.state, it.stopping) }
    }
}
