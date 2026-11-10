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

import org.example.project.components.terminal.window.controller.huggingFace.ImportedModel
import org.example.project.components.terminal.window.controller.modelLoaders.imported.EncoderDecoderModel
import org.example.project.components.terminal.window.controller.modelLoaders.imported.ModelFormats
import org.example.project.components.terminal.window.controller.modelLoaders.imported.ImportedModels
import org.example.project.components.terminal.window.controller.modules.deleteModule
import org.example.project.components.terminal.window.controller.modules.hasModule
import org.example.project.components.terminal.window.controller.script.HeadlessAmie
import kotlinx.coroutines.runBlocking
import org.example.project.amp.ModelFolders
import org.example.project.components.terminal.window.controller.envBuilder.DEFAULT_ENV
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.huggingFace.HfException
import org.example.project.components.terminal.window.controller.huggingFace.HfImports
import org.example.project.components.terminal.window.controller.huggingFace.HfMenu
import org.example.project.components.terminal.window.controller.huggingFace.HfPick
import org.example.project.components.terminal.window.controller.util.bytes
import org.example.project.components.terminal.window.controller.huggingFace.HfSearchFormat
import org.example.project.components.terminal.window.controller.huggingFace.HfSearchKind
import org.example.project.components.terminal.window.controller.huggingFace.ImportedList
import org.example.project.components.terminal.window.controller.modules.MetricsReport
import org.example.project.components.terminal.window.controller.modules.ModelGroups
import org.example.project.components.terminal.window.controller.modules.ModuleManifest
import org.example.project.components.terminal.window.controller.modules.moduleExists
import org.example.project.components.terminal.window.controller.modules.modulesDir
import org.example.project.components.terminal.window.controller.modules.readRegistry
import org.example.project.components.terminal.window.controller.modules.readStoredManifest
import org.example.project.components.terminal.window.controller.pluginEngine.GitHubJars
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.components.terminal.window.controller.pluginEngine.PackageConfigFiles
import org.example.project.components.terminal.window.controller.pluginEngine.PackageMetadata
import org.example.project.components.terminal.window.controller.runtime.ModelServer
import org.example.project.components.terminal.window.controller.runtime.SpeechModels
import org.example.project.components.terminal.window.controller.runtime.TextModels
import org.example.project.components.terminal.window.controller.runtime.readServing
import java.io.File

/** A string as a script writes it, so a name or a path can be put into generated script code */
internal fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

/** The first labels of a model and how many there are, for a list's row */
internal fun labelsCell(labels: List<String>, shown: Int = 6): String =
    if (labels.size <= shown + 2) labels.joinToString(", ") else labels.take(shown).joinToString(", ") + ", … (${labels.size} labels)"

