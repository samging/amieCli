package org.example.project.components.terminal.window.controller.pluginEngine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import java.io.File
import java.time.Instant

/** A named folder of package jars that belong together, e.g. "jvm21" with every jar that runs on Java 21 */
@Serializable
data class PackageCollection(val name: String, val jars: List<String> = emptyList(), val createdAt: String = Instant.now().toString())

@Serializable
private data class CollectionsFile(val collections: List<PackageCollection> = emptyList())

/** The package collections, kept in AMP/pluginDir/collections.json */
object PackageCollections {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file: File get() = File(getDirectory().toString(), "pluginDir/collections.json")

    @Synchronized
    fun all(): List<PackageCollection> =
        runCatching { json.decodeFromString<CollectionsFile>(file.readText()).collections }.getOrDefault(emptyList()).sortedBy { it.name }

    fun find(name: String): PackageCollection? = all().firstOrNull { it.name == name }

    /** The collections a jar is in */
    fun of(jar: String): List<String> = all().filter { jar in it.jars }.map { it.name }

    /** Makes a collection with these jars, or adds them to the one of that name */
    @Synchronized
    fun create(name: String, jars: List<String>) {
        val clean = name.trim().replace(Regex("""[^A-Za-z0-9._ -]"""), "_")
        require(clean.isNotEmpty()) { "A collection needs a name" }
        val current = all()
        val existing = current.firstOrNull { it.name == clean }
        write(current.filter { it.name != clean } + (existing?.copy(jars = (existing.jars + jars).distinct()) ?: PackageCollection(clean, jars.distinct())))
        logInternal("collections: '$clean' holds ${jars.size} jars")
    }

    fun add(collection: String, jar: String) = create(collection, listOf(jar))

    @Synchronized
    fun remove(collection: String, jar: String) {
        val current = all()
        val existing = current.firstOrNull { it.name == collection } ?: return
        write(current.filter { it.name != collection } + existing.copy(jars = existing.jars - jar))
        logInternal("collections: '$jar' detached from '$collection'")
    }

    @Synchronized
    fun delete(collection: String) {
        write(all().filter { it.name != collection })
        logInternal("collections: '$collection' deleted")
    }

    private fun write(collections: List<PackageCollection>) {
        file.parentFile.mkdirs()
        runCatching { file.writeText(json.encodeToString(CollectionsFile(collections.sortedBy { it.name }))) }
            .onFailure { logInternal("collections: couldn't write ${file.name}: ${it.message}", LogLevel.ERROR) }
    }
}
