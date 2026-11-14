package meow.nyx.spotlib

import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpotifyClientRateLimitTest {
    @Test
    fun canvasQueryDoesNotLogAnExpiredTokenResponseWhenRefreshSucceeds() =
        runBlocking {
            PlaybackEventLog.clear()
            var queries = 0
            val client =
                SpotifyClient.create(
                    SpotifyConfig(
                        engine =
                            MockEngine { request ->
                                when (request.url.host) {
                                    "api-partner.spotify.com" -> {
                                        if (queries++ == 0) respond("", HttpStatusCode.Unauthorized) else respond("""{"data":{}}""")
                                    }

                                    "open.spotify.com" -> {
                                        respond(
                                            """{"accessToken":"fresh","accessTokenExpirationTimestampMs":4000000000000,"isAnonymous":true}""",
                                        )
                                    }

                                    "clienttoken.spotify.com" -> {
                                        respond("""{"granted_token":{"token":"new-client"}}""")
                                    }

                                    else -> {
                                        error("Unexpected host ${request.url.host}")
                                    }
                                }
                            },
                        logLevel = LogLevel.NONE,
                        allowAnonymous = true,
                    ),
                )
            client.use {
                client.attachSession(SpotifySession(accessToken = "stale", clientToken = "stale-client"))
                assertEquals("{}", client.canvasQuery("canvas", "hash", buildJsonObject {}).getValue("data").toString())
                assertEquals(2, queries)
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
            PlaybackEventLog.clear()
        }

    @Test
    fun importAuthenticationStillRejectsAnonymousTokens() =
        runBlocking {
            SpotifyClient
                .create(
                    SpotifyConfig(
                        engine =
                            MockEngine { request ->
                                assertEquals("open.spotify.com", request.url.host)
                                respond(
                                    """{"accessToken":"anonymous","accessTokenExpirationTimestampMs":4000000000000,"isAnonymous":true}""",
                                )
                            },
                        logLevel = LogLevel.NONE,
                    ),
                ).use { client ->
                    assertFailsWith<SpotifySessionExpiredException> { client.loginWithSession("own-cookie") }
                    assertEquals("own-cookie", client.session.cookies["sp_dc"])
                }
        }

    @Test
    fun anonymousCanvas401RefreshesOnlyOnce() =
        runBlocking {
            var queries = 0
            var refreshes = 0
            SpotifyClient
                .create(
                    SpotifyConfig(
                        engine =
                            MockEngine { request ->
                                when (request.url.host) {
                                    "api-partner.spotify.com" -> {
                                        queries++
                                        assertEquals(
                                            if (queries == 1) "Bearer stale" else "Bearer fresh",
                                            request.headers[HttpHeaders.Authorization],
                                        )
                                        respond("", HttpStatusCode.Unauthorized)
                                    }

                                    "open.spotify.com" -> {
                                        refreshes++
                                        respond(
                                            """{"accessToken":"fresh","accessTokenExpirationTimestampMs":4000000000000,"isAnonymous":true}""",
                                        )
                                    }

                                    "clienttoken.spotify.com" -> {
                                        respond("""{"granted_token":{"token":"new-client"}}""")
                                    }

                                    else -> {
                                        error("Unexpected host ${request.url.host}")
                                    }
                                }
                            },
                        logLevel = LogLevel.NONE,
                        allowAnonymous = true,
                    ),
                ).use { client ->
                    client.attachSession(SpotifySession(accessToken = "stale", clientToken = "client"))
                    assertFailsWith<SpotifySessionExpiredException> { client.canvasQuery("canvas", "hash", buildJsonObject {}) }
                    assertEquals(2, queries)
                    assertEquals(1, refreshes)
                }
        }

    @Test
    fun retryRevalidatesBearerTokenAfterRateLimit() =
        runBlocking {
            lateinit var client: SpotifyClient
            var queries = 0
            var refreshes = 0
            val engine =
                MockEngine { request ->
                    when (request.url.host) {
                        "api-partner.spotify.com" -> {
                            queries++
                            if (queries == 1) {
                                assertEquals("Bearer old", request.headers[HttpHeaders.Authorization])
                                client.attachSession(client.session.copy(accessTokenExpiresAtMs = 1))
                                respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "0"))
                            } else {
                                assertEquals("Bearer refreshed", request.headers[HttpHeaders.Authorization])
                                respond("""{"data":{}}""")
                            }
                        }

                        "open.spotify.com" -> {
                            refreshes++
                            respond("""{"accessToken":"refreshed","accessTokenExpirationTimestampMs":4000000000000,"isAnonymous":false}""")
                        }

                        else -> {
                            error("Unexpected host ${request.url.host}")
                        }
                    }
                }
            client = SpotifyClient.create(SpotifyConfig(engine = engine, logLevel = LogLevel.NONE))
            client.use {
                client.attachSession(
                    SpotifySession(mapOf("sp_dc" to "own-cookie"), "old", Long.MAX_VALUE, "client", System.currentTimeMillis()),
                )
                assertEquals("{}", client.query("libraryV3", "hash", buildJsonObject {}).getValue("data").toString())
                assertEquals(2, queries)
                assertEquals(1, refreshes)
                assertEquals("refreshed", client.session.accessToken)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun libraryQueryHonorsRetryAfterLongerThanFiveSeconds() =
        runTest {
            var calls = 0
            SpotifyClient
                .create(
                    SpotifyConfig(
                        engine =
                            MockEngine {
                                if (calls++ == 0) {
                                    respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "30"))
                                } else {
                                    respond("""{"data":{}}""")
                                }
                            },
                        logLevel = LogLevel.NONE,
                    ),
                ).use { client ->
                    client.attachSession(
                        SpotifySession(mapOf("sp_dc" to "own-cookie"), "access", Long.MAX_VALUE, "client", System.currentTimeMillis()),
                    )
                    val started = currentTime
                    client.query("libraryV3", "hash", buildJsonObject {})
                    assertEquals(30_000, currentTime - started)
                    assertEquals(2, calls)
                }
        }

    @Test
    fun libraryQueryRetries429AtMostTwice() =
        runBlocking {
            PlaybackEventLog.clear()
            var calls = 0
            val client =
                SpotifyClient.create(
                    SpotifyConfig(
                        engine =
                            MockEngine {
                                calls++
                                if (calls < 3) {
                                    respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "0"))
                                } else {
                                    respond("""{"data":{}}""")
                                }
                            },
                        logLevel = LogLevel.NONE,
                    ),
                )
            client.use {
                client.attachSession(
                    SpotifySession(mapOf("sp_dc" to "own-cookie"), "access", Long.MAX_VALUE, "client", System.currentTimeMillis()),
                )
                assertEquals("{}", client.query("libraryV3", "hash", buildJsonObject {}).getValue("data").toString())
            }
            assertEquals(3, calls)
            PlaybackEventLog.clear()
        }

    @Test
    fun terminalRateLimitIsLoggedOnceAfterRetriesAreExhausted() =
        runBlocking {
            PlaybackEventLog.clear()
            var calls = 0
            SpotifyClient
                .create(
                    SpotifyConfig(
                        engine =
                            MockEngine {
                                calls++
                                respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "0"))
                            },
                        logLevel = LogLevel.NONE,
                    ),
                ).use { client ->
                    client.attachSession(
                        SpotifySession(mapOf("sp_dc" to "own-cookie"), "access", Long.MAX_VALUE, "client", System.currentTimeMillis()),
                    )
                    assertFailsWith<SpotifyException> { client.query("libraryV3", "hash", buildJsonObject {}) }
                }
            assertEquals(3, calls)
            assertEquals(1, PlaybackEventLog.events.value.size)
            PlaybackEventLog.clear()
        }

    @Test
    fun retriedRateLimitDoesNotLogAnIntermediateFailure() =
        runBlocking {
            PlaybackEventLog.clear()
            var calls = 0
            SpotifyClient
                .create(
                    SpotifyConfig(
                        engine =
                            MockEngine {
                                if (calls++ == 0) {
                                    respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "0"))
                                } else {
                                    respond("""{"data":{}}""")
                                }
                            },
                        logLevel = LogLevel.NONE,
                    ),
                ).use { client ->
                    client.attachSession(
                        SpotifySession(mapOf("sp_dc" to "own-cookie"), "access", Long.MAX_VALUE, "client", System.currentTimeMillis()),
                    )
                    client.query("libraryV3", "hash", buildJsonObject {})
                    assertEquals(2, calls)
                    assertEquals(emptyList(), PlaybackEventLog.events.value)
                }
            PlaybackEventLog.clear()
        }

    @Test
    fun rateLimitRetriesAreBoundedAndCanvasDoesNotWait() =
        runBlocking {
            var calls = 0
            val client =
                SpotifyClient.create(
                    SpotifyConfig(
                        engine =
                            MockEngine {
                                calls++
                                respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "0"))
                            },
                        logLevel = LogLevel.NONE,
                    ),
                )
            client.use {
                client.attachSession(
                    SpotifySession(mapOf("sp_dc" to "own-cookie"), "access", Long.MAX_VALUE, "client", System.currentTimeMillis()),
                )
                assertFailsWith<SpotifyException> { client.query("libraryV3", "hash", buildJsonObject {}) }
                assertEquals(3, calls)
                assertFailsWith<SpotifyException> { client.canvasQuery("canvas", "hash", buildJsonObject {}) }
                assertEquals(4, calls)
            }
        }
}
