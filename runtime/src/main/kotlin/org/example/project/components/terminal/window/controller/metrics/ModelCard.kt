package org.example.project.components.terminal.window.controller.metrics

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.models.mixUps
import org.example.project.components.terminal.window.controller.sourceFile.DataSource
import org.example.project.components.terminal.window.controller.modules.MetricsRun
import org.example.project.components.terminal.window.controller.modules.ModelVersions
import org.example.project.components.terminal.window.controller.modules.ModuleManifest
import org.example.project.components.terminal.window.controller.modules.SCRIPT_ROWS
import org.example.project.components.terminal.window.controller.modules.ScoreRange
import org.example.project.components.terminal.window.controller.modules.guideFile
import org.example.project.components.terminal.window.controller.modules.readMetricsHistory
import org.example.project.components.terminal.window.controller.modules.readStoredManifest
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The model card in a model's guide.md, for whoever opens the guide and whoever downloads the model (the guide goes
 * with it): its version, what it learned from with the rows per label, its earlier versions, the last run's scores and
 * how far they hold, its calibration, the labels it mixes up and whether it keeps texts. Written between two marker
 * lines after every run of the model (appendMetrics: a build, fine-tune, calibration, validation or rollback) and when
 * the guide is saved on the page; what is outside them is never changed. A guide without the markers gets the card at
 * its end; one whose markers don't pair up is left as it is.
 */
object ModelCard {
    /** The line the card begins with; the Guide tab, like any markdown viewer, doesn't show it */
    const val BEGIN = "<!-- amie:model-card: written again after every run of the model; your own text goes outside these two lines -->"
    /** The line it ends with */
    const val END = "<!-- /amie:model-card -->"
    // A begin line is found by its start, so a reworded one still counts
    private const val BEGIN_TAG = "<!-- amie:model-card"
    // Labels named in a line of rows per label; the rest are counted
    private const val LABELS_SHOWN = 12

    /** What a new guide holds between the markers until the model's first run writes the card */
    val placeholder: List<String> = listOf("## Model card", "", "- Written after the model's first run")

    // One lock per guide: a run writes the card while the page may be saving what was typed, a fine-tune adding its
    // labels or a rename its title, and none may undo another
    private val locks = ConcurrentHashMap<String, Any>()

    /** [block] holding [dir]'s guide.md, as every write of it does */
    internal fun <T> locked(dir: File, block: () -> T): T =
        synchronized(locks.computeIfAbsent(guideFile(dir).absoluteFile.normalize().path.lowercase()) { Any() }, block)

    /** Writes [text] as [dir]'s guide.md as it was typed (the Guide tab's Save), never in the middle of a [refresh] */
    fun write(dir: File, text: String) = locked(dir) { guideFile(dir).writeText(text) }

    /**
     * The card's lines, between the markers: a heading, then a line for each fact. [runs] are the model's runs, the
     * last one the latest; [earlier] its versions before the current one. The rows per label it learned from are the
     * ones its manifest kept; [rows] counts a file by name for a model from before they were kept (null when that file
     * isn't on this machine or can't be read)
     */
    fun lines(manifest: ModuleManifest, runs: List<MetricsRun>, earlier: List<ModelVersions.Version>, rows: (String) -> Map<String, Int>?): List<String> = buildList {
        add("## Model card")
        add("")
        add("- Version: v${ModelVersions.numberOf(manifest)}, ${made(manifest.kind ?: "build", manifest.parentVersion)}, ${manifest.createdAt.take(10)}")
        add("- Data: " + when {
            manifest.data != null -> learned(manifest.data, manifest.dataRows ?: rows(manifest.data), manifest.labels)
            manifest.dataRows != null -> learned(SCRIPT_ROWS, manifest.dataRows, manifest.labels)
            else -> "rows a script gave" + (manifest.trainRows?.let { ", $it trained on" } ?: "")
        })
        if (manifest.fineTunedOn.isNotEmpty()) add("- Fine-tuned on: " + manifest.fineTunedOn.mapIndexed { i, name ->
            learned(name, manifest.fineTunedRows.getOrNull(i) ?: if (name == SCRIPT_ROWS) null else rows(name), manifest.labels)
        }.joinToString("; "))
        if (earlier.isNotEmpty()) add("- Earlier versions: " + earlier.joinToString(", ") { "v${it.number} (${made(it.kind, it.parent)})" })
        val run = runs.lastOrNull()
        if (run == null) add("- Last run: none yet")
        else {
            val leakage = run.leakage?.takeIf { it.repeated > 0 }
            add("- Last run: ${run.kind ?: "run"}" + (run.version?.let { " of v$it" } ?: "") + ", ${run.createdAt.take(10)}, on ${run.validationSize} rows (${run.validation}): " +
                "accuracy ${score(run.accuracy, run.accuracyRange)}, macro F1 ${score(run.macroF1, run.macroF1Range)}" +
                (leakage?.let { "; ${it.repeated} of its ${it.checked} rows repeat a row it learned from, which flatters these scores" } ?: ""))
        }
        val ece = run?.analysis?.ece?.let { "; calibration error ${"%.3f".format(Locale.US, it)} on the last run" } ?: ""
        add("- Calibration: " + (manifest.temperature?.takeIf { it != 1.0 }?.let { "temperature ${"%.2f".format(Locale.US, it)}, folded into its head" } ?: "none") + ece)
        if (run != null) {
            val mixed = mixUps(run).take(3)
            add("- Mixes up: " + if (mixed.isEmpty()) "no two labels twice or more on the last run"
                else mixed.mapIndexed { i, m -> (if (i == 0) m.words else "“${m.truth}” for “${m.answered}”") + " ${m.count}×" }.joinToString(", "))
        }
        add("- Texts: " + if (manifest.keepTexts) "kept; its logs, runs and queue show the texts it is asked and gets wrong"
            else "none kept; it answers other people, so its logs give a text's length and its runs only counts")
    }

