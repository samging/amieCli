package org.example.project.components.terminal.window.controller.sandbox

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.example.project.components.terminal.window.controller.script.SandboxApi

/*
 * What handleBy(message) tells the local text model about the script environment. A small
 * model on the CPU reads every word of its prompt before it says anything, so the prompt is kept short: the API one
 * line an entry, the script and the errors cut at a size, the env in a few lines.
 */

/** The script environment when handleBy asks: main.kts, the error asked about, the env and the models, read on the script's thread */
internal data class ScriptContext(
    /** main.kts */
    val script: String,
    /** The env the script declares (environment(SettingsEnv("name")), else default) */
    val envName: String,
    /** What the env was asked for: Maven coordinates and local jars */
    val envPackages: List<String> = emptyList(),
    /** The env's model groups, as "name: model, model" */
    val envGroups: List<String> = emptyList(),
    /** What initMod("name") can open: the trained intent models, the text models and the speech models */
    val intentModels: List<String> = emptyList(),
    val textModels: List<String> = emptyList(),
    val speechModels: List<String> = emptyList(),
    /** The error asked about, as the console has it: "main.kts:4:9: Unresolved reference: dumy" */
    val errors: List<String> = emptyList(),
)

/** What is asked: a short name for it, the question the model is given, and how long and how free the answer may be */
internal data class Question(val shown: String, val text: String, val maxTokens: Int, val temperature: Double)

internal object AssistantPrompt {
    /** The most of the script the prompt carries; a longer one is cut there, with how many lines were left out */
    const val SCRIPT_CHARS = 6_000

    /** The most of the API: the entries the script calls first, then the rest in their order, until this is full */
    const val API_CHARS = 7_000

    /** The last errors of the run, and how long one may be */
    const val ERRORS_KEPT = 10
    const val ERROR_CHARS = 300

    /** A description is cut to its first clause, and to this many characters at most */
    private const val DESCRIPTION_CHARS = 90

    /** A function shows this many of its parameters; the rest are "…" */
    private const val PARAMS_SHOWN = 5

    /** A default longer than this shows as "…": the name says enough of what it is for */
    private const val DEFAULT_CHARS = 13

    /** How long an entry's line may be: a long signature leaves its description less room, though never under [DESCRIPTION_LEAST] */
    private const val LINE_CHARS = 200
    private const val DESCRIPTION_LEAST = 40

    /** What a model from initMod or sessionMod answers to, in one line: those entries have no line of their own */
    private const val MODEL_LINE = "A model from initMod(\"name\") or sessionMod(\"name\"): predict(text) the label, scores(text) every label with its score, run(text) the raw answers, answer(text) the first one, close()"

    /**
     * The system prompt: what the model is for, the script API, the env, main.kts with its lines numbered and the last
     * error asked about. The one place the prompt is made, so a test can read what the model would be told.
     */
    fun system(context: ScriptContext, api: List<SandboxApi.Entry> = SandboxApi.entries): String = buildString {
        appendLine("You help write main.kts, a Kotlin script that runs in the Amie pilot sandbox. Answer briefly and plainly. " +
            "Put code in one ```kotlin block and use only plain Kotlin and the script API below. Call a line of the script \"Line N\". " +
            "main.kts runs from top to bottom: it needs no fun main and no import for the script API.")
        appendLine()
        appendLine("Script API, in scope without a prefix (amie.name works too):")
        apiLines(api, context.script).forEach(::appendLine)
        appendLine(MODEL_LINE)
        appendLine()
        appendLine("Env '${context.envName}'")
        appendLine("Packages: ${context.envPackages.ifEmpty { listOf("none") }.joinToString()}")
        appendLine("Model groups: ${context.envGroups.ifEmpty { listOf("none") }.joinToString("; ")}")
        appendLine("Models initMod can open: " + listOf(
            "intent" to context.intentModels,
            "text" to context.textModels,
            "speech" to context.speechModels,
        ).filter { it.second.isNotEmpty() }.joinToString("; ") { (kind, names) -> "$kind ${names.joinToString()}" }.ifEmpty { "none" })
        appendLine()
        if (context.script.isBlank()) appendLine("main.kts is empty.")
        else {
            appendLine("main.kts, each line after its number and | (the numbers are not part of the code):")
            numbered(context.script).forEach(::appendLine)
        }
        appendLine()
        if (context.errors.isEmpty()) appendLine("There is no error.")
        else {
            appendLine("The error:")
            context.errors.takeLast(ERRORS_KEPT).forEach { appendLine(cut(it.trim(), ERROR_CHARS)) }
        }
    }.trimEnd()

    /**
     * What handleBy(message) asks about [message]: why it happened and how to fix it, briefly. Low temperature, as
     * an explanation should be the same every time; a fixed line at most, never a whole script
     */
    fun error(message: String): Question = errors(listOf(message))

    /**
     * The same for error lines, the error terminal's as handleBy() reads them: the last [ERRORS_KEPT], each cut at
     * [ERROR_CHARS], as the system prompt lists them. One line is asked about as the one error
     */
    fun errors(lines: List<String>): Question {
        val kept = lines.takeLast(ERRORS_KEPT).map { cut(it.trim(), ERROR_CHARS) }
        val one = kept.size <= 1
        return Question("handleBy",
            "Explain ${if (one) "this error" else "these error lines of the console"} in two or three short sentences: what is wrong and how to fix it. " +
                "If a line of main.kts is wrong, end with that line fixed in a ```kotlin block. Do not rewrite the whole script.\n" +
                (if (one) "The error: ${kept.firstOrNull().orEmpty()}" else "The errors:\n" + kept.joinToString("\n")),
            maxTokens = 256, temperature = 0.2)
    }

