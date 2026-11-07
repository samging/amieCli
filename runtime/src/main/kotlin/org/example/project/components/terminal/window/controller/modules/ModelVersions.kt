package org.example.project.components.terminal.window.controller.modules

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.util.localTime
import org.example.project.components.terminal.window.controller.util.localTimeWithOffset
import java.io.File
import java.time.Instant

/**
 * The earlier heads of a model. Every time a model is written again (a build, a fine-tune, a calibration, a
 * rollback) the head it replaces is kept in `AMP/models/intents/<name>/versions/v<n>/`: its model.onnx, its module.json
 * and the metrics run it was last scored with, so a fine-tune that went wrong is one rollback away. At most
 * [KEEP] are kept, the oldest go first. Only the current head is published or served.
 */
object ModelVersions {
    const val KEEP = 10
    const val FOLDER = "versions"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** One head of a model: the current one or a kept one, with what made it and its last scores */
    data class Version(
        val number: Int,
        val current: Boolean,
        /** build, fine-tune, calibration or rollback */
        val kind: String,
        val createdAt: String,
        val labels: List<String>,
        val architecture: String?,
        /** The version it was made from: the head a fine-tune started from, the one a rollback brought back */
        val parent: Int?,
        val accuracy: Double?,
        val macroF1: Double?,
        /** What the run says about it: what it was fine-tuned on, which version came back */
        val note: String?,
        val temperature: Double?,
        val dir: File,
    ) {
        /** "v3 · fine-tune of v2 · 4 labels · accuracy 0.933" */
        fun describe(): String = buildString {
            append("v$number")
            if (current) append(" (current)")
            append(" · ")
            append(when (kind) {
                "fine-tune" -> "fine-tune" + (parent?.let { " of v$it" } ?: "")
                "rollback" -> "v${parent ?: "?"} brought back"
                "calibration" -> "calibration" + (parent?.let { " of v$it" } ?: "")
                else -> kind
            })
            append(" · ${localTime(createdAt, seconds = false)}")
            append(" · ${labels.size} labels")
            accuracy?.let { append(" · accuracy ${"%.3f".format(java.util.Locale.US, it)}") }
            macroF1?.let { append(" · macro F1 ${"%.3f".format(java.util.Locale.US, it)}") }
        }
    }

    fun versionsDir(moduleDir: File): File = File(moduleDir, FOLDER)

    /** The number of the head in [manifest]: its version, 1 for a head written before versions were kept */
    fun numberOf(manifest: ModuleManifest): Int = manifest.version ?: 1

    /** The number the next head of [name] gets: one more than the current head's, 1 for a new model */
    fun next(name: String): Int = if (moduleExists(name)) (readModule(name)?.second?.let(::numberOf) ?: 0) + 1 else 1

    /**
     * Keeps the head in [dir], which is about to be replaced, as versions/v<n>/ (n from [manifest]); then the
     * oldest past [KEEP] are dropped. A failure is a console line: the new head is written either way.
     */
    fun keep(dir: File, manifest: ModuleManifest) {
        val number = numberOf(manifest)
        val target = File(versionsDir(dir), "v$number")
        runCatching {
            val model = File(dir, manifest.model)
            if (!model.isFile) return
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()
            model.copyTo(File(target, manifest.model), overwrite = true)
            File(dir, "module.json").copyTo(File(target, "module.json"), overwrite = true)
            val runs = readMetricsHistory(dir).runs
            (runs.lastOrNull { it.version == number } ?: runs.lastOrNull())?.let { File(target, "run.json").writeText(json.encodeToString(it)) }
            prune(dir)
        }.onFailure { logInternal("module '${manifest.name}': v$number could not be kept in ${target.path}: ${it.message}", LogLevel.ERROR) }
    }

    /** Drops all but the newest [keep] kept versions */
    fun prune(dir: File, keep: Int = KEEP) {
        val kept = keptDirs(dir)
        if (kept.size <= keep) return
        kept.sortedBy { it.first }.dropLast(keep).forEach { (_, d) -> d.deleteRecursively() }
    }

