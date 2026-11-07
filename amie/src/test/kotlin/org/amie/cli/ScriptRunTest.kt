/*
 * amie: an interpreter for Kotlin scripts that train, ask, group and serve intent models
 * Copyright (C) 2026 the amie authors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version. It is distributed WITHOUT ANY WARRANTY; see the GNU General Public License for details,
 * <https://www.gnu.org/licenses/>.
 */
package org.amie.cli

import org.example.project.getDirectory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One script through the interpreter against the test's own AMP folder (AMIE_AMP_DIR, which the build sets to
 * build/test-amp): its console on standard output, an error on standard error with the script's line, the exit code
 * as python's. The first run loads the Kotlin script compiler, so this takes seconds.
 */
class ScriptRunTest {
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @BeforeTest
    fun headless() {
        assertTrue(System.getenv("AMIE_AMP_DIR")?.contains("test-amp") == true, "the tests run against build/test-amp, never the user's AMP")
        AmpFolder.resolve(null)
        Output.install(verbose = false)
    }

    private fun captured(block: () -> Int): Int {
        val stdout = System.out
        val stderr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        try {
            return block()
        } finally {
            System.setOut(stdout)
            System.setErr(stderr)
        }
    }

    @Test
    fun `a script that logs finishes with its lines on standard output`() {
        val code = captured { ScriptRun.runCode("log(\"hi from amie\")\nlog(1 + 1)", env = null, name = "-c") }
        assertEquals(Amie.EXIT_OK, code, err.toString())
        val lines = out.toString().lines()
        assertTrue(lines.contains("hi from amie"), out.toString())
        assertTrue(lines.contains("2"), out.toString())
        assertTrue(File(getDirectory().toString(), "logs.txt").isFile)
    }

    @Test
    fun `a script that fails says its line on standard error and exits with 1`() {
        val code = captured { ScriptRun.runCode("log(\"first\")\nval x: Int = \"no\"", env = null, name = "-c") }
        assertEquals(Amie.EXIT_ERROR, code)
        assertTrue(err.toString().contains("2"), "the error names the script's line 2: ${err}")
    }

    @Test
    fun `a file that is not there is said and nothing runs`() {
        val code = captured { ScriptRun.readScript("no-such-file.kts")?.let { 0 } ?: Amie.EXIT_ERROR }
        assertEquals(Amie.EXIT_ERROR, code)
        assertTrue(err.toString().contains("no-such-file.kts"))
    }

    @Test
    fun `main runs a command against the AMP folder`() {
        val code = captured { Amie.main(listOf("envs", "list")) }
        assertEquals(Amie.EXIT_OK, code, err.toString())
        assertTrue(out.toString().contains("default"), out.toString())
    }

    @Test
    fun `version and usage`() {
        assertEquals(Amie.EXIT_OK, captured { Amie.main(listOf("--version")) })
        assertTrue(out.toString().startsWith("amie "), out.toString())
        assertTrue(out.toString().contains("GPL"))
        assertEquals(Amie.EXIT_USAGE, captured { Amie.main(listOf("--nope")) })
        assertEquals(Amie.EXIT_USAGE, captured { Amie.main(listOf("models")) })
        assertEquals(Amie.EXIT_OK, captured { Amie.main(listOf("models", "--help")) })
    }
}
