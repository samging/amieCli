package org.example.project.components.terminal.window.controller.modelLoaders.imported

import org.example.project.components.terminal.window.controller.console.logInternal
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/** A machine as the engines' natives are built for: `mac`, `linux` or `windows`, and `aarch64` or `x86_64` */
data class Platform(val os: String, val arch: String) {
    override fun toString(): String = "${shownOs()} ${if (arch == "x86_64") "x86-64" else if (arch == "aarch64") "arm64" else arch}"

    private fun shownOs() = when (os) { "mac" -> "macOS"; "linux" -> "Linux"; "windows" -> "Windows"; else -> os }

    companion object {
        fun of(osName: String, osArch: String): Platform {
            val os = osName.lowercase().let { when { it.contains("mac") || it.contains("darwin") -> "mac"; it.contains("win") -> "windows"; it.contains("linux") -> "linux"; else -> it } }
            val arch = osArch.lowercase().let { when (it) { "aarch64", "arm64" -> "aarch64"; "amd64", "x86_64", "x64" -> "x86_64"; else -> it } }
            return Platform(os, arch)
        }

        val current: Platform get() = of(System.getProperty("os.name"), System.getProperty("os.arch"))
    }
}

/**
 * Which engine runs which format where, and what its first use downloads. ONNX runs on the ONNX Runtime the app ships.
 * PyTorch and TensorFlow are DJL 0.31 engines: their jars are small, their natives (PyTorch 2.5.1, TensorFlow 2.16.1,
 * CPU builds) are downloaded from publish.djl.ai into DJL's cache (`~/.djl.ai`, or `DJL_CACHE_DIR`) the first time a
 * model of theirs is opened on a machine, and read from there after. DJL has no TFLite engine since 0.28, and its last
 * TFLite natives were for Linux x86-64 and Intel Macs only, so a TFLite model is refused with the way to ONNX.
 *
 * | Engine | macOS arm64 | macOS x86-64 | Linux arm64 | Linux x86-64 | Windows x86-64 |
 * |---|---|---|---|---|---|
 * | ONNX Runtime | yes | yes | yes | yes | yes |
 * | PyTorch 2.5.1 | yes | no | yes (precxx11) | yes | yes |
 * | TensorFlow 2.16.1 | yes | yes | yes | yes | yes |
 * | TFLite | no | no | no | no | no |
 *
 * The TensorFlow engine is in the lightweight build only: its jar brings TensorFlow Java's own `org.tensorflow` classes,
 * which the full build already has from TensorFlow 1.15 for KotlinDL, and the two can't share a class path.
 */
object ModelEngines {
    private const val PYTORCH = "2.5.1"
    private const val TENSORFLOW = "2.16.1"

    private val pytorchOn = setOf(Platform("mac", "aarch64"), Platform("linux", "aarch64"), Platform("linux", "x86_64"), Platform("windows", "x86_64"))
    private val tensorflowOn = setOf(Platform("mac", "aarch64"), Platform("mac", "x86_64"), Platform("linux", "aarch64"), Platform("linux", "x86_64"), Platform("windows", "x86_64"))

    /** The DJL engine name of a format, null for ONNX, which runs on the app's own runtime */
    fun djlEngine(format: ModelFormat): String? = when (format) {
        ModelFormat.TORCHSCRIPT -> "PyTorch"
        ModelFormat.TENSORFLOW -> "TensorFlow"
        ModelFormat.ONNX, ModelFormat.TFLITE -> null
    }

    /** Whether this build carries the engine's jar: the class DJL finds it by is there */
    fun inBuild(format: ModelFormat): Boolean = when (format) {
        ModelFormat.ONNX -> true
        ModelFormat.TORCHSCRIPT -> present("ai.djl.pytorch.engine.PtEngineProvider")
        ModelFormat.TENSORFLOW -> present("ai.djl.tensorflow.engine.TfEngineProvider")
        ModelFormat.TFLITE -> false
    }

    private fun present(className: String): Boolean = runCatching { Class.forName(className, false, ModelEngines::class.java.classLoader) }.isSuccess

    /**
     * Why a model of [format] can't run on [platform] in a build with [inBuild] engines, with what would work instead;
     * null when it can. Nothing is loaded or downloaded to tell
     */
    fun missing(format: ModelFormat, platform: Platform = Platform.current, inBuild: (ModelFormat) -> Boolean = ::inBuild): String? = when (format) {
        ModelFormat.ONNX -> null
        ModelFormat.TFLITE -> "no TFLite engine: DJL, which runs PyTorch and TensorFlow here, dropped TFLite after 0.27, whose natives were for Linux x86-64 and Intel Macs only. " +
            "Convert it to ONNX (python -m tf2onnx.convert --tflite model.tflite --output model.onnx) and open that"
        ModelFormat.TORCHSCRIPT -> when {
            !inBuild(format) -> "this build has no PyTorch engine (ai.djl.pytorch:pytorch-engine); export the model to ONNX"
            platform !in pytorchOn -> "PyTorch $PYTORCH has no natives for $platform (DJL builds them for macOS arm64, Linux arm64 and x86-64, Windows x86-64); export the model to ONNX (torch.onnx.export) and open that"
            else -> null
        }
        ModelFormat.TENSORFLOW -> when {
            !inBuild(format) -> "this build has no TensorFlow 2 engine: the full build carries TensorFlow 1.15 for KotlinDL, whose classes clash with it. " +
                "The lightweight build (-Pamie.lightweight=true, the default on an Apple Silicon Mac and arm64 Linux) opens SavedModels; or convert to ONNX (python -m tf2onnx.convert --saved-model <folder> --output model.onnx)"
            platform !in tensorflowOn -> "TensorFlow $TENSORFLOW has no natives for $platform; convert the model to ONNX (python -m tf2onnx.convert --saved-model <folder> --output model.onnx)"
            else -> null
        }
    }

