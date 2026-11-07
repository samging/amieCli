package org.example.project.components.terminal.window.controller.script

import org.example.project.components.terminal.window.controller.ResponseDto
import org.example.project.components.terminal.window.controller.console.ConsoleBlocks
import org.example.project.components.terminal.window.controller.console.flushLogFile
import org.example.project.components.terminal.window.controller.console.headlessConsole
import org.example.project.components.terminal.window.controller.console.openSessions
import org.example.project.components.terminal.window.controller.console.terminateAllSessions
import org.example.project.components.terminal.window.controller.databases.Databases
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.components.terminal.window.controller.sandbox.SandboxRuns
import org.example.project.getDirectory
import java.io.File
import java.time.Instant

/**
 * The script runtime for a program with no window, such as the amie interpreter (the `amie` module): what the
 * sandbox's Run, its console's command line and its Still open list do, reachable from outside this module. Nothing
 * here touches Compose. Every console line goes to [console] once it is set, so set it before the first line logs.
 */
object HeadlessAmie {
    /** A session open now, as the console's Still open list has it; [terminate] closes it as Terminate on its row does */
    data class OpenSession(val key: String, val owner: String, val id: Int, val kind: String, val running: Boolean, val scope: String?, val openedAt: Instant, val terminate: () -> Unit)

    /** Takes every console line from now on; the app's console is never loaded */
    fun useConsole(print: (ResponseDto) -> Unit) {
        headlessConsole = print
    }

    /** What a script's input() reads is standard input, a line at a time, as in a bundle */
    fun readInputFromStandardInput() {
        ScriptInput.fromStandardInput = true
    }

    /**
     * One run of [code] as main.kts, as the sandbox's Run does it: the env sourced first, then the script on the calling
     * thread. Null when it finished, else its first error in one line; every error was a console line by then.
     */
    fun run(code: String): String? {
        ensurePluginDir()
        return ScriptRunner.run(code)
    }

    /** What [run] does before the script's first line: null when it compiles, else its first error */
    fun compile(code: String): String? {
        ensurePluginDir()
        return ScriptRunner.compile(code)
    }

    // A run writes the assembled script beside main.kts; a fresh AMP folder has no pluginDir yet
    private fun ensurePluginDir(): File = File(getDirectory().toString(), "pluginDir").apply { mkdirs() }

    /** True when [code] can run as it is; false while it waits for more lines, as an interpreter's `...` prompt does */
    fun isComplete(code: String): Boolean = ConsoleBlocks.complete(code)

    /** True when no bracket, raw string or comment of [code] is left open, whatever its last line ends with */
    fun hasClosedBrackets(code: String): Boolean = ConsoleBlocks.bracketsClosed(code)

    /**
     * Runs [code], a line or a block typed at a prompt, in the interpreter the typed lines share: what one defines
     * (`val x = 12`, a fun, a class, an import) is there for the next, until [reset]. Waits for it to end; how it ended,
     * its reason said in the console already when it failed.
     */
    fun runLine(code: String): TemplateState = TemplateRuns.startLine(code).await()

    /** Stops the typed lines running now, as Ctrl+C at the console does; how many */
    fun interruptLines(): Int = TemplateRuns.active.filter { it.typed }.onEach { it.cancel() }.size

    /** Forgets what the typed lines defined; false when nothing had run yet */
    fun reset(): Boolean = ConsoleSession.reset()

    /** Every session open in this process now */
    fun listSessions(): List<OpenSession> = openSessions().map { s -> OpenSession(s.key, s.owner, s.id, s.kind, s.running, s.scope, s.openedAt, s.terminate) }

    /** Closes the sessions named [what], a name or a #id; how many it found */
    fun terminate(what: String): Int {
        val id = what.removePrefix("#").toIntOrNull()
        val found = openSessions().filter { s -> (id != null && s.id == id) || s.owner.equals(what, ignoreCase = true) }
        found.forEach { s -> runCatching { s.terminate() } }
        return found.size
    }

    /** Closes every session and cancels every background template, as Terminate all does; how many were open */
    fun terminateAll(): Int {
        val before = openSessions().size
        terminateAllSessions()
        return before
    }

    /**
     * Waits for what a run started in the background to end, as a bundle's runner does when its script has ended:
     * a template waiting for input is told no more is coming. True when any of them failed.
     */
    fun awaitBackground(): Boolean {
        val seen = linkedSetOf<TemplateRun>()
        while (true) {
            val running = TemplateRuns.active.filter { !it.typed }
            if (running.isEmpty()) break
            seen += running
            running.forEach { it.closeIfUnfed() }
            Thread.sleep(50)
        }
        return seen.any { it.state.failed }
    }

    /** The last runs of main.kts, the newest first, one line each, as AMP/pluginDir/runs.jsonl keeps them */
    fun readRecentRuns(): List<String> = SandboxRuns.read().map { r ->
        val how = when {
            r.stopped -> "stopped"
            r.ok -> "finished"
            else -> "failed: ${r.error}"
        }
        "${r.at}  ${r.millis} ms  $how"
    }

    /** What a process does before it ends: the databases and packages closed, the log file written */
    fun close() {
        runCatching { Databases.closeAll() }
        runCatching { JarPackages.closeAll() }
        flushLogFile()
    }
}
