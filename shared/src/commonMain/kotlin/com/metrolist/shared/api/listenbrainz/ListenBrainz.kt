package com.metrolist.shared.api.listenbrainz

import com.metrolist.shared.api.ScrobbleApiException
import com.metrolist.shared.api.ScrobbleFailureDisposition
import com.metrolist.shared.api.parseRetryAfter
import com.metrolist.shared.api.scrobbleStatusDisposition
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class ListenBrainzUser(
    val username: String,
)

object ListenBrainz {
    private const val API_ROOT = "https://api.listenbrainz.org/1"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun validateToken(
        httpClient: HttpClient,
        token: String,
    ): Result<ListenBrainzUser> =
        catchingApi {
            check(token.isNotBlank()) { "ListenBrainz token is empty" }
            val response =
                httpClient.get("$API_ROOT/validate-token") {
                    header(HttpHeaders.Authorization, "Token $token")
                }
            val body = parseResponse(response.status.value, response.bodyAsText())
            check(body["valid"]?.jsonPrimitive?.content == "true") { "ListenBrainz token is invalid" }
            val username = body["user_name"]?.jsonPrimitive?.content.orEmpty()
            check(username.isNotBlank()) { "ListenBrainz response is missing the username" }
            ListenBrainzUser(username)
        }

    suspend fun updateNowPlaying(
        httpClient: HttpClient,
        token: String,
        artist: String,
        track: String,
        album: String? = null,
        duration: Long? = null,
    ): Result<Unit> = submit(httpClient, token, "playing_now", artist, track, null, album, duration)

    suspend fun scrobble(
        httpClient: HttpClient,
        token: String,
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        duration: Long? = null,
    ): Result<Unit> = submit(httpClient, token, "single", artist, track, timestamp, album, duration)

    private suspend fun submit(
        httpClient: HttpClient,
        token: String,
        listenType: String,
        artist: String,
        track: String,
        timestamp: Long?,
        album: String?,
        duration: Long?,
    ): Result<Unit> =
        catchingApi {
            check(token.isNotBlank()) { "ListenBrainz token is empty" }
            val metadata =
                buildJsonObject {
                    put("artist_name", artist)
                    put("track_name", track)
                    album?.let { put("release_name", it) }
                    duration?.let { seconds ->
                        put(
                            "additional_info",
                            buildJsonObject {
                                put("duration_ms", seconds * 1000L)
                                put("submission_client", "Metrolist")
                            },
                        )
                    }
                }
            val listen =
                buildJsonObject {
                    timestamp?.let { put("listened_at", it) }
                    put("track_metadata", metadata)
                }
            val response =
                httpClient.post("$API_ROOT/submit-listens") {
                    header(HttpHeaders.Authorization, "Token $token")
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("listen_type", listenType)
                            put("payload", buildJsonArray { add(listen) })
                        }.toString(),
                    )
                }
            val body = parseResponse(response.status.value, response.bodyAsText(), response.headers[HttpHeaders.RetryAfter])
            if (body["status"]?.jsonPrimitive?.content != "ok") {
                throw ScrobbleApiException("ListenBrainz rejected the listen", ScrobbleFailureDisposition.PERMANENT)
            }
        }

    private fun parseResponse(
        statusCode: Int,
        body: String,
        retryAfter: String? = null,
    ) = runCatching { json.parseToJsonElement(body).jsonObject }
        .getOrElse {
            throw ScrobbleApiException(
                "ListenBrainz returned an invalid response (HTTP $statusCode)",
                disposition = scrobbleStatusDisposition(statusCode),
                statusCode = statusCode,
                retryAfterMs = parseRetryAfter(retryAfter),
            )
        }.also { response ->
            if (statusCode !in 200..299) {
                val message = response["error"]?.jsonPrimitive?.content ?: response["message"]?.jsonPrimitive?.content
                throw ScrobbleApiException(
                    message ?: "ListenBrainz request failed (HTTP $statusCode)",
                    disposition = scrobbleStatusDisposition(statusCode),
                    statusCode = statusCode,
                    retryAfterMs = parseRetryAfter(retryAfter),
                )
            }
        }

    private suspend inline fun <T> catchingApi(crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
}
