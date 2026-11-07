package org.example.project.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.auth.AuthState
import org.example.project.backend.AmieBackend
import org.example.project.backend.BackendException
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * One file as the cloud keeps it: the saved state `cloud-<first 32 hex of sha256(path)>` of the account (the backend's
 * `/state/{name}`, `GET` and `PUT`), a JSON document with the file's [path] relative to AMP, its [text], when it was
 * last written ([updatedAt], ISO instant) and the [hash] of what it held.
 */
@Serializable
data class CloudItem(val v: Int = 1, val path: String, val text: String, val updatedAt: String, val hash: String)

/** What the Templates pane says about the cloud: [kind] picks the line, [text] is the line */
data class CloudStatus(val kind: Kind, val text: String) {
    enum class Kind { Idle, Working, Saved, Failed }
}

/** What one round with the cloud did: the paths sent, the paths brought down, the other versions kept, and what failed and why */
data class CloudReport(
    val pushed: List<String> = emptyList(),
    val pulled: List<String> = emptyList(),
    /** Path -> the copy kept of the version that did not stay in place, because both sides had changed it */
    val kept: Map<String, File> = emptyMap(),
    val failures: Map<String, String> = emptyMap(),
)

/**
 * Saving to the cloud: when a person is logged in, what they save here (their scripts of AMP/templates, AMP/settings.json
 * and AMP/pluginDir/main.kts) is also kept under their account on the Amie backend, and what the account has that this Mac
 * doesn't comes down when they log in. Saving here is never touched by it: the local save has happened before anything
 * here runs, every call to the cloud is made off the UI thread, and a failure only leaves the file to be sent again on
 * the next save or the next login. Logged out, nothing here makes a request.
 *
 * The allowlist is [AccountBackup.allowed], used both ways like the backup's: a path that is not on it is never sent and a
 * document from the cloud that names one writes nothing. session.json, keycloak.json, databases.json, github.json, tokens,
 * models and logs stay on this machine. What was synced is kept in AMP/cloud-sync.json (hashes only, never sent).
 *
 * Which side is newer is told by a three-way comparison with the hash both sides had at the last sync: only one changed,
 * and that one wins; both changed (or this Mac never synced it and they differ), and the newer stays in place while the
 * other version is kept under AMP/pluginDir/backup (a script shows among Previous scripts in the Templates pane). A pull
 * also keeps what it replaces. A deletion here is not sent: the cloud keeps the item and a pull does not bring it back.
 */
object CloudSave {
    const val PREFIX = "cloud-"
    private const val MAIN_SCRIPT = "pluginDir/main.kts"
    private const val SETTINGS = "settings.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pretty = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    // The fields of a template's json that differ per Mac and are no part of what it says: they are left out of its hash
    private val perMac = listOf("loads", "lastLoadedAt", "createdAt", "updatedAt")
    private val templateMeta = Regex("""templates/[^/]+\.json""")

    private val _status = MutableStateFlow(CloudStatus(CloudStatus.Kind.Idle, ""))
    val status: StateFlow<CloudStatus> = _status.asStateFlow()

    // The paths a pull wrote, for the panes that show them (the Templates list, the editor)
    private val _pulled = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val pulled: SharedFlow<String> = _pulled.asSharedFlow()

    /** How long saves are gathered before they go out, so typing in the editor sends once and not at every pause */
    @Volatile internal var debounceMs = 2_000L
    /** When the cloud said it takes no more for now (429), the wait before the whole folder is looked at again */
    @Volatile internal var rateLimitWaitMs = 70_000L
    /** Off with `AMIE_CLOUD_SAVE=off`: saving and logging in then never start a round with the cloud (the Backup in Settings is unaffected) */
    @Volatile internal var enabled: Boolean = System.getenv("AMIE_CLOUD_SAVE")?.trim()?.lowercase() != "off"
    /** The AMP folder; tests give their own */
    @Volatile internal var rootOverride: File? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val round = Mutex()
    private val dirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var everything = false
    private var scheduled: Job? = null
    private val stateLock = Any()

    private fun root() = rootOverride ?: File(getDirectory().toString())

    /** The name of the saved state that holds [path] */
    fun stateName(path: String): String = PREFIX + sha256(path).take(32)

    // --- when it runs ---

    /**
     * Called after [path] was written here (a template, settings, main.kts). Logged out, or for a path the allowlist
     * doesn't know, it returns at once and nothing goes anywhere; logged in, the path is sent a moment later from a
     * background coroutine.
     */
    fun saved(path: String) {
        if (!enabled || !AuthState.signedIn || !AccountBackup.allowed(path)) return
        dirty += path
        _status.value = CloudStatus(CloudStatus.Kind.Working, "Saved here. Saving to the cloud…")
        schedule(debounceMs)
    }

