package org.example.project.components.terminal.window.controller.modelLoaders.imported

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.components.terminal.window.controller.modelLoaders.DjlTelemetry
import java.io.File
import kotlin.math.exp

/**
 * A language model's head as its `config.json` tells it: the size of its vocabulary, whether it continues a text
 * (GPT-2, Llama: …ForCausalLM, …LMHeadModel) or fills a mask (BERT: …ForMaskedLM), and the tokens that end a text.
 * Its logits are [1, tokens, vocab]: a score for every token of the vocabulary at every place, not a hidden state.
 */
internal data class LanguageHead(val vocab: Int, val causal: Boolean, val eos: Set<Long>)

/**
 * What a model from a file needs to take text and answer labels, whatever runs it: the `tokenizer.json` and the
 * `config.json` labels of a Hugging Face export, found next to the model or in a folder above it ([homes], nearest
 * first), and the reading of its scores as `label:probability` lines.
 */
internal object ModelText {
    /** The first of [homes] that has [name] */
    fun find(homes: List<File>, name: String): File? = homes.map { File(it, name) }.firstOrNull { it.isFile }

    /** The tokenizer of the nearest `tokenizer.json`, null when there is none */
    fun tokenizer(homes: List<File>): HuggingFaceTokenizer? = find(homes, "tokenizer.json")?.let { file ->
        DjlTelemetry.optOut()
        val text = file.readText()
        // A "Precompiled" normalizer without its charsmap (some converted SentencePiece tokenizers, Marian's among them)
        // aborts the native tokenizer, and the process with it; it normalizes nothing, so it goes before the load
        val cleaned = runCatching { withoutEmptyPrecompiled(Json.parseToJsonElement(text).jsonObject) }.getOrNull()
        if (cleaned == null) HuggingFaceTokenizer.newInstance(file.toPath())
        else HuggingFaceTokenizer.newInstance(cleaned.toString().byteInputStream(), emptyMap())
    }

    /** [tokenizer] without a `Precompiled` normalizer whose `precompiled_charsmap` is null; null when there is none to drop */
    private fun withoutEmptyPrecompiled(tokenizer: JsonObject): JsonObject? {
        fun empty(n: JsonElement?) = (n as? JsonObject)?.let { it["type"]?.jsonPrimitive?.contentOrNull == "Precompiled" && it["precompiled_charsmap"].let { c -> c == null || c is JsonNull } } == true
        val normalizer = tokenizer["normalizer"] as? JsonObject ?: return null
        val replacement: JsonElement = when {
            empty(normalizer) -> JsonNull
            normalizer["type"]?.jsonPrimitive?.contentOrNull == "Sequence" -> {
                val steps = normalizer["normalizers"] as? JsonArray ?: return null
                if (steps.none(::empty)) return null
                JsonObject(normalizer + ("normalizers" to JsonArray(steps.filterNot(::empty))))
            }
            else -> return null
        }
        return JsonObject(tokenizer + ("normalizer" to replacement))
    }

    /** The labels of the nearest `config.json` (id2label), empty when there are none */
    fun labels(homes: List<File>): Map<Int, String> = runCatching {
        val config = Json.parseToJsonElement(find(homes, "config.json")!!.readText()).jsonObject
        config["id2label"]?.jsonObject?.entries?.associate { (k, v) -> k.toInt() to v.jsonPrimitive.content } ?: emptyMap()
    }.getOrElse { emptyMap() }

    /** The values a text gives the input of [name]; null for an input text can't fill */
    fun textInput(name: String, encoded: Encoding): LongArray? = when (name.substringBefore(':').lowercase()) {
        "input_ids", "input_ids_1", "ids" -> encoded.ids
        "attention_mask", "mask" -> encoded.attentionMask
        "token_type_ids", "segment_ids" -> encoded.typeIds ?: LongArray(encoded.ids.size)
        else -> null
    }

    /** Whether a model with these inputs takes text: one of them is token ids */
    fun takesText(inputNames: Collection<String>): Boolean = inputNames.any { it.substringBefore(':').lowercase() in setOf("input_ids", "ids") }

    /** "label:probability", most likely first; logits are turned into probabilities, scores that already sum to 1 kept */
    fun classScores(scores: FloatArray, labels: Map<Int, String>): List<String> {
        val probabilities = if (scores.all { it in 0f..1f } && scores.sum() in 0.99f..1.01f) scores else softmax(scores)
        return probabilities.mapIndexed { i, p -> (labels[i] ?: i.toString()) to p }
            .sortedByDescending { it.second }
            .map { (label, p) -> "$label:$p" }
    }

    /** One label per token, "token:label", for scores of [tokens] tokens by [classes] classes laid out flat */
    fun tokenLabels(tokens: List<String>, scores: FloatArray, classes: Int, labels: Map<Int, String>): List<String> =
        tokens.indices.filter { (it + 1) * classes <= scores.size }.map { i ->
            val best = argmax(scores.copyOfRange(i * classes, (i + 1) * classes))
            "${tokens[i]}:${labels[best] ?: best}"
        }

