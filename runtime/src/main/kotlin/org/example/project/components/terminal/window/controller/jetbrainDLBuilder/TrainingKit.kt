package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import org.example.project.components.terminal.window.controller.modules.DenseExport
import org.example.project.components.terminal.window.controller.modules.computeMetrics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import kotlin.math.sqrt

// What a build, a fine-tune and a calibration share: how a head is fitted, scored per fold, exported and charted

/** A learning curve has about this many points, whatever the epochs */
internal const val CURVE_POINTS = 40

/** The heads of a build whose curves are kept with the run: the best ones by score, and the chosen one */
internal const val CURVE_HEADS = 8

/** The share of the training rows early stopping puts aside to watch */
internal const val MONITOR_FRACTION = 0.1

/** The watch rows are scored every this many epochs, so a curve has about [CURVE_POINTS] points */
internal fun curveEvery(epochs: Int): Int = ((epochs + CURVE_POINTS - 1) / CURVE_POINTS).coerceAtLeast(1)

private fun r4(v: Double): Double = Math.round(v * 10_000) / 10_000.0

/** A fit's epochs as curve points: those the watch rows were scored on, or every n-th one when nothing was watched */
internal fun curveOf(history: List<DenseNet.EpochResult>): List<CurvePoint> {
    if (history.isEmpty()) return emptyList()
    val watched = history.filter { it.validationLoss != null }
    val picked = watched.ifEmpty {
        val every = curveEvery(history.size)
        history.filterIndexed { i, _ -> i % every == 0 } + history.last()
    }
    return picked.distinctBy { it.epoch }.map {
        CurvePoint(it.epoch, r4(it.loss), r4(it.accuracy), it.validationLoss?.let(::r4), it.validationAccuracy?.let(::r4), it.monitorLoss?.let(::r4))
    }
}

/** One weight per label so every label counts the same: a row of a rare label weighs more */
internal fun balancedWeights(trainY: IntArray, labels: Int): FloatArray {
    val counts = trainY.toList().groupingBy { it }.eachCount()
    return FloatArray(labels) { c -> (trainY.size.toDouble() / (labels * (counts[c] ?: 1))).toFloat() }
}

/**
 * Fits [net] on [x]/[y] with [settings]. With early stopping a tenth of the rows (per label) is put aside and its
 * loss watched; the head keeps the weights of its best epoch there. [watch] rows are only scored, for the curve.
 */
internal fun fitHead(
    net: DenseNet,
    x: Array<FloatArray>,
    y: IntArray,
    epochs: Int,
    batchSize: Int,
    learningRate: Float,
    seed: Int,
    classWeights: FloatArray?,
    settings: FitSettings,
    watch: Pair<Array<FloatArray>, IntArray>?,
    onEpoch: (DenseNet.EpochResult) -> Unit,
): DenseNet.EpochResult {
    if (settings.patience > 0) {
        val (inner, held) = stratifiedSplit(y, MONITOR_FRACTION, seed + 7)
        if (held.isNotEmpty() && inner.isNotEmpty()) {
            val ix = inner.map { x[it] }.toTypedArray()
            val iy = IntArray(inner.size) { y[inner[it]] }
            val monitor = held.map { x[it] }.toTypedArray() to IntArray(held.size) { y[held[it]] }
            return net.fit(ix, iy, epochs, minOf(batchSize, ix.size), learningRate, seed, classWeights, settings, watch, curveEvery(epochs), monitor, onEpoch)
        }
    }
    return net.fit(x, y, epochs, minOf(batchSize, x.size), learningRate, seed, classWeights, settings, watch, curveEvery(epochs), null, onEpoch)
}

/** What a cross-validation of one head came to: the mean scores over the folds, their spread, and every row's held-out answer */
internal class FoldScores(val accuracy: Double, val macroF1: Double, val spread: Double, val folds: Int, val outOfFold: List<FloatArray?>)

/**
 * [arch] trained [folds] times on the training rows, each time without one fold and scored on it: the mean and
 * spread of the scores, and the probabilities every row got from the head that did not see it. Macro F1 of a fold
 * averages the labels that have rows in it, so a small label missing from a fold doesn't count as a zero.
 */
