package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import org.example.project.components.terminal.window.controller.console.logError
import org.example.project.components.terminal.window.controller.console.logInfo
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modules.Baseline
import org.example.project.components.terminal.window.controller.modules.MetricsReport
import org.example.project.components.terminal.window.controller.modules.ModelVersions
import org.example.project.components.terminal.window.controller.modules.appendMetrics
import org.example.project.components.terminal.window.controller.modules.computeMetrics
import org.example.project.components.terminal.window.controller.modules.denseStackToOnnx
import org.example.project.components.terminal.window.controller.modules.guideFile
import org.example.project.components.terminal.window.controller.modules.ModuleManifest
import org.example.project.components.terminal.window.controller.modules.SCRIPT_ROWS
import org.example.project.components.terminal.window.controller.modules.moduleExists
import org.example.project.components.terminal.window.controller.modules.readModule
import org.example.project.components.terminal.window.controller.modules.readStoredManifest
import org.example.project.components.terminal.window.controller.modules.updateGuideLabels
import org.example.project.components.terminal.window.controller.modules.writeModuleChecked
import org.example.project.components.terminal.window.controller.sourceFile.DataSource
import java.io.File
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How [fineTuneClassifier] goes about it; `fineTuneOptions(...)` in a script. The defaults suit a few dozen new
 * rows, or a new label, on a trained head: a gentle rate, a pull back towards the weights it starts from, and the
 * model's earlier rows replayed with the new ones, so the labels it knows stay known.
 */
data class FineTuneOptions(
    val epochs: Int = 40,
    /** Lower than a build's 0.01: the head is nearly where it should be already */
    val learningRate: Double = 0.002,
    val batchSize: Int = 16,
    /** How hard the head is pulled back to the weights it starts from (L2-SP): higher forgets less and learns the new rows less; 0 lets it go anywhere */
    val stayNear: Double = 0.01,
    /** Train on rows of the model's earlier data too (its data file and the files it was fine-tuned on, when they are still in AMP/) */
    val replay: Boolean = true,
    /** At most this many earlier rows per label are replayed */
    val replayPerLabel: Int = 200,
    /** The first n layers keep their weights: with 1 on a dense[32] head only the last layer learns; 0 trains every layer */
    val freeze: Int = 0,
    /** Weight the loss so every label counts the same: a new label of a few rows beside many replayed ones is otherwise learned last */
    val balanceClasses: Boolean = true,
    /** The share of the new rows, and of the replayed rows, held out to score the head before and after */
    val validationFraction: Double = 0.2,
    val seed: Int = 42,
    /** Clean the new rows as a build does; a fine-tune accepts one label, or a single row of a label the model knows */
    val preprocess: Boolean = true,
    /** Early stopping on a tenth of the training rows; 0 runs every epoch */
    val patience: Int = 0,
    /** Fit a temperature on the held-out rows afterwards, as buildOptions(calibrate = true) does */
    val calibrate: Boolean = false,
    /** Vectors of texts seen before come from AMP/cache/embeddings (never for a model that doesn't keep texts) */
    val cacheEmbeddings: Boolean = true,
    /** How many held-out rows it got wrong are kept with the run */
    val keepMistakes: Int = 25,
    /** A label the model doesn't know yet needs at least this many rows to be added */
    val minNewLabelRows: Int = 3,
) {
    init {
        require(epochs in 1..10_000) { "epochs must be between 1 and 10000, got $epochs" }
        require(learningRate > 0.0 && learningRate < 1.0) { "learningRate must be between 0 and 1, got $learningRate" }
        require(batchSize >= 1) { "batchSize must be at least 1, got $batchSize" }
        require(stayNear >= 0.0) { "stayNear can't be negative, got $stayNear" }
        require(replayPerLabel >= 0) { "replayPerLabel can't be negative, got $replayPerLabel" }
        require(freeze >= 0) { "freeze can't be negative, got $freeze" }
        require(validationFraction > 0.0 && validationFraction < 0.5) { "validationFraction must be between 0 and 0.5, got $validationFraction" }
        require(patience >= 0) { "patience can't be negative, got $patience" }
        require(keepMistakes >= 0) { "keepMistakes can't be negative, got $keepMistakes" }
        require(minNewLabelRows >= 2) { "a new label needs at least 2 rows (one to learn, one to check), got $minNewLabelRows" }
    }
}

/** An old label whose F1 on the held-out rows falls by more than this after a fine-tune is reported as forgotten */
const val FORGET_DROP = 0.1

private const val FINE_TUNE_SOURCE = "sandbox/modelBuilder"
private fun tell(message: String) = logInfo("fineTune: $message", FINE_TUNE_SOURCE)
private fun refuse(message: String) = logError("fineTune: $message", FINE_TUNE_SOURCE)

