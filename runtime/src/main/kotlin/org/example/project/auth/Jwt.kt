package org.example.project.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** A decoded JSON Web Token: header, claims and the signed input + signature needed to verify it */
class Jwt private constructor(
    val header: JsonObject,
    val claims: JsonObject,
    private val signedInput: ByteArray,
    private val signature: ByteArray,
) {
    val kid: String? get() = header["kid"]?.jsonPrimitive?.contentOrNull
    val alg: String? get() = header["alg"]?.jsonPrimitive?.contentOrNull
    val issuer: String? get() = string("iss")
    val subject: String? get() = string("sub")
    val expiresAt: Long get() = claims["exp"]?.jsonPrimitive?.longOrNull ?: 0L
    val username: String? get() = string("preferred_username")
    val email: String? get() = string("email")
    val name: String? get() = string("name")

    /** "aud" is a string or an array of strings */
    val audience: List<String>
        get() = when (val aud = claims["aud"]) {
            is JsonArray -> aud.mapNotNull { it.jsonPrimitive.contentOrNull }
            null -> emptyList()
            else -> listOfNotNull(aud.jsonPrimitive.contentOrNull)
        }

    fun string(claim: String): String? = claims[claim]?.jsonPrimitive?.contentOrNull

    /** Checks the RS256 signature against the realm's published key with this token's kid */
    fun verifySignature(jwks: Jwks): Boolean {
        if (alg != "RS256") return false
        val key = jwks.keys.firstOrNull { it.kid == kid && it.kty == "RSA" } ?: return false
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, b64(key.n)), BigInteger(1, b64(key.e)))
        )
        return runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update(signedInput)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(token: String): Jwt {
            val parts = token.split('.')
            require(parts.size == 3) { "A JWT has three dot-separated parts, got ${parts.size}" }
            val header = json.parseToJsonElement(String(b64(parts[0]))).jsonObject
            val claims = json.parseToJsonElement(String(b64(parts[1]))).jsonObject
            return Jwt(header, claims, "${parts[0]}.${parts[1]}".toByteArray(), b64(parts[2]))
        }

        private fun b64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    }
}

@Serializable
data class Jwk(val kid: String = "", val kty: String = "", val alg: String = "", val use: String = "", val n: String = "", val e: String = "")

@Serializable
data class Jwks(val keys: List<Jwk> = emptyList())
