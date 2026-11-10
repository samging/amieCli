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

import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.export.ExportCommand
import org.example.project.components.terminal.window.controller.script.HeadlessAmie
import org.example.project.components.terminal.window.controller.script.ScriptTemplate
import org.example.project.components.terminal.window.controller.script.ScriptTemplates
import java.io.File

/**
 * A subcommand: `amie <name> <action> [args]`, each with --help. [actions] are its forms, one line each, which the
 * help lists and [run] dispatches on the first word.
 */
abstract class Command(val name: String, val summary: String) {
    /** Each action's usage line and what it does, in the help's order */
    abstract val actions: List<Pair<String, String>>

    /** The options the actions take a value for (`--port 8765`), so [Flags] reads them as such */
    open val valued: Set<String> = emptySet()

    /** The action `amie <name>` alone does; without one, the bare name prints the help */
    open val defaultAction: String? = null

    /** The first line of the help, what the command line looks like */
    open val usage: String get() = "amie $name <action> [args]"

    /** The usage, the summary, then each action on a line of its own with what it does wrapped beneath it */
    val help: String get() {
        val width = terminalWidth() - 1
        return "usage: $usage\n\n" + wrap(summary, width) + "\n\n" +
            actions.joinToString("\n\n") { (usage, what) -> "  $usage\n" + wrap(what, width, "      ") } +
            "\n\n  --help on any action prints this."
    }

    fun run(args: List<String>, options: Options): Int {
        defaultAction?.let { if (args.isEmpty()) return run(listOf(it), options) }
        if (args.isEmpty() || args.first() in setOf("--help", "-h") || "--help" in args) {
            println(help)
            return if (args.isEmpty()) Amie.EXIT_USAGE else Amie.EXIT_OK
        }
        val flags = Flags(args.drop(1), valued)
        if (flags.unknown.isNotEmpty()) return failUsage("Unknown option ${flags.unknown.first()} for $name ${args.first()}")
        return try {
                perform(args.first(), flags, options) ?: failUsage("No action '${args.first()}' for $name")
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
    }

    /** Does [action] with [flags]; null when there is no such action, so the help is printed */
    protected abstract fun perform(action: String, flags: Flags, options: Options): Int?

    protected fun fail(message: String): Int {
        System.err.println(Style.error(message))
        return Amie.EXIT_ERROR
    }

    protected fun failUsage(message: String): Int {
        System.err.println(Style.error(message))
        System.err.println(help)
        return Amie.EXIT_USAGE
    }

    /** The positional word at [index], or null after saying what was missing */
    protected fun Flags.requireWord(index: Int, what: String): String? = words.getOrNull(index) ?: null.also { System.err.println(Style.error("Missing: $what")) }

    protected fun printTable(rows: List<List<String>>) {
        if (rows.isEmpty()) return
        val widths = rows.first().indices.map { c -> rows.maxOf { it.getOrElse(c) { "" }.length } }
        rows.forEach { row -> println(row.mapIndexed { c, cell -> if (c == row.lastIndex) cell else cell.padEnd(widths[c]) }.joinToString("  ").trimEnd()) }
    }
}

object Commands {
    val all: List<Command> by lazy { listOf(RunCommand, NewCommand, ExamplesCommand, ModelsCommand, GroupsCommand, PackagesCommand, EnvsCommand, TemplatesCommand, SessionsCommand, HfCommand, LearnCommand) }

