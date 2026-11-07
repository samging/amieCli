package org.example.project.components.terminal.window.controller.envBuilder

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Script package environments, resolved the way Gradle resolves `implementation("group:module:version")`:
 *  - declarations are read from the script before it compiles, so imported classes are on its compile and run classpath
 *  - Gradle Module Metadata (.module) is preferred and its JVM runtime variant chosen; POMs are the fallback
 *  - dependencies are transitive (compile/runtime scope, exclusions honoured) and version conflicts pick the highest
 *  - files land in a shared cache, AMP/env/caches/modules-2/files-2.1/<group>/<module>/<version>/, and are never fetched twice
 *  - each env (AMP/env/<name>/env.lock.json) keeps everything ever imported into it, like a virtualenv
 *  - a jar file name ("plugJar.jar") is imported like Gradle's files(...): copied into AMP/env/<name>/libs/
 */

val envRoot: File get() = File(getDirectory().toString(), "env")
private val cacheDir: File get() = File(envRoot, "caches/modules-2/files-2.1")

private val repositories = listOf(
    "https://repo.maven.apache.org/maven2/",
    "https://dl.google.com/dl/android/maven2/",
)

// Already on the app classpath; a second copy in the env would shadow the compiler's own stdlib
private val hostProvided = listOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlin:kotlin-reflect", "org.jetbrains:annotations")

private val envJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

data class Coordinate(val group: String, val module: String, val version: String) {
    val key: String get() = "$group:$module"
    override fun toString() = "$group:$module:$version"

    companion object {
        fun parse(notation: String): Coordinate {
            val parts = notation.trim().split(":")
            require(parts.size == 3 && parts.all { it.isNotBlank() }) { "Expected group:module:version, got '$notation'" }
            return Coordinate(parts[0], parts[1], parts[2])
        }
    }
}

// files are relative to AMP/env
@Serializable
data class EnvPackage(val coordinate: String, val files: List<String>, val dependencies: List<String>, val repository: String? = null)

/** A declared Maven package: what was asked for, what the env holds for it (null when nothing resolved) and its jar */
data class MavenPackage(val declared: String, val resolved: String?, val jar: File?) {
    val declaredVersion: String get() = declared.substringAfterLast(':')
    val resolvedVersion: String? get() = resolved?.substringAfterLast(':')
}

@Serializable
data class EnvLock(
    val name: String,
    val activeProfile: String,
    val declared: List<String> = emptyList(),
    val packages: List<EnvPackage> = emptyList(),
    val updatedAt: String = Instant.now().toString(),
)

