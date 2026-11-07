package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import org.example.project.components.terminal.window.controller.modules.Leakage
import org.example.project.components.terminal.window.controller.modules.ModuleManifest
import org.example.project.components.terminal.window.controller.modules.RepeatedRow
import org.example.project.components.terminal.window.controller.sourceFile.DataSource
import kotlin.math.sqrt

/**
 * Scored rows that repeat a row the model learned from: their scores say how well it remembers more than how it does
 * on new texts. A row repeats one exactly when the two are the same text in lowercase letters and digits (case,
 * spaces and punctuation aside), and nearly when, given the vectors, the encoder puts it within [NEAR] cosine of a
 * training row. Read only: what it finds is told, and nothing that is trained changes.
 */
object LeakageCheck {
    /** The cosine over which two texts count as one to the encoder */
    const val NEAR = 0.98
    /** The pairs a run keeps, for a model that keeps texts */
    const val PAIRS = 10
    /** Scored rows looked for near repeats together: each training vector is read from memory once for all of them */
    private const val TILE = 16

    /** A text as the check compares it: its letters and digits, in lowercase */
    fun key(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

    /** Whether two texts are the same to the check: the same letters and digits, and some (a text of none is like no other) */
    fun same(a: String, b: String): Boolean = key(a).let { it.isNotEmpty() && it == key(b) }

    /**
     * Each of [texts] (the [what] scored, such as "validation rows") that repeats one of [training] ([against] in the
     * warning, such as "a training row"): exactly, or nearly with the [vectors] of both given; rows the model
     * [alsoLearned] from that have no vectors are looked for exactly, for every scored row but those [fromLearned]:
     * rows taken from those very rows (a fine-tune's held-out replayed rows, there to see what it forgot), looked for
     * among [training] only. The pairs are kept only with [keepTexts]
     */
    fun find(
        training: List<String>, texts: List<String>, what: String, against: String, keepTexts: Boolean,
        trainingVectors: List<FloatArray>? = null, vectors: List<FloatArray>? = null,
        alsoLearned: List<String> = emptyList(), fromLearned: Set<Int> = emptySet(),
    ): Leakage {
        val learned = training + alsoLearned
        val byKey = HashMap<String, Int>()
        learned.forEachIndexed { i, t -> key(t).takeIf { it.isNotEmpty() }?.let { byKey.putIfAbsent(it, i) } }
        val compared = trainingVectors != null && vectors != null && trainingVectors.isNotEmpty() && trainingVectors.size == training.size && vectors.size == texts.size
        // The training vectors made unit length, one after the other in one array
        val dim = if (compared) trainingVectors!![0].size else 0
        val units = FloatArray(if (compared) training.size * dim else 0).also { flat -> if (compared) trainingVectors!!.forEachIndexed { t, v -> unit(v).copyInto(flat, t * dim) } }
        val rows = Array(texts.size) { r -> if (compared) unit(vectors!![r]).takeIf { it.size == dim } else null }
        // Per scored row: the row it repeats, and how alike the two are. The same text first, then, for the rest, the
        // first training row the encoder puts within NEAR, a tile of rows at a time, the tiles in parallel
        val found = arrayOfNulls<Pair<Int, Double?>>(texts.size)
        texts.forEachIndexed { r, text ->
            val same = key(text).takeIf { it.isNotEmpty() }?.let { byKey[it] }?.takeIf { it < training.size || r !in fromLearned } ?: return@forEachIndexed
            found[r] = same to rows[r]?.takeIf { same < training.size }?.let { dot(it, units, same * dim) }
        }
        texts.indices.filter { found[it] == null && rows[it] != null }.chunked(TILE).parallelStream().forEach { tile ->
            val left = tile.toMutableList()
            for (t in training.indices) {
                if (left.isEmpty()) break
                left.removeAll { r -> dot(rows[r]!!, units, t * dim).takeIf { it > NEAR }?.also { found[r] = t to it } != null }
            }
        }
        var exact = 0
        var near = 0
        val pairs = ArrayList<RepeatedRow>()
        found.forEachIndexed { r, hit ->
            if (hit == null) return@forEachIndexed
            if (same(texts[r], learned[hit.first])) exact++ else near++
            if (keepTexts && pairs.size < PAIRS) pairs += RepeatedRow(texts[r], learned[hit.first], hit.second)
        }
        val leakage = Leakage(texts.size, exact, near, pairs)
        return leakage.copy(warning = warning(leakage, what, against))
    }

    /**
     * The files the model of [manifest] learned from (its data and what fine-tunes taught it) still in AMP/ and
     * readable, and their texts; null when there is none
     */
    fun learnedFrom(manifest: ModuleManifest): Pair<List<String>, List<String>>? {
        val read = (listOfNotNull(manifest.data) + manifest.fineTunedOn).distinct()
            .mapNotNull { name -> DataSource.locate(name)?.let { DataSource.quietSamples(it) }?.let { name to it.map { s -> s.text } } }
        return if (read.isEmpty()) null else read.map { it.first } to read.flatMap { it.second }
    }

    /**
     * The [texts] (the [what] scored, such as "rows of test.csv") that repeat a row the model of [manifest] learned
     * from, in [learnedFrom]'s files. Exactly only, as a validation's rows go through a session and give no vectors;
     * null when none of those files can be read
     */
    fun learned(manifest: ModuleManifest, texts: List<String>, what: String): Leakage? {
        val (files, learned) = learnedFrom(manifest) ?: return null
        val names = if (files.size == 1) files[0] else files.dropLast(1).joinToString() + " or " + files.last()
        return find(learned, texts, what, against = "a row it learned from in $names", keepTexts = manifest.keepTexts)
    }

    /** What the run says of them, null when there are none: "3 of 45 validation rows repeat a training row (…)" */
    fun warning(leakage: Leakage, what: String, against: String): String? {
        val n = leakage.repeated
        if (n == 0) return null
        return "$n of ${leakage.checked} $what ${if (n == 1) "repeats" else "repeat"} $against (" + listOfNotNull(
            "${leakage.exact} the same text, ignoring case and punctuation".takeIf { leakage.exact > 0 },
            "${leakage.near} nearly the same to the encoder".takeIf { leakage.near > 0 },
        ).joinToString("; ") + "): scores on rows it learned from flatter it"
    }

    private fun unit(v: FloatArray): FloatArray {
        val norm = sqrt(v.sumOf { it.toDouble() * it }).takeIf { it > 0 } ?: return v
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    /** [a] times the vector of [b] that starts at [from], summed four ways at once: a long sum waits less on itself */
    private fun dot(a: FloatArray, b: FloatArray, from: Int): Double {
        var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
        var i = 0
        while (i + 3 < a.size) {
            s0 += a[i] * b[from + i]
            s1 += a[i + 1] * b[from + i + 1]
            s2 += a[i + 2] * b[from + i + 2]
            s3 += a[i + 3] * b[from + i + 3]
            i += 4
        }
        while (i < a.size) { s0 += a[i] * b[from + i]; i++ }
        return ((s0 + s1) + (s2 + s3)).toDouble()
    }
}
