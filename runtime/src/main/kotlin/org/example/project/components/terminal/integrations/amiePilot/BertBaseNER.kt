package org.example.project.components.terminal.integrations.amiePilot

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.nio.file.Paths
import java.time.Instant
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modelLoaders.ManagedSession
import org.example.project.components.terminal.window.controller.modelLoaders.DjlTelemetry
import org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.meanPooling
import org.example.project.components.terminal.window.controller.metrics.ModelRequirements
import org.example.project.components.terminal.window.controller.modules.OrtScopes
import org.example.project.components.terminal.window.controller.modules.ModuleManifest
import org.example.project.components.terminal.window.controller.modules.modulesDir
import org.example.project.components.terminal.window.controller.modules.readModule
import org.example.project.components.terminal.window.controller.runtime.textForLog
import org.example.project.components.terminal.window.controller.runtime.AnswersQuiet
import java.io.File
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.fir.scopes.impl.overrides

//open fun requirements(req: listOf<String>?) {
//    if(req.isEmptyOrNull()) { return IllegalArgumentException("Can't be null or empty - program crash") }
//
//
//}
//
//open fun reactOn(req: ListOf<String>? = null,
//                 reactWith: (Any?) -> Unit,
//                 ) {}

interface ModelInterface {
    fun sayHello(text: String): List<String>
    fun getContextTensor(): OnnxTensor?
    fun close()
}


//@param: modelPath - path to the model directory
class BertOnnxModel(val modelPath: String) : ModelInterface {

    private val env = OrtSessions.env
    private val modelDir = modelPath
    private var lastHiddenStateTensor: OnnxTensor? = null
    var lastTokenIds: LongArray = LongArray(0)
        private set
    // The outputs of the last run. ORT frees a result's native memory only when it is closed, so each run closes the
    // one before it (callers copy what they need out of it before their next run) and close() closes the last one
    private var lastResult: OrtSession.Result? = null
    private var closed = false

    /**
     * The last run's hidden state as a Java array, copied when asked. Every run used to copy it here whether or not
     * anyone read it: a seq × 768 copy per text, embed, predict and served request
     */
    val outputTensor: List<Array<Array<FloatArray>>>
        @Suppress("UNCHECKED_CAST")
        get() = listOfNotNull(lastHiddenStateTensor?.value as? Array<Array<FloatArray>>)

    // Managed so it runs in the background and shows up in / can be cancelled from OrtSessions. A cancel there
    // (sessions.cancel, Terminate all) ends the run in flight and frees it; the next text opens it again, so whoever
    // holds this encoder (a model, a listener, a build) never keeps a dead one. Volatile: the memory footer reads it
    // without the lock
    @Volatile var session: ManagedSession
        private set
    val tokenizer: HuggingFaceTokenizer
    init {
        session = OrtSessions.open("bert:${File(modelDir).name}", "$modelDir/model.onnx")
        DjlTelemetry.optOut()
        tokenizer = HuggingFaceTokenizer.newInstance(Paths.get("$modelDir/tokenizer.json"))
    }

    private val id2label = mapOf(
        0 to "O",
        1 to "B-PER", 2 to "I-PER",
        3 to "B-ORG", 4 to "I-ORG",
        5 to "B-LOC", 6 to "I-LOC",
        7 to "B-MISC", 8 to "I-MISC"
    )

    private fun runModel(
        ids: LongArray,
        attentionMask: LongArray,
        tokenTypeIds: LongArray,
        session: ManagedSession,
        env: OrtEnvironment
    ): List<String> {

        val idTensors = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
        val attentionMaskTensors = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), longArrayOf(1, attentionMask.size.toLong()))
        val tokenTypeTensors = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), longArrayOf(1, tokenTypeIds.size.toLong()))


        val inputs = mapOf(
            "input_ids" to idTensors,
            "attention_mask" to attentionMaskTensors,
            "token_type_ids" to tokenTypeTensors
        )

        val results = idTensors.use {
            attentionMaskTensors.use {
                tokenTypeTensors.use {
                    session.run(inputs)
                }
            }
        }
        lastResult?.close()
        lastResult = results
