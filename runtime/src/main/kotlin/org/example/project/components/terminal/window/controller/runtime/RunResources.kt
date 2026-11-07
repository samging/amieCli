package org.example.project.components.terminal.window.controller.runtime

import org.example.project.components.terminal.window.controller.script.TemplateRuns
import kotlinx.serialization.Serializable
import org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions
import org.example.project.components.terminal.window.controller.modelLoaders.imported.DjlSessions
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.components.terminal.window.controller.modules.ModelGroups
import org.example.project.components.terminal.window.controller.modules.OrtScopes
import org.example.project.components.terminal.window.controller.util.SystemStats
import org.example.project.components.terminal.window.controller.util.bytes
import org.example.project.components.terminal.window.controller.util.ms
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue

/**
 * What one run of main.kts used. The figures are for the whole app process, since the JVM can't split them per
 * script: what a served model or the rest of the app did during the run is in them too. A figure the platform
 * can't give, or a reading that failed, is null and left out.
 */
@Serializable
internal data class RunResources(
    // What the process held at the end, the most it held during the run and how that end differs from the start
    val residentBytes: Long? = null,
    val residentPeakBytes: Long? = null,
    val residentChangeBytes: Long? = null,
    val heapUsedBytes: Long? = null,
    val heapMaxBytes: Long? = null,
    // CPU time used during the run, on every core together, so it can be more than the run took
    val cpuNanos: Long? = null,
    val ortRuns: Long? = null,
    val ortMillis: Long? = null,
    // ORT sessions opened and closed during the run
    val sessionsOpened: Long? = null,
    val sessionsClosed: Long? = null,
    // Every session open when the run ended, of any kind and whichever run opened it, as "name #id"
    val stillOpen: List<String>? = null,
) {
    /** The line the console prints after the run */
    fun line(): String {
        val memory = listOfNotNull(
            residentBytes?.let { "memory ${bytes(it)}" },
            residentPeakBytes?.let { "peak ${bytes(it)}" },
            residentChangeBytes?.let { change(it) },
        ).joinToString(", ").ifEmpty { null }
        val heap = heapUsedBytes?.let { used -> "heap ${bytes(used)}" + (heapMaxBytes?.let { " of ${bytes(it)}" } ?: "") }
        val cpu = cpuNanos?.let { "cpu ${ms(it / 1e6)}" }
        val ort = ortRuns?.let { runs -> "ort $runs ${if (runs == 1L) "run" else "runs"}" + (ortMillis?.let { " in ${ms(it.toDouble())}" } ?: "") }
        val sessions = listOfNotNull(
            sessionsOpened?.let { "$it opened" },
            sessionsClosed?.let { "$it closed" },
            stillOpen?.let { "${it.size} still open" },
        ).joinToString(", ").ifEmpty { null }?.let { "sessions $it" }
        return "Resources: " + listOfNotNull(memory, heap, cpu, ort, sessions).joinToString(" · ").ifEmpty { "not available" }
    }

    /** What the console prints: [line], then the names of what stayed open when anything did */
    fun lines(): List<String> = listOfNotNull(line(), stillOpen?.takeIf { it.isNotEmpty() }?.let { "Still open: ${it.joinToString()}" })

    /** The line under a run's row on the Sessions tab while it goes: memory now and its peak, the heap, cpu and the ORT runs */
    fun brief(): String = listOfNotNull(
        listOfNotNull(residentBytes?.let { "memory ${bytes(it)}" }, residentPeakBytes?.let { "peak ${bytes(it)}" }).joinToString(", ").ifEmpty { null },
        heapUsedBytes?.let { used -> "heap ${bytes(used)}" + (heapMaxBytes?.let { " of ${bytes(it)}" } ?: "") },
        cpuNanos?.let { "cpu ${ms(it / 1e6)}" },
        ortRuns?.takeIf { it > 0 }?.let { runs -> "ort $runs ${if (runs == 1L) "run" else "runs"}" + (ortMillis?.let { " in ${ms(it.toDouble())}" } ?: "") },
    ).joinToString(" · ")

    /**
     * The line under the console strip, every second while the run goes and then as it ended: memory now and its peak
     * when that is higher, the heap of its limit, the CPU so far and the ONNX Runtime calls so far; what stayed open
     * is the console line's to say
     */
    fun strip(): String = listOfNotNull(
        residentBytes?.let { now -> "memory ${bytes(now)}" + (residentPeakBytes?.takeIf { it > now }?.let { ", peak ${bytes(it)}" } ?: "") },
        heapUsedBytes?.let { used -> "heap ${bytes(used)}" + (heapMaxBytes?.let { " of ${bytes(it)}" } ?: "") },
        cpuNanos?.let { "cpu ${ms(it / 1e6)}" },
        ortRuns?.takeIf { it > 0 }?.let { "ort $it ${if (it == 1L) "run" else "runs"}" },
    ).joinToString(" · ")

    /** The figures a row of the recent runs has room for; [line] says the rest */
    fun short(): String = listOfNotNull(
        residentBytes?.let { "${bytes(it)} memory" },
        cpuNanos?.let { "${ms(it / 1e6)} cpu" },
        ortRuns?.takeIf { it > 0 }?.let { "$it ort ${if (it == 1L) "run" else "runs"}" },
    ).joinToString(" · ")

    private fun change(value: Long): String = when {
        value > 0 -> "${bytes(value)} more than before"
        value < 0 -> "${bytes(value.absoluteValue)} less than before"
        else -> "same as before"
    }
}

