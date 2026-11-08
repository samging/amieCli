package org.example.project.components.terminal.window.controller.modelLoaders.imported

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.components.terminal.integrations.amiePilot.Tensor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The encoder's input made from a file, as the model's `preprocessor_config.json` says: a Whisper-style log-mel
 * spectrogram of an audio file (`feature_extractor_type`), or the pixels of an image, resized and normalized
 * (`image_processor_type`). Every number comes from that file, so one implementation serves the models of a family.
 */
class Preprocessor(val config: JsonObject) {
    enum class Kind { AUDIO, IMAGE }

    val kind: Kind = when {
        config["feature_extractor_type"] != null || config["feature_size"] != null -> Kind.AUDIO
        config["image_processor_type"] != null || config["size"] != null -> Kind.IMAGE
        else -> throw IllegalArgumentException("preprocessor_config.json names neither a feature extractor nor an image processor")
    }

    /** The encoder's input name for this kind, as the Hub's exports name it */
    val inputName: String get() = if (kind == Kind.AUDIO) "input_features" else "pixel_values"

    private fun int(key: String, default: Int) = config[key]?.jsonPrimitive?.intOrNull ?: default
    private fun double(key: String, default: Double) = config[key]?.jsonPrimitive?.doubleOrNull ?: default
    private fun bool(key: String, default: Boolean) = config[key]?.jsonPrimitive?.booleanOrNull ?: default

    fun tensor(file: File): Tensor {
        require(file.isFile) { "no such file: ${file.path}" }
        return if (kind == Kind.AUDIO) audio(file) else image(file)
    }

    // ---- audio

    val sampleRate: Int get() = int("sampling_rate", 16000)
    val melBins: Int get() = int("feature_size", 80)
    val chunkSeconds: Int get() = int("chunk_length", 30)
    val nFft: Int get() = int("n_fft", 400)
    val hop: Int get() = int("hop_length", 160)

    /** Whisper's features of [file]: `[1, melBins, chunkSeconds * sampleRate / hop]`, the sound padded with silence or cut to the chunk */
    fun audio(file: File): Tensor {
        val samples = Audio.read(file, sampleRate)
        val wanted = chunkSeconds * sampleRate
        val padded = if (samples.size >= wanted) samples.copyOf(wanted) else samples.copyOf(wanted) // copyOf pads with zeros
        val features = LogMel(sampleRate, nFft, hop, melBins).of(padded)
        val frames = features.size / melBins
        return Tensor.of(features, 1, melBins.toLong(), frames.toLong())
    }

    // ---- image

    /** The pixels of [file]: `[1, 3, height, width]`, resized to `size`, rescaled and normalized as the config says */
    fun image(file: File): Tensor {
        val source = ImageIO.read(file) ?: throw IllegalArgumentException("${file.name} is no image ImageIO reads (png, jpeg, gif, bmp)")
        val size = config["size"]
        val (height, width) = when (size) {
            is JsonObject -> {
                val h = size["height"]?.jsonPrimitive?.intOrNull
                val w = size["width"]?.jsonPrimitive?.intOrNull
                val edge = size["shortest_edge"]?.jsonPrimitive?.intOrNull
                if (h != null && w != null) h to w else (edge ?: 224).let { it to it }
            }
            is JsonPrimitive -> size.intOrNull.let { (it ?: 224) to (it ?: 224) }
            is JsonArray -> size.jsonArray.let { (it[0].jsonPrimitive.intOrNull ?: 224) to (it.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 224) }
            else -> 224 to 224
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { out ->
            val g = out.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(source, 0, 0, width, height, null)
            g.dispose()
        }
        val rescale = if (bool("do_rescale", true)) double("rescale_factor", 1.0 / 255) else 1.0
        val normalize = bool("do_normalize", true)
        fun triple(key: String, default: Double) = (config[key] as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull ?: default }?.takeIf { it.size >= 3 } ?: List(3) { default }
        val mean = triple("image_mean", 0.5)
        val std = triple("image_std", 0.5)
        val values = FloatArray(3 * height * width)
        for (y in 0 until height) for (x in 0 until width) {
            val rgb = image.getRGB(x, y)
            val channels = intArrayOf((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
            for (c in 0 until 3) {
                var v = channels[c] * rescale
                if (normalize) v = (v - mean[c]) / std[c]
                values[c * height * width + y * width + x] = v.toFloat()
            }
        }
        return Tensor.of(values, 1, 3, height.toLong(), width.toLong())
    }

    companion object {
        /** The preprocessor of the nearest `preprocessor_config.json` in [homes]; null without one or when it names no kind */
        fun of(homes: List<File>): Preprocessor? = homes.asSequence().map { File(it, "preprocessor_config.json") }.firstOrNull { it.isFile }
            ?.let { runCatching { Preprocessor(kotlinx.serialization.json.Json.parseToJsonElement(it.readText()).jsonObject) }.getOrNull() }
    }
}

/** Sound files as mono samples in -1..1 at one rate: wav, aiff, au and whatever else javax.sound reads, resampled when the rate differs */
object Audio {
    fun read(file: File, rate: Int): FloatArray {
        val stream = try { AudioSystem.getAudioInputStream(file) } catch (e: Exception) { throw IllegalArgumentException("${file.name} is no sound file javax.sound reads (wav, aiff, au); an mp3 or m4a wants converting first: afconvert -f WAVE -d LEI16@16000 -c 1 in.m4a out.wav", e) }
        val source = stream.format
        val pcm = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.sampleRate, 16, source.channels, source.channels * 2, source.sampleRate, false)
        val converted = if (source.matches(pcm)) stream else AudioSystem.getAudioInputStream(pcm, stream)
        val bytes = converted.use { it.readBytes() }
        val channels = pcm.channels
        val frames = bytes.size / (2 * channels)
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val at = (i * channels + c) * 2
                val sample = (bytes[at].toInt() and 0xff) or (bytes[at + 1].toInt() shl 8)
                sum += sample.toShort() / 32768f
            }
            mono[i] = sum / channels
        }
        val from = source.sampleRate.roundToInt()
        return if (from == rate) mono else resample(mono, from, rate)
    }

