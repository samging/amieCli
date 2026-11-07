package org.example.project.components.terminal.window.controller.export

import org.example.project.components.terminal.window.controller.script.ScriptTemplates
import org.example.project.components.terminal.window.controller.console.headlessConsole
import org.example.project.getDirectory
import java.io.File
import kotlin.system.exitProcess

/** The command line of an export: `./gradlew :desktopApp:exportScript --args="main.kts --out ../my-bundle"`, or the app given --export-script */
object ExportCommand {
    const val FLAG = "--export-script"

    private const val USAGE = """Exports a script of amie pilot as a bundle that runs without the app.

  $FLAG <script> --out <folder> [--script] [--template <name>]... [--allow-missing] [--plan]

  <script>          main.kts, the id of a template, or a file
  --out             where the bundle goes: a new or empty folder
  --script          carry the script as text with the Kotlin compiler, instead of compiled to classes (the default)
  --template        a saved template to carry besides those the script starts, by name or id; may be given more than once
  --allow-missing   write it even though something could not be included, saying so in its README
  --plan            only say what would go in"""

    /** What a name on the command line or a template's row comes to: the code, a display name, and where it came from */
    data class Script(val code: String, val name: String, val source: String)

    fun find(script: String): Script? {
        val saved = File(getDirectory().toString(), "pluginDir/main.kts")
        // As the sandbox does on its first start, so the starters' ids work here before the app ever ran
        ScriptTemplates.seed(saved.takeIf { it.isFile }?.readText())
        val template = ScriptTemplates.find(script)
        return when {
            script == "main.kts" -> saved.takeIf { it.isFile }?.let { Script(it.readText(), "main", "main.kts") }
            template != null -> Script(ScriptTemplates.code(script).orEmpty(), template.name, "template $script")
            File(script).isFile -> Script(File(script).readText(), File(script).nameWithoutExtension, File(script).name)
            else -> null
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Logs go to the file; this prints what the person asked for
        headlessConsole = {}
        val list = args.toList().let { if (it.firstOrNull() == FLAG) it.drop(1) else it }
        val script = list.firstOrNull { !it.startsWith("--") }?.takeIf { it == list.first() }
        val out = list.indexOf("--out").takeIf { it >= 0 }?.let { list.getOrNull(it + 1) }
        val onlyPlan = "--plan" in list
        if (script == null || (out == null && !onlyPlan)) {
            println(USAGE)
            exitProcess(2)
        }
        val found = find(script) ?: fail("No script '$script': not main.kts, a template's id or a file")
        val allowMissing = "--allow-missing" in list
        val templates = list.withIndex().filter { it.value == "--template" }.mapNotNull { list.getOrNull(it.index + 1) }.toSet()
        val mode = if ("--script" in list) ExportMode.SCRIPT else ExportMode.COMPILED
        val plan = ScriptExporter.plan(found.code, found.name, found.source, ExportOptions(allowMissing = allowMissing, mode = mode, templates = templates))
        describe(plan)
        if (onlyPlan) exitProcess(if (plan.problems.isEmpty()) 0 else 1)
        if (plan.problems.isNotEmpty() && !allowMissing) fail("Nothing written. Deal with the above, or pass --allow-missing.")
        val written = runCatching { ScriptExporter.write(plan, File(out!!)) { step, _ -> if (step.startsWith("compiling")) println("  $step") } }
            .getOrElse { fail(if (it is ExportFailed) "Nothing written; the script doesn't compile:\n" + it.problems.joinToString("\n") { "  $it" } else it.message ?: "The export failed") }
        val size = written.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        println("Bundle written to ${written.absolutePath} (${size shr 20} MB); run it with: ${ScriptExporter.javaLine(written)}")
        exitProcess(0)
    }

    private fun describe(plan: ExportPlan) {
        val m = plan.manifest
        println("${m.name} (${m.source}): ${m.mode}, env ${m.env.name}, ${m.env.packages} packages, ${plan.copies.size + plan.generated.size} files, ${plan.bytes shr 20} MB")
        fun line(what: String, items: List<String>) { if (items.isNotEmpty()) println("  $what: ${items.joinToString()}") }
        line("models", m.models.map { it.name })
        line("built by the script", m.builtModels)
        line("encoders", m.encoders + listOfNotNull("named entities".takeIf { m.nerModel }))
        line("groups", m.groups)
        line("packages", m.packages)
        line("templates", m.templates.map { m.templateNames[it] ?: it })
        line("databases", m.databases.map { it.name })
        line("data", m.data)
        line("secrets to supply", m.secrets.filter { it.required }.map { it.name })
        m.leftOut.forEach { println("  left out: $it") }
        m.notes.forEach { println("  note: $it") }
        plan.problems.forEach { System.err.println("  PROBLEM: $it") }
    }

    private fun fail(message: String): Nothing {
        System.err.println(message)
        exitProcess(1)
    }
}
