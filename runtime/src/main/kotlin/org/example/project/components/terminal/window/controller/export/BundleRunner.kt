package org.example.project.components.terminal.window.controller.export

import org.example.project.auth.AuthState
import org.example.project.components.terminal.integrations.amiePilot.BundledModels
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.ResponseDto
import org.example.project.components.terminal.window.controller.sandbox.SANDBOX_SOURCE
import org.example.project.components.terminal.window.controller.script.ScriptEngineCache
import org.example.project.components.terminal.window.controller.script.ScriptInput
import org.example.project.components.terminal.window.controller.script.ScriptRunner
import org.example.project.components.terminal.window.controller.databases.Databases
import org.example.project.components.terminal.window.controller.script.TemplateRun
import org.example.project.components.terminal.window.controller.script.TemplateRuns
import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.console.flushLogFile
import org.example.project.components.terminal.window.controller.runtime.RunMeter
import org.example.project.components.terminal.window.controller.console.headlessConsole
import org.example.project.components.terminal.window.controller.console.logToConsole
import org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions
import org.example.project.components.terminal.window.controller.runtime.ModelServer
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.components.terminal.window.controller.console.text
import org.example.project.getDirectory
import org.example.project.util.Secrets
import java.io.File
import java.net.URLClassLoader
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import org.example.project.amp.ModelsMigration

/**
 * What `run.jar` starts: a bundle's script, as the sandbox's Run would do it, with no window. The console is standard
 * output (ERROR lines standard error), and the exit code says how it went: 0 finished, 1 failed, 2 not started. A script
 * that left a model serving keeps the process up until it is stopped.
 *
 * A compiled bundle runs the classes of `lib/scripts.jar`, main's or a template's (`--template`), loaded over the env
 * as a Run loads a script; a script bundle compiles `AMP/pluginDir/main.kts` with the compiler it carries.
 */
object BundleRunner {
    private const val USAGE = """Runs this bundle's script.

  java -jar run.jar                   run the script
  java -jar run.jar --template NAME   run that template instead (its name or its id; --list says which there are)
  java -jar run.jar --list            list what it can run
  java -jar run.jar --check           see that it can run: Java, native libraries, secrets, the scripts; nothing is run
  java -jar run.jar --levels info,data,error,internal
                                      what to print; the default leaves out internal, and every level goes to AMP/logs.txt

What the script's input() reads is standard input, a line at a time; null once it ends.
secrets.env beside run.jar, or the environment, gives it what README.md lists.
AMIE_AMP_DIR names a folder to work in, set up from the bundle's AMP/ the first time; the default is that AMP/ itself."""

    /** The entry main.kts goes by in the manifest's classes */
    const val MAIN = "main"

