package org.example.project.components.terminal.window.controller.modules

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.metrics.AsciiCharts
import org.example.project.components.terminal.window.controller.metrics.ModelCard
import org.example.project.components.terminal.window.controller.metrics.ScoreIntervals
import org.example.project.components.terminal.window.controller.util.localTime
import java.io.File
import java.time.Instant
import java.util.Locale

@Serializable
data class LabelMetrics(val precision: Double, val recall: Double, val f1: Double, val support: Int)

// One validation pass, recorded every time modelBuilder trains a module
@Serializable
data class MetricsRun(
    val createdAt: String = Instant.now().toString(),
    val samples: Int,
    val trainSize: Int,
    val validationSize: Int,
    val accuracy: Double,
    val macroF1: Double,
    val labels: Map<String, LabelMetrics>,
    // confusion[truth][prediction], indexed like ModuleManifest.labels
    val confusion: List<List<Int>>,
    val warnings: List<String> = emptyList(),
    // Related test file the run was validated on, or "split 20%"
    val validation: String = "split 20%",
    // Every head the build tried on the same rows, the chosen one marked; empty for a validation-only run
    val candidates: List<org.example.project.components.terminal.window.controller.jetbrainDLBuilder.CandidateResult> = emptyList(),
    // What the preprocessor did to the rows: the summary, what was read differently, what was dropped and why
    val preprocessing: List<String> = emptyList(),
    // What made the run: build, fine-tune, validation, calibration or rollback; null in runs from before kinds were kept
    val kind: String? = null,
    // The version of the model the run scored (module.json's version), null in runs from before versions were kept
    val version: Int? = null,
    // How far the scores can be trusted, the rows it got wrong and the rows whose label looks wrong
    val analysis: RunAnalysis? = null,
    // A fine-tune or calibration: the head before it, scored on the same rows
    val baseline: Baseline? = null,
    // One sentence about the run when it needs one: what was fine-tuned on, which version came back
    val note: String? = null,
    // Where accuracy and macro F1 fell in the middle 95% of 1,000 resamples of the rows scored (ScoreIntervals); null in
    // runs from before they were kept
    val accuracyRange: ScoreRange? = null,
    val macroF1Range: ScoreRange? = null,
    // The rows scored that repeat a row it learned from (LeakageCheck); null in runs from before they were looked for
    val leakage: Leakage? = null,
)

/**
 * The rows a run was scored on that repeat a row the model learned from, which flatter its scores: of [checked] rows,
 * [exact] are the same text, case and punctuation aside, and [near] a text the encoder can hardly tell from a training
 * row; a few [pairs] only for a model that keeps texts, and the [warning] it gave, also among the run's warnings
 */
@Serializable
data class Leakage(val checked: Int, val exact: Int, val near: Int = 0, val pairs: List<RepeatedRow> = emptyList(), val warning: String? = null) {
    val repeated: Int get() = exact + near
}

/** A scored row and the training row it repeats, with how alike the encoder finds them when it was asked */
@Serializable
data class RepeatedRow(val text: String, val training: String, val similarity: Double? = null)

/** Where a score fell in the middle 95% of the resamples of its rows: from [low] to [high] */
@Serializable
data class ScoreRange(val low: Double, val high: Double) {
    /**
     * Whether it tells anything: when every resample scores the same (every row right, or every row wrong) the
     * resamples can't say how far the score holds, so it is shown as no range at all
     */
    val spread: Boolean get() = high > low
}

/** One of the equal-width bins of the top score: how many answers fell in it, how sure they were and how often right */
@Serializable
data class ReliabilityBin(val from: Double, val to: Double, val count: Int, val confidence: Double, val accuracy: Double)

/** A score gate: answering only when the top score is at least [threshold] keeps [coverage] of the rows, [accuracy] of those right */
@Serializable
data class SelectivePoint(val threshold: Double, val coverage: Double, val accuracy: Double, val kept: Int)

/** A validation row the model got wrong: its label, the answer and that answer's score, and the score its label got */
@Serializable
data class Mistake(val text: String? = null, val label: String, val answered: String, val score: Double, val labelScore: Double)

