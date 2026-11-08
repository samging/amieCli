package org.example.project.components.terminal.window.controller.modelLoaders.imported

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.integrations.amiePilot.Tokens
import org.example.project.components.terminal.window.controller.modelLoaders.builderImplement.onnxModel
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import org.example.project.components.terminal.integrations.amiePilot.Encoded

/**
 * An encoder and decoder pair of `AMP/models/imported/<name>`, as optimum exports a sequence-to-sequence model (T5,
 * BART, Whisper's text side): two ONNX sessions a script wires itself. `encoder.run(mapOf("input_ids" to t.ids,
 * "attention_mask" to t.mask))` gives the hidden states; `decoder.run(...)` takes them with the ids so far and
 * answers the next token's logits, one step at a time, until the end token; `tokens(text)` and `text(ids)` are the
 * tokenizer beside the models. No single answer: `inference(text)` says how to wire them instead.
 */
class EncoderDecoderModel internal constructor(
    val name: String,
    override val encoder: onnxModel,
    override val decoder: onnxModel,
    override val decoderWithPast: onnxModel?,
    private val homes: List<File>,
) : InteractionModel {
    private val tokenizer: HuggingFaceTokenizer? by lazy { ModelText.tokenizer(homes) }

    /** generation_config.json over config.json, the nearest of each */
    private val configs: List<JsonObject> by lazy {
        listOf("generation_config.json", "config.json").mapNotNull { name ->
            homes.asSequence().map { File(it, name) }.firstOrNull { it.isFile }?.let { runCatching { Json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }
        }
    }

    private fun configId(key: String): Long? = configs.firstNotNullOfOrNull { config ->
        when (val v = config[key]) { is JsonArray -> v.firstOrNull()?.jsonPrimitive?.longOrNull; null -> null; else -> v.jsonPrimitive.longOrNull }
    }

    override val startToken: Long? get() = configId("decoder_start_token_id") ?: configId("bos_token_id")
    override val endToken: Long? get() = configId("eos_token_id")

    private fun configIds(key: String): Map<String, Long> = configs.firstNotNullOfOrNull { it[key] as? JsonObject }
        ?.mapNotNull { (k, v) -> v.jsonPrimitive.longOrNull?.let { k to it } }?.toMap().orEmpty()

    override fun startTokens(encoded: Encoded?, vararg tokens: String): LongArray {
        val start = startToken ?: return LongArray(0)
        val languages = configIds("lang_to_id") // "<|en|>" to 50259
        val tasks = configIds("task_to_id") // "transcribe" to 50359
        val noTimestamps = configId("no_timestamps_token_id")
        fun bare(name: String) = name.trim().trim('<', '|', '>').lowercase()
        // What was asked: a language, a task, timestamps or not
        var language: Long? = null
        var task: Long? = null
        var timestamps: Boolean? = null
        tokens.forEach { word ->
            val key = bare(word)
            when {
                languages.containsKey("<|$key|>") -> language = languages.getValue("<|$key|>")
                tasks.containsKey(key) -> task = tasks.getValue(key)
                key == "timestamps" -> timestamps = true
                key == "notimestamps" || key == "no_timestamps" -> timestamps = false
                else -> throw IllegalArgumentException(
                    "$name knows no token '$word'; the names are " +
                        listOfNotNull(
                            languages.keys.takeIf { it.isNotEmpty() }?.joinToString(", ", "a language (", ")") { bare(it) },
                            tasks.keys.takeIf { it.isNotEmpty() }?.joinToString(", ", "a task (", ")"),
                            noTimestamps?.let { "timestamps or notimestamps" },
                        ).joinToString("; ").ifEmpty { "none: its config forces no tokens" },
                )
            }
        }
        val forced = configs.firstNotNullOfOrNull { it["forced_decoder_ids"] as? JsonArray }?.mapNotNull { pair ->
            (pair as? JsonArray)?.takeIf { it.size == 2 }?.let { it[0].jsonPrimitive.intOrNull?.let { at -> at to it[1].jsonPrimitive.longOrNull } }
        }.orEmpty().sortedBy { it.first }
        val ids = mutableListOf(start)
        forced.forEach { (at, id) ->
            val value = when {
                id == null || id in languages.values -> language ?: id ?: when {
                    encoded != null && languages.isNotEmpty() -> detectLanguage(encoded, languages)
                    else -> languages["<|en|>"] ?: return@forEach
                }
                id in tasks.values -> task ?: id
                else -> id
            }
            while (ids.size < at) ids.add(start)
            if (at < ids.size) ids[at] = value else ids.add(value)
        }
        // A language or task asked where the config forces none, in Whisper's order: language, task
        if (language != null && language !in ids) ids.add(1.coerceAtMost(ids.size), language!!)
        if (task != null && task !in ids) ids.add(task!!)
        if (noTimestamps != null && timestamps != true && noTimestamps !in ids) ids.add(noTimestamps)
        if (timestamps == true) ids.remove(noTimestamps)
        return ids.toLongArray()
    }

    /** One decoder step after the start token: the likeliest of the language tokens */
    private fun detectLanguage(encoded: Encoded, languages: Map<String, Long>): Long {
        val logits = decoder.run(decoderFeeds(encoded, longArrayOf(startToken!!))).getValue("logits")
        val vocab = logits.shape.last().toInt()
        val last = logits.floats()
        val offset = last.size - vocab
        return languages.values.maxBy { last[offset + it.toInt()] }
    }

    private val prep: Preprocessor? by lazy { Preprocessor.of(homes) }
    override val preprocessor: JsonObject? get() = prep?.config

    override fun encode(text: String): Encoded {
        val p = prep
        if (p != null) {
            val file = File(text)
            if (file.isFile) return encode(file)
            throw IllegalArgumentException("$name takes ${if (p.kind == Preprocessor.Kind.AUDIO) "audio" else "an image"}, not text: encode(File(\"…\")) or the path of a file")
        }
        val t = tokens(text)
        val feeds = mutableMapOf<String, Any>("input_ids" to t.ids)
        if (encoder.inputs.any { it.name == "attention_mask" }) feeds["attention_mask"] = t.mask
        return Encoded(encoder.run(feeds), t.mask)
    }

    override fun encode(file: File): Encoded {
        val p = prep ?: throw IllegalArgumentException("$name takes text, not files: encode(\"…\"); no preprocessor_config.json beside it says how a file becomes its input")
        val tensor = p.tensor(file)
        val input = encoder.inputs.firstOrNull { it.name == p.inputName }?.name ?: encoder.inputs.firstOrNull()?.name ?: p.inputName
        return Encoded(encoder.run(mapOf(input to tensor)), null)
    }

    override fun decoderFeeds(encoded: Encoded, ids: LongArray): Map<String, Any> {
        val feeds = mutableMapOf<String, Any>("input_ids" to ids, "encoder_hidden_states" to encoded.hidden)
        if (decoder.inputs.any { it.name == "encoder_attention_mask" }) {
            feeds["encoder_attention_mask"] = encoded.mask ?: LongArray(encoded.hidden.shape[1].toInt()) { 1 }
        }
        return feeds
    }

    override val abilities: Set<String>
        get() = setOf("encoder", "decoder") + (if (decoderWithPast != null) setOf("decoderWithPast") else emptySet()) +
            (if (tokenizer != null) setOf("tokens") else emptySet()) + (if (startToken != null) setOf("startToken") else emptySet()) +
            (when (prep?.kind) { Preprocessor.Kind.AUDIO -> setOf("audio"); Preprocessor.Kind.IMAGE -> setOf("image"); null -> setOf("text") })

    override fun inference(text: String): List<String> = throw UnsupportedOperationException(
        "$name is an encoder and decoder pair, which no single run answers. Wire them: val e = m.encode(text or a file); " +
            "val ids = m.startTokens.toMutableList(); then m.decoder.run(m.decoderFeeds(e, ids.toLongArray())) one token at a time, taking the " +
            "likeliest of its logits' last row until m.endToken; m.text(ids) gives the words",
    )

    override fun getStatus(): Any = mapOf("encoder" to encoder.getStatus(), "decoder" to decoder.getStatus(), "decoderWithPast" to decoderWithPast?.getStatus())

    override fun tokens(text: String): Tokens {
        val encoded = (tokenizer ?: throw UnsupportedOperationException("$name has no tokenizer.json beside it")).encode(text)
        return Tokens(encoded.ids, encoded.attentionMask)
    }

    override fun text(ids: LongArray): String = (tokenizer ?: throw UnsupportedOperationException("$name has no tokenizer.json beside it")).decode(ids, true)

    override fun close() {
        decoderWithPast?.close()
        decoder.close()
        encoder.close()
    }

    companion object {
        /** The pair among [scan]'s ONNX files, the plain `encoder_model.onnx` and `decoder_model.onnx` of one folder before a variant; null without both */
        fun of(scan: FolderScan): Triple<File, File, File?>? {
            val onnx = scan.of(ModelFormat.ONNX).map { it.file }.sortedBy { it.name.length }
            val encoder = onnx.firstOrNull { it.name.lowercase().startsWith("encoder_model") } ?: return null
            val siblings = onnx.filter { it.parentFile == encoder.parentFile }
            val decoder = siblings.firstOrNull { it.name.lowercase().startsWith("decoder_model") && !it.name.lowercase().contains("merged") }
                ?: siblings.firstOrNull { it.name.lowercase().startsWith("decoder_model") } ?: return null
            val withPast = siblings.firstOrNull { it.name.lowercase().startsWith("decoder_with_past") }
            return Triple(encoder, decoder, withPast)
        }
    }
}
