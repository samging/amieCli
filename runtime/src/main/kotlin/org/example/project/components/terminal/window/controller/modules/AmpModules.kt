package org.example.project.components.terminal.window.controller.modules

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.DEFAULT_ENCODER
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.LabelQueue
import org.example.project.components.terminal.integrations.amiePilot.BundledModels
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import org.example.project.components.terminal.window.controller.metrics.ModelCard
import org.example.project.components.terminal.window.controller.runtime.ModelServer
import org.example.project.components.terminal.window.controller.runtime.ModelSessions
import org.example.project.components.terminal.window.controller.runtime.TextModels
import org.example.project.components.terminal.window.controller.modelLoaders.imported.ImportedModels
import org.example.project.components.terminal.window.controller.runtime.SpeechModels
import org.example.project.components.terminal.window.controller.runtime.readServing
import org.example.project.components.terminal.window.controller.runtime.writeServing
import org.example.project.components.terminal.window.controller.util.bytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import org.example.project.amp.ModelFolders

// Exported models live in AMP/models/intents/<name>/ as model.onnx + module.json, indexed by AMP/models/intents/modules.json;
// ModelFolders owns the path (and reads AMP/modules while a folder has not been moved yet)
val modulesDir: File get() = ModelFolders.amp.intents

private val moduleJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

@Serializable
data class ModuleManifest(
    val name: String,
    val format: String = "onnx",
    val model: String = "model.onnx",
    // Encoder that turns text into the hidden state the head was trained on
    val encoder: String,
    val pooling: String = "mean",
    val input: String = "embedding",
    val output: String = "score",
    val inputDim: Long,
    val outputDim: Long,
    // Class names in output order
    val labels: List<String> = emptyList(),
    val createdAt: String = Instant.now().toString(),
    // The data file the head was trained on, when known (a csv name); models trained on the same data group together
    val data: String? = null,
    // The rows per label the build kept of its data after cleaning, for the model card; null in modules built before
    // these were kept, whose card counts the file as it is now
    val dataRows: Map<String, Int>? = null,
    // What the model is for, written by whoever built it and editable on the Models page
    val description: String? = null,
    // How it was trained: the head's shape and the fit; null in modules built before these were kept
    val architecture: String? = null,
    val epochs: Int? = null,
    val batchSize: Int? = null,
    val learningRate: Double? = null,
    val hiddenDim: Int? = null,
    val trainRows: Int? = null,
    val validationRows: Int? = null,
    val validation: String? = null,
    val finalLoss: Double? = null,
    val trainAccuracy: Double? = null,
    // The heads the build compared and which one it kept; null when one head was trained
    val search: String? = null,
    // The icon of the model on the Models page: a name from its catalog (lightbulb, cloud, chat, …); null is the chip
    val icon: String? = null,
    // False for a model that answers other people (the model site): its sessions log the answers and the length of
    // each text, never the text itself, here or in the console
    val keepTexts: Boolean = true,
    // The head's number: 1 for the first build, one more for every build, fine-tune or calibration after it; null
    // in modules written before versions were kept. Earlier heads are in versions/v<n>/ until rolled back to or pruned
    val version: Int? = null,
    // What made this head: build, fine-tune, calibration or rollback (null: a build, from before kinds were kept)
    val kind: String? = null,
    // The version this head was made from: the head a fine-tune started from, the one a rollback brought back
    val parentVersion: Int? = null,
    // The temperature folded into the last layer by a calibration (1 or null: none); the scores are softmax(z / t)
    val temperature: Double? = null,
    // The data files fine-tunes added to the head, oldest first; SCRIPT_ROWS for rows a script gave without a file
    val fineTunedOn: List<String> = emptyList(),
    // The rows per label each of those fine-tunes kept after cleaning, in the same order; null for one from before
    // these were kept, whose card counts the file as it is now
    val fineTunedRows: List<Map<String, Int>?> = emptyList(),
    // The mean of what the last layer read over the training rows (base64 of little-endian floats), so a fine-tune
    // without the training data still knows where the labels it has sit; never kept for a model that doesn't keep texts
    val featureMean: String? = null,
    // How many training rows that mean is over
    val featureRows: Int? = null,
)

/** What fineTunedOn names for the rows a fine-tune was given by a script, without a file */
const val SCRIPT_ROWS = "rows from a script"

