package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import org.example.project.components.terminal.window.controller.modules.Mistake
import org.example.project.components.terminal.window.controller.modules.ReliabilityBin
import org.example.project.components.terminal.window.controller.modules.RunAnalysis
import org.example.project.components.terminal.window.controller.modules.SelectivePoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * How far a model's scores can be taken at their word, and how to make them mean what they say. Pure functions over
 * probabilities (one row per text, one value per label in the model's label order), so the build, fine-tuning,
 * calibration and validation runs share them and the tests check them on numbers.
 *
 * Calibration is temperature scaling: the last layer's output is divided by one number T before the softmax; T is
 * the one that makes the validation rows' answers least surprising (lowest log loss). It changes how sure the
 * scores are, never which label is on top, and it folds into the head's last layer, so the exported model is the
 * same graph with other weights.
 */
object Calibration {
    const val BINS = 10
    const val MIN_ROWS = 10
    private const val LOW = 0.05
    private const val HIGH = 20.0

    /** Mean negative log-likelihood of the true labels under softmax(logits / t) */
    fun nll(logits: List<FloatArray>, y: IntArray, t: Double): Double {
        if (logits.isEmpty()) return 0.0
        var sum = 0.0
        for (r in logits.indices) {
            val z = logits[r]
            var max = Double.NEGATIVE_INFINITY
            for (v in z) if (v / t > max) max = v / t
            var total = 0.0
            for (v in z) total += exp(v / t - max)
            sum += -(z[y[r]] / t - max - ln(total))
        }
        return sum / logits.size
    }

    /** The temperature with the lowest [nll] on the rows: a golden-section search over log T between 0.05 and 20 */
    fun fitTemperature(logits: List<FloatArray>, y: IntArray): Double {
        require(logits.size == y.size) { "one label per row" }
        if (logits.isEmpty()) return 1.0
        val phi = (sqrt(5.0) - 1) / 2
        var a = ln(LOW)
        var b = ln(HIGH)
        var c = b - phi * (b - a)
        var d = a + phi * (b - a)
        var fc = nll(logits, y, exp(c))
        var fd = nll(logits, y, exp(d))
        repeat(80) {
            if (fc < fd) { b = d; d = c; fd = fc; c = b - phi * (b - a); fc = nll(logits, y, exp(c)) }
            else { a = c; c = d; fc = fd; d = a + phi * (b - a); fd = nll(logits, y, exp(d)) }
        }
        val t = exp((a + b) / 2)
        // A temperature that only moves the log loss by noise is not worth folding in
        return if (nll(logits, y, t) < nll(logits, y, 1.0) - 1e-9) t else 1.0
    }

    /** softmax(logits / t) */
    fun probabilities(logits: FloatArray, t: Double = 1.0): FloatArray {
        val scaled = FloatArray(logits.size) { (logits[it] / t).toFloat() }
        return DenseNet.softmax(scaled)
    }

    /** Divides the last layer by [t], so the softmax of its output is the softmax at temperature t; nothing else changes */
    fun fold(net: DenseNet, t: Double) {
        if (t == 1.0) return
        val last = net.layers.last()
        for (i in last.w.indices) last.w[i] = (last.w[i] / t).toFloat()
        for (i in last.b.indices) last.b[i] = (last.b[i] / t).toFloat()
    }

    /** The expected calibration error of the top score over [bins] equal bins, and the bins that have rows */
    fun reliability(probabilities: List<FloatArray>, y: IntArray, bins: Int = BINS): Pair<Double, List<ReliabilityBin>> {
        if (probabilities.isEmpty()) return 0.0 to emptyList()
        val count = IntArray(bins)
        val confidence = DoubleArray(bins)
        val right = IntArray(bins)
        probabilities.forEachIndexed { r, p ->
            val top = DenseNet.argmax(p)
            val score = p[top].toDouble().coerceIn(0.0, 1.0)
            val bin = (score * bins).toInt().coerceAtMost(bins - 1)
            count[bin]++
            confidence[bin] += score
            if (top == y[r]) right[bin]++
        }
        val n = probabilities.size.toDouble()
        var ece = 0.0
        val out = ArrayList<ReliabilityBin>()
        for (b in 0 until bins) {
            if (count[b] == 0) continue
            val conf = confidence[b] / count[b]
            val acc = right[b].toDouble() / count[b]
            ece += count[b] / n * abs(acc - conf)
            out += ReliabilityBin(b.toDouble() / bins, (b + 1).toDouble() / bins, count[b], conf, acc)
        }
        return ece to out
    }

    /** What a gate on the top score keeps and how often what it keeps is right, at thresholds 0, 0.05, … 0.95 */
    fun selective(probabilities: List<FloatArray>, y: IntArray): List<SelectivePoint> {
        if (probabilities.isEmpty()) return emptyList()
        val tops = probabilities.mapIndexed { r, p -> val top = DenseNet.argmax(p); p[top].toDouble() to (top == y[r]) }
        return (0 until 20).map { k ->
            val threshold = k * 0.05
            val kept = tops.filter { it.first >= threshold - 1e-12 }
            SelectivePoint(threshold, kept.size.toDouble() / tops.size, if (kept.isEmpty()) 0.0 else kept.count { it.second }.toDouble() / kept.size, kept.size)
        }
    }

    /**
     * The lowest gate whose kept answers are right at least [target] of the time, with what it keeps; null when no
     * gate keeps a tenth of the rows at that accuracy. What "answer only above 0.62" buys, in one sentence
     */
    fun suggestGate(points: List<SelectivePoint>, target: Double = 0.95): SelectivePoint? =
        points.filter { it.accuracy >= target && it.coverage >= 0.1 && it.kept > 0 }.minByOrNull { it.threshold }

    /**
     * The analysis of a run from the probabilities of its rows: calibration error and bins, the gates, and the
     * rows it got wrong, the surest first (with their texts only when [texts] is given)
     */
    fun analyse(labels: List<String>, probabilities: List<FloatArray>, y: IntArray, texts: List<String>?, maxMistakes: Int): RunAnalysis {
        val (ece, bins) = reliability(probabilities, y)
        val mistakes = probabilities.indices.mapNotNull { r ->
            val p = probabilities[r]
            val top = DenseNet.argmax(p)
            if (top == y[r]) null
            else Mistake(texts?.getOrNull(r), labels.getOrElse(y[r]) { "${y[r]}" }, labels.getOrElse(top) { "$top" }, p[top].toDouble(), p[y[r]].toDouble())
        }.sortedByDescending { it.score }.take(maxMistakes)
        return RunAnalysis(ece = ece, reliability = bins, selective = selective(probabilities, y), mistakes = mistakes)
    }

    /** "ECE 0.081: a score of 0.9 is right about 82% of the time" style sentence for the console */
    fun describe(analysis: RunAnalysis): List<String> {
        val out = ArrayList<String>()
        analysis.ece?.let { ece ->
            val busiest = analysis.reliability.maxByOrNull { it.count }
            out += "calibration: expected error ${f3(ece)}" + (busiest?.let { b ->
                " (most answers score ${f2(b.from)}–${f2(b.to)}, averaging ${f2(b.confidence)}, and are right ${pct(b.accuracy)} of the time)"
            } ?: "") + if (ece > 0.1) "; the scores say more than they know, buildOptions(calibrate = true) or calibrate(...) evens them out" else ""
        }
        suggestGate(analysis.selective)?.let { g ->
            if (g.threshold > 0.0) out += "a gate at ${f2(g.threshold)} keeps ${pct(g.coverage)} of the rows and ${pct(g.accuracy)} of what it keeps is right"
        }
        return out
    }

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun f3(v: Double) = String.format(Locale.US, "%.3f", v)
    private fun pct(v: Double) = "${Math.round(v * 100)}%"

    /** Probabilities rebuilt from a model's `label:score` answers, in [labels] order; labels it did not answer get 0 */
    fun fromAnswers(labels: List<String>, answers: List<String>): FloatArray {
        val p = FloatArray(labels.size)
        answers.forEach { a ->
            val i = labels.indexOf(a.substringBeforeLast(':'))
            val score = a.substringAfterLast(':', "").toFloatOrNull()
            if (i >= 0 && score != null) p[i] = max(0f, score)
        }
        return p
    }
}