object ModelsCommand : Command("model", "The models of the AMP folder: built intent models, text and speech models, and those imported from Hugging Face") {
    override val actions = listOf(
        "model list" to "every model: its kind, version, date and first labels",
        "model build <data.csv> [--name <name>] [--test <test.csv>]" to "trains an intent model on the labeled rows, as modelBuilder does; the name is the file's unless given",
        "model validate <name>" to "the model's last stored validation: accuracy, macro F1 and each label's figures",
        "model serve <name> [--port <n>] [--host <h>] [--path <p>]" to "serves the model over HTTP until stopped",
        "model metric <name> [--last] [--file]" to "the model's metrics.json as it is stored, one entry per build: accuracy, macro F1, each label's precision, recall, F1 and support; --last only the latest build, --file only where the file is",
        "model info <name>" to "what is known of the model: its labels, encoder, version, how it was trained",
        "model remove <name>" to "removes the model's folder from AMP/models, whatever its kind (intent, text, speech or imported); a session of it open here is closed first, and its serving stopped",
    )
    override val valued = setOf("--name", "--test", "--port", "--host", "--path")

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return when (action) {
            "list" -> {
                // The labels last and shortened, so a model with forty of them doesn't push every column off the screen; model info names them all
                val rows = builtModels().map { (name, m) -> listOf(name, "intent", m.version?.let { "v$it" } ?: "", m.createdAt.take(10), labelsCell(m.labels)) } +
                    TextModels.names().map { listOf(it, "text", "", "", "") } +
                    SpeechModels.names().map { listOf(it, "speech", "", "", "") } +
                    ImportedList.list(ModelFolders.amp).map { listOf(it.name, "imported", it.format?.label ?: "", it.source?.repo ?: "", labelsCell(it.labels)) }
                if (rows.isEmpty()) println("No models in ${ModelFolders.amp.root.shown}") else printTable(rows)
                Amie.EXIT_OK
            }
            "remove" -> {
                val name = flags.requireWord(0, "the model's name") ?: return Amie.EXIT_USAGE
                val folders = ModelFolders.amp
                // What goes, by kind, the folder named for the message; a speech model by its exact folder, not the prefix find() allows
                val (kind, dir) = when {
                    hasModule(name) -> "intent" to (readRegistry().modules[name]?.let { File(modulesDir, it) } ?: File(modulesDir, name))
                    TextModels.has(name) -> "text" to TextModels.folder(name)
                    name in SpeechModels.names() -> "speech" to File(folders.speech, name)
                    ImportedModels.has(name) -> "imported" to File(folders.imported, name)
                    else -> return fail("No model '$name' in ${folders.root.shown}; model list names them")
                }
                HeadlessAmie.terminate(name)
                ModelServer.stop(name, persist = false) // nothing written about a model that is going; deleteModule stops an intent model's serving itself
                val gone = when (kind) {
                    "intent" -> deleteModule(name)
                    "imported" -> ImportedList.delete(folders, name)
                    else -> dir.deleteRecursively()
                }
                if (!gone) return fail("'$name' could not be removed entirely; what is left is in ${dir.shown}")
                println("$kind model '$name' removed: ${dir.shown} is gone")
                Amie.EXIT_OK
            }
            "build" -> {
                val data = flags.requireWord(0, "the data file, a csv with a text and a label column") ?: return Amie.EXIT_USAGE
                val file = File(data)
                if (!file.isFile) return fail("No file $data")
                val name = flags.valueOf("--name") ?: file.nameWithoutExtension
                val test = flags.valueOf("--test")
                val code = buildString {
                    append("val data = source(${file.absolutePath.quoted()})\n")
                    if (test != null) append("relate(data, ${File(test).absolutePath.quoted()})\n")
                    append("modelBuilder(${name.quoted()}, data)\n")
                }
                Output.withInternalShown { ScriptRun.runCode(code, options.env, "build") }
            }
            "metric" -> {
                val name = flags.requireWord(0, "the model's name") ?: return Amie.EXIT_USAGE
                if (!moduleExists(name)) return fail("No built model '$name'; model list names them")
                val file = java.io.File(org.example.project.components.terminal.window.controller.modules.moduleDir(name), "metrics.json")
                if (flags.has("--file")) { println(file.path); return Amie.EXIT_OK }
                if (!file.isFile) return fail("'$name' has no metrics.json yet: it is written when the model is built")
                if (!flags.has("--last")) { println(file.readText().trimEnd()); return Amie.EXIT_OK }
                val json = kotlinx.serialization.json.Json { prettyPrint = true }
                val runs = (json.parseToJsonElement(file.readText()) as? kotlinx.serialization.json.JsonObject)?.get("runs") as? kotlinx.serialization.json.JsonArray
                val last = runs?.lastOrNull() ?: return fail("${file.path} holds no build yet")
                println(json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), last))
                Amie.EXIT_OK
            }
            "validate" -> {
                val name = flags.requireWord(0, "the model's name") ?: return Amie.EXIT_USAGE
                if (!moduleExists(name)) return fail("No built model '$name'; model list names them")
                Output.withInternalShown { MetricsReport.load(name).print() }
                Amie.EXIT_OK
            }
            "serve" -> {
                val name = flags.requireWord(0, "the model's name") ?: return Amie.EXIT_USAGE
                if (!moduleExists(name)) return fail("No built model '$name'; model list names them")
                val port = flags.valueOf("--port")?.let { it.toIntOrNull() ?: return failUsage("--port needs a number") }
                val settings = readServing(name).with(port, flags.valueOf("--host"), flags.valueOf("--path"), null, null, null)
                val started = Output.withInternalShown { ModelServer.start(name, settings) }
                if (!started) return fail("'$name' could not start serving")
                println("'$name' answers ${settings.methods.joinToString("/")} at ${settings.url(name)}")
                Serving.waitWhileListening()
                Amie.EXIT_OK
            }
            "info" -> {
                val name = flags.requireWord(0, "the model's name") ?: return Amie.EXIT_USAGE
                val m = builtModels().firstOrNull { it.first == name }?.second
                when {
                    m != null -> printInfo(name, m)
                    TextModels.has(name) -> println("$name: a text model of ${TextModels.folder(name).shown}; initMod(\"$name\").answer(text) asks it")
                    SpeechModels.has(name) -> println("$name: a speech model of ${ModelFolders.amp.speech.shown}")
                    else -> ImportedList.list(ModelFolders.amp).firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { i -> printImported(i) }
                        ?: return fail("No model '$name'; model list names them")
                }
                Amie.EXIT_OK
            }
            else -> null
        }
    }

    /**
     * An imported model as its folder holds it: where it came from, its model files by role (an encoder and decoder pair
     * named part by part), the files beside them, its tokenizer, the tokens its config names, its labels, and what
     * initMod gives for it
     */
    private fun printImported(i: ImportedModel) {
        println("${i.name}: imported${i.source?.let { " from ${it.repo} (${it.revision.take(8)}), ${it.downloadedAt.take(10)}" } ?: ""}")
        println("  format: ${i.format?.label ?: "unknown"}, ${bytes(i.bytes)} in ${i.dir.shown}")
        i.task?.let { println("  task: $it") }
        val scan = ModelFormats.scan(i.dir)
        fun shown(f: File) = "${f.relativeTo(i.dir).invariantSeparatorsPath} (${bytes(f.length())})"
        val pair = EncoderDecoderModel.of(scan)
        val models = scan.candidates.map { it.file }
        if (pair != null) {
            val (encoder, decoder, withPast) = pair
            println("  encoder: ${shown(encoder)}")
            println("  decoder: ${shown(decoder)}")
            withPast?.let { println("  decoder with past: ${shown(it)}") }
            val others = models - setOfNotNull(encoder, decoder, withPast)
            if (others.isNotEmpty()) println("  other model files: ${others.joinToString { shown(it) }}")
        } else if (models.isNotEmpty()) {
            println("  model: ${shown(models.first())}" + if (models.size > 1) "; also ${models.drop(1).joinToString { shown(it) }}" else "")
        }
        val companions = i.dir.walkTopDown().filter { it.isFile && it !in models && it.name != ImportedList.SOURCE }.sortedBy { it.path }.toList()
        if (companions.isNotEmpty()) println("  with: ${companions.joinToString { shown(it) }}")
        val tokenizer = companions.firstOrNull { it.name == "tokenizer.json" }
        println("  tokenizer: " + (tokenizer?.let { "${it.relativeTo(i.dir).invariantSeparatorsPath}; text goes in as its ids" + if (pair != null) ", .tokens(text) and .text(ids) use it" else "" } ?: "none; the model takes tensors, or numbers read from the text"))
        val configs = listOf("generation_config.json", "config.json").mapNotNull { n -> companions.firstOrNull { it.name == n }?.readText() }
        fun token(vararg keys: String) = keys.firstNotNullOfOrNull { key -> configs.firstNotNullOfOrNull { Regex("\"$key\"\\s*:\\s*\\[?\\s*(\\d+)").find(it)?.groupValues?.get(1)?.let { v -> "$v ($key)" } } }
        val start = token("decoder_start_token_id", "bos_token_id")
        val end = token("eos_token_id")
        if (start != null || end != null) println("  tokens: " + listOfNotNull(start?.let { "start $it" }, end?.let { "end $it" }).joinToString(", "))
        if (i.labels.isNotEmpty()) println("  labels: ${i.labels.joinToString(", ")}")
        println("  initMod(\"${i.name}\"): " + when {
            pair != null -> "an encoder and decoder pair: .encoder and .decoder are its sessions (.decoderWithPast too when the file is there), .inputs names their tensors, .startToken and .endToken bound the decoding; a script wires the two (amie docs, the pairs section)"
            i.labels.isNotEmpty() -> ".predict(text) the label, .scores(text) every label with its score, .inputs/.outputs/.run(…) the tensors"
            else -> ".inputs and .outputs name its tensors, .run(mapOf(…)) feeds them; .inference(text) when a tokenizer fills its inputs"
        })
    }

    /** The built intent models by name, their manifests read as stored */
    private fun builtModels(): List<Pair<String, ModuleManifest>> {
        val registered = readRegistry().modules.entries.associate { (name, folder) -> folder to name }
        return modulesDir.listFiles { f -> f.isDirectory && File(f, "module.json").isFile }.orEmpty().sortedBy { it.name }
            .mapNotNull { dir -> runCatching { readStoredManifest(dir) }.getOrNull()?.let { (registered[dir.name] ?: dir.name) to it } }
    }

    private fun printInfo(name: String, m: ModuleManifest) {
        println("$name: an intent model" + (m.description?.let { ", $it" } ?: ""))
        println("  labels: ${m.labels.joinToString(", ")}")
        println("  encoder: ${m.encoder}, ${m.format}, ${m.inputDim} in, ${m.outputDim} out")
        println("  version: ${m.version ?: "none kept"}" + (m.kind?.let { " ($it)" } ?: "") + ", built ${m.createdAt.take(19).replace('T', ' ')}")
        m.data?.let { println("  data: $it" + (m.trainRows?.let { r -> ", $r training rows" } ?: "") + (m.validationRows?.let { r -> ", $r validation rows" } ?: "")) }
        m.architecture?.let { println("  head: $it" + (m.epochs?.let { e -> ", $e epochs" } ?: "") + (m.learningRate?.let { r -> ", rate $r" } ?: "")) }
        m.trainAccuracy?.let { println("  training accuracy: ${"%.3f".format(it)}" + (m.finalLoss?.let { l -> ", final loss ${"%.4f".format(l)}" } ?: "")) }
        if (m.fineTunedOn.isNotEmpty()) println("  fine-tuned on: ${m.fineTunedOn.joinToString(", ")}")
        MetricsReport.load(name).runs.lastOrNull()?.let { r -> println("  last validation: accuracy ${"%.3f".format(r.accuracy)}, macro F1 ${"%.3f".format(r.macroF1)} on ${r.validation} (${r.validationSize} rows)") }
        ModelGroups.ofModel(name).takeIf { it.isNotEmpty() }?.let { println("  groups: ${it.joinToString(", ")}") }
        val serving = readServing(name)
        println("  serving: ${serving.url(name)}" + if (ModelServer.isListening(name)) " (listening)" else "")
    }
}