@Serializable
data class ModuleRegistry(val modules: Map<String, String> = emptyMap())

internal val registryFile: File get() = File(modulesDir, "modules.json")

fun moduleDir(name: String): File = File(modulesDir, name.replace(Regex("""[^A-Za-z0-9._-]"""), "_"))

fun readRegistry(): ModuleRegistry =
    runCatching { moduleJson.decodeFromString<ModuleRegistry>(registryFile.readText()) }.getOrElse { ModuleRegistry() }

/** The guide that ships inside a module: `guide.md` next to its manifest, what a downloaded model comes with */
fun guideFile(dir: File): File = File(dir, "guide.md")

/**
 * The guide a freshly built module starts with, from its manifest: what it is, its labels, how to open and ask
 * it from a script and over HTTP, how to validate it, and its files. Whoever builds the model edits it on the page.
 */
fun defaultGuide(m: ModuleManifest): String = buildString {
    appendLine("# ${m.name}")
    appendLine()
    appendLine(m.description ?: "An intent model: it turns a sentence into one of its labels.")
    appendLine()
    // Where the model card goes, written after the model's first run (ModelCard)
    appendLine(ModelCard.BEGIN)
    ModelCard.placeholder.forEach { appendLine(it) }
    appendLine(ModelCard.END)
    appendLine()
    appendLine("## Labels")
    appendLine()
    m.labels.forEach { appendLine("- $it") }
    appendLine()
    appendLine("## From a script")
    appendLine()
    appendLine("```")
    appendLine("val model = initMod(\"${m.name}\")")
    appendLine("model.inference(\"your sentence\")   // [\"label:score\", ...], best first")
    appendLine("model.close()")
    appendLine("```")
    appendLine()
    appendLine("A model has one session: initMod gives the open one when there is one. Terminate it on the Models page or close() it.")
    appendLine()
    appendLine("## Over HTTP")
    appendLine()
    appendLine("Listen on the Models page, then POST {\"text\": \"...\"} or GET ?text=... to its /predict address; the gear beside Listen shows the address and a curl call.")
    appendLine()
    appendLine("## Validating")
    appendLine()
    appendLine("Validate on a csv with a text and a label column (the labels above). Every validation is a run on the Metrics tab.")
    appendLine()
    appendLine("## How it works")
    appendLine()
    appendLine("The text goes through the ${File(m.encoder).name} encoder (${m.inputDim} numbers, ${m.pooling} pooled), then through this model's head" +
        (m.architecture?.let { " ($it)" } ?: "") + ", which gives one score per label.")
    m.epochs?.let { appendLine("Trained for $it epochs on ${m.trainRows ?: "?"} rows of ${m.data ?: "its data"}; validated on ${m.validationRows ?: "?"} rows of ${m.validation ?: "a split"}.") }
    appendLine()
    appendLine("## Files")
    appendLine()
    appendLine("- module.json: name, labels, encoder, how it was trained")
    appendLine("- ${m.model}: the head")
    appendLine("- metrics.json: every validation run")
    appendLine("- guide.md: this guide")
}

/**
 * The guide's "## Labels" list rewritten to [labels], when it lists exactly [old] (the guide a build wrote, or
 * one edited elsewhere); a list someone wrote differently is left alone. True when the guide was changed. Like every
 * write of the guide it holds the guide's lock (ModelCard), so a Save or the card's refresh can't undo it.
 */
fun updateGuideLabels(guide: File, old: List<String>, labels: List<String>): Boolean = ModelCard.locked(guide.absoluteFile.parentFile) {
    if (!guide.isFile) return@locked false
    val lines = guide.readLines()
    val at = lines.indexOfFirst { it.trim() == "## Labels" }
    if (at < 0) return@locked false
    var i = at + 1
    while (i < lines.size && lines[i].isBlank()) i++
    val start = i
    while (i < lines.size && lines[i].startsWith("- ")) i++
    if (lines.subList(start, i).map { it.removePrefix("- ").trim() } != old) return@locked false
    guide.writeText((lines.subList(0, start) + labels.map { "- $it" } + lines.subList(i, lines.size)).joinToString("\n", postfix = "\n"))
    true
}

/** Module names: letters, digits, dots, dashes and underscores, as the folder under AMP/models/intents is named */
val MODULE_NAME: Regex = Regex("""[A-Za-z0-9._-]{1,64}""")

