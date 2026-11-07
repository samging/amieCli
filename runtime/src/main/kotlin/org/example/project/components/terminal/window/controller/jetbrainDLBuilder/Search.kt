package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import org.example.project.components.terminal.window.controller.modules.Suspect
import kotlin.random.Random

/** The most heads one search may ask for: each is trained once per fold, so a search past this takes minutes */
const val MAX_SEARCH = 64

/** The layer shapes a search tries unless told otherwise: from a linear head to two wide hidden layers */
val SEARCH_HIDDEN: List<List<Int>> = listOf(emptyList(), listOf(16), listOf(32), listOf(64), listOf(64, 16), listOf(128, 32))

/** The learning rates a search tries unless told otherwise */
val SEARCH_RATES: List<Double> = listOf(0.01, 0.003)

/**
 * Heads to compare, made from every combination of the values given (a grid), or [trials] of them drawn at
 * random when that is smaller: `buildOptions(candidates = searchSpace(hidden = listOf(emptyList(), listOf(32)),
 * learningRates = listOf(0.01, 0.003)))`. A grid past [MAX_SEARCH] heads needs [trials].
 */
fun searchSpace(
    hidden: List<List<Int>> = SEARCH_HIDDEN,
    learningRates: List<Double> = SEARCH_RATES,
    epochs: List<Int> = listOf(150),
    batchSizes: List<Int> = listOf(16),
    activations: List<String> = listOf("Relu"),
    dropouts: List<Double> = listOf(0.0),
    weightDecays: List<Double> = listOf(0.0),
    patience: Int = 0,
    trials: Int = 0,
    seed: Int = 42,
): List<Architecture> {
    listOf(hidden, learningRates, epochs, batchSizes, activations, dropouts, weightDecays).forEach {
        require(it.isNotEmpty()) { "every searched setting needs at least one value" }
    }
    require(trials >= 0) { "trials can't be negative" }
    val grid = ArrayList<Architecture>()
    for (h in hidden) for (lr in learningRates) for (e in epochs) for (b in batchSizes) for (a in activations) for (d in dropouts) for (wd in weightDecays) {
        // Dropout and an activation mean nothing to a linear head: one linear head per remaining setting
        val head = Architecture(hidden = h, epochs = e, batchSize = b, learningRate = lr, activation = if (h.isEmpty()) "Relu" else a, dropout = if (h.isEmpty()) 0.0 else d, weightDecay = wd, patience = patience)
        if (grid.none { it == head }) grid += head
    }
    val chosen = if (trials in 1 until grid.size) grid.shuffled(Random(seed)).take(trials) else grid
    require(chosen.size <= MAX_SEARCH) { "the search asks for ${chosen.size} heads; give trials = $MAX_SEARCH or fewer to draw some of them" }
    return chosen
}

/**
 * Which fold every row is in: each label's rows are shuffled and dealt round the folds, so every fold has about
 * the same mix of labels; the same labels and seed give the same folds
 */
fun stratifiedFolds(labelIndex: IntArray, folds: Int, seed: Int = 42): IntArray {
    require(folds >= 2) { "at least 2 folds" }
    val random = Random(seed + 101)
    val fold = IntArray(labelIndex.size)
    var start = 0
    labelIndex.indices.groupBy { labelIndex[it] }.toSortedMap().values.forEach { rows ->
        rows.shuffled(random).forEachIndexed { n, row -> fold[row] = (start + n) % folds }
        // The next label starts where this one stopped, so small labels don't all land in the first folds
        start = (start + rows.size) % folds
    }
    return fold
}

/**
 * Training rows the held-out heads of a cross-validation doubt. Each row was scored by a head that never saw it;
 * a row is a suspect when that head answered another label, at least [minScore] sure, and gave the row's own label
 * less than the rows of that label get on average (the idea of confident learning: each label is judged by how
 * sure the heads are of it, not by one cut-off). Mislabeled rows look like this; so do rows that are simply hard.
 * The widest disagreements first.
 */
fun findSuspects(
    labels: List<String>,
    y: IntArray,
    outOfFold: List<FloatArray?>,
    texts: List<String>?,
    minScore: Double = 0.6,
    limit: Int = 30,
): List<Suspect> {
    val average = DoubleArray(labels.size)
    val count = IntArray(labels.size)
    outOfFold.forEachIndexed { r, p -> if (p != null) { average[y[r]] += p[y[r]].toDouble(); count[y[r]]++ } }
    for (c in labels.indices) if (count[c] > 0) average[c] /= count[c]
    return outOfFold.indices.mapNotNull { r ->
        val p = outOfFold[r] ?: return@mapNotNull null
        val top = DenseNet.argmax(p)
        if (top == y[r] || p[top] < minScore || p[y[r]] >= average[y[r]]) null
        else Suspect(texts?.getOrNull(r), labels[y[r]], labels[top], p[top].toDouble(), p[y[r]].toDouble())
    }.sortedByDescending { it.score - it.labelScore }.take(limit)
}
