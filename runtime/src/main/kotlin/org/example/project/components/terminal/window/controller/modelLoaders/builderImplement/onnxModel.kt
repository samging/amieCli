package org.example.project.components.terminal.window.controller.modelLoaders.builderImplement

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import org.example.project.components.terminal.window.controller.modelLoaders.imported.LanguageHead
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.integrations.amiePilot.Tensor
import org.example.project.components.terminal.integrations.amiePilot.TensorSpec
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modelLoaders.ManagedSession
import org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions
import org.example.project.components.terminal.window.controller.modelLoaders.imported.ModelText
import org.example.project.components.terminal.window.controller.modelLoaders.modelProps
import java.io.File
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A fixed, untrainable ONNX model with the same interface as initMod: inference(text), getStatus(), close().
 * Transformer models (an input_ids input) tokenize with the tokenizer.json next to the model;
 * other models read the text as comma-separated numbers for a [batch, n] float input.
 * Labels come from a Hugging Face config.json (id2label) next to the model when there is one. [homes] are where those
 * two files are looked for, nearest first: the model's folder, and for an imported repo its folder too, since a repo
 * keeps them at its top and the model in `onnx/`. Any ONNX model also answers tensors: [inputs], [outputs] and [run].
 */
class onnxModel(private val path: Path, homes: List<File> = emptyList()) : modelProps, InteractionModel {
    private val dir: File = path.toFile().absoluteFile.parentFile
    private val homes: List<File> = (listOf(dir) + homes).distinct()
    @Volatile private var managed: ManagedSession? = null
    private var tokenizer: HuggingFaceTokenizer? = null

    // A close waits for the texts in flight: the native tokenizer can't go from under one
    private val closing = ReentrantReadWriteLock()
    private val labels: Map<Int, String> by lazy { ModelText.labels(homes) }
    private val languageHead: LanguageHead? by lazy { ModelText.languageHead(homes) }

    val name: String get() = path.toFile().nameWithoutExtension

    /** The session it answers through, null once it is closed */
    val ortSession: ManagedSession? get() = managed

    @Synchronized
    override fun load() {
        if (managed != null) return
        require(path.toFile().isFile) { "ONNX model not found: $path" }
        val session = OrtSessions.open("onnx:$name", path.toFile().absolutePath)
        if ("input_ids" in session.inputNames) {
            // A model that can't be used doesn't keep its session open
            tokenizer = runCatching {
                requireNotNull(ModelText.tokenizer(homes)) { "$name takes input_ids but there is no tokenizer.json in ${homes.joinToString(" or ")}" }
            }.getOrElse { session.cancel(); throw it }
        }
        managed = session
        logInternal("importModelOnnx: '$name' read, ${session.inputNames} -> ${session.outputs.keys}, labels ${labels.size}")
    }

    private fun session(): ManagedSession {
        managed?.let { if (it.isCancelled) throw java.util.concurrent.CancellationException("'$name' session #${it.id} was cancelled; import the model again") else return it }
        load()
        return managed!!
    }

    override fun inference(text: String): List<String> = closing.read { answered(text) }

    private fun answered(text: String): List<String> {
        val session = session()
        val res = if (tokenizer != null) textInference(session, text) else vectorInference(session, text)
        logInternal("importModelOnnx: '$name' -> ${res.take(10).joinToString()}${if (res.size > 10) " …" else ""}")
        return res
    }

    private fun textInference(session: ManagedSession, text: String): List<String> {
        val encoded = tokenizer!!.encode(text)
        val ids = encoded.ids
        // Filled inside the try, so the tensors made before an input text can't fill are closed too
        val feeds = LinkedHashMap<String, OnnxTensor>()
        try {
            session.inputNames.forEach { input ->
                val values = when (input) {
                    "input_ids" -> ids
                    "attention_mask" -> encoded.attentionMask
                    "token_type_ids" -> encoded.typeIds ?: LongArray(ids.size)
                    else -> throw IllegalArgumentException("$name has an input '$input' that text can't fill")
                }
                feeds[input] = OnnxTensor.createTensor(OrtSessions.env, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))
            }
            session.run(feeds).use { result ->
                val out = result.get(0).value
                return when {
                    // [1, tokens, classes]: one label per token
                    out is Array<*> && out.firstOrNull() is Array<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val perToken = (out as Array<Array<FloatArray>>)[0]
                        val width = perToken.firstOrNull()?.size ?: 0
                        if (labels.isEmpty() && ModelText.isLanguageLogits(session.outputs.keys.firstOrNull(), width, languageHead))
                            ModelText.languageAnswer(languageHead, perToken.toList(), ids, encoded.tokens.toList(), { tokenizer!!.decode(it, true) }) { seq -> lastRow(session, seq) }
                        else if (labels.isEmpty()) listOf("hidden state [1, ${perToken.size}, $width]")
                        else perToken.mapIndexed { i, logits -> "${encoded.tokens[i]}:${labels[ModelText.argmax(logits)] ?: ModelText.argmax(logits)}" }
                    }
                    // [1, classes]: one label for the text
                    out is Array<*> -> ModelText.classScores(out[0] as FloatArray, labels)
                    else -> listOf(out.toString())
                }
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
    }

