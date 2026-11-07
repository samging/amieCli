package org.example.project.components.terminal.integrations.amiePilot

import org.example.project.components.terminal.window.controller.modules.ModelJob
import org.example.project.components.terminal.window.controller.modules.ModelJobs

/**
 * One answer of a model. A classifier answers `label:score` lines, the score a probability, which parse into
 * the label and its score; any other answer (a package's `run` result, a NER tag, a token with a class index
 * such as `the:3`) is kept whole with no score.
 */
data class Prediction(val label: String, val score: Float?) {
    override fun toString(): String = if (score == null) label else "$label:$score"

    companion object {
        fun parse(answer: String): Prediction {
            val score = answer.substringAfterLast(':', "").toFloatOrNull()?.takeIf { it in 0f..1f }
            return if (score == null) Prediction(answer, null) else Prediction(answer.substringBeforeLast(':'), score)
        }
    }
}

/**
 * What a call of the model is for, so the model logs its answer in that shape: `predict` is answered with the one
 * label, `scores` and `inference` with every label and its score.
 */
enum class Ask { INFERENCE, PREDICT, SCORES }

/**
 * What a model can be asked, and nothing that closes it, trains it or puts another head in its place. All a
 * [org.example.project.components.terminal.window.controller.modules.SessionMod] hands on.
 */
interface ModelAnswers {
    fun inference(text: String): List<String>
    fun getStatus(): Any

    /** What it can be asked to do, in the words [can] takes; anything can at least run */
    val abilities: Set<String> get() = setOf("run")

    /**
     * Whether it can do [what], to look before calling: chat and generate for a text model, classify and fineTune for
     * a built model, tag for the NER model, hear and speak for the speech models, run for all. Any case will do
     */
    fun can(what: String): Boolean = abilities.any { it.equals(what.trim(), ignoreCase = true) }

    /** Calls it with [text] and returns every raw answer as it came: what a package's run(text) returned, a model's lines */
    fun run(text: String): List<String> = inference(text)

    /** The first raw answer to [text] as it came, scores and all; null when there was none */
    fun answer(text: String): String? = run(text).firstOrNull()

    /** [inference] asked for a reason; a model that logs its answers logs only what was asked for */
    fun inference(text: String, ask: Ask): List<String> = inference(text)

    /** The answers of [inference] parsed: a classifier's `label:score` lines become label and score, best first */
    fun predictions(text: String): List<Prediction> = inference(text).map(Prediction::parse)

    /** The predicted label: the best answer of [inference] without its score (the argmax of a classifier) */
    fun predict(text: String): String {
        // Without the text: the model may be one that keeps none (it answers other people)
        val best = inference(text, Ask.PREDICT).firstOrNull()
            ?: throw IllegalStateException(if (text.isBlank()) "the model answered nothing: the text is empty" else "the model answered nothing; its console lines say why")
        return Prediction.parse(best).label
    }

    /** Every label with its score, best first; answers that carry no score are left out */
    fun scores(text: String): Map<String, Float> =
        inference(text, Ask.SCORES).map(Prediction::parse).mapNotNull { p -> p.score?.let { p.label to it } }.toMap()

    /** Asks for [run] of [text] on a thread of its own and returns at once: `job.await()` is its answers. See [ModelJob] */
    fun inBackground(text: String): ModelJob = ModelJobs.start(this, text)
}

/**
 * A model to ask that can be closed. A model opened from a file (importModel, importModelOnnx, an imported folder) also
 * answers tensors: [inputs] and [outputs] say what it takes and gives, and [run] with numbers runs it on them. Every
 * other model takes text only, says so with empty lists and refuses a tensor run.
 */
interface InteractionModel : ModelAnswers, AutoCloseable {
    override fun close()

    /** The tensors it takes: name, shape and type. Empty for a model that takes text only */
    val inputs: List<TensorSpec> get() = emptyList()

    /** The tensors it gives; a TorchScript model knows them after its first run */
    val outputs: List<TensorSpec> get() = emptyList()

    /** Runs it on these floats, as its one input in the shape it declares; every output by name */
    fun run(values: FloatArray): Map<String, Tensor> = throw UnsupportedOperationException("this model takes text, not tensors; run(text) asks it")

    /** The same with longs, such as token ids */
    fun run(values: LongArray): Map<String, Tensor> = throw UnsupportedOperationException("this model takes text, not tensors; run(text) asks it")

    /**
     * Runs it on one value per input name: a FloatArray, LongArray, IntArray or DoubleArray in the shape the input
     * declares, or a [Tensor] with a shape of its own. Every output by name
     */
    fun run(feeds: Map<String, Any>): Map<String, Tensor> = throw UnsupportedOperationException("this model takes text, not tensors; run(text) asks it")

    /** The encoder of an encoder and decoder pair, a model that takes tensors; every other model refuses */
    val encoder: InteractionModel get() = throw UnsupportedOperationException("this model is one session, not an encoder and decoder pair")

    /** The decoder of the pair: the encoder's hidden states and the ids so far in, the next token's logits out */
    val decoder: InteractionModel get() = throw UnsupportedOperationException("this model is one session, not an encoder and decoder pair")

    /** The pair's decoder that takes the past attention state back, when its export has one */
    val decoderWithPast: InteractionModel? get() = null

    /** The id a pair's decoder starts from (decoder_start_token_id of its config); null on every other model, or when the config lacks it */
    val startToken: Long? get() = null

    /** The id that ends a pair's decoding (eos_token_id of its config); null on every other model, or when the config lacks it */
    val endToken: Long? get() = null

    /** The ids of [text] and their attention mask, from the tokenizer beside the model; refused without one */
    fun tokens(text: String): Tokens = throw UnsupportedOperationException("this model has no tokenizer beside it")

    /** The words of [ids], from the same tokenizer */
    fun text(ids: LongArray): String = throw UnsupportedOperationException("this model has no tokenizer beside it")
}

/** A text as a model takes it: the token ids and the attention mask of the same length, both as `[1, n]` tensors when run */
class Tokens(val ids: LongArray, val mask: LongArray) {
    val size: Int get() = ids.size
    override fun toString(): String = "Tokens(${ids.joinToString()})"
}
