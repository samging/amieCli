package org.example.project.components.terminal.window.controller.pluginEngine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.metrics.Requirement
import org.example.project.components.terminal.window.controller.util.localTime
import org.example.project.getDirectory
import java.io.File
import java.time.Instant
import java.util.jar.JarFile

/** A configuration file the package reads from its config folder; a template is written there when the file is missing */
@Serializable
data class PackageConfig(
    val file: String,
    val required: Boolean = true,
    val description: String? = null,
    /** The text the file starts with when it is not there yet, for the person to fill in */
    val template: String? = null,
)

/** A coloured tag on the jar: what it works with or how it is doing (stable, needs key, v2 api…); colour is slate, green, amber, red, violet or blue */
@Serializable
data class PackageFlag(val text: String, val color: String = "slate")

/** An environment variable the package reads */
@Serializable
data class PackageEnvVar(val name: String, val required: Boolean = false, val description: String? = null)

/**
 * One test run the package ships: the text given to `run`, and what the answer must be. `expect` is the whole
 * answer (the lines joined with newlines, or the one line), `contains` a text some line must hold, `lines` how
 * many lines; a test with none of them passes when `run` does not throw.
 */
@Serializable
data class PackageTest(
    val name: String? = null,
    val input: String,
    val expect: String? = null,
    val contains: String? = null,
    val lines: Int? = null,
) {
    val title: String get() = name ?: "\"${input.take(40)}\""

    /** Null when the answer passes, else why it does not */
    fun check(answer: List<String>): String? {
        if (expect != null && answer.joinToString("\n") != expect && answer != listOf(expect)) return "expected \"$expect\", got \"${answer.joinToString(" | ").take(120)}\""
        if (contains != null && answer.none { it.contains(contains) }) return "no line contains \"$contains\" in \"${answer.joinToString(" | ").take(120)}\""
        if (lines != null && answer.size != lines) return "expected $lines line${if (lines == 1) "" else "s"}, got ${answer.size}"
        return null
    }
}

/**
 * What a package jar says about itself: `META-INF/amie/package.json` (or `amie-package.json` at the root) inside
 * the jar, which `AMP/pluginDir/<jar>.package.json` beside it overrides field by field, for a jar that ships none.
 * The runtime reads it before the jar is opened: the config files go in the jar's config folder (templates are
 * written for the missing ones), the Java it needs is checked, the entry class is the one it names, and its
 * tests are what **Test run** and `packages.test("x.jar")` run.
 */
@Serializable
data class PackageMeta(
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
    /** The Java feature version it needs, e.g. 21 */
    val java: Int? = null,
    /** The package class, when the jar holds more than one class with run(String) and close() */
    val entry: String? = null,
    val config: List<PackageConfig> = emptyList(),
    val env: List<PackageEnvVar> = emptyList(),
    val tests: List<PackageTest> = emptyList(),
    val notes: String? = null,
    /** What the jar works with, as coloured tags: the ones in the jar and the ones added on the Models page (kept in the sidecar) */
    val flags: List<PackageFlag> = emptyList(),
) {
    /** The sidecar's fields over the jar's: a field the sidecar sets replaces the jar's; flags add up */
    fun overlay(over: PackageMeta): PackageMeta = PackageMeta(
        name = over.name ?: name, version = over.version ?: version, description = over.description ?: description,
        java = over.java ?: java, entry = over.entry ?: entry,
        config = over.config.ifEmpty { config }, env = over.env.ifEmpty { env }, tests = over.tests.ifEmpty { tests },
        notes = over.notes ?: notes,
        flags = (flags + over.flags).distinctBy { it.text },
    )
}

@Serializable
data class PackageTestResult(val name: String, val input: String, val passed: Boolean, val answer: List<String>, val reason: String? = null, val millis: Long = 0)

/** What the last test run of a jar came to, kept in AMP/pluginDir/<jar>.tests.json */
@Serializable
data class PackageTestReport(
    val jar: String,
    val ranAt: String = Instant.now().toString(),
    val results: List<PackageTestResult> = emptyList(),
    /** The jar could not be opened, or the tests did not run: why */
    val error: String? = null,
    /** No tests were declared, so one call with "hello" was made, checked only for not throwing */
    val smoke: Boolean = false,
    val entryClass: String? = null,
) {
    val passed: Int get() = results.count { it.passed }
    val failed: Int get() = results.count { !it.passed }
    val ok: Boolean get() = error == null && failed == 0

    fun summary(): String = when {
        error != null -> "$jar: test run failed: $error"
        smoke -> "$jar: no tests declared; a call with \"hello\" ${if (ok) "answered ${results.firstOrNull()?.answer?.size ?: 0} line(s)" else "failed"}"
        else -> "$jar: $passed of ${results.size} test${if (results.size == 1) "" else "s"} passed"
    }
}

