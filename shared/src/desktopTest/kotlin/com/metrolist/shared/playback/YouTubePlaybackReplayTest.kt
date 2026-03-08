package com.metrolist.shared.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.responseDecodeDispatcher
import com.metrolist.shared.settings.Settings
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class YouTubePlaybackReplayTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var previousDecodeDispatcher: CoroutineDispatcher

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
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.pauseListenHistory = false
        Settings.shared.queueYouTubeHistoryWhenOffline = true
    }

    @AfterTest
    fun restoreSettings() {
        responseDecodeDispatcher = previousDecodeDispatcher
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun drainSendsOnlyPingsOwnedByTheCurrentAccount() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            val client =
                client {
                    requests += it
                    HttpStatusCode.NoContent
                }
            val repository = repository(client)
            val own = report(repository)
            val other = own.copy(id = "other", accountKey = "datasync:account-b:0", clientPlaybackNonce = "other-cpn")
            PendingYouTubePlaybackReportStore.upsert(other)
            PendingYouTubePlaybackReportStore.upsert(own)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val reporter = YouTubePlaybackReporter(scope, repository, replayDelay = {})
                reporter.awaitPendingPings()
                assertEquals(listOf(other), PendingYouTubePlaybackReportStore.all())
                val pings = requests.filter { it.encodedPath.startsWith("/api/stats/") }
                assertEquals(2, pings.size)
                assertTrue(pings.all { it.parameters["cpn"] == own.clientPlaybackNonce })
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun logoutPauseAndSyncDisablingStopReplayBetweenPings() =
        runTest(testDispatcher) {
            for (disable in listOf("logout", "pause", "sync")) {
                Settings.shared.pauseListenHistory = false
                Settings.shared.ytmSync = true
                PendingYouTubePlaybackReportStore.clear()
                val requests = mutableListOf<Url>()
                val client =
                    client {
                        requests += it
                        HttpStatusCode.NoContent
                    }
                val tube = InnerTube(client).apply { replaceSession("SAPISID=test", "visitor", "account-a", "0", true) }
                val repository = YouTubeRepository(tube)
                PendingYouTubePlaybackReportStore.upsert(report(repository))
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                try {
                    val reporter =
                        YouTubePlaybackReporter(scope, repository, replayDelay = {
                            when (disable) {
                                "logout" -> tube.replaceSession(null, null, null, "0", false)
                                "pause" -> Settings.shared.pauseListenHistory = true
                                "sync" -> Settings.shared.ytmSync = false
                            }
                        })
                    reporter.awaitPendingPings()
                    assertEquals(1, requests.count { it.encodedPath.endsWith("playback") }, disable)
                    assertFalse(requests.any { it.encodedPath.endsWith("watchtime") }, disable)
                    assertEquals(
                        1,
                        PendingYouTubePlaybackReportStore
                            .all()
                            .single()
                            .pings.size,
                    )
                } finally {
                    scope.coroutineContext[Job]!!.cancelAndJoin()
                    client.close()
                }
            }
        }

    @Test
    fun timerRetriesOnlyRemainingPingsWithoutRestartingPlayback() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            var rejectWatchtime = true
            val client =
                client { url ->
                    requests += url
                    if (url.encodedPath.endsWith("watchtime") && rejectWatchtime) {
                        rejectWatchtime = false
                        HttpStatusCode.ServiceUnavailable
                    } else {
                        HttpStatusCode.NoContent
                    }
                }
            val repository = repository(client)
            val report = report(repository)
            PendingYouTubePlaybackReportStore.upsert(report)
            val retry = Channel<Unit>()
            var now = 0L
            val waits = mutableListOf<Long>()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val reporter =
                    YouTubePlaybackReporter(
                        scope,
                        repository,
                        epochTimeMs = { now },
                        replayDelay = { waits += it },
                        retryDelay = { wait ->
                            retry.receive()
                            now += wait
                        },
                    )
                withTimeout(5_000) { reporter.awaitPendingPings() }
                val remaining = PendingYouTubePlaybackReportStore.all().single()
                assertEquals(1, remaining.attemptCount)
                assertTrue(remaining.pings.none { it is PendingYouTubePlaybackPing.Start })
                retry.send(Unit)
                withTimeout(5_000) { PendingYouTubePlaybackReportStore.changes.first { PendingYouTubePlaybackReportStore.all().isEmpty() } }
                assertTrue(PendingYouTubePlaybackReportStore.all().isEmpty())
                assertEquals(1, requests.count { it.encodedPath.endsWith("playback") })
                assertEquals(2, requests.count { it.encodedPath.endsWith("watchtime") })
                assertEquals(2, requests.count { it.encodedPath.endsWith("player") })
                assertEquals(listOf(15_000L, 15_000L), waits)
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun restartRemovesInactiveEmptyReportWithoutFetchingTracking() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            val client =
                client {
                    requests += it
                    HttpStatusCode.NoContent
                }
            val repository = repository(client)
            PendingYouTubePlaybackReportStore.upsert(
                report(repository).copy(pings = emptyList(), lastAcknowledgedOffsetMs = 15_000L),
            )
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val reporter = YouTubePlaybackReporter(scope, repository)
                withTimeout(5_000) { reporter.awaitPendingPings() }
                assertTrue(PendingYouTubePlaybackReportStore.all().isEmpty())
                assertEquals(0, requests.count { it.encodedPath.endsWith("player") })
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun pingsAppendedAfterDrainSnapshotAreNotRemovedAsEmpty() =
        runTest(testDispatcher) {
            val client = client { HttpStatusCode.NoContent }
            val repository = repository(client)
            val first = report(repository)
            val growing = first.copy(id = "growing-report", clientPlaybackNonce = "growing-cpn", pings = emptyList())
            val pendingStart = PendingYouTubePlaybackPing.Start(0, 0)
            PendingYouTubePlaybackReportStore.upsert(first)
            PendingYouTubePlaybackReportStore.upsert(growing)
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val reporter =
                    YouTubePlaybackReporter(
                        scope,
                        repository,
                        replayDelay = { PendingYouTubePlaybackReportStore.append(growing, pendingStart) },
                    )
                withTimeout(5_000) { reporter.awaitPendingPings() }
                val remaining = PendingYouTubePlaybackReportStore.all().single()
                assertEquals(growing.id, remaining.id)
                assertEquals(listOf(pendingStart), remaining.pings)
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun accountSwitchDuringPacingDoesNotSendRemainingWatchtime() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            val client =
                client {
                    requests += it
                    HttpStatusCode.NoContent
                }
            val innerTube = InnerTube(client).apply { replaceSession("SAPISID=test", "visitor", "account-a", "0", true) }
            val repository = YouTubeRepository(innerTube)
            PendingYouTubePlaybackReportStore.upsert(report(repository))
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val reporter =
                    YouTubePlaybackReporter(
                        scope,
                        repository,
                        replayDelay = { innerTube.replaceSession("SAPISID=other", "other", "account-b", "0", true) },
                    )
                withTimeout(5_000) { reporter.awaitPendingPings() }
                assertEquals(1, requests.count { it.encodedPath.endsWith("playback") })
                assertEquals(0, requests.count { it.encodedPath.endsWith("watchtime") })
                assertEquals(
                    1,
                    PendingYouTubePlaybackReportStore
                        .all()
                        .single()
                        .pings.size,
                )
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    @Test
    fun clearingPendingHistoryDuringPacingDoesNotResurrectIt() =
        runTest(testDispatcher) {
            val requests = mutableListOf<Url>()
            val client =
                client {
                    requests += it
                    HttpStatusCode.NoContent
                }
            val repository = repository(client)
            PendingYouTubePlaybackReportStore.upsert(report(repository))
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val reporter =
                    YouTubePlaybackReporter(
                        scope,
                        repository,
                        replayDelay = { PendingYouTubePlaybackReportStore.clear() },
                    )
                withTimeout(5_000) { reporter.awaitPendingPings() }
                assertTrue(PendingYouTubePlaybackReportStore.all().isEmpty())
                assertFalse(requests.any { it.encodedPath.endsWith("watchtime") })
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                client.close()
            }
        }

    private fun repository(client: HttpClient) =
        YouTubeRepository(
            InnerTube(client).apply { replaceSession("SAPISID=test", "visitor", "account-a", "0", true) },
        )

    private fun report(repository: YouTubeRepository) =
        PendingYouTubePlaybackReport(
            id = "test-report",
            accountKey = repository.playbackReportingAccountKey()!!,
            videoId = "test-video",
            clientPlaybackNonce = "test-cpn",
            format = null,
            pings =
                listOf(
                    PendingYouTubePlaybackPing.Start(0, 0),
                    PendingYouTubePlaybackPing.Watchtime(15_000, "0", "15", 15_000, 15_000, true, listOf(0L to 15_000L)),
                ),
        )

    private fun client(status: (Url) -> HttpStatusCode) =
        HttpClient(
            mockEngine { request ->
                val responseStatus = status(request.url)
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
                    respond("", responseStatus)
                }
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
}
