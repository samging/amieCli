package org.example.project.components.terminal.window.controller.metrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.Calibration
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.LeakageCheck
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.sourceFile.DataSource
import org.example.project.components.terminal.window.controller.modules.MetricsRun
import org.example.project.components.terminal.window.controller.modules.ModelVersions
import org.example.project.components.terminal.window.controller.modules.appendMetrics
import org.example.project.components.terminal.window.controller.modules.computeMetrics
import org.example.project.components.terminal.window.controller.modules.readModule
import org.example.project.components.terminal.window.controller.runtime.ModelSessions
import java.io.File

/** A validation in progress: which model, how many rows are done */
data class ValidationProgress(val model: String, val done: Int, val total: Int)

/**
 * Validates an exported model on a labeled csv: every row goes through a session of the model, the top label is
 * compared with the row's, and the result is stored as one more run in `AMP/models/intents/<name>/metrics.json`, the
 * same file and shape `modelBuilder` writes, so the Models page and the console charts show it. Rows whose label
 * the model doesn't know are skipped and counted as a warning, as are rows it gives none of its labels for; a label
 * with neither rows nor answers is left out of the macro F1, as a fine-tune leaves it.
 */
object ModelValidation {
    /** The rows it got wrong kept with the run, the surest first */
    private const val MISTAKES = 25

    private val _current = MutableStateFlow<ValidationProgress?>(null)
    val current: StateFlow<ValidationProgress?> = _current.asStateFlow()

    fun validate(name: String, data: File): MetricsRun = validate(name, data, ModelSessions::acquire)

    /** [validate], asking what [open] gives for the model's name: its session, and whether this call opened it */
    internal fun validate(name: String, data: File, open: (String) -> Pair<InteractionModel, Boolean>): MetricsRun {
        val (dir, manifest) = readModule(name) ?: throw IllegalArgumentException("Model '$name' not found")
        val source = DataSource.read(data.absolutePath) ?: throw IllegalArgumentException("${data.name} has no text and label columns")
        val labels = manifest.labels
        val samples = source.samples()
        val known = samples.filter { it.label in labels }
        val warnings = mutableListOf<String>()
        if (known.size < samples.size) warnings += "${samples.size - known.size} rows of ${data.name} have labels $name doesn't know (${(samples.map { it.label }.toSet() - labels.toSet()).joinToString()})"
        if (known.isEmpty()) throw IllegalArgumentException("${data.name} has no rows with the labels of $name (${labels.joinToString()})")
        // Rows it learned from flatter the scores; looked for exactly, as the rows give no vectors here
        val leakage = LeakageCheck.learned(manifest, known.map { it.text }, "rows of ${data.name}")
        leakage?.warning?.let { warnings += it }

        // The model's one session; closed when the rows are through only if this validation opened it
        val (session, opened) = open(name)
        val truth = ArrayList<Int>(known.size)
        val prediction = ArrayList<Int>(known.size)
        // Every label's score per row, rebuilt from the answers, for the calibration and the mistakes
        val probabilities = ArrayList<FloatArray>(known.size)
        val scored = ArrayList<String>(known.size)
        try {
            known.forEachIndexed { i, sample ->
                _current.value = ValidationProgress(name, i, known.size)
                val answers = session.inference(sample.text)
                // A best answer that is none of its labels, or no answer, is no label to score: once it counted as the first
                val top = answers.firstOrNull()?.substringBeforeLast(':')?.let(labels::indexOf) ?: -1
                if (top < 0) return@forEachIndexed
                truth += labels.indexOf(sample.label)
                prediction += top
                probabilities += Calibration.fromAnswers(labels, answers)
                scored += sample.text
            }
        } finally {
            if (opened) session.close()
            _current.value = null
        }
        val unanswered = known.size - scored.size
        if (unanswered == known.size) throw IllegalStateException("$name gave none of its labels for any row of ${data.name}")
        if (unanswered > 0) warnings += "$unanswered of ${known.size} rows got none of its labels as an answer and are left out of the scores"
        val texts = scored.takeIf { manifest.keepTexts }
        val analysis = runCatching { Calibration.analyse(labels, probabilities, truth.toIntArray(), texts, MISTAKES) }.getOrNull()
        val run = computeMetrics(labels, truth.toIntArray(), prediction.toIntArray(), trainSize = 0, warnings = warnings, presentOnly = true, intervals = true)
            .copy(validation = data.name, kind = "validation", version = ModelVersions.numberOf(manifest), analysis = analysis, leakage = leakage)
        appendMetrics(dir, run)
        logInternal("validate: '$name' on ${data.name}: ${scored.size} rows, accuracy ${"%.3f".format(run.accuracy)}, macro F1 ${"%.3f".format(run.macroF1)}")
        return run
    }
}
