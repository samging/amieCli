package org.example.project.components.terminal.window.controller.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.components.terminal.integrations.amiePilot.BundledModels
import org.example.project.components.terminal.window.controller.script.ScriptTemplates
import org.example.project.components.terminal.window.controller.databases.DatabaseConfig
import org.example.project.components.terminal.window.controller.databases.Databases
import org.example.project.components.terminal.window.controller.envBuilder.EnvLock
import org.example.project.components.terminal.window.controller.envBuilder.EnvPlan
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.envBuilder.envRoot
import org.example.project.components.terminal.window.controller.jetbrainDLBuilder.LabelQueue
import org.example.project.components.terminal.window.controller.modules.DUMMY_MODEL
import org.example.project.components.terminal.window.controller.modules.ModelGroup
import org.example.project.components.terminal.window.controller.modules.ModelGroups
import org.example.project.components.terminal.window.controller.modules.ModuleRegistry
import org.example.project.components.terminal.window.controller.modules.SAMPLE_DATA
import org.example.project.components.terminal.window.controller.modules.SCRIPT_ROWS
import org.example.project.components.terminal.window.controller.runtime.SpeechModels
import org.example.project.components.terminal.window.controller.runtime.TextModels
import org.example.project.components.terminal.window.controller.modules.hasModule
import org.example.project.components.terminal.window.controller.modules.ensureDummyModule
import org.example.project.components.terminal.window.controller.modules.ensureSampleData
import org.example.project.components.terminal.window.controller.modules.modulesDir
import org.example.project.components.terminal.window.controller.modules.readModule
import org.example.project.components.terminal.window.controller.modules.readRegistry
import org.example.project.components.terminal.window.controller.pluginEngine.JarPackages
import org.example.project.components.terminal.window.controller.pluginEngine.PackageMetadata
import org.example.project.components.terminal.window.controller.sourceFile.DataSource
import org.example.project.getDirectory
import org.example.project.util.Secrets
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.example.project.amp.ModelFolders

/** What can change about an export; the tests point [modelsRoot] and [classPath] at small stand-ins */
data class ExportOptions(
    // Write the bundle even though something could not be included, recording it in the manifest and the README
    val allowMissing: Boolean = false,
    // Look for a jar on the Amie server or GitHub when it isn't on this machine
    val fetchJars: Boolean = true,
    val modelsRoot: File = BundledModels.root,
    // The app's own jars and class folders; empty takes the running app's
    val classPath: List<File> = emptyList(),
    // Compiled to classes any Java 21 runs, or the script with the compiler beside it
    val mode: ExportMode = ExportMode.COMPILED,
    // Templates to carry besides those the script starts, by name or id: entry points of their own in the bundle
    val templates: Set<String> = emptySet(),
    // The env's Maven packages and the package jars go in; false leaves them out and lists them in require.txt
    val withJars: Boolean = true,
)

/** A file going into the bundle as it is; [to] is relative to the bundle. A jar with [keep] goes with only the entries it says */
data class BundleCopy(val from: File, val to: String, val keep: ((String) -> Boolean)? = null) {
    // What it comes to in the bundle: a trimmed jar's kept entries, as they are stored in it
    val size: Long by lazy {
        val kept = keep ?: return@lazy from.length()
        java.util.zip.ZipFile(from).use { jar -> jar.entries().asSequence().filter { kept(it.name) }.sumOf { it.compressedSize.coerceAtLeast(0) } }
    }
}

/**
 * All that an export comes to, worked out without writing anything: the Templates pane shows it, the command line
 * prints it, and [ScriptExporter.write] carries it out. [problems] stop the export until they are dealt with.
 */
class ExportPlan(
    val name: String,
    val source: String,
    val code: String,
    val needs: ScriptNeeds,
    val copies: List<BundleCopy>,
    // Files the export writes itself: what is safe to carry of one that isn't, as it is
    val generated: Map<String, String>,
    // Where the app's own classes are: class folders of a Gradle run, and in a compiled bundle the app's jars too
    val classFolders: List<File>,
    val problems: List<String>,
    val manifest: BundleManifest,
    val mode: ExportMode = ExportMode.SCRIPT,
    // What a compiled bundle's scripts compile against: the app's class path as the export sees it
    val classPath: List<File> = emptyList(),
    // The code of each template going in, by id, as it is compiled or carried
    val templateCode: Map<String, String> = emptyMap(),
) {
    val bytes: Long get() = copies.sumOf { it.size } + generated.values.sumOf { it.length.toLong() }
    val compiled: Boolean get() = mode == ExportMode.COMPILED

    /** The same plan with [manifest], as write fills it in */
    fun withManifest(manifest: BundleManifest) = ExportPlan(name, source, code, needs, copies, generated, classFolders, problems, manifest, mode, classPath, templateCode)
}

object ScriptExporter {
    const val AMP = "AMP"
    const val MODELS = "models/bert"
    const val LIB = "lib"
    const val SECRETS_FILE = "secrets.env"

    /** What a bundle exported with --no-jars needs put in place: one line a file, its path under the bundle then what it is */
    const val REQUIRE_FILE = "require.txt"

