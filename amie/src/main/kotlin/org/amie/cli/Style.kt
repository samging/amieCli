/*
 * amie: an interpreter for Kotlin scripts that train, ask, group and serve intent models
 * Copyright (C) 2026 the amie authors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details. You should have received a copy of the GNU General Public License along with this program. If not, see
 * <https://www.gnu.org/licenses/>.
 */
package org.amie.cli

/**
 * The terminal's colors, as ANSI codes: the pride flag's six stripes across the banner, one accent for the commands,
 * dim for hints, red for errors, purple for the prompt. None when the output isn't a terminal (a pipe, a file), when NO_COLOR is set or on a dumb
 * terminal, so what a script prints stays plain text.
 */
object Style {
    val enabled: Boolean = System.console() != null && System.getenv("NO_COLOR").isNullOrEmpty() && System.getenv("TERM") != "dumb"

    fun accent(text: String): String = paint(text, "36")
    fun prompt(text: String): String = paint(text, "38;5;129")
    fun error(text: String): String = paint(text, "31")
    fun dim(text: String): String = paint(text, "2")

    /**
     * [text] in the pride flag's stripes, red to violet, as bands from left to right across its widest line: the banner
     * has five lines and the flag six stripes, so they run across rather than down
     */
    fun pride(text: String): String {
        if (!enabled) return text
        val lines = text.lines()
        val width = lines.maxOf { it.length }.coerceAtLeast(1)
        return lines.joinToString("\n") { line ->
            buildString {
                var stripe = -1
                line.forEachIndexed { i, c ->
                    val s = (i * PRIDE.size / width).coerceAtMost(PRIDE.size - 1)
                    if (s != stripe) {
                        append("\u001b[38;5;${PRIDE[s]}m")
                        stripe = s
                    }
                    append(c)
                }
                if (stripe >= 0) append("\u001b[0m")
            }
        }
    }

    private fun paint(text: String, code: String): String = if (enabled && text.isNotEmpty()) "\u001b[${code}m$text\u001b[0m" else text

    /** Red, orange, yellow, green, blue, violet, as the 256-color palette has them, which every terminal shows */
    private val PRIDE = listOf(196, 208, 226, 34, 27, 129)
}