/**
 * One lock per module name. A change that reads a module when it begins and writes it seconds or minutes later (a
 * build, fine-tune, calibration or rollback) writes holding it, and so do a rename and a destroy, so whichever comes
 * second sees what the first did. Names are taken in order, so two holders of the same names never wait on each other.
 */
object ModuleLocks {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun <T> locked(vararg names: String, block: () -> T): T {
        // By the name in lower case: on a case-insensitive volume (macOS's default) Lights and lights are one folder
        val ordered = names.map { it.lowercase() }.distinct().sorted()
        fun hold(i: Int): T = if (i == ordered.size) block() else synchronized(locks.computeIfAbsent(ordered[i]) { Any() }) { hold(i + 1) }
        return hold(0)
    }
}

/**
 * [writeModule] at the end of a long change that found the module there ([existed]) or not there (a new build) when
 * it began: holding the module's lock it writes only if that is still so, then runs [after] with the model file (the
 * run's metrics, the guide). A model renamed or destroyed meanwhile isn't brought back under its old name, and a build
 * doesn't write over a model renamed to its name meanwhile; it throws with the reason instead. A change made from the
 * head the model had ([startedFrom]: a fine-tune, a calibration) also needs that head to be the one it has still: one
 * rolled back, trained again, or destroyed and built anew meanwhile isn't written over, and no two heads get one number.
 */
fun writeModuleChecked(
    manifest: ModuleManifest,
    onnxBytes: ByteArray,
    existed: Boolean,
    startedFrom: ModuleManifest? = null,
    after: (File) -> Unit = {},
): File =
    ModuleLocks.locked(manifest.name) {
        check(moduleExists(manifest.name) == existed) {
            if (existed) "'${manifest.name}' was renamed or destroyed meanwhile, so its new head was not written"
            else "a model named '${manifest.name}' appeared meanwhile (renamed to that name?), so this one was not written over it"
        }
        if (startedFrom != null) {
            val now = readModule(manifest.name)?.second
            check(now != null && ModelVersions.numberOf(now) == ModelVersions.numberOf(startedFrom) && now.createdAt == startedFrom.createdAt) {
                "'${manifest.name}' got another head meanwhile (v${now?.let(ModelVersions::numberOf)} now, this began from " +
                    "v${ModelVersions.numberOf(startedFrom)}), so its new head was not written; run it again on the head it has now"
            }
        }
        writeModule(manifest, onnxBytes).also(after)
    }

/**
 * Writes the module: a new one, or the same name trained again, in which case the open session and the listener
 * on the old head go on with the new one without a break ([swapRunning]: the new head is opened beside the old,
 * put in its place, and the old one closed once its answers in flight are done), and the description, icon and
 * guide it had are kept unless the manifest brings its own. The head it replaces is kept in versions/
 * ([ModelVersions]); the new one gets the next version number unless the manifest has one.
 */
fun writeModule(manifest: ModuleManifest, onnxBytes: ByteArray): File {
    val previous = if (moduleExists(manifest.name)) readModule(manifest.name)?.second else null
    val described = if (previous == null) manifest
        else manifest.copy(description = manifest.description ?: previous.description, icon = manifest.icon ?: previous.icon)
    val kept = if (described.version != null) described else described.copy(version = (previous?.let(ModelVersions::numberOf) ?: 0) + 1)
    val dir = moduleDir(kept.name).apply { mkdirs() }
    if (previous != null) ModelVersions.keep(dir, previous)
    val modelFile = File(dir, kept.model)
    // The old head answers from memory meanwhile: ONNX Runtime read its file when it opened and holds no handle on it
    modelFile.writeBytes(onnxBytes)
    File(dir, "module.json").writeText(moduleJson.encodeToString(kept))
    guideFile(dir).takeIf { !it.isFile }?.writeText(defaultGuide(kept))
    val registry = readRegistry()
    registryFile.writeText(moduleJson.encodeToString(registry.copy(modules = registry.modules + (kept.name to dir.name))))
    logInternal(if (previous == null) "module '${kept.name}' exported to ${modelFile.absolutePath}" else "module '${kept.name}' trained again; the new head replaced ${modelFile.absolutePath}")
    if (previous != null) swapRunning(kept.name, previous, kept)
    return modelFile
}

