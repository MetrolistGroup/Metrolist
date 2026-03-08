/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.api.parseRetryAfter
import com.metrolist.shared.deviceconnect.DeviceCrypto
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.network.TextResponse
import com.metrolist.shared.network.readTextLimited
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.TimeSource

class LyprioLyricsProvider internal constructor(
    httpClient: HttpClient,
    private val baseUrl: String,
    private val signingKey: ByteArray,
    private val nowEpochSeconds: () -> Long,
    private val delayMillis: suspend (Long) -> Unit,
    private val nextJitterMs: () -> Long,
    private val isMlxFormat: (String) -> Boolean = MlxParser::isMlxFormat,
) {
    private val httpClient =
        HttpClient(httpClient.engine) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            install(ContentEncoding) {
                gzip()
            }
        }
    private val json = Json { ignoreUnknownKeys = true }
    private var v2UnsupportedUntil: Long? = null
    private val requestsLock = SynchronizedObject()
    private val inFlight = mutableMapOf<String, CompletableDeferred<SearchResult>>()

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        videoId: String? = null,
    ): LyricsWithProvider? =
        withTimeout(SEARCH_TIMEOUT_MS) {
            var result = requestLyrics(title, artist, duration, album, videoId, allSources = false)
            if (result.complete && result.lyrics.isEmpty()) {
                result = requestLyrics(title, artist, duration, album, videoId, allSources = true)
            }
            result.lyrics.firstOrNull()?.let { LyricsWithProvider(it, "Lyprio", result.complete) }
        }

    suspend fun getLyricsResults(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        videoId: String? = null,
    ): List<String> = requestLyrics(title, artist, duration, album, videoId, allSources = true).lyrics

    private suspend fun requestLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        videoId: String?,
        allSources: Boolean,
    ): SearchResult {
        val request =
            LyprioLyricsRequest(
                title = title,
                artist = artist,
                album = album?.takeIf { it.isNotBlank() },
                duration = duration?.takeIf { it > 0 }?.div(1_000),
                videoId = videoId?.takeIf { it.isNotBlank() },
                allSources = allSources,
            )
        val body = json.encodeToString(request)
        return withTimeout(SEARCH_TIMEOUT_MS) { coalescedRequest(body, title, artist) }
    }

    private suspend fun coalescedRequest(
        body: String,
        title: String,
        artist: String,
    ): SearchResult {
        while (true) {
            val request = CompletableDeferred<SearchResult>()
            val pending = synchronized(requestsLock) { inFlight.getOrPut(body) { request } }
            if (pending !== request) {
                try {
                    return pending.await()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    synchronized(requestsLock) { if (inFlight[body] === pending) inFlight.remove(body) }
                    continue
                }
            }
            try {
                val result =
                    if (isV2Unsupported()) {
                        SearchResult(requestV1(body, title, artist), complete = true)
                    } else {
                        requestV2(body, title, artist)
                    }
                return result.also(request::complete)
            } catch (error: Throwable) {
                request.completeExceptionally(error)
                throw error
            } finally {
                synchronized(requestsLock) { if (inFlight[body] === request) inFlight.remove(body) }
            }
        }
    }

    private fun isV2Unsupported(): Boolean {
        val until = v2UnsupportedUntil ?: return false
        if (nowEpochSeconds() < until) return true
        v2UnsupportedUntil = null
        return false
    }

    private suspend fun requestV2(
        body: String,
        title: String,
        artist: String,
    ): SearchResult {
        val results = linkedMapOf<String, ConvertedLyrics>()
        var previousConversions = emptyMap<LyprioLyricsResult, String?>()

        fun accumulated(complete: Boolean) = SearchResult(results.values.sortedByDescending { it.quality }.map { it.lyrics }, complete)
        var totalWaitMs = 0L
        var response = sendRequest(V2_SEARCH_PATH, body)
        val pollingStarted = TimeSource.Monotonic.markNow()
        while (true) {
            val protocol = response.headers[PROTOCOL_HEADER]
            if ((response.status == HttpStatusCode.NotFound && protocol == null) ||
                response.status == HttpStatusCode.MethodNotAllowed
            ) {
                rememberV2Unsupported()
                return SearchResult(requestV1(body, title, artist), complete = true)
            }
            check(protocol == PROTOCOL_VERSION) { "Lyprio returned an invalid protocol marker" }
            v2UnsupportedUntil = null

            val responseBody = response.body ?: error("Lyprio response is too large")
            val payload = json.decodeFromString<LyprioLyricsResponse>(responseBody)
            val responseStatus = checkNotNull(payload.status) { "Lyprio response has no status" }
            checkNotNull(payload.complete) { "Lyprio response has no completion state" }

            when (response.status) {
                HttpStatusCode.OK -> {
                    check(responseStatus == "ready") { "Lyprio returned an invalid ready response" }
                    check(payload.results.isNotEmpty()) { "Lyprio returned no usable lyrics" }
                    val matching = payload.results.filter { it.namesArtist(artist) }
                    val conversions =
                        matching.distinct().associateWith { result ->
                            if (result in previousConversions) previousConversions[result] else result.toMlx(title, artist)
                        }
                    previousConversions = conversions
                    check(matching.isEmpty() || conversions.values.any { it != null }) { "Lyprio returned no usable lyrics" }
                    for (result in matching) {
                        val lyrics = conversions[result] ?: continue
                        val previous = results[result.subprovider]
                        val quality = lyrics.syncQuality()
                        if (previous == null || quality >= previous.quality) {
                            results[result.subprovider] = ConvertedLyrics(lyrics, quality)
                        }
                    }
                    if (payload.complete) return accumulated(complete = true)
                }

                HttpStatusCode.NotFound -> {
                    check(responseStatus == "not_found" && payload.complete) { "Lyprio returned an invalid miss response" }
                    check(payload.results.isEmpty()) { "Lyprio returned results with a miss status" }
                    return accumulated(complete = true)
                }

                HttpStatusCode.Accepted, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable -> {
                    val pending = response.status == HttpStatusCode.Accepted
                    check(
                        if (pending) {
                            responseStatus == "pending" && !payload.complete
                        } else {
                            responseStatus == "unavailable" && !payload.complete
                        },
                    ) { "Lyprio returned an invalid temporary response" }
                }

                else -> {
                    error("Lyprio request failed with ${response.status.value}")
                }
            }

            val remainingMs = MAX_TOTAL_WAIT_MS - maxOf(totalWaitMs, pollingStarted.elapsedNow().inWholeMilliseconds)
            val waitMs =
                retryDelayMs(
                    payload.retryAfterMs ?: READY_POLL_INTERVAL_MS.takeIf { response.status == HttpStatusCode.OK },
                    response.headers[HttpHeaders.RetryAfter],
                )
            if (waitMs > remainingMs) break
            response = withTimeoutOrNull(remainingMs) {
                delayMillis(waitMs)
                sendRequest(V2_SEARCH_PATH, body)
            } ?: break
            totalWaitMs += waitMs
        }
        if (results.isEmpty()) {
            if (response.status == HttpStatusCode.ServiceUnavailable) throw LyricsHttpStatusException(response.status.value)
            error("Lyprio polling budget exhausted after HTTP ${response.status.value}")
        }
        return accumulated(complete = false)
    }

    private fun retryDelayMs(
        retryAfterMs: Long?,
        retryAfterHeader: String?,
    ): Long {
        val headerDelay =
            retryAfterHeader?.trim()?.let { value ->
                val seconds = value.toLongOrNull()
                when {
                    seconds != null -> {
                        check(seconds >= 0) { "Lyprio returned an invalid Retry-After" }
                        check(seconds <= Long.MAX_VALUE / 1_000) { "Lyprio Retry-After is too large" }
                        seconds * 1_000
                    }

                    value.isNotEmpty() && value.all { it in '0'..'9' } -> {
                        error("Lyprio Retry-After is too large")
                    }

                    else -> {
                        parseRetryAfter(value)
                    }
                }
            }
        val bodyDelay = retryAfterMs?.also { check(it >= 0) { "Lyprio returned an invalid retryAfterMs" } }
        val baseDelay = listOfNotNull(headerDelay, bodyDelay).maxOrNull() ?: error("Lyprio omitted its retry delay")
        val jitter = nextJitterMs()
        check(jitter in 1..MAX_JITTER_MS) { "Lyprio retry jitter is invalid" }
        check(baseDelay <= Long.MAX_VALUE - jitter) { "Lyprio retry delay is too large" }
        return baseDelay + jitter
    }

    private fun rememberV2Unsupported() {
        val now = nowEpochSeconds()
        v2UnsupportedUntil = if (now > Long.MAX_VALUE - UNSUPPORTED_TTL_SECONDS) Long.MAX_VALUE else now + UNSUPPORTED_TTL_SECONDS
    }

    private suspend fun requestV1(
        body: String,
        title: String,
        artist: String,
    ): List<String> {
        val response = sendRequest(V1_SEARCH_PATH, body, useClientRomanization = true)
        val responseBody = response.body ?: error("Lyprio response is too large")
        if (response.status == HttpStatusCode.NotFound) return emptyList()
        check(response.status == HttpStatusCode.OK) { "Lyprio request failed with ${response.status.value}" }
        return json.decodeFromString<LyprioLyricsResponse>(responseBody).results.toMlxResults(title, artist)
    }

    private suspend fun sendRequest(
        path: String,
        body: String,
        useClientRomanization: Boolean = false,
    ): TextResponse {
        val timestamp = nowEpochSeconds().toString()
        return httpClient
            .preparePost("$baseUrl$path") {
                header("X-Lyprio-Timestamp", timestamp)
                header("X-Lyprio-Signature", lyprioRequestSignature(signingKey, timestamp, body))
                if (useClientRomanization) header(ROMANIZATION_HEADER, CLIENT_ROMANIZATION)
                attributes.put(ExpectedHttpFailureStatusCodes, (400..599).toSet())
                setBody(TextContent(body, ContentType.Application.Json))
            }.readTextLimited(MAX_RESPONSE_BYTES)
    }

    private fun Collection<LyprioLyricsResult>.toMlxResults(
        title: String,
        artist: String,
    ): List<String> =
        this
            .filter { it.namesArtist(artist) }
            .mapNotNull { result -> result.toMlx(title, artist) }
            .sortedByDescending { it.syncQuality() }

    private fun String.syncQuality(): Int = lyricsTimingKind(LyricsUtils.parseLyrics(this)).ordinal

    private fun LyprioLyricsResult.namesArtist(artist: String): Boolean {
        if (!format.equals("mlx", ignoreCase = true)) return true
        val resultArtist = LyricsUtils.parseMlxDocument(lyrics)?.song?.artist ?: return true
        return LyricsUtils.lyricsArtistsMatch(artist, resultArtist)
    }

    private fun LyprioLyricsResult.syncQuality(): Int =
        when (syncType.lowercase().replace('_', '-')) {
            "word", "words", "word-synced", "word-synchronized" -> 2
            "line", "lines", "line-synced", "line-synchronized" -> 1
            else -> 0
        }

    private fun LyprioLyricsResult.toMlx(
        title: String,
        artist: String,
    ): String? {
        val content = lyrics.takeIf { it.isNotBlank() } ?: return null
        val mlx =
            when (format.lowercase()) {
                "mlx" -> {
                    content.takeIf(isMlxFormat)
                }

                "ttml" -> {
                    parseTtmlToMlx(content, title, artist, "Lyprio").takeIf { it.isNotBlank() }
                }

                "lrc" -> {
                    if (syncQuality() == 2) {
                        enhancedLrcToMlx(content, title, artist, "Lyprio") ?: lrcToMlx(content, title, artist)
                    } else {
                        lrcToMlx(content, title, artist)
                    }
                }

                "plain" -> {
                    MlxBuilder()
                        .meta("Lyprio")
                        .song(title = title, artist = artist)
                        .apply { content.lineSequence().filter { it.isNotBlank() }.forEach { unsyncedLine(it.trimEnd()) } }
                        .build()
                }

                else -> {
                    null
                }
            }
        return mlx?.let { removeLyprioIntroHeader(it, title, artist) }
    }

    private data class ConvertedLyrics(
        val lyrics: String,
        val quality: Int,
    )

    private data class SearchResult(
        val lyrics: List<String>,
        val complete: Boolean,
    )

    companion object {
        private const val V2_SEARCH_PATH = "/v2/lyrics/search"
        private const val V1_SEARCH_PATH = "/v1/lyrics/search"
        private const val PROTOCOL_HEADER = "X-Lyprio-Protocol"
        private const val PROTOCOL_VERSION = "2"
        private const val ROMANIZATION_HEADER = "X-Lyprio-Romanization"
        private const val CLIENT_ROMANIZATION = "client"
        private const val REQUEST_TIMEOUT_MS = 25_000L
        internal const val SEARCH_TIMEOUT_MS = 50_000L
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        private const val MAX_TOTAL_WAIT_MS = 20_000L
        private const val READY_POLL_INTERVAL_MS = 2_000L
        private const val MAX_JITTER_MS = 1_000L
        private const val UNSUPPORTED_TTL_SECONDS = 600L

        fun createOrNull(
            httpClient: HttpClient,
            baseUrl: String,
            signingKeyBase64: String,
            allowInsecureHttp: Boolean = false,
            nowEpochSeconds: () -> Long = { Clock.System.now().toEpochMilliseconds() / 1_000 },
            delayMillis: suspend (Long) -> Unit = { delay(it) },
            nextJitterMs: () -> Long = { Random.nextLong(1, MAX_JITTER_MS + 1) },
        ): LyprioLyricsProvider? {
            val endpoint = lyprioEndpointOrNull(baseUrl, signingKeyBase64, allowInsecureHttp) ?: return null
            return LyprioLyricsProvider(
                httpClient,
                endpoint.baseUrl,
                endpoint.signingKey,
                nowEpochSeconds,
                delayMillis,
                nextJitterMs,
            )
        }
    }
}

