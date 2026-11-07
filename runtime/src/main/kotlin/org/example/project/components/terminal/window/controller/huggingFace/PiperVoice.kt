package org.example.project.components.terminal.window.controller.huggingFace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * A Piper voice as the Hub has it, `<voice>.onnx` with its settings in `<voice>.onnx.json`, made into one the speech
 * package runs through sherpa-onnx: `tokens.txt` from the settings' phoneme map, the metadata keys sherpa-onnx reads
 * (model_type, sample_rate, n_speakers...) written into the ONNX file, and `espeak-ng-data/` copied from a voice
 * installed already when the repo brought none. sherpa-onnx's own vits-piper archives have all three; a raw export
 * from rhasspy/piper-voices has none.
 */
object PiperVoice {
    const val ESPEAK = "espeak-ng-data"
    const val TOKENS = "tokens.txt"
    private val json = Json { ignoreUnknownKeys = true }

    /** The settings beside [onnx], Piper's `<voice>.onnx.json`; null when there is none */
    fun settingsOf(onnx: File): JsonObject? =
        File(onnx.path + ".json").takeIf { it.isFile }?.let { f -> runCatching { json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() }

    /**
     * Makes the voice [onnx] in [folder] ready: what it lacks after that is returned, one line each, for the console.
     * [installed] is the speech models folder, where a voice with `espeak-ng-data/` lends it.
     */
    fun prepare(folder: File, onnx: File, installed: File): List<String> {
        val lacking = ArrayList<String>()
        val settings = settingsOf(onnx)
        if (settings == null) {
            lacking += "no ${onnx.name}.json beside the voice, so neither tokens.txt nor the model's metadata could be made; sherpa-onnx needs both"
        } else {
            val tokens = File(folder, TOKENS)
            if (!tokens.isFile) {
                val map = settings["phoneme_id_map"]?.jsonObject
                if (map == null) lacking += "the settings have no phoneme_id_map, so tokens.txt could not be made"
                else tokens.writeText(map.entries.joinToString("") { (symbol, ids) -> "$symbol ${ids.jsonArray.first().jsonPrimitive.content}\n" })
            }
            if (!hasMetadata(onnx)) addMetadata(onnx, metadataOf(settings))
        }
        val espeak = File(folder, ESPEAK)
        if (!espeak.isDirectory) {
            val donor = installed.listFiles().orEmpty().filter { it.isDirectory && it.canonicalPath != folder.canonicalPath }
                .map { File(it, ESPEAK) }.firstOrNull { File(it, "phontab").isFile }
            if (donor != null) donor.copyRecursively(espeak)
            else lacking += "no $ESPEAK: copy that folder of any sherpa-onnx voice (every vits-piper archive has one) into the voice's folder"
        }
        return lacking
    }

    /** The metadata sherpa-onnx reads of a vits model, with the values its own export of a Piper voice writes */
    fun metadataOf(settings: JsonObject): Map<String, String> = linkedMapOf(
        "model_type" to "vits",
        "comment" to "piper",
        "language" to (settings["language"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull ?: "unknown"),
        "voice" to (settings["espeak"]?.jsonObject?.get("voice")?.jsonPrimitive?.contentOrNull ?: "en-us"),
        "has_espeak" to if (settings["phoneme_type"]?.jsonPrimitive?.contentOrNull == "espeak") "1" else "0",
        "n_speakers" to (settings["num_speakers"]?.jsonPrimitive?.intOrNull ?: 1).toString(),
        "sample_rate" to (settings["audio"]?.jsonObject?.get("sample_rate")?.jsonPrimitive?.intOrNull ?: 22050).toString(),
    )

    /** Whether the file names a model_type already, as sherpa-onnx's own exports do */
    fun hasMetadata(onnx: File): Boolean = indexOf(onnx.readBytes(), "model_type".toByteArray()) >= 0

    /**
     * Appends [props] as `metadata_props`, field 14 of ONNX's ModelProto (entries of key 1 and value 2). A protobuf
     * message followed by more of its fields reads as the message with those fields, so the file is not rewritten.
     */
    fun addMetadata(onnx: File, props: Map<String, String>) {
        val out = ByteArrayOutputStream()
        for ((key, value) in props) {
            val entry = ByteArrayOutputStream()
            field(entry, 1, key.toByteArray())
            field(entry, 2, value.toByteArray())
            field(out, 14, entry.toByteArray())
        }
        FileOutputStream(onnx, true).use { it.write(out.toByteArray()) }
    }

    private fun field(out: ByteArrayOutputStream, number: Int, bytes: ByteArray) {
        varint(out, (number shl 3) or 2)
        varint(out, bytes.size)
        out.write(bytes)
    }

    private fun varint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private fun indexOf(bytes: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || bytes.size < needle.size) return -1
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
