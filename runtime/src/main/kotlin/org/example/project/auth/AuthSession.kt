package org.example.project.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import java.io.File
import java.time.Instant

/** A signed-in Keycloak user: the tokens and the claims the UI shows */
@Serializable
data class AuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    // Epoch seconds
    val expiresAt: Long,
    val refreshExpiresAt: Long = 0,
    val subject: String = "",
    val username: String,
    val email: String = "",
    val name: String = "",
    // Issuer claim of the tokens, and the realm URL the app was configured with when they were issued
    val issuer: String = "",
    val realmUrl: String = "",
) {
    fun expired(leewaySeconds: Long = 30): Boolean = Instant.now().epochSecond + leewaySeconds >= expiresAt
    fun refreshExpired(): Boolean = refreshToken == null || (refreshExpiresAt > 0 && Instant.now().epochSecond >= refreshExpiresAt)
}

/** Keycloak refuses a token with 400 or 401; anything else (a 503 while it starts, a proxy's 502) says nothing about the session */
private val AuthException.refused: Boolean get() = status == 400 || status == 401

/**
 * The app's sign-in state. Pages read [session]; the login and register pages set it through [signIn].
 * The session is kept in AMP/session.json so a restart signs the same user back in: [resume] puts it back before the
 * first page is drawn, and [restore] then renews it with the refresh token.
 */
object AuthState {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file: File get() = File(getDirectory().toString(), "session.json")

    private val _session = MutableStateFlow<AuthSession?>(null)
    val session: StateFlow<AuthSession?> = _session.asStateFlow()

    val user: String get() = _session.value?.username ?: ""
    val signedIn: Boolean get() = Accounts.enabled && _session.value != null

    private val realmConfig: KeycloakConfig by lazy { KeycloakConfig.load() }
    private val realmClient: KeycloakClient by lazy { KeycloakClient(realmConfig) }

    /** In place of the realm's client, for tests that answer as Keycloak with a mock engine */
    @Volatile
    internal var clientOverride: KeycloakClient? = null

    val client: KeycloakClient get() = clientOverride ?: realmClient

    // One refresh at a time: at start restore() and the first page that needs a token ask for the same one
    private val refreshing = Mutex()

    // Off in a bundle: its tokens come from the environment, and session.json never gets written beside the script
    @Volatile private var keepSession = true

    fun signIn(session: AuthSession) {
        _session.value = session
        if (keepSession) runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(session))
        }.onFailure { logInternal("auth: couldn't save the session: ${it.message}", LogLevel.ERROR) }
        logInternal("auth: signed in as ${session.username} (${session.issuer})")
    }

    /**
     * Puts the saved session back at once, with no network: pages show a signed-in person as signed in from their first
     * frame, whatever Keycloak takes to answer. Only what this machine can tell keeps it out: another realm than
     * AMP/keycloak.json names, or nothing left to renew it with. [restore] then asks Keycloak.
     */
    fun resume(): AuthSession? {
        if (!Accounts.enabled) return null
        _session.value?.let { return it }
        val saved = runCatching { json.decodeFromString<AuthSession>(file.readText()) }.getOrNull() ?: return null
        val realm = clientOverride?.config ?: realmConfig
        if (saved.realmUrl != realm.issuer) {
            logInternal("auth: saved session is for ${saved.realmUrl}, not ${realm.issuer}; signed out")
            forget()
            return null
        }
        if (saved.expired() && saved.refreshExpired()) {
            logInternal("auth: session of ${saved.username} expired, sign in again")
            forget()
            return null
        }
        _session.value = saved
        return saved
    }

    /**
     * Signs in a run with no browser and no session.json: a bundle given AMIE_ACCESS_TOKEN (used until it expires),
     * AMIE_REFRESH_TOKEN (an offline token Keycloak turns into access tokens), or both. Nothing is saved.
     */
    fun useTokens(accessToken: String?, refreshToken: String?) {
        if (accessToken == null && refreshToken == null) return
        keepSession = false
        val claims = accessToken?.let { runCatching { Jwt.decode(it) }.getOrNull() }
        _session.value = AuthSession(
            accessToken = accessToken.orEmpty(),
            refreshToken = refreshToken,
            // No token, or none that says when it ends: the next call refreshes when it can, and uses it when it can't
            expiresAt = claims?.expiresAt?.takeIf { it > 0 } ?: if (refreshToken != null) 0 else Long.MAX_VALUE,
            username = claims?.username ?: claims?.subject ?: "",
        )
    }

    /**
     * Renews the saved session when its access token has expired. The user is only signed out when Keycloak refuses
     * the refresh token; when Keycloak can't be reached the session stays and is refreshed on the next call that needs
     * a token.
     */
    suspend fun restore(): AuthSession? {
        val saved = resume() ?: return null
        if (!saved.expired()) {
            logInternal("auth: restored session of ${saved.username}")
            return saved
        }
        return refresh(saved)
    }

    /** A fresh access token, refreshing when the current one is about to expire; null when signed out */
    suspend fun accessToken(): String? {
        if (!Accounts.enabled) return null
        val current = _session.value ?: return null
        if (!current.expired()) return current.accessToken
        return refresh(current)?.accessToken
    }

    @Volatile private var refreshFailed = false

    private suspend fun refresh(session: AuthSession): AuthSession? = refreshing.withLock {
        // Refreshed, signed out or signed in anew while this one waited: what stands now is the answer
        if (_session.value != session) return@withLock _session.value?.takeIf { !it.expired() }
        if (session.refreshExpired()) {
            logInternal("auth: session of ${session.username} expired, sign in again")
            forget()
            return@withLock null
        }
        try {
            val fresh = client.refresh(session.refreshToken!!)
            // Log out while Keycloak was answering stands: the answer must not sign the user back in
            if (_session.value != session) return@withLock _session.value
            refreshFailed = false
            signIn(fresh)
            fresh
        } catch (e: AuthException) {
            if (e.refused) {
                // Keycloak answered and refused the token (revoked, logged out elsewhere, offline session over)
                logInternal("auth: Keycloak refused the saved session of ${session.username}: ${e.message}", LogLevel.ERROR)
                if (_session.value == session) forget()
            } else {
                // Keycloak is down, unreachable or not itself: keep the session, the next token request tries again.
                // Normal for an app that runs without it, so it is no error and said once, not at every try
                if (!refreshFailed) logInternal("auth: couldn't refresh the session of ${session.username} (${e.message}); kept, will retry")
                refreshFailed = true
            }
            null
        } catch (e: Exception) {
            logInternal("auth: refresh failed: ${e.message}", LogLevel.ERROR)
            null
        }
    }

    /** Ends the Keycloak session and forgets the local one */
    suspend fun signOut() {
        val current = _session.value
        forget()
        if (current?.refreshToken != null) {
            runCatching { client.logout(current.refreshToken) }
                .onFailure { logInternal("auth: logout at Keycloak failed: ${it.message}", LogLevel.ERROR) }
        }
        logInternal("auth: signed out")
    }

    private fun forget() {
        _session.value = null
        file.delete()
    }
}
