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

import org.example.project.components.terminal.window.controller.envBuilder.DEFAULT_PROFILE
import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.script.HeadlessAmie
import org.example.project.components.terminal.window.controller.script.SandboxApi
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp
import sun.misc.Signal
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * What the lines typed at a prompt mean, as python's prompt reads them: a line beginning with `:` is a command of
 * the prompt (`help`, `help(word)`, `quit` and `exit` are too, bare or with `()`, as python has them, and `amie ...` is
 * a command line of amie run here), a whole piece of script runs, and one that isn't whole yet waits at `...` for the rest. A blank line
 * sends a block as it is, unless a bracket is still open. Pure, so a test can type at it.
 */
class ReplInput(private val isComplete: (String) -> Boolean = HeadlessAmie::isComplete, private val hasClosedBrackets: (String) -> Boolean = HeadlessAmie::hasClosedBrackets) {
    sealed interface Action {
        /** Nothing to do: a blank line at the first prompt */
        data object Nothing : Action
        /** More lines are wanted: the prompt is `...` */
        data object More : Action
        /** A whole piece of script */
        data class Run(val code: String) : Action
        /** `:name rest` */
        data class Command(val name: String, val rest: String) : Action
    }

    private val pending = mutableListOf<String>()

    /** Whether a block waits for its rest */
    val continuing: Boolean get() = pending.isNotEmpty()

    /** The block typed so far, as one text */
    val block: String get() = pending.joinToString("\n")

    fun feed(line: String): Action {
        if (pending.isEmpty()) {
            val text = line.trim()
            if (text.isEmpty()) return Action.Nothing
            if (text.startsWith(":")) {
                val words = text.drop(1).split(Regex("\\s+"), limit = 2)
                return Action.Command(words[0].lowercase(), words.getOrElse(1) { "" }.trim())
            }
            bare(text)?.let { return it }
            COMMAND_LINE.matchEntire(text)?.let { return Action.Command("amie", it.groupValues[1].trim()) }
        }
        pending += line.trimEnd()
        val code = block
        return when {
            isComplete(code) -> Action.Run(take())
            line.isBlank() && hasClosedBrackets(code) -> Action.Run(take().trimEnd())
            else -> Action.More
        }
    }

    /** Drops the block typed so far, as Ctrl+C does */
    fun drop() = pending.clear()

    private fun take(): String = block.also { pending.clear() }

    /** `help`, `help(word)`, `quit` and `exit`, bare or with `()`, as the colon commands they stand for; null for script */
    private fun bare(text: String): Action.Command? {
        val match = BARE.matchEntire(text) ?: return null
        val word = match.groupValues[2].trim().trim('"', '\'')
        return when {
            match.groupValues[1] != "help" -> Action.Command("quit", "")
            word.isEmpty() -> Action.Command("help", "")
            else -> Action.Command("api", word)
        }
    }

    private companion object {
        /** `amie envs new prod`: the interpreter's own command line, typed where a script line goes */
        val COMMAND_LINE = Regex("""amie\s+(\S.*)""")
        val BARE = Regex("""(help|quit|exit)(?:\(\s*([^()]*?)\s*\))?""")
    }
}

/**
 * The prompt: lines of script run in one interpreter, what one defines there for the next, with the whole script
 * API in scope as main.kts has it. Line editing and the history (`~/.amie_history`) are JLine's.
 */
class Repl(private val options: Options, private val banner: Boolean = !options.quiet) {
    /** Where a help line's description starts, past the longest command, "amie templates --help" */
    private val HELP_COLUMN = 23

    private val input = ReplInput()
    private var terminal: Terminal? = null
        set(value) { field = value; currentTerminal = value; terminalWidth = value?.width?.takeIf { it > 20 } }

    companion object {
        /** The prompt's terminal while one is open, for a pick made at it */
        @Volatile var currentTerminal: Terminal? = null
            private set

        /** The width of the prompt's terminal while one is open, for help laid out to it */
        @Volatile var terminalWidth: Int? = null
            private set
    }

