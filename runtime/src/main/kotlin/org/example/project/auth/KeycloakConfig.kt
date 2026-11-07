package org.example.project.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.getDirectory
import org.example.project.util.Secrets
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Where the app signs in. Keycloak runs in docker on this machine (http://localhost:8080) with the
 * realm and client the Amie backend uses (realm "master", client "amie").
 *
 * Read from AMP/keycloak.json, which is written with the defaults on first start so it can be edited;
 * KEYCLOAK_URL, KEYCLOAK_REALM, KEYCLOAK_CLIENT_ID and KEYCLOAK_CLIENT_SECRET override it.
 */
@Serializable
data class KeycloakConfig(
    val baseUrl: String = "http://localhost:8080",
    val realm: String = "master",
    val clientId: String = "amie",
    // Only for a confidential client; a public client leaves it empty
    val clientSecret: String = "",
    // The loopback port the browser flows redirect back to: http://localhost:<port>/callback must be a valid
    // redirect URI of the client in Keycloak
    val redirectPort: Int = 48080,
) {
    val issuer: String get() = "${baseUrl.trimEnd('/')}/realms/$realm"
    private val oidc: String get() = "$issuer/protocol/openid-connect"

    val discoveryUrl: String get() = "$issuer/.well-known/openid-configuration"
    val tokenEndpoint: String get() = "$oidc/token"
    val authEndpoint: String get() = "$oidc/auth"
    val registrationEndpoint: String get() = "$oidc/registrations"
    val logoutEndpoint: String get() = "$oidc/logout"
    val userInfoEndpoint: String get() = "$oidc/userinfo"
    val jwksEndpoint: String get() = "$oidc/certs"
    val accountUrl: String get() = "$issuer/account"
    val adminConsoleUrl: String get() = "${baseUrl.trimEnd('/')}/admin/$realm/console"
    val redirectUri: String get() = "http://localhost:$redirectPort/callback"

    /** Keycloak's own "forgot password" page for this client */
    val resetPasswordUrl: String
        get() = "$issuer/login-actions/reset-credentials?client_id=${enc(clientId)}"

    /** The hosted login or registration page, as an OAuth authorization request with PKCE */
    fun browserUrl(register: Boolean, state: String, codeChallenge: String): String {
        val endpoint = if (register) registrationEndpoint else authEndpoint
        return endpoint +
            "?client_id=${enc(clientId)}" +
            "&response_type=code" +
            "&scope=${enc(OFFLINE_SCOPE)}" +
            "&redirect_uri=${enc(redirectUri)}" +
            "&state=${enc(state)}" +
            "&code_challenge=${enc(codeChallenge)}" +
            "&code_challenge_method=S256"
    }

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        val file: File get() = File(getDirectory().toString(), "keycloak.json")

        /** The saved config with environment overrides applied; writes the defaults when there is no file yet */
        fun load(): KeycloakConfig {
            val saved = runCatching { json.decodeFromString<KeycloakConfig>(file.readText()) }.getOrNull()
                ?: KeycloakConfig().also { defaults ->
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(json.encodeToString(defaults))
                    }
                }
            fun env(name: String) = Secrets.get(name)?.trim()?.takeIf { it.isNotEmpty() }
            return saved.copy(
                baseUrl = env("KEYCLOAK_URL") ?: saved.baseUrl,
                realm = env("KEYCLOAK_REALM") ?: saved.realm,
                clientId = env("KEYCLOAK_CLIENT_ID") ?: saved.clientId,
                clientSecret = env("KEYCLOAK_CLIENT_SECRET") ?: saved.clientSecret,
            )
        }

        fun save(config: KeycloakConfig) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(config))
        }
    }
}

internal fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