/**
 * The new head of [name], just written, into what runs on the old one: its open session ([ModelSessions.reload])
 * and its listener ([ModelServer.reload]) each open a model on the new head, put it in the old one's place and
 * close the old one once its answers in flight are done. So the model keeps answering through a fine-tune, a
 * build or a rollback, and a script, a swarm or a client holding it is never cut off; the old head answered up
 * to the swap, the new one answers from it. Nothing runs: nothing to do, the next initMod or Listen opens the new head.
 */
private fun swapRunning(name: String, previous: ModuleManifest, now: ModuleManifest) {
    val note = "head v${ModelVersions.numberOf(previous)} → v${ModelVersions.numberOf(now)} (${now.kind ?: "build"}) swapped in; the head before was closed once its answers were done"
    val sessions = runCatching { ModelSessions.reload(name, note) }
        .onFailure { logInternal("module '$name': its sessions could not be moved to the new head: ${it.message}", LogLevel.ERROR) }.getOrDefault(0)
    val listener = runCatching { ModelServer.reload(name, note) }
        .onFailure { logInternal("module '$name': its listener could not be moved to the new head: ${it.message}", LogLevel.ERROR) }.getOrDefault(false)
    if (sessions > 0 || listener) logInternal("module '$name': ${listOfNotNull(sessions.takeIf { it > 0 }?.let { "$it ${if (it == 1) "session" else "sessions"}" }, "the listener".takeIf { listener }).joinToString(" and ")} answer${if (sessions == 1 && !listener) "s" else ""} with v${ModelVersions.numberOf(now)} now")
}

/**
 * Gives a module a new name: its sessions are closed and its listener stopped, the folder under AMP/modules is
 * moved, module.json, the guide's title and the registry say the new name, and a serving path that was the
 * default of the old name becomes the default of the new one. Its queue of texts to label, AMP/<name>-to-label.csv,
 * is renamed with it. The listener is started again under the new name if it was on. Throws with the reason when
 * the name is taken or not a module name, or when a queue of that name is there already: one no model owns, left
 * by hand or by an earlier model of that name, which the renamed model would otherwise take for its own.
 */
fun renameModule(name: String, newName: String): Boolean {
    val target = newName.trim()
    require(MODULE_NAME.matches(target)) { "'$target' is not a model name: letters, digits, dots, dashes and underscores, up to 64" }
    if (target == name) return true
    // A fine-tune or a build of either name that ends meanwhile writes after the rename, and sees it
    return ModuleLocks.locked(name, target) { renameLocked(name, target) }
}

private fun renameLocked(name: String, target: String): Boolean {
    require(!moduleExists(target) && !moduleDir(target).exists()) { "a model named '$target' exists already" }
    val queue = LabelQueue.fileFor(name)
    val targetQueue = LabelQueue.fileFor(target)
    require(!targetQueue.exists()) { "AMP/${targetQueue.name} is there already, a queue of texts to label that no model owns; move or delete it first, so '$target' doesn't take it for its own" }
    val (dir, manifest) = readModule(name) ?: return false
    val wasListening = ModelServer.isListening(name)
    val serving = readServing(name)
    ModelServer.stop(name, persist = false)
    ModelSessions.all.filter { it.name == name }.forEach { it.close() }
    val newDir = moduleDir(target)
    // Moved holding the queues' locks from the folder to the queue: a label or a regrade of the old name either lands
    // before and moves with it, or comes after, finds no model of that name and writes nothing; one given to the new
    // name (which exists once its folder is there) waits for the queue to be there first
    LabelQueue.locked(queue) {
        LabelQueue.locked(targetQueue) {
            if (!dir.renameTo(newDir)) {
                dir.copyRecursively(newDir, overwrite = true)
                dir.deleteRecursively()
            }
            File(newDir, "module.json").writeText(moduleJson.encodeToString(manifest.copy(name = target)))
            if (queue.isFile && !queue.renameTo(targetQueue)) {
                queue.copyTo(targetQueue)
                queue.delete()
            }
        }
    }
    guideFile(newDir).takeIf { it.isFile }?.let { guide ->
        ModelCard.locked(newDir) {
            val lines = guide.readLines()
            if (lines.firstOrNull()?.trim() == "# $name") guide.writeText((listOf("# $target") + lines.drop(1)).joinToString("\n", postfix = "\n"))
        }
    }
    val registry = readRegistry()
    registryFile.writeText(moduleJson.encodeToString(registry.copy(modules = registry.modules - name + (target to newDir.name))))
    val path = if (serving.path.isBlank() || serving.path == "/models/$name") "" else serving.path
    writeServing(target, serving.copy(path = path, enabled = wasListening))
    ModelGroups.forget(name, renamedTo = target)
    if (wasListening) runCatching { ModelServer.start(target) }.onFailure { logInternal("module '$target': the listener did not come back after the rename: ${it.message}", LogLevel.ERROR) }
    logInternal("module '$name' renamed to '$target': ${newDir.absolutePath}")
    return true
}

