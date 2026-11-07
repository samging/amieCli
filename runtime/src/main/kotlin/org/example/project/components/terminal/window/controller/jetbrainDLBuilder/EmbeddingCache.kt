package org.example.project.components.terminal.window.controller.jetbrainDLBuilder

import org.example.project.components.terminal.integrations.amiePilot.BundledModels
import org.example.project.components.terminal.integrations.amiePilot.embedEach
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.util.bytes
import org.example.project.getDirectory
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Turns texts into the sentence vectors a head is trained on. The build, fine-tuning and calibration all ask
 * through this, so a test can hand them vectors of its own instead of running the encoder.
 */
fun interface Embedder {
    /** One vector per text, in order; [onProgress] gets (texts done, texts) as they go, cached ones counted at once */
    fun embed(texts: List<String>, onProgress: (Int, Int) -> Unit): List<FloatArray>
}

/**
 * The encoder's last hidden state of every text, mean-pooled: what a head reads. With [cache], vectors already in
 * [EmbeddingCache] are taken from there and only the other texts go through the encoder; when every text is
 * there the encoder isn't even loaded.
 */
class EncoderEmbedder(val encoder: String, private val cache: Boolean) : Embedder {
    /** How many of the last call's texts came from the cache */
    var fromCache: Int = 0
        private set

    override fun embed(texts: List<String>, onProgress: (Int, Int) -> Unit): List<FloatArray> {
        if (!cache) {
            fromCache = 0
            return pooled(texts, onProgress)
        }
        val store = EmbeddingCache.of(encoder)
        val found = store.get(texts)
        val missing = texts.indices.filter { found[it] == null }
        fromCache = texts.size - missing.size
        if (fromCache > 0) onProgress(fromCache, texts.size)
        val fresh = if (missing.isEmpty()) emptyList() else pooled(missing.map { texts[it] }) { done, _ -> onProgress(fromCache + done, texts.size) }
        if (fresh.isNotEmpty()) store.put(missing.map { texts[it] }, fresh)
        val byIndex = HashMap<Int, FloatArray>(missing.size * 2)
        missing.forEachIndexed { n, i -> byIndex[i] = fresh[n] }
        return texts.indices.map { found[it] ?: byIndex.getValue(it) }
    }

    // Pooled as each batch comes back, so the heap holds one sentence vector per text and never every text's hidden state
    private fun pooled(texts: List<String>, onProgress: (Int, Int) -> Unit): List<FloatArray> =
        embedEach(texts, encoder, onProgress) { meanPooling(it) }
}

/**
 * Sentence vectors kept on disk, one store per encoder: `AMP/cache/embeddings/<key>/vectors.bin`, where the key
 * names the encoder folder and the size and date of its model and tokenizer files, so a changed encoder never
 * answers from an old store. A record is the SHA-256 of the text (the text itself is not kept) and its vector.
 * Builds with keepTexts off never use it. `embeddingCache.print()` and `embeddingCache.clear()` in a script.
 */
object EmbeddingCache {
    /** A store past this size is emptied and started again rather than grown */
    const val MAX_BYTES: Long = 512L * 1024 * 1024
    private const val MAGIC = "AMIEEMB1"

    val root: File get() = File(getDirectory().toString(), "cache/embeddings")

    private val stores = HashMap<String, Store>()

    /** The store of an encoder folder, opened on first use */
    @Synchronized
    fun of(encoder: String): Store {
        val resolved = File(BundledModels.resolveEncoder(encoder))
        val id = key(resolved)
        return stores.getOrPut(id) { Store(File(root, id), resolved) }
    }