/** Reads and writes what a package says about itself, and where its configuration lives */
object PackageMetadata {
    const val IN_JAR = "META-INF/amie/package.json"
    const val IN_JAR_ROOT = "amie-package.json"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false }

    internal fun pluginDir(): File = File(getDirectory().toString(), "pluginDir").apply { mkdirs() }

    /** AMP/pluginDir/<jar>.package.json: metadata written beside a jar on this machine */
    fun sidecarFile(jarName: String): File = File(pluginDir(), "${File(jarName).name}.package.json")

    /** AMP/pluginDir/<jar>.tests.json: the last test run */
    fun reportFile(jarName: String): File = File(pluginDir(), "${File(jarName).name}.tests.json")

    /**
     * AMP/pluginDir/config/<jar without .jar>/: the folder the package's configuration files live in. The jar's
     * class loader resolves resources there first, `System.getProperty("amie.package.config")` names it while
     * the package is opened, and a `configure(File | Path | String | Map)` method of the package class gets it.
     */
    fun configDir(jarName: String): File = File(pluginDir(), "config/${File(jarName).name.removeSuffix(".jar")}")

    /** The metadata inside the jar, null when it ships none or it does not parse */
    fun inJar(file: File): PackageMeta? = runCatching {
        JarFile(file).use { jar ->
            val entry = jar.getJarEntry(IN_JAR) ?: jar.getJarEntry(IN_JAR_ROOT) ?: return null
            json.decodeFromString<PackageMeta>(jar.getInputStream(entry).bufferedReader().readText())
        }
    }.onFailure { logInternal("package ${file.name}: its metadata does not parse: ${it.message}", LogLevel.ERROR) }.getOrNull()

    fun sidecar(jarName: String): PackageMeta? = sidecarFile(jarName).takeIf { it.isFile }?.let { f ->
        runCatching { json.decodeFromString<PackageMeta>(f.readText()) }
            .onFailure { logInternal("package ${File(jarName).name}: ${f.name} does not parse: ${it.message}", LogLevel.ERROR) }.getOrNull()
    }

    /** The jar's own metadata with the sidecar's fields over it; null when there is neither */
    fun read(file: File): PackageMeta? {
        val inside = inJar(file)
        val beside = sidecar(file.name)
        return when {
            inside != null && beside != null -> inside.overlay(beside)
            else -> inside ?: beside
        }
    }

    /** Where the metadata came from, for the page */
    fun source(file: File): String? {
        val inside = runCatching { JarFile(file).use { it.getJarEntry(IN_JAR) != null || it.getJarEntry(IN_JAR_ROOT) != null } }.getOrDefault(false)
        val beside = sidecarFile(file.name).isFile
        return when {
            inside && beside -> "$IN_JAR in the jar, with ${sidecarFile(file.name).name} over it"
            inside -> "$IN_JAR in the jar"
            beside -> "${sidecarFile(file.name).name} beside the jar"
            else -> null
        }
    }

    fun writeSidecar(jarName: String, meta: PackageMeta): File = sidecarFile(jarName).apply { writeText(json.encodeToString(meta)) }

    /** Adds a flag to the jar's sidecar (made if there is none), so it shows beside the versions on the page */
    fun addFlag(jarName: String, flag: PackageFlag): File {
        val current = sidecar(jarName) ?: PackageMeta()
        return writeSidecar(jarName, current.copy(flags = (current.flags.filter { it.text != flag.text } + flag)))
    }

    /** Takes a flag out of the sidecar; a flag that is inside the jar itself stays */
    fun removeFlag(jarName: String, text: String): Boolean {
        val current = sidecar(jarName) ?: return false
        if (current.flags.none { it.text == text }) return false
        writeSidecar(jarName, current.copy(flags = current.flags.filter { it.text != text }))
        return true
    }

    /** A sidecar to fill in, for a jar that ships no metadata: the fields with their meaning, and one test to adjust */
    fun writeTemplate(jarName: String, entryClass: String?, minJava: Int?): File {
        val name = File(jarName).name
        val meta = PackageMeta(
            name = name.removeSuffix(".jar"),
            version = null,
            description = "What this package does",
            java = minJava,
            entry = entryClass,
            config = listOf(PackageConfig("settings.properties", required = false, description = "What the file holds; the package reads it from its config folder", template = "# filled in by hand\nkey=value\n")),
            env = listOf(PackageEnvVar("EXAMPLE_TOKEN", required = false, description = "An environment variable it reads, if any")),
            tests = listOf(PackageTest(name = "Answers hello", input = "hello", contains = "hello")),
            notes = "Delete the config, env and tests entries that do not apply. A test passes when its expect (the whole answer), contains (some line) and lines (how many) hold.",
        )
        return writeSidecar(name, meta).also { logInternal("package $name: metadata template written to ${it.absolutePath}; edit it to say what the jar needs") }
    }

    /**
     * Makes the config folder and writes the template of every declared file that is not there yet. Returns one
     * line per thing done or missing; the required files still missing are errors in the console.
     */
    fun prepareConfig(jarName: String, meta: PackageMeta?): List<String> {
        val dir = configDir(jarName).apply { mkdirs() }
        if (meta == null || meta.config.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        meta.config.forEach { c ->
            val file = File(dir, c.file)
            when {
                file.isFile -> lines += "${c.file}: present"
                c.template != null -> {
                    runCatching { file.parentFile.mkdirs(); file.writeText(c.template) }
                        .onSuccess { lines += "${c.file}: template written, fill it in"; logInternal("package ${File(jarName).name}: ${c.file} was missing; its template is in ${file.absolutePath}") }
                        .onFailure { lines += "${c.file}: template could not be written: ${it.message}" }
                }
                c.required -> { lines += "${c.file}: missing (required)"; logInternal("package ${File(jarName).name}: ${c.file} is required but not in ${dir.absolutePath}", LogLevel.ERROR) }
                else -> lines += "${c.file}: missing (optional)"
            }
        }
        return lines
    }

    fun lastReport(jarName: String): PackageTestReport? =
        reportFile(jarName).takeIf { it.isFile }?.let { f -> runCatching { json.decodeFromString<PackageTestReport>(f.readText()) }.getOrNull() }

    fun writeReport(report: PackageTestReport) {
        runCatching { reportFile(report.jar).writeText(json.encodeToString(report)) }
            .onFailure { logInternal("package ${report.jar}: the test report could not be written: ${it.message}", LogLevel.ERROR) }
    }

    /** Whether the config file still is its template, unmodified: there for the person to fill in, so not filled in */
    fun unfilled(file: File, config: PackageConfig): Boolean = config.template != null && file.isFile &&
        runCatching { file.readText().trim() == config.template.trim() }.getOrDefault(false)

    /** One line per config file and environment variable the jar declares, each saying whether it is met */
    fun needs(jarName: String, meta: PackageMeta?): List<Requirement> = buildList {
        val dir = configDir(jarName)
        meta?.config?.forEach { c ->
            val f = File(dir, c.file)
            val blank = unfilled(f, c)
            val state = when {
                blank && c.required -> "Still its template, fill it in"
                blank -> "Still its template, optional"
                f.isFile -> "Present, ${f.length()} bytes"
                c.required && c.template != null -> "Missing; its template is written on the first open, to fill in"
                c.required -> "Missing and required"
                else -> "Missing, optional"
            }
            add(Requirement(c.file, state + (c.description?.let { " · $it" } ?: ""), !c.required || (f.isFile && !blank)))
        }
        meta?.env?.forEach { v ->
            val set = !System.getenv(v.name).isNullOrEmpty()
            add(Requirement("\$${v.name}", (if (set) "Set" else if (v.required) "Not set and required" else "Not set, optional") + (v.description?.let { " · $it" } ?: ""), set || !v.required))
        }
    }

    /** What keeps the jar from running at all: the lines of [needs] that are not met. `initMod` refuses on any */
    fun blockers(jarName: String, meta: PackageMeta?): List<Requirement> = needs(jarName, meta).filter { it.ok == false }

    /**
     * What the jar needs before `initMod` can answer, checked on this machine: the Java it asks for, its entry
     * class, every config file and environment variable it declares ([needs]), and what its last test run came to.
     */
    fun requirements(file: File, contents: JarContents?, meta: PackageMeta?, entryClass: String?, metaSource: String? = source(file)): List<Requirement> {
        val host = Runtime.version().feature()
        val out = mutableListOf<Requirement>()
        val java = meta?.java ?: contents?.minJava
        out += Requirement("Java", if (java == null) "Any Java; this app runs Java $host" else "Java $java or newer${meta?.java?.let { " (declared)" } ?: " (class files)"}; this app runs Java $host", java == null || java <= host)
        out += Requirement("Package class", entryClass?.let { "$it, with run(String) and close()" + (meta?.entry?.takeIf { e -> e != it }?.let { e -> "; the metadata names $e, which does not satisfy the contract" } ?: "") } ?: "none: no public class with run(String), close() and a no-arg constructor", entryClass != null && (meta?.entry == null || meta.entry == entryClass))
        out += Requirement("Metadata", metaSource ?: "None; ⋯ > Write metadata template makes ${sidecarFile(file.name).name} to fill in", if (meta == null) null else true)
        val dir = configDir(file.name)
        out += Requirement("Config folder", "AMP/pluginDir/config/${dir.name}" + (if (dir.isDirectory) "; its path is under Settings, Package folders" else " (made on the first open)"), null)
        out += needs(file.name, meta)
        val report = lastReport(file.name)
        out += Requirement(
            "Tests",
            when {
                meta == null || meta.tests.isEmpty() -> "None declared; Test run makes one call with \"hello\"" + (report?.let { " · last run ${localTime(it.ranAt, seconds = false)}: ${if (it.ok) "answered" else it.error ?: "failed"}" } ?: "")
                report == null -> "${meta.tests.size} declared, not run yet"
                else -> "${meta.tests.size} declared · last run ${localTime(report.ranAt, seconds = false)}: ${report.passed} passed, ${report.failed} failed" + (report.error?.let { "; $it" } ?: "")
            },
            when { report == null -> null; else -> report.ok },
        )
        return out
    }
}