    /** The logits of the last place for token [ids], a run of their own; null when an input can't be filled from ids */
    private fun lastRow(session: ManagedSession, ids: LongArray): FloatArray? {
        val feeds = LinkedHashMap<String, OnnxTensor>()
        try {
            for (input in session.inputNames) {
                val values = ModelText.idsInput(input, ids) ?: return null
                feeds[input] = OnnxTensor.createTensor(OrtSessions.env, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))
            }
            session.run(feeds).use { result ->
                @Suppress("UNCHECKED_CAST")
                return ((result.get(0).value as? Array<*>)?.firstOrNull() as? Array<FloatArray>)?.lastOrNull()
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
    }

    private fun vectorInference(session: ManagedSession, text: String): List<String> {
        val (input, info) = session.inputs.entries.singleOrNull()
            ?: throw IllegalArgumentException("$name has inputs ${session.inputNames}; vector inference needs exactly one")
        val values = ModelText.numbers(text, name)
        val expected = (info.info as? TensorInfo)?.shape?.lastOrNull() ?: -1
        require(expected <= 0 || expected.toInt() == values.size) { "$name input '$input' takes $expected values, got ${values.size}" }
        OnnxTensor.createTensor(OrtSessions.env, arrayOf(values)).use { tensor ->
            session.run(mapOf(input to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return ModelText.classScores((result.get(0).value as Array<FloatArray>)[0], labels)
            }
        }
    }

    override val inputs: List<TensorSpec> get() = session().inputs.map { (n, info) -> spec(n, info.info) }

    override val outputs: List<TensorSpec> get() = session().outputs.map { (n, info) -> spec(n, info.info) }

    private fun spec(name: String, info: Any): TensorSpec = (info as? TensorInfo)
        ?.let { TensorSpec(name, it.shape.toList(), typeWord(it.type)) } ?: TensorSpec(name, null, info.toString())

    override fun run(values: FloatArray): Map<String, Tensor> = closing.read { runOne(values, values.size) }

    override fun run(values: LongArray): Map<String, Tensor> = closing.read { runOne(values, values.size) }

    private fun runOne(values: Any, count: Int): Map<String, Tensor> {
        val specs = inputs
        val spec = specs.singleOrNull() ?: throw IllegalArgumentException("$name takes ${specs.size} inputs (${specs.joinToString { it.name }}); run(mapOf(\"name\" to values)) gives each its own")
        return runTensors(mapOf(spec.name to (values to Tensor.shapeFor(spec.shape, count, "$name's input ${spec.name}"))))
    }

    override fun run(feeds: Map<String, Any>): Map<String, Tensor> = closing.read {
        val specs = inputs
        val missing = specs.map { it.name } - feeds.keys
        val unknown = feeds.keys - specs.map { it.name }.toSet()
        require(missing.isEmpty() && unknown.isEmpty()) {
            "$name takes ${specs.joinToString { it.toString() }}" + (if (missing.isEmpty()) "" else "; missing ${missing.joinToString()}") + (if (unknown.isEmpty()) "" else "; it has no ${unknown.joinToString()}")
        }
        runTensors(specs.associate { spec ->
            spec.name to when (val value = feeds.getValue(spec.name)) {
                is Tensor -> value.data to value.shape
                is FloatArray -> value to Tensor.shapeFor(spec.shape, value.size, "$name's input ${spec.name}")
                is LongArray -> value to Tensor.shapeFor(spec.shape, value.size, "$name's input ${spec.name}")
                is IntArray -> value to Tensor.shapeFor(spec.shape, value.size, "$name's input ${spec.name}")
                is DoubleArray -> value to Tensor.shapeFor(spec.shape, value.size, "$name's input ${spec.name}")
                else -> throw IllegalArgumentException("${spec.name}: a ${value.javaClass.simpleName} can't be a tensor; give a FloatArray, LongArray, IntArray, DoubleArray or Tensor.of(values, shape)")
            }
        })
    }

    // Each feed is its values and the shape they go in; every output comes back as arrays, nothing native kept
    private fun runTensors(feeds: Map<String, Pair<Any, List<Long>>>): Map<String, Tensor> {
        val session = session()
        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            feeds.forEach { (input, value) ->
                val (data, shape) = value
                val dims = shape.toLongArray()
                tensors[input] = when (data) {
                    is FloatArray -> OnnxTensor.createTensor(OrtSessions.env, FloatBuffer.wrap(data), dims)
                    is LongArray -> OnnxTensor.createTensor(OrtSessions.env, LongBuffer.wrap(data), dims)
                    is IntArray -> OnnxTensor.createTensor(OrtSessions.env, IntBuffer.wrap(data), dims)
                    is DoubleArray -> OnnxTensor.createTensor(OrtSessions.env, DoubleBuffer.wrap(data), dims)
                    else -> throw IllegalArgumentException("$input: a ${data.javaClass.simpleName} can't be a tensor")
                }
            }
            session.run(tensors).use { result ->
                val out = LinkedHashMap<String, Tensor>()
                result.forEach { (output, value) ->
                    val tensor = value as? OnnxTensor
                    out[output] = if (tensor == null) Tensor(output, emptyList(), "other", arrayOf(value.value.toString()))
                    else tensorOf(output, tensor)
                }
                logInternal("importModelOnnx: '$name' ran on ${feeds.keys.joinToString()} -> ${out.keys.joinToString()}")
                return out
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    private fun tensorOf(name: String, tensor: OnnxTensor): Tensor {
        val info = tensor.info
        val shape = info.shape.toList()
        return when (info.type) {
            OnnxJavaType.FLOAT -> Tensor(name, shape, "float32", tensor.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } })
            OnnxJavaType.DOUBLE -> Tensor(name, shape, "float64", tensor.doubleBuffer.let { b -> DoubleArray(b.remaining()).also { b.get(it) } })
            OnnxJavaType.INT64 -> Tensor(name, shape, "int64", tensor.longBuffer.let { b -> LongArray(b.remaining()).also { b.get(it) } })
            OnnxJavaType.INT32 -> Tensor(name, shape, "int32", tensor.intBuffer.let { b -> IntArray(b.remaining()).also { b.get(it) } })
            OnnxJavaType.INT8, OnnxJavaType.UINT8 -> Tensor(name, shape, typeWord(info.type), tensor.byteBuffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } })
            else -> Tensor(name, shape, typeWord(info.type), arrayOf(tensor.value.toString()))
        }
    }