/** A training row whose label the heads that did not see it doubt: the label it has, the one they gave and how sure they were */
@Serializable
data class Suspect(val text: String? = null, val label: String, val suggested: String, val score: Double, val labelScore: Double)

/**
 * What a run says beyond its scores: the expected calibration error of the top score and its bins, what a score
 * gate would keep, the rows it got wrong (texts only when the model keeps texts) and the training rows that look
 * mislabeled (from cross-validation); with a calibration, the temperature and the error before it
 */
@Serializable
data class RunAnalysis(
    val ece: Double? = null,
    val reliability: List<ReliabilityBin> = emptyList(),
    val selective: List<SelectivePoint> = emptyList(),
    val mistakes: List<Mistake> = emptyList(),
    val suspects: List<Suspect> = emptyList(),
    val temperature: Double? = null,
    val eceBefore: Double? = null,
    // The learning curve of a fine-tune (a build keeps one per head, in its candidates)
    val curve: List<org.example.project.components.terminal.window.controller.jetbrainDLBuilder.CurvePoint> = emptyList(),
)

/** The head before a fine-tune or calibration, scored on the same validation rows as the head after it */
@Serializable
data class Baseline(
    val version: Int? = null,
    val accuracy: Double,
    val macroF1: Double,
    val perLabelF1: Map<String, Double> = emptyMap(),
    val ece: Double? = null,
)

@Serializable
data class MetricsHistory(val runs: List<MetricsRun> = emptyList())

private val metricsJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

private fun metricsFile(dir: File) = File(dir, "metrics.json")

// The last decode of each model's metrics.json, by the size and date of the file: the Models page reads every
// model's history every second or two, and a history grows with every validation, so decoding it each time was
// most of what the page did on an old disk. One entry per model, replaced when the file changes
private val metricsRead = java.util.concurrent.ConcurrentHashMap<String, Pair<String, MetricsHistory>>()

fun readMetricsHistory(dir: File): MetricsHistory {
    val file = metricsFile(dir)
    val stamp = "${file.length()}:${file.lastModified()}"
    metricsRead[file.absolutePath]?.let { (s, history) -> if (s == stamp) return history }
    val history = runCatching { metricsJson.decodeFromString<MetricsHistory>(file.readText()) }.getOrElse { MetricsHistory() }
    metricsRead[file.absolutePath] = stamp to history
    return history
}

/** How many models' histories are kept decoded, for the tests */
internal fun metricsHistoriesCached(): Int = metricsRead.size

// Appends so earlier builds stay comparable; the last run is the current model. Every change of the head and every
// validation adds a run here, so the guide's model card is written again from it (ModelCard)
fun appendMetrics(dir: File, run: MetricsRun) {
    val history = readMetricsHistory(dir)
    metricsFile(dir).writeText(metricsJson.encodeToString(history.copy(runs = history.runs + run)))
    refreshCard(dir)
}

/** The guide's model card written again after a change of the model; a failure is said in the console, never thrown into the change */
internal fun refreshCard(dir: File) {
    runCatching { ModelCard.refresh(dir) }.onFailure { logInternal("guide: the model card wasn't written again: ${it.message ?: it.javaClass.simpleName}", LogLevel.ERROR) }
}

// Per-label precision/recall/F1 from validation predictions; labels index both truth and prediction. With
// [presentOnly], the macro F1 averages only the labels that have rows or answers (a fine-tune checked on the rows of
// one new label is not scored down for the labels it had no rows of); a build averages every label, as it always has.
// With [intervals] (a run that is kept), how far accuracy and macro F1 can be trusted: ScoreIntervals' ranges
fun computeMetrics(labels: List<String>, truth: IntArray, prediction: IntArray, trainSize: Int, warnings: List<String>, presentOnly: Boolean = false, intervals: Boolean = false): MetricsRun {
    val n = labels.size
    val confusion = Array(n) { IntArray(n) }
    truth.indices.forEach { confusion[truth[it]][prediction[it]]++ }

    val perLabel = labels.mapIndexed { i, label -> label to labelMetrics(confusion, i) }.toMap()
    val ranges = if (intervals) ScoreIntervals.of(n, truth, prediction, presentOnly) else null

    return MetricsRun(
        samples = trainSize + truth.size,
        trainSize = trainSize,
        validationSize = truth.size,
        accuracy = if (truth.isEmpty()) 0.0 else truth.indices.count { truth[it] == prediction[it] }.toDouble() / truth.size,
        macroF1 = macroF1Of(confusion, presentOnly),
        labels = perLabel,
        confusion = confusion.map { it.toList() },
        warnings = warnings,
        accuracyRange = ranges?.first,
        macroF1Range = ranges?.second,
    )
}

