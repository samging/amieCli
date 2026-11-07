package org.example.project.components.terminal.window.controller.modelLoaders

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Every ORT session in the app: created and run on background threads, listed by print(), cancelled by cancel()/cancelAll().
 * Cancelling terminates in-flight runs before the native session is closed, so a closing session never races a run.
 */
object OrtSessions {
    // Loaded by the first session, not by reading the lists and counters here: a run of a script that uses no model
    // reads them too, and the native library would count as something that script used
    val env: OrtEnvironment by lazy {
        OrtNativeCache.point()
        OrtEnvironment.getEnvironment()
    }

    private val ids = AtomicInteger()
    private val threads = AtomicInteger()
    private val executor = Executors.newCachedThreadPool { Thread(it, "amie-ort-${threads.incrementAndGet()}").apply { isDaemon = true } }
    private val sessions = ConcurrentHashMap<Int, ManagedSession>()

    // Counted over the life of the app, since [all] loses a session when it closes and what it did with it
    internal val sessionsOpened = AtomicLong()
    internal val sessionsClosed = AtomicLong()
    internal val totalRuns = AtomicLong()
    internal val totalRunNanos = AtomicLong()

    val all: List<ManagedSession> get() = sessions.values.sortedBy { it.id }

    /** Opens a session for [path] off the UI thread; [name] is how it shows up and is cancelled */
    fun open(name: String, path: String): ManagedSession = opened(name, path) { env.createSession(path, sessionOptions()) }

    /** The same from the bytes of a model, whatever [path], which only says where they came from, holds by now */
    fun open(name: String, path: String, model: ByteArray): ManagedSession = opened(name, path) { env.createSession(model, sessionOptions()) }

    /**
     * How every session here is opened. ORT gives each session a native thread pool of its own, one thread per core,
     * and by default those threads keep spinning for a while after each run looking for more work: with an encoder,
     * a few heads and a group open that is several pools taking CPU on a machine with four cores, which is what a
     * Raspberry Pi has. Spinning is off, so an idle session costs nothing (an idle encoder went from 8 ms to 2 ms of
     * CPU in two seconds on a Mac, StartupCostTest; a Pi's cores are slower and fewer). The pool keeps one thread per
     * core, since fewer made a text slower (10 to 12 ms with four threads on ten cores), unless `-Damie.ort.threads=N`
     * says how many, which leaves a core for the window on a Pi.
     */
    internal fun sessionOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        if (!tuned) return@apply
        runCatching { addConfigEntry("session.intra_op.allow_spinning", "0") }
        runCatching { addConfigEntry("session.inter_op.allow_spinning", "0") }
        intraOpThreads()?.let { runCatching { setIntraOpNumThreads(it) } }
    }

    /** Off with `-Damie.ort.default=true`, which opens every session with ORT's own defaults, to compare; the tests flip it */
    @Volatile internal var tuned: Boolean = System.getProperty("amie.ort.default") != "true"

    /** How many threads a session's pool gets when `-Damie.ort.threads=N` says so; null leaves ORT's one per core */
    internal fun intraOpThreads(property: String? = System.getProperty("amie.ort.threads")): Int? =
        property?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun opened(name: String, path: String, create: () -> OrtSession): ManagedSession {
        val session = background("open $name", unused = { it.close() }, block = create)
        return ManagedSession(ids.incrementAndGet(), name, path, session).also {
            sessions[it.id] = it
            sessionsOpened.incrementAndGet()
            logInternal("ort: session #${it.id} '$name' opened from $path")
        }
    }

    fun find(name: String): List<ManagedSession> = all.filter { it.name == name || it.id.toString() == name }

    /** Cancels and closes sessions by name (or #id); returns how many */
    fun cancel(name: String): Int = find(name).onEach { it.cancel() }.size.also {
        if (it == 0) logInternal("ort: no session '$name'", LogLevel.ERROR)
    }

    fun cancelAll(): Int = all.onEach { it.cancel() }.size.also { logInternal("ort: cancelled $it sessions") }

    // Internal reader: what each session expects and returns, and whether it is busy
    fun print(): List<String> {
        val lines = buildList {
            add("ort sessions (${sessions.size}):")
            all.forEach { s ->
                add("  #${s.id} ${s.name}  ${if (s.running > 0) "running ${s.running}" else "idle"}  runs ${s.runs}  since ${s.openedAt}")
                add("    path    ${s.path}")
                s.inputs.forEach { (n, info) -> add("    input   $n ${describe(info)}") }
                s.outputs.forEach { (n, info) -> add("    output  $n ${describe(info)}") }
            }
        }
        logInternal(lines.joinToString("\n"))
        return all.map { "#${it.id} ${it.name}" }
    }

    internal fun remove(session: ManagedSession) = sessions.remove(session.id)

    /**
     * Runs ORT work on its own threads; the caller (a script or IO coroutine) waits, the UI thread never does. An
     * interrupted caller still waits, after [stop]: it frees what the work reads as soon as it gets back, and native code
     * reading what is gone takes the whole app down. What the work gives nobody by then goes to [unused]
     */
    internal fun <T> background(what: String, stop: () -> Unit = {}, unused: (T) -> Unit = {}, block: () -> T): T {
        check(!Thread.currentThread().name.startsWith("AWT-EventQueue")) { "ort: '$what' called on the UI thread; run it from a background coroutine" }
        val abandoned = AtomicBoolean()
        val future = executor.submit<T> { if (abandoned.get()) throw CancellationException("ort: '$what' interrupted") else block() }
        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            abandoned.set(true)
            stop()
            waitOut(future)?.let { left -> runCatching { unused(left) } }
            throw CancellationException("ort: '$what' interrupted")
        }
    }

    private fun <T> waitOut(future: Future<T>): T? {
        while (true) {
            try {
                return future.get()
            } catch (e: InterruptedException) {
                continue
            } catch (e: ExecutionException) {
                return null
            }
        }
    }

    private fun describe(info: NodeInfo): String = info.info.toString()
        .replace(Regex("""javaType=\w+,onnxType=ONNX_TENSOR_ELEMENT_DATA_TYPE_"""), "")
}

