package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One head to try on top of the encoder: the hidden layers (empty = a linear classifier), and how to fit it.
 * `architecture(hidden = listOf(32, 16))` in a script makes one; [BuildOptions.candidates] lists what a build tries.
 * The settings after [activation] are all off by default (see [FitSettings]); a head that leaves them off trains
 * exactly as it always has.
 */
@Serializable
data class Architecture(
    val hidden: List<Int> = listOf(16),
    val epochs: Int = 150,
    val batchSize: Int = 16,
    val learningRate: Double = 0.01,
    /** The activation between the hidden layers: Relu or Tanh */
    val activation: String = "Relu",
    /** Decoupled weight decay (AdamW) on the weights: a gentle pull towards small weights, against memorising */
    val weightDecay: Double = 0.0,
    /** The share of hidden units switched off for each row while training, against memorising; a linear head has none */
    val dropout: Double = 0.0,
    /** The share of each target spread over every label, so the head is less sure of itself on rows like the training rows */
    val labelSmoothing: Double = 0.0,
    /** How the learning rate moves over the epochs: constant, cosine or linear (both ease down to a hundredth) */
    val schedule: String = "constant",
    /** Epochs at the start over which the learning rate climbs to its value */
    val warmupEpochs: Int = 0,
    /** Early stopping: a tenth of the training rows is put aside and the fit stops once their loss has not improved for this many epochs, keeping the best epoch; 0 runs every epoch */
    val patience: Int = 0,
    /** A name of your own for the head in the console and the charts; otherwise it is named after its layers and settings */
    val name: String? = null,
) {
    init {
        require(hidden.all { it > 0 }) { "every hidden layer needs at least one unit, got $hidden" }
        require(epochs in 1..10_000) { "epochs must be between 1 and 10000, got $epochs" }
        require(batchSize >= 1) { "batchSize must be at least 1, got $batchSize" }
        require(learningRate > 0.0 && learningRate < 1.0) { "learningRate must be between 0 and 1, got $learningRate" }
        require(activation.equals("Relu", true) || activation.equals("Tanh", true)) { "activation must be Relu or Tanh, got $activation" }
        require(weightDecay >= 0.0 && weightDecay < 1.0) { "weightDecay must be at least 0 and below 1, got $weightDecay" }
        require(dropout >= 0.0 && dropout <= 0.9) { "dropout must be between 0 and 0.9, got $dropout" }
        require(labelSmoothing >= 0.0 && labelSmoothing <= 0.5) { "labelSmoothing must be between 0 and 0.5, got $labelSmoothing" }
        require(schedule in FitSettings.SCHEDULES) { "schedule must be one of ${FitSettings.SCHEDULES.joinToString()}, got $schedule" }
        require(warmupEpochs in 0 until epochs) { "warmupEpochs must be at least 0 and below epochs ($epochs), got $warmupEpochs" }
        require(patience >= 0) { "patience can't be negative, got $patience" }
        require(name == null || name.isNotBlank()) { "a name can't be blank" }
    }

    /** `linear`, `dense[16]`, `dense[64, 16]`; a head with settings of its own says them: `dense[32] · lr 0.003 · dropout 0.2` */
    val label: String get() = name ?: (layersLabel + settingsLabel())

    private val layersLabel: String get() = if (hidden.isEmpty()) "linear" else "dense${hidden.joinToString(", ", "[", "]")}"

    // Only what differs from a default head, so the four default candidates keep their short names
    private fun settingsLabel(): String = listOfNotNull(
        "lr ${num(learningRate)}".takeIf { learningRate != 0.01 },
        "$epochs epochs".takeIf { epochs != 150 },
        "batch $batchSize".takeIf { batchSize != 16 },
        activation.lowercase().takeIf { !activation.equals("Relu", true) && hidden.isNotEmpty() },
        "dropout ${num(dropout)}".takeIf { dropout > 0.0 && hidden.isNotEmpty() },
        "wd ${num(weightDecay)}".takeIf { weightDecay > 0.0 },
        "smoothing ${num(labelSmoothing)}".takeIf { labelSmoothing > 0.0 },
        schedule.takeIf { it != "constant" },
        "warm-up $warmupEpochs".takeIf { warmupEpochs > 0 },
        "early stop $patience".takeIf { patience > 0 },
    ).joinToString("") { " · $it" }

    /** The head as written in the manifest, with the input and output sizes filled in */
    fun describe(inputDim: Long, labels: Int): String {
        val dims = listOf(inputDim.toInt()) + hidden + labels
        return dims.zipWithNext().mapIndexed { i, (a, b) -> "dense[$a -> $b]" + if (i < hidden.size) " ${activation.lowercase()}" else " softmax" }.joinToString(" -> ")
    }

    /** What the fit does beyond epochs, batch and rate; all off for a plain head */
    fun fitSettings(): FitSettings = FitSettings(
        weightDecay = weightDecay.toFloat(),
        dropout = if (hidden.isEmpty()) 0f else dropout.toFloat(),
        labelSmoothing = labelSmoothing.toFloat(),
        schedule = schedule,
        warmupEpochs = warmupEpochs,
        patience = patience,
    )

    companion object {
        /** What a build tries unless told otherwise: from a linear head to two hidden layers */
        val DEFAULT_CANDIDATES: List<Architecture> = listOf(
            Architecture(hidden = emptyList()),
            Architecture(hidden = listOf(16)),
            Architecture(hidden = listOf(32)),
            Architecture(hidden = listOf(64, 16)),
        )

        private fun num(v: Double): String = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
    }
}

