package org.example.project.components.terminal.integrations.amiePilot

import java.io.File
import org.example.project.amp.ModelFolders

/**
 * Where the BERT models are. A packaged app ships them under its resources folder as `models/bert/` (the NER
 * model) and `models/bert/bge/` (the encoder every intent model is trained on); Compose points at that folder with
 * the `compose.application.resources.dir` system property. A development run finds them in `AMP/models/bert`, where
 * AMP/setup/setup.sh of the repository downloads them, or in the checkout's usual place, and `AMIE_MODELS_DIR` overrides all of these for a
 * machine that keeps them elsewhere. An exported bundle's runner
 * names its own `models/bert/` with the `amie.models.dir` system property, whatever that folder holds.
 */
object BundledModels {
    private const val DEV_ROOT = "/Users/samuel/Documents/onnx/bert"
    const val ENCODER_NAME = "bge"

    /** The folder holding model.onnx and tokenizer.json of the NER model, with the encoder in its `bge/` subfolder */
    val root: File by lazy {
        val bundle = System.getProperty("amie.models.dir")?.let(::File)
        if (bundle != null && System.getenv("AMIE_MODELS_DIR").isNullOrBlank()) return@lazy bundle
        val amp = runCatching { ModelFolders.amp.bert }.getOrNull()
        val candidates = listOfNotNull(
            System.getenv("AMIE_MODELS_DIR")?.let(::File),
            System.getProperty("compose.application.resources.dir")?.let { File(it, "models/bert") },
            amp,
            File(DEV_ROOT),
        )
        candidates.firstOrNull { File(it, "model.onnx").isFile || File(it, "$ENCODER_NAME/model.onnx").isFile } ?: amp ?: candidates.last()
    }

    /** The bundled NER model (dslim/bert-base-NER) */
    val ner: String get() = root.path

    /** The bundled bge encoder */
    val encoder: String get() = File(root, ENCODER_NAME).path

    /** True when the app runs from a native distribution rather than a Gradle run */
    val packaged: Boolean get() = System.getProperty("compose.application.resources.dir") != null

    /**
     * The encoder folder a model manifest names, or the bundled one of the same name when that folder is not on this
     * machine (a model exported on another machine, or the dummy written by an earlier build), so a shipped head
     * still finds its encoder.
     */
    fun resolveEncoder(path: String): String {
        if (File(path, "model.onnx").isFile) return path
        val bundled = File(root, File(path).name)
        if (File(bundled, "model.onnx").isFile) return bundled.path
        return if (File(path).name == root.name && File(root, "model.onnx").isFile) root.path else encoder
    }
}