/**
 * Trains a model further on new rows instead of building it again: the head it has is read back from its
 * model.onnx and keeps its weights as the start, a label it doesn't know gets an output of its own (started from
 * the mean of that label's rows), the rows it learned from before are replayed with the new ones, and the head is
 * held near where it started, so what it knew stays known. Before and after are scored on the same held-out rows;
 * the new head is a new version of the model, and the one it replaces is kept for a rollback.
 *
 * Returns the model file, or null with the reasons in the console. Progress shows like a build's.
 */
fun fineTuneClassifier(
    modelName: String,
    samples: List<LabeledText>,
    dataName: String? = null,
    options: FineTuneOptions = FineTuneOptions(),
    validation: List<LabeledText>? = null,
    validationName: String? = null,
    embedder: Embedder? = null,
): File? {
    ModelBuildProgress.start(modelName, job = "fineTune")
    return try {
        fineTunePhases(modelName, samples, dataName, options, validation, validationName, embedder)
            .also { if (it == null && ModelBuildProgress.current.value != null) ModelBuildProgress.failed("not fine-tuned") }
    } catch (e: Throwable) {
        ModelBuildProgress.failed(e.message ?: e.javaClass.simpleName)
        throw e
    }
}

private fun stop(reason: String, short: String): File? {
    refuse(reason)
    ModelBuildProgress.failed(short)
    return null
}

/** The new rows as a fine-tune takes them: cleaned by the preprocessor, in the model's spelling of its labels */
private data class FreshRows(val prep: Preprocessor.Result, val known: (LabeledText) -> LabeledText)

private fun freshRows(samples: List<LabeledText>, oldLabels: List<String>, options: FineTuneOptions, keepTexts: Boolean): FreshRows {
    // The model's spelling of a label wins: "Lights" in the file is the model's "lights"
    val byLower = oldLabels.associateBy { it.trim().lowercase() }
    val known: (LabeledText) -> LabeledText = { row -> byLower[row.label.trim().lowercase()]?.let { row.copy(label = it) } ?: row }
    val mapped = samples.map(known)
    val knownShare = if (mapped.isEmpty()) 0.0 else mapped.count { it.label in oldLabels }.toDouble() / mapped.size
    val rules = Preprocessor.Rules(minLabels = 1, minSamplesPerLabel = 1, minDistinctTextsPerLabel = 1, checkSwapped = knownShare < 0.5)
    val prep = if (options.preprocess) Preprocessor.run(mapped, rules, keepTexts) else {
        val usable = mapped.filter { it.text.isNotBlank() && it.label.isNotBlank() }
        Preprocessor.Result(usable, emptyList(), listOf("${usable.size} rows, not preprocessed"), emptyList(), if (usable.isEmpty()) listOf("no labeled rows at all") else emptyList())
    }
    return FreshRows(prep, known)
}

/** The earlier rows a fine-tune replays, the files it means to read them from and the ones it found, and how many new rows relabel an earlier text */
private data class Replay(val rows: List<LabeledText>, val files: List<String>, val readFrom: List<String>, val relabeled: Int)

private fun replayRows(stored: ModuleManifest, oldLabels: List<String>, fresh: List<LabeledText>, options: FineTuneOptions, known: (LabeledText) -> LabeledText): Replay {
    val replayFiles = if (options.replay) (listOfNotNull(stored.data) + stored.fineTunedOn).distinct() else emptyList()
    val earlier = LinkedHashMap<String, LabeledText>()
    val replayedFrom = ArrayList<String>()
    replayFiles.forEach { name ->
        val file = DataSource.locate(name) ?: return@forEach
        val source = DataSource.read(file.path) ?: return@forEach
        // Later files win on a text both have, as they were trained on later
        Preprocessor.run(source.samples().map(known)).samples.filter { it.label in oldLabels }.forEach { earlier[it.text.lowercase()] = it }
        replayedFrom += name
    }
    val freshTexts = fresh.map { it.text.lowercase() }.toSet()
    val relabeled = fresh.count { f -> earlier[f.text.lowercase()]?.let { it.label != f.label } == true }
    val replay = earlier.values.filter { it.text.lowercase() !in freshTexts }
        .groupBy { it.label }.toSortedMap().values
        .flatMap { rows -> rows.shuffled(Random(options.seed)).take(options.replayPerLabel) }
    return Replay(replay, replayFiles, replayedFrom, relabeled)
}

/**
 * What a fine-tune of [modelName] on [samples] would do, worked out without training: the rows it would take,
 * which of its labels they are and which labels they would add, a new label with too few rows to be added, a new
 * label spelled like one it has, the earlier rows it would replay and from where. The Models page shows it before
 * the fine-tune starts.
 */