//        println("---- raw results----")
//            val tensor = results.get("last_hidden_state").orElse(null) as OnnxTensor
//            @Suppress("UNCHECKED_CAST")
//            val floatData = tensor.value as Array<Array<FloatArray>>
//            val cls = floatData[0][0]
//            println("Maybe [CLS]: " + cls.contentToString())
//        println("---- E raw results----")

        lastHiddenStateTensor = results.get("last_hidden_state").orElse(null) as? OnnxTensor

        @Suppress("UNCHECKED_CAST")
        val logitBatch = results.get(0).value as Array<Array<FloatArray>>

        val labels = logitBatch[0].map { tokenLogits ->
            val maxIndex = tokenLogits.indices.maxByOrNull { i -> tokenLogits[i] } ?: 0
            id2label[maxIndex] ?: "O"
        }
        return labels
    }

    /** The session the next text runs through: the one there, or a new one when it was cancelled since the last text */
    @Synchronized
    fun live(): ManagedSession {
        check(!closed) { "the encoder in $modelDir is closed" }
        if (session.isCancelled) {
            session = OrtSessions.open("bert:${File(modelDir).name}", "$modelDir/model.onnx")
            logInternal("encoder: ${File(modelDir).name} was cancelled, opened again as #${session.id}")
        }
        return session
    }

    /**
     * The last hidden state of each of [texts], run through the encoder in one go: padded to the longest with the
     * attention mask off on the padding, then each row cut back to its own tokens, so a row is [tokens][768] as
     * sayHello gives it for that text alone. The run's outputs are closed here; the last sayHello's stay as they were.
     */
    @Synchronized
    fun hiddenStates(texts: List<String>): List<Array<FloatArray>> {
        require(texts.none { it.isEmpty() }) { "an empty text has no hidden state" }
        return hiddenStatesOf(texts.map { tokenizer.encode(it) })
    }

    /** [hiddenStates] of texts the tokenizer has encoded already */
    @Synchronized
    fun hiddenStatesOf(encoded: List<Encoding>): List<Array<FloatArray>> {
        val session = live()
        if (encoded.isEmpty()) return emptyList()
        val width = encoded.maxOf { it.ids.size }
        val ids = LongArray(encoded.size * width)
        val mask = LongArray(encoded.size * width)
        val types = LongArray(encoded.size * width)
        encoded.forEachIndexed { row, e ->
            e.ids.copyInto(ids, row * width)
            e.attentionMask.copyInto(mask, row * width)
            (e.typeIds ?: LongArray(e.ids.size)).copyInto(types, row * width)
        }
        val shape = longArrayOf(encoded.size.toLong(), width.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape).use { typeTensor ->
                    session.run(mapOf("input_ids" to idTensor, "attention_mask" to maskTensor, "token_type_ids" to typeTensor)).use { result ->
                        val hidden = result.get("last_hidden_state").orElse(null) as? OnnxTensor
                            ?: throw IllegalStateException("the encoder returned no last_hidden_state")
                        @Suppress("UNCHECKED_CAST")
                        val all = hidden.value as Array<Array<FloatArray>>
                        return encoded.mapIndexed { row, e -> all[row].copyOfRange(0, e.ids.size) }
                    }
                }
            }
        }
    }

    // Runs and close() hold the same lock, the one callers sharing an encoder hold while they run a text through it
    @Synchronized
    override fun sayHello(text: String): List<String>{
        live()
        if (text.isNotEmpty()) {
            val encoded = tokenizer.encode(text)
            lastTokenIds = encoded.ids

            val tokenTypeIds = encoded.typeIds ?: LongArray(encoded.ids.size) { 0L }
            val outputModel =
                runModel(encoded.ids, encoded.attentionMask, tokenTypeIds, session, env)

            return outputModel
        }
        else {
            // Nothing runs, so the last text's state is forgotten: no caller reads it as this text's
            lastHiddenStateTensor = null
            lastTokenIds = LongArray(0)
            logInternal("Text is empty")
            return emptyList()
        }
    }

    @Synchronized
    override fun getContextTensor() : OnnxTensor? {
        if (lastHiddenStateTensor != null)  {
            return lastHiddenStateTensor
        } else {
            logInternal("No hidden state: nothing has run yet")
            return null
        }
        return null
    }

    /** Waits for a text in flight, then frees the session, the last run's outputs and the tokenizer; a second call does nothing */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session.close()
        lastResult?.close()
        lastResult = null
        lastHiddenStateTensor = null
        tokenizer.close()
    }

    fun metadata() : Any {
        return session.metadata
    }
}
fun initAmie(text: String): List<String> {
    val inst = BertOnnxModel(BundledModels.ner)
    try {
        return inst.sayHello(text)
    } finally {
        inst.close() //[H]AutoCloseable, check it out!
    }
}

// No name: the bundled BERT NER model. With a name: the module modelBuilder exported to AMP/models/intents/<name>
class InitMod(private val moduleName: String? = null) {

    fun create() : InteractionModel = if (moduleName == null) nerModel() else moduleModel(moduleName)

    // Only a script reaches this, so a block around the call answers for it; a bare initMod() is its own to close
    private fun nerModel(): InteractionModel {
        val inst = BertOnnxModel(BundledModels.ner)
        val model = object : InteractionModel {

            override val abilities = setOf("run", "tag")

            override fun getStatus(): Any {
                return inst.metadata()
            }

            override fun inference(text: String): List<String> {
                // One text at a time: each keeps its outputs until the next, and close waits on the same lock
                val res = synchronized(inst) { inst.sayHello(text) }
                logInternal("amie ${textForLog(text, keep = true)} -> ${res.joinToString(" ")}", LogLevel.DATA)
                return res
            }

            override fun close() {
                inst.close()
            }

        }
        return OrtScopes.opened(model, "default model") { inst.session }
    }

