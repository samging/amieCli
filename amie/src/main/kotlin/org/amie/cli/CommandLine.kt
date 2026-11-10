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

/** The options every form of the command line takes, before the script, the code or the command */
data class Options(
    /** -e: the env the script or the prompt runs in; null for the one the script names, else the default */
    val env: String? = null,
    /** --amp: the AMP folder to work in; null for AMIE_AMP_DIR, else the default */
    val amp: String? = null,
    /** -v: the internal lines too (the env sourcing, a build's phases) */
    val verbose: Boolean = false,
    /** -q: no banner at the prompt */
    val quiet: Boolean = false,
)

/** What the command line asks for */
sealed interface Invocation {
    val options: Options

    /** `amie`: the prompt */
    data class Repl(override val options: Options) : Invocation

    /** `amie script.kts [args]` or `amie run script.kts`: the file as main.kts; -i then the prompt */
    data class Script(val file: String, val args: List<String>, val interactive: Boolean, override val options: Options) : Invocation

    /** `amie -c "code"`: the code as main.kts; -i then the prompt */
    data class Code(val code: String, val interactive: Boolean, override val options: Options) : Invocation

    /** `amie model list`: one of [Commands] with its own arguments */
    data class Command(val name: String, val args: List<String>, override val options: Options) : Invocation

    data class Version(override val options: Options = Options()) : Invocation

    /** `-h`, or `amie model --help` */
    data class Help(val command: String? = null, override val options: Options = Options()) : Invocation

    /** Something the command line can't mean, with why; the exit code is 2 */
    data class Usage(val problem: String, override val options: Options = Options()) : Invocation
}

object CommandLine {
    const val SHORT_USAGE = "usage: amie [-e env] [--amp dir] [-v] [-q] [-i] [-c code | script.kts [args] | command [args]]\nTry amie --help for more."

    val USAGE: String = """usage: amie [options] [-c code | script.kts [args] | command [args]]

An interpreter for Kotlin scripts that train, ask, group and serve intent models, with the script's
functions in scope (initMod, group, database, modelBuilder, log, ...).

  amie                 the prompt: lines of script, kept from one to the next; :help lists its commands
  amie script.kts      runs the file as main.kts
  amie -c "log(1 + 1)" runs the code given
  amie -i script.kts   runs the file, then the prompt
  amie run script.kts  the same as the bare file
  amie new NAME        a project: a group of templates in an env of its name; amie run NAME runs them in order
  amie example         the starter scripts by group, what amie can do; amie run "<title>" runs one

Options
  -e, --env NAME       the env to run in (AMP/env/NAME), over the one the script names
  --amp DIR            the AMP folder to work in; the default is AMIE_AMP_DIR, else the user's data folder
  -v, --verbose        the internal lines too: the env sourcing, a build's phases, what a run used
  -q, --quiet          no banner at the prompt
  -i                   the prompt after the script or the code
  -h, --help           this; amie COMMAND --help for a command's
  --version            the version and the license

Commands
""" + Commands.all.joinToString("\n") { "  ${it.name.padEnd(12)} ${it.summary}" } + """

Console output goes to standard output, errors to standard error with the script's line; the exit code is
0 when it finished, 1 when it failed and 2 for a command line it can't read. What input() reads is standard input.
Script arguments after the file are the system property amie.args, one line each.
Licensed under the GNU General Public License, version 3 or later: see LICENSE."""

    /** Reads [args] as the shell gives them; never throws, a mistake is a [Invocation.Usage] */
    /** The words of [line] as a shell splits them: on spaces, with "..." and '...' kept together */
    fun split(line: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var inWord = false
        for (c in line) {
            when {
                quote != null -> if (c == quote) quote = null else word.append(c)
                c == '"' || c == '\'' -> { quote = c; inWord = true }
                c.isWhitespace() -> if (inWord) { words += word.toString(); word.clear(); inWord = false }
                else -> { word.append(c); inWord = true }
            }
        }
        if (inWord) words += word.toString()
        return words
    }

    fun parse(args: List<String>): Invocation {
        var options = Options()
        var interactive = false
        var i = 0
        fun value(flag: String): String? = args.getOrNull(++i)
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-h" || a == "--help" -> return Invocation.Help(options = options)
                a == "--version" || a == "-V" -> return Invocation.Version(options)
                a == "-i" -> interactive = true
                a == "-v" || a == "--verbose" -> options = options.copy(verbose = true)
                a == "-q" || a == "--quiet" -> options = options.copy(quiet = true)
                a == "-e" || a == "--env" -> options = options.copy(env = value(a) ?: return Invocation.Usage("$a needs the env's name"))
                a.startsWith("--env=") -> options = options.copy(env = a.removePrefix("--env="))
                a == "--amp" -> options = options.copy(amp = value(a) ?: return Invocation.Usage("--amp needs a folder"))
                a.startsWith("--amp=") -> options = options.copy(amp = a.removePrefix("--amp="))
                a == "-c" -> {
                    val code = value(a) ?: return Invocation.Usage("-c needs the code to run")
                    return Invocation.Code(code, interactive, options)
                }
                a == "--" -> return parsePositional(args.drop(i + 1), interactive, options)
                a.startsWith("-") && a.length > 1 -> return Invocation.Usage("Unknown option $a")
                else -> return parsePositional(args.drop(i), interactive, options)
            }
            i++
        }
        return Invocation.Repl(options)
    }

    // The first word that is no option: a command's name, or the script to run with what follows as its arguments
    private fun parsePositional(rest: List<String>, interactive: Boolean, options: Options): Invocation {
        val first = rest.first()
        val command = Commands.find(first)
        return when {
            command != null && rest.getOrNull(1).let { it == "--help" || it == "-h" } -> Invocation.Help(command.name, options)
            command != null && command.name == "run" -> rest.getOrNull(1)?.let { Invocation.Script(it, rest.drop(2), interactive, options) } ?: Invocation.Usage("run needs the script's file")
            command != null -> Invocation.Command(command.name, rest.drop(1), options)
            else -> Invocation.Script(first, rest.drop(1), interactive, options)
        }
    }
}

/** A command's own arguments: `--flag`, `--option value` or `--option=value`, and the words that are neither */
class Flags(args: List<String>, valued: Set<String>) {
    val words: List<String>
    private val values = mutableMapOf<String, String>()
    private val set = mutableSetOf<String>()
    /** An option given that the command doesn't know, so it can refuse instead of ignoring it */
    val unknown: List<String>

    init {
        val positional = mutableListOf<String>()
        val strange = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a.startsWith("--") && '=' in a -> a.substringBefore('=').let { name -> if (name in valued) values[name] = a.substringAfter('=') else strange += name }
                a in valued -> args.getOrNull(i + 1)?.let { values[a] = it; i++ } ?: strange.add(a)
                a.startsWith("--") -> set += a
                else -> positional += a
            }
            i++
        }
        words = positional
        unknown = strange
    }

    /** The value of `--name`, null when it wasn't given */
    fun valueOf(name: String): String? = values[name]

    /** Whether `--name` was given */
    fun has(name: String): Boolean = name in set
}