/** What a script declares, read before it compiles (the configuration phase) */
data class EnvPlan(val envName: String, val activeProfile: String, val imports: List<String>) {
    companion object {
        // `amie.` is optional: the sandbox prelude exposes implementation/dependency (and environment for SettingsEnv) prefix-free
        private val importCall = Regex("""(?<![\w.])(?:amie\.)?(?:import|implementation|dependency)\(\s*"([^"]+)"\s*\)""")
        private val settingsCall = Regex("""(?<![\w.])(?:amie\.)?(?:import|environment)\(\s*SettingsEnv\(([^)]*)\)\s*\)""")
        private val stringArg = Regex("""(?:(\w+)\s*=\s*)?"([^"]*)"""")

        /**
         * The script with its env set to [env]: the `environment(SettingsEnv(...))` line it has is rewritten,
         * else one is put at the top (after the leading comment lines, so a script that starts with notes keeps
         * them first). The default env is written out too, so the choice is visible in the text.
         */
        fun withEnv(code: String, env: String): String {
            val call = "environment(SettingsEnv(\"$env\"))"
            settingsCall.find(code)?.let { found ->
                // The profile is the second positional argument or the named one, as scan reads it
                val profile = stringArg.findAll(found.groupValues[1]).mapIndexed { i, arg -> arg.groupValues[1].ifEmpty { if (i == 0) "envName" else "activeProfile" } to arg.groupValues[2] }.toList()
                    .firstOrNull { (name, _) -> name == "activeProfile" }?.second
                val rewritten = if (profile != null) "environment(SettingsEnv(\"$env\", \"$profile\"))" else call
                return code.replaceRange(found.range, rewritten)
            }
            val lines = code.lines()
            val lead = lines.takeWhile { it.trimStart().startsWith("//") || it.isBlank() }.size.let { n -> if (n == lines.size) 0 else n }
            return (lines.take(lead) + call + lines.drop(lead)).joinToString("\n")
        }

        /** Whether the script names an env at all: scan reads the default env both when it says so and when it says nothing */
        fun namesEnv(code: String): Boolean = settingsCall.containsMatchIn(code)

        fun scan(code: String): EnvPlan {
            var envName = DEFAULT_ENV
            var profile = DEFAULT_PROFILE
            settingsCall.find(code)?.let { call ->
                stringArg.findAll(call.groupValues[1]).forEachIndexed { i, arg ->
                    when (arg.groupValues[1].ifEmpty { if (i == 0) "envName" else "activeProfile" }) {
                        "envName" -> envName = arg.groupValues[2]
                        "activeProfile" -> profile = arg.groupValues[2]
                    }
                }
            }
            return EnvPlan(envName, profile, importCall.findAll(code).map { it.groupValues[1].trim() }.distinct().toList())
        }
    }
}

const val DEFAULT_ENV = "default"
const val FILE_PREFIX = "file:"
fun isLocalJar(coordinate: String) = coordinate.startsWith(FILE_PREFIX)
const val DEFAULT_PROFILE = "local"

object ScriptEnvironments {
    private val envs = mutableMapOf<String, ScriptEnvironment>()

    /** The envs that exist as folders under AMP/env, the default one first, for a chooser */
    fun names(): List<String> {
        val dirs = envRoot.listFiles { f -> f.isDirectory && f.name != "caches" }?.map { it.name }?.sorted() ?: emptyList()
        return (listOf(DEFAULT_ENV) + dirs).distinct()
    }

    // The env the running script sources; internalEnv() reports on it
    @Volatile
    var current: ScriptEnvironment? = null
        private set

    @Synchronized
    fun open(name: String, activeProfile: String = DEFAULT_PROFILE): ScriptEnvironment =
        envs.getOrPut(name) { ScriptEnvironment(name, activeProfile) }.also { current = it }

    /** The env of that name as open makes it, but left as a look at it: [current] stays what the running script sourced */
    @Synchronized
    fun peek(name: String): ScriptEnvironment = envs.getOrPut(name) { ScriptEnvironment(name, DEFAULT_PROFILE) }
}

class ScriptEnvironment internal constructor(val name: String, private var activeProfile: String) {
    val dir: File = File(envRoot, name.replace(Regex("""[^A-Za-z0-9._-]"""), "_"))
    private val lockFile = File(dir, "env.lock.json")

    // Declared by the current run, and what the run resolved with where it came from
    private val sourcing = mutableListOf<String>()
    private val sourced = mutableListOf<Pair<String, String>>()

    private var lock: EnvLock = readLock()
    private var loader: Pair<List<File>, ClassLoader>? = null

    private fun readLock(): EnvLock =
        runCatching { envJson.decodeFromString<EnvLock>(lockFile.readText()) }.getOrElse { EnvLock(name, activeProfile) }

    val packages: List<String> get() = lock.packages.map { it.coordinate }

    /** What the env was asked for, literally: Maven coordinates and local jar names */
    val declared: List<String> get() = lock.declared

    /**
     * The Maven packages this env declares (not their dependencies). The version in the env may be newer than the
     * declared one, since a dependency's higher request wins (Gradle-style), so the match is by group:module.
     */
    fun mavenPackages(): List<MavenPackage> = lock.declared.filter { !it.endsWith(".jar") && !isLocalJar(it) }.map { declared ->
        val key = runCatching { Coordinate.parse(declared).key }.getOrNull()
        val resolved = key?.let { k -> lock.packages.firstOrNull { p -> runCatching { Coordinate.parse(p.coordinate).key }.getOrNull() == k } }
        val jar = resolved?.files?.map { File(envRoot, it) }?.firstOrNull { it.isFile && it.extension == "jar" }
        MavenPackage(declared, resolved?.coordinate, jar)
    }

