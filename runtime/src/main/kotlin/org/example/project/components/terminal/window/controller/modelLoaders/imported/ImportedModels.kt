package org.example.project.components.terminal.window.controller.modelLoaders.imported

import org.example.project.amp.ModelFolders
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modelLoaders.builderImplement.onnxModel
import org.example.project.components.terminal.window.controller.modelLoaders.builderImplement.openOnnxModel
import org.example.project.components.terminal.window.controller.modules.OrtScopes
import org.example.project.components.terminal.window.controller.runtime.ScriptRuns
import java.io.File

/** Which model of a folder or path is opened, and how it was picked; or why none is */
sealed interface Resolution {
    /** [candidate] is opened; [note] says why it was picked when there was a choice, null when there was none */
    data class Chosen(val candidate: ModelCandidate, val note: String?) : Resolution

    /** Nothing can be opened: [message] says everything that is missing and what would work */
    data class Refused(val message: String) : Resolution
}

/**
 * The models brought in from outside, one folder each under `AMP/models/imported/<name>` (a Hugging Face repo as it was
 * downloaded, or a folder copied in by hand), and any model file or folder given by its path. Either opens on the
 * engine of its format: ONNX on the app's own ONNX Runtime, TorchScript on PyTorch and a SavedModel on TensorFlow,
 * both DJL engines ([ModelEngines]); TFLite and weights alone are refused, with the way to a format that runs.
 *
 * In a folder of several formats ONNX comes first, since it runs on the bundled runtime; then the format its
 * `source.json` names; then the first that runs here, and the console says which was taken.
 */
object ImportedModels {
    /** `AMP/models/imported` */
    val dir: File get() = ModelFolders.amp.imported

