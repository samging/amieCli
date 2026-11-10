package org.example.project.components.terminal.window.controller.sourceFile

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.LabeledText
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import java.io.File

/** Which column holds the text and which the label, inferred from the header names and the values */
data class DataStructure(val textColumn: String, val labelColumn: String)

/**
 * A labeled data file: val train = amie.source("train.csv"); amie.relate(train, "test.csv").
 * The related test file is what modelBuilder validates on instead of splitting the training data.
 * Relations are kept in AMP/relations.json, so sourcing train.csv again brings its test file back.
 */
class DataSource internal constructor(
    val file: File,
    val headers: List<String>,
    val rows: List<List<String>>,
    val structure: DataStructure,
) {
    var related: DataSource? = null
        private set

    val name: String get() = file.name
    val labels: List<String> get() = samples().map { it.label }.distinct().sorted()

    fun samples(): List<LabeledText> {
        val text = headers.indexOf(structure.textColumn)
        val label = headers.indexOf(structure.labelColumn)
        return rows.mapNotNull { row ->
            val t = row.getOrNull(text)?.trim().orEmpty()
            val l = row.getOrNull(label)?.trim().orEmpty()
            if (t.isEmpty() || l.isEmpty()) null else LabeledText(t, l)
        }
    }

    /** Relates a test file read with this source's structure; it validates the model trained on this one */
    fun relate(test: String): DataSource {
        val testSource = read(test, structure) ?: return this
        related = testSource
        saveRelation(file, testSource.file)
        val unseen = testSource.labels - labels.toSet()
        if (unseen.isNotEmpty()) logInternal("relate: ${testSource.name} has labels $unseen that ${name} doesn't train on", LogLevel.ERROR)
        val untested = labels - testSource.labels.toSet()
        if (untested.isNotEmpty()) logInternal("relate: ${testSource.name} has no rows for $untested, their recall can't be measured")
        logInternal("relate: $name -> ${testSource.name} (${testSource.rows.size} rows)")
        return this
    }

    override fun toString() = "$name [text=${structure.textColumn}, label=${structure.labelColumn}, ${rows.size} rows${related?.let { ", test=${it.name}" } ?: ""}]"

    companion object {
        /** Reads a CSV with a header row; the structure is inferred unless given (a related test file reuses its train's) */
        fun read(path: String, structure: DataStructure? = null): DataSource? {
            val file = locate(path) ?: run {
                logInternal(notFound(path), LogLevel.ERROR)
                return null
            }
            if (file.extension.lowercase() != "csv") {
                logInternal("source: unsupported type .${file.extension}", LogLevel.ERROR)
                return null
            }
            val lines = parseCsv(file.readText()).filter { row -> row.any { it.isNotBlank() } }
            if (lines.size < 2) {
                logInternal("source: ${file.name} needs a header and at least one row", LogLevel.ERROR)
                return null
            }
            val headers = lines[0].map { it.trim() }
            val rows = lines.drop(1)
            val chosen = structure?.takeIf { it.textColumn in headers && it.labelColumn in headers }
                ?: inferStructure(headers, rows) ?: run {
                    logInternal("source: couldn't find a text and a label column in ${file.name} $headers", LogLevel.ERROR)
                    return null
                }
            if (structure != null && chosen != structure) logInternal("source: ${file.name} doesn't have columns $structure, inferred $chosen")
            val source = DataSource(file, headers, rows, chosen)
            logInternal("source: $source")

            // Bring back the test file this one was related to earlier
            if (structure == null) loadRelations()[file.canonicalPath]?.let { test ->
                if (File(test).isFile) source.relate(test)
            }
            return source
        }

        /**
         * The rows of the csv [file] as [read] takes them, its columns inferred, without a line in the log or its test
         * file brought back; null when it can't be read. For a model's data read again, such as by LeakageCheck
         */
        fun quietSamples(file: File): List<LabeledText>? = runCatching {
            if (file.extension.lowercase() != "csv") return null
            val lines = parseCsv(file.readText()).filter { row -> row.any { it.isNotBlank() } }
            if (lines.size < 2) return null
            val headers = lines[0].map { it.trim() }
            val rows = lines.drop(1)
            DataSource(file, headers, rows, inferStructure(headers, rows) ?: return null).samples()
        }.getOrNull()

        /**
         * Says where [path] was looked for and, when AMP holds csv files, the ones whose names come closest: a data file
         * is found as given (from the folder amie was started in), in AMP/ or in AMP/pluginDir/
         */
        internal fun notFound(path: String): String {
            val amp = File(getDirectory().toString())
            val where = if (File(path).isAbsolute) path else "${File(path).absoluteFile.parentFile.path}, ${amp.path} or ${File(amp, "pluginDir").path}"
            val stem = File(path).nameWithoutExtension.lowercase()
            val csvs = (amp.listFiles().orEmpty().toList() + File(amp, "pluginDir").listFiles().orEmpty())
                .filter { it.isFile && it.extension.equals("csv", ignoreCase = true) }.map { it.name }.distinct().sorted()
            val close = csvs.filter { n -> val s = n.substringBeforeLast('.').lowercase(); stem.isNotEmpty() && (stem in s || s in stem) }
            val shown = close.ifEmpty { csvs }.take(6)
            return "source: $path not found in $where" + if (shown.isEmpty()) "" else
                "; AMP has ${shown.joinToString(", ")}" + (if (close.isEmpty() && csvs.size > shown.size) " and ${csvs.size - shown.size} more" else "")
        }

        /** The file a data path names: as given, in AMP/, or in AMP/pluginDir/ */
        fun locate(path: String): File? {
            val given = File(path)
            val amp = File(getDirectory().toString())
            return (if (given.isAbsolute) listOf(given) else listOf(given, File(amp, path), File(amp, "pluginDir/$path")))
                .firstOrNull { it.isFile }
        }

        /** Whether the file lives under AMP/, where a model's data is looked for by its name later on */
        fun inAmp(file: File): Boolean = file.canonicalPath.startsWith(File(getDirectory().toString()).canonicalPath + File.separator)

        /**
         * The file as AMP/<name>: a csv picked from anywhere else on the machine is copied in (a newer copy replaces
         * the one there), so the model that trains or validates on it finds it again by its name. A file already
         * under AMP/ is returned as it is. Throws when the file is gone (deleted or moved since the page listed it), so
         * the page says that rather than that it has no columns to train on.
         */
        fun importIntoAmp(file: File): File {
            require(file.isFile) { "${file.name} is not there any more; it was moved or deleted" }
            if (inAmp(file)) return file
            val amp = File(getDirectory().toString()).apply { mkdirs() }
            val target = File(amp, file.name)
            if (!target.isFile || target.lastModified() < file.lastModified() || target.length() != file.length()) {
                file.copyTo(target, overwrite = true)
                logInternal("data: ${file.name} copied into AMP/ from ${file.parent}")
            }
            return target
        }
    }
}

