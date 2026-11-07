package org.example.project.components.terminal.window.controller.export

import kotlinx.coroutines.runBlocking
import org.example.project.components.terminal.window.controller.script.ScriptEngineCache
import org.example.project.components.terminal.window.controller.script.ScriptErrors
import org.example.project.components.terminal.window.controller.script.ScriptLogger
import org.example.project.components.terminal.window.controller.script.assembleScript
import org.example.project.components.terminal.window.controller.script.movedImports
import org.example.project.components.terminal.window.controller.script.withContextClassLoader
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.commons.ClassRemapper
import org.jetbrains.org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.JvmScriptCompiler

/** How a bundle carries the script: as classes that any Java 21 runs, or as text the Kotlin compiler, carried too, compiles at start */
enum class ExportMode {
    COMPILED, SCRIPT;

    companion object {
        fun of(name: String): ExportMode = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: COMPILED
    }
}

/** An export that stopped: [problems] say why, one line each, as the panel and the command line show them */
class ExportFailed(val problems: List<String>) : IllegalStateException(problems.joinToString("\n"))

/** One script as classes: [className] is what the runner instantiates with the ScriptLogger, [classes] are the files for scripts.jar */
internal class CompiledScript(val className: String, val classes: Map<String, ByteArray>)

/**
 * Compiles a script into classes, as the app's Run compiles it, for a bundle that carries no compiler. The script is
 * assembled as a Run assembles it (the imports first, then the API prelude), with `amie` a provided property of the
 * script class instead of a line reading it from the engine's bindings, so the class's one constructor takes the
 * ScriptLogger and the script's body is the constructor; ScriptRunner.runCompiled runs it so.
 */
internal object CompiledScripts {
    /** The file name a template [id] is compiled under: its class is Template_<id> (a hyphen becomes an underscore), which can't be Main */
    fun templateFile(id: String): String = "template_$id.kts"

    /**
     * [code] compiled as the script [fileName] against [classPath] (the app's jars and the env's); the compiler's
     * errors, with their places in the script's own lines, come as an [ExportFailed]
     */
    fun compile(code: String, fileName: String, classPath: List<File>): CompiledScript {
        val assembled = assembleScript(code, providedAmie = true)
        val header = ScriptErrors.headerLines(code, assembled)
        val imports = movedImports(code)
        val configuration = ScriptCompilationConfiguration {
            providedProperties("amie" to KotlinType(ScriptLogger::class))
            jvm {
                updateClasspath(classPath.filter { it.exists() })
                jvmTarget("21")
            }
        }
        val result = withContextClassLoader(ScriptEngineCache.classLoader) {
            runBlocking { JvmScriptCompiler()(assembled.toScriptSource(fileName), configuration) }
        }
        when (result) {
            is ResultWithDiagnostics.Success -> {
                val compiled = result.value as? KJvmCompiledScript ?: throw ExportFailed(listOf("$fileName compiled to something that isn't a JVM script: ${result.value}"))
                val module = compiled.getCompiledModule() as? KJvmCompiledModuleInMemory
                    ?: throw ExportFailed(listOf("$fileName compiled, but its classes aren't in memory to save: ${compiled.getCompiledModule()}"))
                return CompiledScript(compiled.scriptClassFQName, module.compilerOutputFiles)
            }
            is ResultWithDiagnostics.Failure -> throw ExportFailed(result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.map { report ->
                val row = report.location?.start?.line
                // The header's first lines are the script's imports, moved up; the rest of the header has no place in the script
                val moved = row?.let { imports.getOrNull(it - 1) }
                val line = moved?.line ?: row?.let { (it - header).takeIf { l -> l in 1..code.lines().size } }
                val column = report.location?.start?.col?.let { if (moved != null) it + moved.indent else it }
                val place = if (line == null) fileName else "$fileName:$line" + (column?.let { ":$it" } ?: "")
                "$place: ${report.message}" + (report.exception?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
            }.ifEmpty { listOf("$fileName didn't compile, and the compiler said nothing more") })
        }
    }

    /** The classes of every script in one jar, each file once; the manifest only says there is one */
    fun writeJar(scripts: Collection<CompiledScript>, jar: File) {
        jar.parentFile.mkdirs()
        val seen = mutableSetOf("META-INF/MANIFEST.MF")
        JarOutputStream(jar.outputStream().buffered()).use { out ->
            out.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            out.write("Manifest-Version: 1.0\n\n".toByteArray())
            out.closeEntry()
            scripts.forEach { script ->
                script.classes.forEach { (path, bytes) ->
                    if (seen.add(path)) {
                        out.putNextEntry(JarEntry(path))
                        out.write(bytes)
                        out.closeEntry()
                    }
                }
            }
        }
    }
}

/**
 * What a compiled bundle carries of the app's class path: the jars a compiled script's run reaches, named, and the
 * app's own classes cut down to those reachable from the runner and the scripts. Nothing of the compiler, Compose,
 * Ktor, the Gradle API or TensorFlow goes along, and the two jars with native libraries for every platform are cut
 * down to this platform's.
 */
internal object CompiledRuntime {
    /** The classes every compiled bundle starts from; the scripts' own classes are added to them */
    val roots = listOf(
        "org.example.project.components.terminal.window.controller.export.BundleRunner",
        "org.example.project.components.terminal.window.controller.script.ScriptRunner",
        "org.example.project.components.terminal.window.controller.script.ScriptLogger",
        "org.example.project.components.terminal.window.controller.script.SandboxLogger",
        "org.example.project.components.terminal.window.controller.script.TemplateRuns",
        "org.example.project.components.terminal.window.controller.script.BackgroundLogger",
    )

