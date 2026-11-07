package org.example.project.components.terminal.window.controller.pluginEngine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import org.example.project.util.Secrets
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** AMP/github.json: the repositories a jar that is nowhere local is looked for in, and a token for private ones */
@Serializable
data class GitHubSettings(
    val repos: List<String> = listOf("samging/jar-jar"),
    /** For private repositories and GitHub's higher rate limit; the GITHUB_TOKEN environment variable wins */
    val token: String? = null,
)

/**
 * A jar in a GitHub repository, as a script names it: `github:owner/repo/[dir/]name.jar[@ref]`, or the address of
 * the file on github.com (`https://github.com/owner/repo/blob/<ref>/name.jar`). Without a ref, the default branch;
 * a name that is not a file of the repository is looked for among the assets of the release (the latest one, or
 * the one tagged ref).
 */
data class GitHubSpec(val repo: String, val path: String, val ref: String? = null) {
    val fileName: String get() = path.substringAfterLast('/')

    override fun toString(): String = "github:$repo/$path${ref?.let { "@$it" } ?: ""}"

    companion object {
        fun parse(spec: String): GitHubSpec? {
            val s = spec.trim()
            if (s.startsWith("https://github.com/", ignoreCase = true)) {
                // owner/repo/blob|raw/<ref>/<path>
                val parts = s.substringAfter("github.com/").substringBefore('?').split('/').filter { it.isNotBlank() }
                if (parts.size < 5 || parts[2] !in setOf("blob", "raw")) return null
                return GitHubSpec("${parts[0]}/${parts[1]}", parts.drop(4).joinToString("/"), parts[3]).takeIf { it.path.endsWith(".jar", ignoreCase = true) }
            }
            if (!s.startsWith("github:", ignoreCase = true)) return null
            val body = s.substring("github:".length).trim().removePrefix("//")
            val at = body.lastIndexOf('@')
            val location = if (at > 0) body.substring(0, at) else body
            val ref = if (at > 0) body.substring(at + 1).trim().ifBlank { null } else null
            val parts = location.split('/').filter { it.isNotBlank() }
            if (parts.size < 3) return null
            return GitHubSpec("${parts[0]}/${parts[1]}", parts.drop(2).joinToString("/"), ref).takeIf { it.path.endsWith(".jar", ignoreCase = true) }
        }
    }
}

/** A jar a repository offers: a file of it, or an asset of its release */
data class GitHubJar(
    val repo: String,
    val name: String,
    val path: String,
    val size: Long,
    /** What identifies this version: the file's blob sha, or the release asset's id and update time */
    val version: String,
    /** The API address its bytes are read from */
    val download: String,
    /** The page on github.com that shows it */
    val page: String,
    /** The release tag, for an asset of a release */
    val release: String? = null,
)

@Serializable
private data class ContentItem(val name: String, val path: String, val type: String = "file", val size: Long = 0, val sha: String = "", val url: String = "", val html_url: String? = null)

@Serializable
private data class ReleaseAsset(val id: Long, val name: String, val size: Long = 0, val url: String, val updated_at: String = "")

@Serializable
private data class Release(val tag_name: String, val html_url: String = "", val assets: List<ReleaseAsset> = emptyList())

/**
 * Package jars from GitHub repositories. `initMod("github:owner/repo/x.jar")` takes the jar from that repository;
 * a plain `initMod("x.jar")` that is nowhere local and not on the Amie server is looked for in the repositories of
 * `AMP/github.json`. A downloaded jar is kept in `AMP/pluginDir` only when [JarPackages.check] finds a package class
 * in it; its origin (repository, version, page) is written beside it, and the next open of the same version is local.
 */
object GitHubJars {
    private const val API = "https://api.github.com"
    private const val LISTING_TTL_MS = 5 * 60_000L
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()

    @Volatile private var listing: Pair<Long, List<GitHubJar>>? = null

    private fun settingsFile() = File(getDirectory().toString(), "github.json")

