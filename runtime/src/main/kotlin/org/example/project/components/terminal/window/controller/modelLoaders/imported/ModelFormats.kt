package org.example.project.components.terminal.window.controller.modelLoaders.imported

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * The kinds of model file the app can be handed, each with the engine that runs it and the word `source.json` uses
 * for it. ONNX runs on the app's own ONNX Runtime; the others on a DJL engine whose natives come on first use.
 */
enum class ModelFormat(val label: String, val engine: String, val word: String) {
    ONNX("ONNX", "ONNX Runtime", "onnx"),
    TORCHSCRIPT("TorchScript", "PyTorch", "torchscript"),
    TENSORFLOW("TensorFlow SavedModel", "TensorFlow", "tensorflow"),
    TFLITE("TFLite", "TFLite", "tflite");

    companion object {
        /** The format `source.json` names, by its word or its name in any case; null for anything else */
        fun parse(word: String?): ModelFormat? = word?.trim()?.let { w -> entries.firstOrNull { it.word.equals(w, true) || it.name.equals(w, true) } }
    }
}

/** What a file or folder is to [ModelFormats.ofPath]: a model an engine may run, or one it refuses */
sealed interface FoundModel

/** One model in a folder: a file, or for a SavedModel the folder that holds `saved_model.pb` */
data class ModelCandidate(val format: ModelFormat, val file: File) : FoundModel

/** A file of the folder that is a model of a sort no engine here runs, and why */
data class RefusedFile(val file: File, val reason: String) : FoundModel

/**
 * The `source.json` an import writes next to the model: where it came from and the format it was taken for. Optional,
 * since a folder may be copied in by hand; every field may be missing.
 */
@Serializable
data class ModelSource(
    val source: String? = null,
    val repo: String? = null,
    val revision: String? = null,
    val format: String? = null,
    val files: List<SourceFile> = emptyList(),
    val downloadedAt: String? = null,
) {
    @Serializable
    data class SourceFile(val path: String, val size: Long? = null)

    /** The format it names, null when it names none the app knows */
    val modelFormat: ModelFormat? get() = ModelFormat.parse(format)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The `source.json` of [dir]; null when there is none or it can't be read, which is as good as none */
        fun read(dir: File): ModelSource? = File(dir, "source.json").takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(serializer(), it.readText()) }.getOrNull() }
    }
}

/** What a folder holds: the models an engine could run, the files that are models no engine here runs, and weights alone */
data class FolderScan(
    val dir: File,
    val candidates: List<ModelCandidate>,
    val refused: List<RefusedFile>,
    val weightsOnly: List<File>,
    val source: ModelSource?,
) {
    fun of(format: ModelFormat): List<ModelCandidate> = candidates.filter { it.format == format }
}

/**
 * Tells a model's format from its files: by extension, and for `.pt` and `.pth`, which PyTorch also uses for a pickle of
 * weights, by looking inside. Reads only what is on disk; no engine is loaded.
 */
object ModelFormats {
    private val torchExtensions = setOf("pt", "pth", "torchscript")
    private val weightExtensions = setOf("safetensors", "gguf", "ggml", "h5", "hdf5", "keras", "ckpt", "msgpack", "npz", "joblib", "pkl", "pickle")
    // Hugging Face's weight files that have an extension of no use to tell them by
    private val weightNames = Regex("""(pytorch_model|tf_model|flax_model|model|diffusion_pytorch_model)(-\d+-of-\d+)?\.(bin|h5|msgpack)""")
    // Folders of a repo that hold no model of the repo's own
    private val skipped = setOf(".git", ".cache", ".huggingface", "__pycache__")