    /** The numbers of the heads kept in [dir]'s versions/, oldest first: a listing only, cheap enough to ask often */
    fun kept(dir: File): List<Int> = keptDirs(dir).map { it.first }.sorted()

    private fun keptDirs(dir: File): List<Pair<Int, File>> =
        versionsDir(dir).listFiles()?.mapNotNull { d ->
            val n = d.name.removePrefix("v").toIntOrNull()
            if (d.isDirectory && d.name.startsWith("v") && n != null && File(d, "module.json").isFile) n to d else null
        } ?: emptyList()

    /** The current head first, then the kept ones, newest first; empty when the model isn't there */
    fun list(name: String): List<Version> {
        if (!moduleExists(name)) return emptyList()
        val (dir, manifest) = readModule(name) ?: return emptyList()
        val run = readMetricsHistory(dir).runs.lastOrNull()
        val current = Version(
            numberOf(manifest), true, manifest.kind ?: "build", manifest.createdAt, manifest.labels, manifest.architecture,
            manifest.parentVersion, run?.accuracy, run?.macroF1, run?.note, manifest.temperature, dir,
        )
        return listOf(current) + earlier(dir, current.number)
    }

    /** The heads kept in the versions/ folder of the model in [dir], newest first, but for its [current] one */
    fun earlier(dir: File, current: Int): List<Version> = keptDirs(dir).mapNotNull { (n, d) ->
        runCatching {
            val m = json.decodeFromString<ModuleManifest>(File(d, "module.json").readText())
            val r = File(d, "run.json").takeIf { it.isFile }?.let { json.decodeFromString<MetricsRun>(it.readText()) }
            Version(n, false, m.kind ?: "build", m.createdAt, m.labels, m.architecture, m.parentVersion, r?.accuracy, r?.macroF1, r?.note, m.temperature, d)
        }.getOrNull()
    }.filter { it.number != current }.sortedByDescending { it.number }

    /**
     * Brings back kept version [number] of [name] as a new version: the current head is kept first (so the
     * rollback can itself be undone), the old head and its labels come back with the description, icon and
     * text policy the model has now, and the run it was scored with is appended again, so the Metrics tab shows
     * the scores of the head that answers. Returns the model file; throws with the reason when it can't.
     */
    fun rollback(name: String, number: Int): File = ModuleLocks.locked(name) { rollbackLocked(name, number) }

    private fun rollbackLocked(name: String, number: Int): File {
        val (dir, current) = readModule(name) ?: throw IllegalArgumentException("model '$name' not found")
        val now = numberOf(current)
        require(number != now) { "v$number is the head '$name' runs now" }
        val source = File(versionsDir(dir), "v$number")
        require(File(source, "module.json").isFile) {
            val kept = keptDirs(dir).map { it.first }.sorted()
            "'$name' has no v$number" + if (kept.isEmpty()) "; it has no earlier versions" else "; kept: ${kept.joinToString { "v$it" }}"
        }
        val old = json.decodeFromString<ModuleManifest>(File(source, "module.json").readText())
        val bytes = File(source, old.model).readBytes()
        val version = now + 1
        val manifest = old.copy(
            name = name,
            description = current.description,
            icon = current.icon,
            keepTexts = current.keepTexts && old.keepTexts,
            version = version,
            kind = "rollback",
            parentVersion = number,
            createdAt = Instant.now().toString(),
        )
        val run = File(source, "run.json").takeIf { it.isFile }?.let { runCatching { json.decodeFromString<MetricsRun>(it.readText()) }.getOrNull() }
        val file = writeModule(manifest, bytes)
        val note = "v$number brought back (made ${localTimeWithOffset(old.createdAt)}), replacing v$now"
        if (run != null) appendMetrics(file.parentFile, run.copy(createdAt = Instant.now().toString(), kind = "rollback", version = version, note = note))
        else {
            logInternal("module '$name': no scores were kept with v$number; validate it to score it again")
            // No run to append, so the card is written again here: it says the version the model is now
            refreshCard(file.parentFile)
        }
        logInternal("module '$name': $note; it is v$version now")
        return file
    }
}
