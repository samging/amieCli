package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import org.example.project.components.terminal.window.controller.modules.MetricsRun
import java.util.Locale

/**
 * What the validation says about the chosen head, in sentences: whether the scores can be trusted, whether the
 * head memorised the training rows, whether it beats answering the most common label, and which labels it
 * confuses. Pure functions over the metrics, so they are unit-tested and reused by the validation runs.
 */
object Diagnostics {
    const val MIN_VALIDATION_ROWS = 10
    const val OVERFIT_GAP = 0.15
    const val BASELINE_MARGIN = 0.05
    const val WEAK_F1 = 0.5

    private fun f3(v: Double) = "%.3f".format(Locale.US, v)

    /** Warnings about the validation set itself: too small to mean much, labels with one row */
    fun aboutValidation(labels: List<String>, truth: IntArray): List<String> {
        val out = mutableListOf<String>()
        if (truth.size < MIN_VALIDATION_ROWS) out += "the validation has only ${truth.size} rows; its scores are rough (each row moves accuracy by ${f3(1.0 / truth.size.coerceAtLeast(1))})"
        val counts = truth.toList().groupingBy { it }.eachCount()
        labels.indices.filter { (counts[it] ?: 0) < 2 }.map { labels[it] }.takeIf { it.isNotEmpty() && truth.size >= MIN_VALIDATION_ROWS }?.let {
            out += "labels with fewer than 2 validation rows: ${it.joinToString()}; their precision and recall are a coin toss"
        }
        return out
    }

    /** Warnings about the head: memorising, barely beating the majority label, confusing labels */
    fun aboutModel(metrics: MetricsRun, labels: List<String>, trainAccuracy: Double): List<String> {
        val out = mutableListOf<String>()
        if (trainAccuracy - metrics.accuracy > OVERFIT_GAP) {
            out += "the head fits the training rows much better than the validation rows (${f3(trainAccuracy)} vs ${f3(metrics.accuracy)}): it memorises; more varied rows, or a smaller head, would generalise better"
        }
        val rowTotals = metrics.confusion.map { it.sum() }
        val total = rowTotals.sum()
        // Against always answering the commonest label; meaningless when the rows are all of one label
        if (total > 0 && rowTotals.count { it > 0 } > 1) {
            val majority = rowTotals.indices.maxByOrNull { rowTotals[it] }!!
            val majorityShare = rowTotals[majority].toDouble() / total
            if (metrics.accuracy - majorityShare < BASELINE_MARGIN) {
                out += "accuracy ${f3(metrics.accuracy)} is barely above always answering '${labels.getOrElse(majority) { "$majority" }}' (${f3(majorityShare)}); the head has learned little"
            }
        }
        metrics.labels.forEach { (label, m) ->
            if (m.f1 < WEAK_F1 && m.support > 0) {
                val i = labels.indexOf(label)
                val row = metrics.confusion.getOrNull(i)
                val takenFor = row?.withIndex()?.filter { it.index != i && it.value > 0 }?.maxByOrNull { it.value }
                out += "label '$label' is weak (F1 ${f3(m.f1)})" + (takenFor?.let { "; it is mostly taken for '${labels.getOrElse(it.index) { "?" }}' (${it.value} of ${m.support} rows)" } ?: "")
            }
        }
        return out
    }
}
