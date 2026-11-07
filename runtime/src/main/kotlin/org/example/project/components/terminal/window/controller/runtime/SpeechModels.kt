package org.example.project.components.terminal.window.controller.runtime

import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackageSession
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.components.terminal.window.controller.pluginEngine.PackageMetadata
import org.example.project.components.terminal.window.controller.modules.ModelJob
import org.example.project.components.terminal.window.controller.modules.ModelJobs
import org.example.project.components.terminal.window.controller.util.bytes
import java.io.File
import java.util.Properties
import org.example.project.amp.ModelFolders
import org.example.project.amp.ModelsMigration

/**
 * The speech models speech.jar runs, opened by name as any other model: initMod("whisper"), initMod("voice") or a
 * folder of its models such as initMod("whisper-base.en"). Each is a session of the jar of its own, so the block around
 * it holds it, free and Terminate end it, and the Sessions tab shows it under its name. A model has one session.
 */
object SpeechModels {
    private const val JAR = "speech.jar"
    private val open = HashMap<String, SpeechModel>()

    private val settings: Properties
        get() = Properties().apply {
            File(PackageMetadata.configDir(JAR), "speech.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
        }

    private fun setting(name: String): String? = settings.getProperty(name)?.trim()?.ifEmpty { null }

    // Where speech.properties says, else AMP/models/speech (ModelFolders); the jar reads the same setting, which the move keeps pointed at that folder
    internal val dir: File get() = setting("models")?.let(::File)?.takeIf { it.isDirectory } ?: ModelFolders.amp.speech

    fun names(): List<String> = dir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()

    /** The model [name] means: what speech.properties sets for whisper or voice, a folder, or the first starting so */
    fun find(name: String): String? {
        if (name.isBlank()) return null
        val all = names()
        val set = when (name) {
            "whisper" -> setting("whisper")
            "voice" -> setting("voice") ?: all.firstOrNull { !hears(it) }
            else -> null
        }
        return set?.takeIf { it in all } ?: all.firstOrNull { it == name } ?: all.firstOrNull { it.startsWith(name) }
    }

    fun has(name: String): Boolean = find(name) != null

    /** What a model weighs on disk */
    fun bytes(name: String): Long = File(dir, name).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    internal fun hears(model: String) = model.startsWith("whisper", ignoreCase = true)

    /** The session of the model and whether this call opened it */
    @Synchronized
    fun acquire(name: String): Pair<SpeechModel, Boolean> {
        val model = requireNotNull(find(name)) { "No speech model $name; ${names()} are there" }
        open[model]?.takeUnless { it.session.closed }?.let { return it to false }
        // The jar reads its models where speech.properties says: after the move into AMP/models that is set here
        ModelsMigration.settleSpeechSetting()
        val session = JarPackages.open(JAR)
        session.speechModel = model
        val said = runCatching { session.inference("use $model").firstOrNull() }.getOrElse { it.message }
        if (said?.startsWith("Using") != true) {
            session.close()
            error("$JAR can't use $model ($said): it needs a newer $JAR")
        }
        return SpeechModel(model, session).also { open[model] = it } to true
    }
}

/** Whisper, whose answer to a wav file is what was said in it, or a voice, whose answer to a text is the file of it spoken */
class SpeechModel internal constructor(val name: String, internal val session: JarPackageSession) : InteractionModel {
    private val hears = SpeechModels.hears(name)

    override val abilities = setOf("run", if (hears) "hear" else "speak")

    override fun inference(text: String): List<String> {
        check(!session.closed) { "speech model $name is closed" }
        return session.inference((if (hears) "hear " else "say ") + text)
    }

    override fun getStatus(): Any = session.getStatus()

    // The session is what the block holds and Terminate ends
    override fun inBackground(text: String): ModelJob = ModelJobs.start(this, text, name, session)

    override fun close() = session.close()
}
