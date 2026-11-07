package org.example.project.components.terminal.integrations.amiePilot

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.util.FileSizes
import org.example.project.components.terminal.window.controller.util.SystemStats
import org.example.project.components.terminal.window.controller.util.bytes
import java.io.File

/** One loaded encoder: its folder, how many holders share it and what that folder weighs on disk (model.onnx, the tokenizer and its configs) */
data class LoadedEncoder(val path: String, val holders: Int, val sizeBytes: Long)

/**
 * The encoders in memory, one per folder however many sessions use it. An encoder is the heavy part of a model
 * (hundreds of MB loaded by ORT outside the heap, the head is a few KB), so every `initMod`, Listen, validation
 * and build that used to load its own copy acquires the shared one here and releases it when it closes; the
 * last release closes it. Callers synchronize on the encoder while they run a text through it, as it keeps the
 * last hidden state between `sayHello` and `getContextTensor`.
 */
object SharedEncoders {
    private class Entry(val key: String, val encoder: BertOnnxModel) { var holders = 0 }
    private val entries = HashMap<String, Entry>()
    // What [loaded] reads: replaced, never changed, under the lock, so the memory footer reads it without waiting for
    // a load (seconds) or a close (it waits for a text in flight) that holds the lock
    @Volatile private var shown: List<Pair<LoadedEncoder, BertOnnxModel>> = emptyList()

    private fun publish() {
        shown = entries.map { (k, e) -> LoadedEncoder(k, e.holders, FileSizes.folder(File(k))) to e.encoder }
    }

    // The folder a caller names may be another machine's; the bundled encoder of that name stands in for it
    private fun key(path: String) = runCatching { File(BundledModels.resolveEncoder(path)).canonicalPath }.getOrDefault(path)

    /** The encoder of that folder, loaded on the first call and shared after it */
    @Synchronized
    fun acquire(path: String): BertOnnxModel {
        val k = path.let(::key)
        val entry = entries[k] ?: Entry(k, load(k, File(path).name)).also {
            entries[k] = it
            logInternal("encoder: loaded ${File(path).name} (${bytes(FileSizes.folder(File(k)))} on disk)")
        }
        entry.holders++
        if (entry.holders > 1) logInternal("encoder: ${File(path).name} shared by ${entry.holders} holders, not loaded again")
        publish()
        return entry.encoder
    }

    // ORT loads the encoder outside the heap, and on a machine short of memory that is where a load fails, with a
    // native error that names no cause (bad_alloc, or a session that could not be created). The console then says
    // what the process held and what the machine had left, so the reason is in view; what threw goes on to the caller
    private fun load(key: String, name: String): BertOnnxModel = try {
        BertOnnxModel(key)
    } catch (e: Throwable) {
        logInternal(loadFailure(name, File(key, "model.onnx").length(), e, runCatching { SystemStats.read() }.getOrNull()), LogLevel.ERROR)
        throw e
    }

    /** The console line of an encoder that would not load: what it weighs, what went wrong and how memory stood */
    internal fun loadFailure(name: String, sizeBytes: Long, cause: Throwable, stats: SystemStats?): String {
        val memory = stats?.takeIf { it.physicalBytes > 0 }?.let {
            "; this process held ${bytes(it.residentBytes)} of the machine's ${bytes(it.physicalBytes)}, with ${bytes(it.physicalFreeBytes)} free"
        } ?: ""
        return "encoder: couldn't load $name (${bytes(sizeBytes)} on disk): ${cause.message ?: cause::class.simpleName}$memory. " +
            "It loads outside the Java heap, so if memory is short, close sessions and stop listeners you don't need and try again"
    }

    /** Gives back [encoder], the one [acquire] handed out; the last holder's release closes it and frees its memory */
    @Synchronized
    fun release(encoder: BertOnnxModel) {
        val entry = entries.values.firstOrNull { it.encoder === encoder } ?: return
        entry.holders--
        if (entry.holders <= 0) {
            entries.remove(entry.key)
            runCatching { entry.encoder.close() }.onFailure { logInternal("encoder: close of ${File(entry.key).name} failed: ${it.message}", LogLevel.ERROR) }
            logInternal("encoder: ${File(entry.key).name} released and closed")
        }
        publish()
    }

    /** Runs [block] with the encoder of [path], acquired for just that long */
    inline fun <T> use(path: String, block: (BertOnnxModel) -> T): T {
        val encoder = acquire(path)
        try {
            return block(encoder)
        } finally {
            release(encoder)
        }
    }

    /**
     * What is loaded right now, for the memory footer; never waits. An encoder cancelled and not opened again yet
     * (that happens on its next text) holds no ORT memory and isn't listed, though it is still held
     */
    val loaded: List<LoadedEncoder> get() = shown.filter { (_, encoder) -> !encoder.session.isCancelled }.map { it.first }

    /** The encoder of that folder as [loaded] has it, null when it isn't loaded now */
    fun loadedOn(path: String): LoadedEncoder? = key(path).let { k -> loaded.firstOrNull { it.path == k } }
}
