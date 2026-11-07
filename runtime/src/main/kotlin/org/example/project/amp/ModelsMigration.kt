package org.example.project.amp

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.pluginEngine.PackageMetadata
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Moves the models of an AMP folder into `AMP/models/` ([ModelFolders]), once, before anything reads them. For each
 * [ModelFolders.Kind]:
 *
 * - the old folder is there and the new one is not: the old one is renamed into place, atomically, on the same volume,
 *   so nothing is copied and no gigabyte is doubled; a symbolic link moves as the link it is;
 * - both are there: nothing moves. The app reads the new one, and the console says which folder to tidy;
 * - only the new one, or neither: nothing to do.
 *
 * Nothing is ever deleted or overwritten (an empty new folder, left by an earlier start that got no further, is the
 * one thing removed, so the old one can take its place). Every move is logged, and a move that fails (a read-only
 * folder, another volume) is logged too; the kind is then read where it is, as [ModelFolders.of] falls back to the old
 * folder for one release. The speech models are read by speech.jar where `speech.properties` says, so after their
 * move its `models=` names the new folder ([settleSpeechSetting]).
 */
object ModelsMigration {
    private val ran = HashSet<String>()

    /** The move of an AMP folder (the app's own by default) the first time it is asked for that folder; later calls do nothing */
    fun runOnce(folders: ModelFolders = ModelFolders.amp) {
        synchronized(ran) { if (!ran.add(folders.amp.absolutePath)) return }
        run(folders)
    }

    /** The move of [folders]'s AMP, every time it is called; [say] gets each line that is said about it */
    fun run(folders: ModelFolders, say: (String, LogLevel) -> Unit = { message, level -> logInternal(message, level) }): List<String> {
        val said = mutableListOf<String>()
        fun tell(message: String, level: LogLevel = LogLevel.INTERNAL) { said += message; say(message, level) }
        var speechThere = false
        for (kind in ModelFolders.Kind.entries) {
            val old = folders.oldOf(kind)
            val new = folders.newOf(kind)
            val oldThere = there(old)
            var newThere = there(new)
            if (newThere && oldThere && !Files.isSymbolicLink(new.toPath()) && new.list()?.isEmpty() == true) {
                // An empty folder an earlier start made and never filled: out of the way, so the old one takes its place
                newThere = !runCatching { Files.delete(new.toPath()) }.isSuccess
            }
            when {
                oldThere && newThere -> tell(
                    "models: the ${kind.label} are in both $old and $new; the app reads $new. Move what you still need from $old into it, then delete $old",
                    LogLevel.INFO,
                )
                oldThere -> newThere = move(kind, old, new, ::tell)
            }
            if (kind == ModelFolders.Kind.SPEECH) speechThere = newThere
        }
        if (speechThere) settleSpeechSetting(folders, ::tell)
        return said
    }

    private fun there(folder: File): Boolean {
        val path = folder.toPath()
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) && (folder.isDirectory || Files.isSymbolicLink(path))
    }

    // True when the old folder is now the new one
    private fun move(kind: ModelFolders.Kind, old: File, new: File, tell: (String, LogLevel) -> Unit): Boolean = try {
        Files.createDirectories(new.parentFile.toPath())
        Files.move(old.toPath(), new.toPath(), StandardCopyOption.ATOMIC_MOVE)
        tell("models: moved the ${kind.label} from $old to $new", LogLevel.INTERNAL)
        true
    } catch (e: Exception) {
        tell("models: couldn't move the ${kind.label} from $old to $new (${e.message ?: e::class.simpleName}); they are read where they are", LogLevel.INFO)
        false
    }

    private const val SPEECH_JAR = "speech.jar"

    /**
     * Points speech.jar at the speech models' folder: `models=` in `pluginDir/config/speech/speech.properties` names
     * `AMP/models/speech` when that folder is there and the setting is empty or names a folder that is not. A setting
     * that names a folder that is there is left alone, as are the other lines of the file. When the file is not there
     * yet and the jar is, the jar's template is written first, so the comments stay. True when the file was changed.
     */
    fun settleSpeechSetting(folders: ModelFolders = ModelFolders.amp, say: (String, LogLevel) -> Unit = { message, level -> logInternal(message, level) }): Boolean {
        val new = folders.newOf(ModelFolders.Kind.SPEECH)
        if (!new.isDirectory) return false
        val file = File(folders.amp, "pluginDir/config/speech/speech.properties")
        if (!file.isFile) {
            val jar = File(folders.amp, "pluginDir/$SPEECH_JAR")
            if (!jar.isFile) return false
            PackageMetadata.prepareConfig(SPEECH_JAR, PackageMetadata.read(jar))
            if (!file.isFile) return false
        }
        val lines = file.readLines()
        val setting = lines.firstOrNull { isModelsLine(it) }
        val current = setting?.substringAfter('=')?.trim().orEmpty()
        if (current.isNotEmpty() && File(current).isDirectory) return false
        val line = "models=" + new.absolutePath.replace('\\', '/')
        val written = if (setting != null) lines.map { if (isModelsLine(it)) line else it } else lines + line
        return runCatching { file.writeText(written.joinToString("\n", postfix = "\n")) }
            .onSuccess { say("models: speech.properties reads the speech models from $new", LogLevel.INTERNAL) }
            .onFailure { say("models: couldn't write $file (${it.message}); set models=$new in it, in the Packages tab", LogLevel.INFO) }
            .isSuccess
    }

    private fun isModelsLine(line: String) = line.trimStart().let { it.startsWith("models=") || it.startsWith("models =") }
}