    @JvmStatic
    fun main(args: Array<String>) {
        val check = "--check" in args
        val list = "--list" in args
        val levels = args.indexOf("--levels").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val template = args.indexOf("--template").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val known = listOfNotNull("--check".takeIf { check }, "--list".takeIf { list }, "--levels".takeIf { levels != null }, levels, "--template".takeIf { template != null }, template)
        if ("--help" in args || "-h" in args || args.any { it !in known } || (args.contains("--template") && template == null)) {
            println(USAGE)
            exitProcess(if ("--help" in args || "-h" in args) 0 else 2)
        }
        val shown = levels?.split(',')?.mapNotNull { name -> LogLevel.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }?.toSet()
            ?: setOf(LogLevel.INFO, LogLevel.DATA, LogLevel.ERROR)

        val root = bundleRoot()
        val manifest = runCatching { BundleManifest.read(File(root, BundleManifest.FILE_NAME)) }.getOrElse {
            System.err.println("No readable ${BundleManifest.FILE_NAME} in $root: ${it.message}")
            exitProcess(2)
        }
        if (list) {
            println("main      ${manifest.source}" + if (manifest.compiled) "  (${manifest.classes[MAIN]})" else "")
            manifest.templates.forEach { id -> println("template  ${manifest.templateNames[id] ?: id}  (id $id" + (manifest.classes[id]?.let { ", $it" } ?: "") + ")") }
            exitProcess(0)
        }
        val java = Runtime.version().feature()
        if (java !in manifest.javaMin..manifest.javaMax) {
            System.err.println("This bundle needs Java ${manifest.javaRange}, and this is $java" + if (manifest.compiled) "" else ": its script compiler stops on later ones")
            exitProcess(2)
        }
        val entry = template?.let { wanted ->
            manifest.templates.firstOrNull { id -> id == wanted || manifest.templateNames[id]?.equals(wanted, ignoreCase = true) == true } ?: run {
                System.err.println("No template '$wanted' in this bundle; it has: ${manifest.templates.joinToString { manifest.templateNames[it] ?: it }.ifEmpty { "none" }}")
                exitProcess(2)
            }
        } ?: MAIN
        if (entry != MAIN) {
            val name = manifest.templateNames[entry] ?: entry
            if (!manifest.compiled) {
                System.err.println("--template runs a template of a compiled bundle; this one carries its script as text and only runs that. Export it compiled to run '$name' on its own")
                exitProcess(2)
            }
        }
        // All of this comes before the first line that logs: the AMP folder is settled by the first look at it
        headlessConsole = { line -> if (line.level in shown) print(line) }
        ScriptInput.fromStandardInput = true
        System.setProperty("java.awt.headless", "true")
        // Standard error is for what went wrong: a failed script's reason is there already, from the console
        System.setProperty("org.slf4j.simpleLogger.log.CodingSandbox", "off")
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        System.setProperty("org.slf4j.simpleLogger.log.ai.djl", "error")
        System.setProperty("amie.models.dir", File(root, ScriptExporter.MODELS).path)
        val amp = settleAmp(root)
        // A bundle made before the move into AMP/models is read as it is; one made after has its speech.properties pointed at its own folder
        ModelsMigration.runOnce()
        if (System.getenv("DJL_CACHE_DIR") == null) System.setProperty("DJL_CACHE_DIR", File(amp, "cache/djl").path)
        Secrets.load(File(root, ScriptExporter.SECRETS_FILE))
        AuthState.useTokens(Secrets.get("AMIE_ACCESS_TOKEN"), Secrets.get("AMIE_REFRESH_TOKEN"))
        writeRelations(amp, manifest)
        pointTemplates(amp, manifest)

        val code = File(amp, "pluginDir/main.kts").readText()
        val rewrite = { script: String -> pointAtBundle(script, manifest, amp) }
        val missing = manifest.secrets.filter { it.required && Secrets.get(it.name) == null }
        val lacking = lacking(root, amp, manifest)
        val failed = if (check) check(root, manifest, amp, code, rewrite, missing, lacking) else {
            missing.forEach { System.err.println("${it.name} is not set: ${it.why} (see README.md)") }
            if (lacking.isNotEmpty()) {
                System.err.println("This bundle was exported without its jars, and these are not in place (${ScriptExporter.REQUIRE_FILE} lists them all):")
                lacking.forEach { System.err.println("  ${it.path}  ${it.what}") }
                val packages = lacking.filter { it.what.startsWith("package ") }
                if (packages.isNotEmpty()) {
                    System.err.println("A package jar is fetched from nowhere: put ${packages.joinToString { File(it.path).name }} in AMP/pluginDir and run again")
                    exitProcess(2)
                }
                System.err.println("The env fetches a Maven package from Maven Central when it can reach it; offline, put the files in place")
            }
            if (manifest.compiled) runCompiled(root, manifest, amp, code, entry) else run(code, rewrite)
        }
        close(failed)
    }

    private fun print(line: ResponseDto) {
        val out = if (line.level == LogLevel.ERROR) System.err else System.out
        out.println(line.text())
    }

    // run.jar's own folder, also when it is started from somewhere else
    private fun bundleRoot(): File {
        val own = File(BundleRunner::class.java.protectionDomain.codeSource.location.toURI())
        return generateSequence(if (own.isDirectory) own else own.parentFile) { it.parentFile }
            .firstOrNull { File(it, BundleManifest.FILE_NAME).isFile } ?: File(System.getProperty("user.dir"))
    }

    /**
     * The AMP folder of this run: the bundle's own, or AMIE_AMP_DIR, which gets the bundle's copy of what it doesn't
     * have yet and always its script, so a mounted folder keeps its data over a new version of the bundle.
     */
    private fun settleAmp(root: File): File {
        val bundled = File(root, ScriptExporter.AMP)
        val chosen = System.getenv("AMIE_AMP_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        if (chosen == null) {
            System.setProperty("amie.amp.dir", bundled.path)
            return bundled
        }
        if (chosen.canonicalFile != bundled.canonicalFile) {
            chosen.mkdirs()
            bundled.copyRecursively(chosen, overwrite = false) { _, _ -> OnErrorAction.SKIP }
            File(bundled, "pluginDir/main.kts").copyTo(File(chosen, "pluginDir/main.kts"), overwrite = true)
        }
        return chosen
    }

