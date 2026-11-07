package org.example.project.components.terminal.window.controller.script

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.envBuilder.DEFAULT_ENV
import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan
import org.example.project.getDirectory
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.sandbox.SandboxExamples
import java.io.File
import java.time.Instant

/**
 * A script kept to start from again: its code is `AMP/templates/<id>.kts`, what is known of it `AMP/templates/<id>.json`.
 * [uses] is read from the code when it is saved: the models, groups and package jars it opens, so the list can say
 * what a template needs before it is loaded.
 */
@Serializable
data class ScriptTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    /** Where it was saved from: "main.kts", a previous script (the backup's file name), or "starter" for one the app ships */
    val source: String = "main.kts",
    /** The env the script sources itself in, when it names one */
    val env: String? = null,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = createdAt,
    val lines: Int = 0,
    val uses: List<String> = emptyList(),
    /** How often it was loaded into the editor, and when last */
    val loads: Int = 0,
    val lastLoadedAt: String? = null,
)

/**
 * The script templates of AMP/templates: the sandbox's Templates panel saves the script in the editor (or a previous
 * one from AMP/pluginDir/backup) under a name with a description and tags, lists them newest first and loads one into
 * the editor. The first time the folder is made it is seeded with the starter scripts (what the Examples menu used to
 * offer) and with the script main.kts holds then; a starter deleted later doesn't come back. A starter the app gains
 * later comes once to a folder seeded before it.
 */
object ScriptTemplates {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val idChars = Regex("[^a-z0-9]+")
    private val opens = Regex("""\b(initMod|sessionMod|sharedModel|group)\(\s*"([^"]+)"\s*\)""")

    fun dir(): File = File(getDirectory().toString(), "templates")

    private fun codeFile(id: String) = File(dir(), "$id.kts")
    private fun metaFile(id: String) = File(dir(), "$id.json")