/** Rewrites module.json with [change] applied, e.g. a new description; false when the module isn't there */
fun updateModule(name: String, change: (ModuleManifest) -> ModuleManifest): Boolean = ModuleLocks.locked(name) {
    // Holding the module's lock: a head written meanwhile isn't paired with the manifest it replaced
    val (dir, manifest) = readModule(name) ?: return@locked false
    File(dir, "module.json").writeText(moduleJson.encodeToString(change(manifest)))
    true
}

/**
 * Removes a module for good: its sessions are closed and its listener stopped first, then AMP/models/intents/<name>/
 * and its queue of texts to label (AMP/<name>-to-label.csv) are deleted and the registry forgets it. The csv
 * files it was trained on stay: other models may use them. The dummy comes back on the next start of the page.
 */
fun deleteModule(name: String): Boolean = ModuleLocks.locked(name) {
    val (dir, _) = readModule(name) ?: return@locked false
    ModelServer.stop(name)
    ModelSessions.all.filter { it.name == name }.forEach { it.close() }
    val file = LabelQueue.fileFor(name)
    val queue = file.takeIf { it.exists() }
    // The queue goes holding its lock, after the folder: a label given meanwhile finds no model and writes nothing
    val gone = dir.deleteRecursively() and LabelQueue.locked(file) { !file.exists() || file.delete() }
    val registry = readRegistry()
    registryFile.writeText(moduleJson.encodeToString(registry.copy(modules = registry.modules - name)))
    ModelGroups.forget(name)
    val what = dir.absolutePath + if (queue != null) " and AMP/${queue.name}" else ""
    logInternal(if (gone) "module '$name' destroyed: $what removed" else "module '$name' forgotten, but $what could not be removed entirely", if (gone) LogLevel.INTERNAL else LogLevel.ERROR)
    gone
}

/** Whether a module of that name is exported, without logging when it isn't */
fun moduleExists(name: String): Boolean {
    val dir = readRegistry().modules[name]?.let { File(modulesDir, it) } ?: moduleDir(name)
    return File(dir, "module.json").isFile
}

/** module.json of a module folder as it is stored: the encoder as written, not resolved to this machine's folder */
fun readStoredManifest(dir: File): ModuleManifest = moduleJson.decodeFromString(File(dir, "module.json").readText())

internal fun registeredDir(name: String): File = readRegistry().modules[name]?.let { File(modulesDir, it) } ?: moduleDir(name)

/** Whether a module of that name is there, without the error of a look that expected one */
fun hasModule(name: String): Boolean = File(registeredDir(name), "module.json").exists()

// Resolves a module by its registered name, falling back to its folder name
fun readModule(name: String): Pair<File, ModuleManifest>? {
    val dir = registeredDir(name)
    val manifestFile = File(dir, "module.json")
    if (!manifestFile.exists()) {
        // A text, speech or imported model of that name is not a module and not an error: initMod opens it on its own path
        if (!TextModels.has(name) && !SpeechModels.has(name) && !ImportedModels.has(name)) logInternal("no model '$name' in ${modulesDir.parentFile.absolutePath}: not an intent, text, speech or imported model; amie model list shows what is there, amie hf import <repo> brings one from Hugging Face", LogLevel.ERROR)
        return null
    }
    val manifest = moduleJson.decodeFromString<ModuleManifest>(manifestFile.readText())
    return dir to manifest.copy(encoder = BundledModels.resolveEncoder(manifest.encoder))
}

const val DUMMY_MODEL = "dummy"
const val SAMPLE_DATA = "sample-intents.csv"

