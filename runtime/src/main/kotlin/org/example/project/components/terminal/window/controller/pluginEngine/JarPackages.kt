package org.example.project.components.terminal.window.controller.pluginEngine

import org.example.project.auth.Accounts
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.backend.AmieBackend
import org.example.project.backend.BackendConfig
import org.example.project.components.terminal.integrations.amiePilot.InteractionModel
import org.example.project.components.terminal.integrations.amiePilot.Prediction
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.envBuilder.ScriptEnvironments
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.components.terminal.window.controller.modules.ModelJobs
import org.example.project.components.terminal.window.controller.runtime.SessionLog
import org.example.project.getDirectory
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.JarFile

/** One jar the Amie server offers: what the website's `/api/jars` (or the S3 service's `/api/aws/jars`) lists */
@Serializable
data class ServerJar(
    val name: String,
    val size: Long = 0,
    val version: String? = null,
    val kind: String = "package",
    val lastModified: String? = null,
    /** "downloads" for the site's downloads page, "store" for a jar someone published under their account */
    val where: String = "downloads",
    /** Who published it, for a store jar */
    val author: String? = null,
    /** The exact address the bytes come from, when the listing says */
    val downloadUrl: String? = null,
    /** The page that shows it */
    val page: String? = null,
)

/**
 * What the Amie server offers, as [JarPackages.listing] found it: the jars, and when the server's lists could not be
 * read, why not, with the jars of the lists that could. An empty [jars] with no [problem] means it offers nothing.
 */
data class JarListing(val jars: List<ServerJar>, val problem: String? = null)

/** One row of the website's `/api/all-packages`: every file of the store with its author */
@Serializable
private data class StoreFile(
    val name: String = "",
    val path: String = "",
    val author: String = "",
    val size: Double = 0.0,
    val download_url: String = "",
    val html_url: String = "",
)

/**
 * Where a package jar came from, kept next to it as `AMP/pluginDir/<name>.source.json` when it was fetched from
 * the Amie server, so its owner can go back to the page that lists it and find out how to use it.
 */
@Serializable
data class JarOrigin(
    val name: String,
    /** The exact URL the bytes were downloaded from */
    val url: String,
    /** The website page that lists the jar, where its description lives */
    val page: String,
    val version: String? = null,
    val size: Long = 0,
    val lastModified: String? = null,
    val fetchedAt: String = Instant.now().toString(),
    /** owner/repo, for a jar taken from GitHub */
    val repo: String? = null,
    /** Which version of it: the file's blob sha, or the release asset's id and update time */
    val sha: String? = null,
)

/**
 * Whether a jar can be opened as a package, found without running any of its code (classes are loaded, never
 * initialized): the class a session would open on, whether it implements AmiePackage, else what is wrong.
 */
data class JarCheck(val entryClass: String? = null, val amiePackage: Boolean = false, val problem: String? = null) {
    val ok: Boolean get() = problem == null
    val describe: String get() = problem ?: "package class $entryClass (${if (amiePackage) "implements AmiePackage" else "run(String) and close() by shape"})"
}

/**
 * One open package: a jar loaded into its own class loader with one instance of its package class, the
 * class that has `run(String): List` and `close()` (the contract `POST /upload` checks on the server).
 *
 * Every `initMod("x.jar")` makes a new one, like `initMod("model")` opens a new ONNX session: packages are
 * self-contained bottles, each session owns its instance and its loader, and `close()` releases both.
 */