    fun find(name: String): Command? = all.firstOrNull { it.name == name }
}

/** `amie run script.kts`: the command line reads it as the bare file, so this only holds its help */
object RunCommand : Command("run", "Runs a script file, a starter or a template as main.kts, in the env it names (or -e's)") {
    override val actions = listOf(
        "run script.kts [args]" to "the same as amie script.kts; -i then opens the prompt",
        "run \"<title>\" [args]" to "a starter or a template by its title, as amie examples lists them",
    )
    override fun perform(action: String, flags: Flags, options: Options): Int? = null
}

/** `amie new <name>`: a project, a folder of AMP/projects with a main.kts to start from and an env of its name */
object NewCommand : Command("new", "A project: AMP/projects/<name> with a main.kts to start from, in an env of the same name; amie run <name> runs it") {
    override val actions = listOf(
        "new <name>" to "makes the project's folder, its main.kts and its env; nothing on a name taken",
    )
    override val usage = "amie new <name>"

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        val name = action
        if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) return fail("A project's name is letters, digits, dots, dashes and underscores; '$name' isn't")
        val dir = Projects.dir(name)
        if (dir.exists()) return fail("project '$name' exists already at ${dir.shown}")
        if (name !in ScriptEnvironments.names()) ScriptEnvironments.peek(name).dir.mkdirs()
        dir.mkdirs()
        File(dir, "main.kts").writeText(Projects.starter(name))
        println("project '$name' made at ${dir.shown}, in env '$name'; amie run $name runs its main.kts")
        return Amie.EXIT_OK
    }
}

/** The projects of the AMP folder: AMP/projects/<name>, each a main.kts and what it writes beside it */
object Projects {
    val root: File get() = File(AmpFolder.dir, "projects")

    fun dir(name: String): File = File(root, name)

    /** The project's main.kts, or null when there is no project of that name */
    fun mainScript(name: String): File? = File(dir(name), "main.kts").takeIf { name.isNotBlank() && !name.contains('/') && it.isFile }

    /** The main.kts a new project starts from: its env, and a line to see it run */
    fun starter(name: String): String = """
        |environment(SettingsEnv("$name"))
        |
        |log("$name: hello")
        |""".trimMargin()
}

/** `amie examples`: the starter scripts by group; a title prints one, `amie run "<title>"` runs it */
object ExamplesCommand : Command("examples", "The starter scripts, by group: what amie can do, each ready to run") {
    override val actions = listOf(
        "examples list" to "every starter by group, with its title",
        "examples \"<title>\"" to "prints the starter's code; amie run \"<title>\" runs it",
    )
    override val defaultAction = "list"

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        if (action == "list") {
            Starters.seed()
            Starters.groups.forEach { group ->
                println(group)
                Starters.inGroup(group).forEach { println("  ${it.title}") }
            }
            println("amie examples \"<title>\" prints one, amie run \"<title>\" runs it; they are in AMP/templates too (amie templates list)")
            return Amie.EXIT_OK
        }
        val title = if (action == "show") flags.requireWord(0, "the starter's title") ?: return Amie.EXIT_USAGE else action
        val starter = Starters.find(title) ?: return fail("No starter '$title'; amie examples lists them")
        println(starter.code)
        return Amie.EXIT_OK
    }
}

object SessionsCommand : Command("sessions", "The sessions open in this process, and the recorded runs of main.kts") {
    override val actions = listOf(
        "sessions list" to "every session open in this process (a model, a group, a package, a template in the background)",
        "sessions runs" to "the recorded runs of main.kts, AMP/pluginDir/runs.jsonl",
        "sessions terminate <name or #id>" to "closes that session; terminate all closes every one",
    )

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return when (action) {
            "list" -> {
                val open = HeadlessAmie.listSessions()
                if (open.isEmpty()) println("No open sessions in this process: sessions live in the process that opened them, so :sessions at the prompt lists the prompt's")
                else printTable(open.map { s -> listOf("#${s.id}", s.owner, s.kind, s.scope ?: "", if (s.running) "running" else "") })
                Amie.EXIT_OK
            }
            "runs" -> HeadlessAmie.readRecentRuns().ifEmpty { listOf("No runs of main.kts yet") }.forEach(::println).let { Amie.EXIT_OK }
            "terminate" -> {
                val what = flags.requireWord(0, "the session's name or #id, or all") ?: return Amie.EXIT_USAGE
                val n = if (what.equals("all", ignoreCase = true)) HeadlessAmie.terminateAll() else HeadlessAmie.terminate(what)
                println("$n session${if (n == 1) "" else "s"} closed")
                Amie.EXIT_OK
            }
            else -> null
        }
    }
}

