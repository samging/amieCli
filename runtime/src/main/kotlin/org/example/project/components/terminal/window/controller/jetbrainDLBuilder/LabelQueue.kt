package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modules.moduleExists
import org.example.project.components.terminal.window.controller.modules.readModule
import org.example.project.components.terminal.window.controller.modules.readStoredManifest
import org.example.project.components.terminal.window.controller.sourceFile.DataSource
import org.example.project.components.terminal.window.controller.sourceFile.parseCsv
import org.example.project.getDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** A text a model is unsure of: its best guess and that guess's score, the runner-up, and the gap between the two */
data class LabelPick(val text: String, val guess: String, val score: Double, val runnerUp: String?, val margin: Double)

/** How the texts to label are chosen among the ones a model is unsure of */
enum class PickStrategy {
    /** The closest calls */
    CLOSEST,
    /**
     * Texts unlike each other among the closest calls ([LabelQueue.DIVERSE_POOL] times as many as are asked for): from
     * the closest call on, each time the one least like every text picked so far (k-center greedy), so one kind of
     * sentence doesn't fill the queue
     */
    DIVERSE,
}

/**
 * Which texts are worth a person's labeling time: the ones a model is least sure about. Labeling those and
 * fine-tuning on them teaches the model the most per row (active learning, by margin: the closer its two best
 * labels, the more the right one tells it).
 */
object LabelQueue {
    /** The most texts read from the requests log, the newest */
    const val MAX_SERVED = 5_000
    /** Two texts whose vectors are this alike are one text to a labeler: the second is skipped */
    const val NEAR_REPEAT = 0.98
    /** A diverse pick looks this many times as many of the closest calls as it picks */
    const val DIVERSE_POOL = 5

    private val textColumns = listOf("text", "sentence", "utterance", "content", "message", "input", "query")

    /** What a queue file's name ends with, after its model's name */
    const val SUFFIX = "-to-label.csv"

    /** The file the queue of [modelName] is written to: AMP/<name>-to-label.csv */
    fun fileFor(modelName: String): File = File(getDirectory().toString(), "$modelName$SUFFIX")

    // One lock per queue file. Every change reads the file and writes it back holding it, so a label given while a
    // regrade or Texts to label is writing (or two quick clicks) can't undo the other
    private val locks = ConcurrentHashMap<String, Any>()

    /** Runs [block] holding the lock of [file]; changes to a queue made inside it are never lost to another at the same time */
    fun <T> locked(file: File, block: () -> T): T =
        // By the path in lower case: on a case-insensitive volume (macOS's default) the two spellings are one file
        synchronized(locks.computeIfAbsent(file.absoluteFile.normalize().path.lowercase()) { Any() }, block)

    /**
     * Runs [block] with the queue file of [modelName], holding its lock, while the model is there; null when it isn't.
     * A label, a correction, a regrade or texts to label that finish after the model was renamed or destroyed so don't
     * leave a queue of the old name behind, which no model would own and which would stop a rename back to that name.
     */
    fun <T> ofModel(modelName: String, block: (File) -> T): T? {
        val file = fileFor(modelName)
        return locked(file) { if (moduleExists(modelName)) block(file) else null }
    }

