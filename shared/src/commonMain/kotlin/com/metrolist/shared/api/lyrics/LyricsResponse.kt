package com.metrolist.shared.api.lyrics

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.parseRetryAfter
import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.network.readTextLimited
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay

internal fun HttpRequestBuilder.expectMissingLyrics() {
    attributes.put(ExpectedHttpFailureStatusCodes, setOf(HttpStatusCode.NotFound.value, HttpStatusCode.ServiceUnavailable.value))
}

internal class LyricsHttpStatusException(
    val statusCode: Int,
) : IllegalStateException("Lyrics request failed with HTTP $statusCode")

internal suspend fun HttpStatement.readLyricsText(maxBytes: Int = 1024 * 1024): String? {
    var waitedMs = 0L
    for (attempt in 0..2) {
        val response = readTextLimited(maxBytes)
        if (response.status == HttpStatusCode.ServiceUnavailable && attempt < 2) {
            val retryAfter = response.headers[HttpHeaders.RetryAfter]
            val seconds = retryAfter?.trim()?.toLongOrNull()
            val requestedDelay =
                if (seconds != null) {
                    if (seconds !in 0..5) throw LyricsHttpStatusException(response.status.value)
                    seconds * 1_000L
                } else {
                    if (!retryAfter.isNullOrBlank() && retryAfter.all { it.isDigit() }) {
                        throw LyricsHttpStatusException(response.status.value)
                    }
                    parseRetryAfter(retryAfter) ?: 0L
                }
            val waitMs = maxOf(500L shl attempt, requestedDelay)
            if (waitMs > 5_000L - waitedMs) throw LyricsHttpStatusException(response.status.value)
            delay(waitMs)
            waitedMs += waitMs
            continue
        }
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.NoContent) return null
        if (!response.status.isSuccess()) throw LyricsHttpStatusException(response.status.value)
        return checkNotNull(response.body) { "Lyrics response exceeds size limit" }
    }
    error("Lyrics retry budget exhausted")
}

fun isExpectedLyricsServiceFailure(
    url: String?,
    statusCode: Int,
): Boolean = isExpectedLyricsServiceFailure(url, statusCode, BuildConfig.LYPRIO_BASE_URL)

internal fun isExpectedLyricsServiceFailure(
    url: String?,
    statusCode: Int,
    lyprioBaseUrl: String,
): Boolean {
    if (statusCode != HttpStatusCode.ServiceUnavailable.value || url == null) return false
    val request = runCatching { Url(url) }.getOrNull() ?: return false
    val endpoints =
        listOfNotNull(
            "https://lyrics.paxsenix.org/apple-music/lyrics",
            "https://lrclib.net/api/search",
            lyprioBaseUrl.takeIf { it.isNotBlank() }?.trimEnd('/')?.plus("/v2/lyrics/search"),
        )
    return endpoints.any { endpoint ->
        val expected = Url(endpoint)
        request.protocol == expected.protocol && request.host == expected.host && request.port == expected.port &&
            request.encodedPath == expected.encodedPath
    }
}
