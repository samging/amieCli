package org.example.project.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.backend.AmieBackend
import org.example.project.backend.BackendException
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.util.localTime
import org.example.project.getDirectory
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One file of a backup: where it lives under AMP and what it holds */
@Serializable
data class BackupFile(val path: String, val text: String)

/** What the backend keeps as the saved state [AccountBackup.STATE] */
@Serializable
data class BackupDocument(val version: Int = AccountBackup.VERSION, val savedAt: String, val files: List<BackupFile>)

/** What a restore did: the paths it wrote and, of those it replaced, the copy kept of each */
data class RestoreResult(val savedAt: String, val written: List<String>, val kept: Map<String, File>) {
    /** What the page says afterwards: how many came back and, file by file, what was replaced and where its copy is */
    fun summary(): String {
        if (written.isEmpty()) return "Nothing to restore: your files already match the backup of ${localTime(savedAt, seconds = false)}."
        val restored = "Restored ${written.size} file${if (written.size == 1) "" else "s"} from the backup of ${localTime(savedAt, seconds = false)}."
        val replaced = if (kept.isEmpty()) "" else " Replaced, with a copy under AMP/pluginDir/backup: " + kept.entries.joinToString { (path, copy) -> "$path as ${copy.name}" } + "."
        return "$restored$replaced Templates the backup doesn't have stay as they were."
    }
}

/** A backup or restore that did not happen, with the reason to show */
class BackupException(message: String) : Exception(message)

/**
 * The person's own work on the Amie backend, under their account: the scripts of AMP/templates, AMP/settings.json and
 * AMP/pluginDir/main.kts, kept as the one saved state [STATE], which each backup replaces.
 *
 * [allowed] is a strict allowlist, used both ways: a file that isn't on it is never sent, however it got into the
 * folder, and a document that names one writes nothing. session.json, keycloak.json, databases.json and github.json
 * hold secrets; models, logs and everything else stay on this machine.
 *
 * A restore adds and replaces and never deletes, so a template the backup doesn't have stays. What it replaces is
 * kept under AMP/pluginDir/backup first, as the editor keeps the scripts it replaces, so a script shows among
 * Previous scripts in the sandbox's Templates pane.
 */
object AccountBackup {
    const val STATE = "amp-backup"
    const val VERSION = 1

    private const val SETTINGS = "settings.json"
    private const val MAIN_SCRIPT = "pluginDir/main.kts"
    private const val TEMPLATES = "templates"

    // A template's script or what is known of it, directly in the folder and never a dotfile
    private val template = Regex("""templates/[^/\\.][^/\\]*\.(kts|json)""")
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun amp() = File(getDirectory().toString())

    /** Whether a backup may hold [path], relative to AMP: the only question that decides what is sent or written */
    fun allowed(path: String): Boolean = path == SETTINGS || path == MAIN_SCRIPT || template.matches(path)

    /** [path] under [root] when nothing on the way there is a link: a link could lead anywhere, secrets included */
    internal fun inside(root: File, path: String): File? =
        File(root, path).takeIf { file -> runCatching { file.canonicalFile == File(root.canonicalFile, path) }.getOrDefault(false) }

    /**
     * What a backup of [root] holds right now. Of the json files in AMP/templates only those beside a script of the
     * same name go along: that is what a template is (the sandbox lists one only with both its files).
     */
    fun collect(root: File = amp()): BackupDocument {
        val files = paths(root).mapNotNull { path -> inside(root, path)?.takeIf { it.isFile }?.let { BackupFile(path, text(it, path)) } }
        return BackupDocument(savedAt = Instant.now().toString(), files = files)
    }

    /** The paths under [root] a backup or the cloud may hold, whether the file is there or not: settings, main.kts and each script of AMP/templates with its json */
    internal fun paths(root: File): List<String> {
        val scripts = File(root, TEMPLATES).listFiles().orEmpty().filter { it.extension == "kts" }
        return (listOf(SETTINGS, MAIN_SCRIPT) + scripts.flatMap { listOf("$TEMPLATES/${it.name}", "$TEMPLATES/${it.nameWithoutExtension}.json") }.sorted()).filter(::allowed)
    }

