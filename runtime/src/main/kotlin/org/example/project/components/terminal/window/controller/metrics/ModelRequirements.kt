package org.example.project.components.terminal.window.controller.metrics

import org.example.project.components.terminal.window.controller.modules.ModuleManifest
import org.example.project.components.terminal.window.controller.runtime.ServingSettings
import org.example.project.components.terminal.window.controller.util.bytes
import ai.onnxruntime.OrtEnvironment
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket

/** One thing a model needs to run: what it is, how it stands, and whether it is met (null when it is only information) */
data class Requirement(val name: String, val detail: String, val ok: Boolean? = null)

/**
 * What an exported model needs before `initMod` or Listen can succeed, checked on disk: the encoder folder with
 * its ONNX model, tokenizer and config, the head's ONNX file, the shapes agreeing with the manifest, the runtime
 * this app has, and the port it would serve on. Every line is cheap to compute except the port, which is probed
 * by binding it for an instant when the model is not already listening.
 */
object ModelRequirements {
    /** What keeps the model from running at all: the lines of [ofModel] that are not met. `initMod` refuses on any */
    fun blockers(dir: File, manifest: ModuleManifest): List<Requirement> = ofModel(dir, manifest).filter { it.ok == false }

    /** What the model itself needs, whatever serves it: its encoder folder and files, its head, and the labels of its outputs */
    fun ofModel(dir: File, manifest: ModuleManifest): List<Requirement> = buildList {
        val encoder = File(manifest.encoder)
        add(Requirement("Encoder", if (encoder.isDirectory) "${encoder.name}  ·  ${encoder.absolutePath}" else "Not found: ${encoder.absolutePath}", encoder.isDirectory))
        if (encoder.isDirectory) {
            val onnx = File(encoder, "model.onnx")
            add(Requirement("Encoder model", if (onnx.isFile) "model.onnx  ·  ${bytes(onnx.length())}" else "model.onnx missing in ${encoder.name}", onnx.isFile))
            val tokenizer = File(encoder, "tokenizer.json")
            add(Requirement("Tokenizer", if (tokenizer.isFile) "tokenizer.json  ·  ${bytes(tokenizer.length())}" else "tokenizer.json missing in ${encoder.name}", tokenizer.isFile))
            val hidden = hiddenSize(encoder)
            add(
                when {
                    !File(encoder, "config.json").isFile -> Requirement("Encoder config", "config.json missing in ${encoder.name}", false)
                    // Information only: a DistilBERT config says dim, and its encoder runs all the same
                    hidden == null -> Requirement("Hidden size", "config.json has no hidden_size; the head expects ${manifest.inputDim}", null)
                    hidden.toLong() == manifest.inputDim -> Requirement("Hidden size", "$hidden, as the head expects", true)
                    else -> Requirement("Hidden size", "Encoder gives $hidden, the head expects ${manifest.inputDim}", false)
                }
            )
        }

        val head = File(dir, manifest.model)
        add(Requirement("Head", if (head.isFile) "${manifest.model}  ·  ${bytes(head.length())}  ·  ${manifest.format}" else "${manifest.model} missing in ${dir.name}/", head.isFile && manifest.format == "onnx"))
        add(Requirement("Shape", "${manifest.input} [${manifest.inputDim}]  →  ${manifest.pooling} pooling  →  ${manifest.output} [${manifest.outputDim}]"))
        add(Requirement("Labels", "${manifest.labels.size} for ${manifest.outputDim} outputs: ${manifest.labels.joinToString()}", manifest.labels.size.toLong() == manifest.outputDim))
    }

    /** Every line the Models page shows: the model's own, then the runtime, its files, and the port it would serve on */
    fun check(dir: File, manifest: ModuleManifest, serving: ServingSettings, listening: Boolean): List<Requirement> = ofModel(dir, manifest) + buildList {
        val encoder = File(manifest.encoder)
        add(Requirement("ONNX Runtime", runCatching { OrtEnvironment.getEnvironment().version }.getOrDefault("Not loaded"), true))
        add(Requirement("Java", "${System.getProperty("java.version")}  ·  ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"))
        val max = Runtime.getRuntime().maxMemory()
        add(Requirement("Memory", "Heap up to ${bytes(max)}; the encoder loads outside the heap, ${bytes(File(encoder, "model.onnx").length())} on disk"))

        val port = "${serving.host}:${serving.port}"
        add(
            when {
                listening -> Requirement("Port", "$port  ·  this model is listening", true)
                else -> if (portFree(serving.host, serving.port)) Requirement("Port", "$port  ·  free", true) else Requirement("Port", "$port  ·  taken by another process or model", false)
            }
        )

        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        add(Requirement("Files", files.joinToString("  ") { "${it.name} ${bytes(it.length())}" }.ifEmpty { "none" }))
    }

    private fun hiddenSize(encoder: File): Int? = runCatching {
        Regex(""""hidden_size"\s*:\s*(\d+)""").find(File(encoder, "config.json").readText())?.groupValues?.get(1)?.toInt()
    }.getOrNull()

    private fun portFree(host: String, port: Int): Boolean = runCatching {
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(host, port)) }
        true
    }.getOrDefault(false)
}