    /** Takes a declared package out of the env; its files stay in the cache. False when it wasn't declared */
    @Synchronized
    fun remove(notation: String): Boolean {
        if (notation !in lock.declared) return false
        resolve(lock.declared - notation)
        logInternal("env '$name': $notation removed; scripts compiled from the next run no longer see it")
        return true
    }

    /** Starts a run: sources the env with what the script declares, downloading only what isn't cached */
    @Synchronized
    fun source(plan: EnvPlan) {
        activeProfile = plan.activeProfile
        sourcing.clear()
        sourced.clear()
        sourcing += plan.imports
        logInternal("env '$name': sourcing ${if (plan.imports.isEmpty()) "no new imports" else plan.imports.joinToString()}")
        val declared = (lock.declared + plan.imports).distinct()
        val cached = declared.toSet() == lock.declared.toSet() && lock.packages.all { p -> p.files.all { File(envRoot, it).exists() } }
        if (cached) {
            lock.packages.forEach { p ->
                val from = if (isLocalJar(p.coordinate)) copyLocalJar(p.coordinate.removePrefix(FILE_PREFIX))?.second ?: "missing" else "env"
                sourced += p.coordinate to from
            }
        } else {
            resolve(declared)
        }
        val groups = groups()
        logInternal("env '$name': sourced ${sourced.size} packages, ${lock.packages.size} in environment" +
            (if (groups.isEmpty()) "" else ", ${groups.size} model group${if (groups.size == 1) "" else "s"}: ${groups.joinToString { it.name }}"))
    }

    /** The model groups kept in this env (AMP/env/<name>/groups.json), what `group("name")` opens */
    fun groups(): List<org.example.project.components.terminal.window.controller.modules.ModelGroup> =
        org.example.project.components.terminal.window.controller.modules.ModelGroups.of(name)

    /** A runtime import that wasn't declared literally: added to the env now, on the compile classpath from the next run */
    @Synchronized
    fun import(notation: String) {
        if (notation in lock.declared) {
            logInternal("env '$name': $notation already in environment")
            return
        }
        sourcing += notation
        resolve(lock.declared + notation)
        logInternal("env '$name': $notation added; scripts compiled from the next run can use it")
    }

    fun jars(): List<File> = lock.packages.flatMap { p -> p.files.map { File(envRoot, it) } }.filter { it.exists() && it.extension == "jar" }

    /** Classloader the script compiles and runs with: the env's jars over the app's classes */
    @Synchronized
    fun classLoader(parent: ClassLoader): ClassLoader {
        val jars = jars()
        if (jars.isEmpty()) return parent
        loader?.let { (files, cl) -> if (files == jars) return cl }
        return URLClassLoader("amie-env-$name", jars.map { it.toURI().toURL() }.toTypedArray(), parent).also { loader = jars to it }
    }

    val libsDir: File get() = File(dir, "libs")

    // Where a jar file name is looked up: as given, AMP/pluginDir, AMP, then the env's own libs
    fun jarSearchPath(jar: String): List<File> {
        val given = File(jar)
        if (given.isAbsolute) return listOf(given)
        val amp = File(getDirectory().toString())
        return listOf(File(amp, "pluginDir/$jar"), File(amp, jar), File(libsDir, given.name))
    }

    /** The env's copy of a jar, refreshed when the source is newer; second is where it came from */
    fun copyLocalJar(jar: String): Pair<File, String>? {
        val target = File(libsDir, File(jar).name)
        val source = jarSearchPath(jar).firstOrNull { it.isFile && it.canonicalPath != target.canonicalPath }
        if (source == null) return if (target.isFile) target to "env" else null
        if (target.isFile && target.length() == source.length() && target.lastModified() >= source.lastModified()) return target to "env"
        libsDir.mkdirs()
        source.copyTo(target, overwrite = true)
        logInternal("env '$name': copied ${source.absolutePath} into ${libsDir.absolutePath}")
        return target to source.parent
    }

