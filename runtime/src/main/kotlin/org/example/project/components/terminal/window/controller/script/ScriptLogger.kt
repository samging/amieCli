package org.example.project.components.terminal.window.controller.script

import org.example.project.components.terminal.window.controller.sandbox.NotOpened
import org.example.project.components.terminal.window.controller.sandbox.ErrorHelp
import kotlinx.coroutines.runBlocking
import org.example.project.backend.AmieBackend
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import java.nio.file.Path
import org.example.project.components.terminal.window.controller.console.logToConsole
import org.example.project.components.terminal.window.controller.ResponseDto
import org.example.project.components.terminal.window.controller.envBuilder.BuildSettings
import org.example.project.components.terminal.window.controller.envBuilder.SettingsEnv
import org.example.project.components.terminal.window.controller.sourceFile.readSourceFile
import java.time.Instant
import org.example.project.components.terminal.window.controller.sourceFile.DataSource
import org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions
import org.example.project.components.terminal.window.controller.modelLoaders.builderImplement.openOnnxModel
import org.example.project.components.terminal.window.controller.modelLoaders.imported.ImportedModels
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.components.terminal.window.controller.runtime.SpeechModels
import org.example.project.components.terminal.window.controller.runtime.AccessLog
import org.example.project.components.terminal.window.controller.modules.MetricsReport
import org.example.project.components.terminal.window.controller.runtime.ModelSessions
import org.example.project.components.terminal.window.controller.modules.ModelGroups
import org.example.project.components.terminal.window.controller.modules.ModelSwarm
import org.example.project.components.terminal.window.controller.huggingFace.HfImports
import org.example.project.components.terminal.window.controller.huggingFace.HuggingFaceScript
import org.example.project.components.terminal.window.controller.runtime.ModelServer
import org.example.project.components.terminal.window.controller.modules.OrtJob
import org.example.project.components.terminal.window.controller.modules.OrtJobs
import org.example.project.components.terminal.window.controller.modules.OrtScopes
import org.example.project.components.terminal.window.controller.runtime.ScriptRuns
import org.example.project.components.terminal.window.controller.modules.SessionMod
import org.example.project.components.terminal.window.controller.modules.SharedModels
import org.example.project.components.terminal.window.controller.runtime.readServing
import org.example.project.components.terminal.window.controller.databases.Database
import org.example.project.components.terminal.window.controller.databases.Databases
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.Architecture
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.BuildOptions
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.EmbeddingCache
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.FineTuneOptions
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.FineTunePlan
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.LabelQueue
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.PickStrategy
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.SEARCH_HIDDEN
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.SEARCH_RATES
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.calibrateClassifier
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.fineTuneClassifier
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.parseLabeledData
import org.example.project.components.terminal.window.controller.modules.ModelVersions
import org.example.project.components.terminal.window.controller.modules.moduleExists
import org.example.project.components.terminal.window.controller.runtime.TextModels
import org.example.project.components.terminal.window.controller.modules.hasModule
import org.example.project.components.terminal.window.controller.modules.readModule
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.planFineTune as workOutFineTune
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.regradeQueue as gradeQueueAgain
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.searchSpace as searchHeads
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.teach as writeCorrection
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.textsToLabel as writeTextsToLabel
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.export.ExportCommand
import java.io.File
import org.example.project.components.terminal.window.controller.export.ExportFailed
import org.example.project.components.terminal.window.controller.export.ExportMode
import org.example.project.components.terminal.window.controller.export.ExportOptions
import org.example.project.components.terminal.window.controller.export.ScriptExporter
import org.example.project.components.terminal.window.controller.console.ErrorTerminal
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.models.planBlocks
import org.example.project.components.terminal.window.controller.models.planLines
import org.example.project.components.terminal.window.controller.models.queueLines

/**
 * A stable interface for logging from scripts.
 * Defined in a separate file to ensure it's a top-level class with stable visibility.
 */
