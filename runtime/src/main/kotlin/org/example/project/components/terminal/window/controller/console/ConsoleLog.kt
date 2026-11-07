package org.example.project.components.terminal.window.controller.console

import org.example.project.backend.ErrorReporter
import org.example.project.getDirectory
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.ResponseDto
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/*
 * Where every line goes: the sandbox's console, AMP/logs.txt and, for an error, the signed-in user's log on the backend.
 * Nothing here touches Compose, so what logs through it runs without the UI: a bundle's runner sets [headlessConsole]
 * and the console, with its Compose state in TerminalWindow.kt, is never loaded.
 */

/** Takes every line instead of the console, in a run with no UI; null in the app */
@Volatile var headlessConsole: ((ResponseDto) -> Unit)? = null

/** The app's console, set by the app at its start; null with no window, when [headlessConsole] takes the lines */
@Volatile var consoleView: ((ResponseDto) -> Unit)? = null

private val logFileLogger = LoggerFactory.getLogger("TerminalWindow")

// Single log file shared by every terminal; entries are told apart by their source tag
val appLogFile: File get() = File(getDirectory().toString(), "logs.txt")

// One writer thread keeps appends in order and off the UI thread
private val logWriter = Executors.newSingleThreadExecutor { Thread(it, "amie-log-writer").apply { isDaemon = true } }

// logs.txt moves to logs.1.txt (replacing the one before) once it passes this, so a long-running app keeps two files
private const val LOG_FILE_BYTES = 20L * 1024 * 1024

fun logToConsole(dto: ResponseDto) {
    val headless = headlessConsole
    (headless ?: consoleView)?.invoke(dto)
    ErrorTerminal.record(dto)
    val line = dto.toLogLine()
    logWriter.execute {
        try {
            val file = appLogFile
            if (file.length() > LOG_FILE_BYTES) file.renameTo(File(file.parentFile, "logs.1.txt").apply { delete() })
            file.appendText("$line\n")
        } catch (e: Exception) {
            logFileLogger.error("Error writing to log file: ${e.message}")
        }
    }
    // Errors also go to the signed-in user's log on the backend; report() only queues, the request runs elsewhere
    if (dto.level == LogLevel.ERROR) ErrorReporter.report(dto.source ?: "app", dto.message ?: "")
}

/** Waits until what was logged so far is in the log file, for a process about to end */
fun flushLogFile() {
    runCatching { logWriter.submit {}.get(5, TimeUnit.SECONDS) }
}

// Runtime/model internals; shown in every terminal so they also reach the logging terminal
const val INTERNAL_SOURCE = "internal"
fun logInternal(message: String, level: LogLevel = LogLevel.INTERNAL) = logToConsole(ResponseDto(time = Instant.now(), message = message, level = level, source = INTERNAL_SOURCE))

fun logInfo(message: String, source: String) = logToConsole(ResponseDto(time = Instant.now(), message = message, level = LogLevel.INFO, source = source))
fun logError(message: String, source: String) = logToConsole(ResponseDto(time = Instant.now(), message = message, level = LogLevel.ERROR, source = source))
fun logData(message: String, source: String) = logToConsole(ResponseDto(time = Instant.now(), message = message, level = LogLevel.DATA, source = source))

internal fun ResponseDto.text(): String = message ?: "$time $status"

// Persisted as "> [LEVEL] [source] <instant> <message>"; multi-line messages are escaped to keep one entry per line
private fun ResponseDto.toLogLine(): String {
    val escaped = text().replace("\\", "\\\\").replace("\n", "\\n")
    return listOfNotNull("> [${level.name}]", source?.let { "[$it]" }, time?.toString(), escaped).joinToString(" ")
}
