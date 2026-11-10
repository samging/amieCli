/*
 * amie: an interpreter for Kotlin scripts that train, ask, group and serve intent models
 * Copyright (C) 2026 the amie authors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details. You should have received a copy of the GNU General Public License along with this program. If not, see
 * <https://www.gnu.org/licenses/>.
 */
package org.amie.cli

import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan
import org.example.project.components.terminal.window.controller.runtime.ModelServer
import org.example.project.components.terminal.window.controller.script.HeadlessAmie
import org.example.project.components.terminal.window.controller.script.ScriptTemplates
import java.io.File
import java.util.concurrent.CountDownLatch

/** A script run as main.kts: a file, a template or the code of -c */
object ScriptRun {
    /** The file's code, or a project's main.kts by its name, or a starter's or a template's by its title, when there is no such file; null with the reason on standard error */
    fun readScript(file: String): String? {
        val f = File(file)
        if (f.isFile) return f.readText()
        Projects.mainScript(file)?.let { return it.readText() }
        Starters.template(file)?.let { t -> return ScriptTemplates.code(t.id) }
        System.err.println(Style.error("amie: can't open file '$file': no such file, project, starter or template (amie example lists the starters)"))
        return null
    }

    /** Runs [file]: a project of templates by its name, else the script [readScript] finds; null when there is none */
    fun runFile(file: String, env: String?): Int? {
        if (!File(file).isFile && ProjectTemplates.exists(file) && ProjectTemplates.ids(file).isNotEmpty()) return ProjectTemplates.run(file, env)
        return readScript(file)?.let { runCode(it, env, file) }
    }

    /** What follows the script on the command line, for the script: the system property amie.args, one line each */
    fun passArguments(args: List<String>) {
        System.setProperty("amie.args", args.joinToString("\n"))
    }

    /**
     * Runs [code] as main.kts, in [env] when one was asked for (its environment line rewritten, or one put at the
     * top), then waits for what it started in the background and, when it left a
     * model serving, keeps the process up until it is stopped. The exit code: 0 finished, 1 failed.
     */
    fun runCode(code: String, env: String?, name: String): Int {
        val text = if (env != null) EnvPlan.withEnv(code, env) else code
        val failure = HeadlessAmie.run(text)
        val backgroundFailed = HeadlessAmie.awaitBackground()
        Serving.waitWhileListening()
        return if (failure == null && !backgroundFailed) Amie.EXIT_OK else Amie.EXIT_ERROR
    }
}

/** A model left serving is what the script was for: the process stays up until it is told to stop */
object Serving {
    fun waitWhileListening() {
        if (!ModelServer.anyListening) return
        val stopped = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { ModelServer.stopAll(); stopped.countDown() })
        System.err.println(Style.error("Serving. Stop with Ctrl-C or SIGTERM."))
        stopped.await()
    }
}