/** One reading of the process, what SystemStats and OrtSessions say at a moment; null where the platform can't say */
internal data class RunSample(
    val residentBytes: Long? = null,
    val heapUsedBytes: Long? = null,
    val heapMaxBytes: Long? = null,
    val cpuNanos: Long? = null,
    val ortRuns: Long = 0,
    val ortNanos: Long = 0,
    val sessionsOpened: Long = 0,
    val sessionsClosed: Long = 0,
)

/** The reading of this process; the ORT figures are the counters, which keep what a session did after it closed */
internal fun readRunSample(): RunSample {
    val stats = SystemStats.read()
    return RunSample(
        residentBytes = stats.residentBytes.takeIf { it > 0 },
        heapUsedBytes = stats.heapUsedBytes,
        heapMaxBytes = stats.heapMaxBytes.takeIf { it > 0 },
        cpuNanos = stats.processCpuNanos.takeIf { it >= 0 },
        ortRuns = OrtSessions.totalRuns.get(),
        ortNanos = OrtSessions.totalRunNanos.get(),
        sessionsOpened = OrtSessions.sessionsOpened.get(),
        sessionsClosed = OrtSessions.sessionsClosed.get(),
    )
}

/** Every session open now as the Models page lists them, where ORT sessions named module:<name> belong to a model session */
internal fun openSessionNames(): List<String> {
    fun named(name: String, id: Int, scope: String?) = "$name #$id" + (scope?.let { " ($it)" } ?: "")
    return ModelSessions.all.map { named(it.name, it.id, OrtScopes.scopeOf(it)) } +
        JarPackages.open.map { named(it.jarName, it.id, OrtScopes.scopeOf(it)) } +
        ModelGroups.openSwarms.map { named(it.name, it.id, OrtScopes.scopeOf(it)) } +
        OrtSessions.all.filter { !it.name.startsWith("module:") }.map { named(it.name, it.id, OrtScopes.scopeOfOrt(it)) } +
        DjlSessions.all.map { named("${it.name} (${it.engine})", it.id, OrtScopes.scopeOf(it)) } +
        TemplateRuns.active.map { named(it.name, it.id, "background") }
}

/**
 * Measures one run, on a daemon thread of its own: a reading at the start, one about every [intervalMs] for the peak
 * and one at the end, so a run shorter than an interval still has two. Every reading is taken on that thread, so
 * start() costs the UI thread nothing and a reading that hangs can't hold up the end of a run: stop() waits
 * [stopWaitMs] for it, then gives up on it and interrupts the thread. The open sessions are read once, last, not with
 * every reading: their lists stay locked while a model opens, which is when the peak is worth reading.
 */
