package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import org.example.project.components.terminal.window.controller.console.logError
import org.example.project.components.terminal.window.controller.console.logInfo
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modules.Baseline
import org.example.project.components.terminal.window.controller.modules.ModelVersions
import org.example.project.components.terminal.window.controller.modules.appendMetrics
import org.example.project.components.terminal.window.controller.modules.computeMetrics
import org.example.project.components.terminal.window.controller.modules.denseStackToOnnx
import org.example.project.components.terminal.window.controller.modules.moduleExists
import org.example.project.components.terminal.window.controller.modules.readModule
import org.example.project.components.terminal.window.controller.modules.readStoredManifest
import org.example.project.components.terminal.window.controller.modules.writeModuleChecked
import java.io.File
import java.time.Instant

private const val CALIBRATION_SOURCE = "sandbox/modelBuilder"
private fun note(message: String) = logInfo("calibrate: $message", CALIBRATION_SOURCE)
private fun halt(message: String, short: String): File? {
    logError("calibrate: $message", CALIBRATION_SOURCE)
    ModelBuildProgress.failed(short)
    return null
}

/**
 * Makes a trained model's scores mean what they say: one temperature is fitted on labeled rows the model hasn't
 * learned from and folded into its head, so a score of 0.8 is right about 80% of the time. The answers stay the
 * same, so its accuracy does too. The calibrated head is a new version (the one before is kept for a rollback),
 * and the run on these rows shows the calibration error before and after, also on rows the temperature wasn't
 * fitted on. A temperature from an earlier calibration is replaced, not stacked.
 */
fun calibrateClassifier(
    modelName: String,
    samples: List<LabeledText>,
    dataName: String? = null,
    cacheEmbeddings: Boolean = true,
    embedder: Embedder? = null,
): File? {
    ModelBuildProgress.start(modelName, job = "calibrate")
    return try {
        calibratePhases(modelName, samples, dataName, cacheEmbeddings, embedder)
            .also { if (it == null && ModelBuildProgress.current.value != null) ModelBuildProgress.failed("not calibrated") }
    } catch (e: Throwable) {
        ModelBuildProgress.failed(e.message ?: e.javaClass.simpleName)
        throw e
    }
}

