package org.example.project.components.terminal.window.controller.huggingFace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the search looks for: one of the formats initMod opens, or all of them. [tag] is the Hub's tag the search
 * filters on; TorchScript has none of its own, so it asks for `pytorch` and keeps only the repos with a `.pt` or `.pth`
 * file, since that tag mostly means weights alone.
 */
enum class HfSearchFormat(val label: String, val key: String, val tag: String?, val format: HfFormat?, val moreTags: List<String> = emptyList()) {
    ALL("All supported", "all", null, null),
    ONNX("ONNX", "onnx", "onnx", HfFormat.ONNX),
    TORCHSCRIPT("TorchScript", "torchscript", "pytorch", HfFormat.TORCHSCRIPT),
    // The Hub's `tf` repos are nearly all tf_model.h5 Keras weights, which don't run here; the SavedModels are tagged tf-keras
    TENSORFLOW("TensorFlow", "tensorflow", "tf-keras", HfFormat.TENSORFLOW, moreTags = listOf("tf")),
    TFLITE("TFLite", "tflite", "tflite", HfFormat.TFLITE);

    /** The tags one search asks for: "All supported" asks for every format's and merges the answers */
    val tags: List<String> get() = if (this == ALL) entries.flatMap { listOfNotNull(it.tag) + it.moreTags } else listOfNotNull(tag) + moreTags

    companion object {
        /** A format as a script writes it: onnx, torchscript, tensorflow (or tf), tflite, all; null for anything else */
        fun of(text: String): HfSearchFormat? {
            val t = text.trim().lowercase()
            return entries.firstOrNull { it.key == t || it.label.lowercase() == t } ?: if (t == "tf") TENSORFLOW else if (t == "" || t == "any") ALL else null
        }
    }
}

/**
 * What the search looks for besides a format: the Hub's task (its `pipeline_tag`), so that a text-to-speech model or a
 * classifier with its labels is found as such, not only by a word of its name. [tag] is what the Hub is asked for;
 * [words] are what a script may write for it besides the tag and the label (tts, ner, embeddings).
 */
enum class HfSearchKind(val label: String, val tag: String?, vararg val words: String) {
    ANY("Any task", null, "any", "all", ""),
    TEXT_CLASSIFICATION("Text classification", "text-classification", "classification", "classifier", "labels", "sentiment", "intent"),
    TOKEN_CLASSIFICATION("Token classification", "token-classification", "ner", "entities", "tokens"),
    FEATURE_EXTRACTION("Feature extraction", "feature-extraction", "embedding", "embeddings", "encoder", "sentence-similarity"),
    TEXT_GENERATION("Text generation", "text-generation", "generation", "llm", "chat"),
    TEXT_TO_SPEECH("Text to speech", "text-to-speech", "tts", "speech", "voice"),
    SPEECH_RECOGNITION("Speech recognition", "automatic-speech-recognition", "asr", "transcription", "whisper", "speech-to-text", "stt"),
    AUDIO_CLASSIFICATION("Audio classification", "audio-classification", "audio"),
    IMAGE_CLASSIFICATION("Image classification", "image-classification", "image", "vision");

    /** The Hub's `pipeline_tag` query, nothing for Any */
    val query: String? get() = tag

    companion object {
        /** A task as a script writes it: its tag (text-to-speech), its label (Text to speech) or a word for it (tts); null for anything else */
        fun of(text: String): HfSearchKind? {
            val t = text.trim().lowercase().replace(Regex("[\\s_]+"), "-")
            return entries.firstOrNull { k -> k.tag == t || k.label.lowercase().replace(' ', '-') == t || t in k.words }
        }

        /** The task of a result or a model as the page says it: "Text to speech" for text-to-speech, any other tag with its hyphens made spaces */
        fun describe(tag: String?): String? = tag?.let { t -> entries.firstOrNull { it.tag == t }?.label ?: t.replace('-', ' ').replaceFirstChar { c -> c.uppercase() } }
    }
}

/**
 * One repo the search found: its id, its task (the Hub's pipeline_tag), downloads, likes and the formats it has that
 * run here. With [filesKnown] they are read off its files; without (the Hub did not list them) they come from its tags
 * alone, and the page says to check the files.
 */
data class HfSearchResult(val id: String, val task: String?, val downloads: Long, val likes: Long, val formats: Set<HfFormat>, val filesKnown: Boolean)

/** A page of results, and where each tag's next page starts; [more] while any has one */
data class HfSearchPage(val results: List<HfSearchResult>, val cursors: Map<String, String> = emptyMap()) {
    val more: Boolean get() = cursors.isNotEmpty()
}

