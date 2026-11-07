package org.example.project.components.terminal.window.controller.metrics

import org.example.project.components.terminal.window.controller.modules.ScoreRange
import org.example.project.components.terminal.window.controller.modules.macroF1Of
import java.util.Locale
import kotlin.random.Random

/**
 * How far a run's accuracy and macro F1 can be trusted, as a percentile bootstrap: the rows it was scored on drawn
 * again [RESAMPLES] times with replacement, label by label, as many of each label as there are, each draw scored as
 * computeMetrics scores (the same F1 rule, presentOnly and all), and the middle 95% of the scores kept. Label by label,
 * so no label goes missing from a draw: a build averages every label, and one with no rows would count an F1 of 0
 * (nine rows all answered right had a range down to 0.667). Seeded, so a run's ranges come out the same every time;
 * with random numbers of its own, so training never sees them.
 */
object ScoreIntervals {
    const val RESAMPLES = 1000
    const val SEED = 7L

    /** What a range is, in words: "the middle 95% of 1,000 resamples" */
    val said: String = "the middle 95% of ${String.format(Locale.US, "%,d", RESAMPLES)} resamples"

    /**
     * The ranges of accuracy and macro F1 of rows of [truth] answered [prediction] (both indices below [labelCount]),
     * the macro F1 with computeMetrics' [presentOnly]; null with no rows
     */
    fun of(labelCount: Int, truth: IntArray, prediction: IntArray, presentOnly: Boolean = false, resamples: Int = RESAMPLES, seed: Long = SEED): Pair<ScoreRange, ScoreRange>? {
        val n = truth.size
        if (n == 0 || labelCount == 0 || resamples <= 0) return null
        val byLabel = (0 until labelCount).map { c -> truth.indices.filter { truth[it] == c }.toIntArray() }.filter { it.isNotEmpty() }
        val random = Random(seed)
        val confusion = Array(labelCount) { IntArray(labelCount) }
        val accuracy = DoubleArray(resamples)
        val macroF1 = DoubleArray(resamples)
        for (r in 0 until resamples) {
            confusion.forEach { it.fill(0) }
            var right = 0
            for (rows in byLabel) repeat(rows.size) {
                val i = rows[random.nextInt(rows.size)]
                confusion[truth[i]][prediction[i]]++
                if (truth[i] == prediction[i]) right++
            }
            accuracy[r] = right.toDouble() / n
            macroF1[r] = macroF1Of(confusion, presentOnly)
        }
        return middle(accuracy) to middle(macroF1)
    }

    /** Where the middle 95% of [scores] lie: the 25th and the 975th of a thousand, by rank */
    private fun middle(scores: DoubleArray): ScoreRange {
        scores.sort()
        val k = scores.size
        return ScoreRange(scores[(k * 25 + 999) / 1000 - 1], scores[(k * 975 + 999) / 1000 - 1])
    }
}
