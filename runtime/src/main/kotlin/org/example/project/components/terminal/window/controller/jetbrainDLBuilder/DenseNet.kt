package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * What a fit does beyond its epochs, batch size and rate. Every setting is off by default, and off it changes
 * nothing: a fit with the defaults is the plain fit, to the bit.
 */
data class FitSettings(
    /** Decoupled weight decay (AdamW): every step shrinks each weight by the rate times this; biases are left alone */
    val weightDecay: Float = 0f,
    /** The share of hidden units dropped for each row while training (inverted dropout); answers use every unit */
    val dropout: Float = 0f,
    /** The target gives this much of its mass evenly to every label, so the head learns to be less sure */
    val labelSmoothing: Float = 0f,
    /** How the rate moves over the epochs: constant, cosine (eases down to a hundredth) or linear (straight down to a hundredth) */
    val schedule: String = "constant",
    /** The rate climbs to its value over these first epochs */
    val warmupEpochs: Int = 0,
    /** Stops when the loss on the monitor rows has not improved for this many epochs and keeps the best epoch's weights; 0 never stops */
    val patience: Int = 0,
    /**
     * Weights to stay near, one array per parameter in the order of [DenseNet.parameterArrays] (w, b of each layer);
     * NaN marks a value that is free. Fine-tuning anchors the head it started from (L2-SP)
     */
    val anchor: List<FloatArray>? = null,
    /** How hard [anchor] pulls: the loss gains strength / 2 times the squared distance to it */
    val anchorStrength: Float = 0f,
    /** The first n layers keep their weights; only the layers after them learn */
    val frozenLayers: Int = 0,
) {
    init {
        require(weightDecay >= 0f && weightDecay < 1f) { "weightDecay must be at least 0 and below 1, got $weightDecay" }
        require(dropout >= 0f && dropout < 0.95f) { "dropout must be at least 0 and below 0.95, got $dropout" }
        require(labelSmoothing >= 0f && labelSmoothing < 1f) { "labelSmoothing must be at least 0 and below 1, got $labelSmoothing" }
        require(schedule in SCHEDULES) { "schedule must be one of ${SCHEDULES.joinToString()}, got $schedule" }
        require(warmupEpochs >= 0 && patience >= 0 && frozenLayers >= 0) { "warmupEpochs, patience and frozenLayers can't be negative" }
        require(anchorStrength >= 0f) { "anchorStrength can't be negative" }
    }

    /** The rate's factor in [epoch] (from 1) of [epochs]: exactly 1 for a constant schedule without warm-up */
    fun rateFactor(epoch: Int, epochs: Int): Float {
        if (warmupEpochs > 0 && epoch <= warmupEpochs) return epoch.toFloat() / (warmupEpochs + 1)
        if (schedule == "constant") return 1f
        val span = (epochs - warmupEpochs - 1).coerceAtLeast(1)
        val progress = ((epoch - warmupEpochs - 1).toDouble() / span).coerceIn(0.0, 1.0)
        val eased = if (schedule == "cosine") 0.5 * (1 + cos(PI * progress)) else 1 - progress
        return (MIN_RATE_FACTOR + (1 - MIN_RATE_FACTOR) * eased).toFloat()
    }

    companion object {
        val SCHEDULES = listOf("constant", "cosine", "linear")
        private const val MIN_RATE_FACTOR = 0.01
    }
}

/**
 * A feed-forward classifier (input -> hidden layers with relu or tanh -> softmax) trained with Adam on plain Kotlin
 * arrays; no native library, so it runs everywhere the app does. Any number of hidden layers, none included: a
 * linear classifier. Weights are row-major [in][out], the layout denseStackToOnnx exports.
 */
class DenseNet(val inputDim: Int, val hidden: List<Int>, val outputDim: Int, val activation: String = "Relu", seed: Int = 42) {
    /** For the one-hidden-layer callers that still ask: the first hidden width, 0 for a linear head */
    constructor(inputDim: Int, hiddenDim: Int, outputDim: Int, seed: Int = 42) : this(inputDim, if (hiddenDim > 0) listOf(hiddenDim) else emptyList(), outputDim, "Relu", seed)

