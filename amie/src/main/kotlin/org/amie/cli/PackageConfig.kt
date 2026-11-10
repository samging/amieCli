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

import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.components.terminal.window.controller.pluginEngine.PackageConfigFiles
import org.example.project.components.terminal.window.controller.pluginEngine.PackageConfigFiles.Declared
import org.example.project.components.terminal.window.controller.pluginEngine.PackageMeta
import org.example.project.components.terminal.window.controller.pluginEngine.PackageMetadata
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.File

/**
 * `amie packages config` and `amie packages setup`: the config files of a package jar, AMP/pluginDir/config/<jar>/,
 * shown and set from the command line, so no one has to find the folder and edit them by hand.
 */
internal object PackageConfigCli {
    private const val INDENT = "    "
    private val pluginDir: File get() = File(AmpFolder.dir, "pluginDir")

    /** Whether a config file is filled in, colored: red for a required file still to fill in, yellow for an optional one */
    fun state(d: Declared): String {
        val c = d.config
        val empty = d.empty
        return when {
            d.template && c.required -> Style.error("required, still its template: fill it in")
            d.template -> Style.warn("optional, still its template")
            d.file.isFile && empty.isNotEmpty() -> Style.ok(if (c.required) "required, filled in" else "optional, filled in") + Style.dim(", empty: " + empty.joinToString(", "))
            d.file.isFile -> Style.ok(if (c.required) "required, filled in" else "optional, filled in")
            c.required -> Style.error("required, missing" + if (c.template != null) ": its template is written on the first open" else "")
            else -> Style.dim("optional, not written yet")
        }
    }

    /** `packages config [x.jar] [key=value...] [--file f] [--from path] [--edit] [--all-jars]` */
    fun config(flags: Flags): Int {
        val name = flags.words.firstOrNull() ?: return overview()
        val (jar, meta) = jar(name)
        val files = PackageConfigFiles.declared(jar.name, meta)
        if (files.isEmpty() && meta?.env.isNullOrEmpty()) {
            println("${jar.name} has no config files")
            return Amie.EXIT_OK
        }
        val pick = flags.valueOf("--file")?.let { f ->
            files.firstOrNull { it.file.name == f } ?: throw IllegalArgumentException("${jar.name} has no config file $f; it has ${files.joinToString(", ") { it.file.name }}")
        }
        flags.valueOf("--from")?.let { from ->
            val target = pick ?: files.singleOrNull()
                ?: throw IllegalArgumentException("Say which file --from fills: " + files.joinToString(" or ") { "--file ${it.file.name}" })
            PackageConfigFiles.copyIn(File(home(from)), target.file)
            println("${target.file.name} of ${jar.name} is now a copy of $from  " + state(target))
            return Amie.EXIT_OK
        }
        val pairs = flags.words.drop(1)
        if (pairs.isNotEmpty()) return set(jar, files, pick, pairs, flags.has("--all-jars"), flags.has("--add"))
        if (flags.has("--edit")) return edit(pick?.let(::listOf) ?: files)
        show(jar, meta, pick?.let(::listOf) ?: files)
        return Amie.EXIT_OK
    }

