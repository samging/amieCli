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

import org.example.project.components.terminal.window.controller.script.HeadlessAmie
import kotlin.system.exitProcess

/** `amie`: the entry point of the interpreter; what it was asked decides the exit code, as python's does */
fun main(args: Array<String>) {
    exitProcess(Amie.main(args.toList()))
}

object Amie {
    const val EXIT_OK = 0
    const val EXIT_ERROR = 1
    const val EXIT_USAGE = 2

    fun main(args: List<String>): Int {
        val parsed = CommandLine.parse(args)
        if (parsed is Invocation.Usage) {
            System.err.println(Style.error(parsed.problem))
            System.err.println(CommandLine.SHORT_USAGE)
            return EXIT_USAGE
        }
        if (parsed is Invocation.Help) {
            val command = parsed.command?.let(Commands::find)
            println(command?.help ?: CommandLine.USAGE)
            return EXIT_OK
        }
        if (parsed is Invocation.Version) {
            println(Banner.versionLine)
            println(Banner.LICENSE_LINE)
            return EXIT_OK
        }
        // Everything from here on reads or writes the AMP folder, so it is settled first, and every console line is ours
        Output.install(verbose = parsed.options.verbose)
        AmpFolder.resolve(parsed.options.amp)
        HeadlessAmie.readInputFromStandardInput()
        return try {
            when (parsed) {
                is Invocation.Repl -> Repl(parsed.options).start()
                is Invocation.Script -> {
                    val code = ScriptRun.readScript(parsed.file) ?: return EXIT_ERROR
                    ScriptRun.passArguments(parsed.args)
                    promptIfInteractive(ScriptRun.runCode(code, parsed.options.env, parsed.file), parsed.interactive, parsed.options)
                }
                is Invocation.Code -> promptIfInteractive(ScriptRun.runCode(parsed.code, parsed.options.env, "-c"), parsed.interactive, parsed.options)
                is Invocation.Command -> Commands.find(parsed.name)!!.run(parsed.args, parsed.options)
                else -> EXIT_USAGE
            }
        } finally {
            HeadlessAmie.close()
        }
    }

    // -i: the prompt after the script, with what it defined gone but its models and sessions still open
    private fun promptIfInteractive(code: Int, interactive: Boolean, options: Options): Int =
        if (interactive) Repl(options, banner = false).start() else code
}
