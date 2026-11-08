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

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.ResponseDto
import org.example.project.components.terminal.window.controller.script.HeadlessAmie

/**
 * Where the console's lines go: standard output, the errors on standard error, as a bundle's runner has it. The
 * internal level (the env sourcing, a build's phases, what a run used) is left out unless -v was given or a command
 * asks for it, since a model's metrics and a package's test report are said at that level; every level goes to
 * AMP/logs.txt regardless.
 */
object Output {
    @Volatile var verbose = false

    /** Takes the console over; before the first line that logs */
    fun install(verbose: Boolean) {
        this.verbose = verbose
        // The engine's loggers are for the log file; standard error is for what went wrong in the script
        System.setProperty("org.slf4j.simpleLogger.log.CodingSandbox", "off")
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        System.setProperty("org.slf4j.simpleLogger.log.ai.djl", "error")
        HeadlessAmie.useConsole(::print)
    }

    fun print(line: ResponseDto) {
        if (line.level == LogLevel.INTERNAL && !verbose) return
        val out = if (line.level == LogLevel.ERROR) System.err else System.out
        val text = line.message ?: ""
        out.println(when (line.level) {
            LogLevel.ERROR -> Style.error(text)
            LogLevel.INTERNAL -> Style.dim(text)
            else -> text
        })
        out.flush()
    }

    /** Runs [block] with the internal lines shown, for a command whose answer is said at that level */
    fun <T> withInternalShown(block: () -> T): T {
        val before = verbose
        verbose = true
        try {
            return block()
        } finally {
            verbose = before
        }
    }
}

/** The terminal's width in columns: the prompt's terminal when amie runs at it, else COLUMNS, else 100 */
fun terminalWidth(): Int = Repl.terminalWidth ?: System.getenv("COLUMNS")?.toIntOrNull()?.takeIf { it > 20 } ?: 100

/**
 * [text] in lines no longer than [width], broken at spaces: the first line opens with [first], every other with
 * [indent]; a word longer than a line stands alone. Lines [text] already has are kept.
 */
fun wrap(text: String, width: Int, indent: String = "", first: String = indent): String = text.lines().joinToString("\n") { paragraph ->
    val lines = mutableListOf<String>()
    var line = StringBuilder(first)
    var empty = true
    paragraph.split(' ').filter { it.isNotEmpty() }.forEach { word ->
        if (!empty && line.length + 1 + word.length > width) {
            lines += line.toString()
            line = StringBuilder(indent)
            empty = true
        }
        if (!empty) line.append(' ')
        line.append(word)
        empty = false
    }
    lines += line.toString()
    lines.joinToString("\n")
}
