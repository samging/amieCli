package org.example.project.amp

import org.example.project.getDirectory
import java.io.File

/**
 * Where the models are in an AMP folder: everything under `AMP/models/`, one folder per kind. This is the one owner of
 * these paths; `modulesDir`, `TextModels.dir`, `BundledModels`, `SpeechModels`, the Models page and the exporter all
 * read them here, and no other code builds them.
 *
 * | Folder | Holds |
 * |---|---|
 * | `models/bert/`, `models/bert/bge/` | the NER model and the encoder every intent model is trained on |
 * | `models/intents/<name>/` | the trained intent models (modules), with `intents/modules.json` as their registry |
 * | `models/text/<name>/` | the text models (Llama, Qwen) |
 * | `models/speech/<name>/` | Whisper and the voices speech.jar runs |
 *
 * Before November 2026 the three moving kinds lived in `AMP/modules/`, `AMP/textModels/` and
 * `AMP/pluginDir/config/speech/models/`. [ModelsMigration] renames them into place when the app starts; until it has
 * (a read-only folder, a bundle made before the move), a kind whose new folder is not there is read from its old one.
 * That fallback is kept for one release: in the release after 2026-11, drop the old branch of [of] and [Kind.old].
 */
class ModelFolders(val amp: File) {
    /** A kind of model that moved: its folder under `models/` and the one it was in before */
    enum class Kind(val label: String, val new: String, val old: String) {
        INTENTS("intent models", "models/intents", "modules"),
        TEXT("text models", "models/text", "textModels"),
        SPEECH("speech models", "models/speech", "pluginDir/config/speech/models"),
    }

    /** `AMP/models` */
    val root: File get() = File(amp, "models")

    /** `AMP/models/bert`: the NER model, with the bge encoder in its `bge/` subfolder (unchanged by the move) */
    val bert: File get() = File(root, "bert")

    /** The trained intent models and their registry */
    val intents: File get() = of(Kind.INTENTS)

    /** The text models */
    val text: File get() = of(Kind.TEXT)

    /** The speech models, where speech.jar reads them unless its `models=` setting says otherwise */
    val speech: File get() = of(Kind.SPEECH)

    /** Models brought in from outside, such as a Hugging Face repo: one folder each, `AMP/models/imported/<name>` */
    val imported: File get() = File(root, "imported")

    /** The folder of [kind] under `models/`, whether or not it is there yet */
    fun newOf(kind: Kind): File = File(amp, kind.new)

    /** The folder [kind] was in before the move, whether or not it is still there */
    fun oldOf(kind: Kind): File = File(amp, kind.old)

    /**
     * The folder to read and write for [kind]: the new one, or the old one while only the old one is there. Neither
     * there: the new one, so a fresh folder starts in the new layout. The old branch is the one-release fallback.
     */
    fun of(kind: Kind): File {
        val new = newOf(kind)
        return if (new.isDirectory || !oldOf(kind).isDirectory) new else oldOf(kind)
    }

    companion object {
        /**
         * The app's own AMP folder. The first look at it moves the models into place ([ModelsMigration.runOnce]), so
         * nothing reads a model from its old folder when the new one could be there.
         */
        val amp: ModelFolders by lazy { ModelFolders(File(getDirectory().toString())).also { ModelsMigration.runOnce(it) } }
    }
}
