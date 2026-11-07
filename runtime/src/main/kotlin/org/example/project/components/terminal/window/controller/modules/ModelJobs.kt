package org.example.project.components.terminal.window.controller.modules

import org.example.project.components.terminal.integrations.amiePilot.ModelAnswers
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.script.StartedRuns
import org.example.project.components.terminal.window.controller.script.TemplateRun
import org.example.project.components.terminal.window.controller.script.TemplateRuns
import org.example.project.components.terminal.window.controller.script.TemplateState
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackageSession
import org.example.project.components.terminal.window.controller.runtime.ModelSession
import org.example.project.components.terminal.window.controller.runtime.ScriptRuns
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One answer asked of a model in the background, what `model.inBackground("text")` gives a script. It waits its turn in
 * [ModelJobs], then asks the model on a thread of its own, through the same calls a script makes, so the session counts
 * and logs it like any other.
 *
 * A cancel interrupts that thread, which is what Stop does to a script: an ONNX Runtime run is told to terminate and
 * waited for before anything it reads is freed ([org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions.background]),
 * so a job is over only when its native run is.
 *
 * It ends with whoever started it, however that ends, since nobody is left to read its answer; with the ortClosable
 * block it was started in, which waits for it before closing what the block opened; and with the model it asks.
 */
