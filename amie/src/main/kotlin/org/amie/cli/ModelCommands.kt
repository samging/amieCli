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

import kotlinx.coroutines.runBlocking
import org.example.project.amp.ModelFolders
import org.example.project.components.terminal.window.controller.envBuilder.DEFAULT_ENV
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.huggingFace.HfException
import org.example.project.components.terminal.window.controller.huggingFace.HfImports
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
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
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

object ModelsCommand : Command("models", "The models of the AMP folder: built intent models, text and speech models, and those imported from Hugging Face") {
    override val actions = listOf(
        "models list" to "every model: its kind, version, date and first labels",
        "models build <data.csv> [--name <name>] [--test <test.csv>]" to "trains an intent model on the labeled rows, as modelBuilder does; the name is the file's unless given",
        "models validate <name>" to "the model's last stored validation: accuracy, macro F1 and each label's figures",
        "models serve <name> [--port <n>] [--host <h>] [--path <p>]" to "serves the model over HTTP until stopped",
        "models info <name>" to "what is known of the model: its labels, encoder, version, how it was trained",
    )
    override val valued = setOf("--name", "--test", "--port", "--host", "--path")

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return when (action) {
            "list" -> {
                // The labels last and shortened, so a model with forty of them doesn't push every column off the screen; models info names them all
                val rows = builtModels().map { (name, m) -> listOf(name, "intent", m.version?.let { "v$it" } ?: "", m.createdAt.take(10), labelsCell(m.labels)) } +
                    TextModels.names().map { listOf(it, "text", "", "", "") } +
                    SpeechModels.names().map { listOf(it, "speech", "", "", "") } +
                    ImportedList.list(ModelFolders.amp).map { listOf(it.name, "imported", it.format?.label ?: "", it.source?.repo ?: "", labelsCell(it.labels)) }
                if (rows.isEmpty()) println("No models in ${ModelFolders.amp.root.path}") else printTable(rows)
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
            "validate" -> {
                val name = flags.requireWord(0, "the model's name") ?: return Amie.EXIT_USAGE
                if (!moduleExists(name)) return fail("No built model '$name'; models list names them")
                Output.withInternalShown { MetricsReport.load(name).print() }
                Amie.EXIT_OK
            }
            "serve" -> {
                val name = flags.requireWord(0, "the model's name") ?: return Amie.EXIT_USAGE
                if (!moduleExists(name)) return fail("No built model '$name'; models list names them")
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
                    TextModels.has(name) -> println("$name: a text model of ${TextModels.folder(name).path}; initMod(\"$name\").answer(text) asks it")
                    SpeechModels.has(name) -> println("$name: a speech model of ${ModelFolders.amp.speech.path}")
                    else -> ImportedList.list(ModelFolders.amp).firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { i ->
                        println("${i.name}: imported${i.source?.let { " from ${it.repo} (${it.revision.take(8)}), ${it.downloadedAt.take(10)}" } ?: ""}")
                        println("  format: ${i.format?.label ?: "unknown"}, ${i.bytes shr 20} MB in ${i.dir.path}")
                        i.task?.let { println("  task: $it") }
                        if (i.labels.isNotEmpty()) println("  labels: ${i.labels.joinToString(", ")}")
                    } ?: return fail("No model '$name'; models list names them")
                }
                Amie.EXIT_OK
            }
            else -> null
        }
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

object GroupsCommand : Command("groups", "Model groups: models asked together, kept per env in AMP/env/<env>/groups.json") {
    override val actions = listOf(
        "groups list" to "every group of every env, with its models",
        "groups add <group> <model>..." to "makes the group, or adds the models to it; --env for another env than the default",
        "groups remove <group> [<model>]" to "takes the model out of the group, or removes the group when no model is given",
        "groups open <group> [text...]" to "opens the group and asks it: each text given, else every line of standard input; each model's answer, then the vote",
    )
    override val valued = setOf("--env")

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        val env = flags.valueOf("--env") ?: options.env ?: DEFAULT_ENV
        return when (action) {
                "list" -> {
                    val groups = ModelGroups.all()
                    if (groups.isEmpty()) println("No groups; groups add <group> <model>... makes one")
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
                    if (!done) return fail("No group '$group' in env '$env'; groups list names them")
                    println(if (model == null) "'$group' removed from env '$env'; its models stay" else "'$model' taken out of '$group'")
                    Amie.EXIT_OK
                }
                "open" -> {
                    val group = flags.requireWord(0, "the group's name") ?: return Amie.EXIT_USAGE
                    if (ModelGroups.find(group, env) == null && ModelGroups.find(group) == null) return fail("No group '$group'; groups list names them")
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

object PackagesCommand : Command("packages", "Package jars: those of AMP/pluginDir and the Maven packages of the env, what initMod(\"x.jar\") opens") {
    override val actions = listOf(
        "packages list" to "the jars of AMP/pluginDir with their metadata, and the env's Maven packages",
        "packages add <file.jar>" to "checks the jar has a package class and copies it into AMP/pluginDir",
        "packages maven <group:module:version>" to "adds the Maven package to the env (the default, or --env's), resolved from Maven Central",
        "packages test <x.jar>" to "runs the tests the jar declares on a fresh session, else one call with hello; the report is kept beside it",
        "packages info <x.jar>" to "what the jar says of itself and whether it opens here",
    )
    override val valued = setOf("--env")

    private val pluginDir: File get() = File(AmpFolder.dir, "pluginDir")

    override fun perform(action: String, flags: Flags, options: Options): Int? {
        return when (action) {
            "list" -> {
                val jars = pluginDir.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty().sortedBy { it.name }
                if (jars.isEmpty()) println("No jars in ${pluginDir.path}")
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
            "add" -> {
                val path = flags.requireWord(0, "the jar file") ?: return Amie.EXIT_USAGE
                val file = File(path)
                if (!file.isFile) return fail("No file $path")
                val check = JarPackages.check(file)
                if (!check.ok) return fail("${file.name}: ${check.describe}")
                pluginDir.mkdirs()
                val target = File(pluginDir, file.name)
                file.copyTo(target, overwrite = true)
                println("${file.name} is in ${pluginDir.path} (${check.describe}); initMod(\"${file.name}\") opens it")
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
                val (file, where) = JarPackages.resolve(name) ?: return fail("No package '$name': not in AMP/pluginDir, the env or as a path")
                println("${file.name}: ${file.length() shr 10} KB, found in $where (${file.path})")
                PackageMetadata.read(file)?.let { m ->
                    m.name?.let { println("  name: $it" + (m.version?.let { v -> " $v" } ?: "")) }
                    m.description?.let { println("  description: $it") }
                    m.java?.let { println("  needs Java $it") }
                    m.entry?.let { println("  package class: $it") }
                    if (m.config.isNotEmpty()) println("  config files: ${m.config.joinToString(", ") { it.toString() }}")
                    if (m.env.isNotEmpty()) println("  environment: ${m.env.joinToString(", ") { it.name + if (it.required) " (required)" else "" }}")
                    if (m.tests.isNotEmpty()) println("  tests: ${m.tests.size}")
                    if (m.flags.isNotEmpty()) println("  flags: ${m.flags.joinToString(", ") { it.text }}")
                } ?: println("  no metadata (META-INF/amie/package.json or a sidecar)")
                JarPackages.origin(file.name)?.let { println("  from: ${it.url}" + (it.version?.let { v -> ", version $v" } ?: "")) }
                val check = JarPackages.check(file)
                println("  ${if (check.ok) "opens here: " else "does not open here: "}${check.describe}")
                PackageMetadata.lastReport(file.name)?.let { println("  last test run: ${it.summary()}") }
                if (check.ok) Amie.EXIT_OK else Amie.EXIT_ERROR
            }
            else -> null
        }
    }
}

object HfCommand : Command("hf", "Hugging Face: models of the Hub that run here, and importing one into AMP/models/imported") {
    override val actions = listOf(
        "hf search <query> [--format f] [--task t] [--limit n]" to "the Hub's models matching the query with a file that runs here: onnx, torchscript, tensorflow (the tf-keras tag) or tflite; the task narrows (text-classification, tts, ner...)",
        "hf info <repo>" to "what a repo holds: its files that run here, the one import takes, its labels",
        "hf import <repo> [--model-name <name>]" to "downloads the repo's model into AMP/models/imported/<name>; initMod(name) opens it. HF_TOKEN in the environment for a gated repo",
    )
    override val valued = setOf("--format", "--task", "--limit", "--model-name")

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
                    val pick = listing.defaultPick(repo.asked)
                    listing.candidates.forEach { c -> println("  ${c.format.label}: ${c.main.path}" + (c.main.size?.let { " (${it shr 20} MB)" } ?: "") + if (c == pick) "  <- what import takes" else "") }
                    if (listing.candidates.isEmpty()) println("  nothing that runs here among its ${repo.files.size} files")
                    repo.config?.describeLabels()?.let { println("  labels: $it") }
                    Amie.EXIT_OK
                }
                "import" -> {
                    val input = flags.requireWord(0, "the repo, owner/name or its address") ?: return Amie.EXIT_USAGE
                    val name = Output.withInternalShown { HfImports.ensure(input, flags.valueOf("--model-name").orEmpty()) { println(it) } }
                    println("initMod(\"$name\") opens it")
                    Amie.EXIT_OK
                }
                else -> null
            }
        } catch (e: HfException) {
            fail(e.message ?: e.problem.name)
        }
    }
}
