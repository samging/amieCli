package org.example.project.components.terminal.window.controller.modules

import org.example.project.components.terminal.integrations.amiePilot.Ask
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.integrations.amiePilot.ModelAnswers
import org.example.project.components.terminal.integrations.amiePilot.headModel
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modelLoaders.OrtSessions
import org.example.project.components.terminal.window.controller.runtime.LiveModel
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KProperty

/**
 * One head of a model as it was when it was taken: its manifest and its bytes, read together and kept in memory. It
 * only answers. It isn't the model's session: nothing it is asked reaches the model's sessions.log or its counts, and
 * a fine-tune, a build, a rollback, a rename or a destroy of the model leaves it as it is, where a session moves to the
 * new head. The encoder is the shared one, so a snapshot costs its head and no more.
 */
class ModelSnapshot internal constructor(
    val id: Int,
    val name: String,
    val manifest: ModuleManifest,
    /** What the head weighs: it is kept here, so a cancel can open this head again and not the one the file holds by then */
    val headBytes: Long,
    internal val key: String,
    model: InteractionModel,
) {
    val takenAt: Instant = Instant.now()
    private val runningCount = AtomicInteger()
    private val runCount = AtomicInteger()
    private val answerNanos = AtomicLong()

    // close() waits for the answers in flight on it
    private val live = LiveModel(model)
    private val closing = AtomicBoolean()

    /** How many [SessionMod]s hold it; changed under the lock of [SharedModels] */
    internal var holderCount = 0

    val holders: Int get() = holderCount
    val running: Int get() = runningCount.get()
    val runs: Int get() = runCount.get()

    /** The time it took to answer, over every run */
    val answerMillis: Long get() = answerNanos.get() / 1_000_000

    /** When it last answered; null before its first run */
    @Volatile var lastRunAt: Instant? = null
        private set

    val version: Int get() = ModelVersions.numberOf(manifest)
    val closed: Boolean get() = closing.get()

    internal fun ask(text: String, ask: Ask): List<String> {
        check(!closed) { "the snapshot of $name v$version was terminated" }
        runningCount.incrementAndGet()
        val begun = System.nanoTime()
        try {
            return live.inference(text, ask)
        } finally {
            answerNanos.addAndGet(System.nanoTime() - begun)
            runningCount.decrementAndGet()
            runCount.incrementAndGet()
            lastRunAt = Instant.now()
        }
    }

    internal fun close() {
        if (!closing.compareAndSet(false, true)) return
        ModelJobs.cancelWhere { (it.on as? SnapshotHold)?.snapshot === this }
        runCatching { live.close() }.onFailure { logInternal("sessionMod: the snapshot of $name v$version could not be closed: ${it.message}", LogLevel.ERROR) }
        logInternal("sessionMod: the snapshot of $name v$version closed after $runs runs")
    }
}

// One holder of a snapshot, what a SessionMod hands its answers on to; once it let go it answers no more, whoever else holds the snapshot
private class SnapshotHold(val snapshot: ModelSnapshot) : ModelAnswers {
    private val released = AtomicBoolean()

    override fun inference(text: String): List<String> = inference(text, Ask.INFERENCE)

    override fun inference(text: String, ask: Ask): List<String> {
        check(!released.get()) { "this sessionMod of ${snapshot.name} was closed" }
        return snapshot.ask(text, ask)
    }

    override fun getStatus(): Any = snapshot.manifest

    // A snapshot only answers: it is the session of the model that can be fine-tuned
    override val abilities = setOf("run", "classify")

    override fun inBackground(text: String): ModelJob = ModelJobs.start(this, text, snapshot.name)

    fun release() {
        if (!released.compareAndSet(false, true)) return
        ModelJobs.endOn(this)
        SharedModels.release(snapshot)
    }
}

/**
 * A model to ask and nothing more, what `sessionMod("name")` gives a script. It delegates [ModelAnswers] to a
 * [ModelSnapshot] and is no [InteractionModel], so it has nothing that trains the model, changes it or closes it, and
 * can't be handed to anything that does. It needs nothing else open, and leaves what is open alone: everything that
 * asks for the same head of a model shares one snapshot. It goes with the block it was opened for or the run that
 * took it, or with [close]; `val m by sessionMod("name")` works as well as `val m = sessionMod("name")`.
 */
