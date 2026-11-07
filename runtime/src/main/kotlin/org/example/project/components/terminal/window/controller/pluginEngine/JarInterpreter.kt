package org.example.project.components.terminal.window.controller.pluginEngine

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.console.logInternal
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Runs a plugin jar's static run() or main(), for importPlugin("plugJar.jar"). Scripts open package jars with
 * initMod("x.jar") instead, which makes an instance they can call with text.
 * A jar is looked up in the current env first (imported with amie.import("plugJar.jar")), then as a path or in AMP/pluginDir,
 * and loaded over the env's classpath so it can use the packages the env sourced.
 */
class JarInterpreter {
    private val loaded = linkedMapOf<String, Pair<File, URLClassLoader>>()

    val running: List<String> @Synchronized get() = loaded.keys.toList()

    /**
     * Loads the jar (once, until quit) and calls a static no-arg [methodName] on [className].
     * Without a class name the manifest's Main-Class is used; without [methodName] there, main(String[]) is called.
     */
    @Synchronized
    fun run(jarFile: String, className: String? = null, methodName: String = "run"): Any? {
        val (file, loader) = loaded[key(jarFile)] ?: load(jarFile) ?: return null
        val mainClass = className ?: JarFile(file).use { it.manifest?.mainAttributes?.getValue("Main-Class") }
        if (mainClass == null) {
            logInternal("jar ${file.name}: no class given and no Main-Class in its manifest", LogLevel.ERROR)
            return null
        }
        return try {
            val clazz = loader.loadClass(mainClass)
            val method = clazz.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 && Modifier.isStatic(it.modifiers) }
            val previous = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = loader
            try {
                val result = if (method != null) method.invoke(null)
                else clazz.getMethod("main", Array<String>::class.java).invoke(null, emptyArray<String>())
                logInternal("jar ${file.name}: $mainClass.${method?.name ?: "main"} -> $result")
                result
            } finally {
                Thread.currentThread().contextClassLoader = previous
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            logInternal("jar ${file.name}: ${e.cause}", LogLevel.ERROR)
            null
        } catch (e: ReflectiveOperationException) {
            logInternal("jar ${file.name}: $e", LogLevel.ERROR)
            null
        }
    }

    /** Unloads the jar so the next run picks up a rebuilt file */
    @Synchronized
    fun quit(jarFile: String): Boolean {
        val entry = loaded.remove(key(jarFile)) ?: run {
            logInternal("jar $jarFile is not running")
            return false
        }
        entry.second.close()
        logInternal("jar ${entry.first.name}: quit")
        return true
    }

    @Synchronized
    fun quitAll() = running.forEach { quit(it) }

    private fun key(jarFile: String) = File(jarFile).name

    private fun load(jarFile: String): Pair<File, URLClassLoader>? {
        val env = ScriptEnvironments.current
        val file = env?.localJar(jarFile)
            ?: (env?.jarSearchPath(jarFile) ?: listOf(File(jarFile))).firstOrNull { it.isFile }
        if (file == null) {
            logInternal("jar $jarFile not found; import it with amie.import(\"$jarFile\")", LogLevel.ERROR)
            return null
        }
        val parent = env?.classLoader(JarInterpreter::class.java.classLoader) ?: JarInterpreter::class.java.classLoader
        // The jar's config folder is the first root, so its configuration files resolve as resources; the
        // metadata's templates are written there first, and amie.package.config names it
        val configDir = PackageMetadata.configDir(file.name)
        PackageMetadata.prepareConfig(file.name, PackageMetadata.read(file))
        System.setProperty("amie.package.config", configDir.absolutePath)
        System.setProperty("amie.package.config.${file.name.removeSuffix(".jar")}", configDir.absolutePath)
        val loader = URLClassLoader("amie-jar-${file.name}", arrayOf(configDir.toURI().toURL(), file.toURI().toURL()), parent)
        logInternal("jar ${file.name}: loaded from ${file.absolutePath}, config in ${configDir.absolutePath}")
        return (file to loader).also { loaded[key(jarFile)] = it }
    }

    companion object {
        // Shared so a jar started by one run can be quit by the next
        val shared = JarInterpreter()
    }
}
