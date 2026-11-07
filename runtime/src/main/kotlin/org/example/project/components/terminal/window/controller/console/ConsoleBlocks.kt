package org.example.project.components.terminal.window.controller.console

/**
 * Whether the lines typed at the console make a whole piece of script yet, as an interpreter's `...` prompt asks: a
 * bracket left open, a raw string or a comment not closed, a line ending in an operator, a dot or a comma, or a header
 * still waiting for its body (`for (x in 0..10)`, `if (ok)`, `else`, `fun f(x: Int)`) mean more is coming. It reads the
 * text as the compiler cuts it into code, strings and comments, and decides nothing else: whether it compiles is the
 * compiler's to say once it is whole.
 */
internal object ConsoleBlocks {
    /** True when [code] can run as it is; false while it waits for more lines */
    fun complete(code: String): Boolean {
        val scan = Scan(code)
        if (scan.broken) return true
        if (scan.open || scan.depth > 0) return false
        val last = scan.lastLine.trim()
        return last.isEmpty() || !continues(last)
    }

    /** True when no bracket, raw string or comment of [code] is left open, whatever its last line ends with */
    fun bracketsClosed(code: String): Boolean = Scan(code).let { it.broken || (!it.open && it.depth <= 0) }

    // A last line that can't end a statement: an operator, a dot or a comma at its end, or a header with no body yet
    private fun continues(line: String): Boolean {
        if (line.endsWith("++") || line.endsWith("--")) return false
        if (trailing.any { line.endsWith(it) }) return true
        if (bare.matches(line)) return true
        // do { } while (x) is whole; a while (x) on its own waits for its body
        if (line.startsWith("}") && Regex("""^}\s*while\b""").containsMatchIn(line)) return false
        headed.find(line)?.let { return closesAtEnd(line, it.range.last) }
        signature.find(line)?.let { return parametersEnd(line, it.range.last)?.let(returnTypeOnly::matches) == true }
        return false
    }

    private val trailing = listOf(".", ",", "=", "+", "-", "&&", "||", "?:", "->", "..")

    // A keyword that takes a body and nothing else: else, try, finally, do, a when with no subject, with what closes the block before them
    private val bare = Regex("""^(?:}\s*)?(?:else|try|finally|do|(?:val\s+\w+\s*=\s*)?when)$""")

    // A keyword and the ( of its condition: if, for, while, catch, when, an else if after a closed block; the match ends at the (
    private val headed = Regex("""^(?:}\s*)?(?:else\s+)?(?:if|for|while|catch|(?:val\s+\w+\s*=\s*)?when)\s*\(""")

    // A function up to the ( of its parameters
    private val signature = Regex("""^(?:(?:private|internal|public|inline|suspend|operator|infix|tailrec)\s+)*fun\s+(?:<[^>]*>\s*)?[\w.<>?, ]+\(""")

    // What may follow a function's parameters for it to still want a body: nothing, or its return type
    private val returnTypeOnly = Regex("""^(?:\s*:\s*[\w.<>?, ]+)?\s*$""")

    /** Whether the ( at [open] closes at the very end of [line], so nothing came after the condition */
    private fun closesAtEnd(line: String, open: Int): Boolean = matching(line, open) == line.length - 1

    /** What follows the parameters whose ( is at [open]; null when they don't close on this line */
    private fun parametersEnd(line: String, open: Int): String? = matching(line, open)?.let { line.substring(it + 1) }

    // Strings were blanked to "" by then, so a bracket in one is not counted
    private fun matching(line: String, open: Int): Int? {
        var depth = 0
        for (i in open until line.length) {
            when (line[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return null
    }

    /**
     * One pass over the text: how deep the brackets are outside strings and comments, whether a raw string or a
     * comment is still open at the end, and the last line with what strings held blanked to "" and comments left out,
     * so a `//` comment or a `"(."` in a string doesn't count as code. A plain string can't go over a line, so one left
     * open at a line's end is [broken]: the compiler's to report, not a reason to wait.
     */
    private class Scan(code: String) {
        var depth = 0
        var open = false
        var broken = false
        var lastLine = ""

        init {
            val line = StringBuilder()
            // A string's ${ } is code until its own brace closes: the depth it closes at, and whether the string was raw
            val templates = ArrayDeque<Pair<Int, Boolean>>()
            var i = 0
            var comment = 0
            var inString = false
            var raw = false
            fun at(index: Int) = code.getOrNull(index)
            while (i < code.length) {
                val c = code[i]
                if (c == '\n') {
                    line.clear()
                    if (inString && !raw) broken = true
                    i++
                    continue
                }
                when {
                    comment > 0 -> when {
                        c == '*' && at(i + 1) == '/' -> { comment--; i += 2 }
                        c == '/' && at(i + 1) == '*' -> { comment++; i += 2 }
                        else -> i++
                    }
                    inString -> when {
                        !raw && c == '\\' -> i += 2
                        raw && code.startsWith("\"\"\"", i) -> {
                            inString = false
                            i += 3
                            while (at(i) == '"') i++
                            line.append("\"\"")
                        }
                        !raw && c == '"' -> { inString = false; i++; line.append("\"\"") }
                        c == '$' && at(i + 1) == '{' -> {
                            templates.addLast(depth to raw)
                            depth++
                            inString = false
                            i += 2
                        }
                        else -> i++
                    }
                    c == '/' && at(i + 1) == '/' -> while (i < code.length && code[i] != '\n') i++
                    c == '/' && at(i + 1) == '*' -> { comment++; i += 2 }
                    code.startsWith("\"\"\"", i) -> { inString = true; raw = true; i += 3 }
                    c == '"' -> { inString = true; raw = false; i++ }
                    c == '\'' -> {
                        // A character, so '(' is no bracket
                        val end = code.indexOf('\'', if (at(i + 1) == '\\') i + 3 else i + 2)
                        i = if (end < 0) code.length else end + 1
                        line.append("' '")
                    }
                    else -> {
                        if (c == '(' || c == '[' || c == '{') depth++
                        if (c == ')' || c == ']' || c == '}') {
                            depth--
                            val template = templates.lastOrNull()
                            if (c == '}' && template != null && template.first == depth) {
                                // Back in the string the template is part of
                                templates.removeLast()
                                inString = true
                                raw = template.second
                                i++
                                continue
                            }
                        }
                        line.append(c)
                        i++
                    }
                }
            }
            if (inString && !raw) broken = true
            if ((inString && raw) || comment > 0) open = true
            lastLine = line.toString()
        }
    }
}
