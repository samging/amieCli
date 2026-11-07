package org.example.project.components.terminal.window.controller.modelLoaders.imported

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.inference.Predictor
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.ndarray.types.DataType
import ai.djl.ndarray.types.Shape
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.translate.NoBatchifyTranslator
import ai.djl.translate.TranslateException
import ai.djl.translate.TranslatorContext
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.integrations.amiePilot.Tensor
import org.example.project.components.terminal.integrations.amiePilot.TensorSpec
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modelLoaders.DjlTelemetry
import java.io.File
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One input of a run: [name] null where the engine feeds by position (TorchScript), the values in [shape] */
internal class Feed(val name: String?, val data: Any, val shape: List<Long>)

internal class Feeds(val list: List<Feed>)

internal class Outputs(val list: List<Tensor>)

/**
 * Turns the script's arrays into the engine's tensors and its answers back into arrays, inside the run's own native
 * memory, which DJL frees when the run ends: nothing native outlives a run
 */
internal object FeedTranslator : NoBatchifyTranslator<Feeds, Outputs> {
    override fun processInput(ctx: TranslatorContext, input: Feeds): NDList {
        val manager = ctx.ndManager
        return NDList(input.list.map { feed ->
            val shape = Shape(*feed.shape.toLongArray())
            when (val data = feed.data) {
                is FloatArray -> manager.create(data, shape)
                is LongArray -> manager.create(data, shape)
                is IntArray -> manager.create(data, shape)
                is DoubleArray -> manager.create(data, shape)
                else -> throw IllegalArgumentException("a ${data.javaClass.simpleName} can't be a tensor; give a FloatArray, LongArray, IntArray or DoubleArray")
            }.also { array -> feed.name?.let { array.name = it } }
        })
    }

    override fun processOutput(ctx: TranslatorContext, list: NDList): Outputs = Outputs(list.mapIndexed { i, array -> tensorOf(array, i) })

    private fun tensorOf(array: NDArray, index: Int): Tensor {
        val name = array.name?.takeIf { it.isNotBlank() } ?: "output$index"
        val shape = array.shape.shape.toList()
        return when (array.dataType) {
            DataType.FLOAT32 -> Tensor(name, shape, "float32", array.toFloatArray())
            DataType.FLOAT64 -> Tensor(name, shape, "float64", array.toDoubleArray())
            DataType.INT64 -> Tensor(name, shape, "int64", array.toLongArray())
            DataType.INT32 -> Tensor(name, shape, "int32", array.toIntArray())
            DataType.INT8, DataType.UINT8 -> Tensor(name, shape, array.dataType.name.lowercase(), array.toByteArray())
            DataType.BOOLEAN -> Tensor(name, shape, "bool", array.toBooleanArray())
            DataType.STRING -> Tensor(name, shape, "string", array.toStringArray())
            else -> Tensor(name, shape, "float32", array.toType(DataType.FLOAT32, false).toFloatArray())
        }
    }
}

/**
 * A TorchScript or TensorFlow model run by its DJL engine, with initMod's interface: [inference] takes text when a
 * `tokenizer.json` is next to it (or in a folder of [homes]) and answers `label:score` lines with the labels of its
 * `config.json`, else numbers separated by commas; [run] takes tensors. It holds the engine's model, one predictor and
 * the tokenizer, all native, which [close] gives back once the runs in flight are done. Listed on the Sessions tab
 * through [DjlSessions], where Terminate closes it.
 */