object EnvsCommand : Command("envs", "The envs of AMP/env: each keeps the Maven packages, jars and model groups a script sources") {
    override val actions = listOf(
        "envs list" to "every env, with what it holds",
        "envs new <name>" to "makes an env, empty",
        "envs use <name>" to "the env the saved main.kts runs in from now on: its environment line is rewritten, or a main.kts made with one",
        "envs show <name>" to "what the env holds: its packages, where each came from, its groups",
    )

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return when (action) {
            "list" -> {
                printTable(ScriptEnvironments.names().map { name ->
                    val env = ScriptEnvironments.peek(name)
                    listOf(name, "${env.packages.size} package${if (env.packages.size == 1) "" else "s"}", "${env.groups().size} group${if (env.groups().size == 1) "" else "s"}", env.dir.path)
                })
                Amie.EXIT_OK
            }
            "new" -> {
                val name = flags.requireWord(0, "the env's name") ?: return Amie.EXIT_USAGE
                if (name in ScriptEnvironments.names()) return fail("env '$name' exists already")
                // An env is its folder under AMP/env; its lock file comes with the first package sourced into it
                ScriptEnvironments.peek(name).dir.mkdirs()
                println("env '$name' made at ${ScriptEnvironments.peek(name).dir.shown}; environment(SettingsEnv(\"$name\")) in a script sources it, or amie -e $name")
                Amie.EXIT_OK
            }
            "use" -> {
                val name = flags.requireWord(0, "the env's name") ?: return Amie.EXIT_USAGE
                val main = File(AmpFolder.dir, "pluginDir/main.kts")
                val code = if (main.isFile) main.readText() else ""
                main.parentFile.mkdirs()
                main.writeText(EnvPlan.withEnv(code, name))
                println(if (code.isEmpty()) "${main.shown} made: it runs in env '$name'" else "main.kts runs in env '$name' from now on")
                Amie.EXIT_OK
            }
            "show" -> {
                val name = flags.requireWord(0, "the env's name") ?: return Amie.EXIT_USAGE
                if (name !in ScriptEnvironments.names()) return fail("No env '$name'; envs list names them")
                ScriptEnvironments.peek(name).print().forEach(::println)
                Amie.EXIT_OK
            }
            else -> null
        }
    }
}

