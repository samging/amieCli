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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearnTest {
    @BeforeTest
    fun headless() {
        assertTrue(System.getenv("AMIE_AMP_DIR")?.contains("test-amp") == true, "the tests run against build/test-amp, never the user's AMP")
        AmpFolder.resolve(null)
    }

    @Test
    fun `the book is written to the AMP folder from the build's copy`() {
        val book = LearnBook.writeToAmp()
        assertTrue(book.isFile, book.path)
        assertEquals(File(AmpFolder.dir, "learn/index.html").canonicalPath, book.canonicalPath)
        val text = book.readText()
        assertTrue(text.contains("<title>Learn amie</title>"), "the book's title")
        assertTrue(text.contains("id=\"models\""), "the chapter on models")
        // Written again when it changed, so an older AMP gets the book of the amie that runs
        book.writeText("stale")
        assertEquals(text, LearnBook.writeToAmp().readText())
    }

    @Test
    fun `learn is a command, and path prints where the book is`() {
        assertEquals("learn", Commands.find("learn")?.name)
        assertEquals(Invocation.Command("learn", listOf("path"), Options()), CommandLine.parse(listOf("learn", "path")))
    }
}
