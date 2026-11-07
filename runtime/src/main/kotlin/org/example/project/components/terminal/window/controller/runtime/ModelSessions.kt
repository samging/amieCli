package org.example.project.components.terminal.window.controller.runtime

import org.example.project.components.terminal.integrations.amiePilot.Ask
import org.example.project.components.terminal.integrations.amiePilot.InitMod
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modules.ModelGroups
import org.example.project.components.terminal.window.controller.modules.ModelJobs
import org.example.project.components.terminal.window.controller.modules.moduleDir
import org.example.project.components.terminal.window.controller.modules.readModule
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One open intent model: the encoder and the exported head behind the `inference / getStatus / close` interface.
 * Every `initMod("name")` makes one, and the Models page lists them so any of them can be terminated there.
 */
class ModelSession internal constructor(
    val id: Int,
    val name: String,
    model: InteractionModel,
    /** "intent model", or "collection" when the name is a collection of models opened together */
    val kind: String = "intent model",
    logFile: File = File(moduleDir(name), "sessions.log"),
    /** False when the model's manifest says so: the log keeps each text's length, not the text */
    val keepTexts: Boolean = true,
) : InteractionModel {
    val openedAt: Instant = Instant.now()
    private val runningCount = AtomicInteger()
    private val runCount = AtomicInteger()
    private val answerNanos = AtomicLong()

    // The model behind the session, which a new head takes the place of while the session stays open ([swap])
    private val live = LiveModel(model)

    /** How many times a new head took the place of the one the session answered with */
    val headSwaps: Int get() = live.swaps

    /** What this session was asked and answered; also appended to AMP/models/intents/<name>/sessions.log */
    val log = SessionLog(name, id, logFile, keepTexts = keepTexts)

    /** Calls of inference in flight right now */
    val running: Int get() = runningCount.get()
    val runs: Int get() = runCount.get()

    /** The time it took to answer, over every run */
    val answerMillis: Long get() = answerNanos.get() / 1_000_000

    /** When it last answered; null before its first run */
    @Volatile var lastRunAt: Instant? = null
        private set

    // Taken by the one close() that does the work: two at once (Terminate and a script's close) mustn't both close the
    // model, which would give its shared encoder back twice
    private val closing = AtomicBoolean()

    /** Closed, or being closed: such a session is never handed out again */
    val closed: Boolean get() = closing.get()

    override fun inference(text: String): List<String> = inference(text, Ask.INFERENCE)

    override fun inference(text: String, ask: Ask): List<String> {
        check(!closed) { "Model $name (#$id) is closed" }
        runningCount.incrementAndGet()
        val begun = System.nanoTime()
        try {
            // A plain inference goes through the model's own entry point; predict and scores say what they are for
            val answer = if (ask == Ask.INFERENCE) live.inference(text) else live.inference(text, ask)
            return answer.also { log.run(text, it, (System.nanoTime() - begun) / 1_000_000) }
        } catch (e: Throwable) {
            log.error(text, e)
            throw e
        } finally {
            answerNanos.addAndGet(System.nanoTime() - begun)
            runningCount.decrementAndGet()
            runCount.incrementAndGet()
            lastRunAt = Instant.now()
        }
    }

    override fun getStatus(): Any = live.getStatus()

    override val abilities: Set<String> get() = live.abilities

    /**
     * [next] answers this session from now on, and the model before is closed once the answers in flight on it
     * are done: what a fine-tune, a build or a rollback of the model does to its open session, so a script or a
     * swarm holding it goes on with the new head under the same id. [note] goes to the session's log. False, and
     * [next] closed, when the session is closed already.
     */
    fun swap(next: InteractionModel, note: String): Boolean {
        val old = synchronized(this) {
            if (closed) {
                runCatching { next.close() }
                return false
            }
            live.swap(next)
        }
        runCatching { old.close() }.onFailure { logInternal("model $name (#$id): the head before could not be closed: ${it.message}", LogLevel.ERROR) }
        log.add("swap", note)
        logInternal("model $name (#$id): $note")
        return true
    }

    /** Closes the head session and the encoder; a second call, even one at the same time, does nothing */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        ModelJobs.endOn(this)
        synchronized(this) {
            runCatching { live.close() }.onFailure { logInternal("model $name (#$id): close failed: ${it.message}", LogLevel.ERROR) }
        }
        ModelSessions.unregister(this)
        log.add("close", "closed after $runs runs")
        logInternal("model $name (#$id): closed after $runs runs")
    }

    override fun toString(): String = "#$id $name · ${if (running > 0) "running $running" else "idle"} · runs $runs"
}

