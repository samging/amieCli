package org.example.project.components.terminal.window.controller.script

import kotlinx.coroutines.flow.update
import org.example.project.backend.ErrorReporter
import org.example.project.components.terminal.window.controller.sourceFile.DataSource
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.text
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What a command did. A non-zero [exit] is not an exception, so a script can decide; -1 is a command that never
 * started, with the reason in [err]. [out] and [err] are what it printed without the line breaks at the end, as the
 * shell's own $(...) gives it, and at most about [ShellCommands.KEPT_BYTES] of it: past that the end is kept and the
 * first line says how much went. As text it is [out], so "${shell("ls")}" is the listing.
 */
data class ShellResult(
    val command: String,
    val exit: Int,
    val out: String,
    val err: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
) {
    val ok: Boolean get() = exit == 0 && !timedOut && !cancelled
    val lines: List<String> get() = if (out.isEmpty()) emptyList() else out.lines()
    /** What went wrong, for a person or a model to read; empty when nothing did */
    val failure: String get() = when {
        ok -> ""
        timedOut -> "$command timed out"
        else -> "$command exited with $exit"
    }.let { head -> listOf(head, err.ifBlank { out }.takeLast(FAILURE_CHARS)).filter { it.isNotBlank() }.joinToString("\n") }

    /**
     * Hands [failure] to [patch] when the command failed, so a script can send it anywhere: to a voice, a model, a
     * template. A command stopped with its script is no failure to patch. Gives this result back, to go on from it.
     */
    fun patchBy(patch: (String) -> Any?): ShellResult {
        if (!ok && !cancelled) patch(failure)
        return this
    }

    override fun toString(): String = out

    private companion object {
        const val FAILURE_CHARS = 2000
    }
}

/**
 * Runs commands and script files on this computer for shell() and shellScript(). The command goes to the console
 * first, so the person always sees what ran, then its output as it arrives, through the logger it was called on so a
 * background template's lines get their prefix: the command and stdout at the internal level, stderr at the error level. None of it is
 * sent to the backend's error log ([ErrorReporter.keepLocal]): what a command prints is the person's, not the app's.
 *
 * The script's own thread does all of it. It takes what is ready from both pipes in turn and never waits on either,
 * so neither can fill up and block the command, and a child left in the background, which keeps a pipe open after
 * the command ended, can't hold the script.
 *
 * Only script code reaches this: nothing here is behind an HTTP route, see docs/sandbox-scripting.md.
 */
object ShellCommands {
    // How much of each stream .out and .err keep, and how much of a command the console shows
    const val KEPT_BYTES = 1024 * 1024
    const val CONSOLE_LINES = 1000
    const val CONSOLE_LINE_BYTES = 4096

    private const val READ_BYTES = 64 * 1024
    private const val TICK_MS = 15L
    // How long a stopped command has to end by itself, how long the kill may take to show, and how long what it
    // left in the pipes may take to read
    private const val GRACE_MS = 1000L
    private const val KILL_MS = 1000L
    private const val DRAIN_MS = 1000L

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    // cmd speaks the machine's code page; terminals on macOS and Linux speak UTF-8 whatever the app was started with
    private val charset: Charset =
        if (windows) runCatching { Charset.forName(System.getProperty("native.encoding")) }.getOrDefault(Charsets.UTF_8) else Charsets.UTF_8

    private val running: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    init {
        // Commands still running when the app exits go with it
        runCatching { Runtime.getRuntime().addShutdownHook(Thread({ stopAll() }, "amie-shell-exit")) }
    }

    fun shell(command: String, dir: String?, timeoutSeconds: Int, env: Map<String, String>, log: (String, LogLevel) -> Unit): ShellResult {
        val run = Run("shell", command, log)
        run.announce(dir)
        val folder = folderOf(dir) ?: return run.refused("no directory $dir")
        return run.start(shellCommand(command), folder, timeoutSeconds, env)
    }

    fun script(path: String, args: List<String>, dir: String?, timeoutSeconds: Int, env: Map<String, String>, log: (String, LogLevel) -> Unit): ShellResult {
        val run = Run("shellScript", (listOf(path) + args.map(::quoted)).joinToString(" "), log)
        run.announce(dir)
        val folder = folderOf(dir) ?: return run.refused("no directory $dir")
        // A relative path that isn't there is looked for as a data file is, under AMP/, so an export carries it along
        val file = locate(path, folder).takeIf { it.isFile } ?: DataSource.locate(path) ?: locate(path, folder)
        if (!file.isFile) return run.refused("no file ${file.path}")
        return run.start(scriptCommand(file, args), folder, timeoutSeconds, env)
    }