class ModelJob internal constructor(
    val id: Int,
    /** The model it asks */
    val name: String,
    /** The session, swarm or package it asks: what ends it when that closes, and what memory pressure leaves alone meanwhile */
    internal val on: Any,
    /** The runs of whoever started it, null when no script did */
    internal val owner: StartedRuns?,
    ask: () -> List<String>,
) {
    val askedAt: Instant = Instant.now()

    private val ended = CountDownLatch(1)

    // Under this object's lock; let go of with the end, since a job a script keeps shouldn't keep the text too
    private var question: (() -> List<String>)? = ask
    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var answers: List<String> = emptyList()

    /** Queued, running, finished, failed with the reason, or cancelled */
    @Volatile var state: TemplateState = TemplateState.QUEUED
        private set

    /** Whether it has ended, however it did */
    val done: Boolean get() = ended.count == 0L

    /** Waits for the end; every raw answer as it came. Throws when it failed or was cancelled, saying so */
    fun await(): List<String> {
        ended.await()
        return outcome()
    }

    /** The same, waiting [timeoutMillis] at most; null when it hasn't ended by then */
    fun await(timeoutMillis: Long): List<String>? = if (ended.await(timeoutMillis, TimeUnit.MILLISECONDS)) outcome() else null

    /** Takes it out of the queue, or stops it where it is. Never waits, and a job that ended stays as it ended */
    fun cancel() {
        if (ModelJobs.leave(this)) return finish(TemplateState.CANCELLED)
        synchronized(this) {
            stopRequested = true
            worker?.interrupt()
        }
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

    /** On the thread [ModelJobs] gives it */
    internal fun work() {
        val ask = synchronized(this) {
            if (stopRequested) null
            else question.also {
                worker = Thread.currentThread()
                state = TemplateState.RUNNING
            }
        } ?: return finish(TemplateState.CANCELLED)
        val outcome = try {
            answers = ask()
            TemplateState.FINISHED
        } catch (e: Throwable) {
            if (e is CancellationException) TemplateState.CANCELLED else TemplateState.failed(e.message ?: e.toString())
        }
        // The thread goes back to the pool: a cancel from here on can't reach it, and one that came late leaves no interrupt on it
        val stopped = synchronized(this) {
            worker = null
            Thread.interrupted()
            stopRequested
        }
        finish(if (stopped) TemplateState.CANCELLED else outcome)
    }

    private fun finish(outcome: TemplateState) {
        synchronized(this) { question = null }
        state = outcome
        ended.countDown()
        // Without the reason, which may quote the text of a model that keeps none: its session's log has it
        if (outcome.failed) logInternal("model job #$id on $name failed; its await() says why", LogLevel.ERROR)
    }

    private fun outcome(): List<String> = when {
        state.failed -> throw IllegalStateException("The background answer of $name failed: ${state.reason}")
        state == TemplateState.CANCELLED -> throw CancellationException("The background answer of $name was cancelled")
        else -> answers
    }

    override fun toString(): String = "#$id $name $state"
}

/**
 * What runs in the background on the models: at most [limit] answers at once, the rest waiting first in, first out, so
 * background work never takes the whole machine. A call a script makes itself doesn't come here.
 */
object ModelJobs {
    /** How many jobs get a row on the Sessions tab: a script can ask for thousands, and the tab draws every row */
    internal const val LISTED = 100

    private val ids = AtomicInteger()
    private val threads = AtomicInteger()
    private val pool = Executors.newCachedThreadPool { Thread(it, "amie-model-job-${threads.incrementAndGet()}").apply { isDaemon = true } }
    private val working = LinkedHashSet<ModelJob>()

    // Oldest first, and a cancel takes one out of the middle without going through the rest
    private val waiting = LinkedHashSet<ModelJob>()

    /** `-Damie.model.jobs=N`, else half the cores, one at least */
    internal fun limitFor(property: String?, cores: Int): Int = property?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: (cores / 2).coerceAtLeast(1)

    /** How many answers run at once; the tests set it */
    @Volatile internal var limit: Int = limitFor(System.getProperty("amie.model.jobs"), Runtime.getRuntime().availableProcessors())

    /** What runs and what waits, the first to go first */
    val active: List<ModelJob> @Synchronized get() = working.toList() + waiting

    /** [active] as far as the Sessions tab lists it */
    internal val listed: List<ModelJob> @Synchronized get() = (working.asSequence() + waiting.asSequence()).take(LISTED).toList()

    /**
     * Queues [model]'s answer to [text], put down to the script calling and to the block in force. [on] is what closing
     * ends it, when that isn't the model itself
     */
    internal fun start(model: ModelAnswers, text: String, name: String = nameOf(model), on: Any = model): ModelJob {
        val owner = TemplateRuns.onThread.get()?.started ?: ScriptRuns.startedByCaller()
        val job = ModelJob(ids.incrementAndGet(), name, on, owner) { model.run(text) }
        synchronized(this) {
            waiting += job
            dispatch()
        }
        OrtScopes.startedIn(OrtScopes.inForce(), job::endWithBlock)
        // After it is listed, so a stop of whoever started it either sees it or is seen here
        if (owner?.stopped == true) job.cancel()
        return job
    }

    private fun nameOf(model: ModelAnswers): String = when (model) {
        is ModelSession -> model.name
        is ModelSwarm -> model.name
        is JarPackageSession -> model.shownName
        else -> model.javaClass.simpleName.ifEmpty { "model" }
    }

    // Under the lock
    private fun dispatch() {
        while (working.size < limit) {
            val next = waiting.firstOrNull() ?: return
            waiting -= next
            working += next
            pool.execute {
                try {
                    next.work()
                } finally {
                    synchronized(this) {
                        working -= next
                        dispatch()
                    }
                }
            }
        }
    }

    /** Takes [job] out of the queue; false when it isn't there, having started */
    internal fun leave(job: ModelJob): Boolean = synchronized(this) { waiting.remove(job) }

    /** Cancels every job, the ortAsync blocks too, as Terminate all does; how many there were */
    fun cancelAll(): Int = cancelWhere { true } + OrtJobs.cancelAll()

    /** What was started by [owner] ends with it: its answers in the background and its ortAsync blocks */
    internal fun endOf(owner: StartedRuns) {
        cancelWhere { it.owner === owner }
        OrtJobs.endOf(owner)
    }

    /** What asks [on] ends: it is closing */
    internal fun endOn(on: Any) {
        cancelWhere { it.on === on }
    }

    /** The newest first, so an old one that leaves doesn't start a newer one that is about to be cancelled too */
    internal fun cancelWhere(which: (ModelJob) -> Boolean): Int = active.filter(which).asReversed().onEach { it.cancel() }.size

    /** Whether a job is queued or running on [model], or on a swarm with it or on a member of it */
    internal fun touch(model: Any): Boolean = active.any { job -> job.on === model || asks(job.on, model) || asks(model, job.on) }

    private fun asks(swarm: Any, member: Any): Boolean = swarm is ModelSwarm && swarm.models.any { it === member }
}