    /** Called when a person is logged in (a login, or the app starting with a kept session): everything is looked at, both ways */
    fun onLogin() {
        if (!enabled || !AuthState.signedIn) return
        everything = true
        _status.value = CloudStatus(CloudStatus.Kind.Working, "Looking at what your account has…")
        schedule(0)
    }

    private fun schedule(wait: Long) {
        synchronized(stateLock) {
            scheduled?.cancel()
            scheduled = scope.launch {
                delay(wait)
                withContext(NonCancellable) { drain() }
            }
        }
    }

    /** Forgets what was gathered and what failed, as at a start of the app; for tests */
    internal fun reset() {
        synchronized(stateLock) { scheduled?.cancel(); scheduled = null }
        dirty.clear()
        everything = false
        _status.value = CloudStatus(CloudStatus.Kind.Idle, "")
    }

    /** Waits until nothing is gathered or on its way; for tests */
    internal suspend fun idle() {
        while (true) {
            val job = synchronized(stateLock) { scheduled }
            job?.join()
            round.withLock { }
            if (synchronized(stateLock) { scheduled === job } && dirty.isEmpty()) return
        }
    }

    private suspend fun drain(): Unit = round.withLock {
        val paths = dirty.toList().also { dirty.removeAll(it.toSet()) }
        val all = everything
        everything = false
        if (!AuthState.signedIn) return@withLock
        val report = try {
            if (all) reconcile(root(), null) else reconcile(root(), paths)
        } catch (e: Exception) {
            CloudReport(failures = mapOf("" to plainReason(e)))
        }
        if (report.failures.isNotEmpty()) {
            // Whatever failed stays different from what was synced, and the next round looks at everything
            everything = true
            if (report.failures.values.any { it.contains("a minute") }) {
                synchronized(stateLock) { scheduled = scope.launch { delay(rateLimitWaitMs); withContext(NonCancellable) { drain() } } }
            }
        }
        _status.value = say(report, all)
    }

    /** The line the Templates pane shows for what a round did */
    internal fun say(report: CloudReport, login: Boolean = false): CloudStatus {
        val down = if (report.pulled.isEmpty()) "" else " ${count(report.pulled.size, "file")} came down from your account."
        val both = report.kept.entries.joinToString(" ") { (path, copy) -> "Both ${nameOf(path)} here and in the cloud had changed: the other version is kept as ${copy.name} under AMP/pluginDir/backup." }
        return if (report.failures.isEmpty()) {
            CloudStatus(CloudStatus.Kind.Saved, ((if (login) "Up to date with your account." else "Saved here and to the cloud.") + "$down $both").trim())
        } else {
            val why = report.failures.values.distinct().joinToString("; ")
            CloudStatus(CloudStatus.Kind.Failed, ((if (login) "Couldn't reach your account's files" else "Saved here. Not saved to the cloud") + ": $why. It will be tried again on the next save and when you log in.$down $both").trim())
        }
    }

    private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"
    private fun nameOf(path: String) = path.substringAfterLast('/')

    // --- one round ---

    /**
     * Brings the cloud and [root] together: for each of [only] (or, with null, every allowed path the folder or the cloud
     * has) compares the file here, the item there and what both held at the last sync, sends or brings down what only one
     * side changed, and keeps both versions where both did. A request that fails stops the round when it says nothing
     * will work (no connection, a refused login, too many requests) and is noted per path otherwise; what failed is
     * left as it was, to be tried again.
     */
    internal suspend fun reconcile(root: File, only: Collection<String>?): CloudReport = withContext(Dispatchers.IO) {
        val user = AuthState.session.value?.username
        if (user == null) return@withContext CloudReport(failures = mapOf("" to "you are not logged in"))
        val base = readBase(root, user).toMutableMap()
        val pushed = mutableListOf<String>()
        val pulled = mutableListOf<String>()
        val kept = linkedMapOf<String, File>()
        val failures = linkedMapOf<String, String>()
        try {
            val listed: Set<String>? = if (only == null) {
                try { AmieBackend.listStates().filter { it.startsWith(PREFIX) }.toSet() } catch (e: Exception) { failures[""] = plainReason(e); return@withContext CloudReport(failures = failures) }
            } else null
            val remotePaths = mutableMapOf<String, CloudItem?>()
            val unreadable = mutableSetOf<String>()
            if (listed != null) listed.forEach { name ->
                try {
                    val item = fetch(name)
                    if (item != null && stateName(item.path) == name) remotePaths[item.path] = item
                } catch (e: Exception) {
                    failures[name] = plainReason(e)
                    unreadable += name
                    if (stops(e)) return@withContext CloudReport(pushed, pulled, kept, failures)
                }
            }
            val paths = (only?.toList() ?: (AccountBackup.paths(root) + remotePaths.keys)).filter(AccountBackup::allowed).distinct()
                .sortedWith(compareBy({ it.endsWith(".json") && it.startsWith("templates/") }, { it }))
            for (path in paths) {
                if (isStarter(root, path)) continue
                try {
                    // A copy in the cloud that couldn't be read is left alone: sending over it would lose it
                    if (stateName(path) in unreadable) continue
                    val outcome = sync(root, path, base, remotePaths[path], remoteKnown = listed != null)
                    when (outcome) {
                        Outcome.Pushed -> pushed += path
                        Outcome.Pulled -> { pulled += path; _pulled.tryEmit(path) }
                        is Outcome.Both -> { kept[path] = outcome.copy; if (outcome.pushed) pushed += path else { pulled += path; _pulled.tryEmit(path) } }
                        Outcome.Same -> {}
                    }
                } catch (e: Exception) {
                    failures[path] = plainReason(e, path)
                    logInternal("cloud: $path not synced: ${e.message}", LogLevel.ERROR)
                    if (stops(e)) break
                }
            }
        } finally {
            writeBase(root, user, base)
        }
        if (SETTINGS in pulled) runCatching { AppSettings.reload() }
        if (pushed.isNotEmpty() || pulled.isNotEmpty()) logInternal("cloud: sent ${pushed.size} and brought down ${pulled.size} files for $user")
        CloudReport(pushed, pulled, kept, failures)
    }

