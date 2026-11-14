package com.metrolist.shared.api.lyrics

import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsResponseTest {
    @Test
    fun transientFailuresRetryWithBackoffWithoutErrorLogs() =
        runTest {
            PlaybackEventLog.clear()
            val requestedAt = mutableListOf<Long>()
            HttpClient(
                MockEngine {
                    requestedAt += testScheduler.currentTime
                    if (requestedAt.size < 3) {
                        respond("unavailable", HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond("lyrics", HttpStatusCode.OK)
                    }
                },
            ) { install(HttpFailureLog) }.use { client ->
                assertEquals("lyrics", client.prepareGet("https://lrclib.net/api/search") { expectMissingLyrics() }.readLyricsText())
                assertEquals(listOf(0L, 500L, 1_500L), requestedAt)
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
        }

    @Test
    fun retryAfterDelaysAreHonoredWithinABoundedBudget() =
        runTest {
            for ((retryAfter, expectedTimes) in listOf(
                "3" to listOf(0L, 3_000L),
                "60" to listOf(0L),
                "99999999999999999999" to listOf(0L),
            )) {
                val start = testScheduler.currentTime
                val requestedAt = mutableListOf<Long>()
                HttpClient(
                    MockEngine {
                        requestedAt += testScheduler.currentTime - start
                        respond("unavailable", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, retryAfter))
                    },
                ).use { client ->
                    assertEquals(
                        503,
                        assertFailsWith<LyricsHttpStatusException> {
                            client.prepareGet("https://lyrics.paxsenix.org/apple-music/lyrics") { expectMissingLyrics() }.readLyricsText()
                        }.statusCode,
                    )
                    assertEquals(expectedTimes, requestedAt)
                }
            }
        }

    @Test
    fun cancellationDuringBackoffDoesNotSendAnotherRequest() =
        runTest {
            var requests = 0
            HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        requests++
                        respond("unavailable", HttpStatusCode.ServiceUnavailable)
                    }
                }
            }.use { client ->
                val lookup =
                    launch {
                        client.prepareGet("https://lrclib.net/api/search") { expectMissingLyrics() }.readLyricsText()
                    }
                runCurrent()
                assertEquals(1, requests)
                lookup.cancelAndJoin()
                assertEquals(1, requests)
            }
        }

    @Test
    fun automaticCaptureSkipsOnlyKnownLyricsService503Responses() {
        val lyprio = "https://lyrics.example/base"
        for (url in listOf(
            "$lyprio/v2/lyrics/search",
            "https://lrclib.net/api/search?track_name=song",
            "https://lyrics.paxsenix.org/apple-music/lyrics",
        )) {
            assertTrue(isExpectedLyricsServiceFailure(url, 503, lyprio))
            assertFalse(isExpectedLyricsServiceFailure(url, 500, lyprio))
            assertFalse(isExpectedLyricsServiceFailure(url, 403, lyprio))
        }
        for (url in listOf(
            "https://other.example/api/search",
            "$lyprio/v2/lyrics/search/other",
            "https://lrclib.net.evil.test/api/search",
            "http://lrclib.net/api/search",
            "https://lrclib.net:8443/api/search",
            null,
        )) {
            assertFalse(isExpectedLyricsServiceFailure(url, 503, lyprio))
        }
        assertFalse(isExpectedLyricsServiceFailure("$lyprio/v2/lyrics/search", 503, ""))
    }
}