internal data class LyprioEndpoint(
    val baseUrl: String,
    val signingKey: ByteArray,
)

@OptIn(ExperimentalEncodingApi::class)
internal fun lyprioEndpointOrNull(
    baseUrl: String,
    signingKeyBase64: String,
    allowInsecureHttp: Boolean,
): LyprioEndpoint? {
    val normalizedUrl = baseUrl.trimEnd('/')
    val parsed = runCatching { Url(normalizedUrl) }.getOrNull() ?: return null
    val validProtocol =
        parsed.protocol == URLProtocol.HTTPS ||
            (allowInsecureHttp && parsed.protocol == URLProtocol.HTTP)
    if (!validProtocol || parsed.host.isBlank() || parsed.user != null || parsed.password != null ||
        (parsed.protocol == URLProtocol.HTTPS && parsed.port != 443)
    ) {
        return null
    }
    val key =
        runCatching { Base64.decode(signingKeyBase64) }
            .getOrNull()
            ?.takeIf { it.size == 32 }
            ?: return null
    return LyprioEndpoint(normalizedUrl, key)
}

internal fun lyprioRequestSignature(
    signingKey: ByteArray,
    timestamp: String,
    body: String,
): String =
    DeviceCrypto
        .hmacSha256(signingKey, "$timestamp\n$body".encodeToByteArray())
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

@Serializable
private data class LyprioLyricsRequest(
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long? = null,
    val videoId: String? = null,
    val allSources: Boolean = false,
)

@Serializable
private data class LyprioLyricsResponse(
    val results: List<LyprioLyricsResult> = emptyList(),
    val status: String? = null,
    val complete: Boolean? = null,
    val retryAfterMs: Long? = null,
)

@Serializable
private data class LyprioLyricsResult(
    val subprovider: String,
    val format: String,
    val syncType: String,
    val lyrics: String,
)