    private sealed interface Outcome {
        data object Same : Outcome
        data object Pushed : Outcome
        data object Pulled : Outcome
        /** Both sides had changed: [copy] is the version that did not stay in place; [pushed] says it was this Mac's that stayed */
        data class Both(val copy: File, val pushed: Boolean) : Outcome
    }

    private class Local(val text: String, val hash: String, val modified: Long)

    private suspend fun sync(root: File, path: String, base: MutableMap<String, String>, listedRemote: CloudItem?, remoteKnown: Boolean): Outcome {
        val local = readLocal(root, path)
        val remote: CloudItem? = if (remoteKnown) listedRemote else fetch(stateName(path))?.takeIf { it.path == path }
        val lh = local?.hash
        val rh = remote?.let { hashOf(path, it.text) }
        val was = base[path]
        return when {
            lh == null && rh == null -> { base.remove(path); Outcome.Same }
            lh == rh -> { base[path] = lh!!; Outcome.Same }
            rh == null -> { push(path, local!!); base[path] = lh!!; Outcome.Pushed }
            // Gone here on purpose (it was synced and the cloud still has that version): not brought back, not deleted there
            lh == null -> if (was != null && was == rh) Outcome.Same else { pull(root, path, remote!!, null); base[path] = rh; Outcome.Pulled }
            was == lh -> { pull(root, path, remote!!, local); base[path] = rh; Outcome.Pulled }
            was == rh -> { push(path, local!!); base[path] = lh; Outcome.Pushed }
            else -> {
                // Both changed: the newer stays in place, the other is kept
                val theirs = runCatching { Instant.parse(remote!!.updatedAt).toEpochMilli() }.getOrDefault(0L)
                if (local!!.modified >= theirs) {
                    val copy = keepCopy(root, path, remote!!.text, "cloud")
                    push(path, local); base[path] = lh!!
                    Outcome.Both(copy, pushed = true)
                } else {
                    val copy = keepCopy(root, path, local.text, "this-mac")
                    pull(root, path, remote!!, local, keep = false); base[path] = rh
                    Outcome.Both(copy, pushed = false)
                }
            }
        }
    }

    private suspend fun fetch(name: String): CloudItem? {
        val text = AmieBackend.loadState(name) ?: return null
        return runCatching { json.decodeFromString<CloudItem>(text) }.getOrElse { throw CloudItemException("a copy in the cloud can't be read: ${it.message}") }
    }

    private suspend fun push(path: String, local: Local) {
        if (!AccountBackup.allowed(path)) throw BackendException("$path is not a file the cloud keeps")
        val item = CloudItem(path = path, text = local.text, updatedAt = Instant.ofEpochMilli(local.modified).toString(), hash = local.hash)
        val body = json.encodeToString(item)
        if (body.toByteArray().size > AmieBackend.MAX_STATE_BYTES) throw BackendException("$path is too large for the cloud", 413)
        AmieBackend.saveState(stateName(path), body)
    }

