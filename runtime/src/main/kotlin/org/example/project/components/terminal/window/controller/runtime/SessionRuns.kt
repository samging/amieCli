package org.example.project.components.terminal.window.controller.runtime

import java.io.File

/** One inference a session logged: the text, the scores it answered (label to score, best first) and how long it took */
data class LoggedRun(val text: String, val scores: List<Pair<String, Float>>, val millis: Int) {
    val top: String? get() = scores.firstOrNull()?.first
}

/**
 * Reads what a model has run on, from the files its sessions and server leave in `AMP/models/intents/<name>/`: the run
 * lines of `sessions.log` and the count of `incoming.jsonl`. SessionLog writes the top three scores of a run, so
 * with more than three labels the scores of a run are its best three.
 */
object SessionRuns {
    // The scores end with " +n" when the model has more labels than the three logged
    private val runLine = Regex("""\srun\s+"(.*)" -> (.*?)(?: \+\d+)? \((\d+) ms\)$""")

    fun logFile(dir: File) = File(dir, "sessions.log")
    fun incomingFile(dir: File) = File(dir, "incoming.jsonl")

    /** The last [limit] runs of the log, oldest first; empty when there is no log */
    fun read(dir: File, limit: Int = 5000): List<LoggedRun> {
        val file = logFile(dir)
        if (!file.isFile) return emptyList()
        val runs = runCatching { file.useLines { lines -> lines.mapNotNull(::parse).toList() } }.getOrDefault(emptyList())
        return if (runs.size > limit) runs.takeLast(limit) else runs
    }

    /** Whether the model was ever asked: a run in its sessions log, whatever the model keeps of its texts, or a request its server answered */
    fun asked(dir: File): Boolean {
        val log = logFile(dir)
        val ran = log.isFile && runCatching { log.useLines { lines -> lines.any { anyRun.containsMatchIn(it) } } }.getOrDefault(false)
        return ran || incomingFile(dir).let { it.isFile && it.length() > 0 }
    }

    /** A run line of any session, the text quoted or only its length given */
    private val anyRun = Regex("""\s#\d+ run\s""")

    /** How many requests the server answered for the model */
    fun incoming(dir: File): Int {
        val file = incomingFile(dir)
        if (!file.isFile) return 0
        return runCatching { file.useLines { it.count { line -> line.isNotBlank() } } }.getOrDefault(0)
    }

    fun parse(line: String): LoggedRun? {
        val m = runLine.find(line) ?: return null
        val scores = m.groupValues[2].split(", ").mapNotNull { token ->
            val i = token.lastIndexOf(':')
            if (i <= 0) null else token.substring(i + 1).trim().toFloatOrNull()?.let { token.substring(0, i) to it }
        }
        return LoggedRun(m.groupValues[1], scores, m.groupValues[3].toIntOrNull() ?: 0)
    }
}