private fun calibratePhases(modelName: String, samples: List<LabeledText>, dataName: String?, cacheEmbeddings: Boolean, embedder: Embedder?): File? {
    ModelBuildProgress.phase(BuildPhase.CHECKING, detail = "${samples.size} rows")
    if (!moduleExists(modelName)) return halt("there is no model '$modelName' to calibrate", "no such model")
    val (dir, current) = readModule(modelName) ?: return halt("'$modelName' can't be read", "model can't be read")
    val stored = readStoredManifest(dir)
    val head = runCatching { OnnxHead.read(File(dir, current.model)) }.getOrElse { return halt("'$modelName' can't be calibrated: ${it.message}", "head can't be read") }
    if (head.outputDim != current.labels.size) return halt("'$modelName' can't be calibrated: its head has ${head.outputDim} outputs for ${current.labels.size} labels", "head and manifest disagree")
    if (head.outputActivation == "Sigmoid") note("its head gives sigmoid scores; the calibrated one gives softmax scores, which add up to 1")
    val labels = current.labels
    val byLower = labels.associateBy { it.lowercase() }
    val seen = HashSet<String>()
    val rows = samples.mapNotNull { s -> byLower[s.label.trim().lowercase()]?.let { LabeledText(s.text.trim(), it) } }
        .filter { it.text.isNotEmpty() && seen.add(it.text.lowercase()) }
    val source = dataName ?: "rows from a script"
    if (rows.size < samples.size) note("${samples.size - rows.size} of the ${samples.size} rows were left out: repeated, empty, or of labels '$modelName' doesn't have")
    if (rows.size < Calibration.MIN_ROWS) return halt("'$modelName' was not calibrated: a temperature needs at least ${Calibration.MIN_ROWS} labeled rows, $source has ${rows.size}", "too few rows")
    // Rows the model learned from make it look surer than it is on new texts; past a third of them the warning is
    // about the temperature they give
    val leakage = LeakageCheck.learned(stored, rows.map { it.text }, if (dataName != null) "rows of $dataName" else "rows")?.let { l ->
        if (l.repeated > rows.size / 3) l.copy(warning = "${l.repeated} of the ${rows.size} rows are training rows; the temperature may be too low for new texts") else l
    }
    val overlap = leakage?.repeated ?: 0
    if (overlap > rows.size / 3) note("warning: $overlap of the ${rows.size} rows are rows '$modelName' was trained on; its scores there are surer than on new texts, so calibrate on rows it hasn't seen (a test file) for a temperature that holds")
    else leakage?.warning?.let { note("warning: $it") }

    ModelBuildProgress.phase(BuildPhase.EMBEDDING, steps = rows.size, detail = File(current.encoder).name)
    val embed = embedder ?: EncoderEmbedder(current.encoder, cacheEmbeddings && current.keepTexts)
    val x = embed.embed(rows.map { it.text }) { done, total -> ModelBuildProgress.step(done, "$done of $total texts through ${File(current.encoder).name}") }
    if (x.first().size != head.inputDim) return halt("the encoder gives ${x.first().size} numbers per text, the head of '$modelName' reads ${head.inputDim}", "encoder doesn't fit the head")
    val y = IntArray(rows.size) { labels.indexOf(rows[it].label) }

    ModelBuildProgress.phase(BuildPhase.VALIDATING, steps = rows.size, detail = "fitting a temperature on ${rows.size} rows")
    val served = head.toDenseNet()
    // The temperature is fitted on the head as it was trained, so a calibration replaces an earlier one
    val raw = head.toDenseNet().also { net -> current.temperature?.takeIf { it != 1.0 }?.let { Calibration.fold(net, 1.0 / it) } }
    val logits = x.map { raw.logits(it) }
    val servedProbs = x.map { served.predictProbabilities(it) }
    val (eceServed, _) = Calibration.reliability(servedProbs, y)
    val t = Calibration.fitTemperature(logits, y)

    // How it holds on rows it wasn't fitted on: fitted on one half, measured on the other, and the other way round
    if (rows.size >= 2 * Calibration.MIN_ROWS) {
        val (a, b) = stratifiedSplit(y, 0.5, 7)
        fun heldOut(fit: List<Int>, check: List<Int>): Pair<Double, Double> {
            val tf = Calibration.fitTemperature(fit.map { logits[it] }, IntArray(fit.size) { y[fit[it]] })
            val yc = IntArray(check.size) { y[check[it]] }
            return Calibration.reliability(check.map { servedProbs[it] }, yc).first to Calibration.reliability(check.map { Calibration.probabilities(logits[it], tf) }, yc).first
        }
        if (a.isNotEmpty() && b.isNotEmpty()) {
            val (beforeB, afterB) = heldOut(a, b)
            val (beforeA, afterA) = heldOut(b, a)
            val n = (a.size + b.size).toDouble()
            note("on rows the temperature was not fitted on (two halves), the calibration error goes ${f3((beforeB * b.size + beforeA * a.size) / n)} → ${f3((afterB * b.size + afterA * a.size) / n)}")
        }
    }
    val previous = current.temperature?.takeIf { it != 1.0 }
    if (t == 1.0 && previous == null) {
        note("'$modelName' is calibrated already on $source (calibration error ${f3(eceServed)}): no temperature makes its scores truer, so nothing was written")
        ModelBuildProgress.done("already calibrated · calibration error ${f3(eceServed)}")
        return File(dir, current.model)
    }
    val calibrated = raw.also { Calibration.fold(it, t) }
    val probs = x.mapIndexed { n, v -> calibrated.predictProbabilities(v).also { ModelBuildProgress.step(n + 1) } }
    val prediction = probs.map { DenseNet.argmax(it) }.toIntArray()
    val servedRun = computeMetrics(labels, y, servedProbs.map { DenseNet.argmax(it) }.toIntArray(), 0, emptyList(), presentOnly = true)
    val scored = computeMetrics(labels, y, prediction, 0, emptyList(), presentOnly = true, intervals = true)
    val analysis = Calibration.analyse(labels, probs, y, if (current.keepTexts) rows.map { it.text } else null, 25)
        .copy(temperature = t, eceBefore = eceServed)
    note("temperature ${f3(t)}" + (previous?.let { " (it had ${f3(it)})" } ?: "") + ": the calibration error on $source goes ${f3(eceServed)} → ${f3(analysis.ece ?: 0.0)}; the answers are the same")
    Calibration.describe(analysis).drop(1).forEach { note(it) }

    ModelBuildProgress.phase(BuildPhase.EXPORTING, detail = "AMP/models/intents/$modelName")
    val from = ModelVersions.numberOf(current)
    val version = from + 1
    val run = scored.copy(
        validation = source,
        kind = "calibration",
        version = version,
        analysis = analysis,
        baseline = Baseline(from, servedRun.accuracy, servedRun.macroF1, servedRun.labels.mapValues { it.value.f1 }, eceServed),
        note = "calibrated v$from on $source: temperature ${f3(t)}",
        warnings = listOfNotNull(leakage?.warning),
        leakage = leakage,
    )
    return try {
        val manifest = stored.copy(
            createdAt = Instant.now().toString(),
            version = version,
            kind = "calibration",
            parentVersion = from,
            temperature = t.takeIf { it != 1.0 },
            // Left to writeModule, which keeps the model's as they are when the head is written: edited meanwhile, they stay
            description = null,
            icon = null,
        )
        // Written only if the model still has the head it was calibrated from: renamed, destroyed, rolled back or trained
        // again meanwhile, it isn't written over
        val file = writeModuleChecked(manifest, denseStackToOnnx(denseLayersOf(calibrated), manifest.input, manifest.output, modelName), existed = true, startedFrom = stored) { written ->
            appendMetrics(written.parentFile, run)
        }
        logInternal("module '$modelName' calibrated: v$version (temperature ${f3(t)}) from v$from, calibration error ${f3(eceServed)} → ${f3(analysis.ece ?: 0.0)} on $source")
        ModelBuildProgress.done("calibrated v$from → v$version · temperature ${f3(t)} · calibration error ${f3(eceServed)} → ${f3(analysis.ece ?: 0.0)}")
        file
    } catch (e: Exception) {
        halt("export of '$modelName' failed: ${e.message}", "export failed: ${e.message}")
    }
}