    private fun typeWord(type: OnnxJavaType): String = when (type) {
        OnnxJavaType.FLOAT -> "float32"
        OnnxJavaType.DOUBLE -> "float64"
        OnnxJavaType.FLOAT16 -> "float16"
        OnnxJavaType.BFLOAT16 -> "bfloat16"
        OnnxJavaType.INT64 -> "int64"
        OnnxJavaType.INT32 -> "int32"
        OnnxJavaType.INT16 -> "int16"
        OnnxJavaType.INT8 -> "int8"
        OnnxJavaType.UINT8 -> "uint8"
        OnnxJavaType.BOOL -> "bool"
        OnnxJavaType.STRING -> "string"
        else -> type.name.lowercase()
    }

    override fun getStatus(): Any {
        val session = session()
        return mapOf(
            "name" to name,
            "path" to path.toString(),
            "trainable" to false,
            "session" to "#${session.id}",
            "inputs" to session.inputs.mapValues { it.value.info.toString() },
            "outputs" to session.outputs.mapValues { it.value.info.toString() },
            "labels" to labels.toSortedMap().values.toList(),
            "producer" to runCatching { session.metadata.producerName }.getOrNull(),
            "text" to (tokenizer != null),
        )
    }

    @Synchronized
    override fun close() {
        managed?.cancel()
        closing.write {
            managed = null
            tokenizer?.close()
            tokenizer = null
        }
    }
}

// importModelOnnx's and importModel's way in; an unreadable path surfaces when the session is opened
fun openOnnxModel(path: Path, homes: List<File> = emptyList()): onnxModel = onnxModel(path, homes).also {
    try {
        it.load()
    } catch (e: Exception) {
        logInternal("importModelOnnx: ${e.message}", LogLevel.ERROR)
        throw e
    }
}
