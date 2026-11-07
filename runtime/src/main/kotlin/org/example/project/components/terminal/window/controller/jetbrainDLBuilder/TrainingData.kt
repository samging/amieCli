package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import kotlin.random.Random

data class LabeledText(val text: String, val label: String)

/**
 * Reads what a modelBuilder lambda returns into labeled samples:
 *  - sourceFile(...) result or its rows: each row is [text, ..., label]
 *  - List<Pair<text, label>>
 *  - Map<label, List<text>>
 */
fun parseLabeledData(value: Any?): List<LabeledText> = when (value) {
    is org.example.project.components.terminal.window.controller.sourceFile.DataSource -> value.samples()
    is Pair<*, *> -> if (value.first is Number) parseLabeledData(value.second) else listOfNotNull(toSample(value))
    is Map<*, *> ->
        if (value.keys.all { it is Number }) value.values.mapNotNull(::toSample)
        else value.flatMap { (label, texts) ->
            val items = if (texts is Iterable<*>) texts.toList() else listOf(texts)
            items.mapNotNull { toSample(Pair(it, label)) }
        }
    is Iterable<*> -> value.mapNotNull(::toSample)
    else -> emptyList()
}

private fun toSample(row: Any?): LabeledText? {
    val sample = when (row) {
        is Pair<*, *> -> LabeledText(row.first.toString().trim(), row.second.toString().trim())
        is List<*> -> if (row.size >= 2) LabeledText(row.first().toString().trim(), row.last().toString().trim()) else null
        else -> null
    }
    return sample?.takeIf { it.text.isNotBlank() && it.label.isNotBlank() }
}

const val MIN_LABELS = 2
const val MIN_SAMPLES_PER_LABEL = 5
const val MAX_LABEL_IMBALANCE = 10.0
const val MIN_UNIQUE_TEXT_RATIO = 0.8
const val MIN_EMBEDDING_VARIANCE = 1e-6

data class DataCheck(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

// Refuses data a classifier can't learn or be validated on; softer problems become warnings stored with the metrics
fun checkVariability(samples: List<LabeledText>): DataCheck {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val counts = samples.groupingBy { it.label }.eachCount()

    if (samples.isEmpty()) errors += "no labeled samples; return rows of [text, label], List<Pair<text, label>> or Map<label, texts>"
    if (counts.size < MIN_LABELS) errors += "need at least $MIN_LABELS labels, got ${counts.keys}"
    counts.filterValues { it < MIN_SAMPLES_PER_LABEL }.forEach { (label, n) ->
        errors += "label '$label' has $n samples, need at least $MIN_SAMPLES_PER_LABEL"
    }

    if (counts.size >= 2) {
        val ratio = counts.values.max().toDouble() / counts.values.min()
        if (ratio > MAX_LABEL_IMBALANCE) warnings += "labels are imbalanced ${"%.1f".format(ratio)}x: $counts"
    }

    val normalized = samples.map { it.text.lowercase().replace(Regex("""\s+"""), " ") }
    val uniqueRatio = if (samples.isEmpty()) 1.0 else normalized.toSet().size.toDouble() / samples.size
    if (uniqueRatio < MIN_UNIQUE_TEXT_RATIO) warnings += "only ${"%.0f".format(uniqueRatio * 100)}% of texts are unique; add more varied examples"

    val conflicting = samples.zip(normalized).groupBy({ it.second }, { it.first.label }).filterValues { it.toSet().size > 1 }
    if (conflicting.isNotEmpty()) warnings += "${conflicting.size} texts appear under more than one label, e.g. '${conflicting.keys.first()}'"

    return DataCheck(errors, warnings)
}

// Near-identical sentence vectors mean the encoder sees no difference between the samples
fun checkEmbeddingSpread(pooled: List<FloatArray>): String? {
    if (pooled.size < 2) return null
    val dim = pooled[0].size
    val meanVariance = (0 until dim).sumOf { d ->
        val mean = pooled.sumOf { it[d].toDouble() } / pooled.size
        pooled.sumOf { (it[d] - mean) * (it[d] - mean) } / pooled.size
    } / dim
    return if (meanVariance < MIN_EMBEDDING_VARIANCE) "embeddings are nearly identical (variance $meanVariance); the texts are too similar to learn from" else null
}

// Stratified split so every label is present in validation; fixed seed keeps runs comparable
fun stratifiedSplit(labelIndex: IntArray, validationFraction: Double = 0.2, seed: Int = 42): Pair<List<Int>, List<Int>> {
    require(validationFraction > 0.0 && validationFraction < 1.0) { "validationFraction must be between 0 and 1" }
    val random = Random(seed)
    val train = mutableListOf<Int>()
    val validation = mutableListOf<Int>()
    labelIndex.indices.groupBy { labelIndex[it] }.values.forEach { indices ->
        val shuffled = indices.shuffled(random)
        val take = maxOf(1, Math.round(shuffled.size * validationFraction).toInt()).coerceAtMost(shuffled.size - 1)
        validation += shuffled.take(take)
        train += shuffled.drop(take)
    }
    return train.shuffled(random) to validation
}
