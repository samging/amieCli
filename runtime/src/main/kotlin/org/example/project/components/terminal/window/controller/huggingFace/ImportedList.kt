package org.example.project.components.terminal.window.controller.huggingFace

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.amp.ModelFolders
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.util.bytes
import java.io.File

/** A file of an imported model, by its path in the repo, and its size on disk */
@Serializable
data class ImportedFile(val path: String, val size: Long)

/**
 * `AMP/models/imported/<name>/source.json`: where the model came from. The keys are a contract with initMod, which
 * opens the folder in [format] (`onnx`, `torchscript`, `tensorflow` or `tflite`). [task] is the Hub's pipeline tag
 * (text-classification, text-to-speech…) and [labels] what its `config.json` says it answers with, so the page can
 * say both without opening the model; a `source.json` from before has neither.
 */
@Serializable
data class ImportedSource(
    val source: String = "huggingface",
    val repo: String,
    val revision: String,
    val format: String,
    val files: List<ImportedFile>,
    val downloadedAt: String,
    val task: String? = null,
    val labels: List<String> = emptyList(),
)

/** A model folder under `AMP/models/imported` as the Models page lists it; [source] is null for a folder put there by hand */
data class ImportedModel(val name: String, val dir: File, val source: ImportedSource?, val format: HfFormat?, val bytes: Long) {
    /** The Hub's task, as the page says it: "Text to speech"; null for a model whose source says none */
    val task: String? get() = HfSearchKind.describe(source?.task)
    val labels: List<String> get() = source?.labels.orEmpty()

    /** Found by its name, repo, format, task or one of its labels */
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || name.lowercase().contains(q) || source?.repo?.lowercase()?.contains(q) == true || format?.label?.lowercase()?.contains(q) == true ||
            task?.lowercase()?.contains(q) == true || source?.task?.contains(q) == true || labels.any { it.lowercase().contains(q) }
    }
}

