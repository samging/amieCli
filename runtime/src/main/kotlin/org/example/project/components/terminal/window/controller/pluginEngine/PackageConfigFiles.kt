package org.example.project.components.terminal.window.controller.pluginEngine

import java.io.File
import java.io.StringReader
import java.util.Properties

/**
 * The config files of a package jar in AMP/pluginDir/config/<jar>/, read and written key by key, so `amie packages
 * config` and `amie packages setup` change them without an editor. A .properties file keeps its comments, its order
 * and every line that is not set; any other file, such as a credentials JSON, is copied in whole.
 */
object PackageConfigFiles {
    /** One `key=value` line of a .properties file, with the comment lines right above it */
    data class Entry(val key: String, val value: String, val hint: List<String>)

    /** A config file the jar declares, and where it is */
    data class Declared(val config: PackageConfig, val file: File) {
        val properties: Boolean get() = isProperties(file)
        /** Still its template, as the jar wrote it: there to fill in */
        val template: Boolean get() = PackageMetadata.unfilled(file, config)
        /** The keys of a .properties file that have no value */
        val empty: List<String> get() = if (properties) entries(file).filter { it.value.isBlank() }.map { it.key } else emptyList()
    }

    private val keyLine = Regex("""^\s*([A-Za-z0-9_.\-]+)\s*[=:]\s?(.*)$""")
    private val secretKey = Regex("""(?i)(secret|token|password|passwd|apikey|api_key|privatekey|private_key)""")

    fun isProperties(file: File): Boolean = file.extension.equals("properties", ignoreCase = true)

    /** A key whose value is not shown: a secret, a token, a password, an API key */
    fun isSecret(key: String): Boolean = secretKey.containsMatchIn(key)

    /** Every config file the jar declares, its template written first when it is missing */
    fun declared(jarName: String, meta: PackageMeta?): List<Declared> {
        PackageMetadata.prepareConfig(jarName, meta)
        val dir = PackageMetadata.configDir(jarName)
        return meta?.config.orEmpty().map { Declared(it, File(dir, it.file)) }
    }

    /** The key lines of a .properties file in their order; none for a file that is not there */
    fun entries(file: File): List<Entry> {
        if (!file.isFile) return emptyList()
        val hint = mutableListOf<String>()
        val out = mutableListOf<Entry>()
        file.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> hint.clear()
                line.startsWith("#") || line.startsWith("!") -> hint += line.trimStart('#', '!').trim()
                else -> {
                    keyLine.find(raw)?.let { m -> out += Entry(m.groupValues[1], unescape(m.groupValues[2].trim()), hint.filter { it.isNotEmpty() }) }
                    hint.clear()
                }
            }
        }
        return out
    }

    /**
     * Sets [values] in the .properties [file], each on its key's line, every other line kept. A key the file has no
     * line for is added at the end when [add] is true, else left out and returned. A file that gets a secret is made
     * readable by its owner only.
     */
    fun set(file: File, values: Map<String, String>, add: Boolean = false): List<String> {
        require(isProperties(file)) { "${file.name} is not a .properties file; copy one in with --from" }
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        val left = LinkedHashMap(values)
        lines.indices.forEach { i ->
            if (lines[i].trimStart().startsWith("#")) return@forEach
            val key = keyLine.find(lines[i])?.groupValues?.get(1) ?: return@forEach
            left.remove(key)?.let { v -> lines[i] = "$key=${escape(v)}" }
        }
        val missing = if (add) emptyList() else left.keys.toList()
        if (add) left.forEach { (k, v) -> lines += "$k=${escape(v)}" }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n") + "\n")
        if (values.keys.any(::isSecret)) ownerOnly(file)
        return missing
    }

    /** Copies [from] over the config file [to], readable by its owner only, as credentials are */
    fun copyIn(from: File, to: File) {
        require(from.isFile) { "No file ${from.path}" }
        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = true)
        ownerOnly(to)
    }

    /**
     * The files of that name in the other config folders of AMP/pluginDir/config, the shared ones such as `google`
     * included: where values for [fileName] may already be filled in
     */
    fun others(jarName: String, fileName: String): List<File> {
        val own = PackageMetadata.configDir(jarName)
        return File(PackageMetadata.pluginDir(), "config").listFiles { f -> f.isDirectory && f != own }.orEmpty()
            .sortedBy { it.name }.map { File(it, fileName) }.filter { it.isFile }
    }

    private fun ownerOnly(file: File) {
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
    }

    private fun unescape(raw: String): String =
        runCatching { Properties().apply { load(StringReader("k=$raw")) }.getProperty("k") }.getOrNull() ?: raw

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\r", "").replace("\n", " ")
}
