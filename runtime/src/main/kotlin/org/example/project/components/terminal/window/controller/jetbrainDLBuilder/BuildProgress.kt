package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant

/** The stages modelBuilder goes through, in order; weight is the share of the whole build each one usually takes */
enum class BuildPhase(val label: String, val weight: Float) {
    CHECKING("checking data", 0.02f),
    EMBEDDING("embedding", 0.60f),
    TRAINING("training", 0.30f),
    VALIDATING("validating", 0.03f),
    EXPORTING("exporting", 0.05f),
    DONE("done", 0f),
    FAILED("failed", 0f);

    val finished: Boolean get() = this == DONE || this == FAILED
}

/** One modelBuilder run: where it is, how far it got and what it is doing right now */
data class ModelBuild(
    val modelName: String,
    val phase: BuildPhase,
    val step: Int = 0,
    val steps: Int = 0,
    val detail: String = "",
    val startedAt: Instant = Instant.now(),
    val finishedAt: Instant? = null,
    /** The heads trained so far with their scores on the validation rows, in the order they finished; the chosen one is marked once all are compared */
    val candidates: List<CandidateResult> = emptyList(),
    /** What runs, by the name a script calls it: modelBuilder, fineTune or calibrate */
    val job: String = "modelBuilder",
) {
    val active: Boolean get() = !phase.finished

    /** "building", "fine-tuning" or "calibrating", for the line that follows it */
    val verb: String get() = when (job) { "fineTune" -> "fine-tuning"; "calibrate" -> "calibrating"; else -> "building" }

    /** "build", "fine-tune" or "calibration", for what is said of it once it is over */
    val noun: String get() = when (job) { "fineTune" -> "fine-tune"; "calibrate" -> "calibration"; else -> "build" }
    val succeeded: Boolean get() = phase == BuildPhase.DONE

    /** Progress of the whole build in 0..1: completed phases plus the fraction of the current one */
    val fraction: Float
        get() {
            if (phase == BuildPhase.DONE) return 1f
            if (phase == BuildPhase.FAILED) return 0f
            val before = BuildPhase.entries.filter { it.ordinal < phase.ordinal }.sumOf { it.weight.toDouble() }.toFloat()
            val within = if (steps > 0) step.toFloat() / steps else 0f
            return (before + phase.weight * within).coerceIn(0f, 1f)
        }

    val elapsed: Duration get() = Duration.between(startedAt, finishedAt ?: Instant.now())

    /** "training 72/150 · epoch 72 loss 0.31" style line for the UI */
    fun describe(): String = buildString {
        append(phase.label)
        if (active && steps > 0) append(" $step/$steps")
        if (detail.isNotBlank()) append(" · $detail")
    }
}

/**
 * Progress of the model being built by amie.modelBuilder, observable from the UI.
 * buildClassifier reports every phase and step here; the sandbox status bar, the Run button and the
 * components panel read [current]. Finished builds are kept in [history], latest first.
 */
object ModelBuildProgress {
    private const val HISTORY_SIZE = 20

    private val _current = MutableStateFlow<ModelBuild?>(null)
    val current: StateFlow<ModelBuild?> = _current.asStateFlow()

    private val _history = MutableStateFlow<List<ModelBuild>>(emptyList())
    val history: StateFlow<List<ModelBuild>> = _history.asStateFlow()

    /** The last build that ran, whether it is still going or already finished */
    val last: ModelBuild? get() = _current.value ?: _history.value.firstOrNull()

    fun start(modelName: String, job: String = "modelBuilder") {
        _current.value = ModelBuild(modelName, BuildPhase.CHECKING, job = job)
    }

    fun phase(phase: BuildPhase, steps: Int = 0, detail: String = "") {
        _current.update { it?.copy(phase = phase, step = 0, steps = steps, detail = detail) }
    }

    fun step(step: Int, detail: String? = null) {
        _current.update { it?.copy(step = step, detail = detail ?: it.detail) }
    }

    /** One more head is trained and scored; the page lists it under the build at once */
    fun candidate(result: CandidateResult) {
        _current.update { it?.copy(candidates = it.candidates + result) }
    }

    /** Every head is compared: the one that is kept is marked */
    fun chose(architecture: String) {
        _current.update { b -> b?.copy(candidates = b.candidates.map { it.copy(chosen = it.architecture == architecture) }) }
    }

    /** Every head is compared: the one at [index] (in the order they were trained) is kept; two heads of one name stay apart */
    fun chose(index: Int) {
        _current.update { b -> b?.copy(candidates = b.candidates.mapIndexed { i, c -> c.copy(chosen = i == index) }) }
    }

    fun done(detail: String = "") = finish(BuildPhase.DONE, detail)

    fun failed(reason: String) = finish(BuildPhase.FAILED, reason)

    private fun finish(phase: BuildPhase, detail: String) {
        val finished = _current.value?.copy(phase = phase, detail = detail, finishedAt = Instant.now()) ?: return
        _history.update { (listOf(finished) + it).take(HISTORY_SIZE) }
        _current.value = null
    }
}

/** "41s" or "1m 12s" */
fun Duration.short(): String {
    val s = seconds
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}