/** The models of `AMP/models/imported`: listed, read, removed. Their folders are made by [HfHub.download] alone */
object ImportedList {
    const val SOURCE = "source.json"
    private const val INCOMING = ".incoming"
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    /** Every model folder, by name whatever its case; `.incoming` and other dot folders are downloads under way, not models */
    fun list(folders: ModelFolders): List<ImportedModel> =
        (folders.imported.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { dir ->
                val source = read(dir)
                val format = HfFormat.ofKey(source?.format) ?: guessFormat(dir)
                ImportedModel(dir.name, dir, source, format, source?.files?.sumOf { it.size } ?: dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
            }

    /** The `source.json` of a model folder, null when it has none or it can't be read */
    fun read(dir: File): ImportedSource? = File(dir, SOURCE).takeIf { it.isFile }?.let { f -> runCatching { json.decodeFromString(ImportedSource.serializer(), f.readText()) }.getOrNull() }

    fun write(dir: File, source: ImportedSource) {
        File(dir, SOURCE).writeText(json.encodeToString(ImportedSource.serializer(), source))
    }

    /** Whether [name] is a model folder (or anything else) under `AMP/models/imported` */
    fun has(folders: ModelFolders, name: String): Boolean = File(folders.imported, name).exists()

    /** Where [name] comes in while it downloads */
    fun stagingOf(folders: ModelFolders, name: String): File = File(File(folders.imported, INCOMING), name)

    /** Throws away what a download of [name] left in `.incoming`: a cancel means nothing of it is wanted */
    fun discard(folders: ModelFolders, name: String) {
        if (safe(name)) stagingOf(folders, name).deleteRecursively()
    }

    /** Removes the model folder [name]; false when there is none or not all of it could go */
    fun delete(folders: ModelFolders, name: String): Boolean {
        if (!safe(name)) return false
        val dir = File(folders.imported, name)
        return dir.isDirectory && dir.deleteRecursively()
    }

    private fun safe(name: String) = name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name

    private fun guessFormat(dir: File): HfFormat? {
        val files = dir.walkTopDown().filter { it.isFile }.map { HfFile(it.relativeTo(dir).invariantSeparatorsPath, it.length()) }.toList()
        return HfListing.of(files).defaultPick()?.format
    }
}

/**
 * Downloads from the Hub for the whole app, apart from any page: the Models page starts one with [start] and follows
 * [current], which outlives the page, so going to the sandbox and back leaves a download running. A script brings one
 * in with [ensure], on its own thread. One name downloads once at a time, whoever asked.
 */
object HfImports {
    sealed interface State {
        val name: String
        val repo: String
    }
    data class Running(override val name: String, override val repo: String, val progress: HfProgress? = null) : State
    data class Done(override val name: String, override val repo: String, val format: HfFormat, val bytes: Long, val place: String = "AMP/models/imported/$name") : State
    data class Failed(override val name: String, override val repo: String, val problem: HfProblem, val message: String) : State
    data class Cancelled(override val name: String, override val repo: String) : State

    private val state = MutableStateFlow<State?>(null)
    /** The page's download: running, or how the last one ended until the page has said so ([seen]) */
    val current: StateFlow<State?> = state.asStateFlow()

    @Volatile private var client: HttpClient? = null
    /** The client the Hub is asked through; tests hand in one on a mock engine. Made at the first ask */
    internal var http: HttpClient
        get() = client ?: synchronized(this) { client ?: HfHub.client(Java.create()).also { client = it } }
        set(value) { client = value }
    /** The Hub's address; tests point it elsewhere */
    internal var base: String = HfHub.BASE
    /** Where the models go; tests hand in a folder of their own */
    internal var folders: () -> ModelFolders = { ModelFolders.amp }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    private val busy = mutableSetOf<String>()

    /** `HF_TOKEN` from the environment, the one place a token comes from; never logged, printed or stored */
    fun token(): String? = System.getenv("HF_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }

    fun hub(): HfHub = HfHub(http, base, token())

    /** What [input] (a repo id or a huggingface.co address) names on the Hub, with its `config.json` read for its kind and labels; failures are [HfException]s */
    suspend fun lookUp(input: String): HfRepo {
        val hub = hub()
        val repo = hub.look(HfRef.parse(input))
        return repo.copy(config = hub.config(repo))
    }

    /** Models of the Hub matching [text] in [format] and of [kind]; [cursors] for the next page. Failures are [HfException]s */
    suspend fun search(text: String, format: HfSearchFormat, kind: HfSearchKind = HfSearchKind.ANY, cursors: Map<String, String>? = null, limit: Int = HfHub.SEARCH_LIMIT): HfSearchPage = hub().search(text, format, kind, cursors, limit)

    /** Starts the page's download of [pick] as [name]; false while one runs */
    fun start(repo: HfRepo, pick: HfPick, name: String): Boolean {
        if (state.value is Running) return false
        if (!claim(name)) {
            state.value = Failed(name, repo.id, HfProblem.TAKEN, "$name is being downloaded already, by a script")
            return true
        }
        state.value = Running(name, repo.id)
        job = scope.launch {
            try {
                val dir = hub().download(repo, pick, name, folders(), onProgress = { p -> if (state.value is Running) state.value = Running(name, repo.id, p) })
                val place = HfHub.ampPath(pick, dir.name)
                logInternal("huggingFace: ${repo.id} (${pick.describe()}) is in $place")
                state.value = Done(name, repo.id, pick.format, pick.size, place)
            } catch (e: CancellationException) {
                state.value = Cancelled(name, repo.id)
                throw e
            } catch (e: HfException) {
                logInternal("huggingFace: ${repo.id}: ${e.message}", LogLevel.ERROR)
                state.value = Failed(name, repo.id, e.problem, e.message ?: "The download stopped")
            } catch (e: Exception) {
                logInternal("huggingFace: ${repo.id}: ${e.message}", LogLevel.ERROR)
                state.value = Failed(name, repo.id, HfProblem.SERVER, "The download of ${repo.id} stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                release(name)
            }
        }
        return true
    }

    /** Stops the page's download and throws away what came of it */
    fun cancel() {
        val running = state.value as? Running ?: return
        val stopping = job ?: return
        scope.launch {
            stopping.cancelAndJoin()
            ImportedList.discard(folders(), running.name)
        }
    }

    /** The page has shown how the last download ended */
    fun seen() {
        if (state.value !is Running) state.value = null
    }

    /**
     * For a script: the local name of [input]'s model, downloaded first (its default pick, as the page would choose)
     * when `AMP/models/imported/<name>` (a text model's `AMP/models/text/<name>`, a voice's `AMP/models/speech/<name>`) isn't there yet. [name] is the folder's name, blank for the repo's last part.
     * Blocking; [say] hears the start and the end, the console's internal level each quarter of a big download.
     */
    fun ensure(input: String, name: String = "", say: (String) -> Unit = {}): String {
        val ref = HfRef.parse(input)
        val local = if (name.isBlank()) ref.defaultName else HfRef.localName(name)
        val folders = folders()
        val text = File(folders.text, local)
        val speech = File(folders.speech, local)
        val dir = if (File(text, HfListing.GENAI_CONFIG).isFile) text else if (File(speech, ImportedList.SOURCE).isFile) speech else File(folders.imported, local)
        if (dir.isDirectory) {
            val had = ImportedList.read(dir) ?: return local
            val same = had.repo.equals(ref.repo, ignoreCase = true) || ('/' !in ref.repo && had.repo.substringAfterLast('/').equals(ref.repo, ignoreCase = true))
            if (same) return local
            throw HfException(HfProblem.TAKEN, "AMP/models/${dir.parentFile.name}/$local holds ${had.repo}, not ${ref.repo}: give this one another name, huggingFace(\"${ref.repo}\", \"other-name\")")
        }
        if (!claim(local)) throw HfException(HfProblem.TAKEN, "$local is being downloaded already, from the Models page; wait for it to end")
        try {
            return runBlocking {
                val hub = hub()
                val repo = hub.look(ref)
                val listing = repo.listing
                val pick = listing.defaultPick(ref)?.let(listing::pick)
                    ?: throw if (ref.path != null && listing.candidates.isNotEmpty()) HfException(HfProblem.NOTHING_RUNNABLE, "${repo.id} has nothing that runs here under ${ref.path}: no such ${if (ref.file) "file" else "folder"}, or no ONNX, TorchScript, SavedModel or TFLite file in it; the address's path should name one")
                    else listing.nothingRunnable(repo.id)
                say("huggingFace: downloading ${repo.id} (${pick.describe()}) into ${HfHub.ampPath(pick, local)}")
                var quarter = 0
                hub.download(repo, pick, local, folders, onProgress = { p ->
                    val now = (p.fraction * 4).toInt()
                    if (now > quarter && p.total > BIG) { quarter = now; logInternal("huggingFace: $local ${now * 25}% of ${bytes(p.total)}") }
                })
                say("huggingFace: ${repo.id} is in ${HfHub.ampPath(pick, local)}; initMod(\"$local\") opens it")
                local
            }
        } finally {
            release(local)
        }
    }

    private fun claim(name: String) = synchronized(busy) { busy.add(name) }
    private fun release(name: String) = synchronized(busy) { busy.remove(name) }

    private const val BIG = 20L shl 20
}
