package org.example.project.components.terminal.window.controller.sandbox

import org.example.project.components.terminal.window.controller.console.MemoryWatch
import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.modules.OrtScopes
import org.example.project.components.terminal.window.controller.modules.hasModule
import org.example.project.components.terminal.window.controller.modules.modulesDir
import org.example.project.components.terminal.window.controller.modules.readRegistry
import org.example.project.components.terminal.window.controller.runtime.ModelSession
import org.example.project.components.terminal.window.controller.runtime.ModelSessions
import org.example.project.components.terminal.window.controller.runtime.SpeechModels
import org.example.project.components.terminal.window.controller.runtime.TextModels
import org.example.project.components.terminal.window.controller.script.FreeMemory
import org.example.project.components.terminal.window.controller.script.TemplateRuns
import org.example.project.components.terminal.window.controller.util.bytes
import org.example.project.getDirectory
import org.example.project.settings.AppSettings
import java.io.File

/**
 * What answers handleBy(message). The app's is [LocalTextModel], a text model of AMP/models/text opened on the first
 * question; a test hands in one of its own ([ErrorHelp.answerer]), so no model is loaded there.
 */
internal interface ScriptAnswerer {
    /** The text models it can answer with, by name */
    fun models(): List<String>

    /** Whether [model] is open now, so handleBy says it is opening it only when it is */
    fun isOpen(model: String): Boolean

    /** Opens [model], letting go of another one it opened; throws [NotOpened], with the reason, when it can't */
    fun open(model: String)

    /** What [model], open, answers to [question] with [system] as its system prompt */
    fun answer(model: String, system: String, question: Question): String

    /** Lets go of the model: closed when this opened it, left open for the script that opened it otherwise */
    fun close()
}

/** Why a text model was not opened, in words the console shows as they are */
internal class NotOpened(message: String) : Exception(message)

/**
 * handleBy's model: one of AMP/models/text, opened as initMod opens it ([ModelSessions.acquire]), so it is listed
 * under Still open, counted in a run's memory and ended by Terminate. What it opens it keeps in the timed scope
 * [SCOPE], a timed one, which scripts no longer have: freed once nothing has asked it for [IDLE_SECONDS], freed first when memory runs short
 * (MemoryWatch). A session a script had open already is used and left to it.
 */
internal object LocalTextModel : ScriptAnswerer {
    /** The scope that holds what handleBy opened: Still open names it on the model's row, and Free there closes it */
    const val SCOPE = "handleBy"

    /** How long the model stays open with nothing asked of it */
    const val IDLE_SECONDS = 300

    private val lock = Any()
    @Volatile private var session: ModelSession? = null
    // Whether handleBy opened the session, and so closes it
    @Volatile private var opened = false

    override fun models(): List<String> = TextModels.names()

    override fun isOpen(model: String): Boolean = session?.let { it.name == model && !it.closed } == true

    override fun open(model: String) {
        synchronized(lock) {
            if (isOpen(model)) return
            release()
            if (!TextModels.has(model)) throw NotOpened("There is no text model '$model' in AMP/models/text")
            memoryShort(model, weightBytes(TextModels.folder(model)), TemplateRuns.freeMemory)?.let { throw NotOpened(it) }
            OrtScopes.timed(SCOPE, IDLE_SECONDS) {
                val (open, new) = ModelSessions.acquire(model)
                if (new) OrtScopes.opened(open, true)
                session = open
                opened = new
            }
        }
    }

    override fun answer(model: String, system: String, question: Question): String {
        open(model)
        val open = session ?: throw NotOpened("The model $model was closed")
        return open.answer(AssistantPrompt.request(system, question)).orEmpty()
    }

    override fun close() {
        synchronized(lock) { release() }
    }

    // The scope frees what it holds, with its console line; a session the idle timer or Terminate closed already is let go
    private fun release() {
        val held = session ?: return
        session = null
        if (opened && !held.closed) OrtScopes.free(SCOPE)
        opened = false
    }
}

