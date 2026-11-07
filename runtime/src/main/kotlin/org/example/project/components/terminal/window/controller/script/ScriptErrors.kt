package org.example.project.components.terminal.window.controller.script

import org.example.project.components.terminal.window.controller.LogLevel
import java.lang.reflect.InvocationTargetException
import javax.script.ScriptException

/*
 * Where a failed run of main.kts went wrong, in the script's own lines. The engine runs assembly.kts: the script
 * behind a header of imports and the SandboxApi prelude (assembleScript), so the places it names, a compile error's
 * "(…_Line_0.kts:62:14)" or a stack frame's line, are as many lines further down as the header is long. The script's
 * own imports are the header's first lines, trimmed (movedImports says where each came from).
 */

/** The name a place in the script goes by in the console: "main.kts:2:14", or "main.kts:2" without a column */
internal fun scriptPlace(line: Int, column: Int? = null, file: String = "main.kts"): String = "$file:$line" + (column?.let { ":$it" } ?: "")

/** One thing a run of main.kts got wrong: where in the script, when it was in the script's own lines, and what */
internal data class ScriptProblem(val line: Int?, val column: Int?, val message: String) {
    /** The console line: the place first, as a compiler writes it, then the message */
    val text: String get() = textIn("main.kts")

    /** The same for a script kept in [file], as a template run in the background is */
    fun textIn(file: String): String = line?.let { "${scriptPlace(it, column, file)}: $message" } ?: message

    /** Whether it failed the run: a warning (or a note) only came along with what did */
    val failing: Boolean get() = notes.none { message.startsWith(it) }

    /** Its level in the console: a warning is no error, so it is told as an info line */
    val level: LogLevel get() = if (failing) LogLevel.ERROR else LogLevel.INFO

    /** Its line in the console: an error with no place says it is one */
    val consoleLine: String get() = if (line != null || !failing) text else "Error: $message"

    private companion object {
        val notes = listOf("warning: ", "info: ", "debug: ")
    }
}

internal object ScriptErrors {
    /** How many lines assembleScript put before [code]'s own in [assembled] */
    fun headerLines(code: String, assembled: String): Int = assembled.lines().size - code.lines().size

    // One report per line, its severity first: "ERROR Type mismatch: … (ScriptingHost3a80515c_Line_0.kts:62:14)", the
    // place left out when it has none and an exception after it when it came with one; compile() puts "Error: " first
    private val report = Regex("""^(DEBUG|INFO|WARNING|ERROR|FATAL) (.*?)(?: \((?:\S*\.kts)?(?::(\d+):(\d+))?\)(: .*)?)?$""")

    /**
     * What [e], thrown evaluating a script assembled with [header] lines before the script's [lines], says went
     * wrong: each of the compiler's reports with its place (a script that didn't compile), or else the exception of
     * the run with the innermost line of the script it went through (a frame in the prelude, such as a source() that
     * threw, gives way to the line that called it). [imports] are where the header's first lines came from in the
     * script, as movedImports gives them, so an import that doesn't resolve is told at its own line. A place outside
     * the script's own lines is left out, and with no lines (the run failed before its script was assembled) there
     * are no places.
     */
    fun problems(e: Throwable, header: Int, lines: Int, imports: List<MovedImport> = emptyList()): List<ScriptProblem> {
        fun inScript(assembledLine: Int) = (assembledLine - header).takeIf { it in 1..lines }
        // A script that didn't compile: the engine's ScriptException carries the compiler's reports and no cause
        if (e is ScriptException && e.cause == null) {
            val reported = mutableListOf<ScriptProblem>()
            e.message.orEmpty().lines().forEachIndexed { i, raw ->
                // The engine says "incomplete code; " before a report of a line that stops short, as a typed line can
                val line = if (i == 0) raw.removePrefix("Error: ").removePrefix("incomplete code; ") else raw
                val m = report.find(line.trim())
                if (m != null) {
                    val (severity, text, row, column, exception) = m.destructured
                    val moved = row.toIntOrNull()?.takeIf { lines > 0 }?.let { imports.getOrNull(it - 1) }
                    val at = moved?.line ?: row.toIntOrNull()?.let { inScript(it) }
                    val col = column.toIntOrNull()?.takeIf { at != null }?.let { it + (moved?.indent ?: 0) }
                    val said = text + exception
                    reported += ScriptProblem(at, col, if (severity == "ERROR" || severity == "FATAL") said else "${severity.lowercase()}: $said")
                } else if (line.isNotBlank()) {
                    // A report that runs over more than one line; nothing before the first is lost either
                    if (reported.isEmpty()) reported += ScriptProblem(null, null, line)
                    else reported[reported.lastIndex] = reported.last().let { it.copy(message = it.message + "\n" + raw) }
                }
            }
            if (reported.isNotEmpty()) return reported
        }
        // A run that threw: the engine wraps what the script threw, whose frames name lines of assembly.kts (a few
        // causes down at most, as causes can point back at each other)
        val causes = generateSequence(e) { it.cause }.take(16).toList()
        val at = causes.firstNotNullOfOrNull { t ->
            t.stackTrace.firstNotNullOfOrNull { frame -> if (frame.fileName?.endsWith(".kts") == true) inScript(frame.lineNumber) else null }
        }
        // What it threw is under the wrapping: an exception in a ScriptException, an error (a StackOverflowError,
        // TODO()'s NotImplementedError) in an InvocationTargetException too, whose message is only its class
        val thrown = causes.firstOrNull { it !is ScriptException && it !is InvocationTargetException }
        val message = if (thrown == null || thrown === e) e.message ?: e.javaClass.simpleName else thrown.toString()
        return listOf(ScriptProblem(at, null, message))
    }

    /**
     * A failed run in one line, as its status and RECENT RUNS give it: the first error of [problems] (a warning only
     * when there is nothing else) and how many more errors there are; [file] names the script it was run from
     */
    fun summary(problems: List<ScriptProblem>, file: String = "main.kts"): String {
        val failing = problems.filter { it.failing }
        val first = failing.firstOrNull() ?: problems.first()
        val more = (if (failing.isEmpty()) problems.size else failing.size) - 1
        return first.textIn(file) + if (more > 0) " (and $more more)" else ""
    }
}

/** An import assembleScript moved up into the header: its line in the script and how far it was indented there */
internal data class MovedImport(val line: Int, val indent: Int)