interface ScriptLogger {
    fun log(message: String, level: LogLevel = LogLevel.INFO)
    fun info(message: String) = log(message, LogLevel.INFO)
    fun error(message: String) = log(message, LogLevel.ERROR)
    fun data(message: String) = log(message, LogLevel.DATA)
    /** Any value, as console lines: log(pkg.run("x")) prints every answer. A list or an array is one element a line, a map key: value */
    fun log(message: Any?, level: LogLevel = LogLevel.INFO) = log(consoleText(message), level)
    fun info(message: Any?) = info(consoleText(message))
    fun error(message: Any?) = error(consoleText(message))
    fun data(message: Any?) = data(consoleText(message))
    /**
     * Named entities in the text with the bundled BERT NER model: one tag per token (O, B-PER, I-ORG, …), printed
     * and returned. A one-off: the model is opened and closed for the call. For many texts open it once with initMod()
     */
    fun initAmie(text: String): List<String>
    fun initMod() : InteractionModel
    /**
     * The session of the ONNX module modelBuilder exported to AMP/models/intents/<modelName>: the one already open on it
     * when there is one, else a new one. For a name ending in .jar, a new instance of that package jar every
     * call (env, AMP/pluginDir, a path, or fetched from the Amie server), called with run(text) / answer(text)
     * Whisper and the voices are models too: initMod("whisper") and initMod("voice") are the ones speech.properties
     * names, initMod("whisper-base.en") a folder of its models. .answer(wav) says what was said, .answer(text) where
     * the spoken text is; one session each, held by the block around it and ended by Terminate like any other.
     * After those, a model brought in from outside: initMod("<name>") opens AMP/models/imported/<name>, a Hugging Face
     * repo or a folder copied in, and initMod("/path/model.pt") a model file or folder by its path, as importModel does.
     * ONNX runs on the bundled runtime, TorchScript (.pt, .pth, .torchscript) on PyTorch and a TensorFlow SavedModel
     * folder on TensorFlow, whose natives are downloaded on their first use on a machine; a folder of several formats
     * opens its ONNX, else the format its source.json names, else the first that runs here, and says which. Such a
     * model takes text when a tokenizer.json is next to it, answers the labels of its config.json, and takes tensors:
     * .inputs, .outputs, .run(floatArrayOf(…)), .run(longArrayOf(…)), .run(mapOf("name" to values)). Each call opens a
     * new one. TFLite, frozen graphs and weights alone (safetensors, pytorch_model.bin, gguf, h5) are refused, with
     * what would work.
     * Every kind is listed on the Models page, where any session can be terminated. One that lacks something it
     * needs is refused, with everything it lacks in the message: nothing of it is opened
     */
    fun initMod(modelName: String): InteractionModel {
        val name = modelName.trim()
        if (name.endsWith(".jar", ignoreCase = true)) return OrtScopes.opened(JarPackages.open(name)).also { ScriptRuns.opened("package:${it.id}") }
        if (!hasModule(name) && !TextModels.has(name) && SpeechModels.has(name)) {
            val (model, new) = SpeechModels.acquire(name)
            OrtScopes.opened(model.session, new)
            ScriptRuns.opened("package:${model.session.id}")
            return model
        }
        if (!hasModule(name) && !TextModels.has(name)) {
            if (ImportedModels.has(name)) return ImportedModels.held(ImportedModels.open(name = name))
            if (ImportedModels.looksLikePath(name)) return ImportedModels.held(ImportedModels.open(path = ImportedModels.expand(name)))
        }
        val (session, opened) = ModelSessions.acquire(name)
        return OrtScopes.opened(session, opened).also { ScriptRuns.opened("model:${it.id}") }
    }
    /**
     * Whether AMP has a model of that name, built, a text model, a speech model or one imported into
     * AMP/models/imported: if (!hasModel("x")) modelBuilder("x", data)
     */
    fun hasModel(modelName: String): Boolean = TextModels.has(modelName) || hasModule(modelName) || SpeechModels.has(modelName) || ImportedModels.has(modelName)
    /**
     * A model to ask and nothing more: a snapshot of the head it has now, which answers with .predict(text),
     * .scores(text), .run(text), .answer(text), .predictions(text). It trains nothing, changes nothing and can't be
     * closed from under anyone; it needs no session of the model, leaves one that is open alone and stays on its head
     * when the model is trained again. What asks for the same head shares one snapshot, and only the encoder is shared
     * with the sessions. It goes when the run ends, or earlier with .close(); `val m by sessionMod("name")` works too
     */
    fun sessionMod(modelName: String): SessionMod = ScriptRuns.keep(SharedModels.take(modelName))
    /**
     * The same for one block, in which the model's functions are at hand: sessionMod("name") { predict("text") }.
     * It goes when the block ends; returns the block's value
     */
    fun <T> sessionMod(modelName: String, block: SessionMod.() -> T): T = sessionMod(modelName).use(block)
    /** sessionMod under the name it had before: a saved script still runs, and the console says the new name */
    @Deprecated("sharedModel is sessionMod now", ReplaceWith("sessionMod(modelName)"))
    fun sharedModel(modelName: String): SessionMod {
        log("sharedModel is called sessionMod now; this script still runs, write sessionMod(\"$modelName\") when you next edit it")
        return sessionMod(modelName)
    }
    @Deprecated("sharedModel is sessionMod now", ReplaceWith("sessionMod(modelName, block)"))
    fun <T> sharedModel(modelName: String, block: SessionMod.() -> T): T {
        log("sharedModel is called sessionMod now; this script still runs, write sessionMod(\"$modelName\") { } when you next edit it")
        return sessionMod(modelName, block)
    }
    /**
     * Package jar sessions: packages.available(), packages.fetch("x.jar"), packages.print(), packages.closeAll();
     * packages.meta("x.jar") what the jar says it needs, packages.test("x.jar") runs the tests it declares
     */
    val packages: JarPackages get() = JarPackages
    /**
     * The swarm of a model group of the env (the one open on it, else opened now): the models asked together.
     * .predict(text) is the label with the best score across them, .scores(text) each label's best score,
     * .each(text) every model's own label, .vote(text) the label most models give, .ask(text) every raw answer
     */
    fun group(name: String): ModelSwarm = ModelGroups.acquire(name).let { (swarm, opened) -> OrtScopes.opened(swarm, opened) }.also { ScriptRuns.opened("group:${it.id}") }
    /**
     * The model groups of the envs: groups.print(), groups.names(), groups.create("name", listOf("a", "b")),
     * groups.add("name", "model"), groups.remove("name", "model"), groups.delete("name"), groups.closeAll()
     */
    val groups: ModelGroups get() = ModelGroups
    /**
     * A model of the Hugging Face Hub as AMP/models/imported/<name>, by its repo id (owner/name) or its huggingface.co
     * address; returns the name, so initMod(huggingFace("owner/repo")) opens it. The first call downloads what the
     * Models page would pick, a plain model.onnx when there is one, with the config and tokenizer files beside it; once
     * the folder is there a call asks nothing of the network. [name] is the folder's name, blank for the repo's last
     * part. A private or gated repo needs HF_TOKEN in the app's environment. A repo that can't be read, has nothing
     * that runs here or doesn't fit on the disk is refused with the reason in the message
     */
    fun huggingFace(repo: String, name: String = ""): String = HfImports.ensure(repo, name, say = { log(it) })
    /**
     * The Hub searched for models initMod opens: huggingFace.search("bert", format = "onnx") the repo ids, the most
     * downloaded first, each printed with its task, downloads and formats; format is onnx, torchscript, tensorflow,
     * tflite or all. An id goes on to huggingFace("…")
     */
    val huggingFace: HuggingFaceScript get() = HuggingFaceScript { log(it) }
    /**
     * Saved states of the signed-in user on the Amie backend (uploads/<user>/state/<name>.json): any JSON text under
     * a name matching [A-Za-z0-9._-]{1,64}. Blocking calls like a package fetch; a failure is one ERROR line
     */
    fun saveState(name: String, json: String) {
        runCatching { runBlocking { AmieBackend.saveState(name, json) } }
            .onSuccess { log("saveState: '$name' stored on the backend (${json.length} chars)") }
            .onFailure { log("saveState: '$name' not stored: ${it.message}", LogLevel.ERROR) }
    }
    fun loadState(name: String): String? =
        runCatching { runBlocking { AmieBackend.loadState(name) } }
            .onSuccess { log(if (it == null) "loadState: no state '$name' on the backend" else "loadState: '$name' read from the backend (${it.length} chars)") }
            .onFailure { log("loadState: '$name' not read: ${it.message}", LogLevel.ERROR) }
            .getOrNull()
    fun listStates(): List<String> =
        runCatching { runBlocking { AmieBackend.listStates() } }
            .onSuccess { log("listStates: ${it.size} saved state(s) on the backend" + (if (it.isEmpty()) "" else ": " + it.joinToString(", "))) }
            .onFailure { log("listStates: not read: ${it.message}", LogLevel.ERROR) }
            .getOrDefault(emptyList())