    private fun moduleModel(name: String): InteractionModel {
        val (dir, manifest) = readModule(name) ?: throw IllegalArgumentException("no model '$name' in ${modulesDir.parentFile.absolutePath}: not an intent, text, speech or imported model; amie model list shows what is there, amie hf import <repo> brings one from Hugging Face")
        require(manifest.format == "onnx") { "Module '$name' has unsupported format ${manifest.format}" }
        val missing = ModelRequirements.blockers(dir, manifest)
        check(missing.isEmpty()) { "Module '$name' can't run: ${missing.joinToString("; ") { "${it.name}: ${it.detail}" }}" }

        val headPath = File(dir, manifest.model).absolutePath
        return headModel(name, manifest) { OrtSessions.open("module:$name", headPath) }.also {
            logInternal("initMod: '$name' session ready (${manifest.input} [${manifest.inputDim}] -> ${manifest.output} [${manifest.outputDim}])")
        }
    }

}

/**
 * The model [manifest] describes, on the head [openHead] opens: text -> shared encoder -> pooling -> head. The head is
 * opened with it, and again by [openHead] after a cancel.
 */
internal fun headModel(name: String, manifest: ModuleManifest, openHead: () -> ManagedSession): InteractionModel {
    val env = OrtSessions.env
    val firstHead = openHead()
    // The encoder is shared with every other session on the same folder; only the small head is this session's own
    val encoder = try {
        SharedEncoders.acquire(manifest.encoder)
    } catch (e: Throwable) {
        firstHead.close()
        throw e
    }

    return object : InteractionModel {
        private var head = firstHead
        private var closed = false

        override val abilities = setOf("run", "classify", "fineTune")

        // The head to run a text through. A cancel (sessions.cancel or cancelAll in a script, Terminate all on the
        // page) ends what was running on it; the next text opens it again (the encoder does the same for itself),
        // so a model that was cancelled answers again instead of failing until it is closed
        @Synchronized
        private fun liveHead(): ManagedSession {
            check(!closed) { "model $name is closed" }
            if (head.isCancelled) {
                head = openHead()
                logInternal("initMod: '$name' head was cancelled, opened again as #${head.id}")
            }
            return head
        }

        override fun getStatus(): Any = manifest

        override fun inference(text: String): List<String> = inference(text, Ask.INFERENCE)

        // text -> encoder hidden state -> mean pooling -> exported head
        override fun inference(text: String, ask: Ask): List<String> {
            // A blank text has nothing to answer, as a listener and initContext say too
            if (text.isBlank()) return emptyList<String>().also { logInternal("$name: the text is empty, so there is nothing to answer", LogLevel.ERROR) }
            val head = liveHead()
            // The encoder keeps its last hidden state, so one text at a time goes through it
            val pooled = synchronized(encoder) {
                encoder.sayHello(text)
                val hidden = encoder.getContextTensor() ?: return emptyList<String>().also {
                    logInternal("initMod: '$name' encoder returned no hidden state", LogLevel.ERROR)
                }
                @Suppress("UNCHECKED_CAST")
                meanPooling(hidden.value as Array<Array<FloatArray>>)
            }
            val scores = OnnxTensor.createTensor(env, arrayOf(pooled)).use { input ->
                head.run(mapOf(manifest.input to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    (result.get(0).value as Array<FloatArray>)[0]
                }
            }
            // "label:probability", most likely first
            val res = scores.mapIndexed { i, score -> (manifest.labels.getOrNull(i) ?: i.toString()) to score }
                .sortedByDescending { it.second }
                .map { (label, score) -> "$label:$score" }
            // The answer is data the script asked for, so it shows as a DATA line: the text, then what was asked
            // for: the one label of predict, else every label with its score
            fun pretty(entry: String): String {
                val label = entry.substringBeforeLast(':')
                val score = entry.substringAfterLast(':').toDoubleOrNull()
                return if (score == null) entry else "$label ${"%.3f".format(java.util.Locale.US, score)}"
            }
            if (!AnswersQuiet.now) logInternal(
                "$name ${textForLog(text, manifest.keepTexts)} -> " + if (ask == Ask.PREDICT) res.firstOrNull()?.let(::pretty).orEmpty() else res.joinToString(transform = ::pretty),
                LogLevel.DATA,
            )
            return res
        }

        // A second call does nothing, so the shared encoder is given back once
        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            try {
                head.close()
            } finally {
                SharedEncoders.release(encoder)
            }
        }
    }
}

