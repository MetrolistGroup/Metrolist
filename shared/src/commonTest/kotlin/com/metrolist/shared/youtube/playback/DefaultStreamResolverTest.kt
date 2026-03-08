package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamAttemptDiagnostic
import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartResolutionSource
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.api.musicvideo.MusicVideoCounterpart
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.api.musicvideo.MusicVideoResult
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.sabrAudioResource
import com.metrolist.shared.playback.sabrAudioResponse
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.strategy.ClientHealthTracker
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DefaultStreamResolverTest {
    private val cutoffProfiles = setOf("VISIONOS_0_1__nopo", "VISIONOS_SABR__nopo")
    private val previousNetwork = PlaybackNetworkStateStore.current
    private val hlsMasterUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/test/playlist/index.m3u8"
    private val hlsMasterPlaylist =
        """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-STREAM-INF:BANDWIDTH=269034,CODECS="mp4a.40.5,avc1.42000b",RESOLUTION=256x144,FRAME-RATE=15.000
        https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/91/playlist/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=962461,CODECS="mp4a.40.2,avc1.4d401e",RESOLUTION=640x360,FRAME-RATE=30.000
        https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/93/playlist/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4561186,CODECS="mp4a.40.2,avc1.640028",RESOLUTION=1920x1080,FRAME-RATE=30.000
        https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/96/playlist/index.m3u8
        """.trimIndent()

    @Test
    fun firstPendingTokenResponseEvictsTheCachedProfileBeforeBufferDrain() =
        runBlocking {
            val extractor =
                RecordingStreamExtractor(
                    listOf("WEB" to "WEB_SABR__po", "WEB_REMIX" to "WEB_REMIX_SABR__nopo"),
                    sabrBootstrap = checkNotNull(sabrAudioResource().sabrBootstrap).copy(poToken = byteArrayOf(1)),
                )
            val client = HttpClient(MockEngine { respondOk() })
            val resolver = DefaultStreamResolver(extractor, InnerTube(client), networkStateFlow = null)
            val audio = ByteArray(4096)
            val sabrClient =
                HttpClient(
                    MockEngine {
                        respond(
                            sabrAudioResponse(audio) + byteArrayOf(58, 2, 8, 2),
                            headers = headersOf(HttpHeaders.ContentType, "application/vnd.yt-ump"),
                        )
                    },
                )
            try {
                val cached = resolver.resolve("video")
                assertEquals(cached.profileId, resolver.resolve("video").profileId)
                assertFailsWith<SabrProtocolException> {
                    sabrAudioPlaybackChunks(
                        sabrClient,
                        checkNotNull(cached.sabrBootstrap),
                        { 0L },
                        0L,
                        onAttestationPending = cached.onSabrAttestationPending,
                        requestRetryDelay = {},
                    ).toList()
                }
                WebPoTokenHealth.reset()
                assertEquals("WEB_REMIX_SABR__nopo", resolver.resolve("video").profileId)
                assertTrue("WEB_SABR__po" in extractor.lastExcludedClients.orEmpty())
            } finally {
                resolver.close()
                client.close()
                sabrClient.close()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun slowAutomaticDirectAudioFallsBackToSabrBeforeTheQueueDeadline() =
        runTest {
            val attempts = mutableListOf<ContentHints>()
            var directCancelled = false
            val extractor =
                RecordingStreamExtractor(onExtract = { hints ->
                    attempts += hints
                    try {
                        delay(if (hints.sabrFirst) 1_000L else 30_000L)
                    } catch (cancelled: CancellationException) {
                        if (!hints.sabrFirst) directCancelled = true
                        throw cancelled
                    }
                })
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkStateFlow = null,
                    resolveDispatcher = StandardTestDispatcher(testScheduler),
                )
            try {
                val hints = ContentHints(isExplicit = true, endpointParams = "params")
                val result = withTimeout(30_000L) { resolver.resolve("video", hints) }
                assertEquals("video", result.videoId)
                assertEquals(11_000L, testScheduler.currentTime)
                assertEquals(listOf(hints, hints.copy(sabrFirst = true)), attempts)
                assertTrue(directCancelled)
            } finally {
                resolver.close()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pendingDirectResolutionSurvivesAnUnusableSabrFallback() =
        runTest {
            for (sabrFailure in listOf(null, StreamResolveException.Reason.NO_PLAYABLE_STREAM, StreamResolveException.Reason.NETWORK)) {
                val startedAt = testScheduler.currentTime
                val attempts = mutableListOf<ContentHints>()
                val direct = RecordingStreamExtractor(listOf("WEB_REMIX" to "WEB_REMIX__po"), onExtract = { delay(15_000L) })
                val extractor =
                    object : StreamExtractor by direct {
                        override suspend fun extract(
                            videoId: String,
                            hints: ContentHints,
                            excludedClients: Set<String>,
                            audioQuality: AudioQuality,
                            clientPlaybackNonce: String,
                        ): ExtractedStream? {
                            attempts += hints
                            return if (hints.sabrFirst) {
                                sabrFailure?.let { throw StreamResolveException(it, "SABR unavailable") }
                                null
                            } else {
                                direct.extract(
                                    videoId,
                                    hints,
                                    excludedClients,
                                    audioQuality,
                                    clientPlaybackNonce,
                                )
                            }
                        }
                    }
                val resolver =
                    DefaultStreamResolver(
                        extractor,
                        InnerTube(HttpClient(MockEngine { respondOk() })),
                        networkStateFlow = null,
                        resolveDispatcher = StandardTestDispatcher(testScheduler),
                    )
                try {
                    val result = withTimeout(30_000L) { resolver.resolve("video") }
                    assertEquals("WEB_REMIX__po", result.profileId)
                    assertEquals(startedAt + 15_000L, testScheduler.currentTime)
                    assertEquals(listOf(false, true), attempts.map { it.sabrFirst })
                } finally {
                    resolver.close()
                }
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun directResultCancelsThePendingSabrAttempt() =
        runTest {
            var sabrCancelled = false
            val extractor =
                RecordingStreamExtractor(onExtract = { hints ->
                    try {
                        delay(if (hints.sabrFirst) 30_000L else 15_000L)
                    } catch (cancelled: CancellationException) {
                        if (hints.sabrFirst) sabrCancelled = true
                        throw cancelled
                    }
                })
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkStateFlow = null,
                    resolveDispatcher = StandardTestDispatcher(testScheduler),
                )
            try {
                assertEquals("video", withTimeout(30_000L) { resolver.resolve("video") }.videoId)
                assertEquals(15_000L, testScheduler.currentTime)
                assertTrue(sabrCancelled)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun expiryReportsInvalidateTheUrlWithoutExcludingOrCoolingTheOnlyProfile() =
        runBlocking {
            var extractions = 0
            val health = ClientHealthTracker()
            val hints = ContentHints(playbackClientOverrideId = "WEB_REMIX")
            val extractor = RecordingStreamExtractor(listOf("WEB_REMIX" to "WEB_REMIX__po"), onExtract = { extractions++ })
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    clientHealthTracker = health,
                    networkStateFlow = null,
                )
            try {
                val original = resolver.resolve("video", hints)
                for (status in listOf(404, 410)) {
                    resolver.reportPlaybackFailure("video", original.clientName, status, original.profileId, hints)
                    assertEquals(original.profileId, resolver.resolve("video", hints).profileId)
                    assertTrue(extractor.lastExcludedClients.orEmpty().isEmpty())
                }
                assertEquals(3, extractions)
                assertTrue(health.clientsInCooldown(ClientHealthScope.from(hints, authenticated = false)).isEmpty())
            } finally {
                resolver.close()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun sabrCooldownLeavesThePendingDirectAttemptAliveAndFailsOnlyWithoutAResult() =
        runTest {
            for (directSucceeds in listOf(true, false)) {
                val startedAt = testScheduler.currentTime
                var requests = 0
                val client =
                    HttpClient(MockEngine) {
                        engine {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler {
                                requests++
                                respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "60"))
                            }
                        }
                        install(ExtractionHttpCooldown)
                    }
                val profiles = if (directSucceeds) listOf("WEB_REMIX" to "WEB_REMIX__po") else emptyList()
                val extractor =
                    RecordingStreamExtractor(profiles, onExtract = { hints ->
                        if (hints.sabrFirst) {
                            delay(1_000L)
                            throw assertFailsWith<ExtractionDeferred> {
                                client.get("https://youtubei.googleapis.com/youtubei/v1/player")
                            }
                        } else {
                            delay(15_000L)
                        }
                    })
                val resolver =
                    DefaultStreamResolver(
                        extractor,
                        InnerTube(client),
                        networkStateFlow = null,
                        elapsedMs = { testScheduler.currentTime },
                        resolveDispatcher = StandardTestDispatcher(testScheduler),
                    )
                try {
                    if (directSucceeds) {
                        assertEquals("WEB_REMIX__po", resolver.resolve("video").profileId)
                    } else {
                        val failure = assertFailsWith<StreamResolveException> { resolver.resolve("video") }
                        assertEquals(StreamResolveException.Reason.NETWORK, failure.reason)
                    }
                    assertEquals(startedAt + 15_000L, testScheduler.currentTime)
                    assertEquals(1, requests)
                    assertFailsWith<StreamResolveException> { resolver.resolve("next") }
                    assertEquals(1, requests)
                } finally {
                    resolver.close()
                    client.close()
                }
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun callerCancellationStillCancelsDirectAfterTheSabrCooldownSignal() =
        runTest {
            var directCancelled = false
            val client =
                HttpClient(MockEngine) {
                    engine {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { respond("", HttpStatusCode.TooManyRequests) }
                    }
                    install(ExtractionHttpCooldown)
                }
            val extractor =
                RecordingStreamExtractor(onExtract = { hints ->
                    if (hints.sabrFirst) {
                        delay(1_000L)
                        throw assertFailsWith<ExtractionDeferred> {
                            client.get("https://youtubei.googleapis.com/youtubei/v1/player")
                        }
                    } else {
                        try {
                            delay(30_000L)
                        } catch (cancelled: CancellationException) {
                            directCancelled = true
                            throw cancelled
                        }
                    }
                })
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(client),
                    networkStateFlow = null,
                    resolveDispatcher = StandardTestDispatcher(testScheduler),
                )
            try {
                val resolution = async { resolver.resolve("video") }
                advanceTimeBy(12_000L)
                runCurrent()
                assertFalse(resolution.isCompleted)
                resolution.cancelAndJoin()
                runCurrent()
                assertTrue(resolution.isCancelled)
                assertTrue(directCancelled)
            } finally {
                resolver.close()
                client.close()
            }
        }

    @Test
    fun explicitClientAndVideoResolutionKeepTheirExistingWait() =
        runTest {
            for (hints in listOf(ContentHints(playbackClientOverrideId = "WEB_REMIX"), ContentHints(wantVideo = true))) {
                val attempts = mutableListOf<ContentHints>()
                val extractor =
                    RecordingStreamExtractor(onExtract = {
                        attempts += it
                        delay(15_000L)
                    })
                val resolver =
                    DefaultStreamResolver(
                        extractor,
                        InnerTube(HttpClient(MockEngine { respondOk() })),
                        networkStateFlow = null,
                        resolveDispatcher = StandardTestDispatcher(testScheduler),
                    )
                try {
                    resolver.resolve("video", hints)
                    assertEquals(listOf(hints), attempts)
                } finally {
                    resolver.close()
                }
            }
        }

    @Test
    fun refreshNeverJoinsOldNormalWorkAndOldResultCannotReplaceRefreshCache() =
        runBlocking {
            val oldStarted = CompletableDeferred<Unit>()
            val oldRelease = CompletableDeferred<Unit>()
            val refreshStarted = CompletableDeferred<Unit>()
            val refreshRelease = CompletableDeferred<Unit>()
            var started = 0
            val extractor =
                FakeStreamExtractor(onExtract = {
                    if (++started == 1) {
                        oldStarted.complete(Unit)
                        oldRelease.await()
                    } else {
                        refreshStarted.complete(Unit)
                        refreshRelease.await()
                    }
                })
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            try {
                val old = async { resolver.resolve("video") }
                oldStarted.await()
                val refreshed = async { resolver.refresh("video") }
                refreshStarted.await()
                val joined = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve("video") }
                refreshRelease.complete(Unit)
                assertEquals("https://example.test/video/AUTO/2", refreshed.await().url)
                assertEquals(refreshed.await().url, joined.await().url)
                oldRelease.complete(Unit)
                assertEquals("https://example.test/video/AUTO/1", old.await().url)
                assertEquals(refreshed.await().url, resolver.resolve("video").url)
                assertEquals(2, extractor.calls)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun ordinaryResolveJoinsRefreshAndSurvivesRefreshCallerCancellation() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val extractor = FakeStreamExtractor(started = started, release = release)
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            try {
                val refresh = async { resolver.refresh("video") }
                started.await()
                val ordinary = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve("video") }
                refresh.cancelAndJoin()
                release.complete(Unit)
                assertEquals("video", withTimeout(2_000L) { ordinary.await() }.videoId)
                assertEquals(1, extractor.calls)
                resolver.resolve("video")
                assertEquals(1, extractor.calls)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun localAudioWinsOverResolvedUrlsAndDownloadsStillResolveNetworkResources() =
        runBlocking {
            val extractor = FakeStreamExtractor()
            var local: PlaybackResource? = null
            var connected = true
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkStateFlow = null,
                    localAudioSource = LocalAudioSource { _, _ -> local },
                    networkState = { PlaybackNetworkState("test", connected) },
                )
            try {
                resolver.resolve("video")
                val calls = extractor.calls
                local = PlaybackResource("video", "file:/download", emptyMap(), null, null)
                assertEquals(local, resolver.resolve("video"))
                assertEquals(local, resolver.refresh("video"))
                connected = false
                assertEquals(local, resolver.resolve("video", ContentHints(wantVideo = true)))
                connected = true
                assertEquals(calls, extractor.calls)
                assertNotEquals(local, resolver.resolveRemote("video", ContentHints(), AudioQuality.AUTO, refresh = true))
                assertEquals(calls + 1, extractor.calls)
                local = null
                assertNotEquals("file:/download", resolver.resolve("video").url)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun skipsCoolingClientsAcrossTracksButHonorsManualOverridesAndCooldownExpiry() =
        runBlocking {
            var now = 1_000L
            val health = ClientHealthTracker(nowMs = { now }, persistenceEnabled = false)
            val hints = ContentHints()
            val scope = ClientHealthScope.from(hints, authenticated = false)
            repeat(2) { health.recordFailure("TVHTML5_SIMPLY", ClientFailureKind.PLAYABILITY, scope) }
            val extractor = RecordingStreamExtractor()
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), clientHealthTracker = health)
            try {
                resolver.resolve("first", hints)
                assertEquals(cutoffProfiles + "TVHTML5_SIMPLY", extractor.lastExcludedClients)
                resolver.resolve("second", hints)
                assertEquals(cutoffProfiles + "TVHTML5_SIMPLY", extractor.lastExcludedClients)
                resolver.resolve("manual", hints.copy(playbackClientOverrideId = "TVHTML5_SIMPLY"))
                assertEquals(emptySet(), extractor.lastExcludedClients)
                now += 120_000L
                resolver.resolve("later", hints)
                assertEquals(cutoffProfiles, extractor.lastExcludedClients)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun manualSamsungOverrideReachesTheExtractorAndKeepsItsSelectedResource() =
        runBlocking {
            val profileId = "TVHTML5_SAMSUNG__nopo"
            val extractor = RecordingStreamExtractor(profiles = listOf("TVHTML5" to profileId))
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            try {
                val hints = ContentHints(playbackClientOverrideId = "TVHTML5_SAMSUNG")
                val resource = resolver.resolve("manual", hints)
                assertEquals(hints, extractor.lastHints)
                assertEquals(emptySet(), extractor.lastExcludedClients)
                assertEquals(profileId, resource.profileId)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun coolingClientsRemainAvailableWhenHealthyClientsCannotPlayTheTrack() =
        runBlocking {
            val health = ClientHealthTracker(nowMs = { 1_000L }, persistenceEnabled = false)
            val scope = ClientHealthScope.from(ContentHints(), authenticated = false)
            repeat(2) { health.recordFailure("TVHTML5_SIMPLY", ClientFailureKind.PLAYABILITY, scope) }
            val extractor = FakeStreamExtractor(returnNullWhenExcluded = true, playableClient = "TVHTML5_SIMPLY")
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), clientHealthTracker = health)
            try {
                assertEquals("video", resolver.resolve("video").videoId)
                assertEquals(2, extractor.calls)
                assertTrue("ANDROID_VR_1_65_10__nopo" in extractor.exclusions.last())
                assertTrue("ANDROID_VR_1_65_10__po" in extractor.exclusions.last())
                assertFalse("TVHTML5_SIMPLY__po" in extractor.exclusions.last())
                assertFalse("TVHTML5_SIMPLY" in extractor.exclusions.last())
                assertTrue(extractor.exclusions.all { it.containsAll(cutoffProfiles) })
            } finally {
                resolver.close()
            }
        }

    @Test
    fun cooldownFallbackHandlesPlayabilityErrorsWithoutRetryingNetworkFailures() =
        runBlocking {
            val health = ClientHealthTracker(nowMs = { 1_000L }, persistenceEnabled = false)
            val scope = ClientHealthScope.from(ContentHints(), authenticated = false)
            repeat(2) { health.recordFailure("TVHTML5_SIMPLY", ClientFailureKind.PLAYABILITY, scope) }
            for (reason in listOf(StreamResolveException.Reason.NO_PLAYABLE_STREAM, StreamResolveException.Reason.NETWORK)) {
                val extractor =
                    FakeStreamExtractor(
                        excludedFailure = StreamResolveException(reason, "Failed selection"),
                        playableClient = "TVHTML5_SIMPLY",
                    )
                val resolver =
                    DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), clientHealthTracker = health)
                try {
                    if (reason == StreamResolveException.Reason.NETWORK) {
                        assertFailsWith<StreamResolveException> { resolver.resolve("video") }
                        assertEquals(1, extractor.calls)
                    } else {
                        assertEquals("video", resolver.resolve("video").videoId)
                        assertEquals(2, extractor.calls)
                    }
                } finally {
                    resolver.close()
                }
            }
        }

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        PlaybackNetworkStateStore.publish(PlaybackNetworkState(scope = "", connected = true))
        PlaybackEventLog.clear()
        WebPoTokenHealth.reset()
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
        PlaybackNetworkStateStore.publish(previousNetwork)
        PlaybackEventLog.clear()
        WebPoTokenHealth.reset()
    }

    @Test
    fun cacheSeparatesQualitySessionAndPlaybackFailures() =
        runBlocking {
            val innerTube = InnerTube(HttpClient(MockEngine { respondOk() }))
            val extractor = FakeStreamExtractor()
            val resolver = DefaultStreamResolver(extractor, innerTube, networkStateFlow = null)

            val low = resolver.resolve("video", audioQuality = AudioQuality.LOW)
            val cachedLow = resolver.resolve("video", audioQuality = AudioQuality.LOW)
            val high = resolver.resolve("video", audioQuality = AudioQuality.HIGH)
            assertEquals(low.url, cachedLow.url)
            assertEquals(
                "FakeAgent/1.0",
                low.streamDiagnostics
                    ?.attempts
                    ?.single()
                    ?.userAgent,
            )
            assertNotEquals(low.url, high.url)
            assertEquals(2, extractor.calls)

            innerTube.cookie = "SAPISID=new-session"
            resolver.resolve("video", audioQuality = AudioQuality.LOW)
            assertEquals(3, extractor.calls)

            resolver.reportPlaybackFailure("video", "client", 403, "profile")
            resolver.resolve("video", audioQuality = AudioQuality.LOW)
            assertEquals(4, extractor.calls)
            resolver.close()
        }

    @Test
    fun concurrentIdenticalRequestsShareOneExtraction() =
        runBlocking {
            val innerTube = InnerTube(HttpClient(MockEngine { respondOk() }))
            val release = CompletableDeferred<Unit>()
            val extractor = FakeStreamExtractor(release = release)
            val resolver = DefaultStreamResolver(extractor, innerTube, networkStateFlow = null)

            val callers = List(8) { async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve("video") } }
            release.complete(Unit)
            val resources = callers.awaitAll()

            assertEquals(1, extractor.calls)
            assertEquals(1, resources.map { it.url }.distinct().size)
            resolver.close()
        }

    @Test
    fun stalledExtractionIsBounded() =
        runBlocking {
            val resolver =
                DefaultStreamResolver(
                    extractor = FakeStreamExtractor(delayMs = 250L),
                    innerTube = InnerTube(HttpClient(MockEngine { respondOk() })),
                    resolveTimeoutMs = 25L,
                    networkStateFlow = null,
                )

            val error = assertFailsWith<StreamResolveException> { resolver.resolve("video") }

            assertEquals(StreamResolveException.Reason.NETWORK, error.reason)
            resolver.close()
        }

    @Test
    fun failedProfilesRemainExcludedForTheNextResolution() =
        runBlocking {
            val extractor = FakeStreamExtractor(returnNullWhenExcluded = true)
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            resolver.reportPlaybackFailure("video", "client", 403, "profile")

            assertFailsWith<StreamResolveException> { resolver.resolve("video") }

            assertEquals(2, extractor.calls)
            assertTrue(extractor.exclusions.all { "profile" in it && "client" in it })
            resolver.close()
        }

    @Test
    fun automaticResolutionAndRecoveryPreferFullLengthStreams() =
        runBlocking {
            val extractor =
                RecordingStreamExtractor(
                    listOf(
                        "VISIONOS" to "VISIONOS_0_1__nopo",
                        "VISIONOS" to "VISIONOS_SABR__nopo",
                        "WEB_REMIX" to "WEB_REMIX__po",
                        "WEB" to "WEB_SABR__po",
                    ),
                )
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            try {
                for (override in listOf(null, "SABR_FIRST")) {
                    val hints = ContentHints(playbackClientOverrideId = override)
                    val videoId = "video-$override"
                    val initial = resolver.resolve(videoId, hints)
                    assertEquals("WEB_REMIX__po", initial.profileId)
                    resolver.reportPlaybackFailure(videoId, initial.clientName, 403, initial.profileId, hints)
                    WebPoTokenHealth.reset() // this test covers exclusions, not the tokenless preference
                    val replacement = resolver.refresh(videoId, hints)
                    assertEquals("WEB_SABR__po", replacement.profileId)
                    assertTrue(extractor.lastExcludedClients.orEmpty().containsAll(cutoffProfiles))
                }
                val probe = resolver.resolve("probe", ContentHints(playbackClientOverrideId = "VISIONOS_0_1"))
                assertEquals("VISIONOS_0_1__nopo", probe.profileId)
                assertEquals(emptySet(), extractor.lastExcludedClients)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun rejectedWebTokensTryTokenlessVisionFirst() =
        runBlocking {
            val overrides = mutableListOf<String?>()
            val extractor =
                RecordingStreamExtractor(
                    listOf("WEB_REMIX" to "WEB_REMIX__po", "VISIONOS" to "VISIONOS_0_1__nopo"),
                    onExtract = { overrides += it.playbackClientOverrideId },
                )
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            try {
                val initial = resolver.resolve("song", ContentHints())
                assertEquals(null, overrides.first())
                resolver.reportPlaybackFailure("song", initial.clientName, 403, initial.profileId, ContentHints())
                assertTrue(WebPoTokenHealth.tokensRejected())
                overrides.clear()
                resolver.resolve("next", ContentHints())
                assertEquals(WebPoTokenHealth.TOKENLESS_AUDIO_CLIENT, overrides.first())
            } finally {
                resolver.close()
            }
        }

    @Test
    fun failedDefaultSabrProfileFallsBackToDirectClient() =
        runBlocking {
            val extractor =
                RecordingStreamExtractor(
                    listOf(
                        "WEB" to "WEB_SABR__po",
                        "WEB_REMIX" to "WEB_REMIX__po",
                    ),
                )
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            try {
                val hints = ContentHints(sabrFirst = true)
                val first = resolver.resolve("video", hints)
                assertEquals("WEB_SABR__po", first.profileId)

                resolver.reportPlaybackFailure(
                    videoId = "video",
                    clientName = first.clientName,
                    responseCode = null,
                    profileId = first.profileId,
                    hints = hints,
                    category = PlaybackFailureCategory.SABR_PROTOCOL,
                )

                val fallback = resolver.resolveRemote("video", hints, AudioQuality.HIGH, refresh = true)
                assertEquals("WEB_REMIX__po", fallback.profileId)
                assertTrue(extractor.lastExcludedClients.orEmpty().contains("WEB_SABR__po"))
            } finally {
                resolver.close()
            }
        }

    @Test
    fun failedAutomaticDirectProfileKeepsSabrFallbackAndExplicitContentHints() =
        runBlocking {
            val extractor =
                RecordingStreamExtractor(
                    listOf(
                        "WEB_REMIX" to "WEB_REMIX__po",
                        "WEB" to "WEB_SABR__po",
                    ),
                )
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            try {
                val song = SongItem(id = "video", title = "Song", thumbnail = null, artists = emptyList(), album = null, isExplicit = true)
                val hints = song.toContentHints(wantVideo = false)
                assertFalse(hints.sabrFirst)
                val first = resolver.resolve(song.id, hints)
                assertEquals("WEB_REMIX__po", first.profileId)
                assertTrue(extractor.lastExcludedClients.orEmpty().containsAll(cutoffProfiles))

                resolver.reportPlaybackFailure(song.id, first.clientName, 403, first.profileId, hints)
                WebPoTokenHealth.reset() // this test covers exclusions, not the tokenless preference

                val fallback = resolver.resolveRemote(song.id, hints, AudioQuality.AUTO, refresh = true)
                assertEquals("WEB_SABR__po", fallback.profileId)
                assertEquals(hints, extractor.lastHints)
                assertTrue(extractor.lastHints!!.allowSabr)
                assertTrue(extractor.lastExcludedClients.orEmpty().contains("WEB_REMIX__po"))
                assertTrue(extractor.lastExcludedClients.orEmpty().containsAll(cutoffProfiles))
            } finally {
                resolver.close()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun lastResortVerdictKeepsWhatThePreferredClientsReported() =
        runTest {
            fun failure(
                reason: StreamResolveException.Reason,
                client: String,
            ) = StreamResolveException(
                reason,
                reason.name,
                diagnostics = StreamDiagnostics(listOf(StreamAttemptDiagnostic(client, null, "ua", "playability:UNPLAYABLE")), false),
            )

            suspend fun resolveFailure(preferred: suspend () -> Nothing): StreamResolveException {
                val extractor =
                    object : StreamExtractor {
                        override suspend fun prewarm() = Unit

                        override suspend fun extract(
                            videoId: String,
                            hints: ContentHints,
                            excludedClients: Set<String>,
                            audioQuality: AudioQuality,
                            clientPlaybackNonce: String,
                        ): ExtractedStream? {
                            if ("VISIONOS_SABR__nopo" in excludedClients) preferred()
                            throw failure(StreamResolveException.Reason.UNAVAILABLE, "VISIONOS")
                        }
                    }
                val resolver =
                    DefaultStreamResolver(
                        extractor,
                        InnerTube(HttpClient(MockEngine { respondOk() })),
                        networkStateFlow = null,
                        resolveDispatcher = StandardTestDispatcher(testScheduler),
                    )
                try {
                    return assertFailsWith<StreamResolveException> {
                        resolver.resolve(
                            "video",
                            ContentHints(playbackClientOverrideId = "SABR_FIRST"),
                        )
                    }
                } finally {
                    resolver.close()
                }
            }

            val failed = resolveFailure { throw failure(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "WEB_REMIX") }
            assertEquals(StreamResolveException.Reason.NO_PLAYABLE_STREAM, failed.reason)
            assertEquals(listOf("WEB_REMIX", "VISIONOS"), failed.diagnostics?.attempts?.map { it.clientName })

            val timedOut = resolveFailure { awaitCancellation() }
            assertEquals(StreamResolveException.Reason.NO_PLAYABLE_STREAM, timedOut.reason)
            assertEquals(
                listOf("preferred_clients_timeout", "playability:UNPLAYABLE"),
                timedOut.diagnostics?.attempts?.map { it.outcome },
            )
        }

    @Test
    fun tokenlessVisionRemainsTheLastResortIncludingSabrFirstAndRefresh() =
        runBlocking {
            for (profile in cutoffProfiles) {
                for (override in listOf(null, "SABR_FIRST")) {
                    val extractor = RecordingStreamExtractor(listOf("VISIONOS" to profile))
                    val resolver =
                        DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
                    try {
                        val hints = ContentHints(playbackClientOverrideId = override)
                        assertEquals(profile, resolver.resolve("video", hints).profileId)
                        assertEquals(profile, resolver.refresh("video", hints).profileId)
                        assertFalse(profile in extractor.lastExcludedClients.orEmpty())
                        assertTrue("WEB_REMIX__po" in extractor.lastExcludedClients.orEmpty())
                    } finally {
                        resolver.close()
                    }
                }
            }
        }

    @Test
    fun coolingFullLengthClientPrecedesTokenlessVision() =
        runBlocking {
            val health = ClientHealthTracker(nowMs = { 1_000L }, persistenceEnabled = false)
            val scope = ClientHealthScope.from(ContentHints(), authenticated = false)
            repeat(2) { health.recordFailure("TVHTML5_SIMPLY", ClientFailureKind.PLAYABILITY, scope) }
            val extractor = RecordingStreamExtractor(listOf("VISIONOS" to "VISIONOS_0_1__nopo", "TVHTML5_SIMPLY" to "TVHTML5_SIMPLY__po"))
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), clientHealthTracker = health)
            try {
                assertEquals("TVHTML5_SIMPLY__po", resolver.resolve("video").profileId)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun renewalExclusionsDoNotDisableTokenlessFallbackOrPolluteNormalCache() =
        runBlocking {
            val extractor = RecordingStreamExtractor(listOf("ANDROID_VR" to "ANDROID_VR_1_65_10__nopo", "WEB_REMIX" to "WEB_REMIX__po"))
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            try {
                val first = resolver.resolve("video")
                val replacement =
                    resolver.resolveRemote(
                        "video",
                        ContentHints(),
                        AudioQuality.AUTO,
                        refresh = true,
                        excludedProfiles = setOf(first.profileId),
                    )
                assertEquals("WEB_REMIX__po", replacement.profileId)
                assertTrue(first.profileId in extractor.lastExcludedClients.orEmpty())
                assertEquals(first.profileId, resolver.refresh("video").profileId)
                assertFalse(first.profileId in extractor.lastExcludedClients.orEmpty())
            } finally {
                resolver.close()
            }
        }

    @Test
    fun nopoProfile403PreservesAttestationForTheTokenizedFallback() =
        runBlocking {
            val extractor =
                RecordingStreamExtractor(
                    listOf(
                        "ANDROID_VR" to "ANDROID_VR_1_65_10__nopo",
                        "ANDROID_VR" to "ANDROID_VR_1_65_10__po",
                    ),
                )
            val tokenProvider = RecordingTokenProvider()
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    tokenProvider = tokenProvider,
                    networkStateFlow = null,
                )
            try {
                val initial = resolver.resolve("video")
                resolver.reportPlaybackFailure("video", initial.clientName, 403, initial.profileId)
                val fallback = resolver.refresh("video")

                assertEquals(cutoffProfiles + "ANDROID_VR_1_65_10__nopo", extractor.lastExcludedClients)
                assertEquals("ANDROID_VR_1_65_10__po", fallback.profileId)
                assertEquals(0, tokenProvider.invalidations)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun tokenizedProfile403KeepsSiblingSabrTransportAndRotatesAttestation() =
        runBlocking {
            val extractor =
                RecordingStreamExtractor(listOf("WEB_REMIX" to "WEB_REMIX__po", "WEB_REMIX" to "WEB_REMIX_SABR__po"))
            val tokenProvider = RecordingTokenProvider()
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    tokenProvider = tokenProvider,
                    networkStateFlow = null,
                )
            try {
                resolver.reportPlaybackFailure("video", "WEB_REMIX", 403, "WEB_REMIX__po")
                WebPoTokenHealth.reset() // this test covers exclusions, not the tokenless preference
                assertEquals("WEB_REMIX_SABR__po", resolver.resolve("video").profileId)

                assertEquals(cutoffProfiles + "WEB_REMIX__po", extractor.lastExcludedClients)
                assertEquals(1, tokenProvider.invalidations)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun sabrAttestationExclusionsAdvanceFromUntokenizedToTokenizedThenToNextManifest() =
        runBlocking {
            val extractor =
                RecordingStreamExtractor(
                    listOf(
                        "WEB_REMIX" to "WEB_REMIX_SABR__nopo",
                        "WEB_REMIX" to "WEB_REMIX_SABR__po",
                        "ANDROID_VR" to "ANDROID_VR_SABR__po",
                    ),
                )
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)

            val untokenized = resolver.resolve("video")
            assertEquals("WEB_REMIX_SABR__nopo", untokenized.profileId)
            resolver.reportPlaybackFailure(
                videoId = "video",
                clientName = untokenized.clientName,
                responseCode = null,
                profileId = untokenized.profileId,
                category = PlaybackFailureCategory.SABR_ATTESTATION,
            )
            WebPoTokenHealth.reset() // this test covers exclusions, not the tokenless preference

            val tokenized = resolver.resolve("video")
            assertEquals("WEB_REMIX_SABR__po", tokenized.profileId)
            resolver.reportPlaybackFailure(
                videoId = "video",
                clientName = tokenized.clientName,
                responseCode = null,
                profileId = tokenized.profileId,
                category = PlaybackFailureCategory.SABR_ATTESTATION,
            )
            WebPoTokenHealth.reset() // this test covers exclusions, not the tokenless preference

            val fallback = resolver.resolve("video")
            assertEquals("ANDROID_VR_SABR__po", fallback.profileId)
            assertEquals(
                cutoffProfiles + setOf("WEB_REMIX_SABR__nopo", "WEB_REMIX_SABR__po"),
                extractor.lastExcludedClients,
            )
            resolver.close()
        }

    @Test
    fun resourceCacheIsBoundedAndRetainsRecentlyUsedEntries() =
        runBlocking {
            val innerTube = InnerTube(HttpClient(MockEngine { respondOk() }))
            val extractor = FakeStreamExtractor()
            val resolver = DefaultStreamResolver(extractor, innerTube, networkStateFlow = null)

            repeat(64) { resolver.resolve("video-$it") }
            resolver.resolve("video-0")
            resolver.resolve("video-64")
            resolver.resolve("video-0")
            resolver.resolve("video-1")

            assertEquals(66, extractor.calls)
            resolver.close()
        }

    @Test
    fun visitorDataInitializationDoesNotDiscardResolvedResource() =
        runBlocking {
            val innerTube = InnerTube(HttpClient(MockEngine { respondOk() }))
            val extractor = FakeStreamExtractor(onExtract = { innerTube.visitorData = "fresh-visitor" })
            val resolver = DefaultStreamResolver(extractor, innerTube, networkStateFlow = null)

            resolver.resolve("video")
            resolver.resolve("video")

            assertEquals(1, extractor.calls)
            resolver.close()
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rapidSkipsKeepTheSelectedRequestAliveAfterALatePreloadFailure() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val extractor = FakeStreamExtractor(delayMs = 1_000L)
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkStateFlow = null,
                    resolveDispatcher = dispatcher,
                )
            val requests = MutableSharedFlow<String>(extraBufferCapacity = 1)
            val played = mutableListOf<String>()
            val collector =
                backgroundScope.launch(dispatcher) {
                    requests.collectLatest { played += resolver.resolve(it).videoId }
                }
            try {
                runCurrent()
                requests.emit("first")
                runCurrent()
                advanceTimeBy(100L)
                requests.emit("second")
                runCurrent()
                advanceTimeBy(100L)
                requests.emit("first")
                runCurrent()
                assertEquals(3, extractor.calls)

                resolver.reportPlaybackFailure("first", "WEB", null, "stale-preload-profile")
                runCurrent()
                advanceTimeBy(1_000L)
                runCurrent()

                assertEquals(listOf("first"), played)
                assertEquals(4, extractor.calls)
                assertTrue("stale-preload-profile" in extractor.exclusions.last())
                assertTrue(collector.isActive)
            } finally {
                collector.cancelAndJoin()
                resolver.close()
            }
        }

    @Test
    fun cancellingLastCallerCancelsExtractionAndAllowsNewResolution() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val extractor = FakeStreamExtractor(started = started, release = release, cancelled = cancelled)
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkStateFlow = null,
                )
            try {
                val caller = async { resolver.resolve("video") }
                started.await()

                caller.cancelAndJoin()
                val nextCaller = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve("video") }
                withTimeout(2_000L.milliseconds) { cancelled.await() }
                release.complete(Unit)

                assertEquals("https://example.test/video/AUTO/2", withTimeout(2_000L.milliseconds) { nextCaller.await() }.url)
                assertEquals(2, extractor.calls)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun cancellingFirstCallerDoesNotCancelJoinedCallers() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val extractor = FakeStreamExtractor(started = started, release = release)
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkStateFlow = null,
                )

            try {
                val firstCaller = async { resolver.resolve("video") }
                started.await()
                val joinedCaller = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve("video") }
                firstCaller.cancelAndJoin()
                release.complete(Unit)

                assertEquals("https://example.test/video/AUTO/1", withTimeout(2_000L.milliseconds) { joinedCaller.await() }.url)
                assertEquals(1, extractor.calls)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun closingResolverCancelsFlightsAndRejectsNewResolution() {
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val resolver =
                DefaultStreamResolver(
                    FakeStreamExtractor(started = started, release = release),
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkStateFlow = null,
                )
            val caller = async { resolver.resolve("video") }
            started.await()

            resolver.close()

            assertFailsWith<CancellationException> { caller.await() }
            assertFailsWith<IllegalStateException> { resolver.resolve("other-video") }
        }
    }

    @Test
    fun networkChangeRestartsExtractionWithoutCancellingTheCaller() =
        runBlocking {
            var network = PlaybackNetworkState(scope = "old-network")
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val extractor =
                FakeStreamExtractor(
                    started = started,
                    release = release,
                    cancelled = cancelled,
                )
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkState = { network },
                    networkStateFlow = null,
                )
            try {
                val caller = async { resolver.resolve("video") }
                started.await()

                network = PlaybackNetworkState(scope = "new-network")
                resolver.invalidateNetworkScope(network)

                withTimeout(2_000L.milliseconds) { cancelled.await() }
                release.complete(Unit)
                assertEquals("https://example.test/video/AUTO/2", withTimeout(2_000L.milliseconds) { caller.await() }.url)
                assertEquals(2, extractor.calls)
            } finally {
                resolver.close()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun repeatedNetworkInvalidationEndsWithRetryableFailureWithinTheOriginalDeadline() =
        runTest {
            for (exhaustAttempts in listOf(false, true)) {
                var network = PlaybackNetworkState(scope = "initial")
                val extractor = FakeStreamExtractor(delayMs = 60_000L)
                val resolver =
                    DefaultStreamResolver(
                        extractor,
                        InnerTube(HttpClient(MockEngine { respondOk() })),
                        resolveTimeoutMs = 1_000L,
                        networkState = { network },
                        networkStateFlow = null,
                        resolveDispatcher = StandardTestDispatcher(testScheduler),
                    )
                try {
                    val caller = async { runCatching { resolver.resolve("video") } }
                    runCurrent()
                    repeat(if (exhaustAttempts) 3 else 2) { index ->
                        advanceTimeBy(300L)
                        network = PlaybackNetworkState(scope = "network-$index")
                        resolver.invalidateNetworkScope(network)
                        runCurrent()
                    }
                    advanceTimeBy(1_000L)
                    runCurrent()
                    val failure = caller.await().exceptionOrNull()
                    assertTrue(failure is StreamResolveException)
                    assertEquals(StreamResolveException.Reason.NETWORK, failure.reason)
                    assertEquals(3, extractor.calls)
                } finally {
                    resolver.close()
                }
            }
        }

    @Test
    fun closingResolverCancelsDetachedFlightWaiters() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val resolver =
                DefaultStreamResolver(
                    FakeStreamExtractor(started = started, release = CompletableDeferred()),
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkStateFlow = null,
                )
            val caller = async { resolver.resolve("video") }
            try {
                started.await()
                resolver.clearPlaybackFailures("video")
                resolver.close()

                withTimeout(2_000L.milliseconds) { caller.join() }
                assertFailsWith<CancellationException> { caller.await() }
                Unit
            } finally {
                caller.cancelAndJoin()
                resolver.close()
            }
        }

    @Test
    fun completedOldNetworkFlightCannotRepopulateCache() =
        runBlocking {
            var networkState = PlaybackNetworkState(scope = "network-a")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val extractor = FakeStreamExtractor(started = started, release = release, ignoreCancellation = true)
            val resolver =
                DefaultStreamResolver(
                    extractor = extractor,
                    innerTube = InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkState = { networkState },
                    networkStateFlow = null,
                )

            val oldCaller = async { resolver.resolve("video") }
            started.await()
            networkState = PlaybackNetworkState(scope = "network-b")
            resolver.invalidateNetworkScope(networkState)
            networkState = PlaybackNetworkState(scope = "network-a")
            resolver.invalidateNetworkScope(networkState)
            release.complete(Unit)
            assertEquals("https://example.test/video/AUTO/2", oldCaller.await().url)
            delay(25L.milliseconds)

            resolver.resolve("video")

            assertEquals(2, extractor.calls)
            resolver.close()
        }

    @Test
    fun networkStateFlowInvalidatesResolverCache() =
        runBlocking {
            val networkFlow = MutableStateFlow(PlaybackNetworkState(scope = "network-a"))
            val innerTube = InnerTube(HttpClient(MockEngine { respondOk() }))
            val extractor = FakeStreamExtractor()
            val resolver =
                DefaultStreamResolver(
                    extractor = extractor,
                    innerTube = innerTube,
                    networkState = { networkFlow.value },
                    networkStateFlow = networkFlow,
                )

            resolver.resolve("video")
            assertEquals(1, extractor.calls)

            resolver.resolve("video")
            assertEquals(1, extractor.calls)

            networkFlow.value = PlaybackNetworkState(scope = "network-b")
            delay(50L.milliseconds)

            resolver.resolve("video")
            assertEquals(2, extractor.calls)
            resolver.close()
        }

    @Test
    fun failureRestartsPendingResolutionWithoutReturningTheRejectedProfile() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val extractor =
                RecordingStreamExtractor(
                    profiles = listOf("client" to "profile", "alternative" to "alternative-profile"),
                    onExtract = {
                        if (++calls == 1) {
                            started.complete(Unit)
                            release.await()
                        }
                    },
                )
            val resolver = DefaultStreamResolver(extractor, InnerTube(HttpClient(MockEngine { respondOk() })), networkStateFlow = null)
            try {
                val pending = async { resolver.resolve("video") }
                started.await()
                resolver.reportPlaybackFailure("video", clientName = "client", responseCode = 403, profileId = "profile")
                release.complete(Unit)
                val resource = withTimeout(2_000L.milliseconds) { pending.await() }
                assertEquals("alternative-profile", resource.profileId)
                assertTrue("profile" in checkNotNull(extractor.lastExcludedClients))
                assertEquals(resource.url, resolver.resolve("video").url)
                assertEquals(2, calls)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun reportPlaybackFailureInvalidatesInFlightAndForcesFreshResolution() =
        runBlocking {
            val extractor = RecordingStreamExtractor(listOf("client" to "profile", "other" to "other-profile"))
            val resolver =
                DefaultStreamResolver(
                    extractor = extractor,
                    innerTube = InnerTube(HttpClient(MockEngine { respondOk() })),
                    networkStateFlow = null,
                )

            val first = resolver.resolve("video")
            assertEquals(cutoffProfiles, extractor.lastExcludedClients)

            resolver.reportPlaybackFailure(
                videoId = "video",
                clientName = first.clientName,
                responseCode = 403,
                profileId = first.profileId,
                category = PlaybackFailureCategory.CDN_FORBIDDEN,
            )

            resolver.resolve("video")
            assertEquals(
                cutoffProfiles + setOfNotNull(first.profileId, first.clientName),
                extractor.lastExcludedClients,
            )
            resolver.close()
        }

    @Test
    fun cdnForbiddenRotatesAttestationWhileOtherFailuresKeepIt() =
        runBlocking {
            val tokenProvider = RecordingTokenProvider()
            val resolver =
                DefaultStreamResolver(
                    extractor = RecordingStreamExtractor(),
                    innerTube = InnerTube(HttpClient(MockEngine { respondOk() })),
                    tokenProvider = tokenProvider,
                    networkStateFlow = null,
                )

            resolver.reportPlaybackFailure(
                videoId = "video",
                clientName = "client",
                responseCode = 403,
                profileId = "profile",
                category = PlaybackFailureCategory.CDN_FORBIDDEN,
            )
            assertEquals(1, tokenProvider.invalidations)

            resolver.reportPlaybackFailure(
                videoId = "video",
                clientName = "client",
                responseCode = 404,
                profileId = "profile",
                category = PlaybackFailureCategory.URL_EXPIRED,
            )
            assertEquals(1, tokenProvider.invalidations)
            resolver.close()
        }

    @Test
    fun uploadsKeepTheirIdentityAndAccountHintThroughVideoModeAndClientRetries() =
        runBlocking {
            val extractor = RecordingStreamExtractor(listOf("WEB_REMIX" to "first", "WEB" to "second"))
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })).apply { cookie = "SAPISID=test" },
                    musicVideoResolver = MusicVideoResolver(counterpartLookup = { error("Uploads must not look up a music video") }),
                    networkStateFlow = null,
                )
            val song = SongItem("upload", "Recording", null, emptyList(), null, isUploaded = true)
            try {
                val hints = ContentHints(wantVideo = true)
                val first = resolver.resolve(song.id, hints, sourceSong = song)
                assertEquals(song.id, extractor.lastVideoId)
                assertEquals(true, extractor.lastHints?.isUploaded)
                assertEquals(false, extractor.lastHints?.wantVideo)
                assertEquals(null, first.musicVideoId)
                resolver.reportPlaybackFailure(song.id, first.clientName, 403, first.profileId, hints = extractor.lastHints!!)
                val next = resolver.refresh(song.id, hints, sourceSong = song)
                assertEquals("second", next.profileId)
                assertEquals(song.id, extractor.lastVideoId)
                assertEquals(true, extractor.lastHints?.isUploaded)
                assertEquals(false, extractor.lastHints?.wantVideo)
                assertEquals(null, next.musicVideoId)
                resolver.resolveRemote("upload-without-metadata", ContentHints(isUploaded = true, wantVideo = true), AudioQuality.AUTO)
                assertEquals(true, extractor.lastHints?.isUploaded)
                assertEquals(false, extractor.lastHints?.wantVideo)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun videoResolutionUsesExplicitSourceSongForCounterpartLookup() =
        runBlocking {
            val extractor = RecordingStreamExtractor()
            val resolver =
                DefaultStreamResolver(
                    extractor = extractor,
                    innerTube = InnerTube(HttpClient(MockEngine { respondOk() })),
                    musicVideoResolver =
                        MusicVideoResolver(
                            counterpartLookup = {
                                MusicVideoCounterpart(
                                    sourceVideoId = it.id,
                                    targetVideoId = "official-video",
                                    targetMusicVideoType = "MUSIC_VIDEO_TYPE_OMV",
                                    positionMap = CounterpartPositionMap(listOf(CounterpartSegment(0, 4_492, 131_989))),
                                )
                            },
                        ),
                    networkStateFlow = null,
                )
            val song =
                SongItem(
                    id = "audio-track",
                    title = "Track",
                    thumbnail = null,
                    artists = emptyList(),
                    album = null,
                    musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                )

            val resource =
                resolver.resolve(
                    videoId = song.id,
                    hints = ContentHints(wantVideo = true),
                    sourceSong = song,
                )

            assertEquals("official-video", extractor.lastVideoId)
            assertEquals(song.id, resource.videoId)
            assertEquals("official-video", resource.musicVideoId)
            assertEquals(25_508L, resource.musicVideoPositionMap?.reversed()?.mapPosition(30_000L))
            resolver.close()
        }

    @Test
    fun searchMatchedCanvasVideoCannotReplaceSongAudioDuringResolutionOrRecovery() =
        runBlocking {
            val song =
                SongItem(
                    "album-audio",
                    "Track",
                    null,
                    listOf(ArtistTiny(null, "Artist")),
                    null,
                    duration = 160,
                    musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                )
            val video = song.copy(id = "search-video", duration = 173, musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
            var counterpartLookups = 0
            val counterparts =
                MusicVideoResolver(
                    search = { _, _ -> SearchResultPage(listOf(video), null) },
                    counterpartLookup = {
                        counterpartLookups++
                        null
                    },
                    albumLookup = { null },
                    songLookup = { null },
                    settings = Settings(MapSettings()),
                )
            val extractor = RecordingStreamExtractor(includeVideo = true)
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(HttpClient(MockEngine { respondOk() })),
                    musicVideoResolver = counterparts,
                    networkStateFlow = null,
                )
            try {
                val audioHints = ContentHints(wantVideo = false)
                val canvasHints =
                    ContentHints(wantVideo = true)
                        .withStreamCapabilities(allowHls = false, allowSabr = true, allowBoundedRange = true)
                val original = resolver.resolve(song.id, audioHints, sourceSong = song)
                val match = counterparts.resolveMusicVideo(song) as MusicVideoResult.Found
                assertEquals(video.id, match.videoId)
                assertEquals(CounterpartResolutionSource.HEURISTIC, match.source)
                val lookupsBeforeRecovery = counterpartLookups

                val canvas = resolver.resolve(video.id, canvasHints, sourceSong = video)
                assertEquals(true, canvas.hasVideo)
                val redirected = resolver.resolve(song.id, canvasHints, sourceSong = song)
                assertEquals(video.id, redirected.musicVideoId)
                assertEquals(true, redirected.hasVideo)
                assertEquals(original, resolver.resolve(song.id, audioHints, sourceSong = song))

                val refreshed = resolver.refresh(song.id, audioHints, sourceSong = song)
                val remote =
                    resolver.resolveRemote(
                        song.id,
                        audioHints,
                        AudioQuality.AUTO,
                        song,
                        refresh = true,
                        excludedProfiles = setOf("VISIONOS_SABR__nopo"),
                    )
                for (audio in listOf(original, refreshed, remote)) {
                    assertEquals(song.id, audio.videoId)
                    assertEquals(null, audio.musicVideoId)
                    assertEquals(false, audio.hasVideo)
                    assertEquals("https://example.test/${song.id}/AUTO/profile", audio.url)
                }
                assertEquals(song.id, extractor.lastVideoId)
                assertEquals(false, extractor.lastHints?.wantVideo)
                assertEquals(lookupsBeforeRecovery, counterpartLookups)
            } finally {
                resolver.close()
            }
        }

    @Test
    fun videoResolutionFallsBackToAudioWhenCounterpartNotFound() =
        runBlocking {
            val extractor = RecordingStreamExtractor()
            val resolver =
                DefaultStreamResolver(
                    extractor = extractor,
                    innerTube = InnerTube(HttpClient(MockEngine { respondOk() })),
                    musicVideoResolver =
                        MusicVideoResolver(
                            search = { _, _ -> SearchResultPage(emptyList(), null) },
                            counterpartLookup = { null },
                            albumLookup = { null },
                            songLookup = { null },
                        ),
                    networkStateFlow = null,
                )
            val song =
                SongItem(
                    id = "audio-track",
                    title = "Track",
                    thumbnail = null,
                    artists = emptyList(),
                    album = null,
                    musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
                )

            val resource =
                resolver.resolve(
                    videoId = song.id,
                    hints = ContentHints(wantVideo = true),
                    sourceSong = song,
                )

            assertEquals("audio-track", extractor.lastVideoId)
            assertEquals(false, extractor.lastHints?.wantVideo)
            assertEquals(song.id, resource.videoId)
            assertEquals(null, resource.musicVideoId)
            assertEquals(false, resource.hasVideo)
            resolver.close()
        }

    @Test
    fun liveHlsAutoUsesResolvedHeightInEveryResolutionPath() =
        runBlocking {
            assertLiveHlsSelection(maxVideoHeight = 720, expectedHeight = 360, expectedItag = 93)
            assertLiveHlsSelection(maxVideoHeight = 144, expectedHeight = 144, expectedItag = 91)
            assertLiveHlsSelection(maxVideoHeight = 1080, expectedHeight = 1080, expectedItag = 96)
        }

    @Test
    fun liveHlsExplicitQualityKeepsItsTargetHeight() =
        runBlocking {
            assertLiveHlsSelection(maxVideoHeight = 720, expectedHeight = 1080, expectedItag = 96, quality = MusicVideoQuality.P1080)
            assertLiveHlsSelection(maxVideoHeight = 1080, expectedHeight = 360, expectedItag = 93, quality = MusicVideoQuality.P480)
        }

    @Test
    fun liveHlsKeepsPlayableFallbackWhenNoRenditionFits() =
        runBlocking {
            assertLiveHlsSelection(maxVideoHeight = 100, expectedHeight = 144, expectedItag = 91)
            assertLiveHlsSelection(maxVideoHeight = null, expectedHeight = 1080, expectedItag = 96)
        }

    private suspend fun assertLiveHlsSelection(
        maxVideoHeight: Int?,
        expectedHeight: Int,
        expectedItag: Int,
        quality: MusicVideoQuality = MusicVideoQuality.AUTO,
    ) {
        Settings.shared.musicVideoQuality = quality
        for ((isLive, hasExtractedStream) in listOf(true to false, false to false, false to true)) {
            val client =
                HttpClient(
                    MockEngine {
                        respondOk("""{"playabilityStatus":{"status":"OK"},"streamingData":{"hlsManifestUrl":"$hlsMasterUrl"}}""")
                    },
                ) { install(ContentNegotiation) { json() } }
            val innerTube = InnerTube(client).apply { visitorData = "known-visitor" }
            val resolver =
                DefaultStreamResolver(
                    extractor =
                        RecordingStreamExtractor(
                            profiles = if (hasExtractedStream) listOf("client" to "profile") else emptyList(),
                            audioUrl = hlsMasterUrl,
                        ),
                    innerTube = innerTube,
                    networkStateFlow = null,
                    liveHttpClientFactory = {
                        HttpClient(
                            MockEngine { request ->
                                assertEquals(hlsMasterUrl, request.url.toString())
                                respondOk(hlsMasterPlaylist)
                            },
                        )
                    },
                )
            try {
                val resource =
                    resolver.resolve(
                        "live-video",
                        ContentHints(isLive = isLive, wantVideo = true, maxVideoHeight = maxVideoHeight),
                    )

                assertEquals("ANDROID_LIVE", resource.profileId)
                assertEquals(expectedHeight, resource.videoHeight)
                assertEquals(
                    "https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/$expectedItag/playlist/index.m3u8",
                    resource.videoUrl,
                )
                assertEquals("https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/93/playlist/index.m3u8", resource.url)
            } finally {
                resolver.close()
                innerTube.close()
                client.close()
            }
        }
    }

    @Test
    fun parseHlsMasterPlaylistParsesVariantsCorrectly() {
        val variants = parseHlsMasterPlaylist(hlsMasterUrl, hlsMasterPlaylist)
        assertEquals(3, variants.size)

        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/91/playlist/index.m3u8", variants[0].url)
        assertEquals(256, variants[0].width)
        assertEquals(144, variants[0].height)
        assertEquals(269034L, variants[0].bandwidth)

        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/96/playlist/index.m3u8", variants[2].url)
        assertEquals(1920, variants[2].width)
        assertEquals(1080, variants[2].height)
        assertEquals(4561186L, variants[2].bandwidth)
    }

    @Test
    fun parseHlsMasterPlaylistResolvesRelativeUrls() {
        val master = "https://example.com/live/master.m3u8"
        val playlist =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
            720p/index.m3u8
            """.trimIndent()

        val variants = parseHlsMasterPlaylist(master, playlist)
        assertEquals(1, variants.size)
        assertEquals("https://example.com/live/720p/index.m3u8", variants[0].url)
        assertEquals(1280, variants[0].width)
        assertEquals(720, variants[0].height)
    }

    private class RecordingTokenProvider : TokenProvider {
        var invalidations = 0

        override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): PoTokenResult? = null

        override suspend fun close() = Unit

        override suspend fun invalidateAttestation() {
            invalidations++
        }
    }

    private class RecordingStreamExtractor(
        private val profiles: List<Pair<String, String>> = listOf("client" to "profile"),
        private val includeVideo: Boolean = false,
        private val audioUrl: String? = null,
        private val sabrBootstrap: SabrBootstrap? = null,
        private val onExtract: suspend (ContentHints) -> Unit = {},
    ) : StreamExtractor {
        var lastExcludedClients: Set<String>? = null
        var lastVideoId: String? = null
        var lastHints: ContentHints? = null

        override suspend fun prewarm() = Unit

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            onExtract(hints)
            lastVideoId = videoId
            lastExcludedClients = excludedClients
            lastHints = hints
            val (clientName, profileId) =
                profiles.firstOrNull { (client, profile) -> client !in excludedClients && profile !in excludedClients } ?: return null
            return ExtractedStream(
                videoId = videoId,
                audioUrl = audioUrl ?: "https://example.test/$videoId/${audioQuality.name}/$profileId",
                videoUrl = "https://example.test/$videoId/video".takeIf { includeVideo && hints.wantVideo },
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                contentLengthBytes = null,
                itag = 251,
                mimeType = "audio/webm",
                codecs = "opus",
                bitrate = null,
                sampleRate = null,
                clientName = clientName,
                profileId = profileId,
                requireBoundedRange = false,
                rangeChunkSizeBytes = 0L,
                streamDiagnostics = null,
                sabrBootstrap = sabrBootstrap,
            )
        }
    }

    private class FakeStreamExtractor(
        private val delayMs: Long = 0L,
        private val started: CompletableDeferred<Unit>? = null,
        private val release: CompletableDeferred<Unit>? = null,
        private val onExtract: (suspend () -> Unit)? = null,
        private val returnNullWhenExcluded: Boolean = false,
        private val playableClient: String = "client",
        private val excludedFailure: StreamResolveException? = null,
        private val ignoreCancellation: Boolean = false,
        private val cancelled: CompletableDeferred<Unit>? = null,
    ) : StreamExtractor {
        var calls = 0
        val exclusions = mutableListOf<Set<String>>()

        override suspend fun prewarm() = Unit

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            val call = ++calls
            exclusions += excludedClients
            onExtract?.invoke()
            if (playableClient in excludedClients || "profile" in excludedClients) {
                excludedFailure?.let { throw it }
                if (returnNullWhenExcluded) return null
            }
            started?.complete(Unit)
            if (ignoreCancellation) {
                withContext(NonCancellable) { release?.await() }
            } else {
                try {
                    release?.await()
                } catch (e: CancellationException) {
                    cancelled?.complete(Unit)
                    throw e
                }
            }
            if (delayMs > 0L) delay(delayMs.milliseconds)
            return ExtractedStream(
                videoId = videoId,
                audioUrl = "https://example.test/$videoId/${audioQuality.name}/$call",
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                contentLengthBytes = null,
                itag = 251,
                mimeType = "audio/webm",
                codecs = "opus",
                bitrate = null,
                sampleRate = null,
                clientName = playableClient,
                profileId = "profile",
                requireBoundedRange = false,
                rangeChunkSizeBytes = 0L,
                streamDiagnostics =
                    StreamDiagnostics(
                        attempts =
                            listOf(
                                StreamAttemptDiagnostic(
                                    clientName = playableClient,
                                    profileId = "profile",
                                    userAgent = "FakeAgent/1.0",
                                    outcome = "playable_response",
                                ),
                            ),
                        usedAuthenticatedWatchPage = false,
                    ),
            )
        }
    }
}
