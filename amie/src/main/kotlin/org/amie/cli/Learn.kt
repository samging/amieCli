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

import java.io.File

/** `amie docs`: the learn book, amie chapter by chapter from the first line to a model served, as HTML in the browser */
object LearnCommand : Command("docs", "The learn book: the prompt, scripts, models, groups, packages, envs, templates and serving, chapter by chapter") {
    override val actions = listOf(
        "docs" to "opens the book in the browser",
        "docs path" to "prints where the book is, for a browser of your own",
    )
    override val defaultAction = "open"

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return when (action) {
            "open" -> LearnBook.openInBrowser(LearnBook.writeToAmp())
            "path" -> {
                println(LearnBook.writeToAmp())
                Amie.EXIT_OK
            }
            else -> null
        }
    }
}

/**
 * The book itself: learn/index.html of the module, in the jar as a resource. It is copied to AMP/learn to be opened,
 * and copied again when this build's differs, so an older AMP gets the book of the amie that runs.
 */
object LearnBook {
    private const val RESOURCE = "/learn/index.html"

    /** The book on disk, AMP/learn/index.html, written from this build's copy */
    fun writeToAmp(): File {
        val text = LearnBook::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: throw IllegalStateException("This build has no book: learn/index.html isn't in it")
        val copy = File(AmpFolder.dir, "learn/index.html")
        if (!copy.isFile || !copy.readBytes().contentEquals(text)) {
            copy.parentFile.mkdirs()
            copy.writeBytes(text)
        }
        return copy
    }

    /** Opens [file] with what the OS opens files with: the browser for HTML; when nothing can, says where the book is */
    fun openInBrowser(file: File): Int {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            "mac" in os -> listOf("open", file.path)
            "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", file.path)
            else -> listOf("xdg-open", file.path)
        }
        val opened = runCatching { ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
        if (!opened) System.err.println(Style.error("No browser opened it; the book is at $file"))
        return if (opened) Amie.EXIT_OK else Amie.EXIT_ERROR
    }
}
