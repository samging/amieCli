package org.example.project.components.terminal.window.controller.console

import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.ResponseDto
import org.example.project.components.terminal.window.controller.sandbox.SANDBOX_SOURCE

/**
 * The error terminal: the error lines the sandbox's console showed, kept in memory so a script can pull them
 * (`errorLog()`) and hand them to the local text model (`handleBy()`). Every line passes [logToConsole], which records
 * the error lines the sandbox's console shows, as its own filter has it: the script's, the sandbox's, untagged and
 * internal ones; a device terminal's lines are its own. The last [KEPT] are held. Clearing the console, which a run
 * does when it starts, empties it, so the lines are the run's. Nothing here touches Compose, so a bundle's run keeps it too.
 */
object ErrorTerminal {
    /** How many lines are held: the last ones, as handleBy asks about the last ten of them */
    const val KEPT = 200

    private val held = ArrayDeque<String>()

    /** Keeps [dto] when it is an error line the sandbox's console shows */
    internal fun record(dto: ResponseDto) {
        if (dto.level != LogLevel.ERROR || !shown(dto.source)) return
        synchronized(held) {
            held.addLast(dto.text())
            while (held.size > KEPT) held.removeFirst()
        }
    }

    /** The last [last] lines, oldest first */
    fun lines(last: Int = KEPT): List<String> = synchronized(held) { held.takeLast(last.coerceAtLeast(0)) }

    fun clear() {
        synchronized(held) { held.clear() }
    }

    // What the sandbox's console shows, as TerminalWindow's belongsTo has it for its source
    private fun shown(source: String?): Boolean =
        source == null || source == INTERNAL_SOURCE || source == SANDBOX_SOURCE || source.startsWith("$SANDBOX_SOURCE/")
}
