package org.example.project.components.terminal.window.controller.sourceFile

import okio.Path.Companion.toPath
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import java.io.File

/**
 * Reads a source file by its extension into (row count, rows by index), the shape context/model builders consume. The
 * file is looked for as source() looks: as given, in AMP/, then in AMP/pluginDir/
 */
fun readSourceFile(path: String, column: String = ""): Pair<Int, Map<Int, List<String>>> {
    val file = DataSource.locate(path) ?: run {
        logInternal(DataSource.notFound(path).replaceFirst("source:", "sourceFile:"), LogLevel.ERROR)
        return Pair(0, emptyMap())
    }
    return try {
        when (file.extension.lowercase()) {
            "csv" -> SourceCsv(file.path)[column]
            else -> {
                logInternal("sourceFile: unsupported type .${file.extension}", LogLevel.ERROR)
                Pair(0, emptyMap())
            }
        }.also { (count, _) -> logInternal("sourceFile: read $count rows from ${file.name}${if (column.isNotEmpty()) " [$column]" else ""}") }
    } catch (e: Exception) {
        logInternal("sourceFile: failed to read $path: ${e.message}", LogLevel.ERROR)
        Pair(0, emptyMap())
    }
}

// [+Up] Apache commons csv parser
class SourceCsv(
    private val path: String
) {
    private val toPath = path.toPath()

    init {
        if (!toPath.toFile().exists()) {
            throw IllegalArgumentException("File does not exist: $path")
        }
    }

    /**
     * read["column name"]; empty column returns whole rows. Read as source() reads a csv: a quoted field keeps its
     * commas, quotes and line breaks, and a file whose every line is quoted whole is read as the rows inside
     */
    operator fun get(column: String = ""): Pair<Int, Map<Int, List<String>>> {
        val lines = unwrapQuotedLines(parseCsv(File(path).readText()).filter { row -> row.any { it.isNotBlank() } })
        if (lines.isEmpty()) return Pair(0, emptyMap())

        val headers = lines[0].map { it.trim() }

        val columnIndex = if (column.isEmpty()) -1 else headers.indexOf(column)
        if (column.isNotEmpty() && columnIndex < 0) {
            logInternal("sourceFile: column '$column' not in ${headers}", LogLevel.ERROR)
            return Pair(0, emptyMap())
        }

        val data = lines.drop(1).mapIndexed { index, line ->
            val row = line.map { it.trim() }
            index to if (columnIndex < 0) row else listOf(row.getOrElse(columnIndex) { "" })
        }.toMap()

        return Pair(data.size, data)
    }
}