object GroupsCommand : Command("group", "Model groups: models asked together, kept per env in AMP/env/<env>/groups.json") {
    override val actions = listOf(
        "group list" to "every group of every env, with its models",
        "group add <group> <model>..." to "makes the group, or adds the models to it; --env for another env than the default",
        "group remove <group> [<model>]" to "takes the model out of the group, or removes the group when no model is given",
        "group open <group> [text...]" to "opens the group and asks it: each text given, else every line of standard input; each model's answer, then the vote",
    )
    override val valued = setOf("--env")

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        val env = flags.valueOf("--env") ?: options.env ?: DEFAULT_ENV
        return when (action) {
                "list" -> {
                    val groups = ModelGroups.all()
                    if (groups.isEmpty()) println("No groups; group add <group> <model>... makes one")
                    else printTable(groups.map { g -> listOf(g.name, g.env, g.models.joinToString(", ")) })
                    Amie.EXIT_OK
                }
                "add" -> {
                    val group = flags.requireWord(0, "the group's name") ?: return Amie.EXIT_USAGE
                    val models = flags.words.drop(1)
                    if (models.isEmpty()) return failUsage("add needs the models to put in '$group'")
                    val made = ModelGroups.create(group, models, env)
                    println("'${made.name}' in env '${made.env}' holds ${made.models.joinToString(", ")}")
                    Amie.EXIT_OK
                }
                "remove" -> {
                    val group = flags.requireWord(0, "the group's name") ?: return Amie.EXIT_USAGE
                    val model = flags.words.getOrNull(1)
                    val done = if (model == null) ModelGroups.delete(group, env) else ModelGroups.remove(group, model, env)
                    if (!done) return fail("No group '$group' in env '$env'; group list names them")
                    println(if (model == null) "'$group' removed from env '$env'; its models stay" else "'$model' taken out of '$group'")
                    Amie.EXIT_OK
                }
                "open" -> {
                    val group = flags.requireWord(0, "the group's name") ?: return Amie.EXIT_USAGE
                    if (ModelGroups.find(group, env) == null && ModelGroups.find(group) == null) return fail("No group '$group'; group list names them")
                    val swarm = Output.withInternalShown { ModelGroups.open(group) }
                    try {
                        val texts = flags.words.drop(1).ifEmpty { generateSequence { readLine() }.toList() }
                        // One ask a text: each model's answer is a console line of the swarm's own, then the vote
                        texts.filter { it.isNotBlank() }.forEach { text -> println("vote: ${runCatching { swarm.vote(text) }.getOrElse { "none" }}") }
                    } finally {
                        ModelGroups.closeAll()
                    }
                    Amie.EXIT_OK
                }
                else -> null
            }
    }
}

