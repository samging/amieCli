package org.example.project.components.terminal.window.controller.script

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.window.controller.envBuilder.BuildSettings
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironment
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.envBuilder.SettingsEnv
import org.example.project.components.terminal.window.controller.pluginEngine.JarInterpreter
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.data.remote.parser.CorePluginObject
import org.example.project.getDirectory
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.ResponseDto
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.console.logToConsole
import org.example.project.components.terminal.window.controller.sandbox.SCRIPT_SOURCE
import java.io.File
import java.nio.file.Path
import java.time.Instant
import javax.script.ScriptEngine

/**
 * The script API as a run of main.kts gets it: what ScriptLogger leaves open, on the console and the app's builders.
 * [env] is the env the run was sourced in; [gradleEngine] and [scriptScope] take the Gradle build settings it hands over.
 * A compiled bundle has no engine, so [gradleEngine] is null there and build settings are refused with a line.
 */
internal class SandboxLogger(
    private val env: ScriptEnvironment,
    private val gradleEngine: ScriptEngine?,
    private val scriptScope: CoroutineScope,
) : ScriptLogger {

    override fun log(message: String, level: LogLevel) {
        logToConsole(ResponseDto(time = Instant.now(), message = message, level = level, source = SCRIPT_SOURCE))
    }

    override fun input(): String? = ScriptInput.next()

    override fun initAmie(text: String): List<String> {
        val result = org.example.project.components.terminal.integrations.amiePilot.initAmie(text)
        logToConsole(ResponseDto(time = Instant.now(), message = result.joinToString("\n"), source = SCRIPT_SOURCE))
        return result
    }
    override  fun initMod() : InteractionModel {
        return org.example.project.components.terminal.integrations.amiePilot.InitMod().create()
    }
    override fun forwardContext(text: String, tokens: (matrix: LongArray) -> Unit): Array<Array<FloatArray>>? =
        org.example.project.components.terminal.integrations.amiePilot.initContext(text, tokens)

    override fun validateMetrics(
        modelPath: Path,
        train: Path,
        test: Path) {
        logToConsole(ResponseDto(time = Instant.now(), message = "validateMetrics", source = SCRIPT_SOURCE))
    }

    override fun implementation(it: BuildSettings) {
        val engine = gradleEngine
        if (engine == null) {
            logToConsole(ResponseDto(time = Instant.now(), message = "implementation: a compiled bundle takes no build settings when it runs; its env was settled when it was exported", level = LogLevel.ERROR, source = SCRIPT_SOURCE))
            return
        }
        scriptScope.launch {
            try {
                engine.eval(it.toString())
            } catch (e: Exception) {
                logToConsole(
                    ResponseDto(
                        time = Instant.now(),
                        message = "Error: ${e.message}",
                        level = LogLevel.ERROR,
                        source = SCRIPT_SOURCE
                    )
                )
            }
        }
        logToConsole(ResponseDto(time = Instant.now(), message = "implementation $it ", source = SCRIPT_SOURCE))
    }

    // Selecting an env is read before the run; at runtime it only reports what was sourced
    override fun import(it: SettingsEnv) {
        if (ScriptEnvironments.current?.name == it.envName) {
            logInternal("env '${it.envName}' (profile ${it.activeProfile}) is the sourced environment")
        } else {
            logInternal("env '${it.envName}' must be selected with a literal environment(SettingsEnv(\"${it.envName}\", ...)) or amie.import(SettingsEnv(...)) to be sourced", LogLevel.ERROR)
        }
    }

    override fun import(notation: String) {
        ScriptEnvironments.current?.import(notation)
        logToConsole(ResponseDto(time = Instant.now(), message = "import $notation", source = SCRIPT_SOURCE))
    }

    override fun internalEnv(): List<String> = env.print()

    // Runs a plugin jar's static run() (or main). Nothing here throws: a missing jar, a jar without an
    // entry point or a plugin that fails is one ERROR line, and the script goes on
    override fun importPlugin(plugin: String) {
        // AMP/pluginDir/corePlugins.json names the core plugin set; absent on a fresh checkout
        val file = File(getDirectory().toString(), "pluginDir/corePlugins.json")
        if (file.isFile) runCatching { logToConsole(ResponseDto(time = Instant.now(), message = Json.decodeFromString<CorePluginObject>(file.readText()).name)) }
            .onFailure { logToConsole(ResponseDto(time = Instant.now(), message = "importPlugin: core plugins not read from ${file.path}: ${it.message}", level = LogLevel.ERROR, source = SCRIPT_SOURCE)) }
        else logToConsole(ResponseDto(time = Instant.now(), message = "importPlugin: no ${file.path}, only the jar is run", source = SCRIPT_SOURCE))
        val found = JarPackages.resolve(plugin)
        if (found == null) {
            logToConsole(ResponseDto(time = Instant.now(), message = "importPlugin: $plugin not found in the env, AMP/pluginDir, as a path or on the server", level = LogLevel.ERROR, source = SCRIPT_SOURCE))
            return
        }
        logToConsole(ResponseDto(time = Instant.now(), message = "importPlugin ${found.first.name} (${found.second})", source = SCRIPT_SOURCE))
        val result = runCatching { JarInterpreter.shared.run(found.first.absolutePath) }.getOrElse { e ->
            logToConsole(ResponseDto(time = Instant.now(), message = "importPlugin: ${found.first.name} failed: ${e.message ?: e.javaClass.simpleName}", level = LogLevel.ERROR, source = SCRIPT_SOURCE))
            return
        }
        logToConsole(ResponseDto(
            time = Instant.now(),
            message = if (result == null) "importPlugin: ${found.first.name} returned nothing (a failure is an Error line above)" else "importPlugin: ${found.first.name} -> $result",
            source = SCRIPT_SOURCE,
        ))
    }

    override fun modelBuilder(modelName: String, options: org.example.project.components.terminal.window.controller.jetbrainDLBuilder.BuildOptions, it: (Any) -> Any): ModelCreationalInterface {
        // The lambda supplies labeled data: sourceFile rows of [text, label], List<Pair<text, label>> or Map<label, texts>
        // or amie.source("train.csv"), whose related test file becomes the validation set
        val produced = it(Pair(0, emptyList<String>()))
        val samples = org.example.project.components.terminal.window.controller.jetbrainDLBuilder.parseLabeledData(produced)
        val test = (produced as? org.example.project.components.terminal.window.controller.sourceFile.DataSource)?.related
        val exported = try {
            org.example.project.components.terminal.window.controller.jetbrainDLBuilder.buildClassifier(
                samples, modelName, validation = test?.samples(), validationName = test?.name,
                dataName = (produced as? org.example.project.components.terminal.window.controller.sourceFile.DataSource)?.name,
                options = options,
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            e.cause?.printStackTrace()
            logInternal("modelBuilder failed: ${e.cause?.message}", LogLevel.ERROR)
            null
        } catch (e: Throwable) {
            e.printStackTrace()
            logInternal("modelBuilder failed: ${e.message}", LogLevel.ERROR)
            null
        }
        if (exported != null) {
            logToConsole(ResponseDto(time = Instant.now(), message = "Model '$modelName' saved, load it with amie.initMod(\"$modelName\")", source = SCRIPT_SOURCE))
        }
        return object : ModelCreationalInterface() {
            override val modelName: String? = if (exported != null) modelName else null
        }
    }
}
