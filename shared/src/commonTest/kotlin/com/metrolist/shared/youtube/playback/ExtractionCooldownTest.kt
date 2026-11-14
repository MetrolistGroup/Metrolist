package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ExtractionCooldownTest {
    @Test
    fun realExtractorStopsBeforeCyclingRemainingPlayerClients() =
        runBlocking {
            for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
                Settings.replaceSharedForTest(Settings(MapSettings()))
                val requestedClients = mutableListOf<String?>()
                val client =
                    HttpClient(
                        MockEngine { request ->
                            if (request.url.encodedPath == "/s/player/test/base.js") {
                                return@MockEngine respond("", HttpStatusCode.NotFound)
                            }
                            assertEquals("/youtubei/v1/player", request.url.encodedPath)
                            requestedClients += request.headers["X-YouTube-Client-Name"]
                            respond("", status, headersOf(HttpHeaders.RetryAfter, "60"))
                        },
                    ) {
                        install(ExtractionHttpCooldown)
                        install(ContentNegotiation) { json() }
                    }
                val innerTube = InnerTube(client).apply { visitorData = "known-visitor" }
                val cipher = YouTubeCipherService(client)
                val extractor =
                    InnerTubeExtractor(
                        configParser =
                            object : YtConfigParser {
                                override suspend fun fetchConfig(
                                    videoId: String,
                                    useLoginCookies: Boolean,
                                ) = PlayerConfig("https://www.youtube.com/s/player/test/base.js", 123, "known-visitor", null)
                            },
                        cipherService = cipher,
                        innerTube = innerTube,
                    )
                val resolver = DefaultStreamResolver(extractor, innerTube, networkStateFlow = null)
                try {
                    assertNetworkFailure { resolver.resolve("first") }
                    assertEquals(if (status == HttpStatusCode.TooManyRequests) 1 else 2, requestedClients.distinct().size)
                    val calls = requestedClients.size
                    assertNetworkFailure { resolver.resolve("second") }
                    assertEquals(calls, requestedClients.size)
                } finally {
                    resolver.close()
                    cipher.dispose()
                    innerTube.close()
                    client.close()
                    Settings.replaceSharedForTest(null)
                }
            }
        }

    @Test
    fun retryAfterAcceptsSecondsAndHttpDatesWithoutTruncatingServerDelay() {
        assertEquals(120_000L, retryAfterDelayMs(" 120 ", 0))
        assertEquals(10_000L, retryAfterDelayMs("Thu, 01 Jan 1970 00:00:20 GMT", 10_000))
        assertEquals(0L, retryAfterDelayMs("Thu, 01 Jan 1970 00:00:20 GMT", 30_000))
        assertNull(retryAfterDelayMs("-1", 0))
        assertNull(retryAfterDelayMs("bad date", 0))
    }

    @Test
    fun rateLimitStopsClientLoopAndAllResolveKindsUntilRetryAfterExpires() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            var now = 0L
            var requests = 0
            var status = HttpStatusCode.TooManyRequests
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("", status, headersOf(HttpHeaders.RetryAfter, "120"))
                    },
                ) { install(ExtractionHttpCooldown) }
            val extractor = RequestingExtractor(client)
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(client),
                    networkStateFlow = null,
                    elapsedMs = { now },
                    localAudioSource =
                        LocalAudioSource { videoId, _ ->
                            if (videoId == "local") PlaybackResource(videoId, "file:/download", emptyMap(), null, null) else null
                        },
                )
            try {
                assertNetworkFailure { resolver.resolve("first") }
                assertEquals(1, requests)
                assertNetworkFailure { resolver.resolve("preload") }
                assertNetworkFailure { resolver.refresh("first") }
                assertNetworkFailure { resolver.resolveRemote("download", ContentHints(), AudioQuality.AUTO) }
                resolver.prewarm()
                assertEquals("file:/download", resolver.resolve("local").url)
                assertEquals(1, extractor.calls)
                assertEquals(0, extractor.prewarms)
                assertEquals(1, requests)
                now = 119_999L
                assertNetworkFailure { resolver.resolve("first") }
                now++
                status = HttpStatusCode.OK
                assertEquals("first", resolver.resolve("first").videoId)
                assertEquals(2, requests)
                status = HttpStatusCode.TooManyRequests
                assertNetworkFailure { resolver.resolve("second") }
                assertEquals("first", resolver.resolve("first").videoId)
            } finally {
                resolver.close()
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun outagesNeedTwoDifferentClientsAndBackOffButRejectionsRemainTrackSpecific() =
        runBlocking {
            for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.Forbidden, null)) {
                Settings.replaceSharedForTest(Settings(MapSettings()))
                var now = 0L
                var requests = 0
                val client =
                    HttpClient(
                        MockEngine {
                            requests++
                            if (status == null) throw IOException("test transport failure")
                            respond("", status)
                        },
                    ) { install(ExtractionHttpCooldown) }
                val resolver =
                    DefaultStreamResolver(RequestingExtractor(client), InnerTube(client), networkStateFlow = null, elapsedMs = { now })
                try {
                    assertFailsWith<StreamResolveException> { resolver.resolve("first") }
                    if (status != HttpStatusCode.Forbidden) {
                        assertEquals(3, requests)
                        assertNetworkFailure { resolver.resolve("second") }
                        assertEquals(3, requests)
                        now = 5_000
                        assertNetworkFailure { resolver.resolve("second") }
                        assertEquals(6, requests)
                        now = 14_999
                        assertNetworkFailure { resolver.resolve("third") }
                        assertEquals(6, requests)
                        now++
                        assertNetworkFailure { resolver.resolve("third") }
                        assertEquals(9, requests)
                    } else {
                        assertEquals(8, requests)
                        assertFailsWith<StreamResolveException> { resolver.resolve("second") }
                        assertEquals(16, requests)
                    }
                } finally {
                    resolver.close()
                    client.close()
                    Settings.replaceSharedForTest(null)
                }
            }
        }

    @Test
    fun backoffResetAllowsImmediateProbe() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("", HttpStatusCode.TooManyRequests)
                    },
                ) { install(ExtractionHttpCooldown) }
            val innerTube = InnerTube(client)
            val resolver =
                DefaultStreamResolver(
                    RequestingExtractor(client),
                    innerTube,
                    networkState = { PlaybackNetworkState("same") },
                    networkStateFlow = null,
                )
            try {
                assertNetworkFailure { resolver.resolve("first") }
                assertNetworkFailure { resolver.resolve("second") }
                assertEquals(1, requests)
                resolver.resetExtractionBackoff()
                assertNetworkFailure { resolver.resolve("third") }
                assertEquals(2, requests)
            } finally {
                resolver.close()
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun sessionAndNetworkChangesAllowImmediateProbeAndUnrelatedRequestsAreUnaffected() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            var requests = 0
            var network = PlaybackNetworkState("first")
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("", HttpStatusCode.TooManyRequests)
                    },
                ) { install(ExtractionHttpCooldown) }
            val innerTube = InnerTube(client)
            val resolver =
                DefaultStreamResolver(RequestingExtractor(client), innerTube, networkState = { network }, networkStateFlow = null)
            try {
                assertNetworkFailure { resolver.resolve("first") }
                innerTube.cookie = "SAPISID=changed"
                assertNetworkFailure { resolver.resolve("second") }
                network = PlaybackNetworkState("second")
                assertNetworkFailure { resolver.resolve("third") }
                client.get("https://www.youtube.com/youtubei/v1/player")
                assertEquals(4, requests)
            } finally {
                resolver.close()
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun cancellationDoesNotOpenCircuitAndRepeatedNetworkFailuresDo() =
        runBlocking {
            var now = 0L
            val cooldown = ExtractionCooldown(elapsedMs = { now })
            repeat(3) { assertFailsWith<CancellationException> { cooldown.run { throw CancellationException() } } }
            assertEquals(1, cooldown.run { 1 })
            repeat(3) {
                assertNetworkFailure {
                    cooldown.run {
                        throw StreamResolveException(
                            StreamResolveException.Reason.NETWORK,
                            "test rejection",
                            InnerTubeHttpException("player", HttpStatusCode.Forbidden),
                        )
                    }
                }
            }
            assertEquals(1, cooldown.run { 1 })
            repeat(2) {
                assertNetworkFailure {
                    cooldown.run { throw StreamResolveException(StreamResolveException.Reason.NETWORK, "test") }
                }
            }
            assertNetworkFailure { cooldown.run { error("Must not extract during cooldown") } }
            now = 5_000
            assertEquals(2, cooldown.run { 2 })
        }

    private suspend fun assertNetworkFailure(block: suspend () -> Any?) {
        assertEquals(
            StreamResolveException.Reason.NETWORK,
            assertFailsWith<StreamResolveException> { withTimeout(5_000L) { block() } }.reason,
        )
    }

    private class RequestingExtractor(
        private val client: HttpClient,
    ) : StreamExtractor {
        var calls = 0
        var prewarms = 0

        override suspend fun prewarm() {
            prewarms++
        }

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            calls++
            for (clientId in listOf("1", "1", "2", "3")) {
                try {
                    val response = client.get("https://www.youtube.com/youtubei/v1/player") { header("X-YouTube-Client-Name", clientId) }
                    if (response.status == HttpStatusCode.OK) {
                        return ExtractedStream(
                            videoId,
                            "https://example.test/$videoId",
                            emptyMap(),
                            null,
                            null,
                            null,
                            251,
                            "audio/webm",
                            "opus",
                            null,
                            null,
                            "client",
                            "profile",
                            false,
                            0L,
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
            }
            return null
        }
    }
}