data class FineTunePlan(
    val modelName: String,
    /** The version it has now, kept when the fine-tune makes the next one */
    val version: Int,
    val labels: List<String>,
    /** Rows it would train on, after the preprocessor */
    val rows: Int,
    /** Rows the preprocessor would drop, by reason */
    val dropped: Map<String, Int>,
    /** Rows per label of the ones it has */
    val known: Map<String, Int>,
    /** Rows per label it would add */
    val newLabels: Map<String, Int>,
    /** New labels with fewer rows than a new label needs */
    val tooFew: List<String>,
    /** New label → the label it has that it is probably a misspelling of */
    val nearMisses: Map<String, String>,
    val replayRows: Int,
    val replayedFrom: List<String>,
    /** Files it was trained on that are not in AMP/ any more, so they can't be replayed */
    val notFound: List<String>,
    /** New rows that give a text it learned under another label */
    val relabeled: Int,
    val minNewLabelRows: Int,
    /** Why it can't start at all */
    val problems: List<String>,
) {
    val ok: Boolean get() = problems.isEmpty() && tooFew.isEmpty()
}

fun planFineTune(modelName: String, samples: List<LabeledText>, options: FineTuneOptions = FineTuneOptions()): FineTunePlan {
    fun refused(problem: String, version: Int = 1, labels: List<String> = emptyList()) = FineTunePlan(
        modelName, version, labels, 0, emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyMap(), 0, emptyList(), emptyList(), 0, options.minNewLabelRows, listOf(problem),
    )
    if (!moduleExists(modelName)) return refused("there is no model '$modelName'")
    val (dir, current) = runCatching { readModule(modelName) }.getOrNull() ?: return refused("'$modelName' can't be read")
    val from = ModelVersions.numberOf(current)
    val head = runCatching { OnnxHead.read(File(dir, current.model)) }.getOrElse { return refused("its head can't be read: ${it.message}", from, current.labels) }
    if (head.outputDim != current.labels.size || head.inputDim.toLong() != current.inputDim) return refused("its head and its module.json disagree", from, current.labels)
    val oldLabels = current.labels
    val (prep, known) = freshRows(samples, oldLabels, options, current.keepTexts)
    val fresh = if (prep.ok) prep.samples else emptyList()
    val counts = fresh.groupingBy { it.label }.eachCount()
    val newLabels = counts.filterKeys { it !in oldLabels }.toSortedMap()
    val replay = replayRows(readStoredManifest(dir), oldLabels, fresh, options, known)
    return FineTunePlan(
        modelName = modelName,
        version = from,
        labels = oldLabels,
        rows = fresh.size,
        dropped = prep.dropped.groupingBy { it.reason }.eachCount(),
        known = oldLabels.mapNotNull { l -> counts[l]?.let { l to it } }.toMap(),
        newLabels = newLabels,
        tooFew = newLabels.filterValues { it < options.minNewLabelRows }.keys.toList(),
        nearMisses = newLabels.keys.mapNotNull { new -> nearLabel(new, oldLabels)?.let { new to it } }.toMap(),
        replayRows = replay.rows.size,
        replayedFrom = replay.readFrom,
        notFound = replay.files - replay.readFrom.toSet(),
        relabeled = replay.relabeled,
        minNewLabelRows = options.minNewLabelRows,
        problems = prep.errors,
    )
}

