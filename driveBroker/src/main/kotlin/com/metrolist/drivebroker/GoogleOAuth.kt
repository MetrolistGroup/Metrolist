package com.metrolist.drivebroker

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

@Serializable
data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("error") val error: String? = null,
)

@Serializable
data class GoogleIdentity(
    @SerialName("sub") val subject: String,
    @SerialName("email") val email: String? = null,
    @SerialName("email_verified") val emailVerified: String? = null,
    @SerialName("aud") val audience: String,
    @SerialName("iss") val issuer: String,
    @SerialName("exp")
    @Serializable(with = GoogleEpochSecondsSerializer::class)
    val expiresAt: Long,
)

private object GoogleEpochSecondsSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("GoogleEpochSeconds", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Long,
    ) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Long {
        val value = JsonElement.serializer().deserialize(decoder)
        return (value as? JsonPrimitive)?.content?.toLongOrNull()
            ?: throw SerializationException("Google token expiry is not an epoch second value")
    }
}

sealed interface GoogleAccessTokenResult {
    data class Success(
        val accessToken: String,
        val expiresIn: Long,
    ) : GoogleAccessTokenResult

    data object ReauthorizationRequired : GoogleAccessTokenResult

    data object Unavailable : GoogleAccessTokenResult
}

class GoogleRevocationAmbiguousException(
    message: String = "Google revocation outcome is unknown",
    cause: Throwable? = null,
) : Exception(message, cause)

class GoogleOAuth(
    private val client: HttpClient,
    private val configuration: BrokerConfiguration,
) {
    suspend fun exchange(
        platform: ClientPlatform,
        code: String,
        redirectUri: String?,
        codeVerifier: String?,
    ): DriveAccount? {
        if (code.length !in 1..4096) return null
        if (platform == ClientPlatform.DESKTOP && (!isLoopbackRedirect(redirectUri) || !validVerifier(codeVerifier))) return null
        if (platform == ClientPlatform.IOS &&
            (redirectUri != iosRedirectUri(configuration.iosClientId) || !validVerifier(codeVerifier))
        ) {
            return null
        }
        val clientId =
            when (platform) {
                ClientPlatform.ANDROID -> configuration.webClientId
                ClientPlatform.IOS -> configuration.iosClientId
                ClientPlatform.DESKTOP -> configuration.desktopClientId
            }
        val tokenResponse =
            client
                .submitForm(
                    "https://oauth2.googleapis.com/token",
                    Parameters.build {
                        append("code", code)
                        append("client_id", clientId)
                        if (platform == ClientPlatform.ANDROID) append("client_secret", configuration.webClientSecret)
                        append("grant_type", "authorization_code")
                        if (redirectUri != null) append("redirect_uri", redirectUri)
                        if (codeVerifier != null) append("code_verifier", codeVerifier)
                    },
                ).body<GoogleTokenResponse>()
        val refreshToken = tokenResponse.refreshToken ?: return null
        val idToken = tokenResponse.idToken ?: return null
        val identityResponse =
            client.get("https://oauth2.googleapis.com/tokeninfo") {
                parameter("id_token", idToken)
            }
        if (!identityResponse.status.isSuccess()) return null
        val identity = identityResponse.body<GoogleIdentity>()
        val expectedAudience =
            when (platform) {
                ClientPlatform.ANDROID -> configuration.webClientId
                ClientPlatform.IOS -> configuration.iosClientId
                ClientPlatform.DESKTOP -> configuration.desktopClientId
            }
        if (identity.audience != expectedAudience ||
            identity.issuer !in setOf("accounts.google.com", "https://accounts.google.com") ||
            identity.expiresAt <= System.currentTimeMillis() / 1000
        ) {
            return null
        }
        return DriveAccount(identity.subject, clientId, identity.email, refreshToken)
    }

    suspend fun accessToken(account: DriveAccount): GoogleAccessTokenResult {
        val (clientId, clientSecret) =
            when (account.clientId) {
                configuration.desktopClientId -> configuration.desktopClientId to null
                configuration.iosClientId -> configuration.iosClientId to null
                else -> configuration.webClientId to configuration.webClientSecret
            }
        val response =
            client.submitForm(
                "https://oauth2.googleapis.com/token",
                Parameters.build {
                    append("refresh_token", account.refreshToken)
                    append("client_id", clientId)
                    clientSecret?.let { append("client_secret", it) }
                    append("grant_type", "refresh_token")
                },
            )
        val tokenResponse = runCatching { response.body<GoogleTokenResponse>() }.getOrDefault(GoogleTokenResponse())
        if (response.status.isSuccess()) {
            val accessToken = tokenResponse.accessToken ?: return GoogleAccessTokenResult.Unavailable
            val expiresIn = tokenResponse.expiresIn?.takeIf { it > 0 } ?: return GoogleAccessTokenResult.Unavailable
            return GoogleAccessTokenResult.Success(accessToken, expiresIn)
        }
        if (tokenResponse.error == INVALID_GRANT) return GoogleAccessTokenResult.ReauthorizationRequired
        return GoogleAccessTokenResult.Unavailable
    }

    suspend fun revoke(refreshToken: String) {
        val response =
            try {
                client.post("https://oauth2.googleapis.com/revoke") {
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(Parameters.build { append("token", refreshToken) }.formUrlEncode())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The POST may have reached Google before the connection failed. Keep the revocation
                // marker so an older grant cannot be committed while a retry reconciles the outcome.
                throw GoogleRevocationAmbiguousException(cause = error)
            }
        if (response.status.isSuccess()) return
        if (response.status.value in 500..599) {
            // A server error cannot prove Google rejected the revocation; keep the marker for reconciliation.
            throw GoogleRevocationAmbiguousException(message = "Google revocation failed with HTTP ${response.status.value}")
        }
        val error =
            try {
                response.body<GoogleTokenResponse>().error
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        // A retry after a successful remote revocation and a failed local commit must still complete.
        if (response.status == HttpStatusCode.BadRequest &&
            (error == INVALID_GRANT || error == "invalid_token")
        ) {
            return
        }
        error("Google revocation failed with HTTP ${response.status.value}")
    }

    private companion object {
        const val INVALID_GRANT = "invalid_grant"
    }

    private fun validVerifier(value: String?): Boolean =
        value != null && value.length in 43..128 &&
            value.all {
                it.isLetterOrDigit() || it in "-._~"
            }

    private fun isLoopbackRedirect(value: String?): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "http" && uri.host in setOf("127.0.0.1", "[::1]", "::1") &&
            uri.port in 1024..65535 && uri.rawUserInfo == null && uri.rawFragment == null
    }

    private fun iosRedirectUri(clientId: String): String {
        val suffix = ".apps.googleusercontent.com"
        require(clientId.endsWith(suffix))
        return "com.googleusercontent.apps.${clientId.removeSuffix(suffix)}:/oauth2redirect"
    }
}