private val labelHints = listOf("label", "labels", "class", "intent", "target", "category", "tag", "y")
private val textHints = listOf("text", "sentence", "utterance", "content", "message", "input", "query", "x")

/**
 * The label is a hinted column, else the one with the fewest distinct values (at least 2, repeated across rows);
 * the text is a hinted column, else the remaining column with the longest values.
 */
fun inferStructure(headers: List<String>, rows: List<List<String>>): DataStructure? {
    if (headers.size < 2) return null
    fun values(i: Int) = rows.mapNotNull { it.getOrNull(i)?.trim()?.takeIf(String::isNotEmpty) }
    val stats = headers.indices.associateWith { i ->
        val v = values(i)
        Triple(v.toSet().size, if (v.isEmpty()) 0.0 else v.sumOf { it.length }.toDouble() / v.size, v.size)
    }
    fun hinted(hints: List<String>, exclude: Int?) =
        hints.firstNotNullOfOrNull { h -> headers.indices.firstOrNull { it != exclude && headers[it].lowercase() == h } }

    val label = hinted(labelHints, null)
        ?: stats.filter { (_, s) -> s.first >= 2 && s.first <= maxOf(2, s.third / 2) }.minByOrNull { (_, s) -> s.first }?.key
        ?: return null
    val text = hinted(textHints, label)
        ?: stats.filter { it.key != label }.maxByOrNull { (_, s) -> s.second }?.key
        ?: return null
    return DataStructure(headers[text], headers[label])
}

// RFC 4180: quoted fields may hold commas, quotes ("") and newlines
fun parseCsv(content: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < content.length) {
        val c = content[i]
        when {
            quoted && c == '"' && content.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
            c == '"' -> quoted = !quoted
            !quoted && c == ',' -> { row += field.toString(); field.clear() }
            !quoted && (c == '\n' || c == '\r') -> {
                if (c == '\r' && content.getOrNull(i + 1) == '\n') i++
                row += field.toString(); field.clear()
                rows += row; row = mutableListOf()
            }
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
    return rows
}

private val relationsJson = Json { prettyPrint = true }
private val relationsFile: File get() = File(getDirectory().toString(), "relations.json")

private fun loadRelations(): Map<String, String> =
    runCatching { relationsJson.decodeFromString<Map<String, String>>(relationsFile.readText()) }.getOrElse { emptyMap() }

private fun saveRelation(train: File, test: File) {
    val relations = loadRelations() + (train.canonicalPath to test.canonicalPath)
    relationsFile.parentFile.mkdirs()
    relationsFile.writeText(relationsJson.encodeToString(relations))
}
