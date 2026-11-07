package org.example.project.components.terminal.window.controller.runtime

import org.example.project.components.terminal.integrations.amiePilot.Ask
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A model another can be put in the place of while it answers. A session and a listener hold their model in one
 * of these, so a fine-tune, a build or a rollback of the model doesn't close them: the new head is opened beside
 * the old one, [swap] puts it in place once the answers in flight on the old one are done, and the old one is
 * closed after. Whoever asks sees the old answers up to the swap and the new ones from it, never a closed model.
 */
class LiveModel(first: InteractionModel) : InteractionModel {
    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var model: InteractionModel = first

    /** How many times another model took the place of the one before */
    @Volatile
    var swaps: Int = 0
        private set

    /** The model answering now */
    val current: InteractionModel get() = model

    override fun inference(text: String): List<String> = lock.read { model.inference(text) }

    override fun inference(text: String, ask: Ask): List<String> = lock.read { model.inference(text, ask) }

    override fun getStatus(): Any = model.getStatus()

    override val abilities: Set<String> get() = model.abilities

    /**
     * [next] answers from now on. Waits for the answers in flight on the model before (a text takes well under a
     * second), then gives that model back, to be closed by the caller: nothing is running on it any more
     */
    fun swap(next: InteractionModel): InteractionModel = lock.write {
        val old = model
        model = next
        swaps++
        old
    }

    /** Closes the model answering now, once the answers in flight on it are done */
    override fun close() = lock.write { model.close() }
}