    // A file that isn't UTF-8 text would come back changed: better no backup than one that looks whole and isn't
    internal fun text(file: File, path: String): String {
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) throw BackupException("$path isn't plain text, so a backup couldn't bring it back as it is. Nothing was sent")
        return text
    }

    /** The document as the JSON text the backend takes; too large for it is refused whole, never cut down to fit */
    internal fun encode(document: BackupDocument): String {
        val body = json.encodeToString(document)
        val size = body.toByteArray().size
        if (size > AmieBackend.MAX_STATE_BYTES) {
            val largest = document.files.sortedByDescending { it.text.toByteArray().size }.take(3)
                .joinToString { "${it.path} (${AmpFiles.size(it.text.toByteArray().size.toLong())})" }
            throw BackupException("The backup is ${AmpFiles.size(size.toLong())} and the backend keeps at most ${AmpFiles.size(AmieBackend.MAX_STATE_BYTES.toLong())}, so nothing was sent. The largest files: $largest")
        }
        return body
    }

    /** Sends a backup of [root] to the backend, in place of the last one, and returns what it held */
    suspend fun backUp(root: File = amp()): BackupDocument {
        val document = collect(root)
        if (document.files.isEmpty()) throw BackupException("There is nothing to back up yet: no script in AMP/templates, no AMP/settings.json and no AMP/pluginDir/main.kts")
        val body = encode(document)
        try {
            AmieBackend.saveState(STATE, body)
        } catch (e: BackendException) {
            // Measured as the backend measures it, so this is a backend or a proxy set lower than it ships
            if (e.status == 413) throw BackupException("The backend refused the backup of ${AmpFiles.size(body.toByteArray().size.toLong())} as too large. The last backup there is unchanged")
            throw e
        }
        logInternal("backup: ${document.files.size} files stored on the backend as the state '$STATE'")
        return document
    }

    /** The backup on the backend; null when there is none yet */
    suspend fun latest(): BackupDocument? = AmieBackend.loadState(STATE)?.let { text ->
        runCatching { json.decodeFromString<BackupDocument>(text) }.getOrElse { throw BackupException("The backup on the backend can't be read: ${it.message}") }
    }

    /** Writes the backup on the backend back into [root]; the settings in it apply at once */
    suspend fun restore(root: File = amp()): RestoreResult {
        val document = latest() ?: throw BackupException("There is no backup on the backend yet")
        val result = write(document, root)
        if (SETTINGS in result.written) AppSettings.reload()
        logInternal("backup: restored ${result.written.size} files of the backup of ${document.savedAt}; ${result.kept.size} replaced, kept under pluginDir/backup")
        return result
    }

    /** Puts [document]'s files under [root], every path checked first, so a document with a stranger in it writes nothing */
    internal fun write(document: BackupDocument, root: File): RestoreResult {
        if (document.version != VERSION) throw BackupException("This backup has format ${document.version} and this app reads format $VERSION. Nothing was written")
        val refused = document.files.map { it.path }.filter { !allowed(it) || inside(root, it) == null }
        if (refused.isNotEmpty()) throw BackupException("The backup names files a restore never writes (${refused.joinToString()}). Nothing was written")
        val stamp = stampFormat.format(LocalDateTime.now())
        val written = mutableListOf<String>()
        val kept = linkedMapOf<String, File>()
        try {
            document.files.forEach { file ->
                val target = File(root, file.path)
                val bytes = file.text.toByteArray()
                if (target.isFile && target.readBytes().contentEquals(bytes)) return@forEach
                // The copy first: when it can't be made, the file stays as it is
                if (target.isFile) kept[file.path] = keepCopy(target, File(root, "pluginDir/backup"), stamp)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                written += file.path
            }
        } catch (e: IOException) {
            throw BackupException("The restore stopped: ${e.message}. Written before that: ${written.joinToString().ifEmpty { "nothing" }}. What those replaced is under AMP/pluginDir/backup")
        }
        return RestoreResult(document.savedAt, written, kept)
    }

    /** A copy of [file] in [dir], named as the editor names a script it replaces: main-20261031-101500.kts */
    private fun keepCopy(file: File, dir: File, stamp: String): File {
        dir.mkdirs()
        val name = "${file.nameWithoutExtension}-$stamp"
        // Two files of one restore can share a name: templates/main.kts and pluginDir/main.kts
        val copy = generateSequence(1) { it + 1 }.map { n -> File(dir, (if (n == 1) name else "$name-$n") + ".${file.extension}") }.first { !it.exists() }
        return file.copyTo(copy)
    }
}
