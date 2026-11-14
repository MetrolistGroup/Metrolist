package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.lyricsProviderLabel
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.http.headersOf as ktorHeadersOf

class LyprioLyricsProviderTest {
    @Test
    fun ranksActualTimingInsteadOfTheAdvertisedSyncTypeInBothProtocols() =
        runBlocking {
            val plain =
                """{"version":2,"syncType":"line","lines":[
                    {"startMs":0,"endMs":179999,"text":"First"},
                    {"startMs":0,"endMs":179999,"text":"Second"}]}"""
            val synced = MlxBuilder().meta("Lyprio").line(1_000, "Timed lyrics").build()
            val results = result(plain, "APPLE_MUSIC", "word") + "," + result(synced, "BETTERLYRICS", "line")
            for (v2 in listOf(false, true)) {
                HttpClient(
                    MockEngine { request ->
                        if (!v2 && request.url.encodedPath.startsWith("/v2/")) {
                            respond("", HttpStatusCode.NotFound)
                        } else {
                            respond(
                                if (v2) v2Response("ready", true, result = results) else """{"results":[$results]}""",
                                HttpStatusCode.OK,
                                headersOf("X-Lyprio-Protocol", "2"),
                            )
                        }
                    },
                ).use { client ->
                    assertEquals(listOf(synced, plain), provider(client).getLyricsResults("Song", "Artist", null))
                }
            }
        }

    private fun headersOf(vararg namesAndValues: String) =
        ktorHeadersOf(
            *namesAndValues
                .toList()
                .chunked(2)
                .map { (name, value) -> name to listOf(value) }
                .toTypedArray(),
        )