class JarPackageSession internal constructor(
    val id: Int,
    val jarName: String,
    val file: File,
    val className: String,
    /** Where the jar came from: env, pluginDir, path or server */
    val source: String,
    /** What the jar says it needs, when it says anything */
    val meta: PackageMeta?,
    /** The folder its configuration files are read from */
    val configDir: File,
    private val loader: URLClassLoader,
    private val instance: Any,
    private val runMethod: Method,
    private val closeMethod: Method?,
) : InteractionModel {
    val openedAt: Instant = Instant.now()
    private val runCount = AtomicInteger()
    private val runningCount = AtomicInteger()

    /** The model it was opened for, when it is a speech model: the Sessions tab shows that and not the jar */
    @Volatile internal var speechModel: String? = null

    val shownName: String get() = speechModel ?: jarName

    /** What this session was asked and answered; also appended to AMP/pluginDir/<jar>.sessions.log */
    val log = SessionLog(jarName, id, JarPackages.sessionsLogFile(jarName))

    /** Finished calls of run(text) */
    val runs: Int get() = runCount.get()

    /** Calls of run(text) in flight right now */
    val running: Int get() = runningCount.get()

    private val answerNanos = AtomicLong()

    /** The time run(text) took, over every call */
    val answerMillis: Long get() = answerNanos.get() / 1_000_000

    /** When it last answered; null before its first call */
    @Volatile var lastRunAt: Instant? = null
        private set

    @Volatile
    var closed: Boolean = false
        private set

    /** Hands the text to the package's run(text) and returns what it answered, one line per element */
    override fun inference(text: String): List<String> {
        check(!closed) { "Package $jarName (#$id) is closed" }
        val previous = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = loader
        runningCount.incrementAndGet()
        val begun = System.nanoTime()
        try {
            val result = lines(stoppable { runMethod.invoke(instance, text) })
            log.run(text, result, (System.nanoTime() - begun) / 1_000_000)
            return result
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: e
            log.error(text, cause)
            logInternal("package $jarName: run failed: $cause", LogLevel.ERROR)
            throw cause
        } finally {
            answerNanos.addAndGet(System.nanoTime() - begun)
            runningCount.decrementAndGet()
            runCount.incrementAndGet()
            lastRunAt = Instant.now()
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /**
     * A package's call on a thread of its own, so Stop ends the script at once: a package may read a device for
     * seconds and never look at the interrupt, and one that returns as if nothing happened would let a script's
     * loop go round for ever. What it was doing ends when the block around the script closes the package.
     */
    private fun stoppable(call: () -> Any?): Any? {
        if (Thread.currentThread().isInterrupted) throw CancellationException("$jarName: stopped")
        val job = JarPackages.calls.submit<Any?> {
            Thread.currentThread().contextClassLoader = loader
            call()
        }
        try {
            return job.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            job.cancel(true)
            throw CancellationException("$jarName: stopped")
        }
    }

    // A package answers whatever its run(text) returns, not label:score lines: reading a label or a score out of
    // that would cut text such as "ratio:0.5", so these say what to call instead
    override fun predict(text: String): String = notAClassifier("predict")
    override fun predictions(text: String): List<Prediction> = notAClassifier("predictions")
    override fun scores(text: String): Map<String, Float> = notAClassifier("scores")

    private fun notAClassifier(call: String): Nothing =
        throw IllegalStateException("$jarName is a package, not a classifier: $call(text) has no label to give; use run(text) for every answer or answer(text) for the first")

    override fun getStatus(): Any = mapOf(
        "id" to id,
        "jar" to jarName,
        "class" to className,
        "source" to source,
        "file" to file.absolutePath,
        "url" to JarPackages.origin(jarName)?.url,
        "page" to JarPackages.origin(jarName)?.page,
        "configDir" to configDir.absolutePath,
        "config" to (meta?.config?.map { it.file } ?: emptyList<String>()),
        "metadata" to (meta != null),
        "openedAt" to openedAt.toString(),
        "runs" to runs,
        "running" to running,
        "closed" to closed,
    )

    /** Calls the package's close() and drops its class loader; a second call does nothing */
    override fun close() {
        if (closed) return
        closed = true
        ModelJobs.endOn(this)
        try {
            closeMethod?.invoke(instance)
        } catch (e: InvocationTargetException) {
            logInternal("package $jarName: close failed: ${e.cause}", LogLevel.ERROR)
        } finally {
            runCatching { loader.close() }
            JarPackages.unregister(this)
            log.add("close", "closed after $runs runs")
            logInternal("package $jarName (#$id): closed after $runs runs")
        }
    }

    override fun toString(): String = "#$id $jarName ($className) · $source · runs $runs"

    private fun lines(result: Any?): List<String> = when (result) {
        null -> emptyList()
        is String -> listOf(result)
        is Collection<*> -> result.map { it.toString() }
        is Array<*> -> result.map { it.toString() }
        is Iterable<*> -> result.map { it.toString() }
        else -> listOf(result.toString())
    }
}

/**
 * Opens package jars as sessions: `initMod("x.jar")` resolves the jar (the active env, then `AMP/pluginDir`,
 * then a path, then the Amie server, which stores it in `AMP/pluginDir`), finds the class that satisfies
 * the package contract, instantiates it and returns it behind the `inference / getStatus / close` interface.
 *
 * Scripts also reach this as `amie.packages`: `packages.available()` lists what the server offers,
 * `packages.fetch("x.jar")` downloads one, `packages.print()` shows the open sessions, `packages.closeAll()`.
 */
object JarPackages {
    private const val LISTING_TTL_MS = 60_000L
    private val ids = AtomicInteger()
    private val sessions = linkedMapOf<Int, JarPackageSession>()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var client: HttpClient? = null
    /**
     * The client the server is asked through, tests hand in one on a mock engine. Made the first time the server is
     * asked, not with the object: an exported bundle carries no ktor and finds its jars in AMP/pluginDir, so opening
     * one there failed with NoClassDefFoundError when the object made it
     */
    internal var http: HttpClient
        get() = client ?: synchronized(this) { client ?: AmieBackend.client(Java.create()).also { client = it } }
        set(value) { client = value }

    @Volatile private var cached: Pair<Long, JarListing>? = null

    /** The open package sessions, oldest first */
    val open: List<JarPackageSession> @Synchronized get() = sessions.values.toList()

    /**
     * Opens a new session on the jar; see the class comment for where the jar is looked up. Before the class is
     * made, the jar's metadata is read and its configuration is put where the package finds it: the config
     * folder (`AMP/pluginDir/config/<jar>/`, templates written for the files that are missing) is the first
     * root of the class loader, so `getResource("x.properties")` resolves there; `amie.package.config` and
     * `amie.package.name` are system properties while the package is opened; and a `configure(File)`,
     * `configure(Path)`, `configure(String)` or `configure(Map)` method of the package class, if it has one,
     * is called with the folder (the map holds `dir` and each file by name). A jar that lacks something it says it
     * needs ([PackageMetadata.blockers]) is refused before any instance is made, naming all of it and the folder.
     */
    fun open(name: String): JarPackageSession {
        val (file, source) = resolve(name) ?: throw IllegalArgumentException(
            "Package $name not found: not in the env, AMP/pluginDir, as a path, " +
                (if (Accounts.enabled) "on the server (${BackendConfig.load().site}) " else "") + "or in the GitHub repositories of AMP/github.json"
        )
        val meta = PackageMetadata.read(file)
        val configDir = PackageMetadata.configDir(file.name)
        PackageMetadata.prepareConfig(file.name, meta)
        meta?.java?.let { needed ->
            val host = Runtime.version().feature()
            if (needed > host) throw IllegalStateException("${file.name} needs Java $needed (its metadata says so) and this app runs Java $host")
        }
        // After prepareConfig, so a template is there to fill in when the message names it
        PackageMetadata.blockers(file.name, meta).takeIf { it.isNotEmpty() }?.let { missing ->
            throw IllegalStateException("Package '${file.name}' can't run: ${missing.joinToString("; ") { "${it.name}: ${it.detail}" }}. Its config folder is ${configDir.absolutePath}")
        }
        val env = ScriptEnvironments.current
        val parent = env?.classLoader(JarPackages::class.java.classLoader) ?: JarPackages::class.java.classLoader
        val id = ids.incrementAndGet()
        val loader = URLClassLoader("amie-package-${file.name}-$id", arrayOf(configDir.toURI().toURL(), file.toURI().toURL()), parent)
        try {
            val entry = findEntryClass(file, loader, meta?.entry)
                ?: throw IllegalArgumentException("${file.name} has no package class: a public class with run(String): List and close(), and a no-arg constructor")
            val previous = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = loader
            val instance = try {
                System.setProperty("amie.package.config", configDir.absolutePath)
                System.setProperty("amie.package.name", file.name)
                System.setProperty("amie.package.config.${file.name.removeSuffix(".jar")}", configDir.absolutePath)
                entry.clazz.getConstructor().newInstance().also { configure(it, entry.clazz, configDir, file.name) }
            } catch (e: InvocationTargetException) {
                throw IllegalStateException("${file.name}: ${entry.clazz.name} could not be created: ${e.cause}", e.cause)
            } finally {
                Thread.currentThread().contextClassLoader = previous
            }
            val session = JarPackageSession(id, file.name, file, entry.clazz.name, source, meta, configDir, loader, instance, entry.run, entry.close)
            session.log.add("open", "opened as session #$id on ${entry.clazz.name} from $source")
            synchronized(this) { sessions[id] = session }
            logInternal("package ${file.name} (#$id): session ready on ${entry.clazz.name} from $source")
            return session
        } catch (e: Throwable) {
            runCatching { loader.close() }
            throw e
        }
    }

    /** Hands the config folder to the package through its configure method, when it has one */
    private fun configure(instance: Any, clazz: Class<*>, configDir: File, jarName: String) {
        val method = clazz.methods.firstOrNull { it.name == "configure" && it.parameterCount == 1 && !Modifier.isStatic(it.modifiers) } ?: return
        val type = method.parameterTypes[0]
        val argument: Any? = when {
            type == File::class.java -> configDir
            type == java.nio.file.Path::class.java -> configDir.toPath()
            type == String::class.java -> configDir.absolutePath
            type.isAssignableFrom(Map::class.java) -> buildMap<String, String> {
                put("dir", configDir.absolutePath)
                configDir.listFiles()?.filter { it.isFile }?.forEach { put(it.name, it.absolutePath) }
            }
            else -> null
        }
        if (argument == null) {
            logInternal("package $jarName: configure(${type.simpleName}) is not called; File, Path, String or Map are understood")
            return
        }
        try {
            method.invoke(instance, argument)
            logInternal("package $jarName: configure(${type.simpleName}) called with ${configDir.absolutePath}")
        } catch (e: InvocationTargetException) {
            throw IllegalStateException("$jarName: configure failed: ${e.cause}", e.cause)
        }
    }

    /** What the jar says it needs, from inside it and from the sidecar beside it; null when it says nothing */
    fun meta(name: String): PackageMeta? = resolve(name)?.first?.let { PackageMetadata.read(it) }

    /**
     * Runs the tests the jar declares on a fresh session (opened as initMod would, configuration and all) and
     * closes it: every test's input through run, the answer checked against expect, contains and lines. With no
     * tests declared, one call with "hello" that must not throw. The report is printed, kept beside the jar as
     * <jar>.tests.json and returned.
     */
    fun test(name: String): PackageTestReport {
        val jarName = File(name).name
        val session = try {
            open(name)
        } catch (e: Throwable) {
            val report = PackageTestReport(jarName, error = e.message ?: e.javaClass.simpleName)
            PackageMetadata.writeReport(report)
            logInternal("test run ${report.summary()}", LogLevel.ERROR)
            return report
        }
        val tests = session.meta?.tests.orEmpty()
        val smoke = tests.isEmpty()
        val results = try {
            (if (smoke) listOf(PackageTest(name = "Answers hello", input = "hello")) else tests).map { t ->
                val begun = System.nanoTime()
                val outcome = runCatching { session.inference(t.input) }
                val millis = (System.nanoTime() - begun) / 1_000_000
                outcome.fold(
                    onSuccess = { answer -> t.check(answer).let { reason -> PackageTestResult(t.title, t.input, reason == null, answer, reason, millis) } },
                    onFailure = { PackageTestResult(t.title, t.input, false, emptyList(), "threw ${it.javaClass.simpleName}: ${it.message}", millis) },
                )
            }
        } finally {
            session.close()
        }
        val report = PackageTestReport(jarName, results = results, smoke = smoke, entryClass = session.className)
        PackageMetadata.writeReport(report)
        logInternal("test run ${report.summary()}", if (report.ok) LogLevel.INFO else LogLevel.ERROR)
        results.forEach { r ->
            logInternal("  ${if (r.passed) "Pass" else "Fail"}  ${r.name}: \"${r.input.take(40)}\" -> ${r.answer.joinToString(" | ").take(100)}${r.reason?.let { "  ($it)" } ?: ""}  ${r.millis} ms", if (r.passed) LogLevel.INFO else LogLevel.ERROR)
        }
        return report
    }

    /**
     * The jar as a file with where it was found, downloading it from the server, else from the GitHub repositories
     * of AMP/github.json, when it is nowhere local. A `github:owner/repo/x.jar` name (or a github.com address)
     * always means that repository's jar; it throws with the reason when that cannot be had.
     */
    fun resolve(name: String): Pair<File, String>? {
        GitHubSpec.parse(name)?.let { return GitHubJars.resolve(it) to "github" }
        val env = ScriptEnvironments.current
        env?.localJar(name)?.let { return it to "env" }
        val pluginDir = pluginDir()
        File(pluginDir, File(name).name).takeIf { it.isFile }?.let { return it to "pluginDir" }
        (env?.jarSearchPath(name) ?: emptyList()).firstOrNull { it.isFile }?.let { return it to "path" }
        File(name).takeIf { it.isFile }?.let { return it to "path" }
        return fetch(name)?.let { it to (if (origin(it.name)?.repo != null) "github" else "server") }
    }

    /**
     * Downloads a jar from the Amie server into `AMP/pluginDir`: the website's `/api/jar-download` first,
     * then the S3 service's `/api/aws/jars/<name>`. Null when neither has it, with what each address answered added
     * to [problems] for a page that shows the reason.
     */
    fun fetch(name: String, problems: MutableList<String> = mutableListOf()): File? {
        val fileName = File(name).name
        if (!fileName.endsWith(".jar", ignoreCase = true)) { problems += "$fileName is not a jar"; return null }
        // The Amie server isn't there yet: without accounts a jar comes only from GitHub
        if (!Accounts.enabled) return GitHubJars.fetch(fileName)
        val config = BackendConfig.load()
        // A store jar is fetched from the address its listing gives; the downloads page's jars from the site, then the S3 service
        val listed = available().firstOrNull { it.name == fileName }
        if (listed?.where == "github") return GitHubJars.fetch(fileName)
        val urls = listOfNotNull(
            listed?.downloadUrl?.takeIf { it.isNotBlank() },
            "${config.site}/api/jar-download?name=${java.net.URLEncoder.encode(fileName, Charsets.UTF_8)}",
            "${config.s3}/api/aws/jars/${java.net.URLEncoder.encode(fileName, Charsets.UTF_8)}",
        )
        for (url in urls) {
            val bytes = runCatching {
                runBlocking {
                    val response = http.get(url)
                    if (!response.status.isSuccess()) { problems += "$url answered ${response.status.value}"; null } else response.bodyAsBytes()
                }
            }.onFailure {
                problems += "$url is unreachable (${it.message ?: it.javaClass.simpleName})"
                logInternal("package $fileName: $url unreachable: ${it.message}", LogLevel.ERROR)
            }.getOrNull() ?: continue
            val origin = JarOrigin(fileName, url, listed?.page ?: downloadsPage(), listed?.version, bytes.size.toLong(), listed?.lastModified)
            return runCatching { receive(fileName, bytes, origin) }.onFailure {
                problems += it.message ?: it.javaClass.simpleName
                logInternal("package $fileName: ${it.message}", LogLevel.ERROR)
            }.getOrNull() ?: continue
        }
        return GitHubJars.fetch(fileName)
    }

    /**
     * Keeps downloaded bytes as AMP/pluginDir/<fileName> when [check] passes, with their origin beside them; when it
     * does not, nothing is kept (a copy already there stays) and the reason is thrown. Every fetched jar comes here.
     */
    internal fun receive(fileName: String, bytes: ByteArray, origin: JarOrigin): File {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte())
            throw IllegalArgumentException("${origin.url} did not answer with a jar")
        val incoming = File(pluginDir(), ".incoming").apply { mkdirs() }
        val temp = File(incoming, fileName).apply { writeBytes(bytes) }
        val check = try {
            check(temp)
        } catch (e: Throwable) {
            JarCheck(problem = "could not be inspected: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!check.ok) {
            temp.delete()
            throw IllegalArgumentException("$fileName from ${origin.url} was not kept: ${check.problem}")
        }
        val target = File(pluginDir(), fileName)
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        writeOrigin(origin)
        logInternal("package $fileName: received ${bytes.size} bytes from ${origin.url} into ${target.absolutePath}; ${check.describe}")
        return target
    }

    internal fun writeOrigin(origin: JarOrigin) {
        runCatching { originFile(origin.name).writeText(json.encodeToString(JarOrigin.serializer(), origin)) }
            .onFailure { logInternal("package ${origin.name}: couldn't record its origin: ${it.message}", LogLevel.ERROR) }
    }

    /**
     * Whether the jar can be opened as a package, without running it: it reads as a jar, its classes load on this
     * Java, what its metadata asks of Java holds, and a class satisfies the contract (public, a no-arg constructor,
     * run(String) returning something, close()); when the metadata names the entry class, that class must be it.
     */
    fun check(file: File): JarCheck {
        runCatching { JarFile(file).use { it.size() } }.getOrElse { return JarCheck(problem = "not a readable jar: ${it.message}") }
        val host = Runtime.version().feature()
        JarInspector.inspect(file)?.let { if (!it.runsHere) return JarCheck(problem = "its classes need ${it.javaSupport} and this app runs Java $host") }
        val meta = PackageMetadata.read(file)
        meta?.java?.let { if (it > host) return JarCheck(problem = "its metadata asks for Java $it and this app runs Java $host") }
        val env = ScriptEnvironments.current
        val parent = env?.classLoader(JarPackages::class.java.classLoader) ?: JarPackages::class.java.classLoader
        val entry = URLClassLoader("amie-check-${file.name}", arrayOf(file.toURI().toURL()), parent).use { loader ->
            findEntryClass(file, loader, meta?.entry)?.let { e -> e.clazz.name to e.clazz.interfaces.any { it.simpleName == "AmiePackage" } }
        } ?: return JarCheck(problem = "no package class: no public class with a no-arg constructor, run(String) returning its answer, and close() (implementing AmiePackage is the easiest way)")
        meta?.entry?.let { named -> if (named != entry.first) return JarCheck(problem = "its metadata names $named as the package class, but $named has no run(String) / close() / no-arg constructor") }
        return JarCheck(entry.first, entry.second)
    }

    /**
     * The package jars the website offers, cached for a minute (an unreachable server is remembered as empty
     * for as long): the downloads page's jars (`/api/jars`, else the S3 service's listing) and every jar in the
     * store, the packages people published under their accounts (`/api/all-packages`, each with its author and
     * the address its bytes come from). A jar both places is listed once, as a download.
     */
    fun available(refresh: Boolean = false): List<ServerJar> = listing(refresh).jars

    /**
     * [available] with the reason a list could not be read, cached with it for as long: what a page that must not
     * show an unreachable server as an empty list asks.
     */
    fun listing(refresh: Boolean = false): JarListing {
        val now = System.currentTimeMillis()
        cached?.let { (at, found) -> if (!refresh && now - at < LISTING_TTL_MS) return found }
        if (!Accounts.enabled) return JarListing(githubJars(refresh), null).also { cached = now to it }
        val config = BackendConfig.load()
        val urls = listOf("${config.site}/api/jars", "${config.s3}/api/aws/jars")
        var jars = emptyList<ServerJar>()
        // The second address is the first's fallback: its problems only count when neither gave a list
        val downloadProblems = mutableListOf<String>()
        for (url in urls) {
            val text = get(url, downloadProblems) ?: continue
            jars = runCatching { json.decodeFromString<List<ServerJar>>(text) }
                .onFailure { downloadProblems += "$url did not answer with a list of packages" }
                .getOrNull()?.filter { it.kind != "application" && it.name.endsWith(".jar", ignoreCase = true) }
                ?.map { it.copy(where = "downloads", page = downloadsPage()) } ?: continue
            downloadProblems.clear()
            break
        }
        val storeProblems = mutableListOf<String>()
        val store = get("${config.site}/api/all-packages", storeProblems)?.let { text ->
            runCatching { json.decodeFromString<List<StoreFile>>(text) }
                .onFailure { storeProblems += "${config.site}/api/all-packages did not answer with a list of packages" }
                .getOrDefault(emptyList())
                .filter { it.name.endsWith(".jar", ignoreCase = true) && it.download_url.isNotBlank() }
                .map { f -> ServerJar(f.name, f.size.toLong(), where = "store", author = f.author.ifBlank { null }, downloadUrl = f.download_url, page = f.html_url.ifBlank { null } ?: storePage(f.author)) }
        }.orEmpty()
        val github = githubJars(refresh)
        val server = jars + store.filter { s -> jars.none { it.name == s.name } }.distinctBy { it.name }
        val all = server + github.filter { g -> server.none { it.name == g.name } }.distinctBy { it.name }
        val problems = downloadProblems + storeProblems
        val problem = if (problems.isEmpty()) null else "Couldn't read the Amie package repository: ${problems.joinToString("; ")}. " +
            "Check the addresses in ${BackendConfig.file.path} (AMIE_SITE_URL and AMIE_S3_URL override them)"
        return JarListing(all, problem).also { cached = now to it }
    }

    private fun githubJars(refresh: Boolean): List<ServerJar> = GitHubJars.available(refresh).map { g ->
        ServerJar(g.name, g.size, version = g.release, where = "github", author = g.repo, downloadUrl = g.download, page = g.page)
    }

    private fun get(url: String, problems: MutableList<String>): String? = runCatching {
        runBlocking {
            val response = http.get(url)
            if (response.status.isSuccess()) response.bodyAsText() else { problems += "$url answered ${response.status.value}"; null }
        }
    }.getOrElse { problems += "$url is unreachable (${it.message ?: it.javaClass.simpleName})"; null }

    /** The website page of someone's published packages */
    fun storePage(author: String): String = "${BackendConfig.load().site}/dashboard?username=${java.net.URLEncoder.encode(author, Charsets.UTF_8)}"

    /** Where the jar was fetched from, when it was fetched from the server; null for a jar that was added locally */
    fun origin(name: String): JarOrigin? =
        runCatching { json.decodeFromString(JarOrigin.serializer(), originFile(File(name).name).readText()) }.getOrNull()

    /** The URL a jar of that name is fetched from first (the website), for a jar that is still only on the server */
    fun downloadUrl(name: String): String =
        "${BackendConfig.load().site}/api/jar-download?name=${java.net.URLEncoder.encode(File(name).name, Charsets.UTF_8)}"

    /** The website page that lists every package jar with its description */
    fun downloadsPage(): String = "${BackendConfig.load().site}/downloads"

    private fun originFile(fileName: String): File = File(pluginDir(), "$fileName.source.json")

    /** Where the sessions of a jar log what they were asked and answered */
    fun sessionsLogFile(fileName: String): File = File(pluginDir(), "${File(fileName).name}.sessions.log")

    /**
     * The name of the class a session would be opened on, found with a throwaway loader; null when no class in
     * the jar satisfies the contract. Used by the Models page to describe a jar without opening it.
     */
    fun entryClassName(file: File): String? {
        val env = ScriptEnvironments.current
        val parent = env?.classLoader(JarPackages::class.java.classLoader) ?: JarPackages::class.java.classLoader
        return URLClassLoader("amie-inspect-${file.name}", arrayOf(file.toURI().toURL()), parent).use { loader ->
            runCatching { findEntryClass(file, loader, PackageMetadata.read(file)?.entry)?.clazz?.name }.getOrNull()
        }
    }

    /** Closes the session with that id */
    fun close(id: Int): Boolean {
        val session = synchronized(this) { sessions[id] } ?: return false
        session.close()
        return true
    }

    @Synchronized
    fun closeAll() = sessions.values.toList().forEach { it.close() }

    /** [check] for a jar by name (found, or fetched, as initMod would), with the verdict printed */
    fun check(name: String): JarCheck {
        val result = runCatching { resolve(name)?.first?.let { check(it) } ?: JarCheck(problem = "$name not found") }
            .getOrElse { JarCheck(problem = it.message ?: it.javaClass.simpleName) }
        logInternal("check $name: ${result.describe}", if (result.ok) LogLevel.INFO else LogLevel.ERROR)
        return result
    }

    /**
     * The jars the GitHub repositories offer, one line each, also printed: those of AMP/github.json, or of [repo]
     * ("owner/repo"). Open one with initMod("x.jar"), or initMod("github:owner/repo/x.jar") for that repository's.
     */
    fun github(repo: String? = null): List<String> {
        val jars = if (repo == null) GitHubJars.available(refresh = true)
        else runCatching { GitHubJars.list(repo) }.getOrElse { logInternal("github: $repo: ${it.message}", LogLevel.ERROR); return emptyList() }
        val lines = jars.map { "${it.name} · ${it.size / 1024} KB · ${it.repo}${it.release?.let { r -> " release $r" } ?: ""} · github:${it.repo}/${it.path}" }
            .ifEmpty { listOf("no jars in ${repo ?: GitHubJars.settings().repos.joinToString().ifEmpty { "AMP/github.json (no repos listed)" }}") }
        lines.forEach { logInternal(it) }
        return lines
    }

    /** One line per open session, also printed to the console */
    fun print(): List<String> {
        val lines = open.map { it.toString() }.ifEmpty { listOf("no open packages") }
        lines.forEach { logInternal(it) }
        return lines
    }

    internal fun unregister(session: JarPackageSession) {
        synchronized(this) { sessions.remove(session.id) }
    }

    internal val calls = Executors.newCachedThreadPool { Thread(it, "amie-package-call").apply { isDaemon = true } }

    private fun pluginDir(): File = File(getDirectory().toString(), "pluginDir").apply { mkdirs() }

    private class Entry(val clazz: Class<*>, val run: Method, val close: Method?)

    /**
     * The class to instantiate: the one the metadata names when it satisfies the contract, else the manifest's
     * Main-Class when it does, else a class that implements an interface named AmiePackage, else the first class
     * in the jar that satisfies it.
     */
    private fun findEntryClass(file: File, loader: ClassLoader, preferred: String? = null): Entry? {
        val candidates = mutableListOf<Entry>()
        var mainClass: String? = null
        JarFile(file).use { jar ->
            mainClass = jar.manifest?.mainAttributes?.getValue("Main-Class")
            val names = jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }
                .filter { !it.endsWith("module-info") && !it.endsWith("package-info") }
                .toList()
            for (name in names) {
                val clazz = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
                contract(clazz)?.let { candidates += it }
            }
        }
        if (candidates.isEmpty()) return null
        preferred?.let { p -> candidates.firstOrNull { it.clazz.name == p }?.let { return it } }
        mainClass?.let { main -> candidates.firstOrNull { it.clazz.name == main }?.let { return it } }
        candidates.firstOrNull { c -> c.clazz.interfaces.any { it.simpleName == "AmiePackage" } }?.let { return it }
        return candidates.first()
    }

    /** The run/close methods when the class is a concrete public class with them and a no-arg constructor */
    private fun contract(clazz: Class<*>): Entry? {
        val mods = clazz.modifiers
        if (clazz.isInterface || clazz.isAnnotation || clazz.isEnum || Modifier.isAbstract(mods) || !Modifier.isPublic(mods)) return null
        val methods = runCatching { clazz.methods }.getOrNull() ?: return null
        val run = methods.firstOrNull {
            it.name == "run" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java &&
                !Modifier.isStatic(it.modifiers) && it.returnType != Void.TYPE
        } ?: return null
        val close = methods.firstOrNull { it.name == "close" && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
        runCatching { clazz.getConstructor() }.getOrNull() ?: return null
        return Entry(clazz, run, close)
    }
}
