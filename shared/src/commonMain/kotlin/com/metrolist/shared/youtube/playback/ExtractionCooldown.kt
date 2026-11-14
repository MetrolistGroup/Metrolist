package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.extraction.StreamResolveException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.encodedPath
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource

private val playbackTimeOrigin = TimeSource.Monotonic.markNow()

internal fun playbackElapsedMs(): Long = playbackTimeOrigin.elapsedNow().inWholeMilliseconds

internal class ExtractionCooldown(
    private val elapsedMs: () -> Long = ::playbackElapsedMs,
    private val wallTimeMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var untilMs = 0L
    private var backoffMs = 5_000L
    private var revision = 0L
    private var resolveFailures = 0
    private var lastResolveFailureMs = 0L
    private val failedClients = mutableMapOf<String, Long>()

    suspend fun <T> run(
        resetOnSuccess: Boolean = true,
        block: suspend () -> T,
    ): T {
        var startedRevision = 0L
        try {
            startedRevision =
                mutex.withLock {
                    checkAllowed()
                    revision
                }
            return withContext(ExtractionAttempt(this)) { block() }.also {
                mutex.withLock {
                    if (resetOnSuccess && revision == startedRevision) {
                        untilMs = 0L
                        backoffMs = 5_000L
                        resolveFailures = 0
                        failedClients.clear()
                    }
                }
            }
        } catch (error: ExtractionDeferred) {
            throw error.failure
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.isTransientExtractionFailure()) {
                mutex.withLock {
                    if (revision == startedRevision) {
                        val now = elapsedMs()
                        if (now - lastResolveFailureMs >= 120_000L) resolveFailures = 0
                        lastResolveFailureMs = now
                        if (++resolveFailures >= 2) trip()
                    }
                }
            }
            throw error
        }
    }

    suspend fun beforeRequest() = mutex.withLock { checkAllowed() }

    suspend fun response(
        status: Int,
        retryAfter: String?,
        client: String?,
    ) = mutex.withLock {
        when {
            status == 429 -> {
                trip(retryAfterDelayMs(retryAfter, wallTimeMs()))
                throw deferred()
            }

            status in setOf(408, 500, 502, 503, 504) -> {
                transientFailure(client)
            }

            status in 200..299 && client != null -> {
                failedClients.clear()
            }

            else -> {
                Unit
            }
        }
    }

    suspend fun transportFailure(client: String?) = mutex.withLock { transientFailure(client) }

    private fun transientFailure(client: String?) {
        if (client == null) return
        val now = elapsedMs()
        failedClients.entries.removeAll { now - it.value >= 30_000L }
        failedClients[client] = now
        if (failedClients.size >= 2) {
            trip()
            throw deferred()
        }
    }

    private fun trip(retryAfterMs: Long? = null) {
        val now = elapsedMs()
        val delay = maxOf(backoffMs, retryAfterMs ?: 0L)
        untilMs = maxOf(untilMs, now + delay.coerceAtMost(Long.MAX_VALUE - now))
        backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        resolveFailures = 0
        failedClients.clear()
        revision++
    }

    private fun checkAllowed() {
        if (elapsedMs() < untilMs) throw deferred()
    }

    private fun deferred() = ExtractionDeferred()
}

// InnerTubeX catches ordinary failures to try another client; cancellation must escape that loop.
internal class ExtractionDeferred : CancellationException() {
    val failure = StreamResolveException(StreamResolveException.Reason.NETWORK, "Unable to fetch stream data from YouTube.")
}

private class ExtractionAttempt(
    val cooldown: ExtractionCooldown,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExtractionAttempt>
}

internal val ExtractionHttpCooldown =
    createClientPlugin("ExtractionHttpCooldown") {
        on(Send) { request ->
            val cooldown = currentCoroutineContext()[ExtractionAttempt]?.cooldown
            val host = request.url.host
            if (cooldown == null || !(host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtubei.googleapis.com")) {
                return@on proceed(request)
            }
            cooldown.beforeRequest()
            val playerClient =
                request.headers["X-YouTube-Client-Name"].takeIf { request.url.encodedPath.endsWith("/player") }
            val call =
                try {
                    proceed(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error is IOException || error is HttpRequestTimeoutException) cooldown.transportFailure(playerClient)
                    throw error
                }
            try {
                cooldown.response(call.response.status.value, call.response.headers[HttpHeaders.RetryAfter], playerClient)
            } catch (error: ExtractionDeferred) {
                call.response.bodyAsChannel().cancel(null)
                throw error
            }
            call
        }
    }

internal fun retryAfterDelayMs(
    value: String?,
    nowMs: Long,
): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.all(Char::isDigit)) {
        return text.toLongOrNull()?.coerceAtMost(Long.MAX_VALUE / 1_000L)?.times(1_000L)
    }
    return runCatching { (text.fromHttpToGmtDate().timestamp - nowMs).coerceAtLeast(0L) }.getOrNull()
}

private fun Throwable.isTransientExtractionFailure(): Boolean {
    if (this is InnerTubeHttpException) return status.value in setOf(408, 429, 500, 502, 503, 504)
    if (this is IOException || this is HttpRequestTimeoutException) return true
    if (this is StreamResolveException && reason == StreamResolveException.Reason.NETWORK) {
        return cause?.let { it is TimeoutCancellationException || it.isTransientExtractionFailure() } ?: true
    }
    return false
}