/**
 * How modelBuilder goes about a build: whether the preprocessor runs, which heads it tries and how the best one is
 * picked. `buildOptions(...)` in a script makes one; the Models page's New model uses the defaults. The options after
 * [keepTexts] are extensions; their defaults build what a build always built.
 */
data class BuildOptions(
    /** The heads to train; with one, nothing is compared */
    val candidates: List<Architecture> = Architecture.DEFAULT_CANDIDATES,
    /** `macroF1` (every label counts the same) or `accuracy` (every row counts the same) on the validation set */
    val pickBy: String = "macroF1",
    /** Clean and check the data first; off, rows go to the encoder as they are and only the hard checks run */
    val preprocess: Boolean = true,
    /** The share of the rows held out when there is no related test file */
    val validationFraction: Double = 0.2,
    /** The seed of the split, the weights and the shuffles: the same data and seed give the same model */
    val seed: Int = 42,
    /** Weight the loss so every label counts the same, for imbalanced data (a rare label is otherwise learned last) */
    val balanceClasses: Boolean = false,
    /**
     * Off for models trained on other people's data (the model site): no row text reaches the console or
     * dropped.csv, and the model's manifest says so, so its sessions log answers without the texts asked
     */
    val keepTexts: Boolean = true,
    /**
     * Cross-validation: with 2 to 10, every candidate is also trained [folds] times, each time scored on the fold it
     * did not see, and the best mean is picked (steadier than one split on small data); the rows every fold head
     * doubts are reported as possibly mislabeled. 0 compares the heads on the one validation split
     */
    val folds: Int = 0,
    /** Fit a temperature on the validation rows and fold it into the head, so a score of 0.8 is right about 80% of the time; the answers stay the same */
    val calibrate: Boolean = false,
    /** Keep every text's sentence vector in AMP/cache/embeddings, so building again or fine-tuning embeds only new texts; never with keepTexts off */
    val cacheEmbeddings: Boolean = true,
    /** How many of the validation rows the model got wrong are kept with the run (the surest mistakes first); 0 keeps none */
    val keepMistakes: Int = 25,
) {
    init {
        require(candidates.isNotEmpty()) { "at least one architecture is needed" }
        require(pickBy == "macroF1" || pickBy == "accuracy") { "pickBy must be macroF1 or accuracy, got $pickBy" }
        require(validationFraction > 0.0 && validationFraction < 0.5) { "validationFraction must be between 0 and 0.5, got $validationFraction" }
        require(folds == 0 || folds in 2..10) { "folds must be 0 (no cross-validation) or between 2 and 10, got $folds" }
        require(keepMistakes >= 0) { "keepMistakes can't be negative, got $keepMistakes" }
    }

    /** Whether vectors go to the disk cache: asked for, and allowed (texts of other people's data leave no trace) */
    val cachesEmbeddings: Boolean get() = cacheEmbeddings && keepTexts
}

/** One point of a head's learning curve: the epoch, the training loss and accuracy, and the validation rows' */
@Serializable
data class CurvePoint(
    val epoch: Int,
    val loss: Double,
    val accuracy: Double,
    val validationLoss: Double? = null,
    val validationAccuracy: Double? = null,
    /** The loss on the rows early stopping watched, when it was on */
    val monitorLoss: Double? = null,
)

/** What one candidate came to on the validation set; the list of them is kept with the model's metrics */
@Serializable
data class CandidateResult(
    val architecture: String,
    val hidden: List<Int>,
    val epochs: Int,
    val parameters: Long,
    val accuracy: Double,
    val macroF1: Double,
    val trainAccuracy: Double,
    val finalLoss: Double,
    val chosen: Boolean = false,
    /** F1 per label on the validation rows, so the heads can be compared label by label */
    val perLabelF1: Map<String, Double> = emptyMap(),
    /** What the diagnostics say about this head: memorising, barely above the majority label, weak labels */
    val insights: List<String> = emptyList(),
    /** Cross-validation, when the build ran it: the mean accuracy and macro F1 over the folds, and the spread of the macro F1 */
    val cvAccuracy: Double? = null,
    val cvMacroF1: Double? = null,
    val cvSpread: Double? = null,
    val folds: Int? = null,
    /** The epoch early stopping stopped at, and the one whose weights were kept; null when every epoch ran */
    val stoppedAt: Int? = null,
    val keptEpoch: Int? = null,
    /** The learning curve, at most about 40 points over the epochs */
    val curve: List<CurvePoint> = emptyList(),
)

/** The best candidate: by the chosen score, then the other score, then the fewest parameters */
fun pickBest(results: List<CandidateResult>, pickBy: String): CandidateResult =
    results.sortedWith(
        compareByDescending<CandidateResult> { if (pickBy == "accuracy") it.accuracy else it.macroF1 }
            .thenByDescending { if (pickBy == "accuracy") it.macroF1 else it.accuracy }
            .thenBy { it.parameters }
    ).first()

/**
 * The best candidate by cross-validation: the higher mean of the chosen score over the folds, then the other
 * mean, then the validation split's score, then the fewest parameters. Falls back to [pickBest] when no candidate
 * was cross-validated.
 */
fun pickBestByFolds(results: List<CandidateResult>, pickBy: String): CandidateResult {
    if (results.any { it.cvMacroF1 == null || it.cvAccuracy == null }) return pickBest(results, pickBy)
    return results.sortedWith(
        compareByDescending<CandidateResult> { if (pickBy == "accuracy") it.cvAccuracy else it.cvMacroF1 }
            .thenByDescending { if (pickBy == "accuracy") it.cvMacroF1 else it.cvAccuracy }
            .thenByDescending { if (pickBy == "accuracy") it.accuracy else it.macroF1 }
            .thenBy { it.parameters }
    ).first()
}