/**
 * Writes `AMP/sample-intents.csv` (text,label rows over greeting / lights / weather, the dummy's labels) unless it
 * exists, so a model can be built and validated from the page before any data of one's own is at hand.
 */
fun ensureSampleData(): File {
    val file = File(getDirectory().toString(), SAMPLE_DATA)
    if (file.isFile) return file
    val rows = mapOf(
        "greeting" to listOf(
            "hello there", "hi, how are you", "good morning", "hey amie", "good evening to you", "hi there, nice to see you",
            "hello, anyone home", "greetings", "hey, what's up", "morning", "hello again", "hi amie, you awake",
            "good afternoon", "howdy", "nice to meet you",
        ),
        "lights" to listOf(
            "turn the lights on", "switch off the kitchen light", "dim the living room lights", "lights off please",
            "make the bedroom brighter", "turn on the lamp", "set the lights to 50 percent", "kill the lights in the hall",
            "can you turn the light on in the office", "switch the garden lights on", "lights on", "turn off every light",
            "brighten the desk lamp", "lower the lights a bit", "turn the hallway light off",
        ),
        "weather" to listOf(
            "what's the weather like today", "will it rain tomorrow", "is it cold outside", "how hot is it right now",
            "do i need an umbrella", "what's the forecast for the weekend", "is it going to snow", "how windy is it",
            "temperature outside please", "weather for tomorrow morning", "is it sunny", "any storms coming",
            "what's the humidity today", "will it be warm this afternoon", "forecast for tonight",
        ),
    )
    val text = buildString {
        appendLine("text,label")
        rows.forEach { (label, texts) -> texts.forEach { appendLine("\"${it.replace("\"", "\"\"")}\",$label") } }
    }
    runCatching { file.writeText(text) }.onFailure { logInternal("sample data: could not be written: ${it.message}", LogLevel.ERROR) }
    return file
}

/**
 * Writes a small fixed intent model named "dummy" (three labels, deterministic weights, one dense layer over the
 * encoder's embedding) unless it already exists, so the Models page always has a component to configure and a
 * script can try `initMod("dummy")` before training anything. Returns the module folder, or null when it could
 * not be written.
 */
fun ensureDummyModule(encoder: String = DEFAULT_ENCODER): File? {
    val dir = moduleDir(DUMMY_MODEL)
    if (File(dir, "module.json").isFile) {
        // A dummy written before the description and the guide were kept gets them now
        runCatching { updateModule(DUMMY_MODEL) { m -> if (m.description != null) m else m.copy(description = dummyDescription(m.encoder), architecture = m.architecture ?: "dense[${m.inputDim} -> ${m.labels.size}] sigmoid over mean-pooled ${File(m.encoder).name}") } }
        runCatching { readModule(DUMMY_MODEL)?.let { (d, m) -> guideFile(d).takeIf { !it.isFile }?.writeText(defaultGuide(m)) } }
        return dir
    }
    return runCatching {
        val dim = hiddenSize(encoder) ?: 768
        val labels = listOf("greeting", "lights", "weather")
        val kernel = FloatArray(dim * labels.size) { i ->
            val row = i / labels.size
            val col = i % labels.size
            (kotlin.math.sin((row + 1) * 0.37 + col * 2.1) * 0.05).toFloat()
        }
        val layer = DenseExport(kernel, FloatArray(labels.size), dim.toLong(), labels.size.toLong(), "Sigmoid")
        val manifest = ModuleManifest(
            name = DUMMY_MODEL, encoder = encoder, inputDim = dim.toLong(), outputDim = labels.size.toLong(), labels = labels,
            description = dummyDescription(encoder),
            architecture = "dense[$dim -> ${labels.size}] sigmoid over mean-pooled ${File(encoder).name}",
        )
        writeModule(manifest, denseStackToOnnx(listOf(layer), manifest.input, manifest.output, DUMMY_MODEL))
        dir
    }.onFailure { logInternal("dummy model: could not be written: ${it.message}", LogLevel.ERROR) }.getOrNull()
}

private fun dummyDescription(encoder: String) =
    "A fixed three-label head with made-up weights over the ${File(encoder).name} encoder, so the page has a model to configure, " +
        "serve and validate and a script can try initMod(\"dummy\") before training anything. Its scores are not meaningful; " +
        "build a model from sample-intents.csv to see real ones."