/** The name [SessionMod] had before, for a script that spells the type out */
@Deprecated("SharedModel is SessionMod now", ReplaceWith("SessionMod"))
typealias SharedModel = SessionMod

class SessionMod private constructor(private val hold: SnapshotHold) : ModelAnswers by hold, AutoCloseable {
    internal constructor(snapshot: ModelSnapshot) : this(SnapshotHold(snapshot))

    val name: String get() = hold.snapshot.name

    /** The number of the head it answers with, which stays what it was when it was taken */
    val version: Int get() = hold.snapshot.version
    val labels: List<String> get() = hold.snapshot.manifest.labels

    /** How the Sessions tab knows it */
    internal val sessionKey: String get() = "shared:${hold.snapshot.id}"

    operator fun getValue(thisRef: Any?, property: KProperty<*>): SessionMod = this

    /** Lets go of the snapshot, the last holder closing it; the model isn't touched. A second call does nothing */
    override fun close() = hold.release()

    override fun toString(): String = "sessionMod $name v$version · ${labels.size} labels"
}

/** The snapshots in memory, one per head of a model however many hold it, so the Models page can show and terminate them */
object SharedModels {
    private val ids = AtomicInteger()
    private val snapshots = LinkedHashMap<String, ModelSnapshot>()

    // What [all] reads: replaced, never changed, under the lock, so the page never waits for an encoder that is loading
    @Volatile private var shown: List<ModelSnapshot> = emptyList()

    val all: List<ModelSnapshot> get() = shown

    /** Opens the model a snapshot answers with; the tests hand in their own */
    internal var opener: (String, ModuleManifest, File, ByteArray) -> InteractionModel = { name, manifest, file, head ->
        headModel(name, manifest) { OrtSessions.open("module:$name@v${ModelVersions.numberOf(manifest)}", file.path, head) }
    }

    /** A [SessionMod] on the head [modelName] has now, on the snapshot of that head when there is one */
    fun take(modelName: String): SessionMod {
        val name = modelName.trim()
        require(!name.endsWith(".jar", ignoreCase = true)) { "sessionMod takes a model, not a package: a jar has no head to keep; initMod(\"$name\") opens it" }
        // Read under the lock a write of the model holds, so the head and its labels are of one moment
        val (manifest, file, head) = ModuleLocks.locked(name) {
            val (dir, manifest) = readModule(name) ?: throw IllegalArgumentException("model '$name' not found in ${modulesDir.absolutePath}")
            require(manifest.format == "onnx") { "model '$name' has unsupported format ${manifest.format}" }
            val file = File(dir, manifest.model)
            Triple(manifest, file, file.readBytes())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(head).joinToString("") { "%02x".format(it) }
        val key = "$name:${ModelVersions.numberOf(manifest)}:$digest"
        val snapshot = synchronized(this) {
            val existing = snapshots[key]
            val held = existing ?: ModelSnapshot(ids.incrementAndGet(), name, manifest, head.size.toLong(), key, opener(name, manifest, file, head))
            snapshots[key] = held
            held.holderCount++
            shown = snapshots.values.toList()
            logInternal(if (existing == null) "sessionMod: '$name' v${held.version} snapshot taken as #${held.id}" else "sessionMod: '$name' v${held.version} is already in memory as #${held.id}, held by ${held.holderCount}")
            held
        }
        return SessionMod(snapshot)
    }

    internal fun release(snapshot: ModelSnapshot) {
        val last = synchronized(this) {
            snapshot.holderCount--
            (snapshot.holderCount <= 0 && snapshots.remove(snapshot.key, snapshot)).also { shown = snapshots.values.toList() }
        }
        if (last) snapshot.close()
    }

    /** Terminates it for all who hold it; their next question fails, saying so. The model isn't touched */
    fun close(id: Int): Boolean {
        val snapshot = synchronized(this) {
            val found = snapshots.values.firstOrNull { it.id == id } ?: return false
            snapshots.remove(found.key)
            shown = snapshots.values.toList()
            found
        }
        snapshot.close()
        return true
    }

    fun closeAll() = all.forEach { close(it.id) }
}