/** Precision, recall and F1 of label [i] in [confusion] (truth in rows, answers in columns), with its rows */
internal fun labelMetrics(confusion: Array<IntArray>, i: Int): LabelMetrics {
    val n = confusion.size
    val tp = confusion[i][i]
    val fp = (0 until n).sumOf { if (it != i) confusion[it][i] else 0 }
    val fn = (0 until n).sumOf { if (it != i) confusion[i][it] else 0 }
    val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
    val recall = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
    val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
    return LabelMetrics(precision, recall, f1, tp + fn)
}

/** The macro F1 of [confusion]: every label's F1 averaged, with [presentOnly] only those with rows or answers (all when none has) */
internal fun macroF1Of(confusion: Array<IntArray>, presentOnly: Boolean): Double {
    val all = confusion.indices.map { labelMetrics(confusion, it) }
    return (if (presentOnly) all.filterIndexed { i, m -> m.support > 0 || confusion.any { it[i] > 0 } }.ifEmpty { all } else all).map { it.f1 }.average()
}

/**
 * The stored validations of a module in AMP/models/intents/<name>/metrics.json: [run] is the last one (null when the
 * module was never built), [runs] every build in order. print() tabulates, chart(), history() and confusion()
 * draw ASCII charts in the console. Nothing here needs a sign-in: the file is local.
 */
class MetricsReport(val modelName: String, val runs: List<MetricsRun>) {
    constructor(modelName: String, run: MetricsRun?) : this(modelName, listOfNotNull(run))

    /**
     * The level its tables and charts are written at: internal for the report a build prints by itself, the
     * script's own level once a script asks for it (validateMetrics, metricsChart, ...), so amie shows it without -v
     */
    private var level: LogLevel = LogLevel.INTERNAL

    /** This report as a script asked for it: its tables and charts are lines of the script's output */
    fun asked(): MetricsReport = apply { level = LogLevel.INFO }

    val run: MetricsRun? get() = runs.lastOrNull()

    fun precision(label: String): Double? = run?.labels?.get(label)?.precision
    fun recall(label: String): Double? = run?.labels?.get(label)?.recall
    fun f1(label: String): Double? = run?.labels?.get(label)?.f1
    val accuracy: Double? get() = run?.accuracy
    val macroF1: Double? get() = run?.macroF1
    // Where they fell in the middle 95% of resamples of the last run's rows; null for a run from before they were kept
    val accuracyRange: ScoreRange? get() = run?.accuracyRange
    val macroF1Range: ScoreRange? get() = run?.macroF1Range

    // Prints the chosen labels (all when empty) as a table in the console
    fun print(labels: List<String> = emptyList()): MetricsReport {
        val run = run ?: run {
            logInternal("validateMetrics: no metrics stored for '$modelName', build it with modelBuilder first", LogLevel.ERROR)
            return this
        }
        val missing = labels.filter { it !in run.labels }
        if (missing.isNotEmpty()) logInternal("validateMetrics: '$modelName' has no labels $missing (known: ${run.labels.keys})", LogLevel.ERROR)
        val shown = if (labels.isEmpty()) run.labels else run.labels.filterKeys { it in labels }
        val width = maxOf(5, shown.keys.maxOfOrNull { it.length } ?: 0)

        val lines = buildList {
            add("metrics '$modelName' @ ${localTime(run.createdAt)}: ${run.samples} samples (${run.trainSize} train / ${run.validationSize} validation on ${run.validation})")
            add("accuracy ${fmt(run.accuracy)}${run.accuracyRange.said()}  macro F1 ${fmt(run.macroF1)}${run.macroF1Range.said()}" +
                if (run.accuracyRange?.spread == true || run.macroF1Range?.spread == true) "  (ranges: ${ScoreIntervals.said} of its rows)" else "")
            add("${"label".padEnd(width)}  precision  recall  f1     support")
            shown.forEach { (label, m) ->
                add("${label.padEnd(width)}  ${fmt(m.precision).padEnd(9)}  ${fmt(m.recall).padEnd(6)}  ${fmt(m.f1).padEnd(5)}  ${m.support}")
            }
            run.baseline?.let { b ->
                add("before (${b.version?.let { "v$it" } ?: "the head before"}, same rows): accuracy ${fmt(b.accuracy)}  macro F1 ${fmt(b.macroF1)}")
            }
            run.note?.let { add(it) }
            run.warnings.forEach { add("warning: $it") }
        }
        logInternal(lines.joinToString("\n"), level)
        return this
    }

