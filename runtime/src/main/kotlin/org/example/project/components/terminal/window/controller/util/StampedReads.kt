package org.example.project.components.terminal.window.controller.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** What tells one version of a file from the next: when it was written, to the nanosecond the disk keeps, and how long it is */
data class FileStamp(val modified: Long, val size: Long)

/** The stamp of a file or folder as it is now; null when it isn't there */
fun stampOf(file: File): FileStamp? = try {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    FileStamp(attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.size())
} catch (e: IOException) {
    null
}

/**
 * What was made of a file, one version at a time: asking again for a file that hasn't changed costs one look at its
 * stamp, and the same object comes back. The newest [capacity] worth is kept, by [weight], and the very newest whatever
 * it weighs. What couldn't be made anything of is held as null until the file changes; an exception goes to the caller,
 * and nothing is held.
 */
class StampedReads<T>(private val capacity: Long = 64, private val weight: (T) -> Long = { 1 }) {
    class Read<T>(val stamps: List<FileStamp?>, val value: T?)

    private val held = LinkedHashMap<String, Read<T>>(16, 0.75f, true)
    private var weighs = 0L

    /** What is held for [key], of whichever version of the file; this never touches the disk, so the UI thread may ask */
    @Synchronized
    fun last(key: String): Read<T>? = held[key]

    /** [file] as it is now */
    fun read(file: File, key: String = file.path, make: (File) -> T?): Read<T> = read(listOf(file), key) { make(file) }

    /** What is made of [files] as they are now. The stamps are taken before the read, so a write in between is noticed next time */
    fun read(files: List<File>, key: String, make: () -> T?): Read<T> {
        val stamps = files.map(::stampOf)
        last(key)?.takeIf { it.stamps == stamps }?.let { return it }
        reading?.invoke(files.first())
        return Read(stamps, make()).also { keep(key, it) }
    }

    @Synchronized
    private fun keep(key: String, read: Read<T>) {
        held.put(key, read)?.value?.let { weighs -= weight(it) }
        read.value?.let { weighs += weight(it) }
        val oldest = held.entries.iterator()
        while (weighs > capacity && held.size > 1) {
            val gone = oldest.next().value
            gone.value?.let { weighs -= weight(it) }
            oldest.remove()
        }
    }

    companion object {
        /** Told of every read that goes to the disk, for a test to see which do and on what thread */
        @Volatile internal var reading: ((File) -> Unit)? = null
    }
}