    /** The text as numbers separated by commas, spaces or lines, for a model that takes numbers */
    fun numbers(text: String, model: String): FloatArray = text.split(',', ' ', '\n').filter { it.isNotBlank() }
        .map { it.trim().toFloatOrNull() ?: throw IllegalArgumentException("$model takes numbers separated by commas or spaces; '${it.trim()}' is not one") }
        .toFloatArray()

    /** How many tokens a language model adds to a text at most, one run each, and how many next tokens it lists */
    const val NEW_TOKENS = 24
    const val TOP_TOKENS = 5

    /** The language head of the nearest `config.json`; null when it names none (no vocab_size, or an architecture that is no LM) */
    fun languageHead(homes: List<File>): LanguageHead? = runCatching {
        languageHead(Json.parseToJsonElement(find(homes, "config.json")!!.readText()).jsonObject)
    }.getOrNull()

    fun languageHead(config: JsonObject): LanguageHead? {
        val vocab = config["vocab_size"]?.jsonPrimitive?.intOrNull ?: return null
        val architectures = (config["architectures"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val masked = architectures.any { it.endsWith("ForMaskedLM") }
        val causal = architectures.any { it.endsWith("ForCausalLM") || it.endsWith("LMHeadModel") }
        if (!masked && !causal && architectures.isNotEmpty()) return null
        val eos = when (val e = config["eos_token_id"]) {
            is JsonArray -> e.mapNotNull { it.jsonPrimitive.longOrNull }.toSet()
            null -> emptySet()
            else -> setOfNotNull(e.jsonPrimitive.longOrNull)
        }
        return LanguageHead(vocab, causal = !masked, eos = eos)
    }

    /**
     * Whether an output of [lastDim] values a place, named [outputName], is a language model's logits: as wide as
     * [head]'s vocabulary, or named logits and too wide for a set of labels
     */
    fun isLanguageLogits(outputName: String?, lastDim: Int, head: LanguageHead?): Boolean =
        (head != null && lastDim == head.vocab) || (outputName == "logits" && lastDim >= 1000)

    /** The values token [ids] give the input of [name], the mask all ones and the positions counted; null for an input ids can't fill */
    fun idsInput(name: String, ids: LongArray): LongArray? = when (name.substringBefore(':').lowercase()) {
        "input_ids", "input_ids_1", "ids" -> ids
        "attention_mask", "mask" -> LongArray(ids.size) { 1 }
        "token_type_ids", "segment_ids" -> LongArray(ids.size)
        "position_ids" -> LongArray(ids.size) { it.toLong() }
        else -> null
    }

    /**
     * A language model's answer to the text of [ids], from its logits a place ([rows], one a token). A model that
     * continues text: the text it goes on with, greedily (the likeliest token each time) until an end token or
     * [NEW_TOKENS], [step] giving the logits of the last place for the ids so far (null when it can't run again),
     * then its [TOP_TOKENS] likeliest next tokens as "token:probability". A model that fills a mask: the likeliest
     * tokens at the mask, or at the last place when the text has none. [decode] turns ids into text.
     */
    fun languageAnswer(
        head: LanguageHead?, rows: List<FloatArray>, ids: LongArray, tokens: List<String>,
        decode: (LongArray) -> String, step: ((LongArray) -> FloatArray?)?,
    ): List<String> {
        if (rows.isEmpty()) return emptyList()
        fun top(row: FloatArray): List<String> {
            val p = softmax(row)
            return p.indices.sortedByDescending { p[it] }.take(TOP_TOKENS).map { "${decode(longArrayOf(it.toLong()))}:${p[it]}" }
        }
        if (head != null && !head.causal) {
            val at = tokens.indexOfFirst { it == "[MASK]" || it == "<mask>" }.takeIf { it in rows.indices } ?: rows.lastIndex
            return top(rows[at])
        }
        val made = ArrayList<Long>()
        var seq = ids
        var row: FloatArray? = rows.last()
        while (row != null && made.size < NEW_TOKENS && !Thread.currentThread().isInterrupted) {
            val next = argmax(row).toLong()
            if (next in head?.eos.orEmpty()) break
            made += next
            seq += next
            row = if (made.size < NEW_TOKENS) step?.invoke(seq) else null
        }
        return listOf(decode(made.toLongArray())) + top(rows.last())
    }

    fun argmax(values: FloatArray): Int = values.indices.maxByOrNull { values[it] } ?: 0

    fun softmax(values: FloatArray): FloatArray {
        val max = values.maxOrNull() ?: 0f
        val e = values.map { exp((it - max).toDouble()) }
        val sum = e.sum()
        return FloatArray(values.size) { (e[it] / sum).toFloat() }
    }
}
