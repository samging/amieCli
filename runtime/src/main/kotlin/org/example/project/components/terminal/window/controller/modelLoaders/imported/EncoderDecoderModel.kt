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

    override val abilities: Set<String>
        get() = setOf("encoder", "decoder") + (if (decoderWithPast != null) setOf("decoderWithPast") else emptySet()) +
            (if (tokenizer != null) setOf("tokens") else emptySet()) + (if (startToken != null) setOf("startToken") else emptySet())

    override fun inference(text: String): List<String> = throw UnsupportedOperationException(
        "$name is an encoder and decoder pair, which no single run answers. Wire them: val t = m.tokens(text); " +
            "val h = m.encoder.run(mapOf(\"input_ids\" to t.ids, \"attention_mask\" to t.mask)); then m.decoder.run(...) one token at a time, " +
            "with the encoder's hidden states and the ids so far (m.decoder.inputs names its tensors), from m.startToken until m.endToken; m.text(ids) gives the words",
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
