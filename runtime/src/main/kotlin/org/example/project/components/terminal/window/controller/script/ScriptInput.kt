package org.example.project.components.terminal.window.controller.script

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Optional
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * What input() reads in a run of main.kts, a line at a time. In the app a line comes from the console, handed over
 * while the script waits; a bundle has no console, so there it comes from standard input.
 */
internal object ScriptInput {
    // Nothing is kept: a line only goes to a script that is reading, an empty one says no more are coming
    private val lines = SynchronousQueue<Optional<String>>()
    private val standardInput by lazy { System.`in`.bufferedReader() }
    private val reading = MutableStateFlow(false)

    /** A script waits at input() now; the console's hint says so and its keyboard goes there */
    val waiting: StateFlow<Boolean> = reading.asStateFlow()

    /** Set by the bundle's runner, before anything of the script runs */
    @Volatile
    internal var fromStandardInput = false

    /** The next line, waiting for one; null once there are no more. Stop ends the wait */
    fun next(): String? = try {
        reading.value = true
        if (fromStandardInput) standardInput.readLine() else lines.take().orElse(null)
    } catch (e: InterruptedException) {
        throw CancellationException("Stopped while waiting for input").apply { initCause(e) }
    } finally {
        reading.value = false
    }

    /** False when no script is waiting at input() */
    fun give(line: String): Boolean = hand(Optional.of(line))

    /** The null of a closed input for the script waiting; false when none is */
    fun end(): Boolean = hand(Optional.empty())

    // Waiting is said a moment before the take that receives it
    private fun hand(line: Optional<String>) = reading.value && lines.offer(line, 1, TimeUnit.SECONDS)
}