    /**
     * Default no-op implementations for optional script actions
     * A fixed ONNX model with initMod's interface (inference, getStatus, close); its session is managed by OrtSessions
     */
    fun importModelOnnx(it: Path): InteractionModel? = runCatching { openOnnxModel(it) }.getOrNull()?.let { model ->
        OrtSessions.find("onnx:${model.name}").lastOrNull()?.let { session -> ScriptRuns.opened("ort:${session.id}") }
        OrtScopes.opened(model, "ONNX ${model.name}") { model.ortSession }
    }
    fun importModelOnnx(path: String): InteractionModel? = importModelOnnx(Path.of(path))
    /**
     * Any model by its path, with initMod's interface: an .onnx file, a TorchScript file (.pt, .pth, .torchscript), a
     * TensorFlow SavedModel folder (saved_model.pb and variables/), or a folder holding one, such as a Hugging Face repo
     * with onnx/model.onnx. It takes text when a tokenizer.json is next to it or a folder up, answers the labels of its
     * config.json, and takes tensors: .inputs and .outputs say what, .run(floatArrayOf(…)), .run(longArrayOf(…)) and
     * .run(mapOf("name" to values)) run it. PyTorch's and TensorFlow's natives are downloaded on their first use on a
     * machine, which the console says. A new model every call, closed with the block around it, listed on the
     * Sessions tab with Terminate. Never throws: what can't be opened (TFLite, a frozen graph, weights alone, no
     * natives for this machine) is one error line with what would work, and null
     */
    fun importModel(path: Path): InteractionModel? = runCatching { ImportedModels.held(ImportedModels.open(path = path.toFile())) }
        .onFailure { log("importModel: ${it.message}", LogLevel.ERROR) }
        .getOrNull()
    fun importModel(path: String): InteractionModel? = importModel(ImportedModels.expand(path).toPath())
    /** Global ORT sessions: sessions.print(), sessions.cancel("name"), sessions.cancelAll() */
    val sessions: OrtSessions get() = OrtSessions
    /** Internal reader of every open ORT session: inputs, outputs, runs and whether it is busy */
    fun ortSessions(): List<String> = OrtSessions.print()
    /**
     * What the block opens is for that block alone: closed when it ends, however it ends, the last opened first, so a
     * handle kept past it fails as closed. A session open before the block is used and left open. Returns the block's value
     */
    fun <T> ortClosable(name: String = "closable", block: () -> T): T = OrtScopes.closable(name, block)
    /**
     * ortClosable run in the background: the block runs on a thread of its own and the call returns a job at once.
     * What the block opens is closed when it ends, however it ends, and a template started in it ends with it. The job
     * ends with the script, however that ends, and with an ortClosable block around the call: .await() is the block's
     * value and rethrows its failure, .await(millis) waits that long at most (null if it hasn't ended), .done, .state, .cancel()
     */
    fun <T> ortAsync(name: String = OrtJobs.DEFAULT_NAME, block: () -> T): OrtJob<T> = OrtJobs.start(name, block)
    /**
     * What the block opens stays open across runs until it is terminated or freed, and a session open before the block
     * is kept too, also from a closable around it. Returns the block's value
     */
    fun <T> ortStatic(name: String = "static", block: () -> T): T = OrtScopes.static(name, block)
    /**
     * Kept like ortStatic while anything else is in session: a model, package, group, template or background job the
     * required scopes don't hold themselves, the encoders models share not counted. Once nothing else is open, what it holds is freed, and the block opens it again when next run: for what
     * other models need beside them, an encoder they share, say. Returns the block's value
     */
    fun <T> ortRequired(name: String = "required", block: () -> T): T = OrtScopes.required(name, block)
    /**
     * Kept like ortStatic, at most max sessions under the name: when one more is opened the oldest is closed first.
     * Returns the block's value
     */
    fun <T> ortLimited(name: String, max: Int, block: () -> T): T = OrtScopes.limited(name, max, block)
    /** ortStatic under the name it had before: a saved script still runs, and the console says the new name */
    @Deprecated("ortForever is ortStatic now", ReplaceWith("ortStatic(name, block)"))
    fun <T> ortForever(name: String = "static", block: () -> T): T {
        log("ortForever is called ortStatic now; this script still runs, write ortStatic(\"$name\") { } when you next edit it")
        return ortStatic(name, block)
    }
    /**
     * What the static, timed and limited blocks keep: ortScopes.print(), ortScopes.free("name"), ortScopes.freeAll()
     */
    val ortScopes: OrtScopes get() = OrtScopes
    /**
     * Runs the block and returns its value; when it throws, handler's value for what it threw instead, and the run goes
     * on. A stop, a cancel and an Error such as OutOfMemoryError are never handled: they go on as they would
     */
    fun <T> handleBy(handler: (Throwable) -> T, block: () -> T): T = ScriptStops.handleBy(handler, block)
    /**
     * What the local text model (AMP/models/text) makes of an error: what is wrong and how to fix it, from [message],
     * main.kts and the env, printed and returned. It only answers: the script stays as it is. Without a text model, or
     * short of RAM to open one, an ERROR line says why and the answer is ""
     */
    fun handleBy(message: String): String = handleBy(listOf(message))
    /**
     * The same for error lines, as errorLog() gives them: the last ten go to the model, with main.kts and the env.
     * With no lines it says so and asks nothing
     */
    fun handleBy(lines: List<String>): String {
        if (lines.isEmpty()) {
            log("handleBy: the error terminal is empty, nothing to ask about")
            return ""
        }
        val model = ErrorHelp.model() ?: run {
            log("handleBy: no text model in AMP/models/text to ask; add one, such as Qwen 2.5 1.5B as an ONNX Runtime GenAI export", LogLevel.ERROR)
            return ""
        }
        log(if (ErrorHelp.isOpen(model)) "handleBy: asking $model" else "handleBy: opening $model to ask it; the first answer takes longer", LogLevel.INTERNAL)
        val answer = try { ErrorHelp.ask(model, lines) } catch (e: NotOpened) {
            log("handleBy: ${e.message}", LogLevel.ERROR)
            return ""
        }
        log(answer)
        return answer
    }
    /** The error terminal's last lines handed to the local text model: handleBy() after a command or a run failed, and the console says why */
    fun handleBy(): String = handleBy(errorLog())
    /**
     * The error terminal's log: the last error lines the console showed, oldest first, as the console has them: a
     * run's failures, what a command printed on stderr and why it failed, the app's own error lines. Emptied with the
     * console, which a run does when it starts, so they are the run's. handleBy() hands them to the local text model
     */
    fun errorLog(last: Int = 20): List<String> = ErrorTerminal.lines(last)
    /** The same for what was thrown, by its type, message and line: handleBy({ e -> handleBy(e) }) { … } asks about what the block threw */
    fun handleBy(error: Throwable): String = handleBy(ErrorHelp.describe(error))
    /** importModel under the name it had for TensorFlow alone: a saved script still runs, and the console says the new name */
    @Deprecated("importModelTensorflow is importModel now", ReplaceWith("importModel(it)"))
    fun importModelTensorflow(it: Path): InteractionModel? {
        log("importModelTensorflow is importModel now, which opens TensorFlow, PyTorch and ONNX alike; this script still runs, write importModel(...) when you next edit it")
        return importModel(it)
    }
    /**
     * Runs text through the context model; tokens receives the input ids, returns last_hidden_state [batch][token][hidden]
     */
    fun forwardContext(text: String, tokens: (matrix: LongArray) -> Unit = {}): Array<Array<FloatArray>>? = null
    fun validateMetrics(modelPath: Path, train: Path, test: Path) {}
    /**
     * Reads the last stored validation of AMP/models/intents/<modelName> and prints it; labels narrows the table to those labels
     */
    fun validateMetrics(modelName: String, labels: List<String> = emptyList()): MetricsReport =
        MetricsReport.load(modelName).print(labels)
    /** ASCII bar chart of the stored validation: accuracy, macro F1 and the F1 of each label (or the given ones) */
    fun metricsChart(modelName: String, labels: List<String> = emptyList()): MetricsReport =
        MetricsReport.load(modelName).chart(labels)
    /** ASCII line chart of accuracy and macro F1 across every build of the model */
    fun metricsHistory(modelName: String): MetricsReport = MetricsReport.load(modelName).history()
    /** The last validation's confusion matrix as an ASCII grid */
    fun confusionMatrix(modelName: String): MetricsReport = MetricsReport.load(modelName).confusion()
    /** The validation rows the last run got wrong, the surest first (their texts only when the model keeps texts) */
    fun mistakes(modelName: String): MetricsReport = MetricsReport.load(modelName).mistakes()
    /**
     * The training rows whose label the heads that didn't see them doubt; a build with buildOptions(folds = 5) finds them
     */
    fun suspects(modelName: String): MetricsReport = MetricsReport.load(modelName).suspects()
    /**
     * How far the scores can be trusted: how often each score bin was right, the calibration error, what a score gate keeps
     */
    fun calibrationChart(modelName: String): MetricsReport = MetricsReport.load(modelName).calibration()
    /** Loss and accuracy by epoch of the chosen head of the last build, or of the last fine-tune */
    fun trainingCurves(modelName: String): MetricsReport = MetricsReport.load(modelName).curves()
    fun implementation(it: BuildSettings) {}
    fun import(it: SettingsEnv) {}
    /**
     * Gradle-style dependency, "group:module:version" or a jar file name; literal calls are sourced before the script compiles
     */
    fun import(notation: String) { ScriptEnvironments.current?.import(notation) ?: logInternal("import: no environment is active", LogLevel.ERROR) }
    fun implementation(notation: String) = import(notation)
    /** Prints the env being sourced, what this run sourced and everything in the environment; returns its packages */
    fun internalEnv(): List<String> =
        ScriptEnvironments.current?.print() ?: emptyList<String>().also { logInternal("internalEnv: no environment is active", LogLevel.ERROR) }
    /**
     * Rows of the file keyed by row index (header excluded) with their count; a named column keeps only that cell per row
     */
    fun sourceFile(it: String, column: String = ""): Pair<Int, Map<Int, List<String>>> = readSourceFile(it, column)
    /** Labeled data file with its text/label columns inferred: val train = amie.source("train.csv") */
    fun source(path: String): DataSource? = DataSource.read(path)
    /** Gives the model a test file to validate on after training on train: amie.relate(train, "test.csv") */
    fun relate(train: DataSource?, test: String): DataSource? = train?.relate(test)
        ?: null.also { logInternal("relate: no training source to relate $test to", LogLevel.ERROR) }
    fun modelBuilder(modelName: String, data: DataSource?): ModelCreationalInterface = modelBuilder(modelName, BuildOptions()) { data ?: emptyList<String>() }
    /** The same build with its own options: which heads to try, how to pick, whether to preprocess, the split */
    fun modelBuilder(modelName: String, data: DataSource?, options: BuildOptions): ModelCreationalInterface = modelBuilder(modelName, options) { data ?: emptyList<String>() }
    fun importPlugin(plugin: String) {}
    fun modelBuilder(modelName: String, it: (Any) -> Any): ModelCreationalInterface = modelBuilder(modelName, BuildOptions(), it)
    fun modelBuilder(modelName: String, options: BuildOptions, it: (Any) -> Any): ModelCreationalInterface = object : ModelCreationalInterface() {}
    /**
     * One head to try: hidden layer widths (none = linear), epochs, batch size, learning rate, Relu or Tanh; then what
     * keeps it from memorising (weight decay, dropout, label smoothing), how the rate moves (constant, cosine, linear,
     * with a warm-up) and early stopping (patience); all off unless given, so a plain head trains as it always has
     */
    fun architecture(hidden: List<Int> = listOf(16), epochs: Int = 150, batchSize: Int = 16, learningRate: Double = 0.01, activation: String = "Relu", weightDecay: Double = 0.0, dropout: Double = 0.0, labelSmoothing: Double = 0.0, schedule: String = "constant", warmupEpochs: Int = 0, patience: Int = 0, name: String? = null): Architecture =
        Architecture(hidden, epochs, batchSize, learningRate, activation, weightDecay, dropout, labelSmoothing, schedule, warmupEpochs, patience, name)
    /**
     * How a build goes: the heads to compare (the four defaults unless given), macroF1 or accuracy to pick by, preprocessing on or off, the validation share;
     * folds cross-validates every head (and finds rows that look mislabeled), calibrate makes the scores honest, cacheEmbeddings keeps the vectors on disk
     */
    fun buildOptions(candidates: List<Architecture> = Architecture.DEFAULT_CANDIDATES, pickBy: String = "macroF1", preprocess: Boolean = true, validationFraction: Double = 0.2, seed: Int = 42, balanceClasses: Boolean = false, keepTexts: Boolean = true, folds: Int = 0, calibrate: Boolean = false, cacheEmbeddings: Boolean = true, keepMistakes: Int = 25): BuildOptions =
        BuildOptions(candidates, pickBy, preprocess, validationFraction, seed, balanceClasses, keepTexts, folds, calibrate, cacheEmbeddings, keepMistakes)
    /**
     * Heads for buildOptions(candidates = ...) from every combination of the values given, or trials of them drawn at random
     */
    fun searchSpace(hidden: List<List<Int>> = SEARCH_HIDDEN, learningRates: List<Double> = SEARCH_RATES, epochs: List<Int> = listOf(150), batchSizes: List<Int> = listOf(16), activations: List<String> = listOf("Relu"), dropouts: List<Double> = listOf(0.0), weightDecays: List<Double> = listOf(0.0), patience: Int = 0, trials: Int = 0, seed: Int = 42): List<Architecture> =
        searchHeads(hidden, learningRates, epochs, batchSizes, activations, dropouts, weightDecays, patience, trials, seed)