    /**
     * The format of one file or folder; a [RefusedFile] when it is a model no engine here runs, null when it isn't a
     * model at all (a tokenizer, a config, a README)
     */
    fun ofPath(file: File): FoundModel? {
        if (file.isDirectory) return if (File(file, "saved_model.pb").isFile) ModelCandidate(ModelFormat.TENSORFLOW, file) else null
        val name = file.name.lowercase()
        val extension = file.extension.lowercase()
        return when {
            extension == "onnx" -> ModelCandidate(ModelFormat.ONNX, file)
            name == "saved_model.pb" -> ModelCandidate(ModelFormat.TENSORFLOW, file.absoluteFile.parentFile)
            extension == "pb" -> RefusedFile(file, "a frozen TensorFlow graph: TensorFlow is opened as a SavedModel folder (saved_model.pb and variables/) only")
            extension == "tflite" -> ModelCandidate(ModelFormat.TFLITE, file)
            extension in torchExtensions -> when (torchKind(file)) {
                TorchKind.SCRIPT -> ModelCandidate(ModelFormat.TORCHSCRIPT, file)
                TorchKind.WEIGHTS -> RefusedFile(file, "PyTorch weights saved with torch.save, not a TorchScript model: they need the model's Python code to run")
                TorchKind.UNREADABLE -> RefusedFile(file, "not a TorchScript archive (a TorchScript file is a zip with the model's code in it)")
            }
            extension in weightExtensions || weightNames.matches(name) -> RefusedFile(file, WEIGHTS)
            else -> null
        }
    }

    /** Every model in [dir] and its subfolders, a few levels down, as a Hugging Face repo lays them out */
    fun scan(dir: File, depth: Int = 3): FolderScan {
        val candidates = ArrayList<ModelCandidate>()
        val refused = ArrayList<RefusedFile>()
        val weights = ArrayList<File>()
        fun walk(folder: File, level: Int) {
            val children = folder.listFiles().orEmpty().filter { !it.name.startsWith(".") }.sortedBy { it.name }
            children.filter { it.isFile }.forEach { file ->
                when (val found = ofPath(file)) {
                    is ModelCandidate -> if (candidates.none { it.file == found.file }) candidates += found
                    is RefusedFile -> if (found.reason == WEIGHTS) weights += file else refused += found
                    null -> Unit
                }
            }
            if (level >= depth) return
            children.filter { it.isDirectory && it.name !in skipped && !File(folder, "saved_model.pb").isFile }.forEach { walk(it, level + 1) }
        }
        walk(dir, 0)
        return FolderScan(dir, candidates.sortedWith(compareBy({ it.format.ordinal }, { preference(dir, it.file) })), refused, weights, ModelSource.read(dir))
    }

    /**
     * Which of several models of one format comes first: `model.onnx` at the top before `onnx/model.onnx`, a full model
     * before a quantized or reduced one, then the shallower and the shorter name
     */
    private fun preference(dir: File, file: File): String {
        val relative = file.relativeToOrSelf(dir).invariantSeparatorsPath
        val depth = relative.count { it == '/' }
        val plain = file.nameWithoutExtension.lowercase() in setOf("model", "saved_model", "traced", "scripted")
        val reduced = Regex("""quant|int8|uint8|_q4|fp16|bnb4|_o\d""").containsMatchIn(file.nameWithoutExtension.lowercase())
        return "${if (reduced) 1 else 0}${if (plain) 0 else 1}$depth${relative.length.toString().padStart(4, '0')}$relative"
    }

    internal enum class TorchKind { SCRIPT, WEIGHTS, UNREADABLE }

    /**
     * A TorchScript file is a zip with the model's code (`<name>/code/…`) and `constants.pkl`; torch.save writes a zip
     * with `data.pkl` and no code, or before PyTorch 1.6 a bare pickle
     */
    internal fun torchKind(file: File): TorchKind = runCatching {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            when {
                names.any { it.endsWith("/constants.pkl") || it.contains("/code/") } -> TorchKind.SCRIPT
                names.any { it.endsWith("data.pkl") } -> TorchKind.WEIGHTS
                else -> TorchKind.UNREADABLE
            }
        }
    }.getOrElse { if (isPickle(file)) TorchKind.WEIGHTS else TorchKind.UNREADABLE }

    // A pickle starts with the PROTO opcode 0x80 and a protocol number
    private fun isPickle(file: File): Boolean = runCatching {
        file.inputStream().use { val head = ByteArray(2); it.read(head) == 2 && head[0] == 0x80.toByte() && head[1] in 2..5 }
    }.getOrDefault(false)

    internal const val WEIGHTS = "weights only"
}
