package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import java.util.Locale
import java.io.File
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.console.logInfo
import org.example.project.components.terminal.window.controller.console.logError
import org.example.project.components.terminal.window.controller.modules.ModuleManifest
import org.example.project.components.terminal.window.controller.modules.denseStackToOnnx
import org.example.project.components.terminal.window.controller.modules.moduleExists
import org.example.project.components.terminal.window.controller.modules.readModule
import org.example.project.components.terminal.window.controller.modules.writeModuleChecked
import org.example.project.components.terminal.window.controller.modules.MetricsReport
import org.example.project.components.terminal.window.controller.modules.ModelVersions
import org.example.project.components.terminal.window.controller.modules.appendMetrics
import org.example.project.components.terminal.window.controller.modules.computeMetrics
import org.example.project.components.terminal.integrations.amiePilot.BundledModels


//sentence -> mean pooling -> dense -> softmax
//down casting the 12 vectors into a 768 vector

fun meanPooling(tensor: Array<Array<FloatArray>>) : FloatArray {
    val contextualSize = tensor.size //batches
    val seqLength = tensor[0].size //sequences
    val hiddenSize = tensor[0][0].size //size of last layer
    val sentenceVector = FloatArray(hiddenSize)
    for (sequenceIdx in 0 until seqLength) {
        for (dim in 0 until hiddenSize) {
            sentenceVector[dim] += tensor[0][sequenceIdx][dim]
        }
    }
    for (dim in 0 until hiddenSize) {
        sentenceVector[dim] = sentenceVector[dim] / seqLength
    }

    return sentenceVector
}

/** The encoder a model is trained on unless the call names one: the bundled bge */
val DEFAULT_ENCODER: String get() = BundledModels.encoder
const val EPOCHS = 150
private const val EPOCH_LOG_EVERY = 25
private const val BUILD_SOURCE = "sandbox/modelBuilder"

/** Lines the person must see: the preprocessing summary, the candidates, the pick, and why a build stopped */
private fun say(message: String) = logInfo("modelBuilder: $message", BUILD_SOURCE)
private fun fail(message: String) = logError("modelBuilder: $message", BUILD_SOURCE)

/**
 * Trains a text classifier on labeled samples and exports it to AMP/models/intents/<modelName>/ (model.onnx, module.json,
 * metrics.json). Returns the exported model file, or null with the reasons in the console.
 *
 * Phases: the [Preprocessor] cleans and checks the data (and refuses what cannot be learned from), every text goes
 * through the encoder once, every candidate head in [options] is trained on the same split and scored on the same
 * validation rows, the best one by [BuildOptions.pickBy] is kept, validated and exported. With [validation] (a
 * related test file) the heads train on all of [samples] and are validated on it instead of a split. Every phase
 * is reported to [ModelBuildProgress], which the sandbox and the Models page show while the build runs.
 *
 * Beyond the defaults, [options] can cross-validate the heads ([BuildOptions.folds]), which also reports the
 * training rows that look mislabeled, and calibrate the chosen one ([BuildOptions.calibrate]); a head can stop
 * early, decay its weights or drop units ([Architecture]). The texts' vectors are kept in the embedding cache, so
 * a build on the same rows again goes straight to training.
 */
fun buildClassifier(
    samples: List<LabeledText>,
    modelName: String,
    encoder: String = DEFAULT_ENCODER,
    validation: List<LabeledText>? = null,
    validationName: String? = null,
    dataName: String? = null,
    description: String? = null,
    options: BuildOptions = BuildOptions(),
    /** Turns the texts into vectors: the encoder (through the embedding cache) unless a test brings its own */
    embedder: Embedder? = null,
): File? {
    ModelBuildProgress.start(modelName)
    return try {
        buildClassifierPhases(samples, modelName, encoder, validation, validationName, dataName, description, options, embedder)
            .also { if (it == null && ModelBuildProgress.current.value != null) ModelBuildProgress.failed("not built") }
    } catch (e: Throwable) {
        ModelBuildProgress.failed(e.message ?: e.javaClass.simpleName)
        throw e
    }
}

