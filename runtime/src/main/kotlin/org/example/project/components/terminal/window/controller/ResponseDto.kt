package org.example.project.components.terminal.window.controller

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.slf4j.LoggerFactory
import java.time.Instant

/*
 * A line of the console and its level, as every part of the engine logs them and the app's console shows them: here,
 * with no window, so the engine and the interpreter have them without the console
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
enum class LogLevel { INFO, ERROR, DATA, INTERNAL }

@Serializable
data class ResponseDto(
    @Serializable(with = InstantSerializer::class)
    val time: Instant? = null,
    val status: Int? = null,
    val message: String? = null,
    val color: Long? = null,
    val level: LogLevel = LogLevel.INFO,
    // Who logged it, e.g. "sandbox", "sandbox/main.kts", "terminal/<deviceId>"
    val source: String? = null,
    // The place in main.kts a line of the sandbox is about (an error's), which the message begins with; drawn as a link
    val line: Int? = null,
    val column: Int? = null,
)

val logger = LoggerFactory.getLogger("TerminalWindow")