    /** The prompt's commands, each with what it does; the command lines of amie after them */
    val help: List<String> = (listOf(
        ":help"               to "these lines",
        ":api [word]"         to "the script's functions, those with the word in their name or description",
        ":env [name]"         to "the env the lines run in; with a name, that env from now on (what was defined is forgotten)",
        ":load file.kts"      to "runs the file's lines here, so what it defines stays",
        ":sessions"           to "every open session: models, groups, packages, templates in the background",
        ":config [x.jar]"     to "package config files: every jar's and whether each is filled in, or one jar's keys; key=value sets keys; :config --help for all of it",
        ":setup x.jar"        to "asks for each config key of the jar, with its hint; Enter keeps a value",
        ":docs"              to "opens the learn book in the browser: amie chapter by chapter, from the first line to a model served",
        ":terminate x"        to "closes the session named x, or #id; :terminate all closes every one",
        ":runs"               to "the recorded runs of main.kts, AMP/pluginDir/runs.jsonl",
        ":reset"              to "forgets what the lines typed here defined",
        ":clear"              to "clears the screen",
        ":quit"               to "leaves; Ctrl-D does too, Ctrl-C stops the line running or drops the block being typed",
        "help, help(x), quit" to "the same without the colon, as python has them: help(group) is :api group",
        "amie <command>"      to "a command line of amie, run here as the shell would (amie --help for its options), one of:",
    ) + Commands.all.map { "amie ${it.name}" + (if (it.actions.size > 1) " --help" else "") to it.summary }).map { (command, what) -> command.padEnd(HELP_COLUMN) + what }

    /** Reads lines until :quit or the end of input; the exit code */
    fun start(): Int {
        Logger.getLogger("org.jline").level = Level.OFF
        options.env?.let { useEnv(it) }
        // Standard input that is no terminal, a file or a pipe: its lines run as typed, with no prompt or banner, as python does
        if (System.console() == null) {
            generateSequence(::readLine).forEach { line -> if (!handleLine(line)) return Amie.EXIT_OK }
            return Amie.EXIT_OK
        }
        if (banner) println(Banner.greetingFor(AmpFolder.dir.path))
        val terminal = TerminalBuilder.builder().system(true).build().also { this.terminal = it }
        val reader = LineReaderBuilder.builder().terminal(terminal)
            .variable(LineReader.HISTORY_FILE, File(System.getProperty("user.home"), ".amie_history").path)
            // No shell-style history expansion: "!!" is Kotlin's non-null assertion, not the last command
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build()
        try {
            while (true) {
                val line = try {
                    reader.readLine(Style.prompt(if (input.continuing) "... " else ">>> "))
                } catch (e: UserInterruptException) {
                    println("KeyboardInterrupt")
                    input.drop()
                    continue
                } catch (e: EndOfFileException) {
                    break
                }
                if (!handleLine(line)) break
            }
        } finally {
            terminal.close()
            this.terminal = null
        }
        return Amie.EXIT_OK
    }

    /** One line typed: run, kept for the rest of its block, or a command; false once it was :quit */
    fun handleLine(line: String): Boolean = when (val action = input.feed(line)) {
        ReplInput.Action.Nothing, ReplInput.Action.More -> true
        is ReplInput.Action.Run -> { runCode(action.code); true }
        is ReplInput.Action.Command -> runCommand(action.name, action.rest)
    }

    /** Runs [code] in the shared interpreter; Ctrl-C meanwhile stops it, as python's does, instead of ending the process */
    fun runCode(code: String) {
        val before = Signal.handle(Signal("INT")) { HeadlessAmie.interruptLines() }
        try {
            HeadlessAmie.runLine(code)
        } finally {
            Signal.handle(Signal("INT"), before)
        }
    }

    /** One of the prompt's own commands; false when it was :quit */
    fun runCommand(name: String, rest: String): Boolean {
        when (name) {
            "quit", "exit", "q" -> return false
            "help", "h", "?" -> help.forEach { line ->
                val column = " ".repeat(HELP_COLUMN)
                println(Style.accent(line.take(HELP_COLUMN)) + wrap(line.drop(HELP_COLUMN), terminalWidth() - 1, column, column).removePrefix(column))
            }
            "api" -> describeApi(rest).forEach(::println)
            "env" -> if (rest.isEmpty()) println("env '${ScriptEnvironments.current?.name ?: "default"}'" + (ScriptEnvironments.current?.let { "\n" + it.print().joinToString("\n") } ?: "")) else useEnv(rest)
            "load" -> ScriptRun.readScript(rest)?.let { runCode(it) }
            "sessions" -> listSessions().forEach(::println)
            "docs" -> LearnBook.openInBrowser(LearnBook.writeToAmp())
            "terminate" -> println(terminate(rest))
            "runs" -> HeadlessAmie.readRecentRuns().ifEmpty { listOf("No runs yet") }.forEach(::println)
            "clear" -> clearScreen()
            "amie" -> runCommandLine(rest)
            "config" -> config(rest)
            "setup" -> if (rest.isBlank()) System.err.println(Style.error("Missing: the jar's name, :setup x.jar")) else runCommandLine("packages setup $rest")
            "reset" -> println(if (HeadlessAmie.reset()) "Forgotten: what the lines typed here defined" else "Nothing to forget: no line has run yet")
            else -> System.err.println(Style.error("No command :$name; :help lists them"))
        }
        return true
    }

