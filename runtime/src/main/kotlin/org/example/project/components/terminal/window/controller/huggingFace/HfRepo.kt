package org.example.project.components.terminal.window.controller.huggingFace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.example.project.components.terminal.window.controller.util.bytes

/** What went wrong bringing a model in, so the page and a script can say it and a test can tell the cases apart */
enum class HfProblem { BAD_INPUT, NOT_FOUND, OFFLINE, GATED, NOTHING_RUNNABLE, DISK, INCOMPLETE, SERVER, TAKEN, DECLINED }

/** A failure with its [problem] and a message in the app's words, ready for a notice or a console line; it never holds the token */
class HfException(val problem: HfProblem, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The formats a model brought in can be opened in; [key] is what `source.json` says, [label] what the page shows */
enum class HfFormat(val key: String, val label: String) {
    ONNX("onnx", "ONNX"),
    TORCHSCRIPT("torchscript", "TorchScript"),
    TENSORFLOW("tensorflow", "TensorFlow SavedModel"),
    TFLITE("tflite", "TFLite");

    companion object {
        fun ofKey(key: String?): HfFormat? = entries.firstOrNull { it.key == key }
    }
}

/** A file of a repo, by its path in the repo; [size] is null when the Hub did not say */
data class HfFile(val path: String, val size: Long?) {
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/', "")
}

/**
 * What a repo's `config.json` says of the model: its kind ([modelType], such as bert or vits), its [architectures]
 * and the [labels] it answers with (`id2label`, in the order of their ids), so a classifier's labels show before it
 * is downloaded and are kept with it after.
 */
data class HfConfig(val modelType: String? = null, val architectures: List<String> = emptyList(), val labels: List<String> = emptyList()) {
    /** "greeting, lights, weather", or the first [most] and how many more */
    fun describeLabels(most: Int = 12): String? = labels.takeIf { it.isNotEmpty() }?.let { l ->
        l.take(most).joinToString() + if (l.size > most) " and ${l.size - most} more" else ""
    }
}

/**
 * A repo at one commit, as the Hub describes it: its id (the Hub's own spelling), the commit, every file, its [task]
 * (the Hub's pipeline tag, null when it has none), its `config.json` once read ([config], by [HfHub.config]) and what was asked
 */
data class HfRepo(
    val id: String,
    val sha: String,
    val files: List<HfFile>,
    /** The Hub asks for its terms to be accepted before the files can be read */
    val gated: Boolean = false,
    val private: Boolean = false,
    val asked: HfRef = HfRef(id),
    val task: String? = null,
    val config: HfConfig? = null,
) {
    val listing: HfListing get() = HfListing.of(files)
}

/**
 * One model of a repo that runs here: its [main] file, the files it can't run without ([parts]: an ONNX file's external
 * data, a SavedModel's `variables/` and `assets/`) and, for an ONNX file, the variant its name says (quantized, int8, fp16…).
 */
data class HfCandidate(val format: HfFormat, val main: HfFile, val parts: List<HfFile> = emptyList(), val variant: String? = null) {
    val files: List<HfFile> get() = listOf(main) + parts
    val size: Long get() = files.sumOf { it.size ?: 0 }
}

/** Files that hold weights but no model that runs by itself: shards of one file count as one row, [label] names them */
data class HfWeights(val label: String, val files: List<HfFile>, val reason: String) {
    val size: Long get() = files.sumOf { it.size ?: 0 }
}

/**
 * What will be downloaded: the chosen model and the config and tokenizer files that go with it. A [text] pick is an
 * ONNX Runtime GenAI export (a `genai_config.json` beside its main file): it lands in AMP/models/text, files flat
 */
/**
 * What to download: the model's files and the companions that go with it. [text] is an ONNX Runtime GenAI chat model,
 * which lands flat in `AMP/models/text`; [speech] a Piper voice (an ONNX with its `.onnx.json` beside it, or a
 * `tokens.txt` or `espeak-ng-data/`), which lands flat in `AMP/models/speech`, made ready for the speech package
 */
data class HfPick(val candidate: HfCandidate, val companions: List<HfFile>, val text: Boolean = false, val speech: Boolean = false, val also: List<HfCandidate> = emptyList()) {
    /** The model's files, the other models that go with it (a pair's decoders) and the companions */
    val files: List<HfFile> get() = (candidate.files + also.flatMap { it.files } + companions).distinctBy { it.path }
    val size: Long get() = files.sumOf { it.size ?: 0 }
    val format: HfFormat get() = candidate.format

    /** "onnx/model.onnx and 4 more, 90.4 MB"; a pair: "onnx/encoder_model.onnx with onnx/decoder_model.onnx and 4 more, 223.8 MB" */
    fun describe(): String = candidate.main.path + (if (also.isEmpty()) "" else " with " + also.joinToString { it.main.path }) +
        (if (files.size > 1 + also.size) " and ${files.size - 1 - also.size} more" else "") + ", " + bytes(size)
}

/**
 * An encoder and decoder pair in one folder, as optimum exports a sequence-to-sequence model: `encoder_model.onnx`
 * with `decoder_model.onnx` and, when there, `decoder_with_past_model.onnx` or `decoder_model_merged.onnx`. One of
 * them alone runs nothing a text can fill, so an import takes the pair whole and initMod opens both sessions.
 */
data class HfPair(val encoders: List<HfCandidate>, val decoders: List<HfCandidate>) {
    val all: List<HfCandidate> get() = encoders + decoders
}

/**
 * The files of a repo sorted for the page: what runs here, by format, the weights that don't, and the config and
 * tokenizer files a model takes along. Only what to show and choose is decided here; opening a model is initMod's part.
 */
data class HfListing(val candidates: List<HfCandidate>, val weights: List<HfWeights>, val companions: List<HfFile>) {
    val byFormat: Map<HfFormat, List<HfCandidate>> get() = HfFormat.entries.associateWith { f -> candidates.filter { it.format == f } }.filterValues { it.isNotEmpty() }

    /**
     * The companions in [candidate]'s folder or a folder above it: `config.json` at the root goes with `onnx/model.onnx`,
     * and so does a `voices/` folder there (a speech model's voices), whole
     */
    fun companionsOf(candidate: HfCandidate): List<HfFile> {
        val dirs = generateSequence(candidate.main.dir) { d -> if (d.isEmpty()) null else d.substringBeforeLast('/', "") }.toSet()
        return companions.filter { home(it) in dirs }
    }

    /** The pair [candidate] belongs to: the ONNX files of its folder and variant named encoder_model and decoder_*; null when it is no pair */
    fun pairOf(candidate: HfCandidate): HfPair? {
        if (candidate.format != HfFormat.ONNX) return null
        val same = candidates.filter { it.format == HfFormat.ONNX && it.main.dir == candidate.main.dir && it.variant == candidate.variant }
        val encoders = same.filter { it.main.name.lowercase().startsWith("encoder_model") }
        val everyDecoder = same.filter { it.main.name.lowercase().startsWith("decoder_") }
        // decoder_model_merged is the plain decoder and the with-past one in one file: left out when both are there
        val merged = everyDecoder.filter { it.main.name.lowercase().contains("merged") }
        val decoders = if (merged.size < everyDecoder.size - 1) everyDecoder - merged.toSet() else everyDecoder
        return if (encoders.isNotEmpty() && decoders.isNotEmpty() && candidate in encoders + everyDecoder) HfPair(encoders, decoders) else null
    }

    fun pick(candidate: HfCandidate): HfPick {
        val dir = candidate.main.dir
        // A pair comes whole, the encoder as its main file
        pairOf(candidate)?.let { pair ->
            val main = if (candidate in pair.encoders) candidate else pair.encoders.first()
            return HfPick(main, companionsOf(main), also = pair.all.filter { it != main })
        }
        val text = candidate.format == HfFormat.ONNX && companions.any { it.dir == dir && it.name == GENAI_CONFIG }
        if (text) return HfPick(candidate, companions.filter { it.dir == dir && it.name in TEXT_COMPANIONS }, text = true)
        val own = companionsOf(candidate)
        val speech = candidate.format == HfFormat.ONNX && (candidate.parts.any { it.path == "${candidate.main.path}.json" } ||
            own.any { (it.dir == dir && it.name == "tokens.txt") || it.path.split('/').contains("espeak-ng-data") })
        // A voice takes what sits beside it and the phoneme data, not a repo's index of every voice above it
        return if (speech) HfPick(candidate, own.filter { it.dir == dir || it.path.split('/').any { part -> part in COMPANION_DIRS } }, speech = true)
        else HfPick(candidate, own)
    }

    /**
     * The model to download when nobody chose: the file an address named, else one in the folder it named (null when
     * nothing runs there), else ONNX
     * first, a plain `model.onnx` before any other (the root's before `onnx/`'s), a full one before a variant and an
     * 8-bit variant before fp16 or 4-bit ones ([VARIANTS]), then the other formats; the shallowest and shortest path
     * breaks a tie.
     */
    fun defaultPick(ref: HfRef? = null): HfCandidate? {
        val path = ref?.path?.trimEnd('/')
        val pool = when {
            path == null -> candidates
            ref.file -> candidates.filter { c -> c.files.any { it.path == path } }
            else -> candidates.filter { it.main.path.startsWith("$path/") }
        }
        // An address that names a file or a folder means that one: with nothing runnable there, nothing is picked in its place
        if (pool.isEmpty()) return null
        return pool.minWithOrNull(
            compareBy<HfCandidate> { it.format.ordinal }
                .thenBy { if (it.main.name == "model.onnx" || it.main.name == "saved_model.pb") 0 else 1 + VARIANTS.indexOf(it.variant).let { i -> if (i < 0) VARIANTS.size else i } }
                .thenBy { it.main.path.count { c -> c == '/' } }
                .thenBy { it.main.path.length }
                .thenBy { it.main.path }
        )
    }

    /** What a repo with no runnable file is told, naming the weights it has instead */
    fun nothingRunnable(repo: String): HfException = HfException(
        HfProblem.NOTHING_RUNNABLE,
        "$repo has nothing that runs here: no ONNX, TorchScript, TensorFlow SavedModel or TFLite file" +
            (weights.firstOrNull()?.let { w -> ", only weights (${w.label}${if (weights.size > 1) " and ${weights.size - 1} more" else ""})" } ?: "") +
            ". An ONNX export of a model often has a repo of its own, such as onnx-community's or Xenova's",
    )

    companion object {
        /**
         * The files that go along with a model when they sit in its folder or above it: the config with the labels,
         * the tokenizer's files, and what a speech or an image model needs besides (its generation config, the tokens,
         * lexicon and voices of a text-to-speech export, a label list)
         */
        val COMPANIONS = setOf(
            "config.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "added_tokens.json",
            "vocab.txt", "vocab.json", "merges.txt", "spiece.model", "sentencepiece.bpe.model", "tokenizer.model", "spm_char.model", "preprocessor_config.json",
            "generation_config.json", GENAI_CONFIG, "normalizer.json", "tokens.txt", "lexicon.txt", "voices.json", "labels.txt", "labels.json", "classes.txt",
        )
        /** What an ONNX Runtime GenAI export keeps beside its decoder, and what of its folder a text pick takes */
        const val GENAI_CONFIG = "genai_config.json"
        val TEXT_COMPANIONS = setOf(GENAI_CONFIG, "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json")
        /** Folders that go along whole: a text-to-speech model's voices, the phoneme data a Piper or VITS export reads */
        val COMPANION_DIRS = setOf("voices", "espeak-ng-data")

        /** The folder a companion belongs to: its own, or for a file of a companion folder the one that folder is in */
        fun home(f: HfFile): String {
            val parts = f.dir.split('/').filter { it.isNotEmpty() }
            val at = parts.indexOfFirst { it in COMPANION_DIRS }
            return if (at < 0) f.dir else parts.take(at).joinToString("/")
        }
        /** The ONNX variants in the order a default prefers them: none, then 8-bit, which the CPU runs well, then the rest */
        val VARIANTS = listOf(null, "quantized", "quant", "int8", "uint8", "q8", "fp16", "q4", "q4f16", "int4", "bnb4")
        private val onnxVariant = Regex("_(quantized|quant|int8|uint8|int4|fp16|q4f16|q4|q8|bnb4|o[1-4])$", RegexOption.IGNORE_CASE)
        private val shard = Regex("^(.*)-\\d{5}-of-(\\d{5})(\\.[^/]+)$")
        private val trainerState = Regex("^(optimizer|scheduler|scaler|rng_state[^/]*)\\.(pt|pth)$")

        fun of(files: List<HfFile>): HfListing {
            val candidates = mutableListOf<HfCandidate>()
            val stray = mutableListOf<Pair<HfFile, String>>()
            for (f in files) {
                val lower = f.name.lowercase()
                when {
                    // Its external data, and the `.onnx.json` a Piper voice keeps its settings in
                    lower.endsWith(".onnx") -> candidates += HfCandidate(
                        HfFormat.ONNX, f,
                        files.filter { o -> o.path != f.path && (o.path == "${f.path}.data" || o.path.startsWith("${f.path}_data") || o.path == "${f.path}.json") },
                        onnxVariant.find(f.name.dropLast(5))?.groupValues?.get(1)?.lowercase(),
                    )
                    trainerState.matches(lower) -> stray += f to "The trainer's state for going on with training, not a model"
                    lower.endsWith(".pt") || lower.endsWith(".pth") -> candidates += HfCandidate(HfFormat.TORCHSCRIPT, f)
                    lower == "saved_model.pb" -> {
                        val dir = if (f.dir.isEmpty()) "" else "${f.dir}/"
                        candidates += HfCandidate(HfFormat.TENSORFLOW, f, files.filter { o ->
                            o.path.startsWith("${dir}variables/") || o.path.startsWith("${dir}assets/") || o.path == "${dir}fingerprint.pb" || o.path == "${dir}keras_metadata.pb"
                        })
                    }
                    lower.endsWith(".tflite") -> candidates += HfCandidate(HfFormat.TFLITE, f)
                    lower.endsWith(".safetensors") -> stray += f to "Weights only: the code that runs them is a Python model class"
                    lower.endsWith(".bin") && lower.startsWith("pytorch_model") -> stray += f to "PyTorch weights for Python's transformers, not a model that runs by itself"
                    lower.endsWith(".gguf") -> stray += f to "For llama.cpp; text models here are ONNX Runtime GenAI exports"
                    lower.endsWith(".h5") -> stray += f to "Keras weights; a SavedModel or TFLite export of them would run"
                }
            }
            // Shards of one file are one row: model-00001-of-00004.safetensors to model-00004-of-00004.safetensors
            val weights = stray.groupBy { (f, reason) -> (shard.find(f.path)?.let { m -> "${m.groupValues[1]}-*-of-${m.groupValues[2]}${m.groupValues[3]}" } ?: f.path) to reason }
                .map { (key, list) -> HfWeights(key.first, list.map { it.first }, key.second) }
                .sortedBy { it.label }
            val companions = files.filter { it.name in COMPANIONS || it.dir.split('/').any { d -> d in COMPANION_DIRS } }
            return HfListing(candidates.sortedBy { it.main.path }, weights, companions)
        }
    }
}

/** The Hub API's answers, read: `/api/models/{repo}` (the commit, the files, gated or not) and `/tree/{rev}?recursive=true` (sizes) */
object HfJson {
    private val json = Json { ignoreUnknownKeys = true }

    /** What `/api/models/{repo}` (or `/revision/{rev}`) says: the repo's own id, its commit, its file paths, gated, private, and its task */
    data class Info(val id: String, val sha: String, val siblings: List<String>, val gated: Boolean, val private: Boolean, val task: String? = null)

    fun info(text: String): Info {
        val root = parse(text) as? JsonObject ?: throw unreadable("a model")
        val id = root.string("id") ?: root.string("modelId") ?: throw unreadable("a model")
        val sha = root.string("sha") ?: throw unreadable("a model's commit")
        val siblings = (root["siblings"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("rfilename") }
        // false, or the kind of gate: "auto" or "manual"
        val gated = when (val g = root["gated"]) {
            is JsonPrimitive -> g.booleanOrNull ?: (g.contentOrNull?.let { it.isNotBlank() && it != "false" } ?: false)
            else -> false
        }
        val private = (root["private"] as? JsonPrimitive)?.booleanOrNull ?: false
        return Info(id, sha, siblings, gated, private, root.string("pipeline_tag"))
    }

    /**
     * A `config.json` read: the model type, the architectures and the labels of `id2label` in the order of their ids.
     * Anything that is not such a file is an empty config, never a failure: a model without labels is a model still
     */
    fun config(text: String): HfConfig {
        val root = parse(text) as? JsonObject ?: return HfConfig()
        val labels = (root["id2label"] as? JsonObject)?.entries
            ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { id -> id to ((v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null) } }
            ?.sortedBy { it.first }?.map { it.second }.orEmpty()
        val architectures = (root["architectures"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return HfConfig(root.string("model_type"), architectures, labels)
    }

    /** The files of one page of `/tree/{rev}?recursive=true`, with sizes: an LFS file's real size, not its pointer's */
    fun tree(text: String): List<HfFile> {
        val entries = parse(text) as? JsonArray ?: throw unreadable("a list of files")
        return entries.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.string("type") != "file") return@mapNotNull null
            val path = o.string("path") ?: return@mapNotNull null
            val lfs = (o["lfs"] as? JsonObject)?.let { (it["size"] as? JsonPrimitive)?.longOrNull }
            HfFile(path, lfs ?: (o["size"] as? JsonPrimitive)?.longOrNull)
        }
    }

    /** The repo's files: the info's list with the tree's sizes, and what the tree has that the list hasn't */
    fun files(info: Info, tree: List<HfFile>): List<HfFile> {
        val sizes = tree.associate { it.path to it.size }
        return (info.siblings.map { HfFile(it, sizes[it]) } + tree.filter { it.path !in info.siblings }).distinctBy { it.path }
    }

    /** The next page of a paged answer, from its `Link: <…>; rel="next"` header */
    fun nextLink(header: String?): String? = header?.split(',')?.firstOrNull { it.contains("rel=\"next\"") }
        ?.substringAfter('<', "")?.substringBefore('>', "")?.takeIf { it.isNotBlank() }

    private fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun unreadable(what: String) = HfException(HfProblem.SERVER, "Hugging Face did not answer with $what; try again in a while")
}
