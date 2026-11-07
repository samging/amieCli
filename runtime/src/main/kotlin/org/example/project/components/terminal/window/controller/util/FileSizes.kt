package org.example.project.components.terminal.window.controller.util

import java.io.File

/**
 * What a folder weighs on disk, for the Sessions tab and the memory footer: an encoder is a folder (model.onnx, the
 * tokenizer and its configs), and its size stands for what ONNX Runtime holds of it, which it doesn't say. The sum
 * is kept behind the stamps of the folder and its model file, so asking again costs two looks at those and no walk.
 * A single file needs no such cache: its length comes with the same look that says whether it changed.
 */
internal object FileSizes {
    private val folders = StampedReads<Long>(256)

    /** The regular files right in [dir] together, hidden ones left out; 0 when there is no such folder */
    fun folder(dir: File): Long = folders.read(listOf(dir, File(dir, "model.onnx")), dir.path) {
        dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sumOf { it.length() } ?: 0L
    }.value ?: 0L
}