/** The open intent model sessions, so the Models page can show and terminate them */
object ModelSessions {
    private val ids = AtomicInteger()
    private val sessions = linkedMapOf<Int, ModelSession>()

    val all: List<ModelSession> @Synchronized get() = sessions.values.toList()

    /** Opens the model a session answers with, from the module as it is on disk; the tests hand in their own */
    internal var opener: (String) -> InteractionModel = { if (TextModels.has(it)) TextModels.open(it) else InitMod(it).create() }

    /**
     * The open sessions of [name] answer with the model as it is on disk now: for each, a new model is opened on
     * the head it has, put in the session's place, and the one before is closed once its answers in flight are
     * done ([ModelSession.swap]). The session keeps its id, its log and its counts, so whoever holds it isn't
     * told anything. A new head that can't be opened leaves the session on the old one, with the reason in the
     * console. A session on a model that stopped keeping texts is closed instead of swapped: its log would go
     * on keeping them. Returns how many sessions were swapped.
     */
    fun reload(name: String, note: String): Int {
        val open = all.filter { it.name == name && !it.closed }
        if (open.isEmpty()) return 0
        val keepTexts = readModule(name)?.second?.keepTexts ?: true
        var swapped = 0
        open.forEach { session ->
            if (session.keepTexts && !keepTexts) {
                logInternal("model $name (#${session.id}): the model keeps no texts any more, so the session on the old head is closed; the next initMod opens the new one")
                session.close()
                return@forEach
            }
            val next = try {
                opener(name)
            } catch (e: Exception) {
                logInternal("model $name (#${session.id}): the new head could not be opened, the session keeps the old one: ${e.message}", LogLevel.ERROR)
                return@forEach
            }
            if (session.swap(next, note)) swapped++
        }
        return swapped
    }

    /**
     * The session of the module exported to AMP/models/intents/<name>. A model has one session at most: the one already
     * open is returned (so a script run again, two scripts, or the page's button don't pile up sessions), else a
     * new one is opened.
     */
    fun open(name: String): ModelSession = acquire(name).first

    /** The session of the model and whether this call opened it, for a caller that closes only what it opened */
    @Synchronized
    fun acquire(name: String): Pair<ModelSession, Boolean> {
        val existing = sessions.values.firstOrNull { it.name == name && !it.closed }
        if (existing != null) {
            existing.log.add("open", "reused: a model has one session")
            logInternal("initMod: '$name' is already open as session #${existing.id}, that one is used")
            return existing to false
        }
        // What is said to a text model stays out of its log. It is no module, so it isn't looked for among them
        val session = if (TextModels.has(name)) ModelSession(ids.incrementAndGet(), name, opener(name), "text model", File(TextModels.folder(name), "sessions.log"), keepTexts = false)
            else ModelSession(ids.incrementAndGet(), name, opener(name), keepTexts = readModule(name)?.second?.keepTexts ?: true)
        session.log.add("open", "opened as session #${session.id}")
        sessions[session.id] = session
        return session to true
    }

    fun close(id: Int): Boolean {
        val session = synchronized(this) { sessions[id] } ?: return false
        session.close()
        return true
    }

    /** Closes every model session and every swarm over them */
    @Synchronized
    fun closeAll() {
        ModelGroups.closeAll()
        sessions.values.toList().forEach { it.close() }
    }

    internal fun unregister(session: ModelSession) {
        synchronized(this) { sessions.remove(session.id) }
    }
}