    /**
     * Teaches a trained model more without starting over: rows of labels it knows correct and sharpen it, a label it
     * doesn't know is added to its head, and its earlier data is replayed so it keeps what it knew. The data is what
     * modelBuilder takes (source("more.csv"), listOf("text" to "label"), mapOf("label" to texts)). The model before
     * stays in AMP/models/intents/<name>/versions: versions(name) lists them, rollback(name, n) brings one back
     */
    fun fineTune(modelName: String, data: Any?, options: FineTuneOptions = FineTuneOptions()): ModelCreationalInterface {
        val source = data as? DataSource
        val file = runCatching {
            fineTuneClassifier(modelName, parseLabeledData(data), dataName = source?.name, options = options,
                validation = source?.related?.samples(), validationName = source?.related?.name)
        }.onFailure { log("fineTune: '$modelName' was not fine-tuned: ${it.message ?: it.javaClass.simpleName}", LogLevel.ERROR) }.getOrNull()
        if (file != null) log("fineTune: '$modelName' saved, initMod(\"$modelName\") answers with it")
        return object : ModelCreationalInterface() {
            override val modelName: String? = if (file != null) modelName else null
        }
    }
    /**
     * How a fine-tune goes: epochs and rate (lower than a build's), how near the head stays to where it was (stayNear),
     * whether the earlier data is replayed and how much of it, how many first layers keep their weights (freeze)
     */
    fun fineTuneOptions(epochs: Int = 40, learningRate: Double = 0.002, batchSize: Int = 16, stayNear: Double = 0.01, replay: Boolean = true, replayPerLabel: Int = 200, freeze: Int = 0, balanceClasses: Boolean = true, validationFraction: Double = 0.2, seed: Int = 42, preprocess: Boolean = true, patience: Int = 0, calibrate: Boolean = false, cacheEmbeddings: Boolean = true, keepMistakes: Int = 25, minNewLabelRows: Int = 3): FineTuneOptions =
        FineTuneOptions(epochs, learningRate, batchSize, stayNear, replay, replayPerLabel, freeze, balanceClasses, validationFraction, seed, preprocess, patience, calibrate, cacheEmbeddings, keepMistakes, minNewLabelRows)
    /**
     * Fits a temperature on labeled rows the model hasn't seen, so a score of 0.8 is right about 80% of the time; the
     * answers stay the same. A new version, like a fine-tune
     */
    fun calibrate(modelName: String, data: Any?): ModelCreationalInterface {
        val source = data as? DataSource
        val file = runCatching { calibrateClassifier(modelName, parseLabeledData(data), dataName = source?.name) }
            .onFailure { log("calibrate: '$modelName' was not calibrated: ${it.message ?: it.javaClass.simpleName}", LogLevel.ERROR) }.getOrNull()
        return object : ModelCreationalInterface() {
            override val modelName: String? = if (file != null) modelName else null
        }
    }
    /** The model's versions, the current one first, then the earlier heads kept in versions/ (the ten newest) */
    fun versions(modelName: String): List<ModelVersions.Version> {
        val all = ModelVersions.list(modelName)
        if (all.isEmpty()) log("versions: there is no model '$modelName'", LogLevel.ERROR)
        // A rollback's note repeats what its line says; a fine-tune's says what it was fine-tuned on
        else logInternal((listOf("versions '$modelName' · ${all.size} kept") +
            all.map { it.describe() + (it.note?.takeIf { _ -> it.kind != "rollback" }?.let { n -> " · $n" } ?: "") }).joinToString("\n"))
        return all
    }
    /** Makes an earlier version the model again, as a new version (so a rollback can be undone); true when it did */
    fun rollback(modelName: String, version: Int): Boolean =
        runCatching { ModelVersions.rollback(modelName, version) }
            .onSuccess { log("rollback: '$modelName' answers with the head of v$version again") }
            .onFailure { log("rollback: ${it.message ?: it.javaClass.simpleName}", LogLevel.ERROR) }
            .isSuccess
    /**
     * The texts the model is least sure about, from a csv's text column or a text file's lines (else what it was asked
     * over HTTP), written with its guess to AMP/<modelName>-to-label.csv: fill in the labels and fineTune on the file.
     * diverse picks texts unlike each other among its closest calls, so a few labels cover more kinds of text
     */
    fun textsToLabel(modelName: String, path: String? = null, limit: Int = 50, diverse: Boolean = false): String? {
        val from = if (path == null) null else DataSource.locate(path) ?: run {
            log("textsToLabel: $path not found", LogLevel.ERROR)
            return null
        }
        return runCatching { writeTextsToLabel(modelName, from, limit, strategy = if (diverse) PickStrategy.DIVERSE else PickStrategy.CLOSEST) }
            .onFailure { log("textsToLabel: ${it.message ?: it.javaClass.simpleName}", LogLevel.ERROR) }
            .getOrNull()?.path
    }
    /**
     * A text the model answers wrongly, with the label it should give: written labeled at the top of its queue
     * (AMP/<modelName>-to-label.csv), as the Models page's correction chips do, so the next fineTune on the queue learns
     * it; the label in the model's spelling, a text the queue has relabeled. Returns the queue's path, null when it isn't
     * written (a model that keeps no texts has no queue; the console says why, never with the text)
     */
    fun teach(modelName: String, text: String, label: String): String? = writeCorrection(modelName, text, label)?.path
    /**
     * The rows of the model's queue in the file's order, labeled or not, each with what the model answered when it got
     * there or was graded last; printed as a table (an internal line). None for a model that keeps no texts
     */
    fun queue(modelName: String): List<LabelQueue.Row> {
        if (!moduleExists(modelName)) return emptyList<LabelQueue.Row>().also { log("queue: there is no model '$modelName'", LogLevel.ERROR) }
        val manifest = runCatching { readModule(modelName)?.second }.getOrNull() ?: return emptyList<LabelQueue.Row>().also { log("queue: '$modelName' can't be read", LogLevel.ERROR) }
        if (!manifest.keepTexts) return emptyList<LabelQueue.Row>().also { log("queue: '$modelName' answers other people and keeps no texts, so it has no queue", LogLevel.ERROR) }
        val file = LabelQueue.fileFor(modelName)
        return LabelQueue.rows(file).also { logInternal(queueLines(modelName, file.name, it).joinToString("\n")) }
    }
    /**
     * What a fine-tune on the data would do, worked out without training, in the words of the Models page's plan strip
     * (an internal line): the rows, the labels they are and add, the earlier rows replayed, what is dropped, what is
     * worth a second look and what keeps it from starting (an ERROR line then). The data is what fineTune takes; without
     * it, the labeled rows of the model's queue. Nothing is trained or written: .ok says whether fineTune would start
     */
    fun planFineTune(modelName: String, data: Any? = null, options: FineTuneOptions = FineTuneOptions()): FineTunePlan {
        val queue = LabelQueue.fileFor(modelName)
        val samples = if (data != null) parseLabeledData(data) else DataSource.quietSamples(queue).orEmpty()
        val on = when (data) {
            null -> "the labeled rows of its queue"
            is DataSource -> data.name
            else -> "rows from the script"
        }
        val plan = workOutFineTune(modelName, samples, options).let { plan ->
            // A queue with nothing labeled isn't a file without columns: say what it lacks
            if (data != null || samples.isNotEmpty() || !moduleExists(modelName)) plan
            else plan.copy(problems = listOf(
                if (readModule(modelName)?.second?.keepTexts == false) "it keeps no texts, so it has no queue; give planFineTune the rows to plan on"
                else "nothing in its queue (${queue.name}) is labeled yet; teach puts labeled rows there"
            ))
        }
        logInternal(planLines(plan, on).joinToString("\n"))
        planBlocks(plan).firstOrNull()?.let { log("planFineTune: a fine-tune of '$modelName' on $on would not start: $it", LogLevel.ERROR) }
        return plan
    }
    /**
     * What the model answers now to every text of its queue, as the Models page grades it after its head changed (a
     * fineTune, rollback or calibrate): each row's guess, score and runner-up written again, the labels kept. Returns
     * the rows graded, none when it has no queue; the console says how many labeled texts it answers right
     */
    fun regradeQueue(modelName: String): List<LabelQueue.Row> {
        if (LabelQueue.rows(LabelQueue.fileFor(modelName)).isEmpty()) return emptyList<LabelQueue.Row>().also { logInternal("regradeQueue: '$modelName' has no texts in a queue to grade") }
        return runCatching { gradeQueueAgain(modelName) }
            .onFailure { log("regradeQueue: ${it.message ?: it.javaClass.simpleName}", LogLevel.ERROR) }
            .getOrNull()?.let { LabelQueue.rows(it) }.orEmpty()
    }
    /** Sentence vectors kept on disk so a text is embedded once: embeddingCache.print(), embeddingCache.clear() */
    val embeddingCache: EmbeddingCache get() = EmbeddingCache
    /**
     * The preprocessor alone, without training: prints what it would clean, drop and refuse; true when a build could start
     */
    fun checkData(data: Any?): Boolean {
        val samples = org.example.project.components.terminal.window.controller.jetbrainDLBuilder.parseLabeledData(data)
        val result = org.example.project.components.terminal.window.controller.jetbrainDLBuilder.Preprocessor.run(samples)
        result.lines().forEach { log("checkData: $it", if (it.startsWith("error:")) LogLevel.ERROR else LogLevel.DATA) }
        log(if (result.ok) "checkData: a build can start on these ${result.samples.size} rows" else "checkData: a build would stop; fix the ${result.errors.size} ${if (result.errors.size == 1) "reason" else "reasons"} above", if (result.ok) LogLevel.INFO else LogLevel.ERROR)
        return result.ok
    }