    /** A jar of this env by file name, for JarInterpreter */
    fun localJar(jar: String): File? =
        lock.packages.firstOrNull { it.coordinate == "$FILE_PREFIX${File(jar).name}" }?.files?.firstOrNull()?.let { File(envRoot, it) }?.takeIf { it.isFile }

    fun print(): List<String> {
        val lines = buildList {
            add("env '$name' (profile $activeProfile) at ${dir.absolutePath}")
            add("sourcing: ${sourcing.ifEmpty { listOf("-") }.joinToString()}")
            add("sourced (${sourced.size}):")
            sourced.forEach { (coordinate, from) -> add("  $coordinate  [$from]") }
            add("in environment (${lock.packages.size}):")
            lock.packages.forEach { p ->
                val size = p.files.sumOf { File(envRoot, it).length() }
                add("  ${p.coordinate}  ${if (p.files.isEmpty()) "(metadata only)" else "${size / 1024} KB"}${if (p.coordinate in lock.declared) "  declared" else ""}")
            }
            val groups = groups()
            add("model groups (${groups.size}):" + if (groups.isEmpty()) "  none; groups.create(\"name\", listOf(\"model\")) makes one here" else "")
            groups.forEach { g -> add("  ${g.name}: ${g.models.ifEmpty { listOf("no models") }.joinToString()}  (group(\"${g.name}\"))") }
        }
        logInternal(lines.joinToString("\n"))
        return packages
    }

    private fun resolve(declared: List<String>) {
        val (jarNotations, mavenNotations) = declared.partition { it.endsWith(".jar") }
        val localPackages = jarNotations.mapNotNull { jar ->
            val (file, from) = copyLocalJar(jar) ?: run {
                logInternal("env '$name': jar $jar not found in ${jarSearchPath(jar).joinToString { it.parent }}", LogLevel.ERROR)
                return@mapNotNull null
            }
            sourced += "$FILE_PREFIX${file.name}" to from
            EnvPackage("$FILE_PREFIX${file.name}", listOf(file.relativeTo(envRoot).path), emptyList())
        }

        val roots = mavenNotations.mapNotNull { notation ->
            runCatching { Coordinate.parse(notation) }.onFailure { logInternal("env '$name': ${it.message}", LogLevel.ERROR) }.getOrNull()
        }
        val resolver = Resolver()
        val graph = resolver.resolve(roots)

        val packages = graph.values.map { node ->
            val files = node.meta.files.mapNotNull { fileName ->
                resolver.cached(node.coordinate, fileName, node.meta.repository)?.let { (file, from) ->
                    file.relativeTo(envRoot).path.also { if (from != "cache") logInternal("env '$name': downloaded ${node.coordinate} ${file.name} from $from") }
                } ?: null.also { logInternal("env '$name': ${node.coordinate} has no $fileName in any repository", LogLevel.ERROR) }
            }
            sourced += node.coordinate.toString() to resolver.origin(node.coordinate)
            EnvPackage(node.coordinate.toString(), files, node.meta.dependencies.map { it.toString() }, node.meta.repository)
        }

        lock = EnvLock(name, activeProfile, declared.distinct(), (packages + localPackages).sortedBy { it.coordinate })
        dir.mkdirs()
        lockFile.writeText(envJson.encodeToString(lock))
    }
}

/* ---------- resolution ---------- */

private data class Dep(val group: String, val module: String, val version: String, val excludes: Set<String> = emptySet()) {
    val key: String get() = "$group:$module"
    override fun toString() = "$group:$module:$version"
}

private class ModuleMeta(val files: List<String>, val dependencies: List<Dep>, val repository: String?)

private class Node(val coordinate: Coordinate, val meta: ModuleMeta)

private class Resolver {
    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    private val metaCache = mutableMapOf<String, ModuleMeta>()
    private val pomCache = mutableMapOf<String, EffectivePom?>()
    private val origins = mutableMapOf<String, String>()