// The encoder's hidden size from its config.json, which is the width of the embedding a head is trained on
private fun hiddenSize(encoder: String): Int? = runCatching {
    Regex(""""hidden_size"\s*:\s*(\d+)""").find(File(encoder, "config.json").readText())?.groupValues?.get(1)?.toInt()
}.getOrNull()

/** One dense layer: kernel is row-major [inDim][outDim]; activation is an ONNX op type or null for linear */
class DenseExport(val kernel: FloatArray, val bias: FloatArray?, val inDim: Long, val outDim: Long, val activation: String?)

/**
 * Writes a feed-forward stack as an ONNX model (opset 13): input [batch, inDim] -> MatMul/Add/activation per layer -> output.
 * The protobuf is encoded by hand so no ONNX library is needed.
 */
fun denseStackToOnnx(layers: List<DenseExport>, inputName: String, outputName: String, graphName: String): ByteArray {
    require(layers.isNotEmpty()) { "No layers to export" }
    val nodes = mutableListOf<ByteArray>()
    val initializers = mutableListOf<ByteArray>()
    var current = inputName
    layers.forEachIndexed { i, layer ->
        val isLast = i == layers.lastIndex
        val w = "dense_${i}_kernel"
        initializers += tensorProto(w, longArrayOf(layer.inDim, layer.outDim), layer.kernel)
        var out = "dense_${i}_matmul"
        nodes += nodeProto("MatMul", listOf(current, w), out, "dense_${i}_MatMul")
        if (layer.bias != null) {
            val b = "dense_${i}_bias"
            initializers += tensorProto(b, longArrayOf(layer.outDim), layer.bias)
            val added = "dense_${i}_add"
            nodes += nodeProto("Add", listOf(out, b), added, "dense_${i}_Add")
            out = added
        }
        if (layer.activation != null) {
            val act = "dense_${i}_${layer.activation.lowercase()}"
            nodes += nodeProto(layer.activation, listOf(out), act, "dense_${i}_${layer.activation}")
            out = act
        }
        if (isLast) {
            nodes += nodeProto("Identity", listOf(out), outputName, "output")
        }
        current = out
    }

    val graph = Proto().apply {
        nodes.forEach { bytes(1, it) }
        string(2, graphName)
        initializers.forEach { bytes(5, it) }
        bytes(11, valueInfo(inputName, layers.first().inDim))
        bytes(12, valueInfo(outputName, layers.last().outDim))
    }.toByteArray()

    return Proto().apply {
        varint(1, 8) // ir_version
        string(2, "amie")
        bytes(7, graph)
        bytes(8, Proto().apply { string(1, ""); varint(2, 13) }.toByteArray()) // opset_import
    }.toByteArray()
}

private fun nodeProto(op: String, inputs: List<String>, output: String, name: String): ByteArray = Proto().apply {
    inputs.forEach { string(1, it) }
    string(2, output)
    string(3, name)
    string(4, op)
}.toByteArray()

private fun tensorProto(name: String, dims: LongArray, values: FloatArray): ByteArray {
    val raw = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    values.forEach { raw.putFloat(it) }
    return Proto().apply {
        dims.forEach { varint(1, it) }
        varint(2, 1) // FLOAT
        string(8, name)
        bytes(9, raw.array())
    }.toByteArray()
}

// Float tensor of shape [batch, dim] with a symbolic batch dimension
private fun valueInfo(name: String, dim: Long): ByteArray {
    val shape = Proto().apply {
        bytes(1, Proto().apply { string(2, "batch") }.toByteArray())
        bytes(1, Proto().apply { varint(1, dim) }.toByteArray())
    }.toByteArray()
    val tensorType = Proto().apply { varint(1, 1); bytes(2, shape) }.toByteArray()
    return Proto().apply {
        string(1, name)
        bytes(2, Proto().apply { bytes(1, tensorType) }.toByteArray())
    }.toByteArray()
}

// Minimal protobuf writer: varint (wire type 0) and length-delimited (wire type 2) fields
private class Proto {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun varint(field: Int, value: Long) { rawVarint((field shl 3).toLong()); rawVarint(value) }
    fun bytes(field: Int, value: ByteArray) { rawVarint(((field shl 3) or 2).toLong()); rawVarint(value.size.toLong()); out.write(value) }
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun toByteArray(): ByteArray = out.toByteArray()
}