    /** Ends every command still running and what it started; the app's exit does this */
    fun stopAll() {
        running.toList().map { process -> thread(name = "amie-shell-stop") { destroyTree(process) } }.forEach { it.join() }
    }

    /** A command through the person's shell; a login shell, so their PATH and tools work */
    internal fun shellCommand(command: String, shell: String? = System.getenv("SHELL"), onWindows: Boolean = windows): List<String> = when {
        onWindows -> listOf("cmd", "/c", command)
        shell.isNullOrBlank() -> listOf("/bin/sh", "-c", command)
        else -> listOf(shell, "-lc", command)
    }

    /** A script file with its arguments, each one its own: run directly when it is executable, through the shell otherwise */
    internal fun scriptCommand(file: File, args: List<String>, shell: String? = System.getenv("SHELL"), onWindows: Boolean = windows): List<String> = when {
        file.canExecute() -> listOf(file.path) + args
        onWindows -> listOf("cmd", "/c", file.path) + args
        shell.isNullOrBlank() -> listOf("/bin/sh", file.path) + args
        else -> listOf(shell, "-l", file.path) + args
    }

    private val home: File get() = File(System.getProperty("user.home"))

    /** [path] with a leading ~/ expanded; a relative path starts from [from] */
    internal fun locate(path: String, from: File = home): File {
        val file = if (path == "~" || path.startsWith("~/")) File(home, path.drop(1).trimStart('/')) else File(path)
        return if (file.isAbsolute) file else File(from, path)
    }

    // A command runs where a new terminal would, not where the app happens to have been started
    private fun folderOf(dir: String?): File? = (if (dir == null) home else locate(dir)).takeIf { it.isDirectory }

    // Only for showing it: the arguments themselves never go through a shell's parsing
    private fun quoted(argument: String) =
        if (argument.isNotEmpty() && argument.all { it.isLetterOrDigit() || it in "-_./=:,@%+" }) argument
        else "'" + argument.replace("'", "'\\''") + "'"