    /** `packages setup x.jar`: each key asked at the terminal, Enter keeping its value */
    fun setup(flags: Flags): Int {
        val name = flags.words.firstOrNull() ?: throw IllegalArgumentException("Missing: the jar's name")
        val (jar, meta) = jar(name)
        val files = PackageConfigFiles.declared(jar.name, meta)
        if (files.isEmpty()) {
            println("${jar.name} has no config files to set up")
            return Amie.EXIT_OK
        }
        val ask = Asker.open() ?: throw IllegalArgumentException("setup asks at a terminal; without one, amie packages config ${jar.name} key=value sets a key")
        val width = terminalWidth() - 1
        try {
            println(Style.accent(jar.name) + "  " + Style.dim("Enter keeps a value, - empties it, Ctrl-C leaves"))
            files.forEach { d ->
                println()
                println(Style.accent(d.file.name) + "  " + state(d))
                if (!d.properties) {
                    d.config.description?.let { println(Style.dim(wrap(it, width, INDENT))) }
                    val path = ask.line("${INDENT}copy it from, a path (Enter skips): ")
                    if (path.isNotEmpty()) {
                        PackageConfigFiles.copyIn(File(home(path)), d.file)
                        println(INDENT + state(d))
                    }
                    return@forEach
                }
                offerOthers(jar.name, d, ask)
                val changes = linkedMapOf<String, String>()
                PackageConfigFiles.entries(d.file).forEach { e ->
                    e.hint.forEach { println(Style.dim(wrap(it, width, INDENT))) }
                    val secret = PackageConfigFiles.isSecret(e.key)
                    val current = when { e.value.isEmpty() -> ""; secret -> " [hidden]"; else -> " [${e.value}]" }
                    val typed = if (secret) ask.secret("$INDENT${e.key}$current: ") else ask.line("$INDENT${e.key}$current: ")
                    when {
                        typed == "-" -> changes[e.key] = ""
                        typed.isNotEmpty() -> changes[e.key] = typed
                    }
                }
                if (changes.isNotEmpty()) PackageConfigFiles.set(d.file, changes)
                println(INDENT + (if (changes.isEmpty()) "Nothing changed, " else "Saved, ") + state(d))
                offerAuthorize(jar, d, ask)
            }
        } catch (e: UserInterruptException) {
            println("\nLeft. The files finished before are saved")
        } catch (e: EndOfFileException) {
            println("\nLeft. The files finished before are saved")
        } finally {
            ask.close()
        }
        meta?.env?.forEach { v ->
            if (System.getenv(v.name).isNullOrEmpty()) println(Style.dim("\$${v.name} is read from the environment: export ${v.name}=... before amie" + (v.description?.let { ", $it" } ?: "")))
        }
        println(Style.dim("amie packages info ${jar.name} says whether it opens now"))
        return Amie.EXIT_OK
    }

    /** When another config folder already has this file with values this one lacks, asks whether to take them */
    private fun offerOthers(jarName: String, d: Declared, ask: Asker) {
        val here = PackageConfigFiles.entries(d.file)
        val blank = here.filter { it.value.isBlank() }.map { it.key }.toSet()
        if (blank.isEmpty()) return
        fun takeable(file: File) = PackageConfigFiles.entries(file).filter { it.key in blank && it.value.isNotBlank() }
        val source = PackageConfigFiles.others(jarName, d.file.name).firstOrNull { takeable(it).isNotEmpty() } ?: return
        val take = takeable(source)
        val answer = ask.line("$INDENT${source.parentFile.name} has ${take.joinToString(", ") { it.key }} filled in. Take them? [Y/n] ")
        if (answer.isEmpty() || answer.lowercase().startsWith("y")) {
            PackageConfigFiles.set(d.file, take.associate { it.key to it.value })
            println(INDENT + Style.ok("Took ${take.size} value${if (take.size == 1) "" else "s"} from ${source.parentFile.name}"))
        }
    }

    /** Every jar of AMP/pluginDir that has config files, each file with its state */
    private fun overview(): Int {
        val rows = pluginDir.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty().sortedBy { it.name }.flatMap { jar ->
            val dir = PackageMetadata.configDir(jar.name)
            PackageMetadata.read(jar)?.config.orEmpty().map { c -> jar.name to Declared(c, File(dir, c.file)) }
        }
        if (rows.isEmpty()) {
            println("No jar in ${pluginDir.shown} has config files")
            return Amie.EXIT_OK
        }
        println(Style.dim("Config files in ${File(pluginDir, "config").shown}"))
        val w1 = rows.maxOf { it.first.length }
        val w2 = rows.maxOf { it.second.file.name.length }
        rows.forEach { (jar, d) -> println(Style.accent(jar.padEnd(w1)) + "  " + d.file.name.padEnd(w2) + "  " + state(d)) }
        println(Style.dim("amie packages config <x.jar> shows its keys; amie packages setup <x.jar> asks for each"))
        return Amie.EXIT_OK
    }