    /** The chat completions request a text model answers, as a script asks one: llama.answer("""{"messages": …}""") */
    fun request(system: String, question: Question): String = buildJsonObject {
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", system) }
            addJsonObject { put("role", "user"); put("content", question.text) }
        }
        put("temperature", question.temperature)
        put("max_tokens", question.maxTokens)
    }.toString()

    /** What a script reaches for most: a line of their own before the rest when the whole API doesn't fit */
    private val everyday = setOf(
        "log", "info", "error", "data", "initMod", "sessionMod", "source", "sourceFile", "modelBuilder", "hasModel", "input", "output",
        "shell", "environment", "dependency", "implementation", "template", "coroutineTemplate", "database", "group", "ortClosable", "serve",
    )

    /**
     * One line an entry of the API, the deprecated left out, in the API's order, within [API_CHARS]: the entries the
     * script names have theirs first, then the everyday ones, then the rest. Those left without a line are named in
     * the last one, so the model knows of every function.
     */
    fun apiLines(api: List<SandboxApi.Entry>, script: String = ""): List<String> {
        val lines = api.filter { it.deprecated == null && !it.member }.map { it to apiLine(it) }.distinctBy { it.second }
        val named = Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(script).map { it.value }.toSet()
        val (inScript, others) = lines.partition { it.first.name in named }
        val (common, rest) = others.partition { it.first.name in everyday }
        val full = (inScript + common + rest).toMutableList()
        fun more(): String {
            val left = lines.filter { it !in full }.map { it.first.name }.distinct().filter { name -> full.none { it.first.name == name } }
            return if (left.isEmpty()) "" else "More, by name: ${left.joinToString()}"
        }
        // The last in that order give way to their names until the whole fits
        while (full.isNotEmpty() && full.sumOf { it.second.length + 1 } + more().length + 1 > API_CHARS) full.removeAt(full.lastIndex)
        return lines.filter { it in full }.map { it.second } + listOfNotNull(more().takeIf { it.isNotEmpty() })
    }

    /** `name(param: Type, other = default): Returns - The first clause of what it does` */
    internal fun apiLine(entry: SandboxApi.Entry): String {
        val call = if (entry.target == null && entry.aliasOf == null) "${SandboxApi.RECEIVER}.${entry.name}" else entry.name
        val signature = when (entry.kind) {
            SandboxApi.Kind.PROPERTY -> "$call: ${short(entry.returns ?: "Any")}"
            SandboxApi.Kind.FUNCTION -> {
                val shown = entry.params.take(PARAMS_SHOWN).map { p ->
                    val default = p.default?.let(::short)
                    when {
                        default == null -> "${p.name}: ${short(p.type)}"
                        default.length <= DEFAULT_CHARS -> "${p.name} = $default"
                        else -> "${p.name} = …"
                    }
                } + listOfNotNull("…".takeIf { entry.params.size > PARAMS_SHOWN })
                call + shown.joinToString(", ", "(", ")") + (entry.returns?.let { ": ${short(it)}" } ?: "")
            }
        }
        val room = (LINE_CHARS - signature.length - 3).coerceIn(DESCRIPTION_LEAST, DESCRIPTION_CHARS)
        return "$signature - ${brief(entry.description, room)}"
    }

    /**
     * A description to its first clause, a colon, a semicolon or a full stop ending it, and cut short past [most]. A
     * clause too short to say anything ("In a template: its next answer…") runs on to the next end
     */
    private fun brief(description: String, most: Int): String {
        val ends = Regex("""[:;.] """).findAll(description).map { it.range.first }.filter { it >= CLAUSE_LEAST }
        val clause = ends.firstOrNull()?.let { description.substring(0, it) } ?: description.trimEnd('.')
        return cut(clause, most)
    }

    private const val CLAUSE_LEAST = 20

    /** [text] cut at a word before [most] characters, with … after; shorter, as it is */
    private fun cut(text: String, most: Int): String {
        if (text.length <= most) return text
        val at = text.lastIndexOf(' ', most - 1).takeIf { it > most / 2 } ?: (most - 1)
        return text.substring(0, at).trimEnd(',', ' ') + "…"
    }

    /** The script's lines after their numbers, "4| initMod(…)", as many as fit in [SCRIPT_CHARS] */
    private fun numbered(script: String): List<String> {
        val lines = script.trimEnd().lines()
        val out = ArrayList<String>()
        var used = 0
        for ((i, line) in lines.withIndex()) {
            val numbered = "${i + 1}| $line"
            if (used + numbered.length + 1 > SCRIPT_CHARS) {
                out += "… (${lines.size - i} more lines)"
                break
            }
            out += numbered
            used += numbered.length + 1
        }
        return out
    }

    /** `java.nio.file.Path` -> `Path`, as the editor's dropdown shows a type: a package is any segment that starts in lower case */
    private fun short(type: String): String = type.replace(Regex("""\b(?:[a-z_][A-Za-z0-9_]*\.)+"""), "")
}