    internal fun requireText(required: Collection<BundleRequirement>): String = buildString {
        appendLine("# This bundle was exported without its jars. Put each file at the path given, relative to this folder, before running it:")
        appendLine("# a Maven package's jar from the env that exported it (AMP/env/caches of that machine) or from Maven Central,")
        appendLine("# a package jar from AMP/pluginDir of that machine. run.jar --check says which are still missing.")
        val width = required.maxOf { it.path.length }
        required.forEach { appendLine(it.path.padEnd(width) + "  " + it.what) }
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    // Never copied, whatever a script says
    private val sensitiveName = Regex(
        """(?i)^(session\.json|keycloak\.json|github\.json|databases\.json|\.env(\..+)?|secrets?(\.\w+)?|credentials?(\.\w+)?|client_secret.*\.json|tokens?(\.\w+)?|.*\.(pem|key|p12|pfx|jks|keystore)|id_(rsa|dsa|ecdsa|ed25519))$""",
    )
    private val secretInConfig = Regex("""(?i)"?(password|passwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|private[_-]?key|client[_-]?secret)"?\s*[:=]\s*"?[^"\s,}]{4,}""")

    // In a model's folder: what was asked of it, and what it dropped from its data, stay on this machine
    private val modelLeftOut = Regex("""^(sessions\.log|incoming\.jsonl|access(\.\d+)?\.log|dropped\.csv|icon\..+)$""")
    private val modelFoldersLeftOut = setOf("plots")

    // A jar Compose's packaging renamed carries a hash: skiko-awt-0.8.18-1a2b…f.jar
    private val packagedHash = Regex("""-[0-9a-f]{32}(?=\.jar$)""")
    private val versionPart = Regex("""-\d.*$""")

    // The UI, TensorFlow and Gradle: the jars of the app no script's run reaches, about 800 MB of it
    private val notInBundle = Regex(
        "^(" + listOf(
            "gradle-.*", "groovy(-.*)?", "javaparser-.*",
            "tensorflow(-.*)?", "libtensorflow.*", "mkl-dnn.*", "javacpp.*", "kotlin-deeplearning-.*", "dataset-jvm-.*", "impl-jvm-.*",
            "jewel-.*", "kodeview-.*", "highlights-.*", "skiko-.*", "desktop-jvm-.*", "library-desktop-.*",
            "ui-.*-desktop-.*", "ui-desktop-.*", "foundation-.*desktop-.*", "material.*-desktop-.*", "animation-.*desktop-.*",
            "runtime-desktop-.*", "runtime-saveable-desktop-.*", "navigation-.*", "lifecycle-.*", "savedstate-.*",
            "core-bundle-desktop-.*", "core-common-.*", "collection-jvm-.*", "annotation-jvm-.*", "kotlinx-coroutines-swing-.*",
        ).joinToString("|") + ")\\.jar$",
    )

    /** Works out a bundle for [code]. Nothing is written, apart from what a Run of it would have done to the env */
    fun plan(code: String, name: String, source: String, options: ExportOptions = ExportOptions()): ExportPlan = Planner(code, name, source, options).plan()

    /** The bundle's folder name for a script's [name] */
    fun folderName(name: String): String = name.trim().replace(Regex("""[^A-Za-z0-9._-]+"""), "-").trim('-').ifEmpty { "script" } + "-bundle"

    /** The first bundle folder for [name] under ~/amie-bundles that isn't taken: where Export… and exportScript put one by default */
    fun freeFolder(name: String): File {
        val base = File(System.getProperty("user.home"), "amie-bundles")
        val folder = folderName(name)
        return generateSequence(1) { it + 1 }.map { File(base, if (it == 1) folder else "$folder-$it") }.first { !it.exists() }
    }

    /** The jar a compiled bundle's scripts are in, under lib/; loaded over the env, never on run.jar's class path */
    const val SCRIPTS_JAR = "scripts.jar"

    /** The exact command that starts the bundle at [out]; [template] names a template to run instead of main */
    fun javaLine(out: File, template: String? = null): String {
        val jar = File(out, "run.jar").path
        val quoted = if (jar.any { it.isWhitespace() }) "\"$jar\"" else jar
        return "java -jar $quoted" + (template?.let { " --template \"$it\"" } ?: "")
    }

    /**
     * Carries out [plan] into [out], which must be empty or not there yet; [progress] is told each step and how far
     * along it is. A compiled bundle's scripts are compiled first, so one that doesn't compile leaves nothing behind
     * and its errors come as an [ExportFailed].
     */
    fun write(plan: ExportPlan, out: File, progress: (String, Float) -> Unit = { _, _ -> }): File {
        require(!out.exists() || out.isDirectory && out.list().isNullOrEmpty()) { "$out isn't empty: a bundle goes in a new folder" }
        val compiled = if (plan.compiled) compileAll(plan, progress) else emptyMap()
        out.mkdirs()
        val total = plan.bytes.coerceAtLeast(1)
        var done = 0L
        plan.copies.forEach { copy ->
            progress(copy.to, done.toFloat() / total)
            val target = File(out, copy.to)
            target.parentFile.mkdirs()
            val keep = copy.keep
            if (keep != null) CompiledRuntime.trim(copy.from, keep, target)
            else Files.copy(copy.from.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            done += copy.size
        }
        plan.generated.forEach { (path, text) ->
            File(out, path).apply { parentFile.mkdirs() }.writeText(text)
        }
        progress("run.jar", 1f)
        if (plan.compiled) {
            CompiledScripts.writeJar(compiled.values, File(out, "$LIB/$SCRIPTS_JAR"))
            if (plan.classFolders.isNotEmpty()) CompiledRuntime.packReachable(plan.classFolders, CompiledRuntime.roots, compiled.values, File(out, "$LIB/app-classes.jar"))
        } else if (plan.classFolders.isNotEmpty()) packClasses(plan.classFolders, File(out, "$LIB/app-classes.jar"))
        writeRunJar(File(out, "run.jar"), File(out, LIB).list()?.sorted().orEmpty().filter { it != SCRIPTS_JAR }, plan.copies.map { File(it.to).name }.filter { it.endsWith(".jar") })
        val manifest = plan.manifest.copy(classes = compiled.mapValues { it.value.className })
        BundleFiles.write(plan.withManifest(manifest), out)
        val files = out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).invariantSeparatorsPath }
            .filter { it != SECRETS_FILE && it != BundleManifest.FILE_NAME }.sorted().toList()
        val listed = manifest.copy(files = files.map { BundleFile(it, File(out, it).length(), sha256(File(out, it))) })
        BundleManifest.write(listed, File(out, BundleManifest.FILE_NAME))
        return out
    }

