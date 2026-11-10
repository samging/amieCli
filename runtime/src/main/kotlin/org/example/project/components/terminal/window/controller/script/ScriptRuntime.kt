package org.example.project.components.terminal.window.controller.script

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.example.project.components.terminal.window.controller.envBuilder.DEFAULT_PROFILE
import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironment
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.runtime.ScriptRuns
import org.example.project.getDirectory
import org.example.project.components.terminal.window.controller.ResponseDto
import org.example.project.components.terminal.window.controller.console.logToConsole
import org.example.project.components.terminal.window.controller.logger
import org.example.project.components.terminal.window.controller.sandbox.SANDBOX_SOURCE
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import javax.script.Compilable
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

/*
 * What a run of main.kts needs, apart from the sandbox that shows it: the script engine and the assembled script, and
 * ScriptRunner, the sandbox's Run with no window for a bundle's runner. Nothing here touches Compose: a bundle has none
 * on its class path.
 */

object ScriptEngineCache {
    private val appClassLoader: ClassLoader = ScriptEngineCache::class.java.classLoader

    private val manager by lazy {
        // ClassLoader lookup avoids ClassLoader contention with the UI thread
        ScriptEngineManager(appClassLoader)
    }

    val engine: ScriptEngine by lazy {
        manager.getEngineByExtension("kts")
            ?: manager.getEngineByName("kotlin")
            ?: error("Couldn't find Kotlin script engine")
    }

    // Shares the same cached manager instance
    val gradleEngine: ScriptEngine by lazy {
        manager.getEngineByExtension("kts")
            ?: manager.getEngineByName("kotlin")
            ?: engine
    }

    private val envEngines = mutableMapOf<ClassLoader, ScriptEngine>()

    // The engine is one REPL: a run that evaluated while the warm-up did mixed up their bindings. The warm-up evaluates
    // under this lock, and only while no run has asked for an engine yet
    private val warmUp = Any()
    private var taken = false

    // The engine compiles against the context classloader it was created under, so each env classpath gets its own
    @Synchronized
    fun engineFor(loader: ClassLoader): ScriptEngine {
        synchronized(warmUp) { taken = true }
        if (loader === appClassLoader) return engine
        return envEngines.getOrPut(loader) {
            withContextClassLoader(loader) {
                ScriptEngineManager(loader).getEngineByExtension("kts") ?: error("Couldn't find Kotlin script engine")
            }
        }
    }

    val classLoader: ClassLoader get() = appClassLoader

