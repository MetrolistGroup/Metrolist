package com.metrolist.desktop.storage

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.YouTubePlaybackReporter
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.PropertiesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredAudioHistoryTest {
    private val originalNetworkState = PlaybackNetworkStateStore.current
    private val song = SongItem("dQw4w9WgXcQ", "Song", null, emptyList(), null)

    @BeforeTest
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
        Settings.shared.pauseListenHistory = false
        Settings.shared.queueYouTubeHistoryWhenOffline = false
        PlaybackNetworkStateStore.publish(PlaybackNetworkState("stored-audio-test"))
    }

    @AfterTest
    fun restoreSettings() {
        PlaybackNetworkStateStore.publish(originalNetworkState)
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun cachedAndDownloadedSongsFetchTrackingAndReportSeparateListens() {
        val resources = resources(song)
        resources.forEach {
            assertNull(it.playbackTracking)
            assertEquals("/saved/audio.m4a", it.url)
        }
        val requests = report(resources)
        assertEquals(2, requests.count { it.encodedPath.endsWith("/player") })
        val starts = requests.filter { it.encodedPath == "/api/stats/playback" }
        val watchtimes = requests.filter { it.encodedPath == "/api/stats/watchtime" }
        assertEquals(2, starts.size)
        assertEquals(2, watchtimes.size)
        assertEquals(2, starts.map { it.parameters["cpn"] }.toSet().size)
        starts.forEach { start ->
            val nonce = start.parameters["cpn"]
            assertTrue(!nonce.isNullOrBlank())
            val watchtime = watchtimes.single { it.parameters["cpn"] == nonce }
            assertEquals("0.0", watchtime.parameters["st"])
            assertEquals("1.0", watchtime.parameters["et"])
        }
    }

    @Test
    fun storedLocalFilesUploadsPodcastsAndUnknownSongsDoNotReport() {
        val excluded =
            listOf(
                song.copy(isLocal = true),
                song.copy(id = "local_song"),
                song.copy(isUploaded = true),
                song.copy(musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"),
                song.copy(podcastId = "podcast"),
                null,
            ).flatMap { resources(it) } + resources(song, storedId = "different-song")
        assertTrue(report(excluded).isEmpty())
    }

    @Test
    fun collectionRowsRetainSavedUploadAndPodcastExclusions() =
        runBlocking<Unit> {
            val path = Files.createTempDirectory("stored-audio-history").toFile()
            val database = getDatabaseBuilder(path.resolve("songs.db")).build()
            try {
                val savedSongs =
                    listOf(
                        song.copy(id = "upload", isUploaded = true),
                        song.copy(id = "episode", musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"),
                        song.copy(id = "podcast", podcastId = "show"),
                    )
                savedSongs.forEach { saved ->
                    database.dao().upsertHistorySong(saved)
                    val collectionSong = song.copy(id = saved.id)
                    val reportingSong = storedAudioReportingSong(collectionSong, database)
                    assertTrue(report(resources(reportingSong)).isEmpty())
                }
                database.dao().upsertHistorySong(song)
                assertEquals(song.id, storedAudioReportingSong(song, database)?.id)
                val uploaded = song.copy(isUploaded = true)
                assertEquals(uploaded, storedAudioReportingSong(uploaded, database))
                assertNull(storedAudioReportingSong(null, database))
                assertEquals(song, storedAudioReportingSong(song, null))
                database.close()
                assertNull(storedAudioReportingSong(song, database))
            } finally {
                database.close()
                path.deleteRecursively()
            }
        }

    @Test
    fun pausedHistoryPreventsStoredAudioReporting() {
        Settings.shared.pauseListenHistory = true
        assertTrue(report(resources(song)).isEmpty())
    }

    private fun resources(
        sourceSong: SongItem?,
        storedId: String = sourceSong?.id ?: song.id,
    ): List<PlaybackResource> =
        listOf(
            CachedSongEntry(storedId, "Song", "Artist", "/saved/audio.m4a", fileSize = 2048, cachedAt = 0)
                .toPlaybackResource(sourceSong),
            DownloadedFile(storedId, "Song", "Artist", null, "/saved/audio.m4a", null, 60, 0)
                .toPlaybackResource(sourceSong),
        )

    private fun report(resources: List<PlaybackResource>): List<Url> =
        runBlocking {
            val requests = mutableListOf<Url>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        synchronized(requests) { requests += request.url }
                        if (request.url.encodedPath.endsWith("/player")) {
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
            val job = SupervisorJob()
            try {
                val innerTube = InnerTube(client).apply { replaceSession("SAPISID=session", "visitor", "account", "0", true) }
                val reporter = YouTubePlaybackReporter.create(CoroutineScope(coroutineContext + job), YouTubeRepository(innerTube))
                resources.forEachIndexed { index, resource ->
                    val instance = index.toLong() + 1
                    reporter.onPlaybackSample(instance, resource, 0L, true, true, 1f)
                    reporter.onPlaybackSample(instance, resource, 1_000L, true, true, 1f)
                    reporter.onPlaybackStopped(instance, 1_000L)
                    withTimeout(5_000) { reporter.awaitPendingPings() }
                }
                synchronized(requests) { requests.toList() }
            } finally {
                job.cancelAndJoin()
                client.close()
            }
        }
}