    // main.kts and each template as classes, "main" and the templates' ids; a path the export moved is compiled as its place under AMP/
    private fun compileAll(plan: ExportPlan, progress: (String, Float) -> Unit): Map<String, CompiledScript> {
        val envJars = runCatching { ScriptEnvironments.peek(plan.needs.env.envName).jars() }.getOrDefault(emptyList())
        val classPath = plan.classPath + envJars
        val problems = mutableListOf<String>()
        val compiled = linkedMapOf<String, CompiledScript>()
        fun compile(entry: String, fileName: String, code: String) {
            progress("compiling $fileName", 0f)
            runCatching { CompiledScripts.compile(pointInsideAmp(code, plan.manifest), fileName, classPath) }
                .onSuccess { compiled[entry] = it }
                .onFailure { if (it is ExportFailed) problems += it.problems else problems += "$fileName: ${it.message ?: it.javaClass.simpleName}" }
        }
        compile("main", "main.kts", plan.code)
        plan.templateCode.forEach { (id, code) -> compile(id, CompiledScripts.templateFile(id), code) }
        if (problems.isNotEmpty()) throw ExportFailed(problems)
        return compiled
    }

    /** A path the script names from this machine, as its place under the bundle's AMP/, where a relative path is looked for */
    internal fun pointInsideAmp(code: String, manifest: BundleManifest): String =
        manifest.paths.entries.fold(code) { text, (named, inAmp) -> text.replace(quoted(named), quoted(inAmp)) }