fun initContext(text: String, tokens: (matrix: LongArray) -> Unit = {}): Array<Array<FloatArray>>? {
    if (text.isBlank()) {
        logInternal("forwardContext: text is empty", LogLevel.ERROR)
        return null
    }
    val encoderPath = BundledModels.encoder
    val inst = SharedEncoders.acquire(encoderPath)
    try { synchronized(inst) {
        logInternal("forwardContext: running ${text.length} chars through bge")
        inst.sayHello(text)
        logInternal("forwardContext: ${inst.lastTokenIds.size} tokens ${inst.lastTokenIds.contentToString()}")
        tokens(inst.lastTokenIds)

        val contextTensor = inst.getContextTensor() ?: run {
            logInternal("forwardContext: model returned no last_hidden_state", LogLevel.ERROR)
            return null
        }
        // Copy out of the ORT tensor before the session is closed
        @Suppress("UNCHECKED_CAST")
        val matrix = contextTensor.value as Array<Array<FloatArray>>
        logInternal("forwardContext: last_hidden_state shape ${contextTensor.info.shape.contentToString()}")
        return matrix
    } } catch (e: Exception) {
        logInternal("forwardContext failed: ${e.message}", LogLevel.ERROR)
        return null
    } finally {
        SharedEncoders.release(inst)
    }
}

// One encoder session for many texts; each result is that text's last_hidden_state
// Runs every text through the encoder; onProgress gets (done, total) after each one
fun embedTexts(texts: List<String>, encoder: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Array<Array<FloatArray>>> =
    embedEach(texts, encoder, onProgress) { it }

/** Texts of one token count run through the encoder together, up to this many at a time; see [embedEach] */
const val EMBED_BATCH = 32

/**
 * Runs every text through the encoder and keeps of each only what [take] makes of its last_hidden_state. A hidden
 * state is seq × 768 floats (about 200 KB for a sentence of 64 tokens), so a build that kept every text's until the
 * last one was done needed about 2 GB of heap for 10,000 rows, more than a machine with 8 GB gives the app; a
 * sentence vector taken here as it comes is 3 KB. [onProgress] gets (done, total) after each text.
 *
 * Texts with the same number of tokens run together, [EMBED_BATCH] at a time, about 6 times quicker than one by one
 * on the talk's sentences. Only equal lengths share a run: a padded batch gives vectors a few millionths off a text
 * run alone, while these are the same to the bit (BertBaseNERBatchTest), so a model trained either way is the same.
 */
fun <T> embedEach(texts: List<String>, encoder: String, onProgress: (Int, Int) -> Unit = { _, _ -> }, take: (Array<Array<FloatArray>>) -> T): List<T> {
    texts.forEachIndexed { index, text -> require(text.isNotEmpty()) { "text ${index + 1} of ${texts.size} is empty; it has no embedding" } }
    // Shared with any open session on the same encoder, so a build never loads a second copy
    return SharedEncoders.use(encoder) { inst ->
        // A cancel (Terminate all, sessions.cancel) ends the whole job at the next text it reports: the encoder opens
        // itself again for whoever asks next, but this job doesn't go on on the session opened again
        val session = inst.live()
        var done = 0
        fun stopIfCancelled() = synchronized(inst) {
            if (inst.session !== session || session.isCancelled) {
                throw CancellationException("the encoder was cancelled after $done of ${texts.size} texts; the rest were not embedded")
            }
        }
        val encoded = synchronized(inst) { inst.live(); texts.map { inst.tokenizer.encode(it) } }
        val taken = arrayOfNulls<Any>(texts.size)
        texts.indices.groupBy { encoded[it].ids.size }.values.flatMap { it.chunked(EMBED_BATCH) }.forEach { batch ->
            synchronized(inst) {
                stopIfCancelled()
                inst.hiddenStatesOf(batch.map { encoded[it] }).forEachIndexed { n, row -> taken[batch[n]] = take(arrayOf(row)) }
            }
            repeat(batch.size) { stopIfCancelled(); onProgress(++done, texts.size) }
        }
        @Suppress("UNCHECKED_CAST")
        taken.toList() as List<T>
    }
}

// The encoder a session holds already is used rather than a second copy loaded for one sentence
fun getShapeContext() : List<Long> = SharedEncoders.use(BundledModels.encoder) { inst -> synchronized(inst) {
    inst.sayHello("hello world")
    println("--- context tensors debug: ---")
    val contextTensors = inst.getContextTensor()
    println("printing informations gotten: " + contextTensors?.info?.shape?.contentToString())
    listOf<Long>(contextTensors?.info?.shape?.get(0) ?: 0, contextTensors?.info?.shape?.get(1) ?: 0, contextTensors?.info?.shape?.get(2) ?: 0)
} }