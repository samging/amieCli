package org.example.project.components.terminal.window.controller.modelLoaders

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where ONNX Runtime's native library is loaded from. The Java library unpacks it from its jar into the temp folder
 * on every start (13 MB on an arm64 Linux, 26 MB on a Mac, with the JNI library beside it) and deletes it at exit,
 * so a machine on an SD card writes and reads those megabytes before its first session every time. Unpacked once
 * into `AMP/cache/ort/` instead, named by the platform and the library's size so a new ORT version gets a new copy,
 * and ORT is told where with its `onnxruntime.native.path` property before it loads. Anything that goes wrong here
 * (a folder that can't be written, a platform the jar has no library for) leaves ORT to unpack as it always did.
 * `-Damie.ort.cache=false` leaves it to ORT too, to compare.
 */
internal object OrtNativeCache {
    private const val PROPERTY = "onnxruntime.native.path"

    /** Sets ORT's native path to the cached copy, unpacking it first when it isn't there; does nothing when it can't */
    fun point(cacheRoot: File = File(getDirectory().toString(), "cache/ort")) {
        if (System.getProperty("amie.ort.cache") == "false" || System.getProperty(PROPERTY) != null) return
        val dir = prepare(cacheRoot) ?: return
        System.setProperty(PROPERTY, dir.absolutePath)
    }

    /** The folder holding both libraries for this platform, copied from the jar if need be; null when that failed */
    internal fun prepare(cacheRoot: File, platform: String = platform(), loader: ClassLoader = OrtNativeCache::class.java.classLoader): File? = runCatching {
        val names = libraryNames(platform)
        val resources = names.associateWith { loader.getResource("ai/onnxruntime/native/$platform/$it") ?: return null }
        val sizes = resources.mapValues { (_, url) -> url.openConnection().contentLengthLong }
        if (sizes.values.any { it <= 0 }) return null
        val dir = File(cacheRoot, "$platform-${sizes.values.first()}")
        dir.mkdirs()
        names.forEach { name ->
            val target = File(dir, name)
            if (target.isFile && target.length() == sizes[name]) return@forEach
            val partial = File(dir, "$name.part")
            resources[name]!!.openStream().use { Files.copy(it, partial.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            logInternal("ort: native library ${target.name} unpacked once to ${dir.path}")
        }
        if (names.all { File(dir, it).isFile }) dir else null
    }.onFailure { logInternal("ort: couldn't keep the native library in $cacheRoot (${it.message}); ONNX Runtime unpacks it itself", LogLevel.INTERNAL) }.getOrNull()

    /** The jar's folder for this machine, as ORT names them: osx-aarch64, linux-aarch64, linux-x64, win-x64 */
    internal fun platform(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): String {
        val o = os.lowercase()
        val system = when {
            o.contains("mac") || o.contains("darwin") -> "osx"
            o.contains("win") -> "win"
            else -> "linux"
        }
        val cpu = when (arch.lowercase()) {
            "aarch64", "arm64" -> "aarch64"
            else -> "x64"
        }
        return "$system-$cpu"
    }

    /** The two files ORT loads, the runtime and its JNI binding, in the names the platform gives shared libraries */
    internal fun libraryNames(platform: String): List<String> = when {
        platform.startsWith("win") -> listOf("onnxruntime.dll", "onnxruntime4j_jni.dll")
        platform.startsWith("osx") -> listOf("libonnxruntime.dylib", "libonnxruntime4j_jni.dylib")
        else -> listOf("libonnxruntime.so", "libonnxruntime4j_jni.so")
    }
}