    class Layer(val inDim: Int, val outDim: Int) {
        val w = FloatArray(inDim * outDim)
        val b = FloatArray(outDim)
    }

    val layers: List<Layer> = (listOf(inputDim) + hidden + outputDim).zipWithNext { a, b -> Layer(a, b) }
    val hiddenDim: Int get() = hidden.firstOrNull() ?: 0
    val parameters: Long get() = layers.sumOf { (it.w.size + it.b.size).toLong() }
    private val useTanh = activation.equals("Tanh", ignoreCase = true)

    init {
        val random = Random(seed)
        layers.forEach { glorot(it.w, it.inDim, it.outDim, random) }
    }

    /**
     * One epoch of a fit: the mean loss and accuracy on the training rows as they went by, and on the rows the fit
     * was asked to watch (the validation rows, for the learning curve) and to monitor (early stopping), when given
     */
    class EpochResult(
        val epoch: Int,
        val loss: Double,
        val accuracy: Double,
        val validationLoss: Double? = null,
        val validationAccuracy: Double? = null,
        val monitorLoss: Double? = null,
    )

    /** Every epoch of the last fit, in order */
    var history: List<EpochResult> = emptyList()
        private set

    /** The epoch whose weights the last fit kept: the last one, or the best one when early stopping kept an earlier one */
    var keptEpoch: Int = 0
        private set

    /** The epoch the last fit stopped at early, null when it ran every epoch */
    var stoppedAt: Int? = null
        private set