    fun origin(c: Coordinate): String = origins[c.toString()] ?: "cache"

    // Gradle-style: every request for a module is a candidate and the highest version wins; repeat until stable
    fun resolve(roots: List<Coordinate>): Map<String, Node> {
        val selected = mutableMapOf<String, String>()
        roots.forEach { root -> selected.merge(root.key, root.version) { a, b -> maxVersion(a, b) } }
        var nodes = emptyMap<String, Node>()
        repeat(20) {
            var changed = false
            val visited = linkedMapOf<String, Node>()
            fun visit(key: String, excludes: Set<String>) {
                if (key in visited) return
                val (group, module) = key.split(":")
                val coordinate = Coordinate(group, module, selected.getValue(key))
                val meta = metadata(coordinate) ?: run {
                    logInternal("env: could not resolve $coordinate", LogLevel.ERROR)
                    return
                }
                visited[key] = Node(coordinate, meta)
                for (dep in meta.dependencies) {
                    if (isExcluded(dep, excludes) || hostProvided.any { dep.key.startsWith(it) }) continue
                    val previous = selected[dep.key]
                    val chosen = previous?.let { maxVersion(it, dep.version) } ?: dep.version
                    if (chosen != previous) {
                        if (previous != null) changed = true
                        selected[dep.key] = chosen
                    }
                    visit(dep.key, excludes + dep.excludes)
                }
            }
            roots.forEach { visit(it.key, emptySet()) }
            nodes = visited
            if (!changed) return nodes
        }
        return nodes
    }

    private fun isExcluded(dep: Dep, excludes: Set<String>) = excludes.any { rule ->
        val (g, m) = rule.split(":").let { it[0] to it.getOrElse(1) { "*" } }
        (g == "*" || g == dep.group) && (m == "*" || m == dep.module)
    }

    private fun metadata(c: Coordinate): ModuleMeta? = metaCache.getOrPut(c.toString()) {
        gradleModule(c) ?: pomModule(c) ?: return null
    }

    // Gradle Module Metadata: pick the JVM runtime library variant, following available-at for multiplatform roots
    private fun gradleModule(c: Coordinate): ModuleMeta? {
        val (file, repo) = cached(c, "${c.module}-${c.version}.module", null) ?: return null
        val root = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        val variants = root["variants"]?.jsonArray?.map { it.jsonObject } ?: return null
        fun JsonObject.attr(name: String) = this["attributes"]?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull
        val runtime = variants.filter { v ->
            v.attr("org.gradle.usage") in setOf("java-runtime", "kotlin-runtime") &&
                (v.attr("org.gradle.category") ?: "library") == "library" &&
                v.attr("org.jetbrains.kotlin.platform.type").let { it == null || it == "jvm" } &&
                v.attr("org.gradle.jvm.environment").let { it == null || it == "standard-jvm" }
        }
        val variant = runtime.firstOrNull { it.attr("org.jetbrains.kotlin.platform.type") == "jvm" } ?: runtime.firstOrNull() ?: return null

        variant["available-at"]?.jsonObject?.let { at ->
            val target = Dep(at.str("group")!!, at.str("module")!!, at.str("version")!!)
            return ModuleMeta(emptyList(), listOf(target), repo)
        }
        val files = variant["files"]?.jsonArray?.mapNotNull { it.jsonObject.str("url") } ?: emptyList()
        val deps = variant["dependencies"]?.jsonArray?.mapNotNull { d ->
            val o = d.jsonObject
            val v = o["version"]?.jsonObject
            val version = v?.str("strictly") ?: v?.str("requires") ?: v?.str("prefers") ?: return@mapNotNull null
            val excludes = o["excludes"]?.jsonArray?.map { e -> "${e.jsonObject.str("group") ?: "*"}:${e.jsonObject.str("module") ?: "*"}" }?.toSet() ?: emptySet()
            Dep(o.str("group")!!, o.str("module")!!, version, excludes)
        } ?: emptyList()
        return ModuleMeta(files, deps, repo)
    }