    /** A database from AMP/databases.json: database("main").query("select …"); the same object while the app runs */
    fun database(name: String = "main"): Database = Databases.open(name)
    /** The configured databases: databases.print(), databases.names(), databases.closeAll() */
    val databases: Databases get() = Databases
    /**
     * Runs a command through the person's login shell ($SHELL -lc, so their PATH and tools work; /bin/sh -c without
     * SHELL, cmd /c on Windows) and waits for it. The console shows the command, then what it prints as it comes:
     * both at the internal level, stderr as error lines. A non-zero exit is no exception: .ok, .exit, .out, .err, .lines.
     * dir is where it runs (the home folder unless given; ~/ works); after timeoutSeconds it is stopped with all it
     * started, 0 means no limit; env is added to its environment
     */
    fun shell(command: String, dir: String? = null, timeoutSeconds: Int = 120, env: Map<String, String> = emptyMap()): ShellResult =
        ShellCommands.shell(command, dir, timeoutSeconds, env, this::log)
    /**
     * Exports a script as a bundle that runs without the app, as Export… over the editor does. [script] is main.kts, a
     * template's name or id, or a file; [to] a new or empty folder (~/ works), the first free one under ~/amie-bundles
     * when empty. What can't be included stops it unless [anyway], which writes it and says so in its README;
     * [planOnly] only says what would go in; [compiled] false carries the text and the Kotlin compiler instead of classes.
     * The console says what goes in and where it went. Returns the bundle's folder, null when nothing was written
     */
    fun exportJvm(script: String = "main.kts", to: String = "", anyway: Boolean = false, planOnly: Boolean = false, compiled: Boolean = true): String? {
        fun problem(text: String): String? = null.also { log("exportJvm: $text", LogLevel.ERROR) }
        val found = ExportCommand.find(script.trim()) ?: return problem("no script '$script': not main.kts, a template's name or id, or a file")
        val mode = if (compiled) ExportMode.COMPILED else ExportMode.SCRIPT
        val plan = ScriptExporter.plan(found.code, found.name, found.source, ExportOptions(allowMissing = anyway, mode = mode))
        log("exportJvm: ${found.name} (${found.source}), ${mode.name.lowercase()}: ${plan.copies.size + plan.generated.size} files, ${plan.bytes shr 20} MB" +
            plan.manifest.models.takeIf { it.isNotEmpty() }?.joinToString(", ", ", models ") { it.name }.orEmpty() +
            plan.manifest.packages.takeIf { it.isNotEmpty() }?.joinToString(", ", ", packages ").orEmpty())
        plan.problems.forEach { log("exportJvm: can't include $it", LogLevel.ERROR) }
        if (planOnly) return null
        if (plan.problems.isNotEmpty() && !anyway) return problem("nothing written; deal with the above, or pass anyway = true")
        val out = to.trim().let { if (it.isEmpty()) ScriptExporter.freeFolder(found.name) else File(if (it.startsWith("~/")) System.getProperty("user.home") + it.drop(1) else it) }
        if (out.exists() && !(out.isDirectory && out.list().isNullOrEmpty())) return problem("$out isn't empty: a bundle goes in a new or empty folder")
        return runCatching { ScriptExporter.write(plan, out) }.fold(
            onSuccess = { written ->
                val bytes = written.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                log("exportJvm: written to ${written.path} (${bytes shr 20} MB); run it with: ${ScriptExporter.javaLine(written)}")
                written.path
            },
            onFailure = { problem(if (it is ExportFailed) "nothing written; the script doesn't compile:\n" + it.problems.joinToString("\n") else it.message ?: it.javaClass.simpleName) },
        )
    }