private fun fineTunePhases(
    modelName: String,
    samples: List<LabeledText>,
    dataName: String?,
    options: FineTuneOptions,
    validation: List<LabeledText>?,
    validationName: String?,
    embedder: Embedder?,
): File? {
    // ---- 1. the model as it is, and the new rows in its labels
    ModelBuildProgress.phase(BuildPhase.CHECKING, detail = "${samples.size} rows")
    if (!moduleExists(modelName)) return stop("there is no model '$modelName' to fine-tune; build it with modelBuilder first", "no such model")
    val (dir, current) = readModule(modelName) ?: return stop("'$modelName' can't be read", "model can't be read")
    val stored = readStoredManifest(dir)
    val head = runCatching { OnnxHead.read(File(dir, current.model)) }.getOrElse {
        return stop("'$modelName' can't be fine-tuned: ${it.message}", "head can't be read")
    }
    if (head.outputDim != current.labels.size || head.inputDim.toLong() != current.inputDim) {
        return stop("'$modelName' can't be fine-tuned: its head reads ${head.inputDim} numbers into ${head.outputDim} scores, its module.json says ${current.inputDim} into ${current.labels.size} labels", "head and manifest disagree")
    }
    val keepTexts = current.keepTexts
    val oldLabels = current.labels
    val from = ModelVersions.numberOf(current)
    val shapeName = if (head.hidden.isEmpty()) "linear" else "dense${head.hidden}"
    tell("fine-tuning '$modelName' v$from ($shapeName head over ${File(current.encoder).name}, labels ${oldLabels.joinToString()}) on ${dataName ?: SCRIPT_ROWS}")
    if (head.outputActivation == "Sigmoid") tell("its head gives sigmoid scores; the fine-tuned one gives softmax scores, which add up to 1")
    current.temperature?.takeIf { it != 1.0 }?.let { tell("its calibration (temperature ${f3(it)}) is taken out of the weights before training; calibrate = true fits a new one") }

    val (prep, known) = freshRows(samples, oldLabels, options, keepTexts)
    prep.lines(examples = keepTexts).forEach { line -> if (line.startsWith("error:")) refuse(line.removePrefix("error: ")) else tell(line) }
    if (!prep.ok) return stop("'$modelName' was not fine-tuned: the rows can't be trained on (${prep.errors.size} ${if (prep.errors.size == 1) "reason" else "reasons"} above)", prep.errors.first().take(80))
    val fresh = prep.samples
    val counts = fresh.groupingBy { it.label }.eachCount()
    val newLabels = counts.keys.filter { it !in oldLabels }.sorted()
    val tooFew = newLabels.filter { counts.getValue(it) < options.minNewLabelRows }
    if (tooFew.isNotEmpty()) return stop(
        "'$modelName' was not fine-tuned: a new label needs at least ${options.minNewLabelRows} rows to be learned and checked; " +
            tooFew.joinToString { "'$it' has ${counts.getValue(it)}" } + " (the labels it knows: ${oldLabels.joinToString()})",
        "too few rows of a new label",
    )
    newLabels.forEach { new -> nearLabel(new, oldLabels)?.let { tell("warning: '$new' is taken as a new label; if it is meant to be '$it', spell it so in the file") } }
    val labels = oldLabels + newLabels
    if (newLabels.isNotEmpty()) tell("new ${if (newLabels.size == 1) "label" else "labels"}: " + newLabels.joinToString { "$it (${counts.getValue(it)} rows)" } +
        "; the head gets ${if (newLabels.size == 1) "an output" else "outputs"} for ${if (newLabels.size == 1) "it" else "them"}, the ${oldLabels.size} it has keep theirs")
    val warnings = prep.warnings.toMutableList()
    val preprocessing = prep.notes + prep.dropped.groupBy { it.reason }.map { (reason, rows) -> "dropped ${rows.size}: $reason" }

    // ---- 2. the rows it learned from before, replayed so the labels it knows are kept while it learns the new ones
    val (replay, replayFiles, replayedFrom, relabeled) = replayRows(stored, oldLabels, fresh, options, known)
    if (relabeled > 0) tell("$relabeled of the new rows give a text the model learned under another label; the new label wins")
    val uncovered = oldLabels.filter { l -> fresh.none { it.label == l } && replay.none { it.label == l } }
    when {
        !options.replay -> tell("no replay: only the new rows are trained on, and stayNear (${options.stayNear}) is what keeps the labels it knows")
        replay.isEmpty() -> tell("warning: no earlier rows to replay (" + (if (replayFiles.isEmpty()) "the model doesn't say what it was trained on" else "${replayFiles.joinToString()} not in AMP/") +
            "); only stayNear keeps the labels it knows, so they may fade")
        else -> tell("replaying ${replay.size} earlier rows of ${replayedFrom.joinToString()} (up to ${options.replayPerLabel} per label) with the ${fresh.size} new ones")
    }
    if (uncovered.isNotEmpty()) tell("warning: no rows of ${uncovered.joinToString()} to train on or check; validate the fine-tuned model on a file that has ${if (uncovered.size == 1) "it" else "them"} to see whether ${if (uncovered.size == 1) "it is" else "they are"} still known")

    // A test file is scored as it is; a row of it that is also a training row is left out, as in a build
    val seen = (fresh + replay).map { it.text.lowercase() }.toSet()
    val testSamples = validation?.let { test ->
        val looseRules = Preprocessor.Rules(minLabels = 1, minSamplesPerLabel = 1, minDistinctTextsPerLabel = 1, checkSwapped = false)
        val cleaned = (if (options.preprocess) Preprocessor.run(test.map(known), looseRules).samples else test.map(known))
        val unseen = cleaned.filter { it.text.lowercase() !in seen }
        if (unseen.size < cleaned.size) warnings += "${cleaned.size - unseen.size} rows of $validationName are also training rows; they were left out of the check"
        val usable = unseen.filter { it.label in labels }
        if (usable.size < unseen.size) warnings += "${unseen.size - usable.size} rows of $validationName have labels the model doesn't have"
        usable.ifEmpty {
            warnings += "$validationName has no rows to check on; a ${(options.validationFraction * 100).toInt()}% split is held out instead"
            null
        }
    }

    // ---- 3. which rows train and which check: a share of the new rows and of the replayed rows is held out
    val all = fresh + replay + testSamples.orEmpty()
    val labelIndex = all.map { labels.indexOf(it.label) }.toIntArray()
    val training = fresh.size + replay.size
    val (trainIdx, heldIdx) = if (testSamples != null) (0 until training).toList() to (training until all.size).toList() else {
        val (freshTrain, freshHeld) = stratifiedSplit(IntArray(fresh.size) { labelIndex[it] }, options.validationFraction, options.seed)
        val (replayTrain, replayHeld) = if (replay.isEmpty()) emptyList<Int>() to emptyList()
        else stratifiedSplit(IntArray(replay.size) { labelIndex[fresh.size + it] }, options.validationFraction, options.seed + 1)
        (freshTrain + replayTrain.map { it + fresh.size }) to (freshHeld + replayHeld.map { it + fresh.size })
    }
    val scoredOnTraining = heldIdx.isEmpty()
    val checkIdx = if (scoredOnTraining) trainIdx else heldIdx
    if (scoredOnTraining) warnings += "there were too few rows to hold any out, so the head is scored on the rows it was fine-tuned on; those scores flatter it"
    warnings.drop(prep.warnings.size).forEach { tell("warning: $it") }

    // ---- 4. every text through the model's encoder, or from the cache
    ModelBuildProgress.phase(BuildPhase.EMBEDDING, steps = all.size, detail = File(current.encoder).name)
    val embed = embedder ?: EncoderEmbedder(current.encoder, options.cacheEmbeddings && keepTexts)
    val vectors = embed.embed(all.map { it.text }) { done, total -> ModelBuildProgress.step(done, "$done of $total texts through ${File(current.encoder).name}") }
    (embed as? EncoderEmbedder)?.fromCache?.takeIf { it > 0 }?.let { logInternal("fineTune: $it of ${all.size} texts came from the embedding cache") }
    if (vectors.first().size != head.inputDim) {
        return stop("the encoder gives ${vectors.first().size} numbers per text, the head of '$modelName' reads ${head.inputDim}: it was trained over another encoder", "encoder doesn't fit the head")
    }
    // Held-out rows that repeat a row it learned from flatter the scores: told, and nothing trained changes (a head
    // scored on its training rows was warned of already). Its head learned from more than the rows replayed: the files
    // it was trained on are looked in too, exactly, but not for the held-out replayed rows, which come from them
    val leakage = if (scoredOnTraining) null else LeakageCheck.find(
        trainIdx.map { all[it].text }, heldIdx.map { all[it].text },
        what = if (testSamples != null) "rows of $validationName" else "held-out rows", against = "a row it learned from",
        keepTexts = keepTexts, trainingVectors = trainIdx.map { vectors[it] }, vectors = heldIdx.map { vectors[it] },
        alsoLearned = LeakageCheck.learnedFrom(stored)?.second.orEmpty(),
        fromLearned = heldIdx.indices.filter { heldIdx[it] in fresh.size until training }.toSet(),
    )
    leakage?.warning?.let { warnings += it; tell("warning: $it") }

    // ---- 5. the head it has, grown by the new labels, trained on and held near where it started
    val before = head.toDenseNet()
    val start = head.toDenseNet().also { net -> current.temperature?.takeIf { it != 1.0 }?.let { t -> Calibration.fold(net, 1.0 / t) } }
    val trainX = trainIdx.map { vectors[it] }.toTypedArray()
    val trainY = IntArray(trainIdx.size) { labelIndex[trainIdx[it]] }
    // Where the training rows sat, kept by the build: what a new label is told apart from when there is nothing to replay
    val reference = stored.featureMean?.let(::decodeFloats)?.takeIf { it.size == start.layers.last().inDim }
    val referenceRows = if (reference != null) stored.featureRows ?: 0 else 0
    val net = if (newLabels.isEmpty()) start else imprint(start, newLabels.size, trainX, trainY, oldLabels.size, reference, referenceRows)
    if (options.freeze >= net.layers.size) {
        return stop("freeze = ${options.freeze}, but the head of '$modelName' has ${net.layers.size} ${if (net.layers.size == 1) "layer" else "layers"}; at least the last one has to learn", "freeze too high")
    }
    // The pull back counts for the weights it had; the new labels' columns are free
    val anchor = net.copyWeights().also { a ->
        if (newLabels.isNotEmpty()) {
            val last = net.layers.last()
            val w = a[a.size - 2]
            val b = a[a.size - 1]
            for (i in 0 until last.inDim) for (j in oldLabels.size until last.outDim) w[i * last.outDim + j] = Float.NaN
            for (j in oldLabels.size until last.outDim) b[j] = Float.NaN
        }
    }
    val settings = FitSettings(anchor = anchor, anchorStrength = options.stayNear.toFloat(), frozenLayers = options.freeze, patience = options.patience)
    val classWeights = if (options.balanceClasses) balancedWeights(trainY, labels.size) else null
    val trainFresh = trainIdx.count { it < fresh.size }
    ModelBuildProgress.phase(BuildPhase.TRAINING, steps = options.epochs, detail = "${trainIdx.size} rows")
    tell("training on ${trainIdx.size} rows ($trainFresh new, ${trainIdx.size - trainFresh} replayed), checking on ${checkIdx.size}; ${options.epochs} epochs at rate ${options.learningRate}" +
        (if (options.stayNear > 0) ", held near the weights it starts from (stayNear ${options.stayNear})" else "") +
        (if (options.freeze > 0) ", the first ${options.freeze} ${if (options.freeze == 1) "layer" else "layers"} frozen" else ""))
    val watchRows = spreadSample(checkIdx, 400)
    val watch = watchRows.map { vectors[it] }.toTypedArray() to IntArray(watchRows.size) { labelIndex[watchRows[it]] }
    val last = fitHead(net, trainX, trainY, options.epochs, options.batchSize, options.learningRate.toFloat(), options.seed, classWeights, settings, watch) { epoch ->
        val summary = "fine-tune epoch ${epoch.epoch} loss ${f4(epoch.loss)} accuracy ${f3(epoch.accuracy)}"
        ModelBuildProgress.step(epoch.epoch, summary)
        if (epoch.epoch % 10 == 0 && epoch.epoch < options.epochs) logInternal("fineTune: $summary")
    }
    if (options.patience > 0) tell((net.stoppedAt?.let { "stopped early at epoch $it of ${options.epochs}" } ?: "ran all ${options.epochs} epochs") +
        ", keeping epoch ${net.keptEpoch}, the lowest loss on the tenth of the training rows put aside")

    // ---- 6. before and after on the same rows
    ModelBuildProgress.phase(BuildPhase.VALIDATING, steps = checkIdx.size, detail = "before and after on ${checkIdx.size} rows")
    val truth = IntArray(checkIdx.size) { labelIndex[checkIdx[it]] }
    // The head before knows only its labels: its answers get a zero for each new one
    val beforeProbs = checkIdx.map { i -> before.predictProbabilities(vectors[i]).let { p -> FloatArray(labels.size) { if (it < p.size) p[it] else 0f } } }
    val beforeRun = computeMetrics(labels, truth, beforeProbs.map { DenseNet.argmax(it) }.toIntArray(), trainIdx.size, emptyList(), presentOnly = true)
    val beforeEce = Calibration.reliability(beforeProbs, truth).first
    var temperature: Double? = null
    var eceBefore: Double? = null
    if (options.calibrate) {
        val logits = checkIdx.map { net.logits(vectors[it]) }
        when {
            scoredOnTraining -> tell("calibration: skipped, there are no held-out rows to fit a temperature on")
            logits.size < Calibration.MIN_ROWS -> tell("calibration: skipped, a temperature needs at least ${Calibration.MIN_ROWS} held-out rows, there are ${logits.size}")
            else -> {
                eceBefore = Calibration.reliability(logits.map { DenseNet.softmax(it) }, truth).first
                val t = Calibration.fitTemperature(logits, truth)
                if (t != 1.0) {
                    Calibration.fold(net, t)
                    temperature = t
                    tell("calibration: temperature ${f3(t)} folded into the head")
                } else tell("calibration: the scores are as sure as they should be already, nothing to fold in")
            }
        }
    }
    val afterProbs = checkIdx.mapIndexed { n, i -> net.predictProbabilities(vectors[i]).also { ModelBuildProgress.step(n + 1) } }
    val prediction = afterProbs.map { DenseNet.argmax(it) }.toIntArray()
    val scored = computeMetrics(labels, truth, prediction, trainIdx.size, emptyList(), presentOnly = true, intervals = true)

    fun accuracyOf(positions: List<Int>, probs: List<FloatArray>): Double =
        if (positions.isEmpty()) 0.0 else positions.count { DenseNet.argmax(probs[it]) == truth[it] }.toDouble() / positions.size
    if (testSamples == null && !scoredOnTraining) {
        val newPart = checkIdx.indices.filter { checkIdx[it] < fresh.size }
        val oldPart = checkIdx.indices.filter { checkIdx[it] >= fresh.size }
        if (newPart.isNotEmpty()) tell("on the ${newPart.size} held-out new rows: accuracy ${f3(accuracyOf(newPart, beforeProbs))} before, ${f3(accuracyOf(newPart, afterProbs))} after")
        if (oldPart.isNotEmpty()) tell("on the ${oldPart.size} held-out earlier rows (what it knew): accuracy ${f3(accuracyOf(oldPart, beforeProbs))} before, ${f3(accuracyOf(oldPart, afterProbs))} after")
    }
    tell("on all ${checkIdx.size} rows: accuracy ${f3(beforeRun.accuracy)} → ${f3(scored.accuracy)} · macro F1 ${f3(beforeRun.macroF1)} → ${f3(scored.macroF1)}")
    val forgotten = oldLabels.mapNotNull { l ->
        val b = beforeRun.labels[l] ?: return@mapNotNull null
        val a = scored.labels[l] ?: return@mapNotNull null
        if (b.support > 0 && a.f1 < b.f1 - FORGET_DROP) "'$l' F1 ${f3(b.f1)} → ${f3(a.f1)}" else null
    }
    if (forgotten.isNotEmpty()) {
        val line = "the fine-tune forgets some of what it knew: ${forgotten.joinToString()}; replay more earlier rows (replayPerLabel), raise stayNear, or roll back"
        warnings += line
        tell("warning: $line")
    }
    val diagnostics = Diagnostics.aboutModel(scored, labels, last.accuracy)
    diagnostics.forEach { tell("warning: $it") }
    val analysis = Calibration.analyse(labels, afterProbs, truth, if (keepTexts) checkIdx.map { all[it].text } else null, options.keepMistakes)
        .copy(temperature = temperature, eceBefore = eceBefore, curve = curveOf(net.history))
    Calibration.describe(analysis).forEach { tell(it) }

    // ---- 7. the new head as the next version; the one it replaces is kept
    ModelBuildProgress.phase(BuildPhase.EXPORTING, detail = "AMP/models/intents/$modelName")
    // The mean the next fine-tune starts from: the replayed rows stand for the earlier data; without them the kept
    // mean counts for the rows it is over. Without either, none (a mean of only the new rows would mislead)
    val trainedMean = meanVector(trainX.map { net.features(it) })
    val (keptMean, keptRows) = when {
        !keepTexts || trainedMean == null -> null to 0
        replay.isNotEmpty() -> trainedMean to trainX.size
        reference != null && referenceRows > 0 -> {
            val total = referenceRows + trainX.size
            FloatArray(trainedMean.size) { i -> ((reference[i].toDouble() * referenceRows + trainedMean[i].toDouble() * trainX.size) / total).toFloat() } to total
        }
        else -> null to 0
    }
    val version = from + 1
    val source = dataName ?: SCRIPT_ROWS
    val shape = Architecture(
        hidden = net.hidden, epochs = options.epochs, batchSize = options.batchSize, learningRate = options.learningRate,
        activation = if (net.activation.equals("Tanh", true)) "Tanh" else "Relu",
    )
    val checkedOn = when {
        testSamples != null -> validationName ?: "test set"
        scoredOnTraining -> "its training rows"
        else -> "split ${(options.validationFraction * 100).toInt()}% of $source" + if (replay.isNotEmpty()) " and of the replayed rows" else ""
    }
    val note = "fine-tuned v$from on $source: ${fresh.size} new rows" + (if (replay.isNotEmpty()) ", ${replay.size} replayed" else "") +
        (if (newLabels.isNotEmpty()) "; new ${if (newLabels.size == 1) "label" else "labels"} ${newLabels.joinToString()}" else "")
    val metrics = scored.copy(
        warnings = warnings + diagnostics,
        validation = checkedOn,
        preprocessing = preprocessing,
        kind = "fine-tune",
        version = version,
        analysis = analysis,
        baseline = Baseline(from, beforeRun.accuracy, beforeRun.macroF1, beforeRun.labels.mapValues { it.value.f1 }, beforeEce),
        note = note,
        leakage = leakage,
    )
    return try {
        val manifest = stored.copy(
            labels = labels,
            outputDim = labels.size.toLong(),
            createdAt = Instant.now().toString(),
            architecture = "${shape.describe(current.inputDim, labels.size)} over mean-pooled ${File(current.encoder).name}",
            epochs = options.epochs,
            batchSize = minOf(options.batchSize, trainX.size),
            learningRate = options.learningRate,
            hiddenDim = net.hidden.firstOrNull() ?: 0,
            trainRows = trainIdx.size,
            validationRows = checkIdx.size,
            validation = checkedOn,
            finalLoss = last.loss,
            trainAccuracy = last.accuracy,
            version = version,
            kind = "fine-tune",
            parentVersion = from,
            temperature = temperature,
            fineTunedOn = stored.fineTunedOn + source,
            // Aligned with fineTunedOn: a fine-tune from before the rows were kept has none
            fineTunedRows = stored.fineTunedOn.indices.map { stored.fineTunedRows.getOrNull(it) } + counts.toSortedMap(),
            featureMean = keptMean?.let(::encodeFloats),
            featureRows = keptMean?.let { keptRows },
            // Left to writeModule, which keeps the model's as they are when the head is written: edited meanwhile, they stay
            description = null,
            icon = null,
        )
        // Written only if the model still has the head it was fine-tuned from: renamed, destroyed, rolled back or trained
        // again meanwhile, it isn't written over
        val file = writeModuleChecked(manifest, denseStackToOnnx(denseLayersOf(net), manifest.input, manifest.output, modelName), existed = true, startedFrom = stored) { written ->
            if (newLabels.isNotEmpty() && !updateGuideLabels(guideFile(dir), oldLabels, labels)) tell("guide.md lists the labels some other way, so it was left as it is; add ${newLabels.joinToString()} to it on the Models page")
            appendMetrics(written.parentFile, metrics)
        }
        MetricsReport(modelName, metrics).print()
        logInternal("module '$modelName' fine-tuned: v$version from v$from, accuracy ${f3(beforeRun.accuracy)} → ${f3(metrics.accuracy)} on $checkedOn; v$from is kept for a rollback")
        ModelBuildProgress.done("fine-tune v$from → v$version · accuracy ${f3(beforeRun.accuracy)} → ${f3(metrics.accuracy)} on $checkedOn")
        file
    } catch (e: Exception) {
        refuse("export of '$modelName' failed: ${e.message}")
        ModelBuildProgress.failed("export failed: ${e.message}")
        null
    }
}