    /**
     * Makes the engine and runs one line through it on a daemon thread at the lowest priority, so the first Run finds
     * the compiler loaded instead of waiting for it (seconds on a slow machine; what it saves and costs is in
     * docs/troubleshooting.md, slow hardware). The line gets bindings of its own, disposed after, so nothing of it
     * stays in the engine a run then uses. A run waits for it to end, and once a run has its engine the warm-up does nothing, as they share the engine. Off with `-Damie.prewarm=false`.
     */
    fun prewarm(): Thread? {
        if (System.getProperty("amie.prewarm") == "false") return null
        return Thread({
            synchronized(warmUp) {
                if (!taken) runCatching {
                    val bindings = engine.createBindings()
                    engine.eval("1 + 1", bindings)
                    TemplateRuns.releaseEngine(bindings)
                }
            }
        }, "amie-script-prewarm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }
}

fun <T> withContextClassLoader(loader: ClassLoader, block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    thread.contextClassLoader = loader
    try {
        return block()
    } finally {
        thread.contextClassLoader = previous
    }
}

/** Goes up each time something a script ran failed without failing the run, so the console turns its error level on */
internal val consoleErrorAsks = MutableStateFlow(0)

// An import line, a trailing // comment allowed
private val importDirective = Regex("""^\s*import\s+[\w.]+(\.\*)?(\s+as\s+\w+)?\s*(//.*)?$""")

/**
 * In scope in every script without an import line: the console levels for log("…", LogLevel.DATA), Tensor for
 * m.run(mapOf("x" to Tensor.of(values, 1, 3))) on a model from a file, and info() of a data source with no !!:
 * source("x.csv").info()
 */
private val defaultImports = listOf(
    "org.example.project.components.terminal.window.controller.LogLevel",
    "org.example.project.components.terminal.integrations.amiePilot.Tensor",
    "org.example.project.components.terminal.window.controller.sourceFile.info",
)

/** The lines of [code] that assembleScript moves up to the top of the header, in the header's order */
internal fun movedImports(code: String): List<MovedImport> =
    code.lines().withIndex().filter { importDirective.matches(it.value) }.map { (i, line) -> MovedImport(i + 1, line.length - line.trimStart().length) }

/**
 * What the engine evaluates: the user's import directives first (they must lead the file), then the ScriptLogger
 * import and `val amie`, then the SandboxApi prelude so the API is callable without the prefix, then the rest of
 * the user's code with its import lines blanked so its line numbers keep their spacing. main.kts stays the raw code.
 * With [providedAmie] there is no `val amie` line: a script compiled to classes for a bundle gets `amie` as a provided
 * property, a parameter of the class's constructor, so the line is one shorter.
 */
internal fun assembleScript(code: String, providedAmie: Boolean = false): String {
    val lines = code.lines()
    val imports = lines.filter { importDirective.matches(it) }
    val body = lines.joinToString("\n") { if (importDirective.matches(it)) "" else it }
    val imported = imports.map { it.trim().removePrefix("import").trim().substringBefore("//").trim() }
    return buildString {
        imports.forEach { append(it.trim()).append('\n') }
        defaultImports.filter { it !in imported && "${it.substringBeforeLast('.')}.*" !in imported }.forEach { append("import ").append(it).append('\n') }
        append("import org.example.project.components.terminal.window.controller.script.ScriptLogger\n")
        if (!providedAmie) append("val amie = bindings[\"logger\"] as ScriptLogger\n")
        append(SandboxApi.prelude())
        // environment(SettingsEnv("name")) as the scripting guide writes it: the class by its bare name, the profile optional
        append("fun SettingsEnv(envName: String, activeProfile: String = \"$DEFAULT_PROFILE\") = ")
        append("org.example.project.components.terminal.window.controller.envBuilder.SettingsEnv(envName, activeProfile)\n")
        append(body)
    }
}

internal object ScriptRunner {
    private val logger = LoggerFactory.getLogger("CodingSandbox")

    /**
     * One run of [code] as main.kts, what the sandbox's Run does, with no window: the env sourced with the script's
     * imports before it compiles, then the script on the calling thread. Null when it finished, else its first error
     * in one line; every error is a console line by then. [rewrite] is what a bundle does to the text it evaluates,
     * line for line, with main.kts itself left as it is.
     */
    fun run(code: String, rewrite: (String) -> String = { it }): String? {
        val pluginPath = File(getDirectory().toString(), "pluginDir/main.kts")
        val assemblyPath = File(getDirectory().toString(), "pluginDir/assembly.kts")
        // How many lines assembleScript puts before the script's own, so an error's place is told in the script's lines
        var header = 0

        try {
            // Configuration phase: source the env with the script's imports before it compiles, as Gradle resolves before it runs
            val plan = EnvPlan.scan(code)
            val env = ScriptEnvironments.open(plan.envName, plan.activeProfile)
            env.source(plan)
            val envLoader = env.classLoader(ScriptEngineCache.classLoader)
            val engine = ScriptEngineCache.engineFor(envLoader)
            val loggerObj = SandboxLogger(env, ScriptEngineCache.gradleEngine, CoroutineScope(Dispatchers.IO))

            val scriptBindings = engine.createBindings().apply {
                put("logger", loggerObj)
            }

            val assembled = assembleScript(rewrite(code))
            header = ScriptErrors.headerLines(code, assembled)
            assemblyPath.writeText(assembled)
            try {
                // Execution phase: env packages are loaded through the env classloader
                withContextClassLoader(envLoader) {
                    ScriptRuns.track(code) { engine.eval(assemblyPath.readText(), scriptBindings) }
                }
            } finally {
                assemblyPath.writeText("")
            }
            return null
        } catch (e: Exception) {
            // One line per thing it got wrong, each beginning with its place in main.kts when it has one (the
            // engine names lines of assembly.kts); before the script was assembled nothing has a place
            val problems = ScriptErrors.problems(e, header, lines = if (header > 0) code.lines().size else 0, imports = if (header > 0) movedImports(code) else emptyList())
            logger.error("Error running script: ${pluginPath}| ${e.message}")
            problems.forEach { p ->
                logToConsole(ResponseDto(
                    time = Instant.now(),
                    message = p.consoleLine,
                    level = p.level,
                    source = SANDBOX_SOURCE,
                    line = p.line,
                    column = p.column,
                ))
            }
            return ScriptErrors.summary(problems)
        }
    }

    /**
     * One run of [code] compiled to [scriptClass], what a compiled bundle does instead of [run]: the class was made
     * from assembleScript(code, providedAmie = true), so its constructor takes the ScriptLogger and runs the script.
     * [env] is sourced already and is the parent of the class's loader. Null when it finished, else its first error
     * in one line, the places named in [file]'s lines; every error is a console line by then.
     */
    fun runCompiled(code: String, scriptClass: Class<*>, env: ScriptEnvironment, file: String = "main.kts"): String? {
        val assembled = assembleScript(code, providedAmie = true)
        val header = ScriptErrors.headerLines(code, assembled)
        return try {
            val loggerObj = SandboxLogger(env, null, CoroutineScope(Dispatchers.IO))
            withContextClassLoader(scriptClass.classLoader) {
                ScriptRuns.track(code) { instantiate(scriptClass, loggerObj) }
            }
            null
        } catch (e: Exception) {
            val problems = ScriptErrors.problems(e, header, lines = code.lines().size, imports = movedImports(code))
            logger.error("Error running compiled script $file: ${e.message}")
            problems.forEach { p ->
                logToConsole(ResponseDto(time = Instant.now(), message = if (file == "main.kts") p.consoleLine else p.textIn(file), level = p.level, source = SANDBOX_SOURCE, line = p.line, column = p.column))
            }
            ScriptErrors.summary(problems, file)
        }
    }

    /** Runs a compiled script: its one constructor takes `amie`, the ScriptLogger, and the script's body is the constructor */
    fun instantiate(scriptClass: Class<*>, amie: ScriptLogger): Any = scriptClass.constructors.single().newInstance(amie)

    /** What [run] does before the script's first line, for a bundle's --check: null when it compiles, else its first error */
    fun compile(code: String, rewrite: (String) -> String = { it }): String? {
        var header = 0
        return try {
            val plan = EnvPlan.scan(code)
            val env = ScriptEnvironments.open(plan.envName, plan.activeProfile)
            env.source(plan)
            val envLoader = env.classLoader(ScriptEngineCache.classLoader)
            val assembled = assembleScript(rewrite(code))
            header = ScriptErrors.headerLines(code, assembled)
            withContextClassLoader(envLoader) { (ScriptEngineCache.engineFor(envLoader) as Compilable).compile(assembled) }
            null
        } catch (e: Exception) {
            ScriptErrors.summary(ScriptErrors.problems(e, header, lines = if (header > 0) code.lines().size else 0, imports = if (header > 0) movedImports(code) else emptyList()))
        }
    }
}