object PackagesCommand : Command("package", "Package jars: those of AMP/pluginDir and the Maven packages of the env, what initMod(\"x.jar\") opens") {
    override val actions = listOf(
        "package list" to "the jars of AMP/pluginDir with their metadata, and the env's Maven packages",
        "package search [query] [--repo owner/name]" to "the jars of the GitHub repositories in AMP/github.json (or --repo's) whose name, or description once added, has every word of the query; all of them without one",
        "package add <file.jar | x.jar>" to "checks the jar has a package class and copies it into AMP/pluginDir; a name that is no file is downloaded from the GitHub repositories",
        "package maven <group:module:version>" to "adds the Maven package to the env (the default, or --env's), resolved from Maven Central",
        "package test <x.jar>" to "runs the tests the jar declares on a fresh session, else one call with hello; the report is kept beside it",
        "package info <x.jar>" to "what the jar says of itself and whether it opens here",
        "package config [x.jar]" to "every jar's config files and whether each is filled in; with a jar, its keys and values, a secret hidden, under the hint above each",
        "package config <x.jar> key=value... [--file f] [--all-jars] [--add]" to "sets the keys in the jar's config file that has them; --all-jars sets them as well for every other jar with a file of that name, so one Google client fills every Google package; a key the file lacks is refused unless --add",
        "package config <x.jar> --file f --from <path>" to "copies a file in, such as a credentials JSON",
        "package config <x.jar> --edit" to "opens the jar's config files in \$VISUAL or \$EDITOR, else the Mac's text editor",
        "package setup <x.jar>" to "asks for each key at the terminal with its hint, Enter keeping a value; offers the values another jar already has in a file of that name",
    )
    override val valued = setOf("--env", "--repo", "--file", "--from")

