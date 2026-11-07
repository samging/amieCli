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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplInputTest {
    @Test
    fun `a whole line runs`() {
        val input = ReplInput()
        assertEquals(ReplInput.Action.Run("log(\"hi\")"), input.feed("log(\"hi\")"))
        assertFalse(input.continuing)
    }

    @Test
    fun `a blank first line does nothing`() {
        assertEquals(ReplInput.Action.Nothing, ReplInput().feed("   "))
    }

    @Test
    fun `a block waits for its rest and runs once whole`() {
        val input = ReplInput()
        assertEquals(ReplInput.Action.More, input.feed("for (x in 0..3) {"))
        assertTrue(input.continuing)
        assertEquals(ReplInput.Action.More, input.feed("    log(x)"))
        assertEquals(ReplInput.Action.Run("for (x in 0..3) {\n    log(x)\n}"), input.feed("}"))
        assertFalse(input.continuing)
    }

    @Test
    fun `a blank line sends a header whose brackets are closed`() {
        val input = ReplInput()
        assertEquals(ReplInput.Action.More, input.feed("val x = 1 +"))
        assertEquals(ReplInput.Action.More, input.feed("    2 +"))
        assertEquals(ReplInput.Action.Run("val x = 1 +\n    2 +"), input.feed(""))
    }

    @Test
    fun `a blank line inside an open bracket is part of the block`() {
        val input = ReplInput()
        assertEquals(ReplInput.Action.More, input.feed("fun f() {"))
        assertEquals(ReplInput.Action.More, input.feed(""))
        assertEquals(ReplInput.Action.Run("fun f() {\n\n}"), input.feed("}"))
    }

    @Test
    fun `a colon line is a command of the prompt`() {
        assertEquals(ReplInput.Action.Command("help", ""), ReplInput().feed(":help"))
        assertEquals(ReplInput.Action.Command("terminate", "search #3"), ReplInput().feed("  :TERMINATE search #3  "))
    }

    @Test
    fun `help, help(word), quit and exit work without the colon, as python's`() {
        assertEquals(ReplInput.Action.Command("help", ""), ReplInput().feed("help"))
        assertEquals(ReplInput.Action.Command("help", ""), ReplInput().feed("help()"))
        assertEquals(ReplInput.Action.Command("api", "group"), ReplInput().feed("help(group)"))
        assertEquals(ReplInput.Action.Command("api", "vote"), ReplInput().feed("help(\"vote\")"))
        assertEquals(ReplInput.Action.Command("quit", ""), ReplInput().feed("quit()"))
        assertEquals(ReplInput.Action.Command("quit", ""), ReplInput().feed("exit"))
        assertEquals(ReplInput.Action.Run("val helper = 1"), ReplInput().feed("val helper = 1"))
    }

    @Test
    fun `a command line of amie is run as one, a call on amie is script`() {
        assertEquals(ReplInput.Action.Command("amie", "envs new prod"), ReplInput().feed("amie envs new prod"))
        assertEquals(ReplInput.Action.Command("amie", "--help"), ReplInput().feed("  amie --help "))
        assertEquals(ReplInput.Action.Run("amie.log(\"x\")"), ReplInput().feed("amie.log(\"x\")"))
    }

    @Test
    fun `a colon inside a block is script`() {
        val input = ReplInput()
        input.feed("val m = mapOf(")
        assertEquals(ReplInput.Action.Run("val m = mapOf(\n:a to 1)"), input.feed(":a to 1)"))
    }

    @Test
    fun `drop forgets the block`() {
        val input = ReplInput()
        input.feed("if (ok) {")
        input.drop()
        assertFalse(input.continuing)
        assertEquals(ReplInput.Action.Run("log(1)"), input.feed("log(1)"))
    }
}