/** About what a text model's weights take in RAM: its ONNX files and their external data */
internal fun weightBytes(dir: File): Long =
    dir.listFiles().orEmpty().filter { it.isFile && (it.name.endsWith(".onnx") || it.name.endsWith(".data")) }.sumOf { it.length() }

/**
 * What is short to open [model], whose weights take [needed] bytes, as the line that says so; null when it fits. The
 * weights load outside the heap, where ONNX Runtime keeps them, so they count against the machine's RAM, and what is
 * left must stay over what MemoryWatch keeps free, or the watch would close the oldest idle session at once. [free]
 * reads as TemplateRuns reads for a run: a cheap reading first, then a settled one before refusing.
 */
internal fun memoryShort(model: String, needed: Long, free: (settled: Boolean) -> FreeMemory): String? {
    fun short(reading: FreeMemory): String? {
        val machine = reading.machineBytes ?: return null
        return if (machine >= needed + MemoryWatch.ramLimitBytes) null
        else "Not opened: ${bytes(machine)} of RAM is free and $model needs about ${bytes(needed)}. Close a session or another program, or pick a smaller model"
    }
    return short(free(false))?.let { short(free(true)) }
}

/**
 * The env the script declares and what it gives: its packages, its model groups and the models initMod opens. Read
 * from AMP's folders, so off the UI thread; [errors] is what handleBy was asked about.
 */
internal fun readScriptContext(script: String, errors: List<String>): ScriptContext {
    val envName = EnvPlan.scan(script).envName
    val env = runCatching { ScriptEnvironments.peek(envName) }.getOrNull()
    val intents = runCatching {
        (readRegistry().modules.keys + modulesDir.listFiles().orEmpty().filter { File(it, "module.json").isFile }.map { it.name })
            .distinct().filter(::hasModule).sorted()
    }.getOrDefault(emptyList())
    return ScriptContext(
        script = script,
        envName = envName,
        envPackages = env?.let { runCatching { it.declared }.getOrNull() }.orEmpty(),
        envGroups = env?.let { runCatching { it.groups() }.getOrNull() }.orEmpty().map { g -> "${g.name}: ${g.models.ifEmpty { listOf("no models") }.joinToString()}" },
        intentModels = intents,
        textModels = runCatching { TextModels.names() }.getOrDefault(emptyList()),
        speechModels = runCatching { SpeechModels.names() }.getOrDefault(emptyList()),
        errors = errors,
    )
}

/**
 * What handleBy(message) asks: [answerer], the local text model, with main.kts and the env around the error in its
 * system prompt. It only answers; nothing of the script is changed.
 */
internal object ErrorHelp {
    /** Who answers: the local text model; a test hands in its own */
    @Volatile internal var answerer: ScriptAnswerer = LocalTextModel

    /** The text model to ask: the one settings.json names (assistantModel) when it is installed, else the first of AMP/models/text */
    fun model(): String? {
        val names = runCatching { answerer.models() }.getOrDefault(emptyList())
        return AppSettings.current.value.assistantModel.takeIf { it in names } ?: names.firstOrNull()
    }

    /** Whether [model] is open already, so the console says it is opening it only when it is */
    fun isOpen(model: String): Boolean = answerer.isOpen(model)

    /** What [model] answers about [errors], the error terminal's lines or the one message asked about, trimmed; throws [NotOpened] when it can't be opened */
    fun ask(model: String, errors: List<String>, script: String = mainScript()): String {
        val system = AssistantPrompt.system(readScriptContext(script, errors))
        return answerer.answer(model, system, AssistantPrompt.errors(errors)).trim()
    }

    /** What [error] is, as handleBy asks about it: its type and message, and the line of the script it was thrown at when its trace has one */
    fun describe(error: Throwable): String {
        val type = error::class.simpleName ?: error.javaClass.name
        val at = error.stackTrace.firstOrNull { it.fileName?.endsWith(".kts") == true }?.let { " (at ${it.fileName}:${it.lineNumber})" }.orEmpty()
        return (error.message?.takeIf { it.isNotBlank() }?.let { "$type: $it" } ?: type) + at
    }

    private fun mainScript(): String = runCatching { File(getDirectory().toString(), "pluginDir/main.kts").readText() }.getOrDefault("")
}
