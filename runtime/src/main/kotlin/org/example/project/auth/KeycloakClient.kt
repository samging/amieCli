package org.example.project.auth

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import java.awt.Desktop
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** What went wrong while talking to Keycloak, in words the login page can show */
/**
 * Scopes every sign-in asks for. offline_access makes Keycloak issue an offline refresh token, which outlives the
 * realm's SSO session (30 min idle by default) and only expires after the realm's offline idle timeout (30 days by
 * default, renewed every time it is used), so the app can sign the same user back in days later.
 */
const val OFFLINE_SCOPE = "openid profile email offline_access"

open class AuthException(message: String, val status: Int? = null, cause: Throwable? = null) : Exception(message, cause)

/** The account exists but Keycloak wants the email verified before it signs in; the mail has been sent */
class EmailVerificationPending(val username: String, val email: String) :
    AuthException("Account '$username' is created. Open the verification link Keycloak sent to $email, then sign in with your username")

@Serializable
data class OpenIdConfiguration(val issuer: String = "", val token_endpoint: String = "", val registration_endpoint: String = "")

@Serializable
private data class TokenResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val id_token: String? = null,
    val expires_in: Long = 300,
    val refresh_expires_in: Long = 0,
    val token_type: String = "Bearer",
)

/** What the register page collects; Keycloak's registration form takes the same fields */
data class RegistrationForm(
    val username: String,
    val email: String,
    val password: String,
    val firstName: String = "",
    val lastName: String = "",
)

/**
 * Signs users in against Keycloak the way the Amie web app does, but from the desktop:
 *  - username + password through the token endpoint (grant_type=password, "Direct access grants" on the client)
 *  - the hosted login or registration page in the system browser, returning to a loopback redirect with PKCE
 *  - in-app registration by submitting Keycloak's own registration form (the sso-proxy flow of the Amie site)
 *  - refresh and logout through the realm endpoints
 * Every access token is verified against the realm's JWKS, issuer and expiry before it becomes a session.
 */