    private fun missing(what: String): MetricsReport {
        logInternal("$what: no metrics stored for '$modelName', build it with modelBuilder first", LogLevel.ERROR)
        return this
    }

    /** Bar chart of the last validation: accuracy, macro F1 and the F1 of every label (or the chosen ones) */
    fun chart(labels: List<String> = emptyList()): MetricsReport {
        val run = run ?: return missing("metricsChart")
        val shown = if (labels.isEmpty()) run.labels else run.labels.filterKeys { it in labels }
        val rows = buildList {
            add(Triple("accuracy", run.accuracy, ""))
            add(Triple("macro F1", run.macroF1, ""))
            shown.forEach { (label, m) ->
                add(Triple(label, m.f1, "p ${fmt(m.precision)}  r ${fmt(m.recall)}  n ${m.support}"))
            }
        }
        val lines = buildList {
            add("metrics '$modelName' · build ${runs.size} of ${runs.size} · ${run.validationSize} validation samples on ${run.validation}")
            add("F1 per label (bar), precision p, recall r, support n")
            addAll(AsciiCharts.bars(rows))
            run.warnings.forEach { add("warning: $it") }
        }
        logInternal(lines.joinToString("\n"), level)
        return this
    }

    /** Line chart of accuracy and macro F1 over every build of the model */
    fun history(): MetricsReport {
        if (runs.isEmpty()) return missing("metricsHistory")
        val lines = buildList {
            add("history '$modelName' · ${runs.size} build${if (runs.size == 1) "" else "s"}")
            addAll(AsciiCharts.series(listOf("accuracy" to runs.map { it.accuracy }, "macro F1" to runs.map { it.macroF1 })))
            runs.forEachIndexed { i, r ->
                add("${(i + 1).toString().padStart(3)}  ${localTime(r.createdAt, seconds = false)}  accuracy ${fmt(r.accuracy)}  macro F1 ${fmt(r.macroF1)}  samples ${r.samples}" +
                    listOfNotNull(r.version?.let { "v$it" }, r.kind).joinToString(" ").let { if (it.isEmpty()) "" else "  $it" })
            }
        }
        logInternal(lines.joinToString("\n"), level)
        return this
    }

    /** The last validation's confusion matrix: truth in rows, prediction in columns */
    fun confusion(): MetricsReport {
        val run = run ?: return missing("confusionMatrix")
        val labels = run.labels.keys.toList()
        val lines = listOf("confusion '$modelName' · ${run.validationSize} validation samples") + AsciiCharts.grid(labels, run.confusion)
        logInternal(lines.joinToString("\n"), level)
        return this
    }

    // A run kept before the analysis was: says so instead of an empty table
    private fun older(what: String, kept: String): MetricsReport {
        logInternal("$what: the last run of '$modelName' $kept (it was made before they were kept); build, fine-tune or validate it again")
        return this
    }

    /** "v3 · fine-tune" when the run knows what made it, else its place among the runs */
    private fun runName(run: MetricsRun): String =
        listOfNotNull(run.version?.let { "v$it" }, run.kind).joinToString(" · ").ifEmpty { "run ${runs.indexOf(run) + 1} of ${runs.size}" }

    private fun quote(text: String?): String = text?.let { "\"" + (if (it.length > 80) it.take(79) + "…" else it) + "\"" } ?: "(text not kept)"