    /**
     * Trains on [x] with class indices [y]; returns the result of the epoch whose weights are kept (the last one,
     * or the best one on [monitor] when [FitSettings.patience] stopped it early). [onEpoch] is called after every
     * epoch so callers can report progress. [watch] rows are only scored, every epoch, for the learning curve.
     */
    fun fit(
        x: Array<FloatArray>,
        y: IntArray,
        epochs: Int = 150,
        batchSize: Int = 16,
        learningRate: Float = 0.01f,
        seed: Int = 42,
        /** One weight per class; the loss and gradient of a row are multiplied by its class's weight */
        classWeights: FloatArray? = null,
        settings: FitSettings = FitSettings(),
        /** Rows scored after every epoch for the learning curve; they never change what is learned */
        watch: Pair<Array<FloatArray>, IntArray>? = null,
        /** Score [watch] only every this many epochs (and at the first and last), for long fits on many rows */
        watchEvery: Int = 1,
        /** Rows early stopping watches; needed for [FitSettings.patience] to stop anything */
        monitor: Pair<Array<FloatArray>, IntArray>? = null,
        onEpoch: (EpochResult) -> Unit = {},
    ): EpochResult {
        require(x.size == y.size && x.isNotEmpty()) { "fit needs the same number of inputs and labels" }
        require(classWeights == null || classWeights.size == outputDim) { "classWeights needs one weight per class" }
        require(settings.frozenLayers < layers.size) { "at least the last layer has to learn: frozenLayers must be below ${layers.size}" }
        val anchor = settings.anchor
        require(anchor == null || (anchor.size == layers.size * 2 && anchor.indices.all { anchor[it].size == parameterArrays[it].size })) {
            "the anchor needs one array per parameter array, sized like it"
        }
        val params = parameterArrays
        val adam = Adam(params, learningRate)
        val random = Random(seed)
        // Masks get their own generator, so a fit without dropout draws exactly the numbers it always drew
        val dropRandom = if (settings.dropout > 0f) Random(seed * 31 + 17) else null
        val frozenArrays = settings.frozenLayers * 2
        val stopping = settings.patience > 0 && monitor != null
        var last = EpochResult(0, 0.0, 0.0)
        var best: EpochResult? = null
        var bestWeights: List<FloatArray>? = null
        val epochsRun = ArrayList<EpochResult>(epochs)
        stoppedAt = null
        val order = x.indices.toMutableList()
        for (epoch in 1..epochs) {
            order.shuffle(random)
            val factor = settings.rateFactor(epoch, epochs)
            val rate = if (factor == 1f) learningRate else learningRate * factor
            var lossSum = 0.0
            var correct = 0
            for (start in order.indices step batchSize) {
                val batch = order.subList(start, minOf(start + batchSize, order.size))
                val grads = layers.map { Layer(it.inDim, it.outDim) }
                for (i in batch) {
                    val pass = forward(x[i], dropRandom, settings.dropout)
                    val p = pass.activations.last()
                    val weight = classWeights?.get(y[i]) ?: 1f
                    lossSum += if (settings.labelSmoothing == 0f) -ln(p[y[i]].toDouble().coerceAtLeast(1e-9)) * weight
                    else smoothedLoss(p, y[i], settings.labelSmoothing) * weight
                    if (argmax(p) == y[i]) correct++
                    backward(pass, y[i], grads, weight / batch.size, settings.labelSmoothing, settings.frozenLayers)
                }
                val gradArrays = grads.flatMap { listOf(it.w, it.b) }
                if (anchor != null && settings.anchorStrength > 0f) pullTowards(gradArrays, anchor, settings.anchorStrength, frozenArrays)
                adam.step(gradArrays, rate, settings.weightDecay, frozenArrays)
            }
            val monitored = if (stopping) score(monitor!!.first, monitor.second) else null
            val improved = monitored != null && (best == null || monitored.first < best.monitorLoss!! - 1e-7)
            val stopNow = monitored != null && !improved && best != null && epoch - best.epoch >= settings.patience
            val watchNow = epoch == 1 || epoch == epochs || epoch % watchEvery.coerceAtLeast(1) == 0 || stopNow
            val watched = watch?.takeIf { watchNow }?.let { (wx, wy) -> score(wx, wy) }
            last = EpochResult(epoch, lossSum / x.size, correct.toDouble() / x.size, watched?.first, watched?.second, monitored?.first)
            epochsRun += last
            onEpoch(last)
            if (improved) {
                best = last
                bestWeights = params.map { it.copyOf() }
            } else if (stopNow) {
                stoppedAt = epoch
                break
            }
        }
        history = epochsRun
        val kept = if (stopping && best != null && bestWeights != null && best.epoch != last.epoch) {
            params.forEachIndexed { i, p -> bestWeights[i].copyInto(p) }
            best
        } else last
        keptEpoch = kept.epoch
        return kept
    }

    /** The weight and bias arrays of every layer, in order (w0, b0, w1, b1, …); the arrays themselves, not copies */
    val parameterArrays: List<FloatArray> get() = layers.flatMap { listOf(it.w, it.b) }

    /** A copy of every weight and bias, in the order of [parameterArrays] */
    fun copyWeights(): List<FloatArray> = parameterArrays.map { it.copyOf() }

    /** Puts [weights] (as [copyWeights] gives them) into the layers */
    fun loadWeights(weights: List<FloatArray>) {
        require(weights.size == layers.size * 2) { "expected ${layers.size * 2} arrays, got ${weights.size}" }
        parameterArrays.forEachIndexed { i, p ->
            require(weights[i].size == p.size) { "array $i has ${weights[i].size} values, the layer needs ${p.size}" }
            weights[i].copyInto(p)
        }
    }

    /** Mean loss and accuracy on rows, with every unit in use */
    fun score(x: Array<FloatArray>, y: IntArray): Pair<Double, Double> {
        if (x.isEmpty()) return 0.0 to 0.0
        var loss = 0.0
        var correct = 0
        for (i in x.indices) {
            val p = predictProbabilities(x[i])
            loss += -ln(p[y[i]].toDouble().coerceAtLeast(1e-9))
            if (argmax(p) == y[i]) correct++
        }
        return loss / x.size to correct.toDouble() / x.size
    }

    fun predictProbabilities(x: FloatArray): FloatArray = forward(x).activations.last()