    // A jar's name without its version: kotlinx-coroutines-core-jvm-1.10.1.jar is kotlinx-coroutines-core-jvm
    private val version = Regex("""-\d.*$""")

    // Always: the Kotlin runtime, coroutines, serialization and okio (the AMP folder is an okio path), the two
    // loggers, ONNX Runtime, the tokenizers with the DJL API they are part of, and the app's own jars
    private val always = Regex("^(kotlin-stdlib(-jdk[78])?|kotlinx-coroutines-core(-jvm)?|kotlinx-serialization-(core|json)(-jvm)?|annotations|slf4j-api|slf4j-simple|okio(-jvm)?|onnxruntime|tokenizers|api|gson|jna|desktopApp.*|shared.*)$")
    private val withDatabases = Regex("^postgresql$")
    private val withBackend = Regex("^(ktor-.*|kotlinx-io-.*)$")

    /** The app's own classes: a class folder of a Gradle run, or the app's jars in a packaged one */
    private val appJar = Regex("^(desktopApp|shared)(-.*)?$")

    fun baseName(jar: File): String = ScriptExporter.jarName(jar).removeSuffix(".jar").replace(version, "")

    /** Whether [jar] is one the app's own classes are in, which goes in cut down rather than whole */
    fun isAppJar(jar: File): Boolean = appJar.matches(baseName(jar))

    /**
     * The jars of [classPath] a compiled bundle carries, the first of two versions of one, and the class folders and
     * app jars its classes are read from. The scan's [needs] bring the Postgres driver and Ktor when the script reaches them.
     */
    fun runtimeJars(classPath: List<File>, needs: ScriptNeeds): Pair<List<File>, List<File>> {
        val (folders, jars) = classPath.filter { it.exists() }.partition { it.isDirectory }
        val wanted = listOfNotNull(
            always,
            withDatabases.takeIf { needs.databases.isNotEmpty() || needs.allDatabases },
            withBackend.takeIf { needs.usesBackend },
        )
        val seen = mutableSetOf<String>()
        val kept = jars.filter { it.extension == "jar" }.filter { jar -> wanted.any { it.matches(baseName(jar)) } }.filter { jar -> seen.add(baseName(jar)) }
        return kept.filter { !isAppJar(it) } to folders + kept.filter { isAppJar(it) }
    }