    /**
     * [guide] with [card] between its markers in place of what was there, or after its text when it has none; null
     * when its markers don't pair up (one without the other, or more than one card), as then where the card ends can't
     * be told from text someone wrote
     */
    fun render(guide: String, card: List<String>): String? {
        val lines = guide.split("\n")
        val begins = lines.indices.filter { lines[it].trim().startsWith(BEGIN_TAG) }
        val ends = lines.indices.filter { lines[it].trim() == END }
        val block = listOf(BEGIN) + card + END
        return when {
            begins.isEmpty() && ends.isEmpty() -> when {
                guide.isBlank() -> block.joinToString("\n", postfix = "\n")
                else -> guide + (if (guide.endsWith("\n")) "\n" else "\n\n") + block.joinToString("\n", postfix = "\n")
            }
            begins.size == 1 && ends.size == 1 && begins[0] < ends[0] ->
                (lines.subList(0, begins[0]) + block + lines.subList(ends[0] + 1, lines.size)).joinToString("\n")
            else -> null
        }
    }

    /**
     * The card in [dir]'s guide.md written again from what the model and its runs say now; false when there is no
     * guide or model there, or the guide's markers don't pair up (said in the console). The guide is written only
     * when the card changed
     */
    fun refresh(dir: File): Boolean = locked(dir) { refreshLocked(dir) }

    private fun refreshLocked(dir: File): Boolean {
        val guide = guideFile(dir).takeIf { it.isFile } ?: return false
        val manifest = runCatching { readStoredManifest(dir) }.getOrNull() ?: return false
        val text = guide.readText()
        val card = lines(manifest, readMetricsHistory(dir).runs, ModelVersions.earlier(dir, ModelVersions.numberOf(manifest))) { name ->
            DataSource.locate(name)?.let { DataSource.quietSamples(it) }?.groupingBy { it.label }?.eachCount()
        }
        val next = render(text, card) ?: run {
            logInternal("guide: ${manifest.name}'s guide.md has the model card's marker lines out of pairs, so the card was left as it is; keep both lines or take both out", LogLevel.ERROR)
            return false
        }
        if (next != text) guide.writeText(next)
        return true
    }

    /** "fine-tune of v1", "v1 brought back", "build": what made a version, as the versions list says it */
    private fun made(kind: String, parent: Int?): String = when (kind) {
        "fine-tune", "calibration" -> kind + (parent?.let { " of v$it" } ?: "")
        "rollback" -> "v${parent ?: "?"} brought back"
        else -> kind
    }

    /**
     * "data.csv, 45 rows (greeting 15, lights 15, weather 15)", in the order of the model's labels; "10 rows a script
     * gave (music 10)" for [SCRIPT_ROWS]
     */
    private fun learned(name: String, rows: Map<String, Int>?, labels: List<String>): String {
        val script = name == SCRIPT_ROWS
        if (rows == null) return if (script) "rows a script gave" else "$name (not on this machine)"
        val ordered = labels.filter { it in rows } + rows.keys.filter { it !in labels }.sorted()
        val named = ordered.take(LABELS_SHOWN).joinToString(", ") { "$it ${rows[it]}" } + if (ordered.size > LABELS_SHOWN) " and ${ordered.size - LABELS_SHOWN} more" else ""
        return if (script) "${rows.values.sum()} rows a script gave ($named)" else "$name, ${rows.values.sum()} rows ($named)"
    }

    /** "0.933 (0.880–0.970)", the range only when its resamples didn't all score the same */
    private fun score(value: Double, range: ScoreRange?): String =
        "%.3f".format(Locale.US, value) + (range?.takeIf { it.spread }?.let { " (${"%.3f".format(Locale.US, it.low)}–${"%.3f".format(Locale.US, it.high)})" } ?: "")
}