    fun predict(x: FloatArray): Int = argmax(predictProbabilities(x))

    /** The last layer's output before the softmax: what a temperature divides */
    fun logits(x: FloatArray): FloatArray = forward(x).preActivations.last()

    /** What the last layer reads: the last hidden layer's activations, or the input itself for a linear head */
    fun features(x: FloatArray): FloatArray = forward(x).activations[layers.lastIndex]

    /**
     * activations[0] is the input, activations[l + 1] the output of layer l (softmax for the last, after dropout for a
     * hidden one); preActivations[l] its z; masks[l] the dropout scale of hidden layer l's units, null without dropout
     */
    private class Pass(val activations: List<FloatArray>, val preActivations: List<FloatArray>, val masks: List<FloatArray?>)

    private fun forward(x: FloatArray, dropRandom: Random? = null, dropout: Float = 0f): Pass {
        val activations = mutableListOf(x)
        val pre = mutableListOf<FloatArray>()
        val masks = ArrayList<FloatArray?>(layers.size)
        layers.forEachIndexed { l, layer ->
            val input = activations.last()
            // Row by row over the weights: the same additions in the same order as one dot product per unit, so the
            // same floats, with the weights read in the order they lie in memory
            val z = layer.b.copyOf()
            val out = layer.outDim
            for (i in 0 until layer.inDim) {
                val xi = input[i]
                val row = i * out
                for (j in 0 until out) z[j] += xi * layer.w[row + j]
            }
            pre += z
            if (l == layers.lastIndex) {
                activations += softmax(z)
                masks += null
            } else {
                val a = FloatArray(z.size) { activate(z[it]) }
                if (dropRandom != null && dropout > 0f) {
                    val keep = 1f / (1f - dropout)
                    val mask = FloatArray(a.size) { if (dropRandom.nextFloat() < dropout) 0f else keep }
                    for (j in a.indices) a[j] *= mask[j]
                    masks += mask
                } else masks += null
                activations += a
            }
        }
        return Pass(activations, pre, masks)
    }

    private fun activate(z: Float): Float = if (useTanh) tanh(z) else if (z > 0f) z else 0f
    private fun activateGradient(z: Float, a: Float): Float = if (useTanh) 1f - a * a else if (z > 0f) 1f else 0f

    private fun smoothedLoss(p: FloatArray, label: Int, epsilon: Float): Double {
        val spread = epsilon / p.size
        var loss = 0.0
        for (k in p.indices) {
            val target = (if (k == label) 1f - epsilon else 0f) + spread
            loss += -target * ln(p[k].toDouble().coerceAtLeast(1e-9))
        }
        return loss
    }

    // Cross-entropy gradients, scaled so a batch contributes its mean; layers under [frozen] get none
    private fun backward(pass: Pass, label: Int, grads: List<Layer>, scale: Float, smoothing: Float = 0f, frozen: Int = 0) {
        val output = pass.activations.last()
        var delta = if (smoothing == 0f) FloatArray(outputDim) { k -> (output[k] - if (k == label) 1f else 0f) * scale }
        else {
            val spread = smoothing / outputDim
            FloatArray(outputDim) { k -> (output[k] - ((if (k == label) 1f - smoothing else 0f) + spread)) * scale }
        }
        for (l in layers.indices.reversed()) {
            val layer = layers[l]
            val input = pass.activations[l]
            val g = grads[l]
            for (k in 0 until layer.outDim) g.b[k] += delta[k]
            for (i in 0 until layer.inDim) {
                val xi = input[i]
                if (xi == 0f) continue
                for (k in 0 until layer.outDim) g.w[i * layer.outDim + k] += xi * delta[k]
            }
            if (l == 0 || l <= frozen) break
            val z = pass.preActivations[l - 1]
            val mask = pass.masks[l - 1]
            val next = FloatArray(layer.inDim) { i ->
                var s = 0f
                for (k in 0 until layer.outDim) s += layer.w[i * layer.outDim + k] * delta[k]
                if (mask == null) s * activateGradient(z[i], input[i])
                else if (mask[i] == 0f) 0f
                else s * mask[i] * activateGradient(z[i], activate(z[i]))
            }
            delta = next
        }
    }

