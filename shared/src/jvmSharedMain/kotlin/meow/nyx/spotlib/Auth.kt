package meow.nyx.spotlib

import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private const val TOKEN_URL = "https://open.spotify.com/api/token"
private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
private const val OPEN_HOST = "https://open.spotify.com"

@Serializable
internal data class TokenResponse(
    val accessToken: String? = null,
    val accessTokenExpirationTimestampMs: Long? = null,
    val isAnonymous: Boolean = true,
)

@Serializable
internal data class ClientTokenResponse(
    val response_type: String? = null,
    val granted_token: GrantedToken? = null,
)

@Serializable
internal data class GrantedToken(
    val token: String? = null,
)

internal class AuthHttp(
    private val http: ApiHttp,
    private val config: SpotifyConfig,
    private val jar: SpotifyCookieJar,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAccessToken(): TokenResponse {
        val code = Totp.code()
        val url = "$TOKEN_URL?reason=init&productType=web-player&totp=$code&totpServer=$code&totpVer=${Totp.version()}"
        val response =
            http.get(url) {
                headers {
                    append(HttpHeaders.Accept, "*/*")
                    append(HttpHeaders.Referrer, "https://open.spotify.com/")
                }
            }
        if (response.status.value >= 400) {
            val message = "/api/token failed: HTTP ${response.status.value}"
            if (response.status.value == 401 || response.status.value == 403) {
                throw SpotifySessionExpiredException(message, httpFailureLogged = true)
            }
            throw SpotifyAuthException(message, httpFailureLogged = true)
        }
        val body =
            json
                .parseToJsonElement(
                    response.body ?: throw SpotifyAuthException("token response too large"),
                ).jsonObject
        val token = json.decodeFromJsonElement(TokenResponse.serializer(), body)
        if (token.isAnonymous && !config.allowAnonymous) {
            throw SpotifySessionExpiredException("Spotify reports the session is anonymous. Sign in to Spotify and try again.")
        }
        if (token.accessToken == null) throw SpotifyAuthException("/api/token returned no access token")
        Log.info("Access token exchange succeeded")
        return token
    }

    suspend fun fetchClientToken(): String {
        val deviceId = jar.cookieValue(Url(OPEN_HOST), "sp_t") ?: UUID.randomUUID().toString()
        val payload =
            buildJsonObject {
                put(
                    "client_data",
                    buildJsonObject {
                        put("client_version", config.appVersion)
                        put("client_id", config.webClientId)
                        put(
                            "js_sdk_data",
                            buildJsonObject {
                                put("device_brand", "unknown")
                                put("device_model", "unknown")
                                put("os", "android")
                                put("os_version", "unknown")
                                put("device_id", deviceId)
                                put("device_type", "computer")
                            },
                        )
                    },
                )
            }
        val response =
            http.post(CLIENT_TOKEN_URL) {
                headers {
                    append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    append(HttpHeaders.Origin, "https://open.spotify.com")
                    append(HttpHeaders.Referrer, "https://open.spotify.com/")
                }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), payload))
            }
        if (response.status.value >= 400) {
            throw SpotifyAuthException("clienttoken failed: HTTP ${response.status.value}", httpFailureLogged = true)
        }
        val body =
            json
                .parseToJsonElement(
                    response.body ?: throw SpotifyAuthException("token response too large"),
                ).jsonObject
        val token =
            json.decodeFromJsonElement(ClientTokenResponse.serializer(), body).granted_token?.token
                ?: throw SpotifyAuthException("clienttoken returned no token")
        Log.info("Client token exchange succeeded")
        return token
    }
}