    private val pluginDir: File get() = File(AmpFolder.dir, "pluginDir")
    private const val LABEL = 14

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return when (action) {
            "list" -> {
                val jars = pluginDir.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty().sortedBy { it.name }
                if (jars.isEmpty()) println("No jars in ${pluginDir.shown}")
                else printTable(jars.map { jar ->
                    val meta = PackageMetadata.read(jar)
                    listOf(jar.name, meta?.name ?: "", meta?.version ?: "", "${jar.length() shr 10} KB", meta?.description?.take(60) ?: "")
                })
                val env = ScriptEnvironments.peek(flags.valueOf("--env") ?: options.env ?: DEFAULT_ENV)
                val maven = env.mavenPackages()
                if (maven.isNotEmpty()) {
                    println("Maven packages of env '${env.name}':")
                    printTable(maven.map { listOf("  " + it.declared, it.resolved?.takeIf { r -> r != it.declared } ?: "", it.jar?.name ?: "not cached") })
                }
                Amie.EXIT_OK
            }
            "search" -> search(flags.words.joinToString(" ").trim(), flags.valueOf("--repo"))
            "config" -> PackageConfigCli.config(flags)
            "setup" -> PackageConfigCli.setup(flags)
            "add" -> {
                val path = flags.requireWord(0, "the jar file") ?: return Amie.EXIT_USAGE
                val file = File(path)
                if (!file.isFile && '/' !in path && path.endsWith(".jar", ignoreCase = true)) {
                    val problems = mutableListOf<String>()
                    val fetched = runCatching { JarPackages.fetch(path, problems) }.getOrElse { return fail("$path: ${it.message}") }
                        ?: return fail("No file $path, and no GitHub repository of AMP/github.json has it" + problems.joinToString("") { "; $it" })
                    println("${fetched.name} is in ${pluginDir.shown} (${JarPackages.check(fetched).describe}); initMod(\"${fetched.name}\") opens it")
                    return Amie.EXIT_OK
                }
                if (!file.isFile) return fail("No file $path")
                val check = JarPackages.check(file)
                if (!check.ok) return fail("${file.name}: ${check.describe}")
                pluginDir.mkdirs()
                val target = File(pluginDir, file.name)
                file.copyTo(target, overwrite = true)
                println("${file.name} is in ${pluginDir.shown} (${check.describe}); initMod(\"${file.name}\") opens it")
                Amie.EXIT_OK
            }
            "maven" -> {
                val notation = flags.requireWord(0, "the Maven coordinate, group:module:version") ?: return Amie.EXIT_USAGE
                val env = ScriptEnvironments.open(flags.valueOf("--env") ?: options.env ?: DEFAULT_ENV)
                Output.withInternalShown { env.import(notation) }
                Amie.EXIT_OK
            }
            "test" -> {
                val name = flags.requireWord(0, "the jar's name") ?: return Amie.EXIT_USAGE
                val report = Output.withInternalShown { JarPackages.test(name) }
                if (report.ok) Amie.EXIT_OK else Amie.EXIT_ERROR
            }
            "info" -> {
                val name = flags.requireWord(0, "the jar's name") ?: return Amie.EXIT_USAGE
                val (file, where) = runCatching { JarPackages.resolve(name) }.getOrElse { return fail("$name: ${it.message}") }
                    ?: return fail("No package '$name': not in the env, AMP/pluginDir, as a path or in the GitHub repositories of AMP/github.json")
                if (info(file, where)) Amie.EXIT_OK else Amie.EXIT_ERROR
            }
            else -> null
        }
    }

    /** The jars the GitHub repositories offer, as a table; a jar already in AMP/pluginDir with its version and description */
    private fun search(query: String, only: String?): Int {
        val repos = only?.let { listOf(it) } ?: GitHubJars.settings().repos
        val problems = mutableListOf<String>()
        val listed = repos.associateWith { repo -> runCatching { GitHubJars.list(repo) }.onFailure { problems += "$repo: ${it.message}" }.getOrDefault(emptyList()) }
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        val rows = listed.values.flatten().distinctBy { it.name }.sortedBy { it.name }.map { jar ->
            val local = File(pluginDir, jar.name).takeIf { it.isFile }?.let { PackageMetadata.read(it) to it }
            Triple(jar, local, local?.first?.description.orEmpty())
        }.filter { (jar, _, description) -> words.all { w -> w in jar.name.lowercase().removeSuffix(".jar") || w in description.lowercase() } }
        problems.forEach { System.err.println(Style.error(it)) }
        val from = if (repos.size == 1) repos.single() else "${repos.size} repositories"
        if (rows.isEmpty()) {
            println(if (query.isEmpty()) "No jars in $from" else "No jar in $from matches '$query'")
            val empty = listed.filterValues { it.isEmpty() }.keys
            if (empty.isNotEmpty()) println(Style.dim(
                if (System.getenv("GITHUB_TOKEN").isNullOrBlank() && GitHubJars.settings().token.isNullOrBlank()) "A private repository lists nothing without GITHUB_TOKEN or a \"token\" in AMP/github.json"
                else "Check that ${empty.joinToString(", ")} exist${if (empty.size == 1) "s" else ""} and the token can read ${if (empty.size == 1) "it" else "them"}"
            ))
            return if (problems.isEmpty()) Amie.EXIT_OK else Amie.EXIT_ERROR
        }
        println(Style.dim("${rows.size} jar${if (rows.size == 1) "" else "s"} in $from" + if (query.isEmpty()) "" else " matching '$query'"))
        val width = terminalWidth() - 1
        val cells = rows.map { (jar, local, description) ->
            listOf(jar.name, "${(jar.size + 1023) shr 10} KB", if (repos.size == 1) jar.release ?: "" else jar.repo + (jar.release?.let { " $it" } ?: ""),
                local?.let { (m, _) -> "Added" + (m?.version?.let { v -> " $v" } ?: "") } ?: "", description)
        }
        // A column no jar fills (no releases, nothing added yet) is left out
        val shown = (0 until 4).filter { c -> cells.any { it[c].isNotEmpty() } }
        val widths = (0 until 4).map { c -> cells.maxOf { it[c].length } }
        val paints = listOf<(String) -> String>(Style::accent, { it }, Style::dim, Style::ok)
        cells.forEach { row ->
            val head = shown.joinToString("  ") { c -> paints[c](row[c].padEnd(widths[c])) }
            val used = shown.sumOf { widths[it] + 2 }
            val rest = row[4].let { d -> if (d.length > width - used) d.take((width - used - 1).coerceAtLeast(0)).trimEnd() + "…" else d }
            println((head + "  " + Style.dim(rest)).trimEnd())
        }
        println(Style.dim("amie package info <x.jar> downloads one and says what it holds; amie package add <x.jar> keeps it in AMP/pluginDir"))
        return if (problems.isEmpty()) Amie.EXIT_OK else Amie.EXIT_ERROR
    }

    /** What the jar says of itself and whether it opens here, as labelled rows; true when it opens */
    private fun info(file: File, where: String): Boolean {
        val meta = PackageMetadata.read(file)
        val check = JarPackages.check(file)
        val width = terminalWidth() - 1
        val indent = " ".repeat(2 + LABEL)
        fun wrapped(text: String) = wrap(text, width, indent, indent).removePrefix(indent)
        fun row(label: String, value: String) = println(Style.dim("  " + label.padEnd(LABEL)) + value)
        fun more(value: String) = println(indent + value)

        println(Style.accent(file.name) + (meta?.name?.let { n -> "  " + n + (meta.version?.let { v -> " $v" } ?: "") } ?: ""))
        meta?.description?.let { println(wrap(it, width)) }
        println()
        row("Found in", where)
        more(Style.dim(file.shown))
        row("Size", "${(file.length() + 1023) shr 10} KB")
        JarPackages.origin(file.name)?.let { o -> row("From", (o.repo?.let { "$it  " } ?: "") + Style.dim(o.page.ifBlank { o.url })) }
        meta?.java?.let { row("Java", "$it or newer") }
        row("Class", check.entryClass ?: meta?.entry ?: Style.error("none"))
        val dir = PackageMetadata.configDir(file.name)
        meta?.config?.forEach { c ->
            val d = PackageConfigFiles.Declared(c, File(dir, c.file))
            row("Config", c.file + "  " + PackageConfigCli.state(d))
            c.description?.let { more(Style.dim(wrapped(it))) }
            if (d.file.isFile) more(Style.dim(d.file.shown))
        }
        if (meta?.config?.isNotEmpty() == true) more(Style.dim("amie package setup ${file.name} fills them in"))
        meta?.env?.forEach { v ->
            val set = !System.getenv(v.name).isNullOrEmpty()
            row("Environment", "\$${v.name}  " + when { set -> Style.ok("set"); v.required -> Style.error("not set, required"); else -> Style.dim("not set, optional") })
            v.description?.let { more(Style.dim(wrapped(it))) }
        }
        meta?.tests?.takeIf { it.isNotEmpty() }?.let { tests -> row("Tests", wrapped("${tests.size}: " + tests.joinToString(", ") { it.title })) }
        PackageMetadata.lastReport(file.name)?.let { r -> row("Last test run", (if (r.ok) Style::ok else Style::error)(r.summary())) }
        meta?.flags?.takeIf { it.isNotEmpty() }?.let { flags -> row("Flags", flags.joinToString(", ") { it.text }) }
        meta?.notes?.let { row("Notes", wrapped(it)) }
        if (meta == null) row("Metadata", Style.warn("none: no META-INF/amie/package.json or sidecar"))
        val blockers = PackageMetadata.blockers(file.name, meta)
        row("Opens here", when {
            !check.ok -> Style.error(wrapped("No: ${check.problem}"))
            blockers.isNotEmpty() -> Style.error(wrapped("Not yet: " + blockers.joinToString(", ") { it.name } + " first"))
            else -> Style.ok(if (check.amiePackage) "Yes, implements AmiePackage" else "Yes, run(String) and close() by shape")
        })
        return check.ok
    }
}