    /** AMP/github.json, written with the defaults the first time so it can be edited */
    fun settings(): GitHubSettings {
        val file = settingsFile()
        if (!file.isFile) {
            runCatching { file.writeText(json.encodeToString(GitHubSettings.serializer(), GitHubSettings())) }
            return GitHubSettings()
        }
        return runCatching { json.decodeFromString(GitHubSettings.serializer(), file.readText()) }
            .onFailure { logInternal("github: ${file.path} is not valid: ${it.message}", LogLevel.ERROR) }
            .getOrDefault(GitHubSettings())
    }

    private fun token(): String? = Secrets.get("GITHUB_TOKEN")?.takeIf { it.isNotBlank() } ?: settings().token?.takeIf { it.isNotBlank() }

    /** Every jar the configured repositories offer (their top folder and their latest release), cached five minutes */
    fun available(refresh: Boolean = false): List<GitHubJar> {
        val now = System.currentTimeMillis()
        listing?.let { (at, jars) -> if (!refresh && now - at < LISTING_TTL_MS) return jars }
        val jars = settings().repos.flatMap { repo ->
            runCatching { list(repo) }.onFailure { logInternal("github: $repo not listed: ${it.message}", LogLevel.ERROR) }.getOrDefault(emptyList())
        }
        listing = now to jars
        return jars
    }

    /** The jars of one repository: the files of its top folder (or [dir]) and the assets of its latest release */
    fun list(repo: String, dir: String = "", ref: String? = null): List<GitHubJar> {
        val files = get("$API/repos/$repo/contents/${encodePath(dir)}${refQuery(ref)}", "application/vnd.github+json")?.let { body ->
            parseContents(repo, String(body), ref)
        }.orEmpty()
        val release = get("$API/repos/$repo/releases/latest", "application/vnd.github+json")?.let { parseRelease(repo, String(it)) }.orEmpty()
        return files + release.filter { r -> files.none { it.name == r.name } }
    }