internal class RunMeter(
    private val intervalMs: Long = 1_000,
    private val stopWaitMs: Long = 3_000,
    private val sampler: () -> RunSample = ::readRunSample,
    private val openSessions: () -> List<String> = ::openSessionNames,
    private val slowAfterMs: Long = 300_000,
    private val slowIntervalMs: Long = 30_000,
    /** The run this meter is of, a background template's; the sandbox's own run names none */
    val owner: Any? = null,
) {
    private val stopping = CountDownLatch(1)
    private val peak = AtomicLong()
    @Volatile private var first: RunSample? = null
    @Volatile private var last: RunSample? = null
    // The newest reading, whatever it was taken for: what the strip and the Sessions tab show while the run goes
    @Volatile private var latest: RunSample? = null
    @Volatile private var open: List<String>? = null
    private val thread = Thread(::measure, "amie-run-meter").apply { isDaemon = true }

    fun start(): RunMeter = apply {
        live += this
        thread.start()
    }

    /** Tells the thread to end without waiting for it, for a run that ended before it reached stop() */
    fun halt() {
        stopping.countDown()
        live -= this
    }

    /** Ends the measuring and says what the run used; it never throws, since it runs in the run's finally */
    fun stop(): RunResources {
        halt()
        try {
            thread.join(stopWaitMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread.interrupt()
        return resources()
    }

    /** Whether the thread still runs */
    val measuring: Boolean get() = thread.isAlive

    /**
     * What the run has used up to its newest reading, for the strip while it goes: memory now and the peak so far,
     * the CPU since the start. Null before the first reading. The sessions still open are not read here, since
     * their lists stay locked while a model opens
     */
    fun soFar(): RunResources? {
        val now = latest ?: return null
        return resources(first, now, stillOpen = null)
    }

    private fun measure() {
        try {
            first = read()
            val begun = System.nanoTime()
            while (!stopping.await(wait(begun), TimeUnit.MILLISECONDS)) read()
            last = read()
            open = runCatching { openSessions() }.getOrNull()
        } catch (e: InterruptedException) {
            // stop() gave up on a reading; what was read before it stands
        }
    }

    // A script that stays up, such as a gateway, is not worth a ps a second for days: the readings thin out after a while
    private fun wait(begun: Long): Long =
        if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) >= slowAfterMs) slowIntervalMs else intervalMs

    // A reading that fails is left out, whatever it threw; only an interrupt ends the thread
    private fun read(): RunSample? = try {
        sampler().also { sample ->
            sample.residentBytes?.let { held -> peak.accumulateAndGet(held, Math::max) }
            latest = sample
        }
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private fun resources(): RunResources = resources(first, last, open)

    private fun resources(start: RunSample?, end: RunSample?, stillOpen: List<String>?): RunResources {
        fun used(figure: (RunSample) -> Long?): Long? {
            val before = start?.let(figure) ?: return null
            val after = end?.let(figure) ?: return null
            return after - before
        }
        return RunResources(
            residentBytes = end?.residentBytes,
            residentPeakBytes = peak.get().takeIf { it > 0 },
            residentChangeBytes = used { it.residentBytes },
            heapUsedBytes = end?.heapUsedBytes,
            heapMaxBytes = end?.heapMaxBytes,
            cpuNanos = used { it.cpuNanos },
            ortRuns = used { it.ortRuns },
            ortMillis = used { it.ortNanos }?.div(1_000_000),
            sessionsOpened = used { it.sessionsOpened },
            sessionsClosed = used { it.sessionsClosed },
            stillOpen = stillOpen,
        )
    }

    companion object {
        private val live = java.util.concurrent.CopyOnWriteArrayList<RunMeter>()

        /** The meters of the runs going right now: a background template's names it as [owner], the sandbox's own run has none */
        val running: List<RunMeter> get() = live.toList()
    }
}