    private fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.contentOrNull

    private fun pomModule(c: Coordinate): ModuleMeta? {
        val pom = effectivePom(c) ?: return null
        val deps = pom.dependencies.mapNotNull { d ->
            if (d.optional || d.scope !in setOf(null, "compile", "runtime") || d.type !in setOf(null, "jar", "bundle") || d.classifier != null) return@mapNotNull null
            val version = d.version ?: pom.managed[d.key]
            if (version == null) {
                logInternal("env: $c depends on ${d.key} without a version, skipped", LogLevel.ERROR)
                return@mapNotNull null
            }
            Dep(d.group, d.artifact, normalizeVersion(version), d.exclusions)
        }
        val files = when (pom.packaging) {
            "pom" -> emptyList()
            "aar" -> emptyList<String>().also { logInternal("env: $c is an Android aar, not usable on the JVM", LogLevel.ERROR) }
            else -> listOf("${c.module}-${c.version}.jar")
        }
        return ModuleMeta(files, deps, pom.repository)
    }

    private class PomDep(
        val group: String, val artifact: String, val version: String?, val scope: String?, val type: String?,
        val classifier: String?, val optional: Boolean, val exclusions: Set<String>,
    ) {
        val key: String get() = "$group:$artifact"
    }

    private class EffectivePom(
        val packaging: String,
        val properties: Map<String, String>,
        val managed: Map<String, String>,
        val dependencies: List<PomDep>,
        val repository: String?,
    )

    // POM with its parents' properties, managed versions and dependencies merged in, and imported BOMs applied
    private fun effectivePom(c: Coordinate): EffectivePom? = pomCache.getOrPut(c.toString()) {
        val (file, repo) = cached(c, "${c.module}-${c.version}.pom", null) ?: return@getOrPut null
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(file)
        val project = doc.documentElement

        val parentEl = project.child("parent")
        val parentPom = parentEl?.let { p ->
            effectivePom(Coordinate(p.text("groupId")!!, p.text("artifactId")!!, p.text("version")!!))
        }
        val groupId = project.text("groupId") ?: parentEl?.text("groupId") ?: c.group
        val version = project.text("version") ?: parentEl?.text("version") ?: c.version

        val props = (parentPom?.properties ?: emptyMap()).toMutableMap()
        project.child("properties")?.let { el ->
            el.childElements().forEach { props[it.tagName] = it.textContent.trim() }
        }
        props += mapOf(
            "project.groupId" to groupId, "project.version" to version, "project.artifactId" to c.module,
            "pom.groupId" to groupId, "pom.version" to version, "version" to version,
        )
        parentEl?.let { props["project.parent.version"] = it.text("version")!!; props["project.parent.groupId"] = it.text("groupId")!! }
        fun interp(value: String?): String? {
            var s = value ?: return null
            repeat(5) { s = Regex("""\$\{([^}]+)}""").replace(s) { m -> props[m.groupValues[1]] ?: m.value } }
            return s
        }

        fun deps(container: Element?) = container?.child("dependencies")?.childElements("dependency")?.map { d ->
            PomDep(
                group = interp(d.text("groupId"))!!,
                artifact = interp(d.text("artifactId"))!!,
                version = interp(d.text("version")),
                scope = d.text("scope"),
                type = d.text("type"),
                classifier = d.text("classifier"),
                optional = d.text("optional") == "true",
                exclusions = d.child("exclusions")?.childElements("exclusion")?.map { "${it.text("groupId")}:${it.text("artifactId")}" }?.toSet() ?: emptySet(),
            )
        } ?: emptyList()

        val managedDeps = deps(project.child("dependencyManagement"))
        val managed = mutableMapOf<String, String>()
        managedDeps.filter { it.scope == "import" && it.type == "pom" && it.version != null }.forEach { bom ->
            effectivePom(Coordinate(bom.group, bom.artifact, bom.version!!))?.managed?.forEach { (k, v) -> managed.putIfAbsent(k, v) }
        }
        parentPom?.managed?.let { managed.putAll(it) }
        managedDeps.filter { it.scope != "import" && it.version != null }.forEach { managed[it.key] = it.version!! }

        val own = deps(project)
        val inherited = parentPom?.dependencies?.filter { p -> own.none { it.key == p.key } } ?: emptyList()

        EffectivePom(project.text("packaging") ?: "jar", props, managed, own + inherited, repo)
    }