    /** The validation rows the last run got wrong, the surest mistakes first: the row's label, the answer, both scores and the text */
    fun mistakes(): MetricsReport {
        val run = run ?: return missing("mistakes")
        val analysis = run.analysis ?: return older("mistakes", "kept no mistakes")
        val wrong = run.confusion.withIndex().sumOf { (i, row) -> row.withIndex().sumOf { (j, n) -> if (i == j) 0 else n } }
        val kept = analysis.mistakes
        val lines = buildList {
            add("mistakes '$modelName' · ${runName(run)} · $wrong of ${run.validationSize} validation rows answered wrong on ${run.validation}" +
                if (kept.isNotEmpty() && kept.size < wrong) ", the ${kept.size} surest" else "")
            if (kept.isEmpty()) add(if (wrong == 0) "none: every validation row was answered right" else "none kept with the run (keepMistakes = 0)")
            else {
                val lw = maxOf(5, kept.maxOf { it.label.length })
                val aw = maxOf(8, kept.maxOf { it.answered.length })
                add("${"label".padEnd(lw)}  ${"answered".padEnd(aw)}  score  label's  text")
                kept.forEach { m -> add("${m.label.padEnd(lw)}  ${m.answered.padEnd(aw)}  ${fmt(m.score)}  ${fmt(m.labelScore).padEnd(7)}  ${quote(m.text)}") }
                add("score = how sure the answer was, label's = the score the row's own label got")
            }
        }
        logInternal(lines.joinToString("\n"), level)
        return this
    }

    /** The training rows whose label the heads that did not see them doubt (a build with folds), the widest doubts first */
    fun suspects(): MetricsReport {
        val run = run ?: return missing("suspects")
        val folds = run.candidates.firstOrNull { it.chosen }?.folds ?: run.candidates.firstNotNullOfOrNull { it.folds }
        val suspects = run.analysis?.suspects.orEmpty()
        val lines = buildList {
            if (folds == null) {
                add("suspects '$modelName' · ${runName(run)} was not cross-validated; build it with buildOptions(folds = 5) and the training rows whose label the heads doubt are listed here")
                return@buildList
            }
            add("suspects '$modelName' · ${runName(run)} · ${suspects.size} of ${run.trainSize} training rows doubted by heads that didn't see them ($folds folds)")
            if (suspects.isEmpty()) add("none: every row's label was what the heads that didn't see it expected")
            else {
                val lw = maxOf(5, suspects.maxOf { it.label.length })
                val sw = maxOf(9, suspects.maxOf { it.suggested.length })
                add("${"label".padEnd(lw)}  ${"suggested".padEnd(sw)}  score  label's  text")
                suspects.forEach { x -> add("${x.label.padEnd(lw)}  ${x.suggested.padEnd(sw)}  ${fmt(x.score)}  ${fmt(x.labelScore).padEnd(7)}  ${quote(x.text)}") }
                add("a suspect is mislabeled or just hard: fix its label in the data file, or keep it")
            }
        }
        logInternal(lines.joinToString("\n"), level)
        return this
    }

    /** How far the last run's scores can be trusted: how often each score bin was right, the calibration error, and what a score gate keeps */
    fun calibration(): MetricsReport {
        val run = run ?: return missing("calibrationChart")
        val analysis = run.analysis?.takeIf { it.ece != null } ?: return older("calibrationChart", "kept no calibration")
        val lines = buildList {
            add("calibration '$modelName' · ${runName(run)} · ${run.validationSize} validation rows on ${run.validation}")
            add("expected calibration error ${fmt(analysis.ece!!)}" +
                (analysis.eceBefore?.let { " (${fmt(it)} before the temperature ${analysis.temperature?.let(::fmt) ?: "1"} was folded in)" } ?: "") +
                "; 0 means a score of 0.8 is right 80% of the time")
            add("how often right per top score (bar), how sure on average (sure), answers in the bin (n)")
            addAll(AsciiCharts.bars(analysis.reliability.filter { it.count > 0 }.map { b ->
                Triple("${f2(b.from)}–${f2(b.to)}", b.accuracy, "sure ${fmt(b.confidence)}  n ${b.count}")
            }))
            add("answer only from  keeps  right")
            analysis.selective.filter { Math.round(it.threshold * 100).toInt() in GATES }.forEach { p ->
                add("${f2(p.threshold).padEnd(15)}  ${pct(p.coverage).padStart(5)}  ${fmt(p.accuracy)}")
            }
            org.example.project.components.terminal.window.controller.jetbrainDLBuilder.Calibration.suggestGate(analysis.selective)
                ?.takeIf { it.threshold > 0.0 }
                ?.let { add("a gate at ${f2(it.threshold)} keeps ${pct(it.coverage)} of the rows, and ${pct(it.accuracy)} of what it keeps is right") }
        }
        logInternal(lines.joinToString("\n"), level)
        return this
    }

