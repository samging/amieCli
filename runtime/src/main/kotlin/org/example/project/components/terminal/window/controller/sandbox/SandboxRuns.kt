package org.example.project.components.terminal.window.controller.sandbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.short
import org.example.project.components.terminal.window.controller.runtime.RunResources
import org.example.project.getDirectory
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One run of main.kts: when it began (an instant, UTC), how long it took, its first error when it failed and what it used, which older lines don't say */
@Serializable
internal data class SandboxRun(val at: String, val millis: Long, val ok: Boolean, val error: String? = null, val resources: RunResources? = null, val stopped: Boolean = false)

/**
 * The last runs of main.kts, kept in AMP/pluginDir/runs.jsonl, one line per run and the newest last, so they outlast
 * the console (which each run empties) and the app. Only the newest [KEEP] are kept; a line that doesn't read is
 * skipped, and a record that can't be written never fails the run.
 */
internal object SandboxRuns {
    const val KEEP = 20
    private val json = Json { ignoreUnknownKeys = true }
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun file(): File = File(getDirectory().toString(), "pluginDir/runs.jsonl")

    /** The kept runs, the newest first */
    fun read(): List<SandboxRun> = runCatching {
        file().takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(SandboxRun.serializer(), it) }.getOrNull() }
        }
    }.getOrDefault(emptyList()).asReversed().take(KEEP)

    /** Adds [run] after the others, keeping the newest [KEEP] */
    @Synchronized
    fun add(run: SandboxRun) {
        runCatching {
            val kept = (read().asReversed() + run).takeLast(KEEP)
            file().apply { parentFile?.mkdirs() }.writeText(kept.joinToString("") { json.encodeToString(SandboxRun.serializer(), it) + "\n" })
        }
    }

    /** When [run] began on this machine's clock: the time alone for a run of today, with the date for an older one */
    fun began(run: SandboxRun, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
        val at = Instant.parse(run.at).atZone(zone)
        (if (at.toLocalDate() == now.atZone(zone).toLocalDate()) CLOCK else DAY).format(at)
    }.getOrElse { run.at }

    /** How long [run] took, as the run's status says it */
    fun took(run: SandboxRun): String = Duration.ofMillis(run.millis).short()
}

/** The console source of the sandbox's own lines, and of main.kts run there; a run with no window logs under them too */
internal const val SANDBOX_SOURCE = "sandbox"
internal const val SCRIPT_SOURCE = "$SANDBOX_SOURCE/main.kts"