    internal fun parseContents(repo: String, body: String, ref: String?): List<GitHubJar> {
        val element = json.parseToJsonElement(body)
        val items = if (element is kotlinx.serialization.json.JsonArray) json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(ContentItem.serializer()), element)
        else listOf(json.decodeFromJsonElement(ContentItem.serializer(), element))
        return items.filter { it.type == "file" && it.name.endsWith(".jar", ignoreCase = true) }.map {
            GitHubJar(repo, it.name, it.path, it.size, it.sha, "$API/repos/$repo/contents/${encodePath(it.path)}${refQuery(ref)}",
                it.html_url ?: "https://github.com/$repo/blob/${ref ?: "HEAD"}/${it.path}")
        }
    }

    internal fun parseRelease(repo: String, body: String): List<GitHubJar> {
        val release = json.decodeFromString(Release.serializer(), body)
        return release.assets.filter { it.name.endsWith(".jar", ignoreCase = true) }.map {
            GitHubJar(repo, it.name, it.name, it.size, "asset:${it.id}:${it.updated_at}", it.url,
                release.html_url.ifBlank { "https://github.com/$repo/releases/tag/${release.tag_name}" }, release.tag_name)
        }
    }

    /** The jar a spec names, as the repository has it now; throws with the reason when it has none */
    fun find(spec: GitHubSpec): GitHubJar {
        get("$API/repos/${spec.repo}/contents/${encodePath(spec.path)}${refQuery(spec.ref)}", "application/vnd.github+json")?.let { body ->
            parseContents(spec.repo, String(body), spec.ref).firstOrNull()?.let { return it }
        }
        val release = if (spec.ref == null) "$API/repos/${spec.repo}/releases/latest" else "$API/repos/${spec.repo}/releases/tags/${URLEncoder.encode(spec.ref, Charsets.UTF_8)}"
        get(release, "application/vnd.github+json")?.let { body ->
            parseRelease(spec.repo, String(body)).firstOrNull { it.name == spec.fileName }?.let { return it }
        }
        throw IOException("$spec: no such file in ${spec.repo}${spec.ref?.let { " at $it" } ?: ""} and no release asset named ${spec.fileName}")
    }

    /**
     * The jar of a `github:` spec in AMP/pluginDir: the copy there when it is the repository's current version,
     * else downloaded and checked. When GitHub cannot be reached, a copy from the same repository is used as it is.
     */
    fun resolve(spec: GitHubSpec): File {
        val local = File(pluginDir(), spec.fileName)
        val origin = JarPackages.origin(spec.fileName)
        if (local.isFile && origin?.repo != spec.repo) {
            // A jar put there another way is the repository's when its bytes are the file's (same git blob sha)
            val remote = find(spec)
            if (remote.release == null && blobSha(local) == remote.version) {
                JarPackages.writeOrigin(JarOrigin(remote.name, remote.download, remote.page, null, local.length(), repo = remote.repo, sha = remote.version))
                logInternal("github: ${spec.fileName} in AMP/pluginDir is the one in ${spec.repo}; using it")
                return local
            }
            throw IllegalStateException("AMP/pluginDir already has a different ${spec.fileName} (from ${origin?.url ?: "this machine"}); remove it to take the one from $spec")
        }
        val remote = try {
            find(spec)
        } catch (e: IOException) {
            if (local.isFile) {
                logInternal("github: ${e.message}; using the copy from ${spec.repo} already in AMP/pluginDir", LogLevel.ERROR)
                return local
            }
            throw e
        }
        if (local.isFile && origin?.version == remote.version) return local
        return download(remote)
    }

    /** A jar of that name from the configured repositories, downloaded and checked; null when none offers it */
    fun fetch(name: String): File? {
        val fileName = File(name).name
        val jar = available().firstOrNull { it.name == fileName } ?: return null
        return runCatching { download(jar) }.onFailure { logInternal("github: ${it.message}", LogLevel.ERROR) }.getOrNull()
    }

    /** Reads the jar's bytes and hands them to [JarPackages.receive], which keeps them only when they pass the check */
    fun download(jar: GitHubJar): File {
        val accept = if (jar.release != null) "application/octet-stream" else "application/vnd.github.raw"
        val bytes = get(jar.download, accept) ?: throw IOException("${jar.repo}/${jar.path}: GitHub answered nothing")
        val origin = JarOrigin(jar.name, jar.download, jar.page, jar.release, bytes.size.toLong(), repo = jar.repo, sha = jar.version)
        return JarPackages.receive(jar.name, bytes, origin)
    }

    /**
     * A GET to GitHub: null on 404, the reason thrown on anything else. Redirects (release assets live on another
     * host) are followed by hand, so the token is only ever sent to GitHub's API.
     */
    private fun get(url: String, accept: String): ByteArray? {
        var address = url
        repeat(5) {
            val toApi = URI.create(address).host == "api.github.com"
            val request = HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(60))
                .header("Accept", accept).header("User-Agent", "amie-desktop").header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (toApi) token()?.let { header("Authorization", "Bearer $it") } }
                .GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            when (response.statusCode()) {
                200 -> return response.body()
                301, 302, 303, 307, 308 -> address = response.headers().firstValue("Location").orElseThrow { IOException("$address: a redirect without a Location") }
                404 -> return null
                401 -> throw IOException("GitHub refused the token (401); fix it in AMP/github.json or GITHUB_TOKEN")
                403, 429 -> throw IOException(
                    if (response.headers().firstValue("x-ratelimit-remaining").orElse("") == "0") "GitHub's rate limit is used up (60 an hour without a token); put a token in AMP/github.json or GITHUB_TOKEN"
                    else "GitHub answered ${response.statusCode()} for $address"
                )
                else -> throw IOException("GitHub answered ${response.statusCode()} for $address")
            }
        }
        throw IOException("$url: too many redirects")
    }

    /** The sha git gives the file's bytes as a blob, what the contents API reports as a file's sha */
    internal fun blobSha(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray())
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun refQuery(ref: String?) = ref?.let { "?ref=" + URLEncoder.encode(it, Charsets.UTF_8) } ?: ""

    private fun encodePath(path: String) = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

    private fun pluginDir(): File = File(getDirectory().toString(), "pluginDir").apply { mkdirs() }
}
