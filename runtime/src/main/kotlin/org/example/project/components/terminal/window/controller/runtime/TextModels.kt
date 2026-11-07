package org.example.project.components.terminal.window.controller.runtime

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modelLoaders.ManagedSession
import org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions
import java.io.File
import java.nio.ByteBuffer
import java.nio.LongBuffer
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.random.Random
import org.example.project.amp.ModelFolders

/**
 * The text generation models of AMP/models/text/<name>: ONNX Runtime GenAI exports (a genai_config.json, a decoder
 * and a tokenizer.json), run on the app's own ONNX Runtime. initMod("name") opens one as any other model, so it is
 * in the Still open list, counted in a run's memory, ended by Terminate and by the ortClosable block around it.
 */
object TextModels {
    /** AMP/models/text, the one owner of the path being ModelFolders */
    val dir: File get() = ModelFolders.amp.text

    fun folder(name: String): File = File(dir, name.replace(Regex("""[^A-Za-z0-9._-]"""), "_"))

    fun has(name: String): Boolean = File(folder(name), "genai_config.json").isFile

    fun names(): List<String> = dir.listFiles().orEmpty().filter { has(it.name) }.map { it.name }.sorted()

    /**
     * How many tokens of a prompt one run of a model reads at most. A run gives a row of logits for every token it reads
     * (151936 floats a token for a Qwen), so a long prompt read in one run took gigabytes for the one row the answer
     * needs. A test sets it to see that the size of the parts changes nothing of the answer.
     */
    internal var promptChunk = 128

    /** Opens the model, which loads its weights: the console says so while it does, as a live line, and how long it took */
    fun open(name: String): TextModel {
        logInternal("text model $name: loading…")
        val began = System.nanoTime()
        return TextModel(name, folder(name)).also { logInternal("text model $name: loaded in ${(System.nanoTime() - began) / 1_000_000} ms") }
    }
}

/**
 * One text model: [inference] answers a text, or a chat completions request given as JSON. A model answers one
 * request at a time. A stopped script or a closed session ends the answer at the next token.
 */