    // The file replaced in one step: written beside it, then moved over it, so whoever reads it meanwhile (the page,
    // a fine-tune) sees the old rows or the new ones, never half of them. Where the move can't be done (a file system
    // without atomic moves, or Windows while someone has the file open for reading) it is written in place, as it
    // always was. A write beside it that fails (a full disk) throws and leaves the queue as it was; nothing is left
    // beside it either way
    private fun replace(file: File, text: String) {
        val next = File(file.absoluteFile.parentFile, ".${file.name}.next")
        try {
            next.writeText(text)
        } catch (e: java.io.IOException) {
            next.delete()
            throw e
        }
        try {
            Files.move(next.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.io.IOException) {
            next.delete()
            file.writeText(text)
        }
    }

    /** The texts of a file: a csv's text column (by name, else its only or longest column), or a txt's lines */
    fun textsOf(file: File): List<String> {
        if (file.extension.lowercase() != "csv") return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val table = parseCsv(file.readText()).filter { row -> row.any { it.isNotBlank() } }
        if (table.isEmpty()) return emptyList()
        val headers = table.first().map { it.trim().lowercase() }
        val rows = table.drop(1)
        val column = headers.indexOfFirst { it in textColumns }.takeIf { it >= 0 }
            ?: if (headers.size == 1) 0 else headers.indices.maxByOrNull { c -> rows.sumOf { it.getOrNull(c)?.length ?: 0 } } ?: 0
        return rows.mapNotNull { it.getOrNull(column)?.trim()?.takeIf(String::isNotEmpty) }
    }

    /** What the model was asked over HTTP (its incoming.jsonl), the newest [MAX_SERVED] */
    fun served(dir: File): List<String> {
        val log = File(dir, "incoming.jsonl")
        if (!log.isFile) return emptyList()
        return log.readLines().takeLast(MAX_SERVED).mapNotNull { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    /**
     * The [limit] texts of [texts] whose two best scores are closest, closest first: [vectors] are their sentence
     * vectors and [net] the model's head. Repeats and near-repeats of a text already picked are skipped. [strategy]
     * [PickStrategy.DIVERSE] picks texts unlike each other among the closest calls instead, still closest first.
     */
    fun pick(texts: List<String>, vectors: List<FloatArray>, net: DenseNet, labels: List<String>, limit: Int, strategy: PickStrategy = PickStrategy.CLOSEST): List<LabelPick> {
        if (limit < 1) return emptyList()
        val scored = texts.indices.map { i ->
            val p = net.predictProbabilities(vectors[i])
            val order = p.indices.sortedByDescending { p[it] }
            val top = order.first()
            val second = order.getOrNull(1)
            i to LabelPick(texts[i], labels.getOrElse(top) { "$top" }, p[top].toDouble(), second?.let { labels.getOrElse(it) { "$it" } }, (p[top] - (second?.let { p[it] } ?: 0f)).toDouble())
        }.sortedBy { it.second.margin }
        val norms = vectors.map { v -> sqrt(v.sumOf { (it * it).toDouble() }).takeIf { it > 0 } ?: 1.0 }
        fun cosine(i: Int, j: Int): Double {
            var dot = 0.0
            val a = vectors[i]; val b = vectors[j]
            for (d in a.indices) dot += a[d] * b[d]
            return dot / (norms[i] * norms[j])
        }
        // The pool counted in longs: a limit past a fifth of the largest int would wrap round to a negative count
        if (strategy == PickStrategy.DIVERSE) return diverse(scored.take(minOf(limit.toLong() * DIVERSE_POOL, scored.size.toLong()).toInt()), limit, ::cosine)
        val picked = ArrayList<Pair<Int, LabelPick>>()
        for (candidate in scored) {
            if (picked.size >= limit) break
            val (i, _) = candidate
            val repeat = picked.any { (j, _) -> cosine(i, j) > NEAR_REPEAT }
            if (!repeat) picked += candidate
        }
        return picked.map { it.second }
    }

    /**
     * Up to [limit] of [pool] (closest call first) unlike each other: the closest call, then each time the one whose
     * likeness to the texts picked so far is lowest (ties to the closer call), never a near-repeat of one; closest
     * first again
     */
    private fun diverse(pool: List<Pair<Int, LabelPick>>, limit: Int, cosine: (Int, Int) -> Double): List<LabelPick> {
        if (pool.isEmpty()) return emptyList()
        val picked = mutableListOf(0)
        // How like the texts picked so far each one of the pool is: its highest likeness to any of them, and a text
        // picked is out (a vector of zeros is like nothing, not even itself)
        val likeness = DoubleArray(pool.size) { cosine(pool[it].first, pool[0].first) }.also { it[0] = Double.POSITIVE_INFINITY }
        while (picked.size < limit) {
            var next = -1
            for (c in pool.indices) if (likeness[c] <= NEAR_REPEAT && (next < 0 || likeness[c] < likeness[next])) next = c
            if (next < 0) break
            picked += next
            for (c in pool.indices) likeness[c] = maxOf(likeness[c], cosine(pool[c].first, pool[next].first))
            likeness[next] = Double.POSITIVE_INFINITY
        }
        return picked.sorted().map { pool[it].second }
    }

    /**
     * Writes the picks as a csv to fill in: text, an empty label, the model's guess, its score and the runner-up.
     * [kept] are rows a person labeled already (as [labeledIn] reads them); they come first, as they were.
     */
    fun write(file: File, picks: List<LabelPick>, kept: List<List<String>> = emptyList()): File {
        fun cell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        locked(file) {
            replace(file, buildString {
                appendLine("text,label,guess,score,runner-up")
                kept.forEach { row -> appendLine(listOf(cell(row[0]), cell(row[1]), cell(row.getOrElse(2) { "" }), row.getOrElse(3) { "" }, cell(row.getOrElse(4) { "" })).joinToString(",")) }
                picks.forEach { p -> appendLine(listOf(cell(p.text), "", cell(p.guess), "%.3f".format(java.util.Locale.US, p.score), cell(p.runnerUp ?: "")).joinToString(",")) }
            })
        }
        return file
    }

    /** One row of a queue file: the text, the label a person gave it (empty while unlabeled), the model's guess, its score and the runner-up */
    data class Row(val text: String, val label: String, val guess: String = "", val score: String = "", val runnerUp: String = "") {
        val labeled: Boolean get() = label.isNotBlank()
    }

    /** Every row of a queue file, labeled or not, in the file's order; empty when the file isn't there or has no text column */
    fun rows(file: File): List<Row> {
        if (!file.isFile) return emptyList()
        val table = runCatching { parseCsv(file.readText()) }.getOrNull()?.filter { row -> row.any { it.isNotBlank() } } ?: return emptyList()
        if (table.size < 2) return emptyList()
        val headers = table.first().map { it.trim().lowercase() }
        val at = listOf("text", "label", "guess", "score", "runner-up").map { headers.indexOf(it) }
        if (at[0] < 0) return emptyList()
        fun cell(row: List<String>, i: Int) = if (i < 0) "" else row.getOrNull(i)?.trim().orEmpty()
        return table.drop(1).mapNotNull { row ->
            val text = cell(row, at[0])
            if (text.isEmpty()) null else Row(text, cell(row, at[1]), cell(row, at[2]), cell(row, at[3]), cell(row, at[4]))
        }
    }

    /** Writes [rows] as a queue file, the columns [write] uses */
    fun writeRows(file: File, rows: List<Row>): File {
        fun cell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        locked(file) {
            replace(file, buildString {
                appendLine("text,label,guess,score,runner-up")
                rows.forEach { r -> appendLine(listOf(cell(r.text), cell(r.label), cell(r.guess), r.score, cell(r.runnerUp)).joinToString(",")) }
            })
        }
        return file
    }

    /**
     * Gives the row of [text] the label [label] (null takes it away), or adds the text at the top as a labeled row
     * when the queue doesn't have it. A text is the same text whatever its case.
     */
    fun setLabel(file: File, text: String, label: String?, guess: String = "", score: Double? = null, runnerUp: String = ""): File = locked(file) {
        val rows = rows(file)
        val at = rows.indexOfFirst { it.text.equals(text.trim(), ignoreCase = true) }
        val updated = when {
            at >= 0 -> rows.toMutableList().also { it[at] = it[at].copy(label = label?.trim().orEmpty()) }
            label.isNullOrBlank() -> return@locked file
            else -> listOf(Row(text.trim(), label.trim(), guess, score?.let { "%.3f".format(java.util.Locale.US, it) }.orEmpty(), runnerUp)) + rows
        }
        writeRows(file, updated)
    }

    /** Adds [rows] after the rows of the queue, but for texts it has already (whatever their case); how many were added */
    fun addRows(file: File, rows: List<Row>): Int = locked(file) {
        val have = rows(file)
        val known = have.mapTo(HashSet()) { it.text.lowercase() }
        val added = rows.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() && known.add(it.text.lowercase()) }
        if (added.isNotEmpty()) writeRows(file, have + added)
        added.size
    }

    /** Takes the row of [text] out of the queue */
    fun remove(file: File, text: String): File = locked(file) {
        val rows = rows(file)
        val kept = rows.filterNot { it.text.equals(text.trim(), ignoreCase = true) }
        if (kept.size == rows.size) file else writeRows(file, kept)
    }

    /**
     * The rows of a queue file that have a label filled in: text, label, then guess, score and runner-up as the file
     * has them (empty when a column is gone). Empty when the file isn't there or has no text and label columns.
     */
    fun labeledIn(file: File): List<List<String>> {
        if (!file.isFile) return emptyList()
        val table = runCatching { parseCsv(file.readText()) }.getOrNull()?.filter { row -> row.any { it.isNotBlank() } } ?: return emptyList()
        if (table.size < 2) return emptyList()
        val headers = table.first().map { it.trim().lowercase() }
        val text = headers.indexOf("text")
        val label = headers.indexOf("label")
        if (text < 0 || label < 0) return emptyList()
        val rest = listOf("guess", "score", "runner-up").map { headers.indexOf(it) }
        return table.drop(1).mapNotNull { row ->
            val t = row.getOrNull(text)?.trim().orEmpty()
            val l = row.getOrNull(label)?.trim().orEmpty()
            if (t.isEmpty() || l.isEmpty()) null else listOf(t, l) + rest.map { i -> if (i < 0) "" else row.getOrNull(i)?.trim().orEmpty() }
        }
    }
}

/**
 * A text [modelName] answered wrongly, with the label it should have given: written to the model's queue
 * (AMP/<name>-to-label.csv) as a labeled row, at the top, so the next fine-tune on the queue learns it. The text a
 * queue has already gets the label instead. Refused for a model that doesn't keep texts. Returns the queue file,
 * or null with the reason in the console and given to [onRefused].
 */
fun teach(
    modelName: String,
    text: String,
    label: String,
    guess: String = "",
    score: Double? = null,
    runnerUp: String = "",
    onRefused: (String) -> Unit = {},
): File? {
    fun no(reason: String): File? {
        logInternal("teach: $reason", LogLevel.ERROR)
        onRefused(reason)
        return null
    }
    if (text.isBlank() || label.isBlank()) return no("a text and a label are needed")
    if (!moduleExists(modelName)) return no("there is no model '$modelName'")
    val (_, manifest) = runCatching { readModule(modelName) }.getOrNull() ?: return no("'$modelName' can't be read")
    if (!manifest.keepTexts) return no("'$modelName' answers other people and doesn't keep their texts, so none is written to a file")
    // The model's spelling of a label it knows: "Lights" is its "lights"
    val spelled = manifest.labels.firstOrNull { it.equals(label.trim(), ignoreCase = true) } ?: label.trim()
    val file = LabelQueue.ofModel(modelName) { LabelQueue.setLabel(it, text, spelled, guess, score, runnerUp) }
        ?: return no("'$modelName' was renamed or destroyed meanwhile; the correction was not written")
    logInternal("teach: '${text.trim().take(60)}' is $spelled for '$modelName', in ${file.name}")
    return file
}

/**
 * Texts [modelName] was unsure about, each with its guess, written to its queue (AMP/<name>-to-label.csv) as rows to
 * label, after the rows there; a text the queue has already stays as it is. The Try line's batch puts its unsure
 * answers here. Refused for a model that doesn't keep texts. Returns how many rows were added, or null with the
 * reason in the console and given to [onRefused].
 */
fun queueGuesses(modelName: String, guesses: List<LabelQueue.Row>, onRefused: (String) -> Unit = {}): Int? {
    fun no(reason: String): Int? {
        logInternal("queue: $reason", LogLevel.ERROR)
        onRefused(reason)
        return null
    }
    if (!moduleExists(modelName)) return no("there is no model '$modelName'")
    val (_, manifest) = runCatching { readModule(modelName) }.getOrNull() ?: return no("'$modelName' can't be read")
    if (!manifest.keepTexts) return no("'$modelName' answers other people and doesn't keep their texts, so none is written to a file")
    val added = LabelQueue.ofModel(modelName) { LabelQueue.addRows(it, guesses.map { g -> g.copy(label = "") }) }
        ?: return no("'$modelName' was renamed or destroyed meanwhile; nothing was put in its queue")
    logInternal("queue: $added of ${guesses.size} texts '$modelName' was unsure about put in ${LabelQueue.fileFor(modelName).name}")
    return added
}

/**
 * The texts [modelName] is least sure about, for a person to label: from [from] (a csv with a text column, or a
 * txt with one text per line), or else from what the model was asked over HTTP. Texts it was trained on are left
 * out, as are repeats; the closest calls come first ([PickStrategy.DIVERSE]: texts unlike each other among the
 * closest calls, still closest first). Written to AMP/<name>-to-label.csv with an empty label
 * column: fill it in and fine-tune on the file. Rows of that file a person has labeled already stay, at the top,
 * and their texts aren't picked again, so asking again never loses labeling work. Refused for a model that
 * doesn't keep texts, so other people's texts never land in a file. Returns the file, or null with the reason in
 * the console.
 */
fun textsToLabel(modelName: String, from: File? = null, limit: Int = 50, embedder: Embedder? = null, strategy: PickStrategy = PickStrategy.CLOSEST): File? {
    fun no(reason: String): File? {
        logInternal("textsToLabel: $reason", LogLevel.ERROR)
        return null
    }
    require(limit >= 1) { "limit must be at least 1" }
    if (!moduleExists(modelName)) return no("there is no model '$modelName'")
    val (dir, manifest) = readModule(modelName) ?: return no("'$modelName' can't be read")
    if (!manifest.keepTexts) return no("'$modelName' answers other people and doesn't keep their texts, so none are written to a file")
    val head = runCatching { OnnxHead.read(File(dir, manifest.model)) }.getOrElse { return no("'$modelName': ${it.message}") }
    val raw = if (from != null) LabelQueue.textsOf(from) else LabelQueue.served(dir)
    if (raw.isEmpty()) return no(if (from != null) "${from.name} has no texts" else "'$modelName' hasn't been asked anything over HTTP yet; give a file of texts")
    val stored = readStoredManifest(dir)
    // Read quietly: no source: lines for files only looked in, and one that can't be read is passed over
    val trainedOn = (listOfNotNull(stored.data) + stored.fineTunedOn).mapNotNull { DataSource.locate(it) }
        .flatMap { f -> DataSource.quietSamples(f)?.map { it.text.trim().lowercase() } ?: emptyList() }.toSet()
    val target = LabelQueue.fileFor(modelName)
    val kept = LabelQueue.labeledIn(target)
    val keptTexts = kept.map { it[0].lowercase() }.toSet()
    val seen = HashSet<String>()
    val texts = raw.filter { t -> t.lowercase().let { it !in trainedOn && it !in keptTexts && seen.add(it) } }
    if (texts.isEmpty()) return no("every text of ${from?.name ?: "the requests"} is one '$modelName' was trained on" + if (kept.isNotEmpty()) " or one labeled in ${target.name} already" else "")
    val embed = embedder ?: EncoderEmbedder(manifest.encoder, true)
    val vectors = embed.embed(texts) { _, _ -> }
    if (vectors.first().size != head.inputDim) return no("the encoder gives ${vectors.first().size} numbers per text, the head reads ${head.inputDim}")
    val picks = LabelQueue.pick(texts, vectors, head.toDenseNet(), manifest.labels, limit, strategy)
    // The labeled rows as they are now: a label given while the texts were embedded stays, and its text isn't picked twice
    val (file, written, labeled) = LabelQueue.ofModel(modelName) { queue ->
        val now = LabelQueue.labeledIn(queue)
        val labeledNow = now.map { it[0].lowercase() }.toSet()
        val fresh = picks.filter { it.text.lowercase() !in labeledNow }
        Triple(LabelQueue.write(queue, fresh, now), fresh.size, now.size)
    } ?: return no("'$modelName' was renamed or destroyed while its texts were picked; nothing was written")
    logInternal("textsToLabel: '$modelName' is least sure about $written of ${texts.size} texts${if (raw.size > texts.size) " (${raw.size - texts.size} repeats, training texts or labeled ones left out)" else ""}; " +
        "they are in ${file.path}, " + (if (strategy == PickStrategy.DIVERSE) "texts unlike each other among its closest calls, " else "") + "closest calls first" + (if (labeled > 0) ", under the $labeled rows labeled in it already" else "") + ": fill in the label column and fine-tune on it")
    return file
}

/**
 * What [modelName] answers now to every text of its queue: each row's guess, score and runner-up are rewritten
 * with the head it has, the labels stay. The Models page runs it after a head changes (a fine-tune, a rollback, a
 * retrain, a calibration), so the queue shows which labeled texts it answers right since. Texts embedded before come
 * from the vector cache. A label given while it runs is kept. Nothing for a model without a queue or one that keeps
 * no texts. Returns the queue file, or null (with the reason in the console when there was a queue).
 */
fun regradeQueue(modelName: String, embedder: Embedder? = null): File? {
    fun no(reason: String): File? {
        logInternal("regradeQueue: $reason", LogLevel.ERROR)
        return null
    }
    val file = LabelQueue.fileFor(modelName)
    val rows = LabelQueue.rows(file)
    if (rows.isEmpty()) return null
    if (!moduleExists(modelName)) return no("there is no model '$modelName'")
    val (dir, manifest) = readModule(modelName) ?: return no("'$modelName' can't be read")
    if (!manifest.keepTexts) return no("'$modelName' doesn't keep texts, so its texts aren't graded")
    val head = runCatching { OnnxHead.read(File(dir, manifest.model)) }.getOrElse { return no("'$modelName': ${it.message}") }
    val vectors = (embedder ?: EncoderEmbedder(manifest.encoder, true)).embed(rows.map { it.text }) { _, _ -> }
    if (vectors.first().size != head.inputDim) return no("the encoder gives ${vectors.first().size} numbers per text, the head reads ${head.inputDim}")
    val net = head.toDenseNet()
    fun label(i: Int) = manifest.labels.getOrElse(i) { "$i" }
    val graded = rows.indices.associate { i ->
        val p = net.predictProbabilities(vectors[i])
        val order = p.indices.sortedByDescending { p[it] }
        rows[i].text.lowercase() to Triple(label(order[0]), "%.3f".format(java.util.Locale.US, p[order[0]]), order.getOrNull(1)?.let(::label).orEmpty())
    }
    // The file as it is now, so labels given meanwhile stay; a text added meanwhile keeps the guess it came with. A
    // model renamed or destroyed meanwhile took its queue along or away, and one deleted by hand stays deleted
    val now = LabelQueue.ofModel(modelName) {
        if (!file.isFile) return@ofModel null
        LabelQueue.rows(file).map { r -> graded[r.text.lowercase()]?.let { (guess, score, runnerUp) -> r.copy(guess = guess, score = score, runnerUp = runnerUp) } ?: r }
            .also { LabelQueue.writeRows(file, it) }
    } ?: return no("'$modelName' or its queue is gone since the regrade began; nothing was written")
    val labeled = now.filter { it.labeled }
    logInternal("regradeQueue: '$modelName' v${manifest.version ?: 1} answers ${labeled.count { it.guess.equals(it.label, ignoreCase = true) }} of the ${labeled.size} labeled texts of ${file.name} right")
    return file
}