    /** The folder of [name] under [root]; null for a name that is a path or climbs out of it */
    fun folder(name: String, root: File = dir): File? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == ".." || '/' in trimmed || '\\' in trimmed) return null
        return File(root, trimmed)
    }

    /** Whether [name] is an imported model: a folder of that name under [root] with a model in it of a format the app knows */
    fun has(name: String, root: File = dir): Boolean {
        val folder = folder(name, root)?.takeIf { it.isDirectory } ?: return false
        val scan = ModelFormats.scan(folder)
        return scan.candidates.isNotEmpty() || scan.refused.isNotEmpty() || scan.weightsOnly.isNotEmpty()
    }

    /** The names of the imported models under [root] */
    fun names(root: File = dir): List<String> = root.listFiles().orEmpty().filter { it.isDirectory && has(it.name, root) }.map { it.name }.sorted()

    /** Whether initMod should read [name] as a path: it has a separator, starts at home, or ends in a model's extension */
    fun looksLikePath(name: String): Boolean {
        val trimmed = name.trim()
        return '/' in trimmed || '\\' in trimmed || trimmed.startsWith("~") ||
            trimmed.substringAfterLast('.', "").lowercase() in setOf("onnx", "pt", "pth", "torchscript", "pb", "tflite", "safetensors", "bin", "gguf", "h5", "keras")
    }

    /** [path] with a leading `~` at home */
    fun expand(path: String): File {
        val trimmed = path.trim()
        return if (trimmed == "~" || trimmed.startsWith("~/")) File(System.getProperty("user.home") + trimmed.substring(1)) else File(trimmed)
    }

    /**
     * Which model of [scan] is opened, in the order: ONNX, the format `source.json` names, the first that runs here.
     * [missing] says why a format can't run here (null when it can); nothing is loaded to pick
     */
    fun resolve(scan: FolderScan, what: String, missing: (ModelFormat) -> String? = { ModelEngines.missing(it) }): Resolution {
        val formats = scan.candidates.map { it.format }.distinct()
        val runnable = scan.candidates.filter { missing(it.format) == null }
        fun several(format: ModelFormat) = scan.of(format).size > 1
        fun pickedOf(candidate: ModelCandidate) = if (several(candidate.format)) ", ${candidate.file.relativeToOrSelf(scan.dir).invariantSeparatorsPath} of ${scan.of(candidate.format).size}" else ""

        scan.of(ModelFormat.ONNX).firstOrNull()?.let { onnx ->
            val others = formats - ModelFormat.ONNX
            val note = if (others.isEmpty() && !several(ModelFormat.ONNX)) null
                else "$what has ${(listOf(ModelFormat.ONNX) + others).joinToString { it.label }}; ONNX taken, which runs on the bundled runtime${pickedOf(onnx)}"
            return Resolution.Chosen(onnx, note)
        }
        val named = scan.source?.modelFormat
        var namedNote = ""
        if (named != null) {
            val ofNamed = scan.of(named)
            val why = if (ofNamed.isEmpty()) "it has no ${named.label} file" else missing(named)
            if (why == null) {
                val note = if (formats.size > 1 || several(named)) "$what has ${formats.joinToString { it.label }}; ${named.label} taken, as its source.json says${pickedOf(ofNamed.first())}" else null
                return Resolution.Chosen(ofNamed.first(), note)
            }
            namedNote = "source.json names ${named.label}, but $why; "
        }
        runnable.firstOrNull()?.let { first ->
            val note = if (formats.size > 1 || several(first.format) || namedNote.isNotEmpty())
                "$what has ${formats.joinToString { it.label }}; ${namedNote}${first.format.label} taken, the first that runs here${pickedOf(first)}" else null
            return Resolution.Chosen(first, note)
        }
        return Resolution.Refused(refusal(scan, what, missing))
    }

    /** Everything a folder with nothing to open lacks, and what would work */
    internal fun refusal(scan: FolderScan, what: String, missing: (ModelFormat) -> String?): String {
        val reasons = buildList {
            scan.candidates.map { it.format }.distinct().forEach { format ->
                val files = scan.of(format).joinToString { it.file.relativeToOrSelf(scan.dir).invariantSeparatorsPath.ifEmpty { it.file.name } }
                add("$files (${format.label}): ${missing(format)}")
            }
            scan.refused.forEach { add("${it.file.relativeToOrSelf(scan.dir).invariantSeparatorsPath.ifEmpty { it.file.name }}: ${it.reason}") }
            if (scan.weightsOnly.isNotEmpty()) add("${scan.weightsOnly.joinToString { it.relativeToOrSelf(scan.dir).invariantSeparatorsPath.ifEmpty { it.name } }}: weights only, which no engine runs without the model's Python code")
        }
        val head = if (reasons.isEmpty()) "$what has no model the app knows (ONNX, TorchScript, a TensorFlow SavedModel or TFLite)"
            else "$what can't be opened: ${reasons.joinToString("; ")}"
        val advice = if (scan.weightsOnly.isNotEmpty() || scan.refused.isNotEmpty() || reasons.isEmpty()) ". $WORKS" else ""
        return head + advice
    }

    /** What would work instead of weights alone */
    const val WORKS = "What would work: export it to ONNX (optimum-cli export onnx --model <repo> <folder>, or torch.onnx.export) or to TorchScript " +
        "(torch.jit.trace(model, example).save(\"model.pt\")), or take the repo's onnx/ folder when it has one"

    /** What a file or folder given by its path is, as a scan: a folder is scanned, a file is that file alone */
    fun scanPath(file: File): FolderScan {
        if (file.isDirectory) return ModelFormats.scan(file)
        val home = file.absoluteFile.parentFile
        return when (val found = ModelFormats.ofPath(file)) {
            is ModelCandidate -> FolderScan(home, listOf(found), emptyList(), emptyList(), null)
            is RefusedFile -> if (found.reason == ModelFormats.WEIGHTS) FolderScan(home, emptyList(), emptyList(), listOf(file), null)
                else FolderScan(home, emptyList(), listOf(found), emptyList(), null)
            null -> FolderScan(home, emptyList(), emptyList(), emptyList(), null)
        }
    }

    /**
     * Opens the imported model [name], or the model at [path] when [name] is null: the resolved model on its engine,
     * the console saying what was taken when there was a choice. A refusal throws with all of what is missing
     */
    fun open(name: String? = null, path: File? = null, root: File = dir): InteractionModel {
        val (what, target, folderRoot) = when {
            name != null -> {
                val folder = folder(name, root)?.takeIf { it.isDirectory } ?: throw IllegalArgumentException("no imported model '$name' in $root")
                Triple("imported model '$name'", folder, folder)
            }
            path != null -> {
                require(path.exists()) { "no model at $path" }
                // A repo's onnx/model.onnx keeps its tokenizer and config one folder up
                val parent = path.absoluteFile.parentFile
                Triple(path.name, path, if (path.isDirectory) path else if (parent.name == "onnx") parent.parentFile else parent)
            }
            else -> throw IllegalArgumentException("a name or a path is needed")
        }
        val scan = if (name != null) ModelFormats.scan(target) else scanPath(target)
        // An encoder and decoder pair: both sessions, for a script to wire
        EncoderDecoderModel.of(scan)?.let { (encoder, decoder, withPast) ->
            val pairHomes = homesOf(encoder, folderRoot)
            logInternal("initMod: $what is an encoder and decoder pair; .encoder and .decoder are its sessions, .tokens(text) its tokenizer")
            return EncoderDecoderModel(name ?: encoder.parentFile.name, openOnnxModel(encoder.toPath(), pairHomes), openOnnxModel(decoder.toPath(), pairHomes), withPast?.let { openOnnxModel(it.toPath(), pairHomes) }, pairHomes)
        }
        val chosen = when (val resolution = resolve(scan, what)) {
            is Resolution.Refused -> throw IllegalStateException(resolution.message)
            is Resolution.Chosen -> resolution.also { r -> r.note?.let { logInternal("initMod: $it") } }.candidate
        }
        val shown = name ?: chosen.file.nameWithoutExtension.ifEmpty { chosen.file.name }
        // The tokenizer and the labels: next to the model, else up to the folder the model was asked for
        val homes = homesOf(chosen.file, folderRoot)
        return when (chosen.format) {
            ModelFormat.ONNX -> openOnnxModel(chosen.file.toPath(), homes)
            else -> DjlModel.open(shown, chosen.format, chosen.file, homes)
        }
    }

    /**
     * [model] held as initMod holds what it opens: put down to the script calling, so the Sessions tab lists it under
     * that script, and closed with the ortClosable block around the call or kept by a static, timed or limited one
     */
    internal fun held(model: InteractionModel): InteractionModel = when (model) {
        is EncoderDecoderModel -> model.also { pair -> listOfNotNull(pair.encoder, pair.decoder, pair.decoderWithPast).forEach { held(it) } }
        is DjlModel -> OrtScopes.opened(model).also { ScriptRuns.opened("djl:${it.id}") }
        is onnxModel -> {
            model.ortSession?.let { ScriptRuns.opened("ort:${it.id}") }
            OrtScopes.opened(model, "ONNX ${model.name}") { model.ortSession }
        }
        else -> model
    }

    /** The folders from the model's own up to [top], nearest first; just the model's own without [top], and the two when [top] is not above it */
    internal fun homesOf(model: File, top: File?): List<File> {
        val start = (if (model.isDirectory) model else model.absoluteFile.parentFile).absoluteFile
        val stop = top?.absoluteFile ?: return listOf(start)
        val chain = generateSequence(start) { it.parentFile }.takeWhile { it.path.startsWith(stop.path) }.toList()
        return if (chain.lastOrNull() == stop) chain else listOf(start, stop).distinct()
    }
}
