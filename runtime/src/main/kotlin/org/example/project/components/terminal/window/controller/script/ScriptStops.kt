package org.example.project.components.terminal.window.controller.script

import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException

/**
 * What ends a run rather than failing a call in it, which `handleBy` hands to no handler. A stop: Stop interrupts the
 * script's thread, so a sleep, an input() or a model's answer throws InterruptedException, or a CancellationException
 * where the call turns the interrupt into one (input(), ONNX Runtime). A cancel: a template's run throws
 * CancellationException at its next log, input or output (TemplateRun.stopIfCancelled), as do Terminate on a template
 * or on a model being asked, an ortAsync job and an answer in the background. And an Error, OutOfMemoryError and the
 * like, which no script can put right.
 */
internal object ScriptStops {
    // How far down the causes a stop is looked for: a library that wraps the interrupt wraps it once or twice
    private const val CAUSES = 8

    /** Whether [e] is a stop, a cancel or an Error: thrown while the thread is interrupted, or caused by an interrupt or a cancel */
    fun passesThrough(e: Throwable): Boolean =
        e is Error || Thread.currentThread().isInterrupted ||
            generateSequence(e) { it.cause }.take(CAUSES).any { it is CancellationException || it is InterruptedException || it is ClosedByInterruptException }

    /** [block]'s value; when it throws anything but what [passesThrough], [handler]'s value for it */
    fun <T> handleBy(handler: (Throwable) -> T, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            if (passesThrough(e)) throw e
            handler(e)
        }
}