    /** Cached file and where it came from ("cache" or the repository); downloads from the first repository that has it */
    fun cached(c: Coordinate, fileName: String, preferredRepo: String?): Pair<File, String>? {
        val target = File(cacheDir, "${c.group}/${c.module}/${c.version}/$fileName")
        if (target.exists()) return target to "cache"
        val missing = File(target.path + ".missing")
        if (missing.exists()) return null

        val repos = preferredRepo?.let { listOf(it) + (repositories - it) } ?: repositories
        for (repo in repos) {
            val url = "$repo${c.group.replace('.', '/')}/${c.module}/${c.version}/$fileName"
            val bytes = fetch(url) ?: continue
            fetch("$url.sha1")?.toString(Charsets.UTF_8)?.trim()?.substringBefore(" ")?.let { expected ->
                val actual = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
                if (!expected.equals(actual, ignoreCase = true)) throw IOException("Checksum mismatch for $url")
            }
            target.parentFile.mkdirs()
            val tmp = File(target.path + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(target)
            origins.putIfAbsent(c.toString(), repo)
            return target to repo
        }
        // Remember the miss (e.g. no .module for a POM-only library) so it isn't asked again
        missing.parentFile.mkdirs()
        missing.writeText("")
        return null
    }

    private fun fetch(url: String): ByteArray? {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return when (response.statusCode()) {
            200 -> response.body()
            404, 403 -> null
            else -> throw IOException("GET $url returned ${response.statusCode()}")
        }
    }
}

private fun Element.childElements(tag: String? = null): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { tag == null || it.tagName == tag }

private fun Element.child(tag: String): Element? = childElements(tag).firstOrNull()

private fun Element.text(tag: String): String? = child(tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

// Maven ranges like [1.2,2.0) resolve to their lower bound
private fun normalizeVersion(version: String): String =
    if (version.startsWith("[") || version.startsWith("(")) version.trim('[', '(', ']', ')').split(",").first { it.isNotBlank() }.trim() else version

private val qualifierOrder = listOf("dev", "snapshot", "alpha", "a", "beta", "b", "milestone", "m", "rc", "cr")

// Gradle-like ordering: numeric parts numerically, pre-release qualifiers below the release they precede
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.', '-', '_', '+').filter { it.isNotEmpty() }
    val pb = b.split('.', '-', '_', '+').filter { it.isNotEmpty() }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrNull(i)
        val y = pb.getOrNull(i)
        if (x == null) return if (y!!.toIntOrNull() != null) -1 else qualifierRank(y).let { if (it < 0) 1 else -1 }
        if (y == null) return if (x.toIntOrNull() != null) 1 else qualifierRank(x).let { if (it < 0) -1 else 1 }
        val nx = x.toIntOrNull()
        val ny = y.toIntOrNull()
        val c = when {
            nx != null && ny != null -> nx.compareTo(ny)
            nx != null -> 1
            ny != null -> -1
            else -> qualifierRank(x).compareTo(qualifierRank(y)).takeIf { it != 0 } ?: x.compareTo(y, ignoreCase = true)
        }
        if (c != 0) return c
    }
    return 0
}

// Negative for pre-release qualifiers; release markers (final, ga, release) rank above them
private fun qualifierRank(q: String): Int {
    val i = qualifierOrder.indexOf(q.lowercase().trimEnd { it.isDigit() })
    return if (i >= 0) i - qualifierOrder.size else 1
}

private fun maxVersion(a: String, b: String) = if (compareVersions(a, b) >= 0) a else b
