package org.example.project.components.terminal.window.controller.modules

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.runtime.ScriptRun
import org.example.project.components.terminal.window.controller.runtime.ScriptRuns
import org.example.project.components.terminal.window.controller.script.StartedRuns
import org.example.project.components.terminal.window.controller.script.TemplateRun
import org.example.project.components.terminal.window.controller.script.TemplateRuns
import org.example.project.components.terminal.window.controller.script.TemplateState
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An ortClosable block run in the background, what `ortAsync("name") { }` gives a script. The block runs on a thread
 * of its own inside an ortClosable block of that name, so what it opens is closed when it ends, however it ends, and
 * the templates started in it end with it; [await] gives its value once that is done.
 *
 * The thread works for the script that started it: what the block opens is put down to that run, its log lines go to
 * that run's console, and it starts inside the block in force where it was started, as a template's thread does
 * (OrtScopes.carrying). A cancel interrupts the thread, which is what Stop does to a script.
 *
 * It ends with whoever started it, however that ends, since nobody is left to read its value, and with the
 * ortClosable block it was started in, which waits for it before closing what the block opened.
 */
class OrtJob<T> internal constructor(
    val id: Int,
    /** The name of its block, what its closable scope and its console lines go by */
    val name: String,
    /** The runs of whoever started it, null when no script did */
    internal val owner: StartedRuns?,
) {
    val startedAt: Instant = Instant.now()

    private val ended = CountDownLatch(1)

    // Under this object's lock: the thread while the block runs, null once the block is over and only closing is left
    private var worker: Thread? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var value: Any? = null
    @Volatile private var failure: Throwable? = null

    /** Running, finished, failed with the reason, or cancelled */
    @Volatile var state: TemplateState = TemplateState.RUNNING
        private set

    /** Whether it has ended, however it did, and what its block opened is closed */
    val done: Boolean get() = ended.count == 0L

    /** Waits for the end; the block's value. Rethrows what the block threw, and throws when it was cancelled, saying so */
    fun await(): T {
        notOwnBlock()
        ended.await()
        return outcome()
    }

    /** The same, waiting [timeoutMillis] at most; null when it hasn't ended by then */
    fun await(timeoutMillis: Long): T? {
        notOwnBlock()
        return if (ended.await(timeoutMillis, TimeUnit.MILLISECONDS)) outcome() else null
    }

    /** Stops the block where it is, and what it opened is closed. Never waits, and a job that ended stays as it ended */
    fun cancel() {
        stop()
    }

    /** [cancel]; false when the block was over already, ended or closing what it opened */
    internal fun stop(): Boolean = synchronized(this) {
        if (done || (thread != null && worker == null)) return false
        stopRequested = true
        worker?.interrupt()
        true
    }

    /** The ortClosable block it was started in has ended: what the block closes next is no longer in use once this is back */
    internal fun endWithBlock() {
        cancel()
        try {
            ended.await(TemplateRun.BLOCK_CANCEL_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Starts [block] on its thread, which [carry] ties to the run and the blocks of whoever started it */
    internal fun start(carry: (() -> T) -> T, block: () -> T) {
        val started = Thread({ work(carry, block) }, "amie-ort-async-$id").apply { isDaemon = true }
        synchronized(this) {
            thread = started
            worker = started
        }
        started.start()
    }

    private fun work(carry: (() -> T) -> T, block: () -> T) {
        // Whether a cancel came while the block ran; one that comes while it closes changes nothing
        var stopped = false
        val outcome = try {
            value = carry {
                try {
                    if (stopRequested) throw CancellationException("ortAsync '$name' was cancelled")
                    block()
                } finally {
                    // The block is over: closing what it opened waits for the runs in flight, and no cancel cuts that short
                    stopped = synchronized(this) {
                        worker = null
                        stopRequested
                    }
                }
            }
            if (stopped) TemplateState.CANCELLED else TemplateState.FINISHED
        } catch (e: Throwable) {
            failure = e
            if (stopped || e is CancellationException || e is InterruptedException) TemplateState.CANCELLED
            else TemplateState.failed(e.message ?: e.javaClass.simpleName)
        }
        finish(outcome)
    }

    private fun finish(outcome: TemplateState) {
        synchronized(this) { worker = null }
        state = outcome
        OrtJobs.ended(this)
        ended.countDown()
        // Without the reason, which may quote the text of a model that keeps none: await() rethrows it
        if (outcome.failed) logInternal("ortAsync '$name' #$id failed (${failure?.javaClass?.simpleName}); its await() rethrows it", LogLevel.ERROR)
    }

    // Its own block would wait for good
    private fun notOwnBlock() {
        check(Thread.currentThread() !== thread) { "ortAsync '$name' can't await itself: await() was called inside its own block" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun outcome(): T {
        val thrown = failure
        return when {
            state == TemplateState.CANCELLED -> throw CancellationException("ortAsync '$name' was cancelled").apply { if (thrown != null) initCause(thrown) }
            thrown != null -> throw thrown
            else -> value as T
        }
    }

    override fun toString(): String = "#$id $name $state"
}

/**
 * The ortAsync blocks running now. Each has a thread of its own and nothing waits its turn: a block may ask a model in
 * the background and wait for it, and a queue shared with [ModelJobs] would leave it waiting for its own slot.
 */
object OrtJobs {
    /** The name a block gets when the script gives none, as ortClosable's is "closable" */
    const val DEFAULT_NAME = "async"

    private val ids = AtomicInteger()
    private val running = CopyOnWriteArraySet<OrtJob<*>>()

    /** What runs now, the first started first */
    val active: List<OrtJob<*>> get() = running.toList()

    /**
     * Runs [block] on a thread of its own inside an ortClosable block named [name], and returns at once. The thread
     * works for the script calling: the template run whose thread this is, else the script's run, and it starts inside
     * the block in force here. Put down to that script, and to the ortClosable block around the call
     */
    internal fun <T> start(name: String, block: () -> T): OrtJob<T> {
        val template = TemplateRuns.onThread.get()
        val owner = template?.started ?: ScriptRuns.startedByCaller()
        // A template's thread is its run's; ScriptRuns would take it for whichever script runs alone
        val script: ScriptRun? = if (template == null) ScriptRuns.callingRun() else null
        val scope = OrtScopes.inForce()
        val named = name.trim().ifEmpty { DEFAULT_NAME }
        val job = OrtJob<T>(ids.incrementAndGet(), named, owner)
        running += job
        job.start({ inner ->
            if (template != null) TemplateRuns.onThread.set(template)
            try {
                ScriptRuns.carrying(script) { OrtScopes.carrying(scope) { OrtScopes.closable(named, inner) } }
            } finally {
                TemplateRuns.onThread.remove()
            }
        }, block)
        // After it is listed and going, so the block around it ending either sees it or is seen here
        OrtScopes.startedIn(scope, job::endWithBlock)
        if (owner?.stopped == true) job.cancel()
        return job
    }

    internal fun ended(job: OrtJob<*>) {
        running -= job
    }

    /** What was started by [owner] ends with it, with a line for each that was still going */
    internal fun endOf(owner: StartedRuns) {
        active.filter { it.owner === owner }.asReversed().forEach { job ->
            if (job.stop()) logInternal("ortAsync '${job.name}' #${job.id} ends with the run that started it, so it is cancelled", LogLevel.INFO)
        }
    }

    /** Cancels every one; how many there were */
    fun cancelAll(): Int = active.asReversed().onEach { it.cancel() }.size
}