    /** One jar's files: each key with its value, a secret hidden, under the hint the file gives above it */
    private fun show(jar: File, meta: PackageMeta?, files: List<Declared>) {
        val width = terminalWidth() - 1
        println(Style.accent(jar.name) + "  " + Style.dim(PackageMetadata.configDir(jar.name).shown))
        files.forEach { d ->
            println()
            println(Style.accent(d.file.name) + "  " + state(d))
            d.config.description?.let { println(Style.dim(wrap(it, width, INDENT))) }
            if (!d.properties) {
                println(INDENT + if (d.file.isFile) "${d.file.length()} bytes" else Style.dim("copy it in: amie packages config ${jar.name} --file ${d.file.name} --from <path>"))
                return@forEach
            }
            val entries = PackageConfigFiles.entries(d.file)
            val keyWidth = entries.maxOfOrNull { it.key.length } ?: 0
            entries.forEach { e ->
                val value = when {
                    e.value.isEmpty() -> Style.dim("empty")
                    PackageConfigFiles.isSecret(e.key) -> Style.dim("hidden, ${e.value.length} characters")
                    else -> e.value
                }
                println(INDENT + e.key.padEnd(keyWidth) + "  " + value)
            }
        }
        meta?.env?.forEach { v ->
            println()
            println(Style.accent("\$${v.name}") + "  " + when {
                !System.getenv(v.name).isNullOrEmpty() -> Style.ok("set")
                v.required -> Style.error("not set, required")
                else -> Style.dim("not set, optional")
            })
            v.description?.let { println(Style.dim(wrap(it, width, INDENT))) }
        }
        println()
        println(Style.dim("amie packages config ${jar.name} key=value sets a key; amie packages setup ${jar.name} asks for each; --edit opens the files"))
    }

    /** Sets key=value pairs, each in the file that has the key; one .properties file alone takes keys it lacks too */
    private fun set(jar: File, files: List<Declared>, pick: Declared?, pairs: List<String>, everywhere: Boolean, add: Boolean): Int {
        val values = pairs.associate { p ->
            require('=' in p && p.substringBefore('=').isNotBlank()) { "'$p' is not key=value" }
            p.substringBefore('=').trim() to p.substringAfter('=')
        }
        val props = (pick?.let(::listOf) ?: files).filter { it.properties }
        require(props.isNotEmpty()) { "${jar.name} has no .properties file to set keys in" }
        // A key goes to the file that has a line for it; a key no file has is refused, a typo would only add a line no one reads
        val byFile = values.entries.groupBy { (k, _) -> props.firstOrNull { d -> PackageConfigFiles.entries(d.file).any { it.key == k } } ?: props.singleOrNull()?.takeIf { add } }
        byFile[null]?.let { unknown ->
            throw IllegalArgumentException("${jar.name} has no key ${unknown.joinToString(", ") { it.key }}. Its keys: " +
                props.flatMap { PackageConfigFiles.entries(it.file) }.joinToString(", ") { it.key } + ". --add adds a new one (with --file when it has several files)")
        }
        byFile.forEach { (d, entries) ->
            d!!
            val map = entries.associate { it.key to it.value }
            PackageConfigFiles.set(d.file, map, add = true)
            val others = if (everywhere) sameFile(jar.name, d.file.name) else PackageConfigFiles.others(jar.name, d.file.name)
            if (everywhere) others.forEach { PackageConfigFiles.set(it, map) }
            println("${d.file.name} of ${jar.name}: ${map.keys.joinToString(", ")} set" +
                (if (everywhere && others.isNotEmpty()) ", also in ${others.joinToString(", ") { it.parentFile.name }}" else "") + "  " + state(d))
            if (!everywhere && others.isNotEmpty())
                println(Style.dim("${others.joinToString(", ") { it.parentFile.name }} ${if (others.size == 1) "has" else "have"} a ${d.file.name} too; --all-jars sets the keys there as well"))
        }
        return Amie.EXIT_OK
    }