    /** ONNX Runtime's name for this machine: osx-aarch64, osx-x64, linux-x64, linux-aarch64, win-x64 */
    fun ortPlatform(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String = "${osName(os)}-${if (arm(arch)) "aarch64" else "x64"}"

    /** The tokenizers' name for it: osx-aarch64, linux-x86_64, linux-aarch64, win-x86_64 */
    fun tokenizersPlatform(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String = "${osName(os)}-${if (arm(arch)) "aarch64" else "x86_64"}"

    /** The bundle's own name for it, as the manifest lists platforms: macos-arm64, linux-x64, linux-arm64, windows-x64 */
    fun platform(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String {
        val name = os.lowercase()
        val system = if (name.contains("mac")) "macos" else if (name.contains("win")) "windows" else "linux"
        return "$system-${if (arm(arch)) "arm64" else "x64"}"
    }

    private fun osName(os: String) = os.lowercase().let { if (it.contains("mac")) "osx" else if (it.contains("win")) "win" else "linux" }
    private fun arm(arch: String) = arch.lowercase().let { it.contains("aarch64") || it.contains("arm64") }

    /**
     * Which entries of [jar] a bundle for this platform keeps: null when the jar goes whole. ONNX Runtime and the
     * tokenizers carry a native library for every platform, 50 to 60 MB of them for the others, and their debug symbols
     */
    fun keeps(jar: File, ort: String = ortPlatform(), tokenizers: String = tokenizersPlatform()): ((String) -> Boolean)? = when (baseName(jar)) {
        "onnxruntime" -> { entry -> !entry.startsWith("ai/onnxruntime/native/") || (entry.startsWith("ai/onnxruntime/native/$ort/") && !entry.contains(".dSYM/")) }
        "tokenizers" -> { entry -> !entry.startsWith("native/lib/") || entry == "native/lib/tokenizers.properties" || entry.startsWith("native/lib/$tokenizers/") }
        else -> null
    }

    /** [jar] copied to [into] with only the entries [keep] says */
    fun trim(jar: File, keep: (String) -> Boolean, into: File) {
        into.parentFile.mkdirs()
        JarFile(jar).use { source ->
            JarOutputStream(into.outputStream().buffered()).use { out ->
                source.entries().asSequence().filter { !it.isDirectory && keep(it.name) }.forEach { entry ->
                    out.putNextEntry(JarEntry(entry.name).apply { time = entry.time })
                    source.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    /**
     * The app's own classes, from the class folders and app jars [sources], that the classes named in [roots] and
     * [scripts] reach by reference, in one jar at [into], with the sources' other files (never Compose's resources).
     * What is reached is read from each class with ASM, the compiler's own copy of it, so a class named only in a
     * string isn't seen: the runtime names none of its own that way.
     */
    fun packReachable(sources: List<File>, roots: List<String>, scripts: Collection<CompiledScript>, into: File): Int {
        val classes = linkedMapOf<String, () -> ByteArray>()
        val others = linkedMapOf<String, () -> ByteArray>()
        sources.forEach { source ->
            if (source.isDirectory) source.walkTopDown().filter { it.isFile }.forEach { file ->
                val path = file.relativeTo(source).invariantSeparatorsPath
                (if (path.endsWith(".class")) classes else others).putIfAbsent(path) { file.readBytes() }
            } else if (source.isFile) JarFile(source).use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory }.toList().forEach { entry ->
                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    (if (entry.name.endsWith(".class")) classes else others).putIfAbsent(entry.name) { bytes }
                }
            }
        }
        val kept = linkedSetOf<String>()
        val waiting = ArrayDeque<String>()
        fun want(internalName: String) {
            val path = "$internalName.class"
            if (path in classes && kept.add(path)) waiting += path
        }
        roots.forEach { want(it.replace('.', '/')) }
        // The compiler's output holds a module file beside the classes
        scripts.flatMap { it.classes.entries }.filter { it.key.endsWith(".class") }.forEach { (_, bytes) -> referenced(bytes).forEach(::want) }
        while (waiting.isNotEmpty()) {
            val path = waiting.removeFirst()
            referenced(classes.getValue(path)()).forEach(::want)
        }
        into.parentFile.mkdirs()
        JarOutputStream(into.outputStream().buffered()).use { out ->
            out.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            out.write("Manifest-Version: 1.0\n\n".toByteArray())
            out.closeEntry()
            kept.forEach { path ->
                out.putNextEntry(JarEntry(path))
                out.write(classes.getValue(path)())
                out.closeEntry()
            }
            others.filterKeys { it != "META-INF/MANIFEST.MF" && !it.startsWith("composeResources/") && !it.endsWith(".DS_Store") }.forEach { (path, bytes) ->
                out.putNextEntry(JarEntry(path))
                out.write(bytes())
                out.closeEntry()
            }
        }
        return kept.size
    }

    /** Every class [bytes] refers to, by internal name: its supertypes, the types of its fields, methods and code */
    fun referenced(bytes: ByteArray): Set<String> {
        val names = linkedSetOf<String>()
        val recorder = object : Remapper() {
            override fun map(internalName: String): String = internalName.also { names += it }
        }
        ClassReader(bytes).accept(ClassRemapper(ClassWriter(0), recorder), ClassReader.SKIP_FRAMES)
        return names
    }
}