/**
 * The head with [extra] outputs more, one per new label, each set where that label will end up instead of at random.
 * A new column points from where the labels the head has sit (their training rows, and the kept mean of the build's
 * training rows, [reference], counted as the [referenceRows] it is over) to the mean of the new label's rows, as long
 * as the columns it has. Its bias balances the two sides: the share of the label's rows it would still miss equals
 * the share of the other rows (and of the kept mean) it would take, so the new output answers its rows before the
 * first epoch without taking the others'. With neither other rows nor a kept mean, it answers 90% of its rows.
 */
internal fun imprint(
    net: DenseNet,
    extra: Int,
    x: Array<FloatArray>,
    y: IntArray,
    firstNew: Int,
    reference: FloatArray? = null,
    referenceRows: Int = 0,
): DenseNet {
    val features = x.map { net.features(it) }
    val last = net.layers.last()
    val dim = last.inDim
    // Where the labels it has sit: the mean of their rows, with the kept mean weighing as the rows it is over
    val anchor: DoubleArray? = run {
        val sum = DoubleArray(dim)
        var weight = 0.0
        y.indices.filter { y[it] < firstNew }.forEach { r -> for (i in 0 until dim) sum[i] += features[r][i].toDouble(); weight += 1.0 }
        if (reference != null && referenceRows > 0) {
            for (i in 0 until dim) sum[i] += reference[i].toDouble() * referenceRows
            weight += referenceRows
        }
        if (weight > 0) DoubleArray(dim) { sum[it] / weight } else null
    }
    val columnNorm = (0 until last.outDim).map { j -> sqrt((0 until dim).sumOf { i -> last.w[i * last.outDim + j].toDouble().let { it * it } }) }.average()
    // log of the summed exp of the outputs the head has, for a feature vector: what a new output's score is weighed against
    fun rest(f: FloatArray): Double {
        val z = DoubleArray(last.outDim) { j -> last.b[j] + (0 until dim).sumOf { i -> f[i].toDouble() * last.w[i * last.outDim + j] } }
        val top = z.max()
        return top + ln(z.sumOf { exp(it - top) })
    }
    val rests = features.map(::rest)
    val referenceRest = reference?.let(::rest)
    return net.withMoreOutputs(extra) { e ->
        val label = firstNew + e
        val rows = y.indices.filter { y[it] == label }
        val centre = DoubleArray(dim)
        rows.forEach { r -> for (i in 0 until dim) centre[i] += features[r][i].toDouble() }
        if (rows.isNotEmpty()) for (i in 0 until dim) centre[i] /= rows.size
        val direction = DoubleArray(dim) { centre[it] - (anchor?.get(it) ?: 0.0) }
        val norm = sqrt(direction.sumOf { it * it }).takeIf { it > 1e-12 } ?: 1.0
        val column = FloatArray(dim) { (direction[it] / norm * columnNorm).toFloat() }
        fun dot(f: FloatArray) = (0 until dim).sumOf { column[it].toDouble() * f[it] }
        // The new output's probability on a row is sigmoid(score + bias - rest): margins without the bias
        val mine = rows.map { dot(features[it]) - rests[it] }
        val others = y.indices.filter { y[it] != label }.map { dot(features[it]) - rests[it] } +
            listOfNotNull(reference?.let { dot(it) - referenceRest!! })
        val bias = if (mine.isEmpty()) last.b.average() else {
            fun sigmoid(v: Double) = 1.0 / (1.0 + exp(-v))
            // Decreasing in the bias: what it misses of its rows less what it takes of the others (or less 10%)
            val gap: (Double) -> Double = if (others.isEmpty()) { b -> mine.map { 1 - sigmoid(it + b) }.average() - 0.1 }
                else { b -> mine.map { 1 - sigmoid(it + b) }.average() - others.map { sigmoid(it + b) }.average() }
            var lo = -(mine + others).max() - 30.0
            var hi = -(mine + others).min() + 30.0
            repeat(80) {
                val mid = (lo + hi) / 2
                if (gap(mid) > 0) lo = mid else hi = mid
            }
            (lo + hi) / 2
        }
        column to bias.toFloat()
    }
}

/** A label the model has that [label] is probably a misspelling of: the same but for a plural s, or one letter apart */
internal fun nearLabel(label: String, known: List<String>): String? {
    val l = label.lowercase()
    return known.firstOrNull { k ->
        val kl = k.lowercase()
        kl.trimEnd('s') == l.trimEnd('s') || (minOf(kl.length, l.length) > 3 && editDistance(kl, l) <= 1)
    }
}

private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val row = IntArray(b.length + 1)
        row[0] = i
        for (j in 1..b.length) row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        previous = row
    }
    return previous[b.length]
}
