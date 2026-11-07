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

import org.example.project.components.terminal.window.controller.modules.SAMPLE_DATA
import org.example.project.components.terminal.window.controller.sandbox.SandboxExamples
import org.example.project.components.terminal.window.controller.script.ScriptTemplates
import org.example.project.getDirectory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The starters against the test's own AMP folder (AMIE_AMP_DIR, which the build sets to build/test-amp): seeded into
 * its templates as the app seeds them, found by their titles, listed by group by the examples command.
 */
class StartersTest {
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
    fun `the starters are seeded into the templates once, tagged with their group, with the dummy model and the sample csv`() {
        Starters.seed()
        val starters = ScriptTemplates.list().filter { "starter" in it.tags }
        SandboxExamples.all.forEach { ex ->
            assertTrue(starters.any { it.name == ex.title && ex.group.lowercase() in it.tags && it.source == "starter" }, "seeded: ${ex.title}")
        }
        assertTrue(File(ScriptTemplates.dir(), ".seeded").isFile)
        assertTrue(File(getDirectory().toString(), SAMPLE_DATA).isFile, "the sample csv the Models starters train on")
        assertTrue(File(getDirectory().toString(), "models/intents/dummy/module.json").isFile, "the dummy model the First steps ask")
        Starters.seed()
        assertEquals(starters.size, ScriptTemplates.list().count { "starter" in it.tags }, "seeding again brings nothing twice")
    }

    @Test
    fun `a starter is found by its title with the case apart, as a starter and as its template`() {
        assertEquals("Ask the dummy model", Starters.find("ask the DUMMY model")?.title)
        assertEquals("Ask the dummy model", Starters.template("ask the dummy model")?.name)
        assertEquals(null, Starters.find("No such starter"))
        val template = assertNotNull(Starters.template("Hello: log at every level"))
        assertEquals(ScriptTemplates.code(template.id), ScriptRun.readScript("Hello: log at every level"), "run by title reads the template's code")
    }

    @Test
    fun `the examples command lists every group and prints a starter by its title`() {
        assertEquals(Amie.EXIT_OK, captured { Amie.main(listOf("examples")) }, err.toString())
        val listed = out.toString()
        Starters.groups.forEach { assertTrue(listed.lines().contains(it), "group $it listed") }
        assertTrue(listed.contains("  Ask the dummy model"), listed)
        out.reset()
        assertEquals(Amie.EXIT_OK, captured { Amie.main(listOf("examples", "Ask the dummy model")) }, err.toString())
        assertTrue(out.toString().contains("initMod(\"dummy\")"), out.toString())
        assertEquals(Amie.EXIT_ERROR, captured { Amie.main(listOf("examples", "No such starter")) })
    }

    @Test
    fun `the command line takes a title after run`() {
        val parsed = CommandLine.parse(listOf("run", "Ask the dummy model"))
        assertEquals(Invocation.Script("Ask the dummy model", emptyList(), interactive = false, options = Options()), parsed)
        assertEquals(Invocation.Command("examples", listOf("Ask the dummy model"), Options()), CommandLine.parse(listOf("examples", "Ask the dummy model")))
    }
}
