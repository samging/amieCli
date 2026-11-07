package org.example.project.util

import java.io.File

/**
 * A secret by name: the environment variable of that name, else the line of that name in a bundle's `secrets.env`,
 * which the runner reads at the start. An empty value counts as none. A setting written `$ENV:NAME` (a password in
 * AMP/databases.json, an API key in serving.json) is read through here when it is used, so the files stay free of them.
 */
object Secrets {
    const val PREFIX = "\$ENV:"

    @Volatile private var fromFile: Map<String, String> = emptyMap()

    fun get(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: fromFile[name]

    /** [value], or what it points at when it is written `$ENV:NAME`; empty when that has no value */
    fun resolve(value: String): String = if (value.startsWith(PREFIX)) get(value.removePrefix(PREFIX)).orEmpty() else value

    /** Takes the NAME=value lines of [file]; a blank value, a comment and any other line are skipped */
    fun load(file: File) {
        if (file.isFile) fromFile = parse(file.readText())
    }

    internal fun parse(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .map { it.removePrefix("export ").substringBefore('=').trim() to it.substringAfter('=').trim().unquoted() }
        .filter { (name, value) -> name.isNotEmpty() && value.isNotEmpty() }
        .toMap()

    private fun String.unquoted(): String =
        if (length >= 2 && first() == last() && (first() == '"' || first() == '\'')) substring(1, length - 1) else this
}
