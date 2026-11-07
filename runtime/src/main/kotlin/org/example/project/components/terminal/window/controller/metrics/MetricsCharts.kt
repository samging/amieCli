package org.example.project.components.terminal.window.controller.metrics

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Text charts for the console: it is monospace, so bars, a line chart and a grid drawn with box characters line up.
 * Everything returns lines; the caller joins and logs them.
 */
object AsciiCharts {
    private const val FULL = '█'
    private const val EMPTY = '░'

    /** One horizontal bar per row, scaled to [max]; the value and an optional note follow the bar */
    fun bars(
        rows: List<Triple<String, Double, String>>,
        width: Int = 30,
        max: Double = 1.0,
        format: (Double) -> String = { String.format(Locale.US, "%.3f", it) },
    ): List<String> {
        if (rows.isEmpty()) return emptyList()
        val labelWidth = rows.maxOf { it.first.length }.coerceAtLeast(5)
        return rows.map { (label, value, note) ->
            val filled = ((value / max).coerceIn(0.0, 1.0) * width).roundToInt()
            val bar = FULL.toString().repeat(filled) + EMPTY.toString().repeat(width - filled)
            "${label.padEnd(labelWidth)} $bar ${format(value)}${if (note.isEmpty()) "" else "  $note"}"
        }
    }

    /**
     * Several series over the same x positions (builds 1..n) on one canvas with a 0..[max] axis. Each series has a
     * glyph; where two meet the cell shows [overlap]. Columns are spread so the chart is at most [width] wide.
     * [xLabels] names the positions under the axis (epochs, say) instead of 1..n.
     */
    fun series(
        series: List<Pair<String, List<Double>>>,
        glyphs: List<Char> = listOf('●', '○', '◇', '△'),
        height: Int = 10,
        width: Int = 60,
        max: Double = 1.0,
        overlap: Char = '◆',
        xLabels: List<String>? = null,
    ): List<String> {
        val n = series.maxOfOrNull { it.second.size } ?: 0
        if (n == 0) return listOf("(nothing to draw)")
        val step = if (n == 1) 1 else (width / (n - 1)).coerceIn(1, 6)
        val canvasWidth = (n - 1) * step + 1
        val canvas = Array(height) { CharArray(canvasWidth) { ' ' } }
        series.forEachIndexed { si, (_, values) ->
            val glyph = glyphs.getOrElse(si) { '*' }
            values.forEachIndexed { x, v ->
                val row = (height - 1) - ((v / max).coerceIn(0.0, 1.0) * (height - 1)).roundToInt()
                val col = x * step
                canvas[row][col] = if (canvas[row][col] == ' ') glyph else overlap
            }
        }
        val lines = ArrayList<String>(height + 4)
        for (r in 0 until height) {
            val value = max * (height - 1 - r) / (height - 1)
            val tick = if (r == 0 || r == height - 1 || r == height / 2) String.format(Locale.US, "%4.2f", value) else "    "
            lines.add("$tick ┤" + String(canvas[r]))
        }
        lines.add("     └" + "─".repeat(canvasWidth))
        // Build numbers under their columns (1-based); a wide chart gets every number, a dense one every few.
        // Named positions get room for the last name past the canvas
        val longest = xLabels?.maxOfOrNull { it.length } ?: 1
        val axisWidth = if (xLabels != null) canvasWidth + longest - 1 else canvasWidth
        val axis = CharArray(axisWidth) { ' ' }
        val every = if (xLabels != null) ((longest + step) / step).coerceAtLeast(1)
            else if (step >= 3) 1 else (3 / step).coerceAtLeast(1)
        for (x in 0 until n step every) {
            val text = xLabels?.getOrNull(x) ?: (x + 1).toString()
            val col = x * step
            text.forEachIndexed { i, c -> if (col + i < axisWidth) axis[col + i] = c }
        }
        lines.add("      " + String(axis).let { if (xLabels != null) it.trimEnd() else it })
        lines.add("      " + series.mapIndexed { i, (name, _) -> "${glyphs.getOrElse(i) { '*' }} $name" }.joinToString("   ") +
            (if (series.size > 1) "   $overlap both" else ""))
        return lines
    }

    /** Rows are the truth, columns the prediction; each cell is shaded by its share of the row and shows the count */
    fun grid(labels: List<String>, matrix: List<List<Int>>, cell: Int = 7): List<String> {
        if (labels.isEmpty() || matrix.isEmpty()) return listOf("(empty confusion matrix)")
        val shades = " ·░▒▓█"
        val labelWidth = labels.maxOf { it.length }.coerceIn(5, 14)
        fun short(label: String, width: Int) = if (label.length <= width) label else label.take(width - 1) + "…"
        val lines = ArrayList<String>(labels.size + 3)
        lines.add("${"truth \\ pred".padEnd(labelWidth)} │" + labels.joinToString("") { short(it, cell - 1).padStart(cell) })
        lines.add("─".repeat(labelWidth + 1) + "┼" + "─".repeat(cell * labels.size))
        matrix.forEachIndexed { i, row ->
            val total = row.sum().coerceAtLeast(1)
            val cells = row.mapIndexed { j, count ->
                val shade = shades[((count.toDouble() / total) * (shades.length - 1)).roundToInt()]
                val mark = if (i == j) "✓" else " "
                "$shade$mark${count.toString()}".padStart(cell)
            }
            lines.add("${short(labels.getOrElse(i) { "#$i" }, labelWidth).padEnd(labelWidth)} │" + cells.joinToString(""))
        }
        lines.add("shade = share of the row; ✓ marks correct predictions")
        return lines
    }
}
