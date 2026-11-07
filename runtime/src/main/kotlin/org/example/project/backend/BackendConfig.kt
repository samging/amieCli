package org.example.project.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import java.io.File

/**
 * Where the Amie backend (Spring Boot `packageRepository`) and the Amie website (Kobweb site) are.
 *
 * Saved in `AMP/backend.json`; the environment variables `AMIE_BACKEND_URL`, `AMIE_SITE_URL` and `AMIE_S3_URL`
 * override the file. The defaults are the local setup of the Amie project (`run.sh`): the website on 8081, the
 * S3 service on 8088 and the backend on 8080, or 8090 on a machine where Keycloak holds 8080.
 */
@Serializable
data class BackendConfig(
    val baseUrl: String = "http://localhost:8080",
    val siteUrl: String = "http://localhost:8081",
    /** The S3 service that keeps the package jars; asked directly only when the website cannot serve one */
    val s3ServiceUrl: String = "http://localhost:8088",
) {
    val base: String get() = baseUrl.trimEnd('/')
    val site: String get() = siteUrl.trimEnd('/')
    val s3: String get() = s3ServiceUrl.trimEnd('/')

    /**
     * The website page that lists what [username] uploaded. The site's `/dashboard` shows nothing without
     * `?username=` (only a hint to pick an author), so the parameter is sent whenever the user is known.
     */
    fun siteDashboardUrl(username: String?): String = site + siteDashboardPath(username)

    /** The dashboard as a path on the site, for the signed-in handoff (`AmieBackend.websiteUrl`) */
    fun siteDashboardPath(username: String?): String =
        if (username.isNullOrBlank()) "/dashboard" else "/dashboard?username=${enc(username)}"

    /** The website's upload page (`/navigateto`), where a signed-in user publishes a package jar by hand */
    fun siteUploadUrl(username: String?): String = site + siteUploadPath(username)

    fun siteUploadPath(username: String?): String =
        if (username.isNullOrBlank()) "/navigateto" else "/navigateto?username=${enc(username)}"

    val siteProfileUrl: String get() = "$site/profile"

    /** The site's own route listing one author's packages; `refresh=1` makes the site ask the backend again */
    fun siteUserPackagesUrl(username: String, refresh: Boolean = false): String =
        "$site/api/repo-by-username?username=${enc(username)}" + if (refresh) "&refresh=1" else ""

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    /** A backend URL for a path, or [slug] itself when it is already absolute */
    fun url(slug: String): String =
        if (slug.startsWith("http://") || slug.startsWith("https://")) slug else "$base/${slug.trimStart('/')}"

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        val file: File get() = File(getDirectory().toString(), "backend.json")

        @Volatile private var cached: BackendConfig? = null

        /** In place of the saved config, for tests that answer as the backend on a localhost port */
        @Volatile internal var override: BackendConfig? = null

        /** The saved config with environment overrides applied; writes the defaults when there is no file yet */
        fun load(): BackendConfig {
            override?.let { return it }
            cached?.let { return it }
            val saved = runCatching { json.decodeFromString<BackendConfig>(file.readText()) }.getOrNull()
                ?: BackendConfig().also { defaults ->
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(json.encodeToString(defaults))
                    }
                }
            fun env(name: String) = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
            return saved.copy(
                baseUrl = env("AMIE_BACKEND_URL") ?: saved.baseUrl,
                siteUrl = env("AMIE_SITE_URL") ?: saved.siteUrl,
                s3ServiceUrl = env("AMIE_S3_URL") ?: saved.s3ServiceUrl,
            ).also { cached = it }
        }

        fun save(config: BackendConfig) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(config))
            cached = config
        }

        @Volatile private var probed = false

        /**
         * Makes sure [BackendConfig.baseUrl] is the Amie backend and not Keycloak, once per run. On a machine that
         * runs both, Keycloak holds 8080 and the backend moves to 8090 (`run.sh` does this by itself), but a
         * `backend.json` written with the defaults still says 8080: every call then lands on Keycloak and answers
         * 404. When the address answers like a Keycloak realm and the same host answers on 8090 like the backend,
         * the config is switched to 8090 and saved, and the console says so. Nothing happens when the address is
         * right, unreachable, or set through `AMIE_BACKEND_URL`.
         */
        suspend fun resolve(): BackendConfig {
            val current = load()
            if (probed) return current
            probed = true
            if (System.getenv("AMIE_BACKEND_URL")?.isNotBlank() == true) return current
            val base = runCatching { java.net.URI(current.base) }.getOrNull() ?: return current
            if (base.port != 8080) return current
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!looksLikeKeycloak(current.base)) return@withContext current
                val alternative = "${base.scheme}://${base.host}:8090"
                if (!looksLikeBackend(alternative)) {
                    logInternal("backend: ${current.base} is Keycloak, not the Amie backend, and nothing answers on $alternative; set baseUrl in ${file.path}", LogLevel.ERROR)
                    return@withContext current
                }
                val fixed = current.copy(baseUrl = alternative)
                runCatching { save(fixed) }
                    .onFailure { logInternal("backend: couldn't save ${file.path}: ${it.message}", LogLevel.ERROR) }
                logInternal("backend: ${current.base} is Keycloak; using the Amie backend on $alternative (saved in ${file.path})")
                fixed
            }
        }

        private val probe: java.net.http.HttpClient by lazy {
            java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()
        }

        private fun fetch(url: String): Pair<Int, String>? = runCatching {
            val request = java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(3)).GET().build()
            val response = probe.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            response.statusCode() to response.body()
        }.getOrNull()

        /** A Keycloak server answers `/realms/<realm>` with the realm's public key */
        private fun looksLikeKeycloak(base: String): Boolean {
            val realm = org.example.project.auth.KeycloakConfig.load().realm
            val (status, body) = fetch("$base/realms/$realm") ?: return false
            return status == 200 && body.contains("\"public_key\"")
        }

        /** The Amie backend answers `/packages` with a JSON list */
        private fun looksLikeBackend(base: String): Boolean {
            val (status, body) = fetch("$base/packages") ?: return false
            return status == 200 && body.trimStart().startsWith("[")
        }
    }
}