object HfCommand : Command("hf", "Hugging Face: models of the Hub that run here, and importing one into AMP/models/imported") {
    override val actions = listOf(
        "hf search <query> [--format f] [--task t] [--limit n]" to "the Hub's models matching the query with a file that runs here: onnx, torchscript, tensorflow (the tf-keras tag) or tflite; the task narrows (text-classification, tts, ner...)",
        "hf info <repo>" to "what a repo holds: its files that run here, the one import takes, its labels",
        "hf import <repo> [--model-name <name>] [--take <number or file>]" to "downloads the repo's model into AMP/models/imported/<name>; initMod(name) opens it. A repo with several files that run here is picked at the terminal with the arrow keys, the one import takes marked (a pair: its encoders and decoders, space takes or leaves one), Enter imports, n leaves; --take picks by number or file name for a pipe or a script. HF_TOKEN in the environment for a gated repo",
    )
    override val valued = setOf("--format", "--task", "--limit", "--model-name", "--take")

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return try {
            when (action) {
                "search" -> {
                    val query = flags.words.joinToString(" ")
                    val format = flags.valueOf("--format")?.let { HfSearchFormat.of(it) ?: return failUsage("--format is one of ${HfSearchFormat.entries.joinToString { f -> f.key }}") } ?: HfSearchFormat.ALL
                    val kind = flags.valueOf("--task")?.let { HfSearchKind.of(it) ?: return failUsage("--task is one of ${HfSearchKind.entries.mapNotNull { k -> k.tag }.joinToString()}") } ?: HfSearchKind.ANY
                    val limit = flags.valueOf("--limit")?.let { it.toIntOrNull() ?: return failUsage("--limit needs a number") } ?: 20
                    val page = runBlocking { HfImports.search(query, format, kind, limit = limit) }
                    if (page.results.isEmpty()) println("Nothing on the Hub matches '$query' with a ${format.label.lowercase()} file")
                    else printTable(page.results.take(limit).map { r ->
                        listOf(r.id, HfSearchKind.describe(r.task) ?: "", "${r.downloads} downloads", "${r.likes} likes", if (r.filesKnown) r.formats.joinToString(", ") { it.label } else "files not listed")
                    })
                    Amie.EXIT_OK
                }
                "info" -> {
                    val input = flags.requireWord(0, "the repo, owner/name or its address") ?: return Amie.EXIT_USAGE
                    val repo = runBlocking { HfImports.lookUp(input) }
                    val listing = repo.listing
                    println("${repo.id} (${repo.sha.take(8)})" + (repo.task?.let { ", ${HfSearchKind.describe(it)}" } ?: "") + if (repo.gated) ", gated" else "")
                    val pick = listing.defaultPick(repo.asked)?.let(listing::pick)
                    listing.candidates.forEach { c ->
                        val mark = when (c) { pick?.candidate -> "  <- what import takes"; in pick?.also.orEmpty() -> "  <- with it, as its pair"; else -> "" }
                        println("  ${c.format.label}: ${c.main.path}" + (c.main.size?.let { " (${it shr 20} MB)" } ?: "") + mark)
                    }
                    if (listing.candidates.isEmpty()) println("  nothing that runs here among its ${repo.files.size} files")
                    repo.config?.describeLabels()?.let { println("  labels: $it") }
                    Amie.EXIT_OK
                }
                "import" -> {
                    val input = flags.requireWord(0, "the repo, owner/name or its address") ?: return Amie.EXIT_USAGE
                    val take = flags.valueOf("--take")
                    val choose: (HfMenu) -> HfPick? = { menu -> if (take != null) menu.choose(take) else chooseAtTerminal(menu) }
                    Output.withInternalShown { HfImports.ensure(input, flags.valueOf("--model-name").orEmpty(), say = { println(it) }, choose = choose) }
                    Amie.EXIT_OK
                }
                else -> null
            }
        } catch (e: HfException) {
            fail(e.message ?: e.problem.name)
        }
    }
}


