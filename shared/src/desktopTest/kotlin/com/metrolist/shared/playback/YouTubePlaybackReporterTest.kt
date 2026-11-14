package com.metrolist.shared.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.PlaybackTrackingData
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.responseDecodeDispatcher
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubePlaybackReporterTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var previousDecodeDispatcher: CoroutineDispatcher
    private lateinit var previousNetwork: PlaybackNetworkState

    private fun mockEngine(handler: MockRequestHandler) =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = testDispatcher
                addHandler(handler)
            },
        )

    @BeforeTest
    fun isolateSettings() {
        previousDecodeDispatcher = responseDecodeDispatcher
        responseDecodeDispatcher = testDispatcher
        previousNetwork = PlaybackNetworkStateStore.current
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.pauseListenHistory = false
        Settings.shared.queueYouTubeHistoryWhenOffline = true
        PlaybackNetworkStateStore.publish(PlaybackNetworkState("report-test"))
    }

    @AfterTest
    fun restoreSettings() {
        responseDecodeDispatcher = previousDecodeDispatcher
        PlaybackNetworkStateStore.publish(previousNetwork)
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun signedOutPausedAndSyncDisabledPlaysNeitherReportNorEnterTheQueue() =
        runTest(testDispatcher) {
            val client = HttpClient(mockEngine { error("Private plays must stay local") })
            try {
                for ((cookie, sync, paused) in listOf(
                    Triple("", true, false),
                    Triple("SAPISID=account", false, false),
                    Triple("SAPISID=account", true, true),
                )) {
                    Settings.shared.ytmSync = sync
                    Settings.shared.pauseListenHistory = paused
                    val scope = CoroutineScope(coroutineContext + SupervisorJob())
                    try {
                        val tube = InnerTube(client).apply { replaceSession(cookie, "visitor", "account", "0", true) }
                        val reporter = YouTubePlaybackReporter(scope, YouTubeRepository(tube))
                        reporter.onPlaybackSample(1L, trackedResource("private"), 0, true, true, 1f)
                        reporter.onPlaybackStopped(1L, 30_000)
                        reporter.awaitPendingPings()
                        assertTrue(PendingYouTubePlaybackReportStore.all().isEmpty())
                    } finally {
                        scope.coroutineContext[Job]!!.cancelAndJoin()
                    }
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun pendingRetryWaitsForItsExactDeadline() =
        runTest(testDispatcher) {
            val client = HttpClient(mockEngine { error("Retry is not due yet") })
            val job = SupervisorJob()
            val delayRequested = CompletableDeferred<Long>()
            try {
                val innerTube = InnerTube(client).apply { replaceSession("SAPISID=session", "visitor", "account", "0", true) }
                val repository = YouTubeRepository(innerTube)
                PendingYouTubePlaybackReportStore.upsert(
                    PendingYouTubePlaybackReport(
                        id = "deadline",
                        accountKey = requireNotNull(repository.playbackReportingAccountKey()),
                        videoId = "track",
                        clientPlaybackNonce = "nonce",
                        format = null,
                        pings = listOf(PendingYouTubePlaybackPing.Start(0L, 0L)),
                        nextAttemptAtMs = 6_000L,
                    ),
                )
                YouTubePlaybackReporter(
                    CoroutineScope(coroutineContext + job),
                    repository,
                    epochTimeMs = { 1_000L },
                    retryDelay = {
                        delayRequested.complete(it)
                        awaitCancellation()
                    },
                )
                assertEquals(5_000L, withTimeout(5_000) { delayRequested.await() })
            } finally {
                job.cancelAndJoin()
                PendingYouTubePlaybackReportStore.clear()
                client.close()
            }
        }

    @Test
    fun emptyOfflineQueueDoesNotScheduleRetries() =
        runTest(testDispatcher) {
            var retries = 0
            val client = HttpClient(mockEngine { error("Empty queue must not contact YouTube") })
            val job = SupervisorJob()
            try {
                val innerTube = InnerTube(client).apply { replaceSession("SAPISID=session", "visitor", "account", "0", true) }
                val reporter =
                    YouTubePlaybackReporter(
                        CoroutineScope(coroutineContext + job),
                        YouTubeRepository(innerTube),
                        retryDelay = {
                            retries++
                            delay(it)
                        },
                    )
                withTimeout(5_000) { reporter.awaitPendingPings() }
                runCurrent()
                assertEquals(0, retries)
            } finally {
                job.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun productionReporterDrainsInOrderWithoutDispatchingWorkToUiScope() =
        runBlocking {
            val requests = mutableListOf<Url>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            val uiDispatcher =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        error("Reporting consumers must not dispatch onto the UI scope")
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + uiDispatcher)
            try {
                val reporter =
                    YouTubePlaybackReporter.create(
                        scope,
                        YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=session" }),
                    )
                val resource = trackedResource("background-consumer")
                reporter.onPlaybackSample(1L, resource, 0L, true, true, 1f)
                reporter.onPlaybackSample(1L, resource, 1_000L, true, true, 1f)
                reporter.onPlaybackStopped(1L, 1_000L)
                withTimeout(5.seconds) { reporter.awaitPendingPings() }
                val recorded = synchronized(requests) { requests.toList() }
                assertEquals(listOf("/api/stats/playback", "/api/stats/watchtime"), recorded.map { it.encodedPath })
                assertEquals("0.0", recorded.last().parameters["st"])
                assertEquals("1.0", recorded.last().parameters["et"])
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun overlappingPlayersReportSeparateListensAndCloseEachOnce() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            val client =
                HttpClient(
                    mockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var nowMs = 0L
            try {
                val repository = YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=session" })
                val outgoing = YouTubePlaybackReporter(scope, repository, monotonicTimeMs = { nowMs })
                val incoming = YouTubePlaybackReporter(scope, repository, monotonicTimeMs = { nowMs })
                outgoing.onPlaybackSample(1L, trackedResource("outgoing"), 0L, true, true, 1f)
                nowMs = 1_000L
                outgoing.onPlaybackSample(1L, trackedResource("outgoing"), 1_000L, true, true, 1f)
                incoming.onPlaybackSample(2L, trackedResource("incoming"), 0L, true, true, 1f)
                nowMs = 11_000L
                outgoing.onPlaybackSample(1L, trackedResource("outgoing"), 11_000L, true, true, 1f)
                incoming.onPlaybackSample(2L, trackedResource("incoming"), 10_000L, true, true, 1f)
                outgoing.onPlaybackStopped(1L, 11_000L)
                incoming.onPlaybackStopped(2L, 10_000L)
                outgoing.awaitPendingPings()
                incoming.awaitPendingPings()

                val recorded = synchronized(requests) { requests.toList() }
                for ((cpn, end) in listOf("outgoing" to "11.0", "incoming" to "10.0")) {
                    assertEquals(
                        1,
                        recorded.count { it.encodedPath == "/api/stats/playback" && it.parameters["cpn"] == cpn },
                    )
                    val watchtimes = recorded.filter { it.encodedPath == "/api/stats/watchtime" && it.parameters["cpn"] == cpn }
                    assertEquals(1, watchtimes.size)
                    assertEquals("0.0" to end, watchtimes.single().parameters["st"] to watchtimes.single().parameters["et"])
                }
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun recoveredStreamFormatAppliesToSubsequentWatchtimeOnSamePlaybackInstance() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            val client =
                HttpClient(
                    mockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val reporter = YouTubePlaybackReporter(scope, YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=session" }))
                val resource = trackedResource("format-change").copy(itag = 140)
                reporter.onPlaybackSample(1L, resource, 0L, true, true, 1f)
                reporter.onPlaybackSample(1L, resource, 1_000L, true, true, 1f)
                reporter.onPlaybackRecovered(1L, 1L, 1_000L, format = 139)
                reporter.onPlaybackSample(1L, resource.copy(itag = 139), 2_000L, true, true, 1f)
                reporter.onPlaybackStopped(1L, 2_000L)
                reporter.awaitPendingPings()

                assertEquals(
                    listOf("139", "139"),
                    synchronized(requests) {
                        requests.filter { it.encodedPath.endsWith("watchtime") }.map { it.parameters["fmt"] }
                    },
                )
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun renderingGapsAreNotReportedAsWatchtime() =
        runTest(testDispatcher) {
            val client =
                HttpClient(
                    mockEngine {
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            var nowMs = 0L
            val results = mutableListOf<PlaybackReporterPingResult>()
            try {
                val reporter =
                    YouTubePlaybackReporter(
                        scope = scope,
                        repository = YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=session" }),
                        monotonicTimeMs = { nowMs },
                        onPingResult = { synchronized(results) { results += it } },
                    )

                reporter.onPlaybackSample(1L, trackedResource("gap"), 0L, true, true, 1f)
                nowMs = 1_000L
                reporter.onPlaybackSample(1L, trackedResource("gap"), 1_000L, true, true, 1f)
                nowMs = 2_000L
                reporter.onPlaybackSample(1L, trackedResource("gap"), 1_000L, false, true, 1f)
                nowMs = 11_000L
                reporter.onPlaybackSample(1L, trackedResource("gap"), 10_000L, true, true, 1f)
                nowMs = 12_000L
                reporter.onPlaybackSample(1L, trackedResource("gap"), 11_000L, true, true, 1f)
                reporter.onPlaybackStopped(1L, 11_000L)
                reporter.awaitPendingPings()

                assertEquals(
                    listOf(0L to 1_000L, 10_000L to 11_000L),
                    synchronized(results) {
                        results
                            .filterIsInstance<PlaybackReporterPingResult.Watchtime>()
                            .flatMap { it.segments }
                    },
                )
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun missingTrackingCachedYouTubeResourceFetchesFreshTracking() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            val client =
                HttpClient(
                    mockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        if (request.url.encodedPath.contains("/player")) {
                            respond(
                                """
                                {
                                  "playabilityStatus": { "status": "OK" },
                                  "playbackTracking": {
                                    "videostatsPlaybackUrl": { "baseUrl": "https://music.youtube.com/api/stats/playback" },
                                    "videostatsWatchtimeUrl": { "baseUrl": "https://music.youtube.com/api/stats/watchtime" }
                                  }
                                }
                                """.trimIndent(),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond("", HttpStatusCode.NoContent)
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val results = mutableListOf<PlaybackReporterPingResult>()
                val innerTube =
                    InnerTube(client).apply {
                        cookie = "SAPISID=session; PREF=theme=3"
                        visitorData = "visitor-data"
                        dataSyncId = "channel-sync-id"
                        authUser = "0"
                    }
                val reporter =
                    YouTubePlaybackReporter(
                        scope = scope,
                        repository = YouTubeRepository(innerTube),
                        onPingResult = { synchronized(results) { results += it } },
                    )
                val resource =
                    PlaybackResource(
                        videoId = "dQw4w9WgXcQ",
                        url = "file:///cached-song.m4a",
                        headers = emptyMap(),
                        loudnessDb = null,
                        expiresAt = null,
                        allowPlaybackTrackingFallback = true,
                    )

                reporter.onPlaybackSample(1L, resource, 0L, true, true, 1f)
                reporter.onPlaybackSample(1L, resource, 1_000L, true, true, 1f)
                reporter.onPlaybackStopped(1L, 1_000L)
                reporter.awaitPendingPings()

                val recorded = synchronized(requests) { requests.toList() }
                val cpn =
                    synchronized(results) { results.filterIsInstance<PlaybackReporterPingResult.Start>().single().clientPlaybackNonce }
                assertTrue(recorded.count { it.encodedPath.contains("/player") } == 1, recorded.joinToString { it.toString() })
                assertEquals(
                    1,
                    recorded.count { it.encodedPath == "/api/stats/playback" && it.parameters["cpn"] == cpn },
                    recorded.joinToString { it.toString() },
                )
                assertEquals(
                    1,
                    recorded.count { it.encodedPath == "/api/stats/watchtime" && it.parameters["cpn"] == cpn },
                    recorded.joinToString { it.toString() },
                )
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun nonWebRemixTrackingFetchesWebRemixTrackingAndKeepsStatsHost() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            val client =
                HttpClient(
                    mockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        if (request.url.encodedPath.contains("/player")) {
                            respond(
                                """
                                {
                                  "playabilityStatus": { "status": "OK" },
                                  "playbackTracking": {
                                    "videostatsPlaybackUrl": { "baseUrl": "https://s.youtube.com/api/stats/playback" },
                                    "videostatsWatchtimeUrl": { "baseUrl": "https://s.youtube.com/api/stats/watchtime" }
                                  }
                                }
                                """.trimIndent(),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond("", HttpStatusCode.NoContent)
                        }
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val reporter = YouTubePlaybackReporter(scope, YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=session" }))
                val resource = trackedResource("shared-cpn").copy(clientName = "ANDROID")

                reporter.onPlaybackSample(1L, resource, 0L, true, true, 1f)
                reporter.onPlaybackSample(1L, resource, 1_000L, true, true, 1f)
                reporter.onPlaybackStopped(1L, 1_000L)
                reporter.awaitPendingPings()

                val recorded = synchronized(requests) { requests.toList() }
                assertEquals(1, recorded.count { it.encodedPath.contains("/player") }, recorded.joinToString { it.toString() })
                assertEquals(
                    1,
                    recorded.count {
                        it.host == "s.youtube.com" &&
                            it.encodedPath == "/api/stats/playback" &&
                            it.parameters["cpn"] == "shared-cpn"
                    },
                    recorded.joinToString { it.toString() },
                )
                assertEquals(
                    1,
                    recorded.count {
                        it.host == "s.youtube.com" &&
                            it.encodedPath == "/api/stats/watchtime" &&
                            it.parameters["cpn"] == "shared-cpn"
                    },
                    recorded.joinToString { it.toString() },
                )
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun missingTrackingResourceWithoutFallbackIsIgnored() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            val client =
                HttpClient(
                    mockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val reporter = YouTubePlaybackReporter(scope, YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=session" }))
                reporter.onPlaybackSample(
                    playbackInstanceId = 1L,
                    resource = PlaybackResource("local_1", "file:///local.m4a", emptyMap(), null, null),
                    positionMs = 0L,
                    isRendering = true,
                    wantsPlayback = true,
                    playbackSpeed = 1f,
                )
                reporter.onPlaybackStopped(1L, 1_000L)
                reporter.awaitPendingPings()
                assertEquals(emptyList(), synchronized(requests) { requests.toList() })
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun failedReportsPersistAndReplayWithFreshTrackingAndRecordedPacing() =
        runTest(testDispatcher) {
            PendingYouTubePlaybackReportStore.clear()
            var nowMs = 0L
            val failedClient = HttpClient(mockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
            val failedScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val innerTube =
                    InnerTube(failedClient).apply {
                        replaceSession("SAPISID=session-a", "visitor-a", "account-a", "0", true)
                    }
                val reporter =
                    YouTubePlaybackReporter(
                        scope = failedScope,
                        repository = YouTubeRepository(innerTube),
                        monotonicTimeMs = { nowMs },
                        epochTimeMs = { nowMs },
                    )
                reporter.onPlaybackSample(1L, trackedResource("offline-cpn"), 0L, true, true, 1f)
                nowMs = 1_000L
                reporter.onPlaybackSample(1L, trackedResource("offline-cpn"), 1_000L, true, true, 1f)
                reporter.onPlaybackStopped(1L, 1_000L)
                reporter.awaitPendingPings()
                assertTrue(
                    PendingYouTubePlaybackReportStore
                        .all()
                        .single()
                        .pings.size >= 2,
                )
            } finally {
                failedScope.coroutineContext[Job]!!.cancelAndJoin()
                failedClient.close()
            }

            nowMs += 30_000L
            val requests = mutableListOf<Url>()
            val delays = mutableListOf<Long>()
            val successClient =
                HttpClient(
                    mockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        if (request.url.encodedPath.contains("/player")) {
                            respond(
                                """
                                {
                                  "playabilityStatus": { "status": "OK" },
                                  "playbackTracking": {
                                    "videostatsPlaybackUrl": { "baseUrl": "https://music.youtube.com/api/stats/playback" },
                                    "videostatsWatchtimeUrl": { "baseUrl": "https://music.youtube.com/api/stats/watchtime" }
                                  }
                                }
                                """.trimIndent(),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond("", HttpStatusCode.NoContent)
                        }
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val successScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val innerTube =
                    InnerTube(successClient).apply {
                        replaceSession("SAPISID=session-a", "visitor-a", "account-a", "0", true)
                    }
                val reporter =
                    YouTubePlaybackReporter(
                        scope = successScope,
                        repository = YouTubeRepository(innerTube),
                        epochTimeMs = { nowMs },
                        replayDelay = { delays += it },
                    )
                withTimeout(5.seconds) { reporter.awaitPendingPings() }
                val recorded = synchronized(requests) { requests.toList() }
                assertEquals(1, recorded.count { it.encodedPath.contains("/player") })
                assertEquals(1, recorded.count { it.encodedPath == "/api/stats/playback" && it.parameters["cpn"] == "offline-cpn" })
                assertEquals(1, recorded.count { it.encodedPath == "/api/stats/watchtime" && it.parameters["cpn"] == "offline-cpn" })
                assertTrue(delays.any { it == 1_000L }, delays.toString())
                assertEquals(emptyList(), PendingYouTubePlaybackReportStore.all())
                val repeated = YouTubePlaybackReporter(successScope, YouTubeRepository(innerTube), epochTimeMs = { nowMs })
                repeated.awaitPendingPings()
                assertEquals(1, synchronized(requests) { requests.count { it.encodedPath == "/api/stats/playback" } })
                assertEquals(1, synchronized(requests) { requests.count { it.encodedPath == "/api/stats/watchtime" } })
            } finally {
                successScope.coroutineContext[Job]!!.cancelAndJoin()
                successClient.close()
                PendingYouTubePlaybackReportStore.clear()
            }
        }

    @Test
    fun pausedDeferredSessionReplaysBeforeCompletionAndRetainsAcknowledgedOffset() =
        runTest(testDispatcher) {
            PendingYouTubePlaybackReportStore.clear()
            var nowMs = 0L
            var online = false
            val requests = mutableListOf<Url>()
            val replayDelays = mutableListOf<Long>()
            PlaybackNetworkStateStore.publish(PlaybackNetworkState("report-offline", connected = false))
            val firstDeferredWatchtime = CompletableDeferred<Unit>()
            val resumedDeferredWatchtime = CompletableDeferred<Unit>()
            val firstReplayedWatchtime = CompletableDeferred<Unit>()
            val resumedReplayedWatchtime = CompletableDeferred<Unit>()
            var deferredWatchtimeCount = 0
            var replayedWatchtimeCount = 0
            val client =
                HttpClient(
                    mockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        when {
                            request.url.encodedPath.contains("/player") && !online -> {
                                respond("", HttpStatusCode.ServiceUnavailable)
                            }

                            request.url.encodedPath.contains("/player") -> {
                                respond(
                                    """
                                    {
                                      "playabilityStatus": { "status": "OK" },
                                      "playbackTracking": {
                                        "videostatsPlaybackUrl": { "baseUrl": "https://music.youtube.com/api/stats/playback" },
                                        "videostatsWatchtimeUrl": { "baseUrl": "https://music.youtube.com/api/stats/watchtime" }
                                      }
                                    }
                                    """.trimIndent(),
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }

                            request.url.encodedPath.endsWith("/watchtime") -> {
                                replayedWatchtimeCount++
                                when (replayedWatchtimeCount) {
                                    1 -> firstReplayedWatchtime.complete(Unit)
                                    2 -> resumedReplayedWatchtime.complete(Unit)
                                }
                                respond("", HttpStatusCode.NoContent)
                            }

                            else -> {
                                respond("", HttpStatusCode.NoContent)
                            }
                        }
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val innerTube =
                    InnerTube(client).apply {
                        replaceSession("SAPISID=session-a", "visitor-a", "account-a", "0", true)
                    }
                val reporter =
                    YouTubePlaybackReporter(
                        scope = scope,
                        repository = YouTubeRepository(innerTube),
                        monotonicTimeMs = { nowMs },
                        epochTimeMs = { nowMs },
                        replayDelay = { replayDelays += it },
                        onPingResult = { result ->
                            if (!result.success && result is PlaybackReporterPingResult.Watchtime) {
                                deferredWatchtimeCount++
                                when (deferredWatchtimeCount) {
                                    1 -> firstDeferredWatchtime.complete(Unit)
                                    2 -> resumedDeferredWatchtime.complete(Unit)
                                }
                            }
                        },
                    )
                withTimeout(5.seconds) { reporter.awaitPendingPings() }
                val cachedResource =
                    PlaybackResource(
                        videoId = "cached-video",
                        url = "file:///cached-song.m4a",
                        headers = emptyMap(),
                        loudnessDb = null,
                        expiresAt = null,
                        allowPlaybackTrackingFallback = true,
                    )

                reporter.onPlaybackSample(1L, cachedResource, 0L, true, true, 1f)
                nowMs = 60_000L
                reporter.onPlaybackSample(1L, cachedResource, 60_000L, false, false, 1f)
                withTimeout(5.seconds) { firstDeferredWatchtime.await() }

                val deferred = PendingYouTubePlaybackReportStore.all().single()
                assertEquals(2, deferred.pings.size)
                assertTrue(deferred.pings.first() is PendingYouTubePlaybackPing.Start)
                assertTrue(deferred.pings.last() is PendingYouTubePlaybackPing.Watchtime)

                online = true
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("report-online"))
                withTimeout(5.seconds) {
                    firstReplayedWatchtime.await()
                    PendingYouTubePlaybackReportStore.changes.first { PendingYouTubePlaybackReportStore.all().all { it.pings.isEmpty() } }
                }

                val acknowledged = PendingYouTubePlaybackReportStore.all().single()
                assertTrue(acknowledged.pings.isEmpty())
                assertEquals(60_000L, acknowledged.lastAcknowledgedOffsetMs)

                PlaybackNetworkStateStore.publish(PlaybackNetworkState("report-offline", connected = false))
                nowMs = 70_000L
                reporter.onPlaybackSample(1L, cachedResource, 60_000L, true, true, 1f)
                nowMs = 100_000L
                reporter.onPlaybackSample(1L, cachedResource, 90_000L, true, true, 1f)
                withTimeout(5.seconds) { resumedDeferredWatchtime.await() }

                val resumed = PendingYouTubePlaybackReportStore.all().single()
                assertEquals(60_000L, resumed.lastAcknowledgedOffsetMs)
                assertEquals(
                    listOf(60_000L to 90_000L),
                    (resumed.pings.single() as PendingYouTubePlaybackPing.Watchtime).segments,
                )

                PlaybackNetworkStateStore.publish(PlaybackNetworkState("report-online"))
                withTimeout(5.seconds) {
                    resumedReplayedWatchtime.await()
                    PendingYouTubePlaybackReportStore.changes.first { PendingYouTubePlaybackReportStore.all().all { it.pings.isEmpty() } }
                }

                val afterResume = PendingYouTubePlaybackReportStore.all().single()
                assertTrue(afterResume.pings.isEmpty())
                assertEquals(100_000L, afterResume.lastAcknowledgedOffsetMs)
                val recorded = synchronized(requests) { requests.toList() }
                val stats = recorded.filter { it.encodedPath.startsWith("/api/stats/") }
                assertEquals(1, stats.count { it.encodedPath.endsWith("/playback") })
                assertEquals(2, stats.count { it.encodedPath.endsWith("/watchtime") })
                assertTrue(stats.all { it.parameters["cpn"] == deferred.clientPlaybackNonce })
                assertEquals(
                    listOf("0.0" to "60.0", "60.0" to "90.0"),
                    stats
                        .filter { it.encodedPath.endsWith("/watchtime") }
                        .map { it.parameters["st"] to it.parameters["et"] },
                )
                assertEquals(listOf(60_000L, 40_000L), replayDelays)
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
                PendingYouTubePlaybackReportStore.clear()
            }
        }

    @Test
    fun offlineQueueSettingGatesPersistence() =
        runTest(testDispatcher) {
            PendingYouTubePlaybackReportStore.clear()

            val previousPauseHistory = Settings.shared.pauseListenHistory
            val previousQueueOffline = Settings.shared.queueYouTubeHistoryWhenOffline
            val client = HttpClient(mockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                Settings.shared.pauseListenHistory = false
                Settings.shared.queueYouTubeHistoryWhenOffline = false
                val innerTube = InnerTube(client).apply { replaceSession("SAPISID=session-a", "visitor-a", "account-a", "0", true) }
                val reporter = YouTubePlaybackReporter(scope, YouTubeRepository(innerTube))
                reporter.onPlaybackSample(1L, trackedResource("gated-cpn"), 0L, true, true, 1f)
                reporter.onPlaybackStopped(1L, 1_000L)
                withTimeout(5.seconds) { reporter.awaitPendingPings() }
                assertEquals(emptyList(), PendingYouTubePlaybackReportStore.all())
            } finally {
                Settings.shared.pauseListenHistory = previousPauseHistory
                Settings.shared.queueYouTubeHistoryWhenOffline = previousQueueOffline
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
                PendingYouTubePlaybackReportStore.clear()
            }
        }

    @Test
    fun partialAckRetryDoesNotResendStart() =
        runTest(testDispatcher) {
            PendingYouTubePlaybackReportStore.clear()

            val requests = mutableListOf<Url>()
            var failWatchtime = true
            val client =
                HttpClient(
                    mockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        if (request.url.encodedPath == "/api/stats/watchtime" && failWatchtime) {
                            failWatchtime = false
                            respond("", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond("", HttpStatusCode.NoContent)
                        }
                    },
                )
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val innerTube = InnerTube(client).apply { replaceSession("SAPISID=session-a", "visitor-a", "account-a", "0", true) }
                val reporter = YouTubePlaybackReporter(scope, YouTubeRepository(innerTube))
                reporter.onPlaybackSample(1L, trackedResource("partial-cpn"), 0L, true, true, 1f)
                reporter.onPlaybackStopped(1L, 1_000L)
                withTimeout(5.seconds) { reporter.awaitPendingPings() }
                val pending = PendingYouTubePlaybackReportStore.all().single()
                assertTrue(pending.pings.none { it is PendingYouTubePlaybackPing.Start })
                assertTrue(pending.pings.any { it is PendingYouTubePlaybackPing.Watchtime })
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
                PendingYouTubePlaybackReportStore.clear()
            }
        }

    @Test
    fun pendingReplayStaysScopedAndBounded() =
        runTest(testDispatcher) {
            PendingYouTubePlaybackReportStore.clear()

            val accountAClient = HttpClient(mockEngine { respond("", HttpStatusCode.NoContent) })
            val accountA =
                YouTubeRepository(
                    InnerTube(accountAClient).apply {
                        replaceSession("SAPISID=session-a", "visitor-a", "account-a", "0", true)
                    },
                ).playbackReportingAccountKey()!!
            repeat(PendingYouTubePlaybackReportStore.MAX_PENDING_REPORTS + 5) {
                PendingYouTubePlaybackReportStore.upsert(
                    PendingYouTubePlaybackReport(
                        id = "$accountA:cpn-$it",
                        accountKey = accountA,
                        videoId = "video-$it",
                        clientPlaybackNonce = "cpn-$it",
                        format = null,
                        pings = listOf(PendingYouTubePlaybackPing.Start(0L, 0L)),
                    ),
                )
            }
            assertEquals(PendingYouTubePlaybackReportStore.MAX_PENDING_REPORTS, PendingYouTubePlaybackReportStore.all().size)

            val requests = mutableListOf<Url>()
            val client =
                HttpClient(
                    mockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val previousPauseHistory = Settings.shared.pauseListenHistory
            val previousQueueOffline = Settings.shared.queueYouTubeHistoryWhenOffline
            Settings.shared.pauseListenHistory = false
            Settings.shared.queueYouTubeHistoryWhenOffline = true
            try {
                val reporter =
                    YouTubePlaybackReporter(
                        scope,
                        YouTubeRepository(
                            InnerTube(client).apply {
                                replaceSession("SAPISID=session-b", "visitor-b", "account-b", "0", true)
                            },
                        ),
                    )
                reporter.awaitPendingPings()
                assertEquals(emptyList(), synchronized(requests) { requests.toList() })

                Settings.shared.pauseListenHistory = true

                val paused =
                    YouTubePlaybackReporter(
                        scope,
                        YouTubeRepository(
                            InnerTube(client).apply {
                                replaceSession("SAPISID=session-a", "visitor-a", "account-a", "0", true)
                            },
                        ),
                    )
                paused.awaitPendingPings()
                assertEquals(emptyList(), synchronized(requests) { requests.toList() })
            } finally {
                Settings.shared.pauseListenHistory = previousPauseHistory
                Settings.shared.queueYouTubeHistoryWhenOffline = previousQueueOffline
                accountAClient.close()
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
                PendingYouTubePlaybackReportStore.clear()
            }
        }

    @Test
    fun detectsRecoveryJumpThatWouldFabricateWatchtime() {
        assertTrue(
            isPlaybackPositionDiscontinuity(
                previousPositionMs = 0L,
                positionMs = 60_000L,
                elapsedRealtimeMs = 50L,
                playbackSpeed = 1f,
            ),
        )
    }

    @Test
    fun acceptsNormalProgressAtPlaybackSpeed() {
        assertFalse(
            isPlaybackPositionDiscontinuity(
                previousPositionMs = 10_000L,
                positionMs = 12_000L,
                elapsedRealtimeMs = 1_000L,
                playbackSpeed = 2f,
            ),
        )
    }

    @Test
    fun detectsLargeBackwardJump() {
        assertTrue(
            isPlaybackPositionDiscontinuity(
                previousPositionMs = 60_000L,
                positionMs = 40_000L,
                elapsedRealtimeMs = 50L,
                playbackSpeed = 1f,
            ),
        )
    }

    @Test
    fun accountSwitchDuringSessionCreationDoesNotMixIdentityAndCredentials() =
        runTest(testDispatcher) {
            var requests = 0
            val client =
                HttpClient(
                    mockEngine {
                        requests++
                        respond("", HttpStatusCode.NoContent)
                    },
                )
            val innerTube = InnerTube(client).apply { replaceSession("SAPISID=account-a", "visitor-a", "account-a", "0", true) }
            val repository = YouTubeRepository(innerTube)
            val capturedSession = repository.playbackTrackingSession()
            val accountA = repository.playbackReportingAccountKey(capturedSession)
            val job = SupervisorJob()
            var switched = false
            try {
                val reporter =
                    YouTubePlaybackReporter(
                        CoroutineScope(coroutineContext + job),
                        repository,
                        monotonicTimeMs = {
                            if (!switched) {
                                switched = true
                                innerTube.replaceSession("SAPISID=account-b", "visitor-b", "account-b", "0", true)
                            }
                            0L
                        },
                    )
                reporter.onPlaybackSample(1L, trackedResource("account-switch"), 0L, true, true, 1f)
                reporter.onPlaybackStopped(1L, 1_000L)
                withTimeout(5_000) { reporter.awaitPendingPings() }

                assertEquals(accountA, repository.playbackReportingAccountKey(capturedSession))
                assertTrue(accountA != repository.playbackReportingAccountKey())
                assertEquals(0, requests)
                assertTrue(PendingYouTubePlaybackReportStore.all().isEmpty())
            } finally {
                job.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun credentialRefreshRetainsAndReplaysProgressWithoutResendingAcknowledgedStart() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Pair<Url, String?>>()
            var rejectWatchtime = true
            val client =
                HttpClient(
                    mockEngine { request ->
                        requests += request.url to request.headers[HttpHeaders.Cookie]
                        if (request.url.encodedPath.endsWith("player")) {
                            respond(
                                """
                                {"playabilityStatus":{"status":"OK"},"playbackTracking":{
                                "videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback"},
                                "videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime"}}}
                                """.trimIndent(),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond(
                                "",
                                if (rejectWatchtime &&
                                    request.url.encodedPath.endsWith("watchtime")
                                ) {
                                    HttpStatusCode.ServiceUnavailable
                                } else {
                                    HttpStatusCode.NoContent
                                },
                            )
                        }
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val innerTube = InnerTube(client).apply { replaceSession("SAPISID=original", "visitor", "same-account", "0", true) }
            val repository = YouTubeRepository(innerTube)
            val job = SupervisorJob()
            val results = Channel<PlaybackReporterPingResult>(Channel.UNLIMITED)
            PlaybackNetworkStateStore.publish(PlaybackNetworkState("report-offline", connected = false))
            val waits = mutableListOf<Long>()
            var nowMs = 0L
            try {
                val reporter =
                    YouTubePlaybackReporter(
                        CoroutineScope(coroutineContext + job),
                        repository,
                        monotonicTimeMs = { nowMs },
                        replayDelay = { waits += it },
                        onPingResult = { results.trySend(it) },
                    )
                val resource = trackedResource("credential-refresh")
                reporter.onPlaybackSample(1L, resource, 0L, true, true, 1f)
                assertTrue(withTimeout(5_000) { results.receive() }.success)
                nowMs = 1_000L
                reporter.onPlaybackInterrupted(1L, 1_000L)
                assertFalse(withTimeout(5_000) { results.receive() }.success)
                val queued = PendingYouTubePlaybackReportStore.all().single()
                assertEquals(1, queued.pings.size)

                innerTube.replaceSession("SAPISID=refreshed", "visitor", "same-account", "0", true)
                nowMs = 2_000L
                reporter.onPlaybackSample(1L, resource, 1_000L, true, true, 1f)
                nowMs = 3_000L
                reporter.onPlaybackStopped(1L, 2_000L)
                withTimeout(5_000) { reporter.awaitPendingPings() }
                val preserved = PendingYouTubePlaybackReportStore.all().single()
                assertEquals(queued.accountKey, preserved.accountKey)
                assertEquals(
                    listOf(0L to 1_000L, 1_000L to 2_000L),
                    preserved.pings.filterIsInstance<PendingYouTubePlaybackPing.Watchtime>().flatMap { it.segments },
                )

                val requestsBeforeReplay = requests.size
                rejectWatchtime = false
                withTimeout(5_000) {
                    PlaybackNetworkStateStore.publish(PlaybackNetworkState("report-online"))
                    PendingYouTubePlaybackReportStore.changes.first { PendingYouTubePlaybackReportStore.all().isEmpty() }
                }
                assertTrue(PendingYouTubePlaybackReportStore.all().isEmpty())
                assertEquals(1, requests.count { it.first.encodedPath.endsWith("playback") })
                assertEquals(listOf(1_000L, 2_000L), waits)
                assertTrue(requests.drop(requestsBeforeReplay).all { it.second?.contains("SAPISID=refreshed") == true })
                assertEquals(2, requests.drop(requestsBeforeReplay).count { it.first.encodedPath.endsWith("watchtime") })
            } finally {
                job.cancelAndJoin()
                client.close()
            }
        }

    private fun trackedResource(cpn: String) =
        PlaybackResource(
            videoId = cpn,
            url = "https://music.youtube.com/media",
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = null,
            clientName = YouTubeClient.WEB_REMIX.clientName,
            playbackTracking =
                PlaybackTrackingData(
                    clientPlaybackNonce = cpn,
                    playbackUrl = "https://music.youtube.com/api/stats/playback",
                    watchtimeUrl = "https://music.youtube.com/api/stats/watchtime",
                    scheduledFlushWalltimeSeconds = emptyList(),
                    defaultFlushIntervalSeconds = null,
                    resolvedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                ),
        )
}