class KeycloakClient(val config: KeycloakConfig, engine: HttpClientEngine = Java.create()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    // The engine is a parameter so tests can answer as Keycloak with a mock engine
    private val http = HttpClient(engine) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 15_000
        }
        expectSuccess = false
    }

    // The realm may publish a different issuer than the URL it is reached on (a frontend URL); tokens carry that one
    @Volatile private var discovered: OpenIdConfiguration? = null

    /** The realm's discovery document; throws when Keycloak isn't reachable */
    suspend fun discover(): OpenIdConfiguration = try {
        val response = http.get(config.discoveryUrl)
        if (!response.status.isSuccess()) throw AuthException("Keycloak answered ${response.status.value} for realm '${config.realm}'", response.status.value)
        json.decodeFromString<OpenIdConfiguration>(response.bodyAsText()).also { discovered = it }
    } catch (e: AuthException) {
        throw e
    } catch (e: Exception) {
        throw AuthException("Keycloak is not reachable at ${config.baseUrl}: ${e.message ?: e.javaClass.simpleName}", cause = e)
    }

    /** The issuer tokens must carry: what the realm publishes, else the configured URL */
    suspend fun expectedIssuer(): String =
        (discovered ?: runCatching { discover() }.getOrNull())?.issuer?.takeIf { it.isNotBlank() } ?: config.issuer

    /* ---------- grants ---------- */

    suspend fun passwordLogin(username: String, password: String): AuthSession {
        if (username.isBlank() || password.isBlank()) throw AuthException("Enter a username and a password")
        return token(Parameters.build {
            append("grant_type", "password")
            append("username", username.trim())
            append("password", password)
            append("scope", OFFLINE_SCOPE)
        })
    }

    suspend fun refresh(refreshToken: String): AuthSession = token(Parameters.build {
        append("grant_type", "refresh_token")
        append("refresh_token", refreshToken)
    })

    suspend fun exchangeCode(code: String, codeVerifier: String): AuthSession = token(Parameters.build {
        append("grant_type", "authorization_code")
        append("code", code)
        append("redirect_uri", config.redirectUri)
        append("code_verifier", codeVerifier)
    })

    suspend fun logout(refreshToken: String) {
        val response = http.submitForm(config.logoutEndpoint, Parameters.build {
            append("client_id", config.clientId)
            if (config.clientSecret.isNotEmpty()) append("client_secret", config.clientSecret)
            append("refresh_token", refreshToken)
        })
        if (!response.status.isSuccess() && response.status != HttpStatusCode.BadRequest) {
            throw AuthException("Logout answered ${response.status.value}: ${errorOf(response.bodyAsText())}", response.status.value)
        }
    }

    private suspend fun token(grant: Parameters): AuthSession {
        val response = try {
            http.submitForm(config.tokenEndpoint, Parameters.build {
                append("client_id", config.clientId)
                if (config.clientSecret.isNotEmpty()) append("client_secret", config.clientSecret)
                appendAll(grant)
            })
        } catch (e: Exception) {
            throw AuthException("Keycloak is not reachable at ${config.baseUrl}: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw AuthException(explain(response.status.value, body), response.status.value)
        val tokens = json.decodeFromString<TokenResponse>(body)
        return sessionOf(tokens)
    }

    /* ---------- browser flows (RFC 8252 loopback redirect) ---------- */

    /** Opens Keycloak's login page in the browser and waits for it to come back with a code */
    suspend fun browserLogin(): AuthSession = browserFlow(register = false)

    /** Opens Keycloak's registration page in the browser; the new user is signed in when it comes back */
    suspend fun browserRegister(): AuthSession = browserFlow(register = true)

    private suspend fun browserFlow(register: Boolean): AuthSession = withContext(Dispatchers.IO) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw AuthException("This system can't open a browser; sign in with username and password instead")
        }
        val verifier = randomToken(64)
        val state = randomToken(24)
        val url = config.browserUrl(register, state, challenge(verifier))

        val callback = CompletableFuture<Map<String, String>>()
        val server = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", config.redirectPort), 0)
        } catch (e: Exception) {
            throw AuthException("Port ${config.redirectPort} is busy, the browser can't redirect back; change redirectPort in ${KeycloakConfig.file.name}", cause = e)
        }
        server.createContext("/callback") { exchange ->
            val params = query(exchange.requestURI.rawQuery)
            val ok = params["code"] != null && params["state"] == state
            val page = if (ok) "<h2>Signed in to amie</h2><p>You can close this tab and return to the app.</p>"
            else "<h2>Sign-in failed</h2><p>${params["error_description"] ?: params["error"] ?: "No code received"}</p>"
            val bytes = "<html><body style='font-family:sans-serif;background:#0e0e0e;color:#e8eaed;padding:40px'>$page</body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            callback.complete(params)
        }
        server.start()
        try {
            logInternal("auth: opening ${if (register) "registration" else "login"} page in the browser")
            Desktop.getDesktop().browse(URI(url))
            val params = withTimeoutOrNull(TimeUnit.MINUTES.toMillis(5)) {
                withContext(Dispatchers.IO) { callback.get() }
            } ?: throw AuthException("The browser didn't come back within 5 minutes")
            if (params["state"] != state) throw AuthException("The browser came back with a different state; try again")
            val code = params["code"] ?: throw AuthException(params["error_description"] ?: params["error"] ?: "No code came back from Keycloak")
            exchangeCode(code, verifier)
        } finally {
            server.stop(0)
        }
    }

    /* ---------- in-app registration ---------- */

    /**
     * Creates the account by submitting Keycloak's registration form server-to-server, as the Amie site's
     * sso-proxy does: GET the page for its form action and session cookie, POST the fields, read the error
     * or the code on the redirect back, then exchange the code. Needs "User registration" on in the realm.
     */
    suspend fun register(form: RegistrationForm): AuthSession = withContext(Dispatchers.IO) {
        form.validate()
        val verifier = randomToken(64)
        val state = randomToken(24)
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val client = java.net.http.HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build()

        val page = try {
            client.send(
                java.net.http.HttpRequest.newBuilder(URI(config.browserUrl(register = true, state = state, codeChallenge = challenge(verifier)))).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
            )
        } catch (e: Exception) {
            throw AuthException("Keycloak is not reachable at ${config.baseUrl}: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
        if (page.statusCode() != 200) {
            throw AuthException(
                "Keycloak answered ${page.statusCode()} for the registration page: ${errorOf(page.body())}. " +
                    "Check that '${config.clientId}' allows ${config.redirectUri} and that the realm has user registration on",
                page.statusCode()
            )
        }
        val action = formAction(page.body())
            ?.let { sameServer(it) }
            ?: throw AuthException("Keycloak has no registration form; turn on 'User registration' in realm '${config.realm}' login settings")

        val fields = mapOf(
            "firstName" to form.firstName,
            "lastName" to form.lastName,
            "email" to form.email,
            "username" to form.username,
            "password" to form.password,
            "password-confirm" to form.password,
        ).entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val posted = try {
            client.send(
                java.net.http.HttpRequest.newBuilder(URI(action))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(fields))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
            )
        } catch (e: Exception) {
            throw AuthException(
                "Keycloak didn't answer the registration at ${action.substringBefore('?')}: ${e.message ?: e.javaClass.simpleName}. " +
                    "With 'Verify email' on it sends a mail first; check the realm's SMTP settings",
                cause = e
            )
        }

        when (posted.statusCode()) {
            302, 303 -> {
                val location = posted.headers().firstValue("Location").orElse("")
                val params = query(URI(location).rawQuery)
                if (params["code"] == null) {
                    if (params["execution"] == "VERIFY_EMAIL") {
                        // Keycloak sends the verification mail when this page is opened, so open it for the user
                        runCatching {
                            client.send(
                                java.net.http.HttpRequest.newBuilder(URI(sameServer(location))).timeout(java.time.Duration.ofSeconds(30)).GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.discarding()
                            )
                        }.onFailure { logInternal("auth: couldn't open the verify-email page for '${form.username}': ${it.message}") }
                        logInternal("auth: registered '${form.username}', verification mail requested for ${form.email}")
                        throw EmailVerificationPending(form.username, form.email)
                    }
                    // Other required actions (terms, OTP...) need the hosted page; let the user finish there
                    throw AuthException("Keycloak needs one more step before signing in (${location.substringBefore('?')}); use 'Register in browser'")
                }
                if (params["state"] != state) throw AuthException("Keycloak came back with a different state; try again")
                logInternal("auth: registered '${form.username}' in realm '${config.realm}'")
                exchangeCode(params["code"]!!, verifier)
            }
            200 -> throw AuthException(formError(posted.body()) ?: "Keycloak didn't accept the registration")
            else -> throw AuthException("Keycloak answered ${posted.statusCode()} to the registration: ${errorOf(posted.body())}", posted.statusCode())
        }
    }

    private fun RegistrationForm.validate() {
        if (username.isBlank()) throw AuthException("Choose a username")
        if (!email.contains('@')) throw AuthException("Enter a valid email address")
        if (password.length < 8) throw AuthException("The password needs at least 8 characters")
    }

    /* ---------- tokens -> session ---------- */

    private suspend fun sessionOf(tokens: TokenResponse): AuthSession {
        val jwt = runCatching { Jwt.decode(tokens.access_token) }
            .getOrElse { throw AuthException("The access token isn't a JWT: ${it.message}") }
        val issuer = expectedIssuer()
        if (jwt.issuer != issuer) throw AuthException("Token issuer ${jwt.issuer} isn't $issuer")
        if (jwt.expiresAt <= Instant.now().epochSecond) throw AuthException("Keycloak returned an already expired token; check the clocks")
        val jwks = try {
            json.decodeFromString<Jwks>(http.get(config.jwksEndpoint).bodyAsText())
        } catch (e: Exception) {
            throw AuthException("Couldn't read the realm's signing keys: ${e.message}", cause = e)
        }
        if (!jwt.verifySignature(jwks)) throw AuthException("The token's signature doesn't match realm '${config.realm}' (kid ${jwt.kid})")

        val now = Instant.now().epochSecond
        return AuthSession(
            accessToken = tokens.access_token,
            refreshToken = tokens.refresh_token,
            idToken = tokens.id_token,
            expiresAt = minOf(jwt.expiresAt, now + tokens.expires_in),
            refreshExpiresAt = if (tokens.refresh_expires_in > 0) now + tokens.refresh_expires_in else 0,
            subject = jwt.subject ?: "",
            username = jwt.username ?: jwt.email ?: jwt.subject ?: "user",
            email = jwt.email ?: "",
            name = jwt.name ?: "",
            issuer = issuer,
            realmUrl = config.issuer,
        )
    }

    /** The user's profile as Keycloak sees it, for a signed-in access token */
    suspend fun userInfo(accessToken: String): Map<String, String> {
        val response = http.get(config.userInfoEndpoint) { header("Authorization", "Bearer $accessToken") }
        if (!response.status.isSuccess()) throw AuthException("Userinfo answered ${response.status.value}", response.status.value)
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
            .mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
    }

    /* ---------- helpers ---------- */

    // Turns Keycloak's error JSON into the sentence the login page shows
    private fun explain(status: Int, body: String): String {
        val error = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = error?.get("error")?.jsonPrimitive?.contentOrNull
        val description = error?.get("error_description")?.jsonPrimitive?.contentOrNull
        return when {
            code == "invalid_grant" && description?.contains("Invalid user credentials", ignoreCase = true) == true -> "Wrong username or password"
            code == "invalid_grant" && description?.contains("not fully set up", ignoreCase = true) == true -> "The account has a pending action (verify email or update the profile); finish it in the browser"
            code == "invalid_grant" && description?.contains("disabled", ignoreCase = true) == true -> "The account is disabled"
            code == "invalid_grant" -> description ?: "Keycloak rejected the credentials"
            code == "unauthorized_client" && description?.contains("Direct access", ignoreCase = true) == true ->
                "Client '${config.clientId}' has 'Direct access grants' off; enable it in Keycloak or sign in with the browser"
            code == "unauthorized_client" -> "Client '${config.clientId}' is not allowed to do this: ${description ?: ""}".trim()
            code == "invalid_client" -> "Client '${config.clientId}' rejected: ${description ?: "wrong client id or secret"}"
            status == 404 -> "Realm '${config.realm}' doesn't exist at ${config.baseUrl}"
            else -> description ?: code ?: "Keycloak answered $status"
        }
    }

    private fun errorOf(body: String): String =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?.let { it["error_description"] ?: it["error"] }?.jsonPrimitive?.contentOrNull
            ?: body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(200)

    /**
     * The same URL on the server the app is configured with. Keycloak writes the realm's frontend URL
     * (for example a LAN address) into form actions and redirects; when that address is stale the
     * request would hang until it times out, while the configured base URL is known to answer.
     */
    private fun sameServer(url: String): String {
        val base = URI(config.baseUrl)
        val given = URI(url)
        if (given.host == null || base.host == null) return url
        return URI(base.scheme, given.rawUserInfo, base.host, base.port, given.path, null, null).toString() +
            (given.rawQuery?.let { "?$it" } ?: "") + (given.rawFragment?.let { "#$it" } ?: "")
    }

    // The action of Keycloak's registration form (id kc-register-form), with HTML entities unescaped
    private fun formAction(html: String): String? {
        val regex = Regex("""<form[^>]*id="kc-register-form"[^>]*action="([^"]+)"|<form[^>]*action="([^"]+)"[^>]*id="kc-register-form"""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.replace("&amp;", "&")
    }

    // Keycloak's field and form errors: <span class="kc-feedback-text">…</span>, <span id="input-error-…">…</span>
    private fun formError(html: String): String? {
        val regex = Regex("""<span[^>]*(?:class="[^"]*kc-feedback-text[^"]*"|id="input-error[^"]*")[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(html)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
            .ifEmpty { null }
    }

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
        }

    private fun randomToken(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
}
