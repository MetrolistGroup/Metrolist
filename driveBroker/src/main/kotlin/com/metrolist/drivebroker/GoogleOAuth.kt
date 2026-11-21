package com.metrolist.drivebroker

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    @SerialName("exp") val expiresAt: Long,
)

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

    suspend fun accessToken(account: DriveAccount): Pair<String, Long>? {
        val (clientId, clientSecret) =
            when (account.clientId) {
                configuration.desktopClientId -> configuration.desktopClientId to null
                configuration.iosClientId -> configuration.iosClientId to null
                else -> configuration.webClientId to configuration.webClientSecret
            }
        val response =
            client
                .submitForm(
                    "https://oauth2.googleapis.com/token",
                    Parameters.build {
                        append("refresh_token", account.refreshToken)
                        append("client_id", clientId)
                        clientSecret?.let { append("client_secret", it) }
                        append("grant_type", "refresh_token")
                    },
                ).body<GoogleTokenResponse>()
        return response.accessToken?.let { it to (response.expiresIn ?: 3600L) }
    }

    suspend fun revoke(refreshToken: String) {
        client.post("https://oauth2.googleapis.com/revoke") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(Parameters.build { append("token", refreshToken) }.formUrlEncode())
        }
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