    /**
     * Ends the process and everything it started. The tree is listed before anything goes: a child whose parent has
     * gone belongs to init and can't be found from here any more. It goes through the handles because Process.destroy
     * closes the pipes, and [pump] goes on reading them meanwhile. What ignores the request is killed after a moment.
     */
    private fun destroyTree(process: Process, pump: () -> Boolean = { false }) {
        val root = process.toHandle()
        val tree = LinkedHashSet<ProcessHandle>()
        var interrupted = false
        fun list() = (tree + root).forEach { parent -> parent.descendants().forEach { tree.add(it) } }
        fun settle(ms: Long) {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
            while (System.nanoTime() < until && (tree + root).any { it.isAlive }) {
                if (!pump()) try { Thread.sleep(TICK_MS) } catch (e: InterruptedException) { interrupted = true }
            }
        }
        list()
        root.destroy()
        tree.forEach { it.destroy() }
        settle(GRACE_MS)
        list()
        (tree + root).forEach { it.destroyForcibly() }
        settle(KILL_MS)
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** The end of a stream: the last [KEPT_BYTES], held in at most twice that */
    private class Tail {
        private var bytes = ByteArray(8192)
        private var size = 0
        private var total = 0L

        fun add(chunk: ByteArray, count: Int) {
            total += count
            if (size + count > KEPT_BYTES * 2) {
                System.arraycopy(bytes, size - KEPT_BYTES, bytes, 0, KEPT_BYTES)
                size = KEPT_BYTES
            }
            if (size + count > bytes.size) bytes = bytes.copyOf(minOf(maxOf(bytes.size * 2, size + count), KEPT_BYTES * 2))
            System.arraycopy(chunk, 0, bytes, size, count)
            size += count
        }

        fun text(): String {
            val kept = minOf(size, KEPT_BYTES)
            val text = String(bytes, size - kept, kept, charset)
            if (total == kept.toLong()) return text.trimEnd('\n', '\r')
            // What is left of the first line is only the end of one
            return "The first ${total - kept} bytes were cut, the end is kept\n" + text.substringAfter('\n', text).trimEnd('\n', '\r')
        }
    }

    /** One command from the line that shows it to its result */
    private class Run(private val label: String, private val shown: String, private val log: (String, LogLevel) -> Unit) {
        // The first line of it, for the lines that come after what it printed
        private val brief = shown.lineSequence().first().take(120)
        private val chunk = ByteArray(READ_BYTES)
        private var linesShown = 0
        private var linesLeftOut = 0L

        // The command and what it prints are the internal level's, shown when that level is on, so a script that runs
        // commands keeps the console for what it says itself; a failure is an error line, which turns its level on
        private fun say(message: String, level: LogLevel = LogLevel.INTERNAL) = ErrorReporter.keepLocal { log(message, level) }

        // Only info is on at start, so a command that fails turns the error level on, as a failed run does
        private fun fail(message: String) {
            say(message, LogLevel.ERROR)
            consoleErrorAsks.update { it + 1 }
        }

        fun announce(dir: String?) = say("$label: $shown" + (dir?.let { " (in $it)" } ?: ""))

        fun refused(reason: String): ShellResult {
            fail("$label: $reason")
            return ShellResult(shown, -1, "", reason)
        }

        private fun show(line: String, level: LogLevel) {
            if (linesShown < CONSOLE_LINES) {
                linesShown++
                say(line, level)
            } else linesLeftOut++
        }

        /** One pipe of the command: what is kept of it, and the line being put together for the console */
        private inner class Pipe(private val input: InputStream, private val level: LogLevel) {
            private val tail = Tail()
            private val line = ByteArrayOutputStream()
            private var lineCut = false

            /** Takes what is ready, without waiting for more; false when nothing was */
            fun pump(): Boolean {
                val count = try {
                    val ready = input.available()
                    if (ready <= 0) return false
                    input.read(chunk, 0, minOf(ready, chunk.size))
                } catch (e: IOException) {
                    return false
                }
                if (count <= 0) return false
                tail.add(chunk, count)
                for (i in 0 until count) when {
                    chunk[i] == NEWLINE -> endLine()
                    line.size() < CONSOLE_LINE_BYTES -> line.write(chunk[i].toInt())
                    else -> lineCut = true
                }
                return true
            }

            private fun endLine() {
                show(line.toString(charset).trimEnd('\r') + if (lineCut) " (line cut)" else "", level)
                line.reset()
                lineCut = false
            }

            /** A last line without a line break */
            fun finish() {
                if (line.size() > 0) endLine()
            }

            fun text() = tail.text()
        }

        fun start(argv: List<String>, folder: File, timeoutSeconds: Int, env: Map<String, String>): ShellResult {
            if (Thread.currentThread().isInterrupted) {
                fail("$label: cancelled before it started: $brief")
                return ShellResult(shown, -1, "", "", cancelled = true)
            }
            val process = try {
                ProcessBuilder(argv).directory(folder).apply { environment().putAll(env) }.start()
            } catch (e: Exception) {
                return refused("can't start: ${e.message ?: e.javaClass.simpleName}")
            }
            running += process
            // Nobody is there to answer: a command that asks for input gets the end of it instead of waiting
            runCatching { process.outputStream.close() }
            val out = Pipe(process.inputStream, LogLevel.INTERNAL)
            val err = Pipe(process.errorStream, LogLevel.ERROR)
            val pump = { out.pump() or err.pump() }
            val deadline = if (timeoutSeconds > 0) System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong()) else null
            var timedOut = false
            var cancelled = false
            try {
                while (process.isAlive) {
                    val read = pump()
                    cancelled = Thread.interrupted()
                    if (!read && !cancelled) {
                        try {
                            process.waitFor(TICK_MS, TimeUnit.MILLISECONDS)
                        } catch (e: InterruptedException) {
                            cancelled = true
                        }
                    }
                    timedOut = !cancelled && deadline != null && System.nanoTime() - deadline >= 0
                    if (cancelled || timedOut) {
                        destroyTree(process, pump)
                        break
                    }
                }
                // What it printed last is still in the pipes; a child left in the background can't keep this going
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_MS)
                while (pump() && System.nanoTime() < until) Unit
            } finally {
                running -= process
                if (process.isAlive) destroyTree(process)
            }
            out.finish()
            err.finish()
            if (linesLeftOut > 0) say("$label: $linesLeftOut more lines not shown; .out and .err keep the end")
            val exit = if (process.isAlive) -1 else process.exitValue()
            when {
                timedOut -> fail("$label: timed out after ${timeoutSeconds}s and was stopped, with what it started: $brief")
                cancelled -> fail("$label: cancelled and stopped, with what it started: $brief")
                exit != 0 -> fail("$label: exit $exit: $brief")
            }
            // The thread stays interrupted, so what it does next stops as well
            if (cancelled) Thread.currentThread().interrupt()
            return ShellResult(shown, exit, out.text(), err.text(), timedOut, cancelled)
        }

        private companion object {
            const val NEWLINE = '\n'.code.toByte()
        }
    }
}