    // relations.json is keyed by whole paths, which only this machine can say
    private fun writeRelations(amp: File, manifest: BundleManifest) {
        if (manifest.relations.isEmpty()) return
        val lines = manifest.relations.entries.joinToString(",\n") { (train, test) ->
            "  ${quoted(File(amp, train).canonicalPath)}: ${quoted(File(amp, test).canonicalPath)}"
        }
        File(amp, "relations.json").writeText("{\n$lines\n}\n")
    }

    // What a template starts with is read from its file, so its paths are put right there; a second run finds them done
    private fun pointTemplates(amp: File, manifest: BundleManifest) {
        if (manifest.paths.isEmpty()) return
        manifest.templates.map { File(amp, "templates/$it.kts") }.filter { it.isFile }.forEach { it.writeText(pointAtBundle(it.readText(), manifest, amp)) }
    }

    private fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // A path the script wrote out from the machine it was exported on, now where the file is on this one; only ever
    // inside a string, so the script's lines stay where they were
    internal fun pointAtBundle(script: String, manifest: BundleManifest, amp: File): String =
        manifest.paths.entries.fold(script) { text, (named, inAmp) -> text.replace(quoted(named), quoted(File(amp, inAmp).path)) }

    private fun run(code: String, rewrite: (String) -> String): Boolean = timed("pluginDir/main.kts") { ScriptRunner.run(code, rewrite) }

    /**
     * A compiled bundle's [entry], main or a template's id: its class from lib/scripts.jar, loaded over the env main.kts
     * names, which is sourced first as a Run sources it. Every template of the bundle is reachable to coroutineTemplate
     * the same way, so the templates start each other without the app.
     */
    private fun runCompiled(root: File, manifest: BundleManifest, amp: File, code: String, entry: String): Boolean {
        val className = manifest.classes[entry] ?: run {
            System.err.println("This bundle has no class for '$entry'; its ${BundleManifest.FILE_NAME} lists: ${manifest.classes.keys.joinToString()}")
            exitProcess(2)
        }
        val loader = scriptsLoader(root, code)
        TemplateRuns.compiledTemplates = { id -> manifest.classes[id]?.let { loader.loadClass(it) } }
        val env = ScriptEnvironments.current ?: error("the env wasn't sourced")
        val entryCode = if (entry == MAIN) code else File(amp, "templates/$entry.kts").takeIf { it.isFile }?.readText() ?: code
        val file = if (entry == MAIN) "main.kts" else "$entry.kts"
        return timed(if (entry == MAIN) "pluginDir/main.kts" else "template ${manifest.templateNames[entry] ?: entry}") {
            ScriptRunner.runCompiled(entryCode, loader.loadClass(className), env, file)
        }
    }

    // The env main.kts names, sourced, under the scripts' loader: its jars over the app's, the scripts over both
    private fun scriptsLoader(root: File, code: String): ClassLoader {
        val plan = EnvPlan.scan(code)
        val env = ScriptEnvironments.open(plan.envName, plan.activeProfile)
        env.source(plan)
        val envLoader = env.classLoader(ScriptEngineCache.classLoader)
        return URLClassLoader("amie-bundle-scripts", arrayOf(File(root, "${ScriptExporter.LIB}/${ScriptExporter.SCRIPTS_JAR}").toURI().toURL()), envLoader)
    }