class ManagedSession internal constructor(val id: Int, val name: String, val path: String, private val session: OrtSession) {
    val openedAt: Instant = Instant.now()
    private val active = ConcurrentHashMap.newKeySet<OrtSession.RunOptions>()
    private val runCount = AtomicInteger()
    private val runNanos = java.util.concurrent.atomic.AtomicLong()
    private val lastRunNanos = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var closed = false
    private val released = AtomicBoolean()

    val running: Int get() = active.size
    val isCancelled: Boolean get() = closed
    val runs: Int get() = runCount.get()
    /** Time spent inside the native session over every run, in milliseconds */
    val runTimeMs: Long get() = runNanos.get() / 1_000_000
    val lastRunMs: Long get() = lastRunNanos.get() / 1_000_000
    val averageRunMs: Double get() = if (runs == 0) 0.0 else runTimeMs.toDouble() / runs

    /** When the last run ended; null before the first */
    @Volatile var lastRunAt: Instant? = null
        private set

    val inputs: Map<String, NodeInfo> get() = session.inputInfo
    val outputs: Map<String, NodeInfo> get() = session.outputInfo
    val inputNames: Set<String> get() = session.inputNames
    val metadata get() = session.metadata

    /** Runs the model in the background; cancel() terminates it and makes this throw CancellationException */
    fun run(inputs: Map<String, OnnxTensor>): OrtSession.Result {
        if (closed) throw CancellationException("ort: session #$id '$name' was cancelled")
        val current = AtomicReference<OrtSession.RunOptions?>()
        return OrtSessions.background("run $name", stop = { current.get()?.let(::terminate) }, unused = { it.close() }) {
            val options = OrtSession.RunOptions()
            current.set(options)
            active += options
            val begun = System.nanoTime()
            try {
                // After joining active: a cancel that set closed before this sees the run and waits for it, or this sees closed
                if (closed) throw CancellationException("ort: session #$id '$name' was cancelled")
                runCount.incrementAndGet()
                OrtSessions.totalRuns.incrementAndGet()
                session.run(inputs, options).also {
                    val took = System.nanoTime() - begun
                    runNanos.addAndGet(took)
                    OrtSessions.totalRunNanos.addAndGet(took)
                    lastRunNanos.set(took)
                    lastRunAt = Instant.now()
                }
            } catch (e: Exception) {
                if (closed) throw CancellationException("ort: run on #$id '$name' cancelled") else throw e
            } finally {
                active -= options
                synchronized(options) { options.close() }
                if (closed && active.isEmpty()) release()
            }
        }
    }

    // Options close with their run, so they are told to stop under their own lock, while they are still there
    private fun terminate(options: OrtSession.RunOptions) = synchronized(options) {
        if (options in active) runCatching { options.setTerminate(true) }
    }

    private fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { session.close() }.onFailure { logInternal("ort: session #$id '$name' could not be closed: ${it.message}", LogLevel.ERROR) }
    }

    @Synchronized
    fun cancel() {
        if (closed) return
        closed = true
        active.forEach(::terminate)
        // Native close must wait for runs to unwind, however long that is: a run past the wait closes it when it ends.
        // A stopped script gets here interrupted, and that may not cut the wait short
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var interrupted = false
        while (active.isNotEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (active.isEmpty()) release()
        else logInternal("ort: session #$id '$name' still runs after the wait, it closes when that run ends")
        OrtSessions.remove(this)
        OrtSessions.sessionsClosed.incrementAndGet()
        logInternal("ort: session #$id '$name' cancelled")
    }

    fun close() = cancel()
}
