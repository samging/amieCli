package org.amie.cli

import org.jline.keymap.BindingReader
import org.jline.keymap.KeyMap
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp

/**
 * A list picked with the arrow keys at the terminal: up and down move the cursor over the rows, space takes the row
 * under it or leaves it again (a choice of several), Enter answers, n, q or Esc leave. The lines are redrawn in place
 * on every key. The prompt's terminal is used when amie runs at it, else one is opened for the pick.
 */
object Picker {
    sealed interface Line {
        /** A line the cursor passes over, a section's name */
        data class Header(val text: String) : Line

        /** A row that can be taken, [index] counting the rows from 0 */
        data class Row(val index: Int, val text: String) : Line
    }

    /**
     * [lines] under [title], [taken] marked; with [several], space toggles a row and Enter answers the marked rows, else
     * the mark follows the cursor and Enter answers its row. [status] says what the marked rows come to, drawn beneath
     * with the keys. Null when the pick is left.
     */
    fun pick(title: String, lines: List<Line>, taken: Set<Int>, several: Boolean, status: (Set<Int>) -> String, footer: List<String> = emptyList()): Set<Int>? {
        val rows = lines.filterIsInstance<Line.Row>()
        if (rows.isEmpty()) return null
        val own = Repl.currentTerminal == null
        val terminal = Repl.currentTerminal ?: TerminalBuilder.builder().system(true).build()
        val out = terminal.writer()
        var cursor = rows.indexOfFirst { it.index in taken }.coerceAtLeast(0)
        val marked = taken.toMutableSet()
        if (!several) { marked.clear(); marked += rows[cursor].index }
        val keys = KeyMap<String>().apply {
            bind("up", *listOfNotNull(KeyMap.key(terminal, InfoCmp.Capability.key_up), "\u001b[A", "\u001bOA", "k").toTypedArray())
            bind("down", *listOfNotNull(KeyMap.key(terminal, InfoCmp.Capability.key_down), "\u001b[B", "\u001bOB", "j").toTypedArray())
            bind("toggle", " ")
            bind("enter", "\r", "\n")
            bind("leave", "n", "q", "\u001b", "\u0003")
            ambiguousTimeout = 200
        }
        fun draw(first: Boolean) {
            val text = StringBuilder()
            if (!first) text.append("\u001b[").append(lines.size + footer.size + 2).append('A')
            text.append("\u001b[2K\r").append(Style.accent(title)).append("\r\n")
            lines.forEach { line ->
                text.append("\u001b[2K\r")
                when (line) {
                    is Line.Header -> text.append("  ").append(line.text)
                    is Line.Row -> {
                        val here = rows[cursor].index == line.index
                        val mark = if (line.index in marked) "[x]" else "[ ]"
                        val row = "${if (here) ">" else " "} $mark ${(line.index + 1).toString().padStart(2)}  ${line.text}"
                        text.append(if (here) Style.accent(row) else row)
                    }
                }
                text.append("\r\n")
            }
            footer.forEach { text.append("\u001b[2K\r").append(it).append("\r\n") }
            text.append("\u001b[2K\r").append(status(marked)).append("\r\n")
            text.append("\u001b[2K\r").append(Style.dim(if (several) "up and down move, space takes or leaves a file, Enter imports, n leaves" else "up and down move, Enter imports the one under the cursor, n leaves"))
            out.print(text)
            out.flush()
        }
        val saved = terminal.enterRawMode()
        out.print("\u001b[?25l")
        try {
            val reader = BindingReader(terminal.reader())
            draw(true)
            while (true) {
                when (reader.readBinding(keys)) {
                    "up" -> cursor = (cursor - 1 + rows.size) % rows.size
                    "down" -> cursor = (cursor + 1) % rows.size
                    "toggle" -> if (several) { val i = rows[cursor].index; if (!marked.remove(i)) marked += i }
                    "enter" -> return if (several) marked.toSet() else setOf(rows[cursor].index)
                    "leave", null -> return null
                }
                if (!several) { marked.clear(); marked += rows[cursor].index }
                draw(false)
            }
        } finally {
            out.print("\u001b[?25h\r\n")
            out.flush()
            terminal.setAttributes(saved)
            if (own) terminal.close()
        }
    }
}
