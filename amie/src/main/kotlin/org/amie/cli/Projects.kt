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
import org.example.project.components.terminal.window.controller.script.HeadlessAmie
import org.example.project.components.terminal.window.controller.script.ScriptTemplate
import org.example.project.components.terminal.window.controller.script.ScriptTemplates
import java.io.File

/**
 * The templates a project groups: AMP/projects/<name>/templates.txt, one template id a line, in the order they run.
 * `amie run <project>` runs them one after another in the project's env, then the project's main.kts.
 */
object ProjectTemplates {
    private fun listFile(project: String) = File(Projects.dir(project), "templates.txt")

    fun exists(project: String): Boolean = project.isNotBlank() && !project.contains('/') && Projects.dir(project).isDirectory

    fun names(): List<String> = Projects.root.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.sorted()

    /** The ids in templates.txt, blank lines and # comments left out */
    fun ids(project: String): List<String> = listFile(project).takeIf { it.isFile }?.readLines()
        ?.map { it.substringBefore('#').trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** The project's templates in their order; an id whose template is gone is null */
    fun templates(project: String): List<Pair<String, ScriptTemplate?>> = ids(project).map { id -> id to ScriptTemplates.find(id) }

    fun write(project: String, ids: List<String>) {
        Projects.dir(project).mkdirs()
        listFile(project).writeText("# The templates of project $project, run in this order by amie run $project; one template id a line\n" + ids.joinToString("") { "$it\n" })
    }

    /** The project's env: the folder name, as amie new makes it */
    fun env(project: String): String = project

    /** Whether the project's main.kts holds more than the line amie new wrote */
    fun ownScript(project: String): File? = Projects.mainScript(project)?.takeIf { it.readText().trim() != Projects.starter(project).trim() }

    /**
     * Runs the project: each template as main.kts in [env] (else the project's), then its main.kts, stopping at the
     * first that fails; then waits for what they started in the background. The exit code: 0 finished, 1 failed.
     */
    fun run(project: String, env: String?): Int {
        val inEnv = env ?: env(project)
        val steps = templates(project).map { (id, t) ->
            val code = t?.let { ScriptTemplates.code(it.id) } ?: return Amie.EXIT_ERROR.also { System.err.println(Style.error("Project '$project' lists template '$id', and it is gone from AMP/templates")) }
            t.name to code
        } + listOfNotNull(ownScript(project)?.let { "main.kts" to it.readText() })
        if (steps.isEmpty()) {
            System.err.println(Style.error("Project '$project' has no templates yet: amie project add $project <template>, or amie template new <name> --project $project"))
            return Amie.EXIT_ERROR
        }
        for ((i, step) in steps.withIndex()) {
            println(Style.dim("${project}: ${i + 1}/${steps.size} ${step.first}"))
            if (HeadlessAmie.run(EnvPlan.withEnv(step.second, inEnv)) != null) {
                System.err.println(Style.error("Project '$project' stopped: ${step.first} failed"))
                HeadlessAmie.awaitBackground()
                return Amie.EXIT_ERROR
            }
        }
        val backgroundFailed = HeadlessAmie.awaitBackground()
        Serving.waitWhileListening()
        return if (backgroundFailed) Amie.EXIT_ERROR else Amie.EXIT_OK
    }
}

/** Opens [file] in $VISUAL or $EDITOR and waits; false when there is none or no terminal, so the caller prints the path */
internal fun openInEditor(file: File): Boolean? {
    val editor = (System.getenv("VISUAL") ?: System.getenv("EDITOR"))?.takeIf { it.isNotBlank() }
    if (editor == null || System.console() == null) return false
    val code = ProcessBuilder(editor.split(' ').filter { it.isNotEmpty() } + file.path).inheritIO().start().waitFor()
    return if (code == 0) true else null
}

/** `amie project`: projects as groups of templates */
object ProjectsCommand : Command("project", "Projects: each a group of templates run in order, in an env of its own, then its main.kts") {
    override val actions = listOf(
        "project list" to "every project, with its templates",
        "project show <project>" to "the project's templates in their order, its env and its folder",
        "project new <project> [template ...]" to "makes a project, with these templates in it; the same as amie new",
        "project add <project> <template> [template ...]" to "puts templates at the end of the project; --at <n> puts them at place n, moving one already in it",
        "project remove <project> <template>" to "takes a template out of the project; the template itself stays",
        "project run <project> [args]" to "runs its templates one after another, then its main.kts; the same as amie run <project>",
        "project edit <project>" to "opens templates.txt, the order the templates run in, in \$VISUAL or \$EDITOR",
    )
    override val valued = setOf("--at")

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return when (action) {
            "list" -> {
                Starters.seed()
                val all = ProjectTemplates.names()
                if (all.isEmpty()) println("No projects in ${Projects.root.shown}; amie new <name> makes one")
                all.forEach { p ->
                    val ts = ProjectTemplates.templates(p).map { (id, t) -> t?.name ?: Style.error("$id (gone)") }
                    println(Style.dim("name: ") + Style.accent(p) + "  " + Style.dim("templates: ") + (ts.joinToString(", ").ifEmpty { Style.dim("none") }) +
                        (if (ProjectTemplates.ownScript(p) != null) "  " + Style.dim("and main.kts") else ""))
                }
                Amie.EXIT_OK
            }
            "show" -> {
                val p = project(flags) ?: return Amie.EXIT_ERROR
                println(Style.dim("project: ") + Style.accent(p) + "  " + Style.dim("env: ") + ProjectTemplates.env(p) + "  " + Style.dim("folder: ") + Projects.dir(p).shown)
                val ts = ProjectTemplates.templates(p)
                if (ts.isEmpty()) println(Style.dim("  no templates yet: amie project add $p <template>"))
                ts.forEachIndexed { i, (id, t) ->
                    println("  ${i + 1}. " + (t?.let { Style.accent(it.name) + Style.dim("  ${it.lines} lines" + (it.description.lines().first().takeIf(String::isNotEmpty)?.let { d -> "  $d" } ?: "")) } ?: Style.error("$id: gone from AMP/templates")))
                }
                ProjectTemplates.ownScript(p)?.let { println("  then ${it.shown}") }
                Amie.EXIT_OK
            }
            "new" -> NewCommand.make(flags.requireWord(0, "the project's name") ?: return Amie.EXIT_USAGE, flags.words.drop(1))
            "add" -> {
                val p = project(flags) ?: return Amie.EXIT_ERROR
                if (flags.words.size < 2) return failUsage("Missing: the template to add")
                val found = flags.words.drop(1).map { find(it) ?: return Amie.EXIT_ERROR }
                val ids = ProjectTemplates.ids(p).toMutableList()
                val at = flags.valueOf("--at")?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: return failUsage("--at needs a place, 1 or more") }
                // A template already in the project moves to the place asked for; without --at it stays where it is
                val moving = found.map { it.id }.distinct().filter { at != null || it !in ids }
                ids.removeAll(moving)
                ids.addAll(((at ?: (ids.size + 1)) - 1).coerceAtMost(ids.size), moving)
                ProjectTemplates.write(p, ids)
                println("'$p' runs ${ids.size} template${if (ids.size == 1) "" else "s"}: " + ids.joinToString(", ") { id -> ScriptTemplates.find(id)?.name ?: id })
                Amie.EXIT_OK
            }
            "remove" -> {
                val p = project(flags) ?: return Amie.EXIT_ERROR
                val wanted = flags.requireWord(1, "the template to take out") ?: return Amie.EXIT_USAGE
                val ids = ProjectTemplates.ids(p)
                val id = ids.firstOrNull { it == wanted || ScriptTemplates.find(it)?.name.equals(wanted, ignoreCase = true) }
                    ?: return fail("Project '$p' has no template '$wanted'; project show $p lists them")
                ProjectTemplates.write(p, ids - id)
                println("'${ScriptTemplates.find(id)?.name ?: id}' taken out of '$p'; the template is still in AMP/templates")
                Amie.EXIT_OK
            }
            "run" -> {
                val p = project(flags) ?: return Amie.EXIT_ERROR
                ScriptRun.passArguments(flags.words.drop(1))
                ProjectTemplates.run(p, options.env)
            }
            "edit" -> {
                val p = project(flags) ?: return Amie.EXIT_ERROR
                val file = File(Projects.dir(p), "templates.txt")
                if (!file.isFile) ProjectTemplates.write(p, ProjectTemplates.ids(p))
                when (openInEditor(file)) {
                    false -> println(file.shown)
                    null -> return fail("The editor didn't finish; ${file.shown} is as it left it")
                    true -> println("'$p' runs: " + ProjectTemplates.templates(p).joinToString(", ") { (id, t) -> t?.name ?: "$id (gone)" })
                }
                Amie.EXIT_OK
            }
            else -> null
        }
    }

    private fun project(flags: Flags): String? {
        val p = flags.requireWord(0, "the project's name") ?: return null
        return p.takeIf(ProjectTemplates::exists) ?: null.also { System.err.println(Style.error("No project '$p'; project list names them, amie new $p makes it")) }
    }

    private fun find(wanted: String): ScriptTemplate? =
        Starters.template(wanted) ?: null.also { System.err.println(Style.error("No template '$wanted'; template list names them, template new $wanted makes it")) }
}