    /** Folder name of an encoder's store: its name and a hash of where it is and what its files weigh and when they changed */
    fun key(encoder: File): String {
        val parts = listOf(runCatching { encoder.canonicalPath }.getOrDefault(encoder.path)) +
            listOf("model.onnx", "tokenizer.json").map { File(encoder, it) }.map { "${it.name}:${it.length()}:${it.lastModified()}" } + "mean"
        val hash = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }
        return encoder.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + hash.take(12)
    }

    /** One line per store: the encoder, how many vectors and their size on disk */
    fun describe(): List<String> {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (dirs.isEmpty()) return listOf("embeddings: nothing cached yet (${root.path})")
        return dirs.map { dir ->
            val file = File(dir, "vectors.bin")
            val encoder = runCatching { File(dir, "encoder.txt").readLines().firstOrNull() }.getOrNull() ?: dir.name
            val count = synchronized(this) { stores[dir.name]?.size } ?: countRecords(file)
            "embeddings: $count vectors of ${File(encoder).name} in ${dir.name} (${bytes(file.length())})"
        }
    }

    /** Prints [describe] in the console and returns its lines */
    fun print(): List<String> = describe().also { logInternal(it.joinToString("\n")) }

    /** Every vector of every store, gone; they are made again the next time a text is embedded */
    @Synchronized
    fun clear(): Long {
        val freed = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        stores.values.forEach { it.forget() }
        stores.clear()
        root.deleteRecursively()
        logInternal("embeddings: cache cleared (${bytes(freed)} freed)")
        return freed
    }

    /** Bytes on disk across the stores */
    fun sizeOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun countRecords(file: File): Int = runCatching {
        if (!file.isFile) return 0
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = ByteArray(MAGIC.length).also { input.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != MAGIC) return 0
            val dim = Integer.reverseBytes(input.readInt())
            ((file.length() - MAGIC.length - 4) / (32 + dim * 4L)).toInt()
        }
    }.getOrDefault(0)

    /** The vectors of one encoder: an index of text hash to file offset in memory, the vectors read when asked for */
    class Store internal constructor(private val dir: File, private val encoder: File) {
        private val file = File(dir, "vectors.bin")
        private var index: HashMap<Key, Long>? = null
        private var dim = 0

        /** Text hashes compared by value */
        private class Key(val bytes: ByteArray) {
            override fun equals(other: Any?) = other is Key && bytes.contentEquals(other.bytes)
            override fun hashCode() = bytes.contentHashCode()
        }

        val size: Int @Synchronized get() = load().size

        @Synchronized
        internal fun forget() { index = null; dim = 0 }

        /** The cached vector of every text that has one, by position; null where the encoder has to run */
        @Synchronized
        fun get(texts: List<String>): List<FloatArray?> {
            val idx = load()
            if (idx.isEmpty() || dim == 0) return texts.map { null }
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val buffer = ByteArray(dim * 4)
                    texts.map { text ->
                        val at = idx[Key(hash(text))] ?: return@map null
                        raf.seek(at)
                        raf.readFully(buffer)
                        val floats = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        FloatArray(dim).also { floats.get(it) }
                    }
                }
            }.getOrElse {
                logInternal("embeddings: couldn't read ${file.path}, the texts go through the encoder: ${it.message}", LogLevel.ERROR)
                texts.map { null }
            }
        }

        /** Keeps the vectors of [texts]; texts already kept are skipped */
        @Synchronized
        fun put(texts: List<String>, vectors: List<FloatArray>) {
            require(texts.size == vectors.size) { "one vector per text" }
            if (texts.isEmpty()) return
            load()
            val width = vectors.first().size
            if (dim != 0 && width != dim) {
                logInternal("embeddings: vectors of $width numbers don't fit the store of ${encoder.name} ($dim); it is started again", LogLevel.ERROR)
                reset()
            }
            if (file.isFile && file.length() > MAX_BYTES) {
                logInternal("embeddings: the store of ${encoder.name} passed ${bytes(MAX_BYTES)} and is started again")
                reset()
            }
            val idx = load()
            runCatching {
                dir.mkdirs()
                val fresh = !file.isFile || file.length() == 0L
                if (fresh) {
                    File(dir, "encoder.txt").writeText("${encoder.path}\nmean-pooled last hidden state; a record is sha256(text) and the vector, little-endian floats\n")
                    dim = width
                }
                var offset = if (fresh) 0L else file.length()
                FileOutputStream(file, true).buffered().use { out ->
                    if (fresh) {
                        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(width).array())
                        offset = MAGIC.length + 4L
                    }
                    val record = ByteBuffer.allocate(width * 4).order(ByteOrder.LITTLE_ENDIAN)
                    texts.forEachIndexed { n, text ->
                        val key = Key(hash(text))
                        if (idx.containsKey(key)) return@forEachIndexed
                        record.clear()
                        vectors[n].forEach { record.putFloat(it) }
                        out.write(key.bytes)
                        out.write(record.array())
                        idx[key] = offset + 32
                        offset += 32 + width * 4L
                    }
                }
            }.onFailure { logInternal("embeddings: couldn't write ${file.path}: ${it.message}", LogLevel.ERROR) }
        }

        private fun reset() {
            file.delete()
            index = HashMap()
            dim = 0
        }

        // Reads the hashes and their offsets once; a record cut short by a crash is dropped and the file trimmed.
        // The records are counted from the file's length: skip() on a file can run past its end without a word
        private fun load(): HashMap<Key, Long> {
            index?.let { return it }
            val idx = HashMap<Key, Long>()
            index = idx
            if (!file.isFile || file.length() < MAGIC.length + 4) return idx
            runCatching {
                val length = file.length()
                DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                    val magic = ByteArray(MAGIC.length).also { input.readFully(it) }
                    if (String(magic, Charsets.US_ASCII) != MAGIC) throw IllegalStateException("not a vector store")
                    dim = Integer.reverseBytes(input.readInt())
                    if (dim <= 0 || dim > 1 shl 16) throw IllegalStateException("vector width $dim")
                    val header = MAGIC.length + 4L
                    val recordBytes = 32 + dim * 4L
                    val complete = (length - header) / recordBytes
                    val vector = ByteArray(dim * 4)
                    for (n in 0 until complete) {
                        val key = ByteArray(32)
                        input.readFully(key)
                        input.readFully(vector)
                        idx[Key(key)] = header + n * recordBytes + 32
                    }
                    val good = header + complete * recordBytes
                    if (good < length) RandomAccessFile(file, "rw").use { it.setLength(good) }
                }
            }.onFailure {
                logInternal("embeddings: ${file.path} could not be read (${it.message}); it is started again", LogLevel.ERROR)
                idx.clear()
                file.delete()
                dim = 0
            }
            return idx
        }

        private fun hash(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }
}