    /**
     * The files of that name in every other config folder: the jars of AMP/pluginDir that declare it (their template
     * written when it is missing) and the folders already there, the shared ones such as `google` included
     */
    private fun sameFile(jarName: String, fileName: String): List<File> {
        val declaring = pluginDir.listFiles { f -> f.isFile && f.extension == "jar" && f.name != jarName }.orEmpty().flatMap { other ->
            val meta = PackageMetadata.read(other)
            if (meta?.config?.any { it.file == fileName } == true) PackageConfigFiles.declared(other.name, meta).filter { it.file.name == fileName }.map { it.file } else emptyList()
        }
        return (declaring + PackageConfigFiles.others(jarName, fileName)).distinctBy { it.canonicalPath }.sortedBy { it.parentFile.name }
    }

    /**
     * When the file's hints say to run `java -jar <jar> authorize`, as the Google packages do for their refresh token,
     * and a key is still empty, offers to run it: the jar opens the consent page and writes the token into the folder
     */
    private fun offerAuthorize(jar: File, d: Declared, ask: Asker) {
        val text = runCatching { d.file.readText() }.getOrDefault("")
        if ("${jar.name} authorize" !in text || d.empty.none { PackageConfigFiles.isSecret(it) }) return
        val answer = ask.line("$INDENT${jar.name} signs in with its own authorize step, which opens the browser. Run it now? [Y/n] ")
        if (answer.isNotEmpty() && !answer.lowercase().startsWith("y")) return
        val java = File(System.getProperty("java.home"), "bin/java").path
        val code = ProcessBuilder(java, "-jar", jar.path, "authorize", d.file.parentFile.path).inheritIO().start().waitFor()
        println(INDENT + (if (code == 0) "" else Style.error("authorize left with $code. ")) + state(d))
    }

    /** Opens the files in \$VISUAL or \$EDITOR, else the Mac's text editor; without a terminal, says where they are */
    private fun edit(files: List<Declared>): Int {
        val paths = files.map { it.file.path }
        val editor = (System.getenv("VISUAL") ?: System.getenv("EDITOR"))?.takeIf { it.isNotBlank() }
        when {
            System.console() == null && Repl.currentTerminal == null -> paths.forEach(::println)
            editor != null -> {
                val code = ProcessBuilder(editor.split(' ').filter { it.isNotEmpty() } + paths).inheritIO().start().waitFor()
                if (code != 0) throw IllegalStateException("$editor left with $code")
                files.forEach { println(it.file.name + "  " + state(it)) }
            }
            System.getProperty("os.name").startsWith("Mac") -> {
                ProcessBuilder(listOf("open", "-t") + paths).inheritIO().start().waitFor()
                println("Opened in the text editor: " + files.joinToString(", ") { it.file.name })
            }
            else -> paths.forEach(::println)
        }
        return Amie.EXIT_OK
    }

    private fun jar(name: String): Pair<File, PackageMeta?> {
        val (file, _) = JarPackages.resolve(name)
            ?: throw IllegalArgumentException("No package '$name': not in the env, AMP/pluginDir, as a path or in the GitHub repositories of AMP/github.json")
        return file to PackageMetadata.read(file)
    }

    private fun home(path: String): String = if (path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path

    /** Lines read at the terminal: the prompt's own while it is open, else one made for the question */
    private class Asker(private val terminal: Terminal, private val own: Boolean) : AutoCloseable {
        private val reader = LineReaderBuilder.builder().terminal(terminal).build()
        fun line(prompt: String): String = reader.readLine(prompt).trim()
        fun secret(prompt: String): String = reader.readLine(prompt, '*').trim()
        override fun close() { if (own) terminal.close() }

        companion object {
            fun open(): Asker? = Repl.currentTerminal?.let { Asker(it, false) }
                ?: if (System.console() != null) Asker(TerminalBuilder.builder().system(true).build(), true) else null
        }
    }
}
