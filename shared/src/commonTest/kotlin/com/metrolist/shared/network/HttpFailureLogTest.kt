package com.metrolist.shared.network

import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpFailureLogTest {
    @AfterTest
    fun tearDown() {
        PlaybackEventLog.clear()
    }

    @Test
    fun logsOnlyFailedResponsesAndTransportExceptionsOnceWithoutRequestData() =
        runBlocking {
            PlaybackEventLog.clear()
            val client =
                HttpClient(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/success" -> respond("ok", HttpStatusCode.OK)
                            "/missing" -> respond("missing", HttpStatusCode.NotFound)
                            "/failure" -> respond("failed", HttpStatusCode.InternalServerError)
                            else -> throw IllegalStateException("https://example.test/private?token=secret")
                        }
                    },
                ) {
                    install(HttpFailureLog) {
                        transportHandledByEngine = true
                        expectedStatusCodes = setOf(HttpStatusCode.NotFound.value)
                    }
                    expectSuccess = false
                }
            try {
                client.get("https://example.test/success?token=secret")
                assertTrue(PlaybackEventLog.events.value.isEmpty())

                client.get("https://example.test/missing?token=secret")
                assertTrue(PlaybackEventLog.events.value.isEmpty())

                client.get("https://example.test/failure?token=secret")
                assertEquals(1, PlaybackEventLog.events.value.size)

                runCatching { client.get("https://example.test/transport?token=secret") }
                val events = PlaybackEventLog.events.value
                assertEquals(2, events.size)
                assertTrue(events.all { it.kind == PlaybackEventKind.NETWORK })
                assertFalse(PlaybackEventLog.exportText().contains("secret"))
                assertFalse(PlaybackEventLog.exportText().contains("private"))
            } finally {
                client.close()
            }
        }

    @Test
    fun cancellationDoesNotBecomeANetworkFailure() =
        runBlocking {
            PlaybackEventLog.clear()
            val client =
                HttpClient(MockEngine { throw CancellationException("cancelled") }) {
                    install(HttpFailureLog)
                }
            try {
                runCatching { client.get("https://example.test/cancel") }
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun requestTimeoutIsLoggedBeforeCancellationIsRethrown() =
        runBlocking {
            PlaybackEventLog.clear()
            val client =
                HttpClient(
                    MockEngine {
                        throw object : CancellationException("cancelled") {
                            override val cause: Throwable =
                                HttpRequestTimeoutException("https://example.test/path", 1L)
                        }
                    },
                ) {
                    install(HttpFailureLog)
                }
            try {
                runCatching { client.get("https://example.test/path?token=secret") }
                val events = PlaybackEventLog.events.value
                assertEquals(1, events.size)
                assertTrue(events.single().message.contains("request timed out"))
                assertFalse(PlaybackEventLog.exportText().contains("secret"))
            } finally {
                client.close()
            }
        }

    @Test
    fun requestCanMarkFallbackStatusesAsExpected() =
        runBlocking {
            PlaybackEventLog.clear()
            val client =
                HttpClient(
                    MockEngine { respond("unsupported structured response", HttpStatusCode.UnprocessableEntity) },
                ) {
                    install(HttpFailureLog)
                }
            try {
                client.get("https://example.test/translation") {
                    attributes.put(ExpectedHttpFailureStatusCodes, setOf(HttpStatusCode.UnprocessableEntity.value))
                }
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            } finally {
                client.close()
            }
        }
}