    private fun timed(what: String, block: () -> String?): Boolean {
        val startedAt = Instant.now()
        logToConsole(ResponseDto(time = startedAt, message = "Running $what", level = LogLevel.INTERNAL, source = SANDBOX_SOURCE))
        val meter = RunMeter().start()
        val failure = block()
        val took = Duration.between(startedAt, Instant.now()).toMillis()
        logToConsole(ResponseDto(
            time = Instant.now(),
            message = if (failure == null) "Finished in $took ms" else "Failed after $took ms",
            level = LogLevel.INTERNAL,
            source = SANDBOX_SOURCE,
        ))
        // What it started in the background goes on after it, as in the app, and a failed one counts as failing
        val background = linkedSetOf<TemplateRun>()
        while (true) {
            val running = TemplateRuns.active
            if (running.isEmpty()) break
            background += running
            // Nothing is left to feed one that waits for input, and waiting for it would keep this java up for good
            running.forEach { it.closeIfUnfed() }
            Thread.sleep(50)
        }
        val backgroundFailed = background.any { it.state.failed }
        // What the run used, the lines the app's console prints when a run ends; on standard error, since standard out
        // is the script's own and may be piped
        meter.stop().lines().forEach(System.err::println)
        // A model left serving is what the script was for: it answers until the process is told to stop
        if (ModelServer.anyListening) {
            flushLogFile()
            val stopped = CountDownLatch(1)
            Runtime.getRuntime().addShutdownHook(Thread { ModelServer.stopAll(); stopped.countDown() })
            System.err.println("Serving. Stop with Ctrl-C or SIGTERM.")
            stopped.await()
        }
        return failure != null || backgroundFailed
    }

    /** The files require.txt names that are not in place: under the bundle, or under the AMP folder the run was given */
    private fun lacking(root: File, amp: File, manifest: BundleManifest): List<BundleRequirement> = manifest.required.filter { r ->
        !File(root, r.path).isFile && !File(amp, r.path.removePrefix("${ScriptExporter.AMP}/")).isFile
    }

    private fun check(root: File, manifest: BundleManifest, amp: File, code: String, rewrite: (String) -> String, missing: List<BundleSecret>, lacking: List<BundleRequirement>): Boolean {
        var ok = true
        fun line(what: String, passed: Boolean, detail: String) {
            println("${if (passed) "ok " else "NO "} ${what.padEnd(9)} $detail")
            ok = ok && passed
        }
        val java = Runtime.version().feature()
        line("java", java in manifest.javaMin..manifest.javaMax, "${System.getProperty("java.version")} (${manifest.javaRange})")
        val damaged = manifest.files.filter { it.path.startsWith("${ScriptExporter.LIB}/") || it.path.startsWith("models/") }
            .filter { File(root, it.path).length() != it.size }
        line("files", damaged.isEmpty(), if (damaged.isEmpty()) "the jars and models are all there" else "wrong size or missing: ${damaged.joinToString { it.path }}")
        line("AMP", amp.canWrite(), getDirectory().toString())
        if (manifest.required.isNotEmpty()) line("jars", lacking.isEmpty(), if (lacking.isEmpty()) "the ${manifest.required.size} files ${ScriptExporter.REQUIRE_FILE} names are in place" else "not in place: ${lacking.joinToString { it.path }} (${ScriptExporter.REQUIRE_FILE})")
        if (manifest.encoders.isNotEmpty() || manifest.models.isNotEmpty() || manifest.nerModel) {
            val native = runCatching { OrtSessions.env }
            line("natives", native.isSuccess, native.fold({ "ONNX Runtime loads on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}" }, { "ONNX Runtime doesn't load: ${it.message}" }))
            manifest.encoders.forEach { line("encoder", File(BundledModels.root, "$it/model.onnx").isFile, File(BundledModels.root, it).path) }
        }
        if (manifest.secrets.isEmpty()) line("secrets", true, "none needed")
        manifest.secrets.forEach { secret ->
            val given = Secrets.get(secret.name) != null
            println("${if (given) "ok " else if (secret.required) "NO " else "-- "} ${"secret".padEnd(9)} ${secret.name}${if (given) "" else if (secret.required) " is not set: ${secret.why}" else " (optional)"}")
        }
        ok = ok && missing.isEmpty()
        if (manifest.compiled) {
            val loaded = runCatching {
                val loader = scriptsLoader(root, code)
                manifest.classes.map { (entry, className) -> loader.loadClass(className); entry }
            }
            line("classes", loaded.isSuccess, loaded.fold({ "load: ${it.joinToString()}" }, { "don't load: $it" }))
        } else {
            val compiled = ScriptRunner.compile(code, rewrite)
            line("script", compiled == null, compiled ?: "compiles")
        }
        manifest.missing.forEach { println("--  missing   $it") }
        return !ok
    }

    private fun close(failed: Boolean) {
        runCatching { Databases.closeAll() }
        runCatching { JarPackages.closeAll() }
        flushLogFile()
        exitProcess(if (failed) 1 else 0)
    }
}
