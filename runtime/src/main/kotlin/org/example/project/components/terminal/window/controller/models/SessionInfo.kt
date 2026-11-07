package org.example.project.components.terminal.window.controller.models

import org.example.project.components.terminal.integrations.amiePilot.LoadedEncoder
import org.example.project.components.terminal.window.controller.runtime.RunResources
import org.example.project.components.terminal.window.controller.runtime.SessionLogEntry
import java.time.Instant

/*
 * An open session as the app's Sessions tab and the interpreter list it, with its weight, and the kinds there are;
 * with no window
 */
/** Any open session on this page, named after its owner: an intent model, a package jar or a bare ONNX file */
internal data class SessionInfo(
    val key: String,
    val owner: String,
    val id: Int,
    val kind: String,
    val running: Boolean,
    val runs: Int,
    val openedAt: Instant,
    /** What the session was asked and answered; empty with hasLog = false for a bare ONNX session */
    val log: List<SessionLogEntry>,
    val hasLog: Boolean,
    /** The script block that holds it, such as `static search`, `timed search, 40s left` or `closable`; null when none does */
    val scope: String? = null,
    val weight: SessionWeight = SessionWeight(),
    val terminate: () -> Unit,
) {
    // Every read makes a new terminate that does what the old one did, so it says nothing of whether the session changed
    private val shown get() = listOf(key, owner, id, kind, running, runs, openedAt, log, hasLog, weight)
    override fun equals(other: Any?) = other is SessionInfo && shown == other.shown
    override fun hashCode() = shown.hashCode()
}

/**
 * What an open session holds and has spent, as near as can be told: ONNX Runtime doesn't say what it keeps in memory,
 * so the files it loaded stand for it.
 */
internal data class SessionWeight(
    /** What it has to itself: the head of a model, a package's jar, a bare ONNX file */
    val ownBytes: Long = 0,
    /** The encoder it shares with whatever else is on that folder; null when it has none, or that isn't loaded now */
    val encoder: LoadedEncoder? = null,
    /** The time it took to answer, over every run */
    val answerMillis: Long = 0,
    /** When it last answered; null before its first run */
    val lastRunAt: Instant? = null,
    /** What a background template's meter reads right now; null for every other kind */
    val meter: RunResources? = null,
)

internal const val KIND_MODEL = "Intent model"

internal const val KIND_PACKAGE = "Package"
internal const val KIND_SPEECH = "Speech model"

internal const val KIND_ONNX = "ONNX session"

/** A TorchScript or TensorFlow model from a file or AMP/models/imported, on its DJL engine; Terminate closes it */
internal const val KIND_IMPORTED = "Imported model"

internal const val KIND_GROUP = "Model group"

internal const val KIND_TEMPLATE = "Background template"

internal const val KIND_SHARED = "Session model"

internal const val KIND_JOB = "Background answer"

/** An ortAsync block running on a thread of its own; Terminate cancels it and what it opened is closed */
internal const val KIND_ASYNC = "Background block"