class DjlModel private constructor(
    val id: Int,
    val name: String,
    val format: ModelFormat,
    /** The file, or for a SavedModel its folder */
    val path: File,
    private val homes: List<File>,
    private val model: ZooModel<Feeds, Outputs>,
    private val predictor: Predictor<Feeds, Outputs>,
    private val tokenizer: HuggingFaceTokenizer?,
) : InteractionModel {
    val engine: String get() = format.engine
    val openedAt: Instant = Instant.now()

    private val labels: Map<Int, String> by lazy { ModelText.labels(homes) }
    private val languageHead: LanguageHead? by lazy { ModelText.languageHead(homes) }
    private val runningCount = AtomicInteger()
    private val runCount = AtomicInteger()
    private val runNanos = AtomicLong()
    private val closing = AtomicBoolean()
    private val released = AtomicBoolean()

    @Volatile private var inputSpecs: List<TensorSpec> = describe(true)
    @Volatile private var outputSpecs: List<TensorSpec> = describe(false)

    val running: Int get() = runningCount.get()
    val runs: Int get() = runCount.get()
    val runTimeMs: Long get() = runNanos.get() / 1_000_000
    val closed: Boolean get() = closing.get()

    /** When it last answered; null before its first run */
    @Volatile var lastRunAt: Instant? = null
        private set

    /** What its files weigh: the file, or every file of the SavedModel folder */
    val bytes: Long by lazy { if (path.isFile) path.length() else path.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    override val abilities: Set<String> get() = if (tokenizer != null && labels.isNotEmpty()) setOf("run", "classify") else setOf("run")

    override val inputs: List<TensorSpec> get() = inputSpecs
    override val outputs: List<TensorSpec> get() = outputSpecs

    // TensorFlow says its signature as soon as it is loaded; TorchScript says nothing until a run, so nothing is asked
    private fun describe(input: Boolean): List<TensorSpec> {
        if (format != ModelFormat.TENSORFLOW) return emptyList()
        val described = runCatching { if (input) model.describeInput() else model.describeOutput() }.getOrNull() ?: return emptyList()
        return described.keys().mapIndexed { i, key -> TensorSpec(key, described.valueAt(i)?.shape?.toList(), null) }
    }

    override fun inference(text: String): List<String> {
        val answer = if (tokenizer != null) {
            val encoded = tokenizer.encode(text)
            val known = inputSpecs
            val feeds = if (known.isEmpty()) {
                // TorchScript feeds by position: token ids and the mask, as a traced BERT takes them
                listOf(Feed(null, encoded.ids, listOf(1L, encoded.ids.size.toLong())), Feed(null, encoded.attentionMask, listOf(1L, encoded.ids.size.toLong())))
            } else known.map { spec ->
                val values = ModelText.textInput(spec.name, encoded) ?: throw IllegalArgumentException("$name has an input '${spec.name}' that text can't fill; run(mapOf(…)) gives it tensors")
                Feed(spec.name, values, listOf(1L, values.size.toLong()))
            }
            textAnswer(predict(feeds), encoded.ids, encoded.tokens.toList())
        } else {
            ModelText.numbers(text, name).let { numbers -> scoresOf(run(numbers).values.first()) }
        }
        logInternal("importModel: '$name' -> ${answer.take(10).joinToString()}${if (answer.size > 10) " …" else ""}")
        return answer
    }

    private fun textAnswer(out: List<Tensor>, ids: LongArray, tokens: List<String>): List<String> {
        val first = out.firstOrNull { it.type.startsWith("float") } ?: return out.map { it.toString() }
        return when (first.shape.size) {
            3 -> when {
                labels.isNotEmpty() -> ModelText.tokenLabels(tokens, first.floats(), first.shape[2].toInt(), labels)
                ModelText.isLanguageLogits(first.name, first.shape[2].toInt(), languageHead) ->
                    ModelText.languageAnswer(languageHead, rowsOf(first), ids, tokens, { tokenizer!!.decode(it, true) }) { seq -> lastRow(seq) }
                else -> listOf("hidden state ${first.shape}")
            }
            else -> scoresOf(first)
        }
    }

    /** A [1, places, width] tensor's rows, one a place */
    private fun rowsOf(tensor: Tensor): List<FloatArray> {
        val width = tensor.shape[2].toInt()
        val values = tensor.floats()
        return (0 until values.size / width).map { values.copyOfRange(it * width, (it + 1) * width) }
    }

    /** The logits of the last place for token [ids], a run of their own; null when an input can't be filled from ids */
    private fun lastRow(ids: LongArray): FloatArray? {
        val shape = listOf(1L, ids.size.toLong())
        val known = inputSpecs
        val feeds = if (known.isEmpty()) listOf(Feed(null, ids, shape), Feed(null, LongArray(ids.size) { 1 }, shape))
        else known.map { spec -> Feed(spec.name, ModelText.idsInput(spec.name, ids) ?: return null, shape) }
        val first = predict(feeds).firstOrNull { it.type.startsWith("float") && it.shape.size == 3 } ?: return null
        return rowsOf(first).lastOrNull()
    }

    private fun scoresOf(tensor: Tensor): List<String> =
        if (tensor.shape.size <= 2 && tensor.type.startsWith("float")) ModelText.classScores(tensor.floats(), labels) else listOf(tensor.toString())

    override fun run(values: FloatArray): Map<String, Tensor> = runOne(values, values.size)

    override fun run(values: LongArray): Map<String, Tensor> = runOne(values, values.size)

    private fun runOne(values: Any, count: Int): Map<String, Tensor> {
        val known = inputSpecs
        require(known.size <= 1) { "$name takes ${known.size} inputs (${known.joinToString { it.name }}); run(mapOf(\"name\" to values)) gives each its own" }
        val spec = known.firstOrNull()
        return named(predict(listOf(Feed(spec?.name, values, Tensor.shapeFor(spec?.shape, count, "$name's input")))))
    }

    override fun run(feeds: Map<String, Any>): Map<String, Tensor> {
        val known = inputSpecs
        if (known.isNotEmpty()) {
            val missing = known.map { it.name } - feeds.keys
            val unknown = feeds.keys - known.map { it.name }.toSet()
            require(missing.isEmpty() && unknown.isEmpty()) {
                "$name takes ${known.joinToString { it.toString() }}" + (if (missing.isEmpty()) "" else "; missing ${missing.joinToString()}") + (if (unknown.isEmpty()) "" else "; it has no ${unknown.joinToString()}")
            }
        }
        val ordered = if (known.isEmpty()) feeds.keys.toList() else known.map { it.name }
        return named(predict(ordered.map { key ->
            val spec = known.firstOrNull { it.name == key }
            when (val value = feeds.getValue(key)) {
                is Tensor -> Feed(key, value.data, value.shape)
                else -> Feed(key, value, Tensor.shapeFor(spec?.shape, sizeOf(value, key), "$name's input $key"))
            }
        }.map { if (known.isEmpty()) Feed(null, it.data, it.shape) else it }))
    }

    private fun sizeOf(value: Any, key: String): Int = when (value) {
        is FloatArray -> value.size
        is LongArray -> value.size
        is IntArray -> value.size
        is DoubleArray -> value.size
        else -> throw IllegalArgumentException("$key: a ${value.javaClass.simpleName} can't be a tensor; give a FloatArray, LongArray, IntArray, DoubleArray or Tensor.of(values, shape)")
    }

    private fun named(out: List<Tensor>): Map<String, Tensor> = LinkedHashMap<String, Tensor>().apply { out.forEach { put(it.name, it) } }

    private fun predict(feeds: List<Feed>): List<Tensor> {
        if (closed) throw CancellationException("'$name' (#$id) was closed; open the model again")
        runningCount.incrementAndGet()
        val begun = System.nanoTime()
        try {
            // After counting itself: a close that came first sees the run and waits for it, or this sees the close
            if (closed) throw CancellationException("'$name' (#$id) was closed; open the model again")
            val out = synchronized(predictor) { predictor.predict(Feeds(feeds)) }.list
            if (format == ModelFormat.TORCHSCRIPT) {
                // What TorchScript takes and gives is known once it has run
                if (inputSpecs.isEmpty()) inputSpecs = feeds.mapIndexed { i, f -> TensorSpec(f.name ?: "input$i", f.shape, typeOf(f.data)) }
                outputSpecs = out.map { TensorSpec(it.name, it.shape, it.type) }
            }
            return out
        } catch (e: TranslateException) {
            throw IllegalStateException("$engine could not run '$name': ${generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.distinct().joinToString(": ")}", e)
        } finally {
            runNanos.addAndGet(System.nanoTime() - begun)
            runCount.incrementAndGet()
            lastRunAt = Instant.now()
            runningCount.decrementAndGet()
            if (closed && runningCount.get() == 0) release()
        }
    }

    private fun typeOf(data: Any): String? = when (data) {
        is FloatArray -> "float32"
        is LongArray -> "int64"
        is IntArray -> "int32"
        is DoubleArray -> "float64"
        else -> null
    }

    override fun getStatus(): Any = mapOf(
        "name" to name,
        "format" to format.label,
        "engine" to engine,
        "path" to path.path,
        "trainable" to false,
        "session" to "#$id",
        "inputs" to inputSpecs.map { it.toString() },
        "outputs" to outputSpecs.map { it.toString() },
        "labels" to labels.toSortedMap().values.toList(),
        "text" to (tokenizer != null),
        "runs" to runs,
    )

    /** Gives back the engine's model, the predictor and the tokenizer once the runs in flight are done; a run past the wait does it when it ends */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var interrupted = false
        while (runningCount.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (runningCount.get() == 0) release()
        else logInternal("importModel: '$name' (#$id) still runs after the wait, it closes when that run ends")
        DjlSessions.remove(this)
        logInternal("importModel: '$name' (#$id) closed")
    }

    private fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { predictor.close() }
        runCatching { model.close() }.onFailure { logInternal("importModel: '$name' could not be closed: ${it.message}", LogLevel.ERROR) }
        runCatching { tokenizer?.close() }
    }

    override fun toString(): String = "#$id $name ($engine) · ${if (running > 0) "running $running" else "idle"} · runs $runs"

    companion object {
        /**
         * Loads [path] with the engine of [format]: the natives come first, downloaded on the first use on this
         * machine. What fails on the way is given back, so nothing of it stays open
         */
        internal fun open(name: String, format: ModelFormat, path: File, homes: List<File>): DjlModel {
            val engine = requireNotNull(ModelEngines.djlEngine(format)) { "${format.label} is not run by a DJL engine" }
            ModelEngines.missing(format)?.let { throw IllegalStateException("'$name' is ${format.label}, refused: $it") }
            DjlTelemetry.optOut()
            ModelEngines.prepare(format)
            logInternal("importModel: opening '$name' (${format.label}) with $engine…")
            val began = System.nanoTime()
            val criteria = Criteria.builder()
                .setTypes(Feeds::class.java, Outputs::class.java)
                .optModelPath(path.toPath())
                .optEngine(engine)
                .optTranslator(FeedTranslator)
                .apply {
                    if (format == ModelFormat.TENSORFLOW) {
                        optOption("Tags", "serve")
                        optOption("SignatureDefKey", "serving_default")
                    }
                }
                .build()
            val model = try {
                criteria.loadModel()
            } catch (e: Throwable) {
                if (e is Error && e !is UnsatisfiedLinkError && e !is NoClassDefFoundError) throw e
                throw IllegalStateException("'$name': ${ModelEngines.explain(format, e)}", e)
            }
            var predictor: Predictor<Feeds, Outputs>? = null
            try {
                predictor = model.newPredictor()
                val tokenizer = ModelText.tokenizer(homes)
                return DjlModel(DjlSessions.nextId(), name, format, path, homes, model, predictor, tokenizer).also {
                    DjlSessions.add(it)
                    logInternal("importModel: '$name' opened with $engine in ${(System.nanoTime() - began) / 1_000_000} ms as #${it.id}, " +
                        "${it.inputs.ifEmpty { null }?.joinToString() ?: "inputs known after the first run"}, labels ${it.labels.size}")
                }
            } catch (e: Throwable) {
                runCatching { predictor?.close() }
                runCatching { model.close() }
                throw e
            }
        }
    }
}

/** The open DJL models, for the Sessions tab and Terminate */
object DjlSessions {
    private val ids = AtomicInteger()
    private val open = ConcurrentHashMap<Int, DjlModel>()

    val all: List<DjlModel> get() = open.values.sortedBy { it.id }

    internal fun nextId(): Int = ids.incrementAndGet()

    internal fun add(model: DjlModel) { open[model.id] = model }

    internal fun remove(model: DjlModel) { open.remove(model.id) }

    fun close(id: Int): Boolean = open[id]?.close() != null

    fun closeAll(): Int = all.onEach { it.close() }.size
}