    /**
     * A script file (~/ works), what shell gives back. The arguments go to it one by one and are never joined into a
     * command line, so spaces and quotes arrive as they are. Run directly when it is executable, through the shell otherwise
     */
    fun shellScript(path: String, args: List<String> = emptyList(), dir: String? = null, timeoutSeconds: Int = 120, env: Map<String, String> = emptyMap()): ShellResult =
        ShellCommands.script(path, args, dir, timeoutSeconds, env, this::log)

    /**
     * Serves the exported model over HTTP as the Models page's Listen does; null parameters keep what serving.json
     * says (host 127.0.0.1, port 8765, path /models/<name>, POST and GET, no min score). Under minScore it abstains,
     * answering fallbackLabel or no label. Returns the predict URL, null when it could not start (the console says
     * why: the model can't load, the port is taken, the path is served already)
     */
    fun serve(
        modelName: String, port: Int? = null, host: String? = null, path: String? = null, methods: List<String>? = null,
        minScore: Double? = null, fallbackLabel: String? = null,
    ): String? {
        val settings = runCatching { readServing(modelName).with(port, host, path, methods, minScore, fallbackLabel) }.getOrElse {
            log("serve: ${it.message}", LogLevel.ERROR)
            return null
        }
        return if (ModelServer.start(modelName, settings)) settings.url(modelName).also { log("serve: '$modelName' answers ${settings.methods.joinToString("/")} at $it") }
        else null.also { log("serve: '$modelName' could not start; the console has the reason", LogLevel.ERROR) }
    }
    /** Stops serving the model and remembers that, so it does not resume on the next start */
    fun stopServing(modelName: String) = ModelServer.stop(modelName)
    /** The predict URL of a model that is being served right now, null when it is not */
    fun servingUrl(modelName: String): String? = if (ModelServer.isListening(modelName)) readServing(modelName).url(modelName) else null
    /**
     * The last requests the model's listener answered, from AMP/models/intents/<name>/access.log, oldest first and also
     * printed: time, request id (the X-Request-Id it sent back), client, method, path, status, ms, bytes, outcome
     */
    fun accessLog(modelName: String, last: Int = 20): List<String> =
        AccessLog.tail(modelName, last).also { lines ->
            if (lines.isEmpty()) log("accessLog: no requests logged for '$modelName' yet")
            else lines.forEach { log(it.replace('\t', ' ')) }
        }

