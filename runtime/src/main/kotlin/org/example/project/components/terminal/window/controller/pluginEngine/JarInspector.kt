package org.example.project.components.terminal.window.controller.pluginEngine

import java.io.DataInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * What a package jar contains, read from the file without running anything: its classes and resources, the
 * class a session opens on, what built it (the manifest) and which Java it needs (the class file version).
 */
data class JarContents(
    val name: String,
    val size: Long,
    val entries: Int,
    /** Fully qualified class names, inner classes left out */
    val classes: List<String>,
    /** Files that are not classes or manifest, e.g. a .properties or a .kotlin_module */
    val resources: List<String>,
    /** The class a session is opened on: the one with run(String) and close() */
    val entryClass: String?,
    val mainClass: String?,
    val createdBy: String?,
    val buildJdk: String?,
    val implementationTitle: String?,
    val implementationVersion: String?,
    /** The highest class file major version in the jar, e.g. 61 */
    val classVersion: Int?,
    /** The oldest Java that can load every class, e.g. 17 */
    val minJava: Int?,
    val kotlin: Boolean,
) {
    /** The Java this app runs on */
    val hostJava: Int get() = Runtime.version().feature()

    /** Whether every class loads on this app's Java */
    val runsHere: Boolean get() = minJava == null || minJava <= hostJava

    /** "Java 17 or newer" / "any Java" */
    val javaSupport: String get() = minJava?.let { "Java $it or newer" } ?: "any Java"

}

/** Reads jars for the Models page; one read per file version, cached by path, size and modification time */
object JarInspector {
    private val cache = ConcurrentHashMap<String, Pair<String, JarContents>>()

    fun inspect(file: File): JarContents? {
        if (!file.isFile) return null
        val stamp = "${file.length()}:${file.lastModified()}"
        cache[file.absolutePath]?.let { (s, contents) -> if (s == stamp) return contents }
        val contents = runCatching { read(file) }.getOrNull() ?: return null
        cache[file.absolutePath] = stamp to contents
        return contents
    }

    private fun read(file: File): JarContents {
        val classes = mutableListOf<String>()
        val resources = mutableListOf<String>()
        var entries = 0
        var major = 0
        var kotlin = false
        var mainClass: String? = null
        var createdBy: String? = null
        var buildJdk: String? = null
        var title: String? = null
        var version: String? = null
        JarFile(file).use { jar ->
            jar.manifest?.mainAttributes?.let { attributes ->
                mainClass = attributes.getValue("Main-Class")
                createdBy = attributes.getValue("Created-By")
                buildJdk = attributes.getValue("Build-Jdk-Spec") ?: attributes.getValue("Build-Jdk")
                title = attributes.getValue("Implementation-Title")
                version = attributes.getValue("Implementation-Version")
            }
            for (entry in jar.entries().asSequence().filter { !it.isDirectory }) {
                entries++
                val name = entry.name
                when {
                    name.endsWith(".class") -> {
                        if (name.endsWith("module-info.class") || name.endsWith("package-info.class")) continue
                        jar.getInputStream(entry).use { input ->
                            DataInputStream(input).let { data ->
                                if (data.readInt() == 0xCAFEBABE.toInt()) {
                                    data.readUnsignedShort() // minor
                                    major = maxOf(major, data.readUnsignedShort())
                                }
                            }
                        }
                        val className = name.removeSuffix(".class").replace('/', '.')
                        if (className.startsWith("kotlin.")) kotlin = true
                        if ('$' !in className.substringAfterLast('.')) classes += className
                    }
                    name == "META-INF/MANIFEST.MF" -> Unit
                    name.endsWith(".kotlin_module") -> { kotlin = true; resources += name }
                    else -> resources += name
                }
            }
        }
        if (!kotlin) kotlin = classes.any { it.endsWith("Kt") }
        return JarContents(
            name = file.name,
            size = file.length(),
            entries = entries,
            classes = classes.sorted(),
            resources = resources.sorted(),
            entryClass = JarPackages.entryClassName(file),
            mainClass = mainClass,
            createdBy = createdBy,
            buildJdk = buildJdk,
            implementationTitle = title,
            implementationVersion = version,
            classVersion = major.takeIf { it > 0 },
            // Class file 52 is Java 8, 61 is Java 17, 65 is Java 21: major - 44 from Java 5 on
            minJava = major.takeIf { it >= 49 }?.let { it - 44 },
            kotlin = kotlin,
        )
    }
}
