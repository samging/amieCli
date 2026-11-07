package org.example.project.components.terminal.window.controller.script

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironment
import java.util.concurrent.Executors
import javax.script.Bindings
import javax.script.Compilable
import javax.script.ScriptContext
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

/**
 * The console's interpreter: the lines typed at the console run one after another in one Kotlin script engine that is
 * kept from line to line, so what a line defines (`val x = 12`, a fun, a class, an import) is there for the next, as in
 * a REPL. The engine's first snippet is the header every script gets, [assembleScript] of nothing: the imports, `amie`
 * and the prelude, so a line calls the API as main.kts does. A line is then compiled as it was typed, and the value it
 * ends with is printed ([shown]).
 *
 * The engine's compiler keeps its state for the thread it first compiled on: a snippet compiled on another thread
 * fails inside the compiler ("Exception during psi2ir"), and every snippet after it too. So an engine has a thread of
 * its own, and every line it compiles and runs goes there.
 *
 * Each line is still a run of its own (TemplateRuns.startLine), listed while it goes and ended by terminate console;
 * the one `amie` the engine holds answers for the line running now ([BackgroundLogger.run]). A line waits until the
 * ones typed before it have ended ([queued]), so `val x = 1` and then `log(x)` run in that order however fast they
 * come. The definitions are forgotten by [reset], and when the env changes under them: the engine compiles against
 * the env's classloader, so a line in another env starts a new one. A line that goes on after it was stopped, a loop
 * that calls nothing of the API, is left to itself with its engine after [STOP_GRACE_MS] ([stopped]), so the lines
 * after it get a new one instead of waiting for good.
 */
internal object ConsoleSession {
    /** How long a stopped line has to end before the lines after it stop waiting for it */
    internal const val STOP_GRACE_MS = 3_000L

    private class Live(
        val env: ScriptEnvironment,
        val loader: ClassLoader,
        val engine: ScriptEngine,
        val bindings: Bindings,
        val logger: BackgroundLogger,
        val thread: ExecutorCoroutineDispatcher,
    ) {
        /** The line running in it now, null between lines */
        var running: TemplateRun? = null
        /** No line is given to it any more: it goes once the line running in it ends */
        var retired = false

        fun release() {
            TemplateRuns.releaseEngine(bindings)
            thread.close()
        }
    }

    private var live: Live? = null

    // The lines in the order they were typed, the one running first
    private val queue = MutableStateFlow<List<TemplateRun>>(emptyList())
    private val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** [run] is the line typed last: it runs once those before it have ended */
    fun queued(run: TemplateRun) = queue.update { it + run }

    /** [run] has ended, whether it got to run or was cancelled before, so the next line may go */
    fun left(run: TemplateRun) = queue.update { it - run }

    /** Runs [code], typed as [run], after the lines before it; the value it ends with, null for none */
    suspend fun eval(run: TemplateRun, code: String, scope: CoroutineScope): Any? {
        queue.first { lines -> run !in lines || lines.first() === run }
        val session = take(run, scope)
        try {
            session.logger.run = run
            val compiled = withContext(session.thread) { withContextClassLoader(session.loader) { (session.engine as Compilable).compile(code) } }
            // A cancel interrupts the thread from here on, which ends a blocked input() and a sleep
            return runInterruptible(session.thread) { run.working { withContextClassLoader(session.loader) { compiled.eval() } } }
        } finally {
            val gone = synchronized(this) {
                session.running = null
                session.retired
            }
            if (gone) session.release()
        }
    }

    /** Forgets every definition: the next line starts with the header alone; false when there was nothing to forget */
    fun reset(): Boolean {
        val old = synchronized(this) { live.also { live = null } } ?: return false
        retire(old)
        return true
    }

    /**
     * [run] was stopped. A line ends at its next API call, or at once from a sleep or input(); one that is still going
     * after [STOP_GRACE_MS] keeps its engine and the lines after it go on in a new one, what was defined forgotten
     */
    fun stopped(run: TemplateRun) {
        watch.launch {
            delay(STOP_GRACE_MS)
            if (!run.state.running) return@launch
            val held = synchronized(this@ConsoleSession) { live?.takeIf { it.running === run }?.also { live = null } }
            if (held != null) {
                retire(held)
                run.say("still runs after it was stopped, so the next line starts a new console: what the lines before defined is forgotten", LogLevel.ERROR)
            }
            left(run)
        }
    }

    private fun retire(session: Live) {
        val now = synchronized(this) {
            session.retired = true
            session.running == null
        }
        if (now) session.release()
    }

    // The engine of the env the line runs in, given to it; made with the header when there is none or the env is another now
    private suspend fun take(run: TemplateRun, scope: CoroutineScope): Live {
        val loader = run.env.classLoader(ScriptEngineCache.classLoader)
        val old = synchronized(this) {
            val current = live
            if (current != null && current.env === run.env && current.loader === loader) {
                current.running = run
                return current
            }
            live = null
            current
        }
        if (old != null) {
            logInternal("console: the env is '${run.env.name}' with other packages now, so what the lines before defined is forgotten")
            retire(old)
        }
        val fresh = open(run, loader, scope)
        synchronized(this) {
            fresh.running = run
            live = fresh
        }
        return fresh
    }

    private suspend fun open(run: TemplateRun, loader: ClassLoader, scope: CoroutineScope): Live {
        val thread = Executors.newSingleThreadExecutor { task -> Thread(task, "amie-console").apply { isDaemon = true } }.asCoroutineDispatcher()
        val logger = BackgroundLogger(SandboxLogger(run.env, ScriptEngineCache.gradleEngine, scope), run)
        return try {
            withContext(thread) {
                withContextClassLoader(loader) {
                    val engine = ScriptEngineManager(loader).getEngineByExtension("kts") ?: error("Couldn't find Kotlin script engine")
                    val bindings = engine.createBindings().apply { put("logger", logger) }
                    // The engine is the console's alone, so its own context keeps the bindings, and with them the REPL's state
                    engine.setBindings(bindings, ScriptContext.ENGINE_SCOPE)
                    try {
                        (engine as Compilable).compile(assembleScript("")).eval()
                    } catch (e: Throwable) {
                        TemplateRuns.releaseEngine(bindings)
                        throw e
                    }
                    Live(run.env, loader, engine, bindings, logger, thread)
                }
            }
        } catch (e: Throwable) {
            thread.close()
            throw e
        }
    }

    /**
     * What a line's value is printed as; null for none to print: nothing or Unit, a shell result whose output was shown
     * as it came, and an object with no text of its own (Class@1a2b3c says nothing)
     */
    fun shown(value: Any?): String? {
        if (value == null || value == Unit || value is ShellResult) return null
        val text = consoleText(value)
        val identity = value.javaClass.name + "@" + Integer.toHexString(value.hashCode())
        return text.takeUnless { it == identity }
    }
}
