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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandLineTest {
    @Test
    fun `no arguments is the prompt`() {
        assertEquals(Invocation.Repl(Options()), CommandLine.parse(emptyList()))
    }

    @Test
    fun `a file runs as a script with what follows as its arguments`() {
        val parsed = CommandLine.parse(listOf("train.kts", "a", "b"))
        assertEquals(Invocation.Script("train.kts", listOf("a", "b"), interactive = false, Options()), parsed)
    }

    @Test
    fun `run names the file the same way`() {
        assertEquals(Invocation.Script("x.kts", emptyList(), interactive = false, Options()), CommandLine.parse(listOf("run", "x.kts")))
        assertIs<Invocation.Usage>(CommandLine.parse(listOf("run")))
    }

    @Test
    fun `the options come before the script`() {
        val parsed = CommandLine.parse(listOf("-e", "prod", "--amp", "/tmp/amp", "-v", "-q", "-i", "x.kts"))
        assertEquals(Invocation.Script("x.kts", emptyList(), interactive = true, Options(env = "prod", amp = "/tmp/amp", verbose = true, quiet = true)), parsed)
        assertEquals(Options(env = "prod"), CommandLine.parse(listOf("--env=prod")).options)
    }

    @Test
    fun `-c takes the code`() {
        assertEquals(Invocation.Code("log(1)", interactive = false, Options()), CommandLine.parse(listOf("-c", "log(1)")))
        assertEquals(Invocation.Code("log(1)", interactive = true, Options()), CommandLine.parse(listOf("-i", "-c", "log(1)")))
        assertIs<Invocation.Usage>(CommandLine.parse(listOf("-c")))
    }

    @Test
    fun `a command's name takes its arguments`() {
        assertEquals(Invocation.Command("model", listOf("list"), Options()), CommandLine.parse(listOf("model", "list")))
        assertEquals(Invocation.Command("hf", listOf("search", "bert", "--format", "onnx"), Options()), CommandLine.parse(listOf("hf", "search", "bert", "--format", "onnx")))
    }

    @Test
    fun `help and version`() {
        assertEquals(Invocation.Help(), CommandLine.parse(listOf("-h")))
        assertEquals(Invocation.Help(), CommandLine.parse(listOf("--help")))
        assertEquals(Invocation.Help("model"), CommandLine.parse(listOf("model", "--help")))
        assertEquals(Invocation.Version(), CommandLine.parse(listOf("--version")))
    }

    @Test
    fun `an unknown option is a usage mistake`() {
        val parsed = CommandLine.parse(listOf("--what"))
        assertIs<Invocation.Usage>(parsed)
        assertTrue(parsed.problem.contains("--what"))
        assertIs<Invocation.Usage>(CommandLine.parse(listOf("-e")))
    }

    @Test
    fun `every command has help and a summary`() {
        Commands.all.forEach { c ->
            assertTrue(c.help.startsWith("usage: amie ${c.name}"), c.name)
            assertTrue(c.summary.isNotBlank(), c.name)
            assertTrue(c.actions.isNotEmpty(), c.name)
        }
        assertTrue(CommandLine.USAGE.contains("hf"))
    }

    @Test
    fun `flags read options, values and words`() {
        val flags = Flags(listOf("bert", "--format", "onnx", "--limit=5", "--dry", "more"), valued = setOf("--format", "--limit"))
        assertEquals(listOf("bert", "more"), flags.words)
        assertEquals("onnx", flags.valueOf("--format"))
        assertEquals("5", flags.valueOf("--limit"))
        assertTrue(flags.has("--dry"))
        assertEquals(emptyList(), flags.unknown)
        assertEquals(listOf("--other"), Flags(listOf("--other=1"), valued = emptySet()).unknown)
    }

    @Test
    fun `a name or a path is quoted for a script`() {
        assertEquals(listOf("group", "open", "weather", "is it raining", "x y", "z"), CommandLine.split("group open  weather \"is it raining\" 'x y' z "))
        assertEquals("\"a b\"", "a b".quoted())
        assertEquals("\"say \\\"hi\\\" \\\$x \\\\\"", "say \"hi\" \$x \\".quoted())
    }
}
