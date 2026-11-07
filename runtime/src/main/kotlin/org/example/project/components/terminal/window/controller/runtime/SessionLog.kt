package org.example.project.components.terminal.window.controller.runtime

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How a text someone asked shows in a log line: on one line, quoted and cut at [max] characters; only its length when
 * it is not kept, because [keep] is off (a model that answers other people) or [TextsWithheld] is on for this thread
 */
fun textForLog(text: String, keep: Boolean, max: Int = 60): String {
    if (!keep || TextsWithheld.now) return "(${text.length} characters, not kept)"
    val one = text.replace('\n', ' ').trim()
    return "\"" + (if (one.length > max) one.take(max - 3) + "..." else one) + "\""
}

/**
 * Texts kept out of every log line written on this thread while [during] runs: a group with a model that keeps no
 * texts asks its other models inside it, so the text it was given stays out of their session logs and console lines
 * too, though they keep the texts they are asked themselves
 */
object TextsWithheld {
    private val on = ThreadLocal.withInitial { false }

    val now: Boolean get() = on.get()

    fun <T> during(block: () -> T): T {
        val was = on.get()
        on.set(true)
        try {
            return block()
        } finally {
            on.set(was)
        }
    }
}

/** One line of a session's log: when, what kind of event (open, run, error, close) and the text */
data class SessionLogEntry(val at: Instant, val kind: String, val text: String) {
    val time: String get() = TIME.format(at)

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}

/**
 * The log of one session: what it was asked and what it answered, kept in memory for the Models page and
 * appended to a file next to its owner (`AMP/models/intents/<name>/sessions.log` for an intent model,
 * `AMP/pluginDir/<jar>.sessions.log` for a package) so the history of a terminated session is not lost.
 */
class SessionLog(
    private val owner: String,
    private val id: Int,
    private val file: File?,
    private val capacity: Int = 300,
    /** Off for models that answer other people: a run is logged with the length of its text instead of the text */
    private val keepTexts: Boolean = true,
) {
    private val entries = ArrayDeque<SessionLogEntry>()

    /** The entries still in memory, oldest first */
    val lines: List<SessionLogEntry> @Synchronized get() = entries.toList()

    val size: Int @Synchronized get() = entries.size

    @Synchronized
    fun add(kind: String, text: String) {
        val entry = SessionLogEntry(Instant.now(), kind, text)
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
        file?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                f.appendText("${entry.at} $owner #$id ${kind.padEnd(5)} $text\n")
            }.onFailure { logInternal("session log $owner #$id: couldn't write ${f.name}: ${it.message}", LogLevel.ERROR) }
        }
    }

    /** A run: the text in, the first answers out and how long it took */
    fun run(input: String, output: List<String>, millis: Long) =
        add("run", "${quote(input)} -> ${output.take(3).joinToString(", ").ifEmpty { "(nothing)" }}${if (output.size > 3) " +${output.size - 3}" else ""} (${millis} ms)")

    fun error(input: String, error: Throwable) = add("error", "${quote(input)} -> ${error::class.simpleName}: ${error.message}")

    private fun quote(text: String): String = textForLog(text, keepTexts, max = 80)
}
