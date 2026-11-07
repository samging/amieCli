package org.example.project.components.terminal.window.controller.console

import kotlinx.coroutines.flow.update
import org.example.project.components.terminal.window.controller.modules.OrtScopes
import org.example.project.components.terminal.window.controller.runtime.ScriptRuns
import org.example.project.components.terminal.window.controller.util.bytes
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.ResponseDto
import org.example.project.components.terminal.window.controller.sandbox.SCRIPT_SOURCE
import org.example.project.components.terminal.window.controller.script.FreeMemory
import org.example.project.components.terminal.window.controller.script.TemplateRun
import org.example.project.components.terminal.window.controller.script.TemplateRuns
import org.example.project.components.terminal.window.controller.script.consoleErrorAsks
import java.time.Instant

/**
 * Halts the runs in the background when the heap runs short while they go, not only at their start
 * ([TemplateRuns.memoryShort] is the start's check). A thread of its own looks once a second, but only while a template
 * runs: it begins with the first and ends with the last. A reading is a few calls on the runtime. When the free heap is
 * under [limitBytes] for [checks] readings in a row, the heap is collected and read once more, since garbage makes a heap
 * look fuller than it is; if it is still short, the newest template is cancelled, with a console line that says which and
 * why. One at a time: the next waits until that one has ended and the free heap has stayed short again for [checks]
 * readings, so the memory a run gives back is seen before another goes. main.kts is never cancelled; while memory is
 * short and it goes, it gets a warning line, once for each time memory runs short.
 *
 * Before any of that, what nothing uses goes: the oldest idle session of a timed or limited scope, one at a time on the
 * same clock, each with a line ([OrtScopes.freeOldestIdle]). So the watch also runs while such a scope holds something,
 * and it reads the machine's RAM too, where ONNX Runtime keeps a model: RAM under [ramLimitBytes] frees sessions and
 * never stops a template. RAM is looked at once per [calmMs] at most.
 */
internal object MemoryWatch {
    /** What the free heap must stay over; half of what a run needs to start, so a heap that merely sits near the start's limit isn't a halt */
    @Volatile var limitBytes = TemplateRuns.RUN_HEAP_BYTES / 2

    /** What the machine's free RAM must stay over: about one encoder, what opening the next model would take */
    @Volatile var ramLimitBytes = 512L shl 20

    /** The wait between two readings */
    @Volatile var intervalMs = 1_000L

    /** How many readings in a row are short before a run is stopped */
    @Volatile var checks = 3

    /** How long a cancelled run that is busy gets to end before the next is looked at */
    @Volatile var endWaitMs = 15_000L

    /** How long after a reading that was short only for garbage the watcher takes no more, so it doesn't collect the heap every few seconds */
    @Volatile var calmMs = 10_000L

    private var thread: Thread? = null

    /** Called when a run starts, or a scope that yields to memory pressure holds something: the watch begins if it isn't there */
    @Synchronized
    fun ensureRunning() {
        if (thread?.isAlive == true) return
        thread = Thread(::watch, "amie-memory-watch").apply { isDaemon = true; start() }
    }

    /** Whether the thread is there now */
    val watching: Boolean get() = synchronized(this) { thread?.isAlive == true }

    // Read without TemplateRuns' lock, which a starting run holds while it asks for this lock in ensureRunning
    private fun templatesGo(): Boolean = TemplateRuns.shown.value.any { it.state.running }

    private fun heapShort(free: FreeMemory) = free.heapBytes < limitBytes

    private fun ramShort(free: FreeMemory) = free.machineBytes?.let { it < ramLimitBytes } == true

    private fun nothingToWatch(episode: Boolean): Boolean {
        if (templatesGo() || OrtScopes.holdsYielding() || (episode && ScriptRuns.all.any { it.running })) return false
        // A run that started meanwhile has published itself before it asks for the watch, so it is seen here, under the lock ensureRunning takes
        return synchronized(this) {
            val over = !templatesGo() && !OrtScopes.holdsYielding() && !(episode && ScriptRuns.all.any { it.running })
            if (over) thread = null
            over
        }
    }

    private fun watch() {
        var low = 0
        var episode = false
        var warned = false
        var calmUntil = 0L
        var nextRamLook = 0L
        var pending: TemplateRun? = null
        var pendingSince = 0L
        while (true) {
            try {
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                synchronized(this) { thread = null }
                return
            }
            if (nothingToWatch(episode)) return
            val now = System.currentTimeMillis()
            if (now < calmUntil) continue
            // A run that was cancelled is given time to end and give its memory back, before the next is looked at
            if (pending?.state?.running == true && now - pendingSince < endWaitMs) continue
            pending = null
            val free = runCatching { TemplateRuns.freeMemory(false) }.getOrNull() ?: continue
            if (!heapShort(free)) {
                low = 0
                episode = false
                warned = false
                // A Mac's free figure is low whatever it could give, so this says only that the settled reading, a process forked there, is worth it
                if (ramShort(free) && now >= nextRamLook) {
                    nextRamLook = now + calmMs
                    runCatching { TemplateRuns.freeMemory(true) }.getOrNull()?.takeIf(::ramShort)?.let { OrtScopes.freeOldestIdle("only ${bytes(it.machineBytes ?: 0)} of RAM is free") }
                }
                continue
            }
            if (++low < checks) continue
            val reading = runCatching { TemplateRuns.freeMemory(true) }.getOrNull() ?: continue
            val settled = reading.heapBytes
            low = 0
            if (!heapShort(reading)) {
                // Only garbage filled it
                calmUntil = now + calmMs
                episode = false
                warned = false
                continue
            }
            // What nothing uses goes before anything that runs
            if (OrtScopes.freeOldestIdle("only ${bytes(settled.coerceAtLeast(0))} of heap is free")) continue
            episode = true
            val victim = TemplateRuns.active.filter { !it.stopping }.maxByOrNull { it.id }
            val short = "only ${bytes(settled.coerceAtLeast(0))} of heap is free, under the ${bytes(limitBytes)} the templates in the background need to go on"
            if (victim != null) {
                val going = TemplateRuns.active.size
                victim.say(
                    "stopped, because $short. It is the newest of $going" + (if (going > 1) "; if memory stays short the next newest is stopped too" else ""),
                    LogLevel.ERROR,
                )
                consoleErrorAsks.update { it + 1 }
                victim.cancel()
                pending = victim
                pendingSince = now
            }
            if (!warned && ScriptRuns.all.any { it.running }) {
                warned = true
                logToConsole(ResponseDto(
                    time = Instant.now(),
                    message = "Memory is short: $short. main.kts is not stopped for it; stop it, or close sessions, if it runs out",
                    level = LogLevel.ERROR,
                    source = SCRIPT_SOURCE,
                ))
                consoleErrorAsks.update { it + 1 }
            }
        }
    }
}