    private fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun writeRunJar(jar: File, inLib: List<String>, order: List<String>) {
        // In the app's class path order: the first of two versions of a class wins
        val ordered = order.filter { it in inLib } + (inLib - order.toSet())
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name.MAIN_CLASS] = "org.example.project.components.terminal.window.controller.export.BundleRunner"
        manifest.mainAttributes[Attributes.Name.CLASS_PATH] = ordered.joinToString(" ") { "$LIB/$it" }
        manifest.mainAttributes[Attributes.Name("Enable-Native-Access")] = "ALL-UNNAMED"
        JarOutputStream(jar.outputStream().buffered(), manifest).close()
    }

    // A Gradle run has class folders where a packaged app has jars
    private fun packClasses(folders: List<File>, jar: File) {
        jar.parentFile.mkdirs()
        val seen = mutableSetOf<String>()
        JarOutputStream(jar.outputStream().buffered()).use { out ->
            // With no manifest of its own the script compiler would not see this jar on the class path
            out.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            out.write("Manifest-Version: 1.0\n\n".toByteArray())
            out.closeEntry()
            seen += "META-INF/MANIFEST.MF"
            folders.forEach { folder ->
                folder.walkTopDown().filter { it.isFile }.forEach { file ->
                    val path = file.relativeTo(folder).invariantSeparatorsPath
                    if (seen.add(path)) {
                        out.putNextEntry(JarEntry(path))
                        file.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun sensitive(file: File): Boolean = sensitiveName.matches(file.name)

    // Text that says password = something, past a first megabyte nobody writes a config file that long
    internal fun holdsSecret(file: File): Boolean =
        runCatching { file.inputStream().use { String(it.readNBytes(1 shl 20)) } }.getOrDefault("").let(secretInConfig::containsMatchIn)

    /** The file name of a jar on a class path: without the hash of Compose's packaging, so two copies of one jar can be told */
    internal fun jarName(file: File): String = file.name.replace(packagedHash, "")

    /** The running app's jars a script can reach, the first of two versions of one artifact, and its class folders */
    internal fun runtimeJars(classPath: List<File>): Pair<List<File>, List<File>> {
        val (folders, jars) = classPath.filter { it.exists() }.partition { it.isDirectory }
        val seen = mutableSetOf<String>()
        return jars.filter { it.extension == "jar" && !notInBundle.matches(jarName(it)) }
            .filter { seen.add(jarName(it).replace(versionPart, "")) } to folders
    }

    private class Planner(val code: String, val name: String, val source: String, val options: ExportOptions) {
        val amp = File(getDirectory().toString())
        val copies = linkedMapOf<String, BundleCopy>()
        val generated = linkedMapOf<String, String>()
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val leftOut = mutableListOf<String>()
        val required = linkedMapOf<String, BundleRequirement>()
        // A file the bundle needs that --no-jars leaves out: noted for require.txt instead of copied
        fun require(to: String, what: String) { required.putIfAbsent(to, BundleRequirement(to, what)) }
        val secrets = linkedMapOf<String, BundleSecret>()
        val paths = linkedMapOf<String, String>()
        val relations = linkedMapOf<String, String>()
        val data = linkedSetOf<String>()
        val encoders = linkedSetOf<File>()
        val models = mutableListOf<BundleModel>()
        val templates = linkedSetOf<String>()
        val templateNames = linkedMapOf<String, String>()
        val templateCode = linkedMapOf<String, String>()
        val templateImports = linkedSetOf<String>()
        // A name initMod opens as a speech model (whisper, voice, a folder of AMP/models/speech) is no intent model: speech.jar
        // runs it and carries the speech models along, so the bundle needs that jar instead. The same order as initMod's
        val reached = reach(ScriptScan.scan(code))
        val speechModels = reached.models.filter { !hasModule(it) && !TextModels.has(it) && SpeechModels.has(it) }.toSet()
        val needs = if (speechModels.isEmpty()) reached else reached.copy(models = reached.models - speechModels, packages = reached.packages + "speech.jar")

        fun copy(from: File, to: String, keep: ((String) -> Boolean)? = null) { copies.putIfAbsent(to, BundleCopy(from, to, keep)) }
        fun inAmp(path: String) = "$AMP/$path"
        fun secret(name: String, why: String, required: Boolean = true) {
            val known = secrets[name]
            secrets[name] = BundleSecret(name, if (known == null) why else "${known.why}; $why", required || known?.required == true)
        }

        fun plan(): ExportPlan {
            scanned()
            val groups = groups()
            val modelNames = needs.models + groups.flatMap { it.models }
            models(modelNames.toSet())
            encoders(modelNames.isNotEmpty() || needs.builtModels.isNotEmpty())
            val env = env()
            packages()
            val databases = databases()
            files()
            trainingFiles(modelNames.toSet())
            backend()
            if (needs.shellCommands.isNotEmpty()) notes += "it runs shell commands, which aren't carried: ${needs.shellCommands.joinToString { "`$it`" }}. What they call has to be on the machine it runs on"
            val classPath = options.classPath.ifEmpty { System.getProperty("java.class.path").split(File.pathSeparator).map(::File) }
            val compiled = options.mode == ExportMode.COMPILED
            val (jars, folders) = if (compiled) CompiledRuntime.runtimeJars(classPath, needs) else runtimeJars(classPath)
            if (compiled) {
                if (jars.none { CompiledRuntime.baseName(it) == "kotlin-stdlib" }) problems += "the app's jars are not on this class path, so the runner has nothing to run on"
                jars.forEach { copy(it, "$LIB/${jarName(it)}", CompiledRuntime.keeps(it)) }
                notes += "ONNX Runtime and the tokenizers go with the native libraries of ${CompiledRuntime.platform()} only; for another platform export there, or put the whole onnxruntime and tokenizers jars from Maven Central in lib/"
                if (needs.usesBackend) notes += "it saves state on the Amie server, so the Ktor client goes along"
                if (needs.databases.isNotEmpty() || needs.allDatabases) notes += "the Postgres driver goes along for its databases"
            } else {
                if (jars.none { it.name.startsWith("kotlin-compiler-embeddable") }) problems += "the app's jars are not on this class path, so the runner has nothing to run on"
                jars.forEach { copy(it, "$LIB/${jarName(it)}") }
            }

            val manifest = BundleManifest(
                name = name,
                source = source,
                scriptSha256 = sha256(code),
                exportedAt = Instant.now().toString(),
                javaMax = if (compiled) BundleManifest.JAVA_ANY else BundleManifest.JAVA_MAX,
                platforms = if (compiled) listOf(CompiledRuntime.platform()) else BundleManifest.PLATFORMS,
                mode = if (compiled) BundleManifest.MODE_COMPILED else BundleManifest.MODE_SCRIPT,
                templateNames = templateNames,
                env = env,
                models = models,
                builtModels = needs.builtModels.filter { n -> models.none { it.name == n } },
                encoders = encoders.map { it.name },
                nerModel = needs.usesNer,
                groups = groups.map { it.name },
                data = data.toList(),
                databases = databases,
                packages = needs.packages.map { File(it).name }.toList(),
                templates = templates.toList(),
                secrets = secrets.values.toList(),
                envVars = (needs.envVars.filter { it !in secrets } + needs.properties.map { "-D$it" }),
                paths = paths,
                relations = relations,
                leftOut = leftOut,
                missing = if (options.allowMissing) problems.toList() else emptyList(),
                required = required.values.toList(),
                notes = notes,
            )
            generated[inAmp("pluginDir/main.kts")] = code
            if (required.isNotEmpty()) generated[REQUIRE_FILE] = requireText(required.values)
            return ExportPlan(name, source, code, needs, copies.values.toList(), generated, folders, problems, manifest, options.mode, classPath, templateCode)
        }

        // What the script reaches and what the templates it starts reach, those they start included: they run in its
        // env. The templates asked for besides go the same way, entry points of their own
        fun reach(own: ScriptNeeds): ScriptNeeds {
            var all = own
            val waiting = ArrayDeque(own.templates + options.templates)
            while (waiting.isNotEmpty()) {
                val wanted = waiting.removeFirst()
                val saved = ScriptTemplates.list()
                val template = saved.firstOrNull { it.name.equals(wanted, ignoreCase = true) } ?: saved.firstOrNull { it.id == wanted }
                val body = template?.let { ScriptTemplates.code(it.id) }
                if (template == null || body == null) {
                    problems += "template '$wanted' isn't saved here"
                    continue
                }
                if (!templates.add(template.id)) continue
                templateNames[template.id] = template.name
                templateCode[template.id] = body
                listOf("kts", "json").forEach { copy(File(ScriptTemplates.dir(), "${template.id}.$it"), inAmp("templates/${template.id}.$it")) }
                val started = ScriptScan.scan(body)
                if (EnvPlan.namesEnv(body) && started.env.envName != own.env.envName) {
                    problems += "template '${template.name}' asks for env '${started.env.envName}' and the script runs in '${own.env.envName}'; a background run stays in the script's env"
                }
                if (started.secretLines.isNotEmpty()) problems += "template '${template.name}' holds what looks like a secret on line ${started.secretLines.joinToString()}: read it with System.getenv"
                templateImports += started.env.imports
                all = all.plus(started, template.name)
                waiting += started.templates
            }
            return all
        }

        fun scanned() {
            needs.unsupported.forEach { problems += it }
            needs.dynamic.forEach { d ->
                val fix = "add a line `// export: ${d.kind} <name>` saying what it will be"
                if (d.kind == "file") notes += "line ${d.line}: ${d.call} reads a file named only when it runs; $fix"
                else problems += "line ${d.line}: ${d.call} names a ${d.kind} only when it runs; $fix"
            }
            if (needs.secretLines.isNotEmpty()) problems += "line${if (needs.secretLines.size == 1) "" else "s"} ${needs.secretLines.joinToString()} of the script hold${if (needs.secretLines.size == 1) "s" else ""} what looks like a password or key, and the script goes into the bundle as it is; read it with System.getenv instead"
            needs.envVars.filter(ScriptScan::looksSecret).forEach { secret(it, "the script reads it") }
            if (DUMMY_MODEL in needs.models) ensureDummyModule()
            if (SAMPLE_DATA in needs.files) ensureSampleData()
        }

        fun groups(): List<ModelGroup> {
            val env = needs.env.envName
            val all = needs.groups.mapNotNull { group ->
                val found = ModelGroups.find(group, env)
                if (found == null && group !in needs.createdGroups) problems += "group '$group' isn't in env '$env'"
                found
            }
            val created = needs.createdGroups.mapNotNull { ModelGroups.find(it, env) }
            val kept = (all + created).distinctBy { it.name }
            if (kept.isNotEmpty()) generated["$AMP/${ModelGroups.dirOf(env).relativeTo(amp).invariantSeparatorsPath}/groups.json"] = json.encodeToString(GroupsFile.serializer(), GroupsFile(kept))
            return kept
        }

        fun models(names: Set<String>) {
            val registry = readRegistry().modules
            val kept = linkedMapOf<String, String>()
            (names + needs.builtModels).forEach { model ->
                // readModule logs a model it can't find as an error, and a text model is none of its
                val found = if (hasModule(model)) runCatching { readModule(model) }.getOrNull() else null
                if (found == null && TextModels.has(model)) {
                    val dir = TextModels.folder(model)
                    dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.extension != "log" }
                        .forEach { copy(it, inAmp(it.relativeTo(amp).invariantSeparatorsPath)) }
                    return@forEach
                }
                if (found == null) {
                    if (model !in needs.builtModels) problems += "model '$model' isn't in AMP/models/intents"
                    return@forEach
                }
                val (dir, manifest) = found
                val folder = dir.relativeTo(amp).invariantSeparatorsPath
                val encoder = encoderOf(manifest.encoder)
                kept[model] = registry[model] ?: dir.name
                dir.walkTopDown().onEnter { it == dir || it.name !in modelFoldersLeftOut }.filter { it.isFile && !it.name.startsWith(".") }.forEach { file ->
                    val path = file.relativeTo(dir).invariantSeparatorsPath
                    val target = inAmp("$folder/$path")
                    when {
                        file.name == "module.json" -> generated[target] = portableManifest(file, encoder.name)
                        path == "serving.json" -> generated[target] = keylessServing(model, file)
                        !modelLeftOut.matches(path) -> copy(file, target)
                    }
                }
                if (needs.usesEncoder) encoders += encoder
                models += BundleModel(model, manifest.version, encoder.name, manifest.labels.size)
            }
            if (kept.isNotEmpty()) generated[inAmp("${modulesDir.relativeTo(amp).invariantSeparatorsPath}/modules.json")] = json.encodeToString(ModuleRegistry.serializer(), ModuleRegistry(kept))
        }

        fun encoderOf(path: String): File {
            val given = File(path)
            if (File(given, "model.onnx").isFile) return given
            val named = File(options.modelsRoot, given.name)
            return if (File(named, "model.onnx").isFile) named else File(options.modelsRoot, BundledModels.ENCODER_NAME)
        }

        // The encoder by its folder's name: on another machine BundledModels finds it under the bundle's models/bert
        fun portableManifest(file: File, encoder: String): String {
            val manifest = json.parseToJsonElement(file.readText()).jsonObject
            return json.encodeToString(JsonElement.serializer(), JsonObject(manifest + ("encoder" to JsonPrimitive(encoder))))
        }

        fun keylessServing(model: String, file: File): String {
            val serving = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrElse { return "{}" }
            val key = serving["apiKey"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull ?: return file.readText()
            val variable = "AMIE_SERVE_${envName(model)}_KEY"
            val name = if (key.startsWith(Secrets.PREFIX)) key.removePrefix(Secrets.PREFIX) else variable
            secret(name, "the key callers of model '$model' send as Authorization: Bearer")
            return json.encodeToString(JsonElement.serializer(), JsonObject(serving + ("apiKey" to JsonPrimitive(Secrets.PREFIX + name))))
        }

        fun encoders(modelsInvolved: Boolean) {
            val root = options.modelsRoot
            // A text model has its own tokenizer and a speech model needs none, so a script that opens only those has no use for one
            val ownTokenizers = (needs.models.isNotEmpty() || speechModels.isNotEmpty()) && needs.models.all(TextModels::has) && needs.builtModels.isEmpty()
            if (needs.usesEncoder && encoders.isEmpty() && !ownTokenizers) encoders += File(root, BundledModels.ENCODER_NAME)
            encoders.forEach { encoder ->
                if (!File(encoder, "model.onnx").isFile) problems += "the encoder ${encoder.name} isn't on this machine (looked in ${encoder.path})"
                else folderFiles(encoder).forEach { copy(it, "$MODELS/${encoder.name}/${it.name}") }
            }
            if (needs.usesNer) {
                if (!File(root, "model.onnx").isFile) problems += "the named-entity model isn't on this machine (looked in ${root.path})"
                else folderFiles(root).forEach { copy(it, "$MODELS/${it.name}") }
            }
            if (!modelsInvolved && !needs.usesNer && !needs.usesEncoder) notes += "no model files: this script opens none"
        }

        fun folderFiles(folder: File) = folder.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name }

        fun readLock(): EnvLock? = runCatching {
            json.decodeFromString(EnvLock.serializer(), File(ModelGroups.dirOf(needs.env.envName), "env.lock.json").readText())
        }.getOrNull()

        fun env(): BundleEnv {
            val plan = needs.env
            var lock = readLock()
            // What the script imports and the env doesn't hold yet comes in now, as it would on a Run
            if (plan.imports.any { it !in lock?.declared.orEmpty() }) {
                runCatching { ScriptEnvironments.open(plan.envName, plan.activeProfile).source(plan) }
                    .onFailure { problems += "env '${plan.envName}' couldn't be resolved: ${it.message}" }
                lock = readLock()
                plan.imports.filter { it !in lock?.declared.orEmpty() }.forEach { problems += "package $it couldn't be resolved into env '${plan.envName}'" }
            }
            templateImports.filter { it !in plan.imports && it !in lock?.declared.orEmpty() && lock?.packages.orEmpty().none { p -> p.coordinate == it } }
                .forEach { problems += "a template it starts imports $it, which env '${plan.envName}' doesn't have, and a background run doesn't change its env: import it in the script too" }
            lock ?: return BundleEnv(plan.envName, plan.activeProfile)
            val dir = ModelGroups.dirOf(plan.envName).relativeTo(amp).invariantSeparatorsPath
            copy(File(ModelGroups.dirOf(plan.envName), "env.lock.json"), inAmp("$dir/env.lock.json"))
            lock.packages.forEach { p ->
                p.files.forEach { path ->
                    val file = File(envRoot, path)
                    if (!options.withJars) require(inAmp("env/$path"), p.coordinate)
                    else if (file.isFile) copy(file, inAmp("env/$path")) else problems += "${p.coordinate}: $path is missing from the env's cache"
                }
            }
            return BundleEnv(plan.envName, plan.activeProfile, lock.declared, lock.packages.size)
        }

        fun localJar(spec: String): File? {
            val fileName = File(spec).name
            val lock = readLock()
            return listOfNotNull(
                lock?.packages?.firstOrNull { it.coordinate == "file:$fileName" }?.files?.firstOrNull()?.let { File(envRoot, it) },
                File(amp, "pluginDir/$fileName"),
                File(ModelGroups.dirOf(needs.env.envName), "libs/$fileName"),
                File(spec),
            ).firstOrNull { it.isFile }
        }

        fun packages() {
            needs.packages.forEach { spec ->
                val jar = localJar(spec)
                    ?: if (options.fetchJars) runCatching { JarPackages.resolve(spec)?.first }.getOrNull() else null
                if (jar == null) {
                    problems += "package $spec isn't on this machine${if (options.fetchJars) " or on the server" else ""}"
                    return@forEach
                }
                val fileName = jar.name
                val pluginDir = File(amp, "pluginDir")
                if (options.withJars) copy(jar, inAmp("pluginDir/$fileName")) else require(inAmp("pluginDir/$fileName"), "package $spec")
                if (spec != fileName) paths[spec] = "pluginDir/$fileName"
                listOf(".package.json", ".source.json", ".tests.json").map { File(pluginDir, "$fileName$it") }.filter { it.isFile }
                    .forEach { copy(it, inAmp("pluginDir/${it.name}")) }
                val config = PackageMetadata.configDir(fileName)
                // The speech models go along with speech.jar under the bundle's AMP/models/speech (when they are not in its
                // config folder still); its speech.properties then names no folder, and the bundle's runner points it at that one
                val speech = if (fileName == "speech.jar") ModelFolders.amp.speech.takeIf { it.isDirectory && !it.canonicalFile.startsWith(config.canonicalFile) } else null
                config.walkTopDown().filter { it.isFile }.forEach { file ->
                    val path = "pluginDir/config/${config.name}/${file.relativeTo(config).invariantSeparatorsPath}"
                    if (sensitive(file) || holdsSecret(file)) leftOut += "$path: it may hold credentials, so it was not copied; put the deployer's own there"
                    else if (speech != null && file.name == "speech.properties" && file.parentFile == config) generated[inAmp(path)] = file.readLines().joinToString("\n", postfix = "\n") { if (it.trimStart().startsWith("models=")) "models=" else it }
                    else copy(file, inAmp(path))
                }
                speech?.walkTopDown()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { copy(it, inAmp("${ModelFolders.Kind.SPEECH.new}/${it.relativeTo(speech).invariantSeparatorsPath}")) }
                val meta = PackageMetadata.read(jar)
                meta?.env?.forEach { secret(it.name, "$fileName reads it${it.description?.let { d -> ": $d" } ?: ""}", required = it.required) }
                meta?.config?.filter { it.required && !File(config, it.file).isFile }?.forEach { problems += "$fileName needs ${it.file} in its config folder, and it isn't there" }
                meta?.java?.takeIf { it > BundleManifest.JAVA_MIN }?.let { notes += "$fileName needs Java $it" }
            }
        }

        fun databases(): List<BundleDatabase> {
            if (needs.databases.isEmpty() && !needs.allDatabases) return emptyList()
            val configured = Databases.configs()
            needs.databases.filter { it !in configured }.forEach { problems += "database '$it' isn't in AMP/databases.json" }
            // A script that lists the connections sees only these in the bundle, unless it names none
            val used = if (needs.allDatabases && needs.databases.isEmpty()) configured else configured.filterKeys { it in needs.databases }
            val safe = used.mapValues { (name, config) -> safeConnection(name, config) }
            generated[inAmp(Databases.file.name)] = json.encodeToString(MapSerializer(String.serializer(), DatabaseConfig.serializer()), safe)
            return safe.map { (name, config) ->
                if (!config.url.startsWith("jdbc:postgresql:") && !config.url.startsWith(Secrets.PREFIX)) notes += "database '$name': a bundle carries the Postgres driver only, and ${config.url.substringBefore("//")} needs its own"
                if (config.url.contains("localhost") || config.url.contains("127.0.0.1")) notes += "database '$name' is at ${config.url}: on another machine, change that in AMP/databases.json"
                BundleDatabase(name, config.url, config.user, listOf(config.url, config.user, config.password).filter { it.startsWith(Secrets.PREFIX) }.map { it.removePrefix(Secrets.PREFIX) })
            }
        }

        // A password never goes in the bundle, whether in its own field or in the url
        fun safeConnection(name: String, config: DatabaseConfig): DatabaseConfig {
            val passwordVariable = if (config.password.startsWith(Secrets.PREFIX)) config.password.removePrefix(Secrets.PREFIX) else "AMIE_DB_${envName(name)}_PASSWORD"
            if (config.password.isNotEmpty()) secret(passwordVariable, "the password of database '$name'")
            listOf(config.url, config.user).filter { it.startsWith(Secrets.PREFIX) }.forEach { secret(it.removePrefix(Secrets.PREFIX), "the connection of database '$name'") }
            val url = config.url
                .replace(Regex("""([?&])password=[^&]*&?""")) { m -> if (m.value.endsWith("&")) m.groupValues[1] else "" }
                .replace(Regex("""(://[^/@:]+):[^/@]+@"""), "$1@")
            if (url != config.url) notes += "database '$name': the password in its url was taken out"
            return config.copy(url = url, password = if (config.password.isEmpty()) "" else Secrets.PREFIX + passwordVariable)
        }

        fun files() {
            needs.files.forEach { file(it, strict = true) }
            needs.literals.forEach { file(it, strict = false) }
        }

        fun file(path: String, strict: Boolean) {
            // shellScript takes ~/, which DataSource doesn't; only a path given to a file API, never any string of the script
            val found = DataSource.locate(path) ?: path.takeIf { strict && it.startsWith("~/") }?.let { File(System.getProperty("user.home"), it.drop(2)) }?.takeIf { it.isFile }
            if (found == null) {
                if (strict) problems += "file $path isn't on this machine"
                else if (File(path).isAbsolute && File(path).isDirectory) notes += "$path is a folder, which isn't copied; add what the script reads from it with `// export: file <path>`"
                return
            }
            if (found.canonicalFile == File(amp, "pluginDir/main.kts").canonicalFile) return
            // A jar the script names is a package: with --no-jars it is listed in require.txt, not carried
            if (!options.withJars && found.extension == "jar") {
                if (found.canonicalFile.parentFile == File(amp, "pluginDir").canonicalFile) require(inAmp("pluginDir/${found.name}"), "package ${found.name}")
                return
            }
            include(found, path)
            relatedTo(found)
        }

        // Under AMP/ it keeps its place; from anywhere else it goes to AMP/files/, and the script is pointed at it there
        fun include(found: File, asNamed: String? = null): String? {
            if (sensitive(found)) {
                problems += "${found.name}: this kind of file is never exported; give the bundle its own and read its path from the environment"
                leftOut += found.path
                return null
            }
            val canonical = found.canonicalFile
            val root = amp.canonicalFile
            val inside = canonical.path.startsWith(root.path + File.separator)
            val relative = if (inside) canonical.relativeTo(root).invariantSeparatorsPath else freeName(canonical)
            copy(canonical, inAmp(relative))
            data += relative
            if (asNamed != null && (!inside || File(asNamed).isAbsolute)) paths[asNamed] = relative
            return relative
        }

        fun freeName(file: File): String {
            val taken = copies.values.filter { it.from != file }.map { it.to }.toSet()
            return generateSequence(0) { it + 1 }
                .map { if (it == 0) "files/${file.name}" else "files/${file.nameWithoutExtension}-$it.${file.extension}" }
                .first { inAmp(it) !in taken }
        }

        // source("train.csv") brings the test file relate() tied to it back, wherever that is
        fun relatedTo(train: File) {
            val saved = runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), File(amp, "relations.json").readText())
            }.getOrDefault(emptyMap())
            val test = saved[train.canonicalPath]?.let(::File)?.takeIf { it.isFile } ?: return
            val from = include(train) ?: return
            val to = include(test) ?: return
            relations[from] = to
        }

        fun trainingFiles(names: Set<String>) {
            names.forEach { model ->
                if (needs.usesQueue) LabelQueue.fileFor(model).takeIf { it.isFile }?.let { include(it) }
                if (needs.usesTrainingData) {
                    val manifest = runCatching { readModule(model)?.second }.getOrNull() ?: return@forEach
                    (listOfNotNull(manifest.data) + manifest.fineTunedOn).filter { it != SCRIPT_ROWS }.distinct().forEach { data ->
                        val file = DataSource.locate(data)
                        if (file != null) include(file) else notes += "model '$model' learned from $data, which isn't here: a fine-tune works on with what the model kept"
                    }
                }
            }
        }

        fun backend() {
            if (!needs.usesBackend) return
            File(amp, "backend.json").takeIf { it.isFile }?.let { copy(it, inAmp(it.name)) }
            val keycloak = File(amp, "keycloak.json")
            if (keycloak.isFile) runCatching {
                val config = json.parseToJsonElement(keycloak.readText()).jsonObject
                if (!config["clientSecret"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) secret("KEYCLOAK_CLIENT_SECRET", "the secret of the amie client, to refresh a token")
                generated[inAmp(keycloak.name)] = json.encodeToString(JsonElement.serializer(), JsonObject(config + ("clientSecret" to JsonPrimitive(""))))
            }.onFailure { problems += "keycloak.json can't be read: ${it.message}" }
            secret("AMIE_REFRESH_TOKEN", "an offline token of the account the script saves its state under (or AMIE_ACCESS_TOKEN, used until it expires)")
            secret("AMIE_ACCESS_TOKEN", "a signed-in token, instead of AMIE_REFRESH_TOKEN", required = false)
        }

        fun envName(name: String) = name.uppercase().replace(Regex("""[^A-Z0-9]+"""), "_").trim('_')
    }

    @Serializable
    private data class GroupsFile(val groups: List<ModelGroup>)
}
