package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeHistoryEntry
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.HistorySortMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalInnerTube
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryAccountSyncTest {
    private val previousSession = GlobalInnerTube.instance.sessionSnapshot()
    private lateinit var database: MusicDatabase
    private lateinit var directory: Path

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        directory = Files.createTempDirectory("history-account")
        database = getDesktopDatabaseBuilder(directory.resolve("music.db").toFile()).build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
        directory.toFile().deleteRecursively()
        GlobalInnerTube.instance.replaceSession(
            previousSession.cookie,
            previousSession.visitorData,
            previousSession.dataSyncId,
            previousSession.authUser,
            previousSession.useLoginForBrowse,
        )
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun historyMergesLatestLocalAndAccountRowsBeforePagingSearchingSortingAndSelecting() =
        runBlocking {
            val tube = GlobalInnerTube.instance
            tube.replaceSession("SAPISID=a", "visitor", "account-a", "0", true)
            val accountA = YouTubeRepository(tube).playbackReportingAccountKey()!!
            val song = SongItem("same", "Same", null, emptyList(), null)
            database.recordHistoryPlay(song, 1000, playTimeMs = 10)
            val local = database.recordHistoryPlay(song, 2000, playTimeMs = 20)!!
            database.recordHistoryPlay(song, 1500, "YOUTUBE", "same", remoteAccountKey = accountA)
            val remote =
                database.recordHistoryPlay(
                    song.copy(id = "remote", title = "Remote"),
                    1800,
                    "YOUTUBE",
                    "remote",
                    remoteAccountKey = accountA,
                )!!
            database.recordHistoryPlay(song.copy(id = "legacy"), 5000, "YOUTUBE", "unknown-account")
            database.recordHistoryPlay(song, 4000, "YOUTUBE", "other-account", remoteAccountKey = "datasync:account-b:0")

            assertEquals(listOf(local, remote), database.recentVisibleHistory(100).map { it.id })
            assertEquals(listOf(remote), database.recentVisibleHistory(1, before = HistoryCursor(2000, local)).map { it.id })
            assertEquals(setOf(local, remote), database.visibleHistoryIds(""))
            assertEquals(setOf(local), database.visibleHistoryIds("Same"))
            assertEquals(
                listOf(remote, local),
                database.visibleHistoryPage(100, 0, "", HistorySortMode.TITLE, SortDirection.ASCENDING).map { it.id },
            )
            assertEquals(6, database.history().size)
            assertEquals(30L, database.historyStatisticsTotals(0, Long.MAX_VALUE).playTimeMs)

            Settings.shared.ytmSync = false
            assertEquals(listOf(local), database.recentVisibleHistory(100).map { it.id })
            Settings.shared.ytmSync = true
            Settings.shared.pauseListenHistory = true
            assertEquals(listOf(local), database.recentVisibleHistory(100).map { it.id })
            Settings.shared.pauseListenHistory = false
            tube.replaceSession(null, null, null, "0", false)
            assertEquals(listOf(local), database.recentVisibleHistory(100).map { it.id })
            tube.replaceSession("SAPISID=b", "visitor", "account-b", "0", true)
            assertEquals(listOf("other-account"), database.recentVisibleHistory(100).map { it.remoteFeedbackToken })
        }

    @Test
    fun timestampTiesSelectLatestAccountOrderThenIdAndDeletionRevealsOnlyOneOlderPlay() =
        runBlocking {
            val tube = GlobalInnerTube.instance
            tube.replaceSession("SAPISID=a", "visitor", "account-a", "0", true)
            val account = YouTubeRepository(tube).playbackReportingAccountKey()!!
            val song = SongItem("tied", "Tied", null, emptyList(), null)
            val local = database.recordHistoryPlay(song, 1000)!!
            val remote = database.recordHistoryPlay(song, 1000, "YOUTUBE", "remote", remoteOrder = 2, remoteAccountKey = account)!!
            val latest = database.recordHistoryPlay(song, 1000, "YOUTUBE", "latest", remoteOrder = 2, remoteAccountKey = account)!!
            database.recordHistoryPlay(song, 1000, "YOUTUBE", "hidden", remoteOrder = 3, remoteAccountKey = "other-account")
            assertEquals(listOf(latest), database.recentVisibleHistory(100).map { it.id })
            assertEquals(setOf(latest), database.visibleHistoryIds(""))
            assertTrue(database.recentVisibleHistory(100, before = HistoryCursor(1000, latest, 2)).isEmpty())
            var removed = emptyList<String>()
            database.deleteHistoryEntries(setOf(latest)) { removed = it }
            assertEquals(listOf("latest"), removed)
            assertEquals(setOf(remote), database.visibleHistoryIds(""))
            database.deleteHistoryEntries(database.visibleHistoryIds("")) { removed = it }
            assertEquals(listOf("remote"), removed)
            assertEquals(listOf(local), database.recentVisibleHistory(100).map { it.id })
            assertEquals(2, database.history().size)
        }

    @Test
    fun tokenlessImportsStayWithTheirAccountAndDoNotReplaceLocalPlays() =
        runBlocking {
            val song = SongItem("song", "Song", null, emptyList(), null)
            val date = RemoteHistoryDate(1_765_000_000_000, "date")
            val entry = YouTubeHistoryEntry(song, "date", null)
            database.recordHistoryPlay(song, date.startMs + 1000, playTimeMs = 30_000)
            assertTrue(database.applyYouTubeHistoryEntry(entry, date, accountKey = "datasync:a:0"))
            assertFalse(database.applyYouTubeHistoryEntry(entry, date, accountKey = "datasync:a:0"))
            assertTrue(database.applyYouTubeHistoryEntry(entry, date, accountKey = "datasync:b:0"))
            assertEquals(3, database.history().size)
            assertEquals(30_000L, database.historyStatisticsTotals(0, Long.MAX_VALUE).playTimeMs)
        }

    @Test
    fun importsRequireSyncAndRejectPrivacyOrAccountChangesBeforePersistence() =
        runBlocking {
            val tube = InnerTube(HttpClient(MockEngine { error("Disabled sync must not request history") }))
            try {
                val repository = YouTubeRepository(tube)
                assertFalse(database.syncYouTubeHistory(repository))
                tube.replaceSession("SAPISID=a", "visitor", "a", "0", true)
                Settings.shared.ytmSync = false
                assertFalse(database.syncYouTubeHistory(repository))
                Settings.shared.ytmSync = true
                Settings.shared.pauseListenHistory = true
                assertFalse(database.syncYouTubeHistory(repository))
            } finally {
                tube.close()
            }
            Settings.shared.pauseListenHistory = false
            for (switchAccount in listOf(false, true)) {
                val client =
                    HttpClient(
                        MockEngine {
                            respond(historyResponse(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
                val tube = InnerTube(client).apply { replaceSession("SAPISID=a", "visitor", "a", "0", true) }
                try {
                    try {
                        database.syncYouTubeHistory(YouTubeRepository(tube)) { _, _, _ ->
                            if (switchAccount) {
                                tube.replaceSession("SAPISID=b", "visitor", "b", "0", true)
                            } else {
                                Settings.shared.ytmSync = false
                            }
                            RemoteHistoryDate(1_765_000_000_000, "date")
                        }
                        error("Stale import must be cancelled")
                    } catch (_: CancellationException) {
                        assertTrue(database.history().isEmpty())
                    }
                } finally {
                    Settings.shared.ytmSync = true
                    tube.close()
                    client.close()
                }
            }
        }

    @Test
    fun accountSwitchBypassesPreviousAccountsRefreshThrottle() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(historyResponse(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client).apply { replaceSession("SAPISID=a", "visitor", "a", "0", true) }
            try {
                val repository = YouTubeRepository(tube)
                val parseDate = { _: String, _: Long, _: String -> RemoteHistoryDate(1_765_000_000_000, "date") }
                assertTrue(database.syncYouTubeHistory(repository, parseDate))
                assertFalse(database.syncYouTubeHistory(repository, parseDate))
                assertEquals(1, requests)
                tube.replaceSession("SAPISID=b", "visitor", "b", "0", true)
                assertTrue(database.syncYouTubeHistory(repository, parseDate))
                assertEquals(2, requests)
                assertEquals(2, database.history().size)
            } finally {
                tube.close()
                client.close()
            }
        }

    private fun historyResponse() =
        """
        {"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{
        "title":{"runs":[{"text":"Today"}]},"contents":[{"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":"remote"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{
        "text":{"runs":[{"text":"Remote","navigationEndpoint":{"watchEndpoint":{"videoId":"remote"}}}]}}}]}}]}}]}}}
        """.trimIndent()
}
