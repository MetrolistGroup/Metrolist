package com.metrolist.sponsorblock

import com.metrolist.sponsorblock.models.Segment
import com.metrolist.sponsorblock.models.SponsorBlockCategory
import com.metrolist.sponsorblock.models.VideoSegments
import com.metrolist.sponsorblock.models.sanitized
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Minimal SponsorBlock client.
 *
 * Requests go through the hash-prefix endpoint rather than the plain one: only
 * the first [HASH_PREFIX_LENGTH] characters of the SHA-256 of the video ID are
 * sent, so the server is never told which track is being played. The response
 * covers every video sharing that prefix and is filtered locally.
 */
object SponsorBlock {
    private const val BASE_URL = "https://sponsor.ajay.app"
    private const val HASH_PREFIX_LENGTH = 4

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            defaultRequest {
                url(BASE_URL)
            }

            expectSuccess = false
        }
    }

    /**
     * Returns the skippable segments of [videoId] limited to [categories],
     * already sorted and merged.
     *
     * An empty list is a normal, common result: most tracks have no segments.
     * Network and parsing problems surface as a failed [Result] so the caller can
     * simply play the track untouched.
     */
    suspend fun getSegments(
        videoId: String,
        categories: Set<SponsorBlockCategory>,
    ): Result<List<Segment>> {
        if (videoId.isBlank() || categories.isEmpty()) return Result.success(emptyList())

        return try {
            val response: HttpResponse = client.get("api/skipSegments/${hashPrefixOf(videoId)}") {
                parameter("categories", categories.toJsonArray { it.apiName })
                parameter("actionTypes", listOf("skip").toJsonArray { it })
            }

            currentCoroutineContext().ensureActive()

            // The API answers 404 when nothing is submitted for the whole prefix.
            if (response.status == HttpStatusCode.NotFound) return Result.success(emptyList())
            if (!response.status.isSuccess()) {
                return Result.failure(
                    IllegalStateException("SponsorBlock request failed: ${response.status}"),
                )
            }

            Result.success(
                response.body<List<VideoSegments>>()
                    .filter { it.videoId == videoId }
                    .flatMap { it.segments }
                    .mapNotNull { it.toSegment() }
                    .filter { it.category in categories }
                    .sanitized(),
            )
        } catch (cancellation: CancellationException) {
            // Must propagate: swallowing this into a failed Result would let a
            // cancelled lookup carry on as an ordinary "no segments" answer.
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    /** First [HASH_PREFIX_LENGTH] hex characters of the SHA-256 of [videoId]. */
    internal fun hashPrefixOf(videoId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(HASH_PREFIX_LENGTH)

    private fun <T> Iterable<T>.toJsonArray(name: (T) -> String) =
        joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]", transform = name)
}