object TemplatesCommand : Command("templates", "The script templates of AMP/templates: the starters and the scripts saved there") {
    override val actions = listOf(
        "templates list" to "every template: its name, lines, env and what it opens, and which run in this process",
        "templates show <name or id>" to "prints the template's code",
        "templates run <name or id> [args]" to "runs the template as main.kts",
        "templates export <name or id> --out <folder> [--no-jars]" to "exports it as a bundle that runs on its own (java -jar run.jar); --no-jars leaves the env's Maven packages and the package jars out, listed in the bundle's require.txt",
        "templates save <name> <file> [--description <text>]" to "keeps a script file (or a project or starter by its name) as the template <name>; a name taken is saved again, its id kept",
        "templates edit <name or id>" to "opens its file in \$VISUAL or \$EDITOR and takes the edit; prints the file's path when neither is set",
        "templates delete <name or id>" to "removes the template, its code and its entry",
    )
    override val valued = setOf("--out", "--description")

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return when (action) {
            "list" -> {
                Starters.seed()
                val templates = ScriptTemplates.list()
                if (templates.isEmpty()) println("No templates in ${ScriptTemplates.dir().shown}")
                else {
                    // One template two lines: its labelled fields, then the description fitted to the terminal
                    val width = terminalWidth() - 1
                    val running = HeadlessAmie.listSessions().filter { it.kind == "Background template" }.map { it.owner }.toSet()
                    templates.forEach { t ->
                        val uses = if (t.uses.size > 2) "${t.uses.first()} … (${t.uses.size})" else t.uses.joinToString(", ")
                        val fields = listOfNotNull(
                            Style.dim("name: ") + Style.accent(t.name),
                            Style.dim("lines: ") + t.lines,
                            t.env?.let { Style.dim("env: ") + it },
                            uses.takeIf { it.isNotEmpty() }?.let { Style.dim("opens: ") + it },
                            if (t.name in running) Style.ok("running") else null,
                        )
                        println(fields.joinToString("  "))
                        val d = t.description.lines().first()
                        if (d.isNotEmpty()) println(Style.dim("  " + if (d.length > width - 2) d.take((width - 3).coerceAtLeast(0)).trimEnd() + "…" else d))
                    }
                    println(Style.dim("amie templates show \"<name>\" prints one, run runs it, export makes a bundle of it; a name or its id, as the file in AMP/templates. Running means in this process"))
                }
                Amie.EXIT_OK
            }
            "show" -> findTemplate(flags)?.let { t -> println(ScriptTemplates.code(t.id).orEmpty()); Amie.EXIT_OK } ?: Amie.EXIT_ERROR
            "run" -> findTemplate(flags)?.let { t ->
                ScriptRun.passArguments(flags.words.drop(1))
                ScriptRun.runCode(ScriptTemplates.code(t.id) ?: return fail("The code of template '${t.name}' is gone from AMP/templates"), options.env, t.name)
            } ?: Amie.EXIT_ERROR
            "export" -> {
                val t = findTemplate(flags) ?: return Amie.EXIT_ERROR
                val out = flags.valueOf("--out") ?: return failUsage("export needs --out <folder>")
                // Says what goes in and exits with its own code: 0 written, 1 a problem, 2 nothing to export
                ExportCommand.main((listOf(t.id, "--out", out) + listOf("--no-jars").filter { flags.has(it) }).toTypedArray())
                Amie.EXIT_OK
            }
            "save" -> {
                val name = flags.requireWord(0, "the template's name") ?: return Amie.EXIT_ERROR
                val file = flags.requireWord(1, "the script file to keep") ?: return Amie.EXIT_ERROR
                val code = ScriptRun.readScript(file) ?: return Amie.EXIT_ERROR
                val before = Starters.template(name)
                val t = try {
                    ScriptTemplates.save(name, code, flags.valueOf("--description") ?: before?.description.orEmpty(), before?.tags.orEmpty(), File(file).name)
                } catch (e: IllegalArgumentException) {
                    return fail(e.message ?: "The template can't be saved")
                }
                println("'${t.name}' ${if (before == null) "saved" else "saved again, its id kept"} as ${ScriptTemplates.file(t.id).shown} (${lines(t.lines)}); templates run \"${t.name}\" runs it")
                Amie.EXIT_OK
            }
            "edit" -> {
                val t = findTemplate(flags) ?: return Amie.EXIT_ERROR
                val file = ScriptTemplates.file(t.id)
                if (!file.isFile) return fail("The code of template '${t.name}' is gone from AMP/templates")
                val editor = (System.getenv("VISUAL") ?: System.getenv("EDITOR"))?.takeIf { it.isNotBlank() }
                if (editor == null || System.console() == null) {
                    println(file.shown)
                    return Amie.EXIT_OK
                }
                val code = ProcessBuilder(editor.split(' ').filter { it.isNotEmpty() } + file.path).inheritIO().start().waitFor()
                if (code != 0) return fail("$editor left with $code; '${t.name}' is as it was")
                // The entry follows the edit: its line count, what it opens and its env
                ScriptTemplates.save(t.name, file.readText(), t.description, t.tags, t.source)
                println("'${t.name}' edited: ${lines(file.readText().trimEnd().lines().size)}")
                Amie.EXIT_OK
            }
            "delete" -> {
                val t = findTemplate(flags) ?: return Amie.EXIT_ERROR
                if (!ScriptTemplates.delete(t.id)) return fail("'${t.name}' is already gone from AMP/templates")
                println("'${t.name}' deleted: ${t.id}.kts and ${t.id}.json left AMP/templates")
                Amie.EXIT_OK
            }
            else -> null
        }
    }

    private fun lines(n: Int) = if (n == 1) "1 line" else "$n lines"

    private fun findTemplate(flags: Flags): ScriptTemplate? {
        val wanted = flags.requireWord(0, "the template's name or id") ?: return null
        return Starters.template(wanted) ?: null.also { System.err.println(Style.error("No template '$wanted'; templates list names them")) }
    }
}