    private fun pull(root: File, path: String, remote: CloudItem, local: Local?, keep: Boolean = true) {
        val target = AccountBackup.inside(root, path)?.takeIf { AccountBackup.allowed(path) } ?: throw BackendException("$path is not a file the cloud writes")
        // What the pull replaces is kept first, as the restore keeps it: an editor still holding it must not be the only place
        if (keep && local != null && !templateMeta.matches(path)) keepCopy(root, path, local.text, "this-mac")
        var text = remote.text
        if (local != null && templateMeta.matches(path)) text = withLocalUse(local.text, text)
        target.parentFile?.mkdirs()
        // A template's script comes down before its json, which is what makes the sandbox list it
        target.writeBytes(text.toByteArray())
        runCatching { Instant.parse(remote.updatedAt).toEpochMilli().takeIf { it > 0 } }.getOrNull()?.let { target.setLastModified(it) }
    }

    // How often this Mac loaded a template is this Mac's own: a template that comes down keeps it
    private fun withLocalUse(here: String, theirs: String): String = runCatching {
        val mine = json.parseToJsonElement(here).jsonObject
        val merged = json.parseToJsonElement(theirs).jsonObject.toMutableMap()
        listOf("loads", "lastLoadedAt").forEach { key -> mine[key]?.let { merged[key] = it } }
        pretty.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }.getOrDefault(theirs)

    /** [text] as a copy beside the scripts the editor replaced: main-cloud-20261101-101500.kts */
    private fun keepCopy(root: File, path: String, text: String, whose: String): File {
        val dir = File(root, "pluginDir/backup").apply { mkdirs() }
        val name = File(path).nameWithoutExtension + "-$whose-" + stampFormat.format(LocalDateTime.now())
        val ext = File(path).extension
        return generateSequence(1) { it + 1 }.map { n -> File(dir, (if (n == 1) name else "$name-$n") + ".$ext") }.first { !it.exists() }.also { it.writeText(text) }
    }

    // --- reading here ---

    private fun readLocal(root: File, path: String): Local? {
        val file = AccountBackup.inside(root, path)?.takeIf { it.isFile } ?: return null
        val text = AccountBackup.text(file, path)
        return Local(text, hashOf(path, text), file.lastModified())
    }

    // A starter the app ships and nobody saved over is the same on every Mac: it is not sent, and the cloud's is not written over it
    private fun isStarter(root: File, path: String): Boolean {
        if (!path.startsWith("templates/")) return false
        val meta = File(root, "templates/" + File(path).nameWithoutExtension + ".json")
        return runCatching { json.parseToJsonElement(meta.readText()).jsonObject["source"]?.jsonPrimitive?.content == "starter" }.getOrDefault(false)
    }

    internal fun hashOf(path: String, text: String): String {
        if (!templateMeta.matches(path)) return sha256(text)
        val normal = runCatching { JsonObject(json.parseToJsonElement(text).jsonObject.filterKeys { it !in perMac }).toString() }.getOrDefault(text)
        return sha256(normal)
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // --- what was synced (never sent) ---

    @Serializable
    private data class SyncFile(val users: Map<String, Map<String, String>> = emptyMap())

    private fun readBase(root: File, user: String): Map<String, String> = synchronized(stateLock) {
        runCatching { json.decodeFromString<SyncFile>(File(root, "cloud-sync.json").readText()).users[user] }.getOrNull().orEmpty()
    }

    private fun writeBase(root: File, user: String, base: Map<String, String>) = synchronized(stateLock) {
        val file = File(root, "cloud-sync.json")
        val all = runCatching { json.decodeFromString<SyncFile>(file.readText()).users }.getOrDefault(emptyMap())
        runCatching { file.parentFile?.mkdirs(); file.writeText(pretty.encodeToString(SyncFile(all + (user to base)))) }
            .onFailure { logInternal("cloud: couldn't keep ${file.name}: ${it.message}", LogLevel.ERROR) }
    }

    // --- failures in words ---

    private class CloudItemException(message: String) : Exception(message)

    private fun stops(e: Exception): Boolean = e is BackendException && (e.status == null || e.status == 401 || e.status == 429 || (e.status ?: 0) >= 500) || e is IOException

    /** Why a call failed, short and plain; the console and the debug log have the long form */
    internal fun plainReason(e: Exception, path: String? = null): String {
        if (e is BackendException) return when (e.status) {
            null -> if (e.message.orEmpty().startsWith("Sign in") || e.message.orEmpty().startsWith("Couldn't renew")) "your login couldn't be renewed" else if (e.cause != null || e.message.orEmpty().contains("unreachable")) "the server can't be reached" else e.message.orEmpty().replaceFirstChar { it.lowercase() }
            401 -> "the cloud didn't accept your login, log in again"
            403 -> "the cloud refused this account"
            413 -> "${path?.let { nameOf(it) } ?: "a file"} is too large for the cloud"
            429 -> "the cloud takes 20 saves a minute, the rest follow in a minute"
            in 500..599 -> "the cloud's server had a problem (${e.status})"
            else -> "the cloud answered ${e.status}"
        }
        if (e is IOException) return "the server can't be reached"
        return e.message ?: e.javaClass.simpleName
    }
}