    /** Linear resampling, enough for speech going into a model */
    fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        val count = (samples.size.toLong() * to / from).toInt()
        val out = FloatArray(count)
        val step = from.toDouble() / to
        for (i in 0 until count) {
            val pos = i * step
            val left = pos.toInt().coerceAtMost(samples.size - 1)
            val right = (left + 1).coerceAtMost(samples.size - 1)
            val frac = (pos - left).toFloat()
            out[i] = samples[left] * (1 - frac) + samples[right] * frac
        }
        return out
    }
}

/**
 * Whisper's log-mel spectrogram as the Hugging Face feature extractor computes it: a periodic Hann window, a centered
 * short-time Fourier transform (reflect padding, the last frame dropped), a Slaney mel filter bank of [melBins]
 * filters from 0 Hz to half the rate, log10 floored at 1e-10, clamped to 8 below the loudest, then (x + 4) / 4.
 */
class LogMel(private val rate: Int, private val nFft: Int, private val hop: Int, private val melBins: Int) {
    private val bins = nFft / 2 + 1
    private val window = DoubleArray(nFft) { 0.5 - 0.5 * cos(2 * PI * it / nFft) }
    private val cosTable = Array(bins) { k -> DoubleArray(nFft) { n -> cos(2 * PI * k * n / nFft) } }
    private val sinTable = Array(bins) { k -> DoubleArray(nFft) { n -> sin(2 * PI * k * n / nFft) } }
    private val filters: Array<DoubleArray> = melFilters()

    /** The features of [samples], mel-major: `melBins` rows of `samples.size / hop` frames, as one array */
    fun of(samples: FloatArray): FloatArray {
        val frames = samples.size / hop // centered: 1 + size / hop frames, the last dropped
        val half = nFft / 2
        val power = DoubleArray(bins)
        val mel = Array(melBins) { DoubleArray(frames) }
        val frame = DoubleArray(nFft)
        for (t in 0 until frames) {
            val start = t * hop - half
            for (n in 0 until nFft) {
                var i = start + n
                if (i < 0) i = -i // reflect padding
                if (i >= samples.size) i = 2 * samples.size - i - 2
                frame[n] = samples[i.coerceIn(0, samples.size - 1)] * window[n]
            }
            for (k in 0 until bins) {
                var re = 0.0; var im = 0.0
                val c = cosTable[k]; val s = sinTable[k]
                for (n in 0 until nFft) { re += frame[n] * c[n]; im -= frame[n] * s[n] }
                power[k] = re * re + im * im
            }
            for (m in 0 until melBins) {
                var sum = 0.0
                val f = filters[m]
                for (k in 0 until bins) sum += f[k] * power[k]
                mel[m][t] = log10(max(sum, 1e-10))
            }
        }
        var loudest = Double.NEGATIVE_INFINITY
        for (row in mel) for (v in row) if (v > loudest) loudest = v
        val floor = loudest - 8.0
        val out = FloatArray(melBins * frames)
        for (m in 0 until melBins) for (t in 0 until frames) out[m * frames + t] = ((max(mel[m][t], floor) + 4.0) / 4.0).toFloat()
        return out
    }

    private fun hzToMel(hz: Double): Double = if (hz < 1000.0) hz * 3.0 / 200.0 else 15.0 + 27.0 * ln(hz / 1000.0) / ln(6.4)
    private fun melToHz(mel: Double): Double = if (mel < 15.0) mel * 200.0 / 3.0 else 1000.0 * Math.exp(ln(6.4) * (mel - 15.0) / 27.0)

    /** [melBins] triangular filters over the [bins] frequency bins, Slaney-normalized, as HF's mel_filter_bank(norm = "slaney", mel_scale = "slaney") */
    private fun melFilters(): Array<DoubleArray> {
        val maxHz = rate / 2.0
        val melMin = hzToMel(0.0); val melMax = hzToMel(maxHz)
        val points = DoubleArray(melBins + 2) { melToHz(melMin + (melMax - melMin) * it / (melBins + 1)) }
        val freqs = DoubleArray(bins) { maxHz * it / (bins - 1) }
        return Array(melBins) { m ->
            val lo = points[m]; val mid = points[m + 1]; val hi = points[m + 2]
            val norm = 2.0 / (hi - lo)
            DoubleArray(bins) { k ->
                val f = freqs[k]
                val up = (f - lo) / (mid - lo)
                val down = (hi - f) / (hi - mid)
                max(0.0, min(up, down)) * norm
            }
        }
    }
}