    /** What the first use downloads on [platform], about: "PyTorch 2.5.1 (CPU), about 40 MB" */
    fun download(format: ModelFormat, platform: Platform = Platform.current): String? {
        val mb = when (format) {
            ModelFormat.TORCHSCRIPT -> when (platform) {
                Platform("mac", "aarch64") -> 40
                Platform("linux", "aarch64") -> 65
                Platform("linux", "x86_64") -> 110
                else -> 75
            }
            ModelFormat.TENSORFLOW -> when (platform.os) { "windows" -> 80; "mac" -> 130; else -> 150 }
            else -> return null
        }
        val version = if (format == ModelFormat.TORCHSCRIPT) PYTORCH else TENSORFLOW
        return "${format.engine} $version (CPU), about $mb MB compressed"
    }

    /** DJL's cache of engine natives: ENGINE_CACHE_DIR, else DJL_CACHE_DIR, else ~/.djl.ai, as DJL reads them */
    fun cacheDir(env: (String) -> String? = { System.getenv(it) ?: System.getProperty(it) }): File =
        env("ENGINE_CACHE_DIR")?.let(::File) ?: File(env("DJL_CACHE_DIR") ?: File(System.getProperty("user.home"), ".djl.ai").path)

    /** Whether the natives of [format]'s engine are in the cache already, or a library path points at them */
    fun cached(format: ModelFormat, cache: File = cacheDir(), env: (String) -> String? = { System.getenv(it) ?: System.getProperty(it) }): Boolean {
        val (folder, version, libraryPath) = when (format) {
            ModelFormat.TORCHSCRIPT -> Triple("pytorch", PYTORCH, "PYTORCH_LIBRARY_PATH")
            ModelFormat.TENSORFLOW -> Triple("tensorflow", TENSORFLOW, "TENSORFLOW_LIBRARY_PATH")
            else -> return true
        }
        if (env(libraryPath) != null) return true
        return File(cache, folder).listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("$version-") && it.list().orEmpty().isNotEmpty() }
    }

    private val told = ConcurrentHashMap.newKeySet<ModelFormat>()

    /**
     * Readies DJL for [format]'s engine before it loads: PyTorch takes the CPU build unless PYTORCH_FLAVOR says otherwise
     * (a machine with CUDA would fetch a GPU build of gigabytes), the precxx11 one on arm64 Linux, the only one there.
     * The first time on a machine whose cache lacks the natives the console says once what comes and how big
     */
    fun prepare(format: ModelFormat, platform: Platform = Platform.current) {
        if (format == ModelFormat.TORCHSCRIPT && System.getenv("PYTORCH_FLAVOR") == null && System.getProperty("PYTORCH_FLAVOR") == null) {
            System.setProperty("PYTORCH_FLAVOR", if (platform == Platform("linux", "aarch64")) "cpu-precxx11" else "cpu")
        }
        if (!cached(format) && told.add(format)) {
            logInternal("${format.engine}: first use on this machine, downloading its native libraries, ${download(format, platform)}, from publish.djl.ai into ${cacheDir()}; this happens once")
        }
    }

    /**
     * What a failed engine load says: a download that couldn't reach the network names the natives it wanted and how
     * to get them; anything else keeps DJL's own words
     */
    fun explain(format: ModelFormat, error: Throwable, cached: Boolean = cached(format)): String {
        val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.toList()
        val offline = chain.any {
            it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException ||
                it.message?.contains("Offline model is enabled", ignoreCase = true) == true
        }
        val words = chain.mapNotNull { it.message?.takeIf(String::isNotBlank) }.distinct().joinToString(": ")
        return when {
            offline && !cached -> "${format.engine}'s native libraries (${download(format)}) are not on this machine yet and could not be downloaded from publish.djl.ai: " +
                "${chain.last().let { it.message ?: it.javaClass.simpleName }}. Open the model once with a network, or point ${if (format == ModelFormat.TORCHSCRIPT) "PYTORCH_LIBRARY_PATH" else "TENSORFLOW_LIBRARY_PATH"} at a copy"
            words.contains("No ", ignoreCase = false) && words.contains("matches your operating system") -> "${format.engine} has no natives for ${Platform.current}: $words"
            else -> "${format.engine} could not open it: ${words.ifEmpty { error.javaClass.simpleName }}"
        }
    }
}