    /** Loss and accuracy by epoch: the chosen head of the last build (every other head in a line), or the last fine-tune */
    fun curves(): MetricsReport {
        val run = run ?: return missing("trainingCurves")
        val chosen = run.candidates.firstOrNull { it.chosen }
        val own = run.analysis?.curve.orEmpty()
        val curve = own.ifEmpty { chosen?.curve.orEmpty() }
        if (curve.isEmpty()) return older("trainingCurves", "kept no learning curves")
        val what = if (own.isNotEmpty()) "the ${run.kind ?: "run"}" else chosen!!.architecture
        val epochs = curve.map { it.epoch.toString() }
        val lossSeries = buildList {
            add("training" to curve.map { it.loss })
            if (curve.all { it.validationLoss != null }) add("validation" to curve.map { it.validationLoss!! })
            if (curve.all { it.monitorLoss != null }) add("early-stop rows" to curve.map { it.monitorLoss!! })
        }
        val top = (Math.ceil(lossSeries.maxOf { (_, v) -> v.max() } * 10) / 10).coerceAtLeast(0.1)
        val lines = buildList {
            add("curves '$modelName' · ${runName(run)} · $what · ${curve.last().epoch} epochs" +
                (chosen?.takeIf { own.isEmpty() }?.let { c -> listOfNotNull(c.stoppedAt?.let { " · stopped at $it" }, c.keptEpoch?.let { " · kept epoch $it" }).joinToString("") } ?: ""))
            add("loss by epoch (lower is better)")
            addAll(AsciiCharts.series(lossSeries, max = top, xLabels = epochs))
            add("accuracy by epoch")
            addAll(AsciiCharts.series(buildList {
                add("training" to curve.map { it.accuracy })
                if (curve.all { it.validationAccuracy != null }) add("validation" to curve.map { it.validationAccuracy!! })
            }, xLabels = epochs))
            if (own.isEmpty()) run.candidates.filter { !it.chosen && it.curve.isNotEmpty() }.forEach { c ->
                val first = c.curve.first()
                val last = c.curve.last()
                val best = c.curve.filter { it.validationLoss != null }.minByOrNull { it.validationLoss!! }
                add("${c.architecture}: loss ${fmt(first.loss)} → ${fmt(last.loss)}" +
                    (if (first.validationLoss != null && last.validationLoss != null) ", validation ${fmt(first.validationLoss)} → ${fmt(last.validationLoss)}" else "") +
                    (best?.let { ", lowest validation loss at epoch ${it.epoch}" } ?: ""))
            }
            add("validation loss rising while training loss falls = memorising: fewer epochs, patience, dropout or weight decay")
        }
        logInternal(lines.joinToString("\n"), level)
        return this
    }

    companion object {
        fun load(modelName: String): MetricsReport {
            val dir = readModule(modelName)?.first
            return MetricsReport(modelName, dir?.let { readMetricsHistory(it).runs } ?: emptyList())
        }
    }
}

private fun fmt(value: Double) = String.format(Locale.US, "%.3f", value)
private fun ScoreRange?.said() = this?.takeIf { it.spread }?.let { " (${fmt(it.low)}–${fmt(it.high)})" } ?: ""
private fun f2(value: Double) = String.format(Locale.US, "%.2f", value)
private fun pct(value: Double) = "${Math.round(value * 100)}%"

// The score gates calibrationChart lists, in hundredths
private val GATES = setOf(0, 50, 60, 70, 80, 90, 95)