class TextModel internal constructor(val name: String, dir: File) : InteractionModel {
    private val config = Json.parseToJsonElement(File(dir, "genai_config.json").readText()).jsonObject
    private val model = config.getValue("model").jsonObject
    private val decoderConfig = model.getValue("decoder").jsonObject
    private val family = model["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
    private val stops: Set<Long> = model["eos_token_id"].let { id ->
        (id as? JsonArray)?.map { it.jsonPrimitive.int.toLong() } ?: listOfNotNull(id?.jsonPrimitive?.intOrNull?.toLong())
    }.toSet()

    // What the export's graph says of itself: the size of a layer's kept keys and values, and if it wants positions
    private val session: ManagedSession = OrtSessions.open("module:$name", File(dir, decoderConfig.getValue("filename").jsonPrimitive.content).path)
    private val layers = session.inputNames.filter { it.startsWith("past_key_values.") }
    private val wantsPositions = "position_ids" in session.inputNames
    private val tokenizer = HuggingFaceTokenizer.newInstance(
        Paths.get(dir.path, "tokenizer.json"),
        mapOf("addSpecialTokens" to "false", "truncation" to "false", "padding" to "false"),
    )

    private val answered = java.util.concurrent.atomic.AtomicLong()

    override val abilities = setOf("run", "chat", "generate")

    override fun inference(text: String): List<String> {
        val request = text.takeIf { it.trimStart().startsWith("{") }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val messages = request?.get("messages")?.jsonArray?.map { it.jsonObject.getValue("role").jsonPrimitive.content to it.jsonObject.getValue("content").jsonPrimitive.content }
            ?: listOf("user" to text)
        val temperature = request?.get("temperature")?.jsonPrimitive?.doubleOrNull ?: 0.7
        val most = request?.get("max_tokens")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: DEFAULT_TOKENS
        return listOf(generate(prompt(messages), temperature, most))
    }

    /** The chat format of the model's family; a Llama and a Qwen mark the turns of a chat each their own way */
    private fun prompt(messages: List<Pair<String, String>>): String = when {
        family.startsWith("llama") -> "<|begin_of_text|>" +
            messages.joinToString("") { (role, content) -> "<|start_header_id|>$role<|end_header_id|>\n\n$content<|eot_id|>" } +
            "<|start_header_id|>assistant<|end_header_id|>\n\n"
        // Qwen3 thinks aloud first unless its think block comes already empty
        else -> messages.joinToString("") { (role, content) -> "<|im_start|>$role\n$content<|im_end|>\n" } + "<|im_start|>assistant\n" +
            if (family == "qwen3") "<think>\n\n</think>\n\n" else ""
    }

    @Synchronized
    private fun generate(prompt: String, temperature: Double, most: Int): String {
        val env = OrtSessions.env
        val given = tokenizer.encode(prompt).ids
        val made = ArrayList<Long>()
        var input = given
        var seen = 0
        var previous: OrtSession.Result? = null
        try {
            while (made.size < most) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("the answer of $name was stopped")
                // The prompt is read in parts into the kept keys and values, its last token alone: only that run's
                // logits are read, one row
                val step = if (input.size == 1) input else input.copyOfRange(0, minOf(TextModels.promptChunk, input.size - 1))
                val total = seen + step.size
                val tensors = LinkedHashMap<String, OnnxTensor>()
                tensors["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(step), longArrayOf(1, step.size.toLong()))
                tensors["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(total) { 1 }), longArrayOf(1, total.toLong()))
                if (wantsPositions) tensors["position_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(step.size) { seen + it.toLong() }), longArrayOf(1, step.size.toLong()))
                val fresh = layers.filter { previous == null }.associateWith { emptyLayer(it) }
                layers.forEach { layer ->
                    tensors[layer] = fresh[layer] ?: previous!!.get(layer.replace("past_key_values", "present")).get() as OnnxTensor
                }
                val result = try {
                    session.run(tensors)
                } finally {
                    tensors.forEach { (key, tensor) -> if (key !in layers || key in fresh) tensor.close() }
                }
                previous?.close()
                previous = result
                seen = total
                input = input.copyOfRange(step.size, input.size)
                if (input.isNotEmpty()) continue

                val logits = (result.get("logits").get() as OnnxTensor).floatBuffer
                val size = logits.remaining() / step.size
                val last = FloatArray(size).also { logits.position(logits.remaining() - size + logits.position()); logits.get(it) }
                val next = choose(last, temperature, made)
                if (next in stops) break
                made += next
                input = longArrayOf(next)
            }
        } finally {
            previous?.close()
        }
        answered.addAndGet(made.size.toLong())
        return tokenizer.decode(made.toLongArray(), true).trim()
    }

    /** A layer's keys or values before anything is kept: no rows, and the rest of its shape as the graph has it */
    private fun emptyLayer(name: String): OnnxTensor {
        val shape = (session.inputs.getValue(name).info as ai.onnxruntime.TensorInfo).shape
        val first = shape.indexOfFirst { it < 0 }
        return OnnxTensor.createTensor(
            OrtSessions.env,
            ByteBuffer.allocateDirect(0).asFloatBuffer(),
            LongArray(shape.size) { i -> if (shape[i] >= 0) shape[i] else if (i == first) 1 else 0 },
        )
    }

    /** The next token: none but the best at temperature 0, else drawn from the best few, what was just said counting less */
    private fun choose(logits: FloatArray, temperature: Double, made: List<Long>): Long {
        made.takeLast(REPEAT_WINDOW).toSet().forEach { id ->
            val i = id.toInt()
            logits[i] = if (logits[i] > 0) logits[i] / REPEAT_PENALTY else logits[i] * REPEAT_PENALTY
        }
        val best = logits.indices.sortedByDescending { logits[it] }.take(TOP_K)
        if (temperature <= 0.0) return best.first().toLong()
        val weights = best.map { exp((logits[it] - logits[best.first()]) / temperature) }
        var draw = Random.nextDouble(weights.sum())
        best.forEachIndexed { index, id ->
            draw -= weights[index]
            if (draw <= 0) return id.toLong()
        }
        return best.first().toLong()
    }

    override fun getStatus(): Any = mapOf(
        "model" to name,
        "family" to family,
        "tokens answered" to answered.get(),
        "runs" to session.runs,
    )

    override fun close() {
        session.cancel()
        tokenizer.close()
        logInternal("text model $name: closed")
    }

    private companion object {
        const val DEFAULT_TOKENS = 256
        const val TOP_K = 40
        const val REPEAT_WINDOW = 64
        const val REPEAT_PENALTY = 1.05f
    }
}
