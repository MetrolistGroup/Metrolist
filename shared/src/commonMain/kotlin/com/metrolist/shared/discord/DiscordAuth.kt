package com.metrolist.shared.discord

import com.metrolist.shared.deviceconnect.DeviceCrypto
import com.metrolist.shared.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64

@Serializable
data class DiscordAuthResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long,
    val scope: String,
)

data class PkcePair(
    val verifier: String,
    val challenge: String,
)

@Serializable
data class DiscordAuthSession(
    val authorizeUrl: String,
    val redirectUri: String,
    val pkceVerifier: String,
    val state: String,
)

sealed class DiscordAuthException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class NetworkFailure(
        cause: Throwable,
    ) : DiscordAuthException("Network failure: ${cause.message}", cause)

    class InvalidGrant(
        message: String = "Invalid or expired grant",
    ) : DiscordAuthException(message)

    class StateMismatch : DiscordAuthException("OAuth state mismatch")
}

class DiscordAuth(
    private val httpClient: HttpClient,
) {
    private val redirectUri: String = DISCORD_REDIRECT_URI

    fun createSession(): DiscordAuthSession {
        val pkce = generatePkcePair()
        val state = generateState()

        val authorizeUrl =
            URLBuilder(DISCORD_OAUTH_AUTHORIZE)
                .apply {
                    parameters.append("client_id", DISCORD_APP_ID.toString())
                    parameters.append("response_type", "code")
                    parameters.append("redirect_uri", redirectUri)
                    parameters.append("scope", DISCORD_SCOPES)
                    parameters.append("state", state)
                    parameters.append("code_challenge_method", "S256")
                    parameters.append("code_challenge", pkce.challenge)
                }.buildString()

        Logger.i(TAG, "createSession: url length=${authorizeUrl.length}, redirectUri=$redirectUri")

        return DiscordAuthSession(
            authorizeUrl = authorizeUrl,
            redirectUri = redirectUri,
            pkceVerifier = pkce.verifier,
            state = state,
        )
    }

    suspend fun exchangeCode(
        code: String,
        verifier: String,
        state: String,
        expectedState: String,
    ): DiscordAuthResult {
        if (state != expectedState) {
            Logger.w(TAG, "exchangeCode: state mismatch")
            throw DiscordAuthException.StateMismatch()
        }

        return performTokenExchange(
            grantType = "authorization_code",
            extraParams =
                mapOf(
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to verifier,
                ),
        )
    }

    suspend fun refresh(refreshToken: String): DiscordAuthResult =
        performTokenExchange(
            grantType = "refresh_token",
            extraParams = mapOf("refresh_token" to refreshToken),
        )

    private suspend fun performTokenExchange(
        grantType: String,
        extraParams: Map<String, String>,
    ): DiscordAuthResult {
        try {
            val response: HttpResponse =
                httpClient.submitForm(
                    url = DISCORD_OAUTH_TOKEN,
                    formParameters =
                        parameters {
                            append("client_id", DISCORD_APP_ID.toString())
                            append("grant_type", grantType)
                            extraParams.forEach { (name, value) -> append(name, value) }
                        },
                )

            val body = response.bodyAsText()
            if (response.status.value in 200..299) {
                val json = Json.parseToJsonElement(body).jsonObject
                return DiscordAuthResult(
                    accessToken = json["access_token"]?.jsonPrimitive?.content ?: "",
                    refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: "",
                    expiresInSec = json["expires_in"]?.jsonPrimitive?.long ?: 0L,
                    scope = json["scope"]?.jsonPrimitive?.content ?: DISCORD_SCOPES,
                )
            }

            val errorCode =
                runCatching {
                    Json
                        .parseToJsonElement(body)
                        .jsonObject["error"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()
            if (response.status == HttpStatusCode.BadRequest && errorCode == "invalid_grant") {
                Logger.w(TAG, "token exchange: invalid_grant on $grantType")
                throw DiscordAuthException.InvalidGrant()
            }
            throw DiscordAuthException.NetworkFailure(Exception("HTTP ${response.status.value}: $body"))
        } catch (e: DiscordAuthException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw DiscordAuthException.NetworkFailure(e)
        }
    }

    companion object {
        private const val TAG = "DiscordSvc"
        private const val DISCORD_APP_ID = 1447278780795064401L
        private const val DISCORD_OAUTH_AUTHORIZE = "https://discord.com/oauth2/authorize"
        private const val DISCORD_OAUTH_TOKEN = "https://discord.com/api/v10/oauth2/token"
        private const val DISCORD_REDIRECT_URI = "metrolist-auth://discord/callback"
        private const val DISCORD_SCOPES = "openid sdk.social_layer_presence"

        fun generatePkcePair(): PkcePair {
            val bytes = DeviceCrypto.secureRandomBytes(64)
            val verifier = base64UrlEncode(bytes)
            val challenge = base64UrlEncode(DeviceCrypto.sha256(verifier.encodeToByteArray()))
            return PkcePair(verifier, challenge)
        }

        fun generateState(): String = base64UrlEncode(DeviceCrypto.secureRandomBytes(16))

        private fun base64UrlEncode(data: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(data)
    }
}