    /** `:config [x.jar] [key=value...]`: `amie packages config` at the prompt; `:config --help` its lines */
    private fun config(rest: String) {
        val words = CommandLine.split(rest)
        if ("--help" !in words && "-h" !in words) return runCommandLine("packages config $rest")
        val lines = listOf(
            ":config" to "every jar of AMP/pluginDir with config files, and whether each is filled in",
            ":config x.jar" to "its config files: each key with its value under the hint the file gives, a secret hidden",
            ":config x.jar key=value..." to "sets the keys in the file that has them; a key no file has is refused unless --add",
            ":config x.jar key=value --all-jars" to "the same keys for every jar with a file of that name, as the Google packages share google.properties",
            ":config x.jar --file f --from path" to "copies a file in, such as a credentials JSON",
            ":config x.jar --edit" to "opens its files in \$VISUAL or \$EDITOR, else the Mac's text editor",
            ":setup x.jar" to "asks for each key at the terminal; Enter keeps a value, - empties it, and it offers values another jar already has",
            "packages.config(\"x.jar\")" to "in a script, the keys and values of its .properties files, file name to key to value",
            "packages.setConfig(\"x.jar\", \"k\" to \"v\")" to "in a script, sets keys in the file that has them; returns the keys no file has",
        )
        val column = lines.maxOf { it.first.length } + 2
        val pad = " ".repeat(column)
        println("The config files of a package jar live in ${File(AmpFolder.dir, "pluginDir/config").path}/<jar>/, written from its templates.\n")
        lines.forEach { (command, what) -> println(Style.accent(command.padEnd(column)) + wrap(what, terminalWidth() - 1, pad, pad).removePrefix(pad)) }
    }

    /** `amie models list` typed at the prompt: the command line as the shell would run it, in this process */
    private fun runCommandLine(line: String) {
        when (val parsed = CommandLine.parse(CommandLine.split(line))) {
            is Invocation.Command -> {
                if (parsed.options.amp != null) println("--amp can't change here; the AMP folder is ${AmpFolder.dir.path}")
                Commands.find(parsed.name)!!.run(parsed.args, parsed.options.copy(env = parsed.options.env ?: options.env))
            }
            is Invocation.Help -> println(parsed.command?.let(Commands::find)?.help ?: CommandLine.USAGE)
            is Invocation.Version -> println(Banner.versionLine)
            is Invocation.Usage -> System.err.println(Style.error(parsed.problem))
            is Invocation.Script -> ScriptRun.readScript(parsed.file)?.let {
                ScriptRun.passArguments(parsed.args)
                ScriptRun.runCode(it, parsed.options.env ?: options.env, parsed.file)
            }
            is Invocation.Code -> ScriptRun.runCode(parsed.code, parsed.options.env ?: options.env, "-c")
            is Invocation.Repl -> println("This is the prompt; :help lists its commands")
        }
    }

    /** Clears the screen the way the terminal does it; nothing when the lines come from a pipe */
    private fun clearScreen() {
        val t = terminal ?: return
        t.puts(InfoCmp.Capability.clear_screen)
        t.flush()
    }

    /** The functions of the script API, one line each: the signature and what it does; [word] narrows them */
    fun describeApi(word: String): List<String> {
        val q = word.trim().lowercase()
        return SandboxApi.entries.filter { it.deprecated == null && (q.isEmpty() || q in it.name.lowercase() || q in it.description.lowercase()) }
            .map { "${Style.accent(it.signature)}\n    ${Style.dim(it.description.take(200))}" }
            .ifEmpty { listOf("Nothing of the API has '$word' in its name or description") }
    }

    fun listSessions(): List<String> {
        val open = HeadlessAmie.listSessions()
        if (open.isEmpty()) return listOf("No open sessions")
        return listOf("${open.size} open:") + open.map { s -> "  ${s.owner} #${s.id} · ${s.kind}" + (s.scope?.let { " · $it" } ?: "") + (if (s.running) " · running" else "") }
    }

    fun terminate(what: String): String = when {
        what.isEmpty() -> ":terminate <name or #id>, or :terminate all; :sessions lists what is open"
        what.equals("all", ignoreCase = true) -> "${HeadlessAmie.terminateAll()} sessions closed"
        else -> HeadlessAmie.terminate(what).let { n -> if (n == 0) "No open session is named '$what'; :sessions lists them" else "$n session${if (n == 1) "" else "s"} closed" }
    }

    // The lines run in the env sourced last: sourced here with nothing new, so group("x") finds the env's groups
    private fun useEnv(name: String) {
        ScriptEnvironments.open(name).source(EnvPlan(name, DEFAULT_PROFILE, emptyList()))
        println("env '$name'")
    }
}