    /**
     * Starts a saved template, named as it was saved (any case) or by its id, in the background; the script goes on.
     * input waits for it to read. The run answers to job.send, job.receive, job.closeInput, job.await, job.cancel and
     * job.state, and outlives the script unless the script is stopped. An unknown name, a template of another env, a
     * ninth run, a loop of templates or too little memory is an error
     */
    fun coroutineTemplate(name: String, input: List<String> = emptyList()): TemplateRun = TemplateRuns.start(name, input, this)
    /**
     * A saved template to call like a function: `val reader by template("name")`, then reader("text") starts it on the
     * first call, gives it the line and returns its next output, on the same run each time. It throws when the template
     * failed. reader.close() closes its input and waits, reader.run is the run; it is closed for the script when that ends
     */
    fun template(name: String): TemplateDelegate = TemplateDelegate { TemplateRuns.start(name, parent = this, closesWithOwner = true) }
    /** What runs in the background: templateRuns.print(), templateRuns.cancelAll() */
    val templateRuns: TemplateRuns get() = TemplateRuns
    /**
     * The next line, waiting for one; null once input is closed. In main.kts a line typed in the console, or of standard
     * input in a bundle; in a template the next line main.kts gave it. With neither it is null at once, with a line saying so
     */
    fun input(): String? {
        log("input: nothing feeds this run, it has no console")
        return null
    }
    /** In a template: its next answer to job.receive() in main.kts. A foreground run logs the text */
    fun output(text: String) = log(text)
    /** Any value as its text, a list one element a line */
    fun output(value: Any?) = output(consoleText(value))
}