private fun buildClassifierPhases(
    rawSamples: List<LabeledText>,
    modelName: String,
    encoder: String,
    validation: List<LabeledText>?,
    validationName: String?,
    dataName: String?,
    description: String?,
    options: BuildOptions,
    embedder: Embedder?,
): File? {
    // Trained again or new: what the name is when the head is written at the end must still be so
    val existed = moduleExists(modelName)
    // ---- 1. the data: cleaned, measured, refused when it cannot be learned from
    ModelBuildProgress.phase(BuildPhase.CHECKING, detail = "${rawSamples.size} rows")
    val prep = if (options.preprocess) Preprocessor.run(rawSamples, keepTexts = options.keepTexts) else {
        val check = checkVariability(rawSamples)
        Preprocessor.Result(rawSamples, emptyList(), listOf("${rawSamples.size} rows, not preprocessed"), check.warnings, check.errors)
    }
    prep.lines(examples = options.keepTexts).forEach { line -> if (line.startsWith("error:")) fail(line.removePrefix("error: ")) else say(line) }
    if (!prep.ok) {
        fail("'$modelName' was not built: the data cannot give a usable classifier (${prep.errors.size} ${if (prep.errors.size == 1) "reason" else "reasons"} above). Fix the rows and run again")
        ModelBuildProgress.failed(prep.errors.first().take(80))
        return null
    }
    val samples = prep.samples
    val labels = samples.map { it.label }.distinct().sorted()
    val warnings = prep.warnings.toMutableList()
    val preprocessing = prep.notes + prep.dropped.groupBy { it.reason }.map { (reason, rows) -> "dropped ${rows.size}: $reason" }

    // Test rows can only be scored on labels the model trains on; they are cleaned the same way, and a test row
    // that is also a training row is left out: it would be scored on what was memorised, not learned
    val trainingTexts = samples.map { it.text.lowercase() }.toSet()
    val testSamples = validation?.let { test ->
        val cleaned = if (options.preprocess) Preprocessor.run(test).samples else test
        val unseen = cleaned.filter { it.text.lowercase() !in trainingTexts }
        if (unseen.size < cleaned.size) warnings += "${cleaned.size - unseen.size} rows of $validationName also appear in the training data; they were left out of the validation, as they would inflate its scores"
        val known = unseen.filter { it.label in labels }
        if (known.size < unseen.size) warnings += "${unseen.size - known.size} rows of $validationName have labels the training data doesn't have"
        known.ifEmpty {
            warnings += "$validationName has no rows with trained labels, falling back to a ${(options.validationFraction * 100).toInt()}% split"
            null
        }
    }
    warnings.drop(prep.warnings.size).forEach { say("warning: $it") }
    // The rows that were dropped, for fixing: AMP/models/intents/<name>/dropped.csv
    if (prep.dropped.isNotEmpty() && options.keepTexts) runCatching {
        val dir = org.example.project.components.terminal.window.controller.modules.moduleDir(modelName).apply { mkdirs() }
        val file = File(dir, "dropped.csv")
        file.writeText(buildString {
            appendLine("text,label,reason")
            prep.dropped.forEach { d -> appendLine(listOf(d.text, d.label, d.reason).joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }) }
        })
        say("the ${prep.dropped.size} dropped rows are in ${file.path}")
    }.onFailure { logInternal("modelBuilder: couldn't write dropped.csv: ${it.message}", LogLevel.ERROR) }

    // ---- 2. every text through the encoder, once; the vectors of texts seen before come from the cache
    val all = samples + testSamples.orEmpty()
    val labelIndex = all.map { labels.indexOf(it.label) }.toIntArray()
    logInternal("modelBuilder: embedding ${all.size} samples over ${labels.size} labels $labels…")
    ModelBuildProgress.phase(BuildPhase.EMBEDDING, steps = all.size, detail = File(encoder).name)
    val embed = embedder ?: EncoderEmbedder(encoder, options.cachesEmbeddings)
    val pooled = embed.embed(all.map { it.text }) { done, total ->
        ModelBuildProgress.step(done, "$done of $total texts through ${File(encoder).name}")
    }
    (embed as? EncoderEmbedder)?.fromCache?.takeIf { it > 0 }?.let {
        logInternal("modelBuilder: $it of ${all.size} texts came from the embedding cache, ${all.size - it} went through ${File(encoder).name}")
    }
    checkEmbeddingSpread(pooled)?.let {
        fail(it)
        fail("'$modelName' was not built: the encoder sees no difference between the texts, so no head can tell the labels apart")
        ModelBuildProgress.failed("texts are too similar to learn from")
        return null
    }
    val inputDim = pooled[0].size.toLong()

    val (trainIdx, validationIdx) =
        if (testSamples != null) samples.indices.toList() to (samples.size until all.size).toList()
        else stratifiedSplit(labelIndex, options.validationFraction, options.seed)
    val trainX = trainIdx.map { pooled[it] }.toTypedArray()
    val trainY = IntArray(trainIdx.size) { labelIndex[trainIdx[it]] }
    val truth = validationIdx.map { i -> labelIndex[i] }.toIntArray()
    Diagnostics.aboutValidation(labels, truth).forEach { warnings += it; say("warning: $it") }
    // Validation rows that repeat a training row flatter the scores: told, and nothing trained changes
    val leakage = LeakageCheck.find(
        trainIdx.map { all[it].text }, validationIdx.map { all[it].text },
        what = if (testSamples != null) "rows of ${validationName ?: "the test set"}" else "validation rows", against = "a training row",
        keepTexts = options.keepTexts, trainingVectors = trainIdx.map { pooled[it] }, vectors = validationIdx.map { pooled[it] },
    )
    leakage.warning?.let { warnings += it; say("warning: $it") }
    if (options.keepTexts && leakage.pairs.isNotEmpty()) say("repeated, e.g. " + leakage.pairs.take(3).joinToString(" · ") { "\"${it.text.take(40)}\" and \"${it.training.take(40)}\"" })
    // Balanced: a row of a rare label weighs more, so the head cannot settle for the frequent labels
    val classWeights = if (options.balanceClasses) {
        balancedWeights(trainY, labels.size)
            .also { w -> say("balancing the labels: weights " + labels.indices.joinToString { "${labels[it]} ${fmt3(w[it].toDouble())}" }) }
    } else null
    say("training on ${trainIdx.size} rows, validating on ${validationIdx.size} (${if (testSamples != null) validationName ?: "test set" else "a ${(options.validationFraction * 100).toInt()}% split"}); ${options.candidates.size} ${if (options.candidates.size == 1) "head" else "heads to compare"}")

    // ---- 3. every candidate head on the same rows, scored on the same validation rows; with folds, each one is
    // also trained once per fold of the training rows and scored on the fold it did not see
    val candidates = options.candidates
    val folds = options.folds
    val foldOf = if (folds >= 2) stratifiedFolds(trainY, folds, options.seed) else null
    if (foldOf != null) say("cross-validating every head over $folds folds of the ${trainIdx.size} training rows before it is trained on all of them")
    val totalEpochs = candidates.sumOf { it.epochs * (1 + if (foldOf != null) folds else 0) }
    ModelBuildProgress.phase(BuildPhase.TRAINING, steps = totalEpochs, detail = "${trainIdx.size} samples")
    // The validation rows are scored along the way for the learning curves; they never change what is learned
    val watchRows = spreadSample(validationIdx, 400)
    val watch = watchRows.map { pooled[it] }.toTypedArray() to IntArray(watchRows.size) { labelIndex[watchRows[it]] }
    var epochsDone = 0
    val trained = candidates.map { arch ->
        val cv = foldOf?.let { assignment ->
            val before = epochsDone
            crossValidate(arch, trainX, trainY, assignment, folds, labels, classWeights, options.seed) { fold, epoch ->
                ModelBuildProgress.step(before + fold * arch.epochs + epoch, "${arch.label} fold ${fold + 1} of $folds, epoch $epoch")
            }.also { epochsDone += folds * arch.epochs }
        }
        val model = DenseNet(inputDim.toInt(), arch.hidden, labels.size, arch.activation, seed = options.seed)
        val batchSize = minOf(arch.batchSize, trainX.size)
        logInternal("modelBuilder: fitting ${arch.label} (${model.parameters} parameters) for ${arch.epochs} epochs…")
        val last = fitHead(model, trainX, trainY, arch.epochs, batchSize, arch.learningRate.toFloat(), options.seed, classWeights, arch.fitSettings(), watch) { epoch ->
            val summary = "${arch.label} epoch ${epoch.epoch} loss ${fmt4(epoch.loss)} accuracy ${fmt3(epoch.accuracy)}"
            ModelBuildProgress.step(epochsDone + epoch.epoch, summary)
            if (epoch.epoch % EPOCH_LOG_EVERY == 0 && epoch.epoch < arch.epochs) logInternal("modelBuilder: $summary")
        }
        epochsDone += arch.epochs
        // Every head is scored on the same validation rows as soon as it is fitted: its scores, its scores per
        // label and what the diagnostics make of it go to the console and under the build on the Models page
        val prediction = validationIdx.map { i -> model.predict(pooled[i]) }.toIntArray()
        val scored = computeMetrics(labels, truth, prediction, trainIdx.size, emptyList())
        val insights = Diagnostics.aboutModel(scored, labels, last.accuracy)
        val result = CandidateResult(
            arch.label, arch.hidden, arch.epochs, model.parameters, scored.accuracy, scored.macroF1, last.accuracy, last.loss,
            perLabelF1 = scored.labels.mapValues { it.value.f1 }, insights = insights,
            cvAccuracy = cv?.accuracy, cvMacroF1 = cv?.macroF1, cvSpread = cv?.spread, folds = cv?.folds,
            stoppedAt = model.stoppedAt, keptEpoch = if (arch.patience > 0) model.keptEpoch else null,
            curve = curveOf(model.history),
        )
        say("${arch.label}: accuracy ${fmt3(scored.accuracy)} · macro F1 ${fmt3(scored.macroF1)} on validation (train accuracy ${fmt3(last.accuracy)}, loss ${fmt4(last.loss)}, ${model.parameters} parameters)")
        cv?.let { say("${arch.label}: over ${it.folds} folds of the training rows, accuracy ${fmt3(it.accuracy)} · macro F1 ${fmt3(it.macroF1)} ± ${fmt3(it.spread)}") }
        if (arch.patience > 0) say("${arch.label}: " + (model.stoppedAt?.let { "stopped early at epoch $it of ${arch.epochs}" } ?: "ran all ${arch.epochs} epochs") +
            ", keeping epoch ${model.keptEpoch}, the lowest loss on the tenth of the training rows put aside")
        say("${arch.label} per label: " + scored.labels.entries.joinToString(" · ") { (label, m) -> "$label F1 ${fmt3(m.f1)} (precision ${fmt3(m.precision)}, recall ${fmt3(m.recall)})" })
        insights.forEach { say("${arch.label}: $it") }
        ModelBuildProgress.candidate(result)
        Trained(arch, model, result, last, batchSize, cv)
    }
    val results = trained.map { it.result }
    val best = if (foldOf != null) pickBestByFolds(results, options.pickBy) else pickBest(results, options.pickBy)
    val bestIndex = results.indexOfFirst { it === best }
    val chosen = trained[bestIndex]
    val arch = chosen.arch
    val model = chosen.model
    val last = chosen.last
    val batchSize = chosen.batchSize
    val score = { r: CandidateResult -> if (options.pickBy == "accuracy") r.accuracy else r.macroF1 }
    // The curves of the best heads (and the chosen one) are kept with the run; a search of many heads keeps a few
    val curvesKept = results.indices.sortedByDescending { score(results[it]) }.take(CURVE_HEADS).toSet() + bestIndex
    val candidateResults = results.mapIndexed { i, r -> r.copy(chosen = i == bestIndex, curve = if (i in curvesKept) r.curve else emptyList()) }
    ModelBuildProgress.chose(bestIndex)
    val scoreName = if (options.pickBy == "accuracy") "accuracy" else "macro F1"
    if (candidates.size > 1 && foldOf != null) {
        val cvScore = { r: CandidateResult -> (if (options.pickBy == "accuracy") r.cvAccuracy else r.cvMacroF1) ?: 0.0 }
        say("compared ${candidates.size} heads by $scoreName over $folds folds: " +
            results.sortedByDescending(cvScore).joinToString(" · ") { "${it.architecture} ${fmt3(cvScore(it))}" })
        say("chosen: ${best.architecture}, the best mean $scoreName over the folds" +
            (results.filter { it !== best }.maxByOrNull(cvScore)?.let { " (next: ${it.architecture} ${fmt3(cvScore(it))})" } ?: ""))
    } else if (candidates.size > 1) {
        say("compared ${candidates.size} heads by $scoreName: " +
            results.sortedByDescending(score).joinToString(" · ") { "${it.architecture} ${fmt3(score(it))}" })
        say("chosen: ${best.architecture}, the best $scoreName on the validation rows" +
            (results.filter { it !== best }.maxByOrNull(score)?.let { " (next: ${it.architecture} ${fmt3(score(it))})" } ?: ""))
    }
    // Rows every held-out head doubts: their label is likely wrong (or they are hard); texts only when they may be kept
    val suspects = chosen.cv?.let { cv -> findSuspects(labels, trainY, cv.outOfFold, if (options.keepTexts) trainIdx.map { all[it].text } else null) } ?: emptyList()
    if (suspects.isNotEmpty()) say("${suspects.size} training ${if (suspects.size == 1) "row looks" else "rows look"} mislabeled: the heads that did not see " +
        (if (suspects.size == 1) "it were" else "them were") + " sure of another label" +
        if (options.keepTexts) ", e.g. " + suspects.take(3).joinToString(" · ") { "\"${it.text.orEmpty().take(40)}\" is '${it.label}', they say '${it.suggested}' ${fmt3(it.score)}" } else "")

    // ---- 4. the chosen head's validation, kept with the model: run once more after the comparison, so what is
    // stored and charted is the head that was exported, scored on every validation row
    ModelBuildProgress.phase(BuildPhase.VALIDATING, steps = validationIdx.size, detail = "the chosen ${arch.label} on ${validationIdx.size} rows")
    // Calibrated: one temperature, fitted on the validation rows, divides the last layer, so the scores mean what they say
    var temperature: Double? = null
    var eceBefore: Double? = null
    if (options.calibrate) {
        val logits = validationIdx.map { model.logits(pooled[it]) }
        if (logits.size < Calibration.MIN_ROWS) say("calibration: skipped, a temperature needs at least ${Calibration.MIN_ROWS} validation rows to be fitted on, there are ${logits.size}")
        else {
            eceBefore = Calibration.reliability(logits.map { DenseNet.softmax(it) }, truth).first
            val t = Calibration.fitTemperature(logits, truth)
            if (t == 1.0) say("calibration: the scores are as sure as they should be already, nothing to fold in")
            else {
                Calibration.fold(model, t)
                temperature = t
                say("calibration: temperature ${fmt3(t)} folded into the head; the scores are ${if (t > 1) "less" else "more"} sure, the answers the same")
            }
        }
    }
    val probabilities = validationIdx.mapIndexed { n, i -> model.predictProbabilities(pooled[i]).also { ModelBuildProgress.step(n + 1) } }
    val prediction = probabilities.map { DenseNet.argmax(it) }.toIntArray()
    val scored = computeMetrics(labels, truth, prediction, trainIdx.size, warnings, intervals = true)
    val diagnostics = Diagnostics.aboutModel(scored, labels, last.accuracy)
    diagnostics.forEach { say("warning: $it") }
    val analysis = Calibration.analyse(labels, probabilities, truth, if (options.keepTexts) validationIdx.map { all[it].text } else null, options.keepMistakes)
        .copy(suspects = suspects, temperature = temperature, eceBefore = eceBefore)
    Calibration.describe(analysis).forEach { say(it) }
    if (options.keepTexts && analysis.mistakes.isNotEmpty()) say("the ${analysis.mistakes.size} validation ${if (analysis.mistakes.size == 1) "row" else "rows"} it got wrong ${if (analysis.mistakes.size == 1) "is" else "are"} kept with the run, the surest first: " +
        analysis.mistakes.take(3).joinToString(" · ") { "\"${it.text.orEmpty().take(40)}\" is '${it.label}', it said '${it.answered}' ${fmt3(it.score)}" })
    // The head a retrain replaces, read with the number the new one takes: one written meanwhile (a fine-tune ending)
    // isn't overwritten, nor given the same number
    val replaces = if (moduleExists(modelName)) readModule(modelName)?.second else null
    val version = (replaces?.let(ModelVersions::numberOf) ?: 0) + 1
    val metrics = scored.copy(
        warnings = warnings + diagnostics,
        validation = if (testSamples != null) validationName ?: "test set" else "split ${(options.validationFraction * 100).toInt()}%",
        candidates = candidateResults,
        preprocessing = preprocessing,
        kind = "build",
        version = version,
        analysis = analysis,
        leakage = leakage,
    )

    // ---- 5. export
    ModelBuildProgress.phase(BuildPhase.EXPORTING, detail = "AMP/models/intents/$modelName")
    return try {
        val layers = denseLayersOf(model)
        val manifest = ModuleManifest(
            name = modelName,
            encoder = encoder,
            inputDim = inputDim,
            outputDim = layers.last().outDim,
            labels = labels,
            data = dataName,
            dataRows = samples.groupingBy { it.label }.eachCount().toSortedMap(),
            description = description?.takeIf { it.isNotBlank() },
            architecture = "${arch.describe(inputDim, labels.size)} over mean-pooled ${File(encoder).name}",
            epochs = arch.epochs,
            batchSize = batchSize,
            learningRate = arch.learningRate,
            hiddenDim = arch.hidden.firstOrNull() ?: 0,
            trainRows = trainIdx.size,
            validationRows = validationIdx.size,
            validation = metrics.validation,
            finalLoss = last.loss,
            trainAccuracy = last.accuracy,
            keepTexts = options.keepTexts,
            search = if (candidates.size > 1) "${candidates.size} heads compared by ${if (options.pickBy == "accuracy") "accuracy" else "macro F1"}" +
                (if (foldOf != null) " over $folds folds" else "") + ": " +
                candidateResults.joinToString { "${it.architecture} ${fmt3(if (foldOf != null) (if (options.pickBy == "accuracy") it.cvAccuracy else it.cvMacroF1) ?: 0.0 else if (options.pickBy == "accuracy") it.accuracy else it.macroF1)}${if (it.chosen) " (chosen)" else ""}" } else null,
            version = version,
            kind = "build",
            temperature = temperature,
            featureMean = if (options.keepTexts) meanVector(trainIdx.map { model.features(pooled[it]) })?.let(::encodeFloats) else null,
            featureRows = if (options.keepTexts) trainIdx.size else null,
        )
        val file = writeModuleChecked(manifest, denseStackToOnnx(layers, manifest.input, manifest.output, modelName), existed, startedFrom = replaces) { written ->
            appendMetrics(written.parentFile, metrics)
        }
        MetricsReport(modelName, metrics).print()
        ModelBuildProgress.done("${arch.label} · accuracy ${fmt3(metrics.accuracy)} · macro F1 ${fmt3(metrics.macroF1)} on ${metrics.validation}")
        file
    } catch (e: Exception) {
        fail("export of '$modelName' failed: ${e.message}")
        ModelBuildProgress.failed("export failed: ${e.message}")
        null
    }
}

private fun fmt3(value: Double) = "%.3f".format(Locale.US, value)
private fun fmt4(value: Double) = "%.4f".format(Locale.US, value)

/** One head of a build as it came out of training: what it is, the net, its scores, its last epoch and its folds */
private class Trained(
    val arch: Architecture,
    val model: DenseNet,
    val result: CandidateResult,
    val last: DenseNet.EpochResult,
    val batchSize: Int,
    val cv: FoldScores?,
)