/**
 * The file, or a pair's files, to take, picked with the arrow keys at the terminal: the marked ones are taken by
 * default, Enter imports, n leaves (null). Without a terminal (a pipe, a script) the menu is printed and the marked
 * ones are taken without asking.
 */
private fun chooseAtTerminal(menu: HfMenu): HfPick? {
    if (System.console() == null) {
        println(menu.text())
        return menu.choose("")
    }
    val pair = menu.pair
    val lines = buildList {
        if (pair != null) {
            add(Picker.Line.Header("encoders"))
            pair.encoders.forEach { add(Picker.Line.Row(menu.items.indexOf(it), menu.label(it))) }
            add(Picker.Line.Header("decoders"))
            pair.decoders.forEach { add(Picker.Line.Row(menu.items.indexOf(it), menu.label(it))) }
        } else menu.items.forEachIndexed { i, c -> add(Picker.Line.Row(i, menu.label(c))) }
    }
    fun numbers(rows: Set<Int>) = rows.sorted().joinToString(" ") { "${it + 1}" }
    val chosen = Picker.pick(
        menu.title(), lines, menu.taken.map { menu.items.indexOf(it) }.toSet(), several = pair != null,
        status = { rows -> runCatching { "Take ${numbers(rows)}, ${bytes(menu.choose(numbers(rows)).size)}" }.getOrElse { Style.error(it.message ?: "not a choice") } },
        footer = menu.footer(),
    ) ?: return null
    return menu.choose(numbers(chosen))
}
