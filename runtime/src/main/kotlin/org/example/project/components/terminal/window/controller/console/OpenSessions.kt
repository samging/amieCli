package org.example.project.components.terminal.window.controller.console

import org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions
import org.example.project.components.terminal.window.controller.modelLoaders.imported.DjlSessions
import org.example.project.components.terminal.window.controller.models.KIND_ASYNC
import org.example.project.components.terminal.window.controller.models.KIND_GROUP
import org.example.project.components.terminal.window.controller.models.KIND_IMPORTED
import org.example.project.components.terminal.window.controller.models.KIND_JOB
import org.example.project.components.terminal.window.controller.models.KIND_MODEL
import org.example.project.components.terminal.window.controller.models.KIND_ONNX
import org.example.project.components.terminal.window.controller.models.KIND_PACKAGE
import org.example.project.components.terminal.window.controller.models.KIND_SHARED
import org.example.project.components.terminal.window.controller.models.KIND_SPEECH
import org.example.project.components.terminal.window.controller.models.KIND_TEMPLATE
import org.example.project.components.terminal.window.controller.models.SessionInfo
import org.example.project.components.terminal.window.controller.modules.ModelGroups
import org.example.project.components.terminal.window.controller.modules.ModelJobs
import org.example.project.components.terminal.window.controller.modules.OrtJobs
import org.example.project.components.terminal.window.controller.modules.OrtScopes
import org.example.project.components.terminal.window.controller.modules.SharedModels
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.components.terminal.window.controller.runtime.ModelSessions
import org.example.project.components.terminal.window.controller.script.TemplateRuns

/*
 * Every open session and the way to end it, with no window: what the app's Sessions tab and console list, and what the
 * interpreter's :sessions and terminate use
 */
/**
 * Every session open now, as the Sessions tab lists them and with the same Terminate: model sessions, snapshots,
 * packages, bare ONNX Runtime sessions (those named module:<name> belong to a model session), imported TorchScript and
 * TensorFlow models, swarms, the templates
 * running in the background, the answers asked in the background and the ortAsync blocks running. Cheap to read: no
 * weights, which need the model's files
 */
internal fun openSessions(): List<SessionInfo> =
    ModelSessions.all.map { s ->
        SessionInfo("model:${s.id}", s.name, s.id, KIND_MODEL, s.running > 0, s.runs, s.openedAt, s.log.lines, true, OrtScopes.scopeOf(s)) { ModelSessions.close(s.id) }
    } + SharedModels.all.map { s ->
        SessionInfo("shared:${s.id}", s.name, s.id, KIND_SHARED, s.running > 0, s.runs, s.takenAt, emptyList(), false) { SharedModels.close(s.id) }
    } + JarPackages.open.map { s ->
        SessionInfo("package:${s.id}", s.shownName, s.id, if (s.speechModel == null) KIND_PACKAGE else KIND_SPEECH, s.running > 0, s.runs, s.openedAt, s.log.lines, true, OrtScopes.scopeOf(s)) { JarPackages.close(s.id) }
    } + OrtSessions.all.filter { !it.name.startsWith("module:") }.map { s ->
        SessionInfo("ort:${s.id}", s.name, s.id, KIND_ONNX, s.running > 0, s.runs, s.openedAt, emptyList(), false, OrtScopes.scopeOfOrt(s)) { OrtSessions.cancel(s.id.toString()) }
    } + DjlSessions.all.map { s ->
        SessionInfo("djl:${s.id}", "${s.name} (${s.engine})", s.id, KIND_IMPORTED, s.running > 0, s.runs, s.openedAt, emptyList(), false, OrtScopes.scopeOf(s)) { DjlSessions.close(s.id) }
    } + ModelGroups.openSwarms.map { s ->
        SessionInfo("group:${s.id}", s.group.name, s.id, KIND_GROUP, s.running > 0, s.runs, s.openedAt, s.log.lines, true, OrtScopes.scopeOf(s)) { ModelGroups.closeSwarm(s.id) }
    } + TemplateRuns.active.map { r ->
        SessionInfo("template:${r.id}", r.name, r.id, KIND_TEMPLATE, true, 1, r.startedAt, emptyList(), false, "background") { r.cancel() }
    } + jobSessions() + OrtJobs.active.map { j ->
        SessionInfo("async:${j.id}", j.name, j.id, KIND_ASYNC, true, 0, j.startedAt, emptyList(), false, "Running") { j.cancel() }
    }

/** What a job's row says where another says the scope that holds it */
internal val jobStates = setOf("Queued", "Running")

/** The background answers as rows, whether each is queued or running said on it */
internal fun jobSessions(): List<SessionInfo> = ModelJobs.listed.map { j ->
    SessionInfo("job:${j.id}", j.name, j.id, KIND_JOB, j.state.running, 0, j.askedAt, emptyList(), false, j.state.name.replaceFirstChar(Char::uppercase)) { j.cancel() }
}

/** What Terminate all on the Sessions tab does: swarms first, since each closes the model sessions it opened, then whatever is left */
internal fun terminateAllSessions() {
    ModelJobs.cancelAll()
    ModelGroups.closeAll()
    ModelSessions.closeAll()
    SharedModels.closeAll()
    JarPackages.closeAll()
    OrtSessions.cancelAll()
    DjlSessions.closeAll()
    TemplateRuns.cancelAll()
}