internal fun crossValidate(
    arch: Architecture,
    x: Array<FloatArray>,
    y: IntArray,
    foldOf: IntArray,
    folds: Int,
    labels: List<String>,
    classWeights: FloatArray?,
    seed: Int,
    onEpoch: (fold: Int, epoch: Int) -> Unit,
): FoldScores {
    val outOfFold = arrayOfNulls<FloatArray>(x.size)
    val accuracies = ArrayList<Double>()
    val f1s = ArrayList<Double>()
    for (f in 0 until folds) {
        val inRows = x.indices.filter { foldOf[it] != f }
        val outRows = x.indices.filter { foldOf[it] == f }
        if (inRows.isEmpty() || outRows.isEmpty()) continue
        val foldSeed = seed + 1000 * (f + 1)
        val net = DenseNet(x[0].size, arch.hidden, labels.size, arch.activation, seed = foldSeed)
        val fx = inRows.map { x[it] }.toTypedArray()
        val fy = IntArray(inRows.size) { y[inRows[it]] }
        fitHead(net, fx, fy, arch.epochs, arch.batchSize, arch.learningRate.toFloat(), foldSeed, classWeights, arch.fitSettings(), null) { onEpoch(f, it.epoch) }
        val truth = IntArray(outRows.size) { y[outRows[it]] }
        val prediction = IntArray(outRows.size) { n ->
            val p = net.predictProbabilities(x[outRows[n]])
            outOfFold[outRows[n]] = p
            DenseNet.argmax(p)
        }
        val scored = computeMetrics(labels, truth, prediction, fx.size, emptyList())
        accuracies += scored.accuracy
        f1s += scored.labels.values.filter { it.support > 0 }.map { it.f1 }.ifEmpty { listOf(0.0) }.average()
    }
    val mean = if (f1s.isEmpty()) 0.0 else f1s.average()
    val spread = if (f1s.size < 2) 0.0 else sqrt(f1s.sumOf { (it - mean) * (it - mean) } / f1s.size)
    return FoldScores(if (accuracies.isEmpty()) 0.0 else accuracies.average(), mean, spread, f1s.size, outOfFold.toList())
}

/** The trained layers in ONNX form; the logits are exported behind a Softmax so callers get probabilities */
internal fun denseLayersOf(model: DenseNet): List<DenseExport> = model.layers.mapIndexed { i, layer ->
    val hiddenActivation = if (model.activation.equals("Tanh", ignoreCase = true)) "Tanh" else "Relu"
    DenseExport(layer.w.copyOf(), layer.b.copyOf(), layer.inDim.toLong(), layer.outDim.toLong(), if (i == model.layers.lastIndex) "Softmax" else hiddenActivation)
}

/** Up to [max] rows of [rows], evenly spread, so a curve on a big validation set stays cheap */
internal fun spreadSample(rows: List<Int>, max: Int): List<Int> =
    if (rows.size <= max) rows else List(max) { rows[(it.toLong() * rows.size / max).toInt()] }

/** The mean of [rows] (all as long as the first), null for none */
internal fun meanVector(rows: List<FloatArray>): FloatArray? {
    if (rows.isEmpty()) return null
    val sum = DoubleArray(rows.first().size)
    rows.forEach { r -> for (i in sum.indices) sum[i] += r[i].toDouble() }
    return FloatArray(sum.size) { (sum[it] / rows.size).toFloat() }
}

/** Floats as base64 of their little-endian bytes: a vector kept on one line of module.json */
fun encodeFloats(values: FloatArray): String {
    val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach { buffer.putFloat(it) }
    return Base64.getEncoder().encodeToString(buffer.array())
}

/** The floats [encodeFloats] wrote; null for anything else */
fun decodeFloats(text: String): FloatArray? = runCatching {
    val bytes = Base64.getDecoder().decode(text)
    require(bytes.size % 4 == 0)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    FloatArray(bytes.size / 4) { buffer.getFloat() }
}.getOrNull()

internal fun f3(value: Double) = "%.3f".format(Locale.US, value)
internal fun f4(value: Double) = "%.4f".format(Locale.US, value)