    // L2-SP: the gradient of strength / 2 · |θ − anchor|² added to every anchored value of the learning arrays
    private fun pullTowards(grads: List<FloatArray>, anchor: List<FloatArray>, strength: Float, frozenArrays: Int) {
        val params = parameterArrays
        for (p in frozenArrays until grads.size) {
            val g = grads[p]; val a = anchor[p]; val param = params[p]
            for (i in g.indices) if (!a[i].isNaN()) g[i] += strength * (param[i] - a[i])
        }
    }

    private class Adam(private val params: List<FloatArray>, private val lr: Float, private val beta1: Float = 0.9f, private val beta2: Float = 0.999f, private val eps: Float = 1e-7f) {
        private val m = params.map { FloatArray(it.size) }
        private val v = params.map { FloatArray(it.size) }
        private var t = 0

        /** One step at [rate]; [decay] shrinks the weight arrays (even positions), arrays before [from] stay as they are */
        fun step(grads: List<FloatArray>, rate: Float = lr, decay: Float = 0f, from: Int = 0) {
            t++
            val correction1 = 1f - Math.pow(beta1.toDouble(), t.toDouble()).toFloat()
            val correction2 = 1f - Math.pow(beta2.toDouble(), t.toDouble()).toFloat()
            for (p in from until params.size) {
                val param = params[p]; val g = grads[p]; val mp = m[p]; val vp = v[p]
                val shrink = decay > 0f && p % 2 == 0
                for (i in param.indices) {
                    mp[i] = beta1 * mp[i] + (1f - beta1) * g[i]
                    vp[i] = beta2 * vp[i] + (1f - beta2) * g[i] * g[i]
                    val mHat = mp[i] / correction1
                    val vHat = vp[i] / correction2
                    if (shrink) param[i] -= rate * decay * param[i]
                    param[i] -= rate * mHat / (sqrt(vHat) + eps)
                }
            }
        }
    }

    /**
     * This head with [extra] more outputs: the layers are copied, the last one gets new columns, filled by
     * [init] (the new label's weights over the last layer's inputs and its bias)
     */
    fun withMoreOutputs(extra: Int, init: (label: Int) -> Pair<FloatArray, Float>): DenseNet {
        require(extra >= 0) { "extra can't be negative" }
        val grown = DenseNet(inputDim, hidden, outputDim + extra, activation)
        layers.dropLast(1).forEachIndexed { l, layer ->
            layer.w.copyInto(grown.layers[l].w)
            layer.b.copyInto(grown.layers[l].b)
        }
        val old = layers.last()
        val new = grown.layers.last()
        for (i in 0 until old.inDim) for (j in 0 until old.outDim) new.w[i * new.outDim + j] = old.w[i * old.outDim + j]
        old.b.copyInto(new.b)
        for (e in 0 until extra) {
            val (column, bias) = init(e)
            require(column.size == old.inDim) { "a new label's weights need ${old.inDim} values, got ${column.size}" }
            val j = old.outDim + e
            for (i in 0 until old.inDim) new.w[i * new.outDim + j] = column[i]
            new.b[j] = bias
        }
        return grown
    }

    companion object {
        private fun glorot(w: FloatArray, fanIn: Int, fanOut: Int, random: Random) {
            val limit = sqrt(6.0 / (fanIn + fanOut)).toFloat()
            for (i in w.indices) w[i] = (random.nextFloat() * 2f - 1f) * limit
        }

        fun softmax(z: FloatArray): FloatArray {
            val max = z.maxOrNull() ?: 0f
            val e = FloatArray(z.size) { exp((z[it] - max).toDouble()).toFloat() }
            val sum = e.sum()
            return FloatArray(z.size) { e[it] / sum }
        }

        fun argmax(values: FloatArray): Int = values.indices.maxByOrNull { values[it] } ?: 0
    }
}