    /** Every template that has both its files, the last changed first */
    @Synchronized
    fun list(): List<ScriptTemplate> =
        dir().listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString(ScriptTemplate.serializer(), f.readText()) }.getOrNull() }
            .filter { codeFile(it.id).isFile }
            .sortedWith(compareByDescending<ScriptTemplate> { it.updatedAt }.thenBy { it.name })

    fun find(id: String): ScriptTemplate? = list().firstOrNull { it.id == id }

    fun code(id: String): String? = codeFile(id).takeIf { it.isFile }?.readText()

    /** What a script opens: "model x", "group x" and "package x.jar", in the order it first does; comments don't count */
    fun uses(code: String): List<String> = opens.findAll(code.lineSequence().map { it.substringBefore("//") }.joinToString("\n")).map { m ->
        val (call, name) = m.destructured
        when {
            call == "group" -> "group $name"
            name.endsWith(".jar", ignoreCase = true) -> "package $name"
            else -> "model $name"
        }
    }.distinct().toList()

    /**
     * Keeps [code] as the template [name]: a new one, or the one of that name made again (its id, creation time and
     * loads are kept). Blank names and blank scripts are refused.
     */
    @Synchronized
    fun save(name: String, code: String, description: String = "", tags: List<String> = emptyList(), source: String = "main.kts"): ScriptTemplate {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A template needs a name" }
        require(code.isNotBlank()) { "The script is empty; there is nothing to keep" }
        return write(list().firstOrNull { it.name.equals(clean, ignoreCase = true) }, clean, code, description, tags, source)
    }

    // [code] as the template [clean], over [existing] when there is one (its id, creation time and loads kept, whatever its name was)
    private fun write(existing: ScriptTemplate?, clean: String, code: String, description: String, tags: List<String>, source: String): ScriptTemplate {
        val now = Instant.now().toString()
        val template = ScriptTemplate(
            id = existing?.id ?: freeId(clean),
            name = clean,
            description = description.trim(),
            tags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
            source = source,
            env = EnvPlan.scan(code).envName.takeIf { it != DEFAULT_ENV },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lines = code.trimEnd().lines().size,
            uses = uses(code),
            loads = existing?.loads ?: 0,
            lastLoadedAt = existing?.lastLoadedAt,
        )
        dir().mkdirs()
        codeFile(template.id).writeText(code)
        metaFile(template.id).writeText(json.encodeToString(ScriptTemplate.serializer(), template))
        val how = when {
            existing == null -> "saved"
            existing.name != template.name -> "was '${existing.name}', saved again"
            else -> "saved again"
        }
        logInternal("templates: '${template.name}' $how as ${codeFile(template.id).path} (${template.lines} lines${if (template.uses.isEmpty()) "" else ", uses ${template.uses.joinToString()}"})")
        return template
    }

    /** A new description and tags for a template, its code untouched */
    @Synchronized
    fun describe(id: String, description: String, tags: List<String>): ScriptTemplate? {
        val template = find(id) ?: return null
        val next = template.copy(description = description.trim(), tags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(), updatedAt = Instant.now().toString())
        metaFile(id).writeText(json.encodeToString(ScriptTemplate.serializer(), next))
        return next
    }

    /** The template's code, counted as loaded once more */
    @Synchronized
    fun load(id: String): String? {
        val template = find(id) ?: return null
        val code = code(id) ?: return null
        metaFile(id).writeText(json.encodeToString(ScriptTemplate.serializer(), template.copy(loads = template.loads + 1, lastLoadedAt = Instant.now().toString())))
        return code
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val gone = codeFile(id).delete() or metaFile(id).delete()
        if (gone) logInternal("templates: '$id' deleted")
        return gone
    }

    /** The scripts the editor replaced, kept in AMP/pluginDir/backup when a template was loaded over them, newest first */
    fun previousScripts(): List<File> =
        File(getDirectory().toString(), "pluginDir/backup").listFiles { f -> f.isFile && f.extension == "kts" }.orEmpty()
            .sortedByDescending { it.lastModified() }

    /**
     * Fills the folder the first time there is none: the starter scripts and [current], the script main.kts holds,
     * when it is one of its own (a .seeded file says that was done). After that only the starters the app has gained
     * since come, once (.seeded-level says how far it got), so a starter deleted stays deleted. A starter that gained a
     * new title comes as a new seeding too: the template under its old title, when it is still the starter (source
     * "starter", never saved over), is renamed and brought up to date under its own id, so the folder doesn't hold the
     * old one and a new one both. A starter whose code changed names its own title as a former one, and is brought up to
     * date the same way. One the user saved over is theirs and is left alone; the starter comes beside it.
     */
    @Synchronized
    fun seed(current: String?) {
        val marker = File(dir(), ".seeded")
        val level = File(dir(), ".seeded-level")
        val first = !marker.exists()
        // A folder with no level was seeded before there were levels, with the starters of the first one
        val reached = if (first) 0 else runCatching { level.readText().trim().toInt() }.getOrDefault(1)
        val starters = SandboxExamples.all
        val latest = starters.maxOf { it.since }
        if (reached >= latest) return
        dir().mkdirs()
        if (first && current != null && current.isNotBlank() && starters.none { it.code.trim() == current.trim() } && list().none { code(it.id)?.trim() == current.trim() })
            runCatching { save("My script", current, "main.kts as it was when the templates began", listOf("mine"), "main.kts") }
        starters.filter { it.since > reached }.forEach { ex ->
            val have = list()
            val renamed = ex.formerly.firstNotNullOfOrNull { old -> have.firstOrNull { it.name.equals(old, ignoreCase = true) && it.source == "starter" } }
            if (have.any { it.name.equals(ex.title, ignoreCase = true) && it.id != renamed?.id }) return@forEach
            runCatching { write(renamed, ex.title, ex.code, describeStarter(ex.code), listOf(ex.group.lowercase(), "starter"), "starter") }
        }
        if (first) marker.writeText(Instant.now().toString())
        level.writeText(latest.toString())
    }

    // A starter's first comment lines, which say what it does, as its description
    private fun describeStarter(code: String): String =
        code.lineSequence().map { it.trim() }.takeWhile { it.startsWith("//") }.map { it.removePrefix("//").trim() }
            .joinToString(" ").take(240)

    private fun freeId(name: String): String {
        val base = name.lowercase().replace(idChars, "-").trim('-').ifEmpty { "template" }.take(48)
        var id = base
        var n = 2
        while (codeFile(id).exists() || metaFile(id).exists()) id = "$base-${n++}"
        return id
    }
}