    @Test
    fun expectedUnavailablePollingStatusesDoNotLogFailures() =
        runBlocking {
            PlaybackEventLog.clear()
            var requestCount = 0
            val mlx = """{"version":2,"lines":[{"text":"Ready","startMs":1000}]}"""
            val client =
                HttpClient(
                    MockEngine {
                        requestCount++
                        if (requestCount < 3) {
                            val status = if (requestCount == 1) HttpStatusCode.TooManyRequests else HttpStatusCode.ServiceUnavailable
                            respond(
                                v2Response("unavailable", complete = false, retryAfterMs = 0L),
                                status,
                                headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                            )
                        } else {
                            respond(
                                v2Response("ready", complete = true, result = result(mlx)),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                            )
                        }
                    },
                ) { install(HttpFailureLog) }
            client.use {
                assertNotNull(provider(client).getLyrics("Song", "Artist", null))
                assertEquals(3, requestCount)
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
        }

    @Test
    fun exhaustedUnavailablePollingDoesNotLogExpectedServiceFailure() =
        runBlocking {
            PlaybackEventLog.clear()
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            v2Response("unavailable", complete = false, retryAfterMs = 25_000L),
                            HttpStatusCode.ServiceUnavailable,
                            headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                        )
                    },
                ) { install(HttpFailureLog) }

            client.use {
                assertFailsWith<LyricsHttpStatusException> { provider(client).getLyrics("Song", "Artist", null) }
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
        }

    @Test
    fun malformedUnavailableResponseFailsWithoutLogging() =
        runBlocking {
            PlaybackEventLog.clear()
            val client =
                HttpClient(
                    MockEngine {
                        respond("not-json", HttpStatusCode.ServiceUnavailable, headersOf("X-Lyprio-Protocol", "2"))
                    },
                ) { install(HttpFailureLog) }

            client.use {
                assertFailsWith<Exception> { provider(client).getLyrics("Song", "Artist", null) }
                assertEquals(0, PlaybackEventLog.events.value.size)
            }
        }

    @Test
    fun cleansIntroHeadersAcrossProtocolsFormatsAndResultSelection() =
        runBlocking {
            val payloads =
                listOf(
                    "mlx" to """{"version":2,"lines":[{"text":"Song - Artist","startMs":0},{"text":"Next","startMs":4000}]}""",
                    "mlx" to MlxBuilder().line(0, "Song - Artist").line(4_000, "Next").build(),
                    "plain" to "Song - Artist\nNext",
                    "lrc" to "[00:00.00]Song - Artist\n[00:04.00]Next",
                    "ttml" to
                        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0s" end="2s">Song - Artist</p><p begin="4s" end="5s">Next</p></div></body></tt>""",
                )
            for (legacy in listOf(false, true)) {
                for ((format, lyrics) in payloads) {
                    val result = """{"subprovider":"TEST","format":"$format","syncType":"line","lyrics":${Json.encodeToString(lyrics)}}"""
                    HttpClient(
                        MockEngine { request ->
                            if (legacy && request.url.encodedPath.startsWith("/v2/")) {
                                respond("", HttpStatusCode.NotFound)
                            } else if (legacy) {
                                respond("""{"results":[$result]}""", HttpStatusCode.OK)
                            } else {
                                respond(v2Response("ready", true, result = result), HttpStatusCode.OK, headersOf("X-Lyprio-Protocol", "2"))
                            }
                        },
                    ).use { client ->
                        val provider = provider(client)
                        val best = assertNotNull(provider.getLyrics("Song", "Artist", null))
                        val all = provider.getLyricsResults("Song", "Artist", null)
                        for (mlx in listOf(best.lyrics) + all) {
                            val lines = MlxParser.parse(mlx).lines
                            assertEquals(listOf("Next"), lines.map { it.text }, "$format legacy=$legacy")
                            assertEquals(if (format == "plain") 0L else 4_000L, lines.single().time)
                        }
                        assertEquals(1, all.size)
                    }
                }
            }
        }

    @Test
    fun signsV2SearchAndAccumulatesPartialLyricsUntilCompletion() =
        runBlocking {
            val mlx = """{"version":2,"subprovider":"TEST","song":{},"lines":[{"startMs":1000,"text":"Ready now"}]}"""
            val rich = MlxBuilder().meta("Rich").line(1_000, 2_000, words = listOf(MlxWord("Later", 1_000, 2_000))).build()
            var timestamp = TEST_TIMESTAMP
            var requestCount = 0
            val waits = mutableListOf<Long>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requestCount++
                        val body = (request.body as TextContent).text
                        assertEquals("/v2/lyrics/search", request.url.encodedPath)
                        val requestTimestamp = (TEST_TIMESTAMP + requestCount - 1).toString()
                        assertEquals(requestTimestamp, request.headers["X-Lyprio-Timestamp"])
                        assertEquals(
                            lyprioRequestSignature(TEST_KEY, requestTimestamp, body),
                            request.headers["X-Lyprio-Signature"],
                        )
                        if (requestCount == 1) {
                            respond(
                                v2Response("pending", complete = false, retryAfterMs = 100L),
                                HttpStatusCode.Accepted,
                                headersOf(
                                    HttpHeaders.ContentType,
                                    "application/json",
                                    HttpHeaders.RetryAfter,
                                    "2",
                                    "X-Lyprio-Protocol",
                                    "2",
                                ),
                            )
                        } else {
                            assertEquals(
                                "true",
                                Json
                                    .parseToJsonElement(body)
                                    .jsonObject["allSources"]
                                    ?.jsonPrimitive
                                    ?.content,
                            )
                            respond(
                                if (requestCount == 2) {
                                    v2Response("ready", complete = false, result = result(mlx))
                                } else {
                                    v2Response("ready", complete = true, result = result(rich, "RICH", "word"))
                                },
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                            )
                        }
                    },
                )

            client.use {
                val provider = provider(client, now = { timestamp++ }, wait = waits::add, jitter = { 7L })
                val results = provider.getLyricsResults("Song", "Artist", null)
                assertEquals(3, requestCount)
                assertEquals(listOf(2_007L, 2_007L), waits)
                assertEquals(2, results.size)
                assertEquals(rich, results.first())
                assertEquals(
                    "Ready now",
                    MlxParser
                        .parse(results.last())
                        .lines
                        .single()
                        .text,
                )
                assertEquals("Lyprio/TEST", lyricsProviderLabel("Lyprio", MlxParser.parse(results.last())))
            }
        }

    @Test
    fun cleanV2MissRetriesAllSourcesOnlyOnce() =
        runBlocking {
            var requestCount = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        requestCount++
                        assertEquals("/v2/lyrics/search", request.url.encodedPath)
                        val query = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals(requestCount == 2, query["allSources"]?.jsonPrimitive?.content == "true")
                        respond(
                            v2Response("not_found", complete = true),
                            HttpStatusCode.NotFound,
                            headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                        )
                    },
                )

            client.use {
                assertNull(provider(client).getLyrics("Missing", "Nobody", null))
                assertEquals(2, requestCount)
            }
        }

    @Test
    fun resultsNamingAnotherArtistAreSkipped() =
        runBlocking {
            // A live catalog response matched Baby Jane to the distinct artist Baby J. through the shared title Savior.
            val otherSong =
                MlxBuilder()
                    .meta(
                        "Lyprio",
                    ).song(title = "SAVIOR", artist = "Baby J.")
                    .line(1_000, "A lot of things weighing on my mind")
                    .build()
            val requestedSong =
                MlxBuilder()
                    .meta("Lyprio")
                    .song(title = "Savior", artist = "Baby Jane")
                    .line(1_000, "Shine down on me")
                    .build()
            var onlyOtherArtist = false
            HttpClient(
                MockEngine {
                    val results =
                        if (onlyOtherArtist) {
                            result(otherSong, "APPLE_MUSIC_OFFLINE")
                        } else {
                            result(otherSong, "APPLE_MUSIC_OFFLINE") + "," + result(requestedSong, "BETTERLYRICS")
                        }
                    respond(v2Response("ready", true, result = results), HttpStatusCode.OK, headersOf("X-Lyprio-Protocol", "2"))
                },
            ).use { client ->
                val provider = provider(client)
                assertEquals(requestedSong, assertNotNull(provider.getLyrics("Savior", "Baby Jane", 187_000, "Savior")).lyrics)
                assertEquals(listOf(requestedSong), provider.getLyricsResults("Savior", "Baby Jane", 187_000, "Savior"))
                onlyOtherArtist = true
                assertNull(provider.getLyrics("Savior 2", "Baby Jane", 187_000))
            }
        }

    @Test
    fun automaticMissRecoversTheSameLyricsAsTheSourcePicker() =
        runBlocking {
            val mlx = MlxBuilder().meta("Lyprio").line(1_000, "Recovered lyrics").build()
            val searches = mutableListOf<Boolean>()
            HttpClient(
                MockEngine { request ->
                    val body = (request.body as TextContent).text
                    val query = Json.parseToJsonElement(body).jsonObject
                    val allSources = query["allSources"]?.jsonPrimitive?.content == "true"
                    searches += allSources
                    assertEquals("Song", query.getValue("title").jsonPrimitive.content)
                    assertEquals("Artist", query.getValue("artist").jsonPrimitive.content)
                    assertEquals("Album", query.getValue("album").jsonPrimitive.content)
                    assertEquals("180", query.getValue("duration").jsonPrimitive.content)
                    assertEquals("video", query.getValue("videoId").jsonPrimitive.content)
                    assertEquals(
                        lyprioRequestSignature(TEST_KEY, TEST_TIMESTAMP.toString(), body),
                        request.headers["X-Lyprio-Signature"],
                    )
                    respond(
                        if (allSources) v2Response("ready", true, result = result(mlx)) else v2Response("not_found", true),
                        if (allSources) HttpStatusCode.OK else HttpStatusCode.NotFound,
                        headersOf("X-Lyprio-Protocol", "2"),
                    )
                },
            ).use { client ->
                val provider = provider(client)
                val automatic = assertNotNull(provider.getLyrics("Song", "Artist", 180_000, "Album", "video"))
                assertEquals(mlx, automatic.lyrics)
                assertTrue(automatic.isComplete)
                assertEquals(listOf(false, true), searches)
                assertEquals(listOf(automatic.lyrics), provider.getLyricsResults("Song", "Artist", 180_000, "Album", "video"))
                assertEquals(listOf(false, true, true), searches)
            }
        }

    @Test
    fun automaticAndAllSourcesFailuresPropagateInsteadOfBecomingMisses() =
        runBlocking {
            for (failAllSources in listOf(false, true)) {
                var requests = 0
                HttpClient(
                    MockEngine {
                        requests++
                        val cleanMiss = failAllSources && requests == 1
                        respond(
                            if (cleanMiss) v2Response("not_found", true) else "invalid response",
                            if (cleanMiss) HttpStatusCode.NotFound else HttpStatusCode.InternalServerError,
                            headersOf("X-Lyprio-Protocol", "2"),
                        )
                    },
                ).use { client ->
                    assertFailsWith<Exception> { provider(client).getLyrics("Song", "Artist", null) }
                    assertEquals(if (failAllSources) 2 else 1, requests)
                }
            }
        }

    @Test
    fun cancellationDuringAllSourcesPollingPropagates() =
        runBlocking {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        if (requests == 1) v2Response("not_found", true) else v2Response("pending", false, retryAfterMs = 0),
                        if (requests == 1) HttpStatusCode.NotFound else HttpStatusCode.Accepted,
                        headersOf("X-Lyprio-Protocol", "2"),
                    )
                },
            ).use { client ->
                val provider = provider(client, wait = { throw CancellationException("cancelled") })
                assertFailsWith<CancellationException> { provider.getLyrics("Song", "Artist", null) }
                assertEquals(2, requests)
            }
        }

    @Test
    fun incompleteAllSourcesLyricsRemainIncomplete() =
        runBlocking {
            val mlx = MlxBuilder().meta("Lyprio").line(1_000, "Available so far").build()
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        if (requests == 1) {
                            v2Response("not_found", true)
                        } else {
                            v2Response("ready", false, retryAfterMs = 21_000, result = result(mlx))
                        },
                        if (requests == 1) HttpStatusCode.NotFound else HttpStatusCode.OK,
                        headersOf("X-Lyprio-Protocol", "2"),
                    )
                },
            ).use { client ->
                val lyrics = assertNotNull(provider(client).getLyrics("Song", "Artist", null))
                assertEquals(mlx, lyrics.lyrics)
                assertFalse(lyrics.isComplete)
                assertEquals(2, requests)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun automaticAndAllSourcesSearchShareOneTimeoutBudget() =
        runTest {
            var requests = 0
            HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        requests++
                        delay(20_000)
                        respond(
                            if (requests == 1) v2Response("not_found", true) else v2Response("pending", false, retryAfterMs = 1_000),
                            if (requests == 1) HttpStatusCode.NotFound else HttpStatusCode.Accepted,
                            headersOf("X-Lyprio-Protocol", "2"),
                        )
                    }
                }
            }.use { client ->
                assertFailsWith<TimeoutCancellationException> {
                    provider(client, wait = { delay(it) }).getLyrics("Song", "Artist", null)
                }
                assertEquals(3, requests)
                assertEquals(LyprioLyricsProvider.SEARCH_TIMEOUT_MS, testScheduler.currentTime)
            }
        }

    @Test
    fun fallsBackOnlyForUnsupportedRouteAndOptsOutOfServerRomanization() =
        runBlocking {
            val mlx = """{"version":2,"song":{},"lines":[{"startMs":1000,"text":"Legacy"}]}"""
            val paths = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        if (request.url.encodedPath == "/v2/lyrics/search") {
                            respond("{}", HttpStatusCode.NotFound)
                        } else {
                            assertEquals("client", request.headers["X-Lyprio-Romanization"])
                            respond(
                                """{"results":[${result(mlx)}]}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                )

            client.use {
                val lyrics = assertNotNull(provider(client).getLyrics("Song", "Artist", null))
                assertEquals(listOf("/v2/lyrics/search", "/v1/lyrics/search"), paths)
                assertEquals(
                    "Legacy",
                    MlxParser
                        .parse(lyrics.lyrics)
                        .lines
                        .single()
                        .text,
                )
            }
        }

    @Test
    fun legacyFallbackBroadensAnAutomaticMiss() =
        runBlocking {
            val paths = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    paths += request.url.encodedPath
                    val query = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(paths.size == 3, query["allSources"]?.jsonPrimitive?.content == "true")
                    respond("{\"results\":[]}", HttpStatusCode.NotFound)
                },
            ).use { client ->
                assertNull(provider(client).getLyrics("Song", "Artist", null))
                assertEquals(listOf("/v2/lyrics/search", "/v1/lyrics/search", "/v1/lyrics/search"), paths)
            }
        }

    @Test
    fun methodNotAllowedFallsBackToV1() =
        runBlocking {
            PlaybackEventLog.clear()
            val paths = mutableListOf<String>()
            val v1AllSources = mutableListOf<Boolean>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        if (request.url.encodedPath == "/v2/lyrics/search") {
                            respond("{}", HttpStatusCode.MethodNotAllowed)
                        } else {
                            assertEquals("client", request.headers["X-Lyprio-Romanization"])
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            v1AllSources += body["allSources"]?.jsonPrimitive?.content == "true"
                            respond("{\"results\":[]}", HttpStatusCode.NotFound)
                        }
                    },
                )

            client.use {
                assertNull(provider(client).getLyrics("Missing", "Nobody", null))
                assertEquals(listOf("/v2/lyrics/search", "/v1/lyrics/search", "/v1/lyrics/search"), paths)
                assertEquals(listOf(false, true), v1AllSources)
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
        }

    @Test
    fun methodNotAllowedFromV1FailsWithoutLogging() =
        runBlocking {
            PlaybackEventLog.clear()
            val client =
                HttpClient(
                    MockEngine { respond("{}", HttpStatusCode.MethodNotAllowed) },
                )

            client.use {
                assertFailsWith<IllegalStateException> { provider(client).getLyrics("Song", "Artist", null) }
                assertEquals(0, PlaybackEventLog.events.value.size)
            }
        }

    @Test
    fun remembersUnsupportedRouteUntilCapabilityCacheExpires() =
        runBlocking {
            val mlx = """{"version":2,"song":{},"lines":[{"startMs":1,"text":"Legacy"}]}"""
            var now = 1_000L
            var v2Supported = false
            val paths = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        if (request.url.encodedPath == "/v2/lyrics/search" && !v2Supported) {
                            respond("{}", HttpStatusCode.NotFound)
                        } else if (request.url.encodedPath == "/v2/lyrics/search") {
                            respond(
                                v2Response("not_found", complete = true),
                                HttpStatusCode.NotFound,
                                headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                            )
                        } else {
                            respond(
                                """{"results":[${result(mlx)}]}""",
                                HttpStatusCode.OK,
                            )
                        }
                    },
                )

            client.use {
                val lyrics = provider(client, now = { now })
                lyrics.getLyrics("Song", "Artist", null)
                lyrics.getLyrics("Song", "Artist", null)
                assertEquals(listOf("/v2/lyrics/search", "/v1/lyrics/search", "/v1/lyrics/search"), paths)
                now += 600
                v2Supported = true
                lyrics.getLyrics("Song", "Artist", null)
                assertEquals("/v2/lyrics/search", paths.last())
            }
        }

    @Test
    fun exhaustsPendingPollsAsFailureAndPropagatesCancellation() =
        runBlocking<Unit> {
            var requestCount = 0
            val client =
                HttpClient(
                    MockEngine {
                        requestCount++
                        respond(
                            v2Response("pending", complete = false, retryAfterMs = 2_000L),
                            HttpStatusCode.Accepted,
                            headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                        )
                    },
                )

            client.use {
                val provider = provider(client, wait = {}, jitter = { 1L })
                assertFailsWith<IllegalStateException> { provider.getLyrics("Song", "Artist", null) }
                assertEquals(10, requestCount)
            }

            val cancelledClient =
                HttpClient(
                    MockEngine {
                        respond(
                            v2Response("pending", complete = false, retryAfterMs = 0L),
                            HttpStatusCode.Accepted,
                            headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                        )
                    },
                )
            cancelledClient.use {
                val provider = provider(cancelledClient, wait = { throw CancellationException("cancelled") })
                assertFailsWith<CancellationException> { provider.getLyrics("Song", "Artist", null) }
            }
        }

    @Test
    fun retriesTemporaryAdmissionFailureWithinRetryAfterBudget() =
        runBlocking {
            var requestCount = 0
            val waits = mutableListOf<Long>()
            val client =
                HttpClient(
                    MockEngine {
                        requestCount++
                        if (requestCount == 1) {
                            respond(
                                v2Response("unavailable", complete = false, retryAfterMs = 500L),
                                HttpStatusCode.TooManyRequests,
                                headersOf(
                                    HttpHeaders.ContentType,
                                    "application/json",
                                    HttpHeaders.RetryAfter,
                                    "2",
                                    "X-Lyprio-Protocol",
                                    "2",
                                ),
                            )
                        } else {
                            respond(
                                v2Response(
                                    "ready",
                                    complete = true,
                                    result = result("""{"version":2,"song":{},"lines":[{"startMs":1,"text":"ok"}]}"""),
                                ),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                            )
                        }
                    },
                )

            client.use {
                assertNotNull(provider(client, wait = waits::add, jitter = { 1L }).getLyrics("Song", "Artist", null))
                assertEquals(2, requestCount)
                assertEquals(listOf(2_001L), waits)
            }
        }

    @Test
    fun honorsHttpDateRetryAfterWithoutRetryingBeforeItsDeadline() =
        runBlocking {
            var requestCount = 0
            val client =
                HttpClient(
                    MockEngine {
                        requestCount++
                        respond(
                            v2Response("unavailable", complete = false, retryAfterMs = 100L),
                            HttpStatusCode.ServiceUnavailable,
                            headersOf(
                                HttpHeaders.ContentType,
                                "application/json",
                                HttpHeaders.RetryAfter,
                                "Thu, 01 Jan 2099 00:00:00 GMT",
                                "X-Lyprio-Protocol",
                                "2",
                            ),
                        )
                    },
                )

            client.use {
                assertFailsWith<IllegalStateException> { provider(client, wait = {}).getLyrics("Song", "Artist", null) }
                assertEquals(1, requestCount)
            }
        }

    @Test
    fun boundsHugeRetryAfterAndUsesBodyDelayForMalformedHeader() =
        runBlocking {
            var requestCount = 0
            val waits = mutableListOf<Long>()
            val client =
                HttpClient(
                    MockEngine {
                        requestCount++
                        respond(
                            v2Response("unavailable", complete = false, retryAfterMs = 21_000L),
                            HttpStatusCode.ServiceUnavailable,
                            headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                        )
                    },
                )

            client.use {
                assertFailsWith<IllegalStateException> {
                    provider(client, wait = waits::add, jitter = { 1L }).getLyrics("Song", "Artist", null)
                }
                assertEquals(1, requestCount)
                assertTrue(waits.isEmpty())
            }

            requestCount = 0
            val invalidDelayClient =
                HttpClient(
                    MockEngine {
                        requestCount++
                        respond(
                            v2Response("unavailable", complete = false, retryAfterMs = 1_000L),
                            HttpStatusCode.ServiceUnavailable,
                            headersOf(
                                HttpHeaders.ContentType,
                                "application/json",
                                HttpHeaders.RetryAfter,
                                "soon",
                                "X-Lyprio-Protocol",
                                "2",
                            ),
                        )
                    },
                )
            invalidDelayClient.use {
                assertFailsWith<IllegalStateException> {
                    provider(invalidDelayClient, wait = waits::add, jitter = { 1L }).getLyrics("Song", "Artist", null)
                }
                assertEquals(20, requestCount)
                assertEquals(List(19) { 1_001L }, waits)
            }
        }

    @Test
    fun pendingPollingCanFindLyricsAfterMoreThanFiveRetries() =
        runBlocking {
            val mlx = MlxBuilder().meta("Lyprio").line(1_000, "Arrived later").build()
            var requests = 0
            val waits = mutableListOf<Long>()
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        if (requests < 9) {
                            v2Response("pending", complete = false, retryAfterMs = 2_000L)
                        } else {
                            v2Response("ready", complete = true, result = result(mlx))
                        },
                        if (requests < 9) HttpStatusCode.Accepted else HttpStatusCode.OK,
                        headersOf("X-Lyprio-Protocol", "2"),
                    )
                },
            ).use { client ->
                val lyrics = assertNotNull(provider(client, wait = waits::add).getLyrics("Song", "Artist", null))
                assertEquals(mlx, lyrics.lyrics)
                assertTrue(lyrics.isComplete)
                assertEquals(9, requests)
                assertEquals(16_008L, waits.sum())
            }
        }

    @Test
    fun returnsBestAccumulatedPartialWithoutMarkingItCompleteWhenBudgetExpires() =
        runBlocking {
            val line = MlxBuilder().meta("Lyprio").line(1_000, "Earlier line").build()
            val word = MlxBuilder().meta("Lyprio").line(1_000, 2_000, words = listOf(MlxWord("Better", 1_000, 2_000))).build()
            var requests = 0
            val waits = mutableListOf<Long>()
            HttpClient(
                MockEngine { request ->
                    requests++
                    assertNull(Json.parseToJsonElement((request.body as TextContent).text).jsonObject["allSources"])
                    respond(
                        v2Response(
                            "ready",
                            complete = false,
                            retryAfterMs = 2_000L,
                            result = if (requests == 2) result(word, syncType = "word") else result(line),
                        ),
                        HttpStatusCode.OK,
                        headersOf("X-Lyprio-Protocol", "2"),
                    )
                },
            ).use { client ->
                val lyrics = assertNotNull(provider(client, wait = waits::add).getLyrics("Song", "Artist", null))
                assertEquals(word, lyrics.lyrics)
                assertFalse(lyrics.isComplete)
                assertEquals(10, requests)
                assertEquals(18_009L, waits.sum())
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun returnsAccumulatedPartialWhenNextPollStalls() =
        runTest {
            val mlx = MlxBuilder().meta("Lyprio").line(1_000, "Already available").build()
            var requests = 0
            HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        if (++requests > 1) delay(30_000L)
                        respond(
                            v2Response("ready", complete = false, retryAfterMs = 2_000L, result = result(mlx)),
                            HttpStatusCode.OK,
                            headersOf("X-Lyprio-Protocol", "2"),
                        )
                    }
                }
            }.use { client ->
                val lyrics = assertNotNull(provider(client, wait = { delay(it) }).getLyrics("Song", "Artist", null))
                assertEquals(mlx, lyrics.lyrics)
                assertFalse(lyrics.isComplete)
                assertTrue(testScheduler.currentTime in 19_000L..20_000L)
            }
        }

    @Test
    fun rejectsInvalidProtocolAndOversizedPayloads() =
        runBlocking<Unit> {
            val invalidClient =
                HttpClient(
                    MockEngine {
                        respond(v2Response("ready", complete = true), HttpStatusCode.OK)
                    },
                )
            invalidClient.use {
                assertFailsWith<IllegalStateException> { provider(invalidClient).getLyrics("Song", "Artist", null) }
            }

            val oversizedClient =
                HttpClient(
                    MockEngine {
                        respond(
                            "{}",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentLength, (8 * 1024 * 1024 + 1).toString(), "X-Lyprio-Protocol", "2"),
                        )
                    },
                )
            oversizedClient.use {
                assertFailsWith<IllegalStateException> { provider(oversizedClient).getLyrics("Song", "Artist", null) }
            }
        }

    @Test
    fun prefersWordSyncedResultAndExplicitlyRequestsAllSources() =
        runBlocking {
            val unsynced = """{"version":2,"song":{},"lines":[{"startMs":1000,"text":"Plain lyrics"}]}"""
            val wordSynced =
                """{"version":2,"song":{},"lines":[{"startMs":1000,"endMs":2000,"text":"Word lyrics","words":[{"text":"Word ","startMs":1000,"endMs":1500},{"text":"lyrics","startMs":1500,"endMs":2000}]}]}"""
            val response =
                v2Response(
                    "ready",
                    true,
                    result =
                        result(unsynced, "PLAIN", "unsynced") + "," + result(wordSynced, "RICH", "word"),
                )
            val client =
                HttpClient(
                    MockEngine { request ->
                        val query = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals("true", query.getValue("allSources").jsonPrimitive.content)
                        respond(
                            response,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json", "X-Lyprio-Protocol", "2"),
                        )
                    },
                )

            client.use {
                val results = provider(client).getLyricsResults("Song", "Artist", null)
                assertEquals(2, results.size)
                assertTrue(
                    MlxParser
                        .parse(results.first())
                        .lines
                        .first()
                        .words
                        .orEmpty()
                        .isNotEmpty(),
                )
                assertEquals(
                    "Plain lyrics",
                    MlxParser
                        .parse(results.last())
                        .lines
                        .first()
                        .text,
                )
            }
        }

    @Test
    fun validatesEndpointAndParsesLargeCompactMlx() {
        val line =
            """{"startMs":1000,"endMs":2000,"text":"Never gonna give you up","words":[{"text":"Never ","startMs":1000,"endMs":1500},{"text":"gonna","startMs":1500,"endMs":2000}]}"""
        val mlx = """{"version":2,"song":{},"lines":[${List(120) { line }.joinToString()}]}"""

        assertTrue(mlx.length > 16 * 1024)
        assertEquals(120, MlxParser.parse(mlx).lines.size)

        val client = HttpClient(MockEngine { error("network must not be called") })
        client.use {
            assertNull(
                LyprioLyricsProvider.createOrNull(
                    httpClient = client,
                    baseUrl = "http://192.168.0.158:8084",
                    signingKeyBase64 = TEST_KEY_BASE64,
                ),
            )
        }
    }

    private fun provider(
        client: HttpClient,
        now: () -> Long = { TEST_TIMESTAMP },
        wait: suspend (Long) -> Unit = {},
        jitter: () -> Long = { 1L },
    ): LyprioLyricsProvider =
        assertNotNull(
            LyprioLyricsProvider.createOrNull(
                httpClient = client,
                baseUrl = "https://lyrics.example",
                signingKeyBase64 = TEST_KEY_BASE64,
                nowEpochSeconds = now,
                delayMillis = wait,
                nextJitterMs = jitter,
            ),
        )

    private fun v2Response(
        status: String,
        complete: Boolean,
        retryAfterMs: Long? = null,
        result: String? = null,
    ): String =
        """{"results":[${result.orEmpty()}],"sources":[],"status":"$status","complete":$complete${retryAfterMs?.let {
            ",\"retryAfterMs\":$it"
        }.orEmpty()}}"""

    private fun result(
        lyrics: String,
        subprovider: String = "TEST",
        syncType: String = "line",
    ): String = """{"subprovider":"$subprovider","format":"mlx","syncType":"$syncType","lyrics":${Json.encodeToString(lyrics)}}"""

    private companion object {
        const val TEST_TIMESTAMP = 1_800_000_000L
        const val TEST_KEY_BASE64 = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
        val TEST_KEY = "01234567890123456789012345678901".encodeToByteArray()
    }
}