/** The search's answers read, and sorted into what a format asks for */
object HfSearchJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val tagFormats = mapOf("onnx" to HfFormat.ONNX, "tflite" to HfFormat.TFLITE)

    /** The rows of `/api/models?…`: a list of repos, each with its siblings when `full=true` was asked */
    fun results(text: String): List<HfSearchResult> {
        val rows = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray
            ?: throw HfException(HfProblem.SERVER, "Hugging Face did not answer the search with a list; try again in a while")
        return rows.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.string("id") ?: o.string("modelId") ?: return@mapNotNull null
            val siblings = (o["siblings"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.string("rfilename") }
            val formats = siblings?.let { s -> HfListing.of(s.map { HfFile(it, null) }).candidates.map { it.format }.toSet() }
                // From the tags alone: onnx and tflite say what they mean; tf mostly means Keras weights, and pytorch weights, so neither counts
                ?: (o["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(tagFormats::get) }.toSet()
            HfSearchResult(id, o.string("pipeline_tag"), o.long("downloads"), o.long("likes"), formats, filesKnown = siblings != null)
        }
    }

    /** What a search for [format] keeps: a repo with a file in it (any supported one for All), or one whose files the Hub did not list */
    fun keep(result: HfSearchResult, format: HfSearchFormat): Boolean = when {
        !result.filesKnown -> true
        format == HfSearchFormat.ALL -> result.formats.isNotEmpty()
        else -> format.format in result.formats
    }

    /** The answers of each tag as one list: each repo once, the most downloaded first */
    fun merge(pages: List<List<HfSearchResult>>, format: HfSearchFormat): List<HfSearchResult> =
        pages.flatten().filter { keep(it, format) }.sortedByDescending { it.downloads }.distinctBy { it.id }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0
}

/**
 * The search as the form runs it: typing waits [debounceMs] before it asks, a new ask cancels the one before (its
 * request too, so a slow answer to old text never shows), and [more] fetches the next page of every tag that has one.
 * [search] asks the Hub (tests hand in their own).
 */
class HfSearchRunner(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 300,
    private val search: suspend (String, HfSearchFormat, HfSearchKind, Map<String, String>?) -> HfSearchPage,
) {
    data class State(
        val text: String = "",
        val format: HfSearchFormat = HfSearchFormat.ALL,
        val kind: HfSearchKind = HfSearchKind.ANY,
        val results: List<HfSearchResult> = emptyList(),
        val more: Boolean = false,
        val searching: Boolean = false,
        val problem: String? = null,
        /** An answer came for this text and format: an empty list then means nothing matched, not "not asked yet" */
        val answered: Boolean = false,
    )

    private val state = MutableStateFlow(State())
    val current: StateFlow<State> = state.asStateFlow()
    private var job: Job? = null
    private var cursors: Map<String, String> = emptyMap()

    fun ask(text: String, format: HfSearchFormat, kind: HfSearchKind = HfSearchKind.ANY) {
        job?.cancel()
        cursors = emptyMap()
        state.value = State(text, format, kind, searching = true)
        job = scope.launch {
            delay(debounceMs)
            run(text, format, kind, null)
        }
    }

    fun more() {
        val s = state.value
        if (!s.more || s.searching) return
        job?.cancel()
        val from = cursors
        state.value = s.copy(searching = true, problem = null)
        job = scope.launch { run(s.text, s.format, s.kind, from) }
    }

    private suspend fun run(text: String, format: HfSearchFormat, kind: HfSearchKind, from: Map<String, String>?) {
        try {
            val page = search(text, format, kind, from)
            cursors = page.cursors
            val shown = if (from == null) page.results else (state.value.results + page.results).distinctBy { it.id }
            state.value = State(text, format, kind, shown, page.more, searching = false, answered = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HfException) {
            state.value = state.value.copy(searching = false, problem = e.message, answered = true)
        } catch (e: Exception) {
            state.value = state.value.copy(searching = false, problem = "The search stopped: ${e.message ?: e.javaClass.simpleName}", answered = true)
        }
    }
}

/**
 * `huggingFace` in a script, beside `huggingFace("owner/repo")`, which brings one in: huggingFace.search("bert",
 * format = "onnx") finds models of the Hub in a format initMod opens, and huggingFace.search("", task = "text-to-speech")
 * the ones of a task. [say] prints at the script's info level.
 */
class HuggingFaceScript(private val say: (String) -> Unit) {
    /**
     * The repo ids of up to [limit] models matching [text], the most downloaded first, in [format] (onnx, torchscript,
     * tensorflow, tflite, or all, the default) and of [task] (the Hub's pipeline tag, such as text-to-speech or
     * text-classification, a word for it such as tts or ner, or any, the default); each found one is printed with its
     * task, downloads and formats. Blank [text] lists the most downloaded of the format and task. Pass an id to
     * huggingFace("…") to bring it in
     */
    fun search(text: String = "", format: String = "all", limit: Int = 20, task: String = "any"): List<String> {
        val f = HfSearchFormat.of(format) ?: throw HfException(HfProblem.BAD_INPUT, "huggingFace.search: format is onnx, torchscript, tensorflow, tflite or all, not \"$format\"")
        val k = HfSearchKind.of(task) ?: throw HfException(HfProblem.BAD_INPUT, "huggingFace.search: task is a Hugging Face task, ${HfSearchKind.entries.mapNotNull { it.tag }.joinToString()} or any, not \"$task\"")
        val page = runBlocking { HfImports.search(text, f, k, null, limit.coerceIn(1, 100)) }
        val found = page.results.take(limit)
        if (found.isEmpty()) say("huggingFace.search: nothing on Hugging Face matches \"$text\" in ${f.label}" + (k.tag?.let { " for ${k.label.lowercase()}" } ?: ""))
        found.forEach { r -> say("${r.id} · ${describe(r)}") }
        return found.map { it.id }
    }

    companion object {
        /** "Text classification · 1.2M downloads · 34 likes · ONNX, TFLite" */
        fun describe(r: HfSearchResult): String = listOfNotNull(
            HfSearchKind.describe(r.task),
            "${count(r.downloads)} downloads",
            "${count(r.likes)} likes",
            if (r.filesKnown) r.formats.sortedBy { it.ordinal }.joinToString { it.label }.ifEmpty { null } else "Check the files",
        ).joinToString(" · ")

        fun count(n: Long): String = when {
            n >= 1_000_000 -> "%.1fM".format(java.util.Locale.US, n / 1_000_000.0)
            n >= 1_000 -> "%.1fK".format(java.util.Locale.US, n / 1_000.0)
            else -> n.toString()
        }
    }
}