/** What the console shows of a value: a string as it is, a list, a set, a sequence or an array one element a line, a map key: value, null as null */
internal fun consoleText(value: Any?): String = when {
    value is String -> value
    value is Map<*, *> -> value.entries.joinToString("\n") { "${consoleText(it.key)}: ${consoleText(it.value)}" }
    value is Iterable<*> -> value.joinToString("\n") { consoleText(it) }
    value is Sequence<*> -> value.joinToString("\n") { consoleText(it) }
    value != null && value.javaClass.isArray -> (0 until java.lang.reflect.Array.getLength(value)).joinToString("\n") { consoleText(java.lang.reflect.Array.get(value, it)) }
    else -> value.toString()
}

abstract class ModelCreationalInterface {
    /** Set when modelBuilder exported a module; its status is the last stored validation */
    open val modelName: String? get() = null

    fun modelStatus(): Unit {
        val name = modelName
        if (name != null) MetricsReport.load(name).print()
        else logToConsole(ResponseDto(time = Instant.now(), message = "Status of model ${this.javaClass.simpleName}"))
    }

    /** The stored validation as ASCII charts: bars per label, the build history and the confusion matrix */
    fun modelChart(): Unit {
        val name = modelName ?: return logToConsole(ResponseDto(time = Instant.now(), message = "modelChart: nothing was exported", level = LogLevel.ERROR))
        MetricsReport.load(name).chart().history().confusion()
    }
}
