package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.loadLikedSongPages
import com.metrolist.shared.viewmodels.DownloadPlaylistFilter
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflinePlaylistSyncTest {
    private lateinit var previousNetwork: PlaybackNetworkState

    @BeforeTest
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        SongLikeState.refreshFromSettings()
        previousNetwork = PlaybackNetworkStateStore.current
    }

    @AfterTest
    fun restoreSettings() {
        PlaybackNetworkStateStore.publish(previousNetwork)
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun offlineBrowsingAndFailedRefreshKeepCompletePlaylistMembership() =
        runBlocking {
            val path = Files.createTempFile("offline-playlist-sync", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val songs = songs()
                database.cacheRemotePlaylist("PLtest", PlaylistItem("PLtest", "Playlist", "cover"), songs)
                database.dao().updateDownloadedInfo(songs.first().id, true, null)
                val original = database.dao().findPlaylistEntity("PLtest")
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}), databaseProvider = { database })

                for (connected in listOf(false, true)) {
                    PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = connected))
                    assertTrue(repository.getPlaylistPageData("PLtest").isOffline)
                    assertTrue(repository.getPlaylistResult("PLtest", allowOfflineFallback = false).isFailure)
                    assertFailsWith<IllegalStateException> { repository.playlistSongIds("PLtest", allowOfflineFallback = false) }
                    val result = database.loadAndCachePlaylist(repository, "PLtest")

                    assertEquals(listOf(songs.first().id), result.playlistSongs().map { it.id })
                    assertEquals(original, database.dao().findPlaylistEntity("PLtest"))
                    assertEquals(
                        songs.map { it.id },
                        database
                            .dao()
                            .playlistSongs("PLtest")
                            .first()
                            .map { it.song.id },
                    )
                    assertEquals(
                        songs.map { it.playlistSetVideoId },
                        database
                            .dao()
                            .playlistSongs("PLtest")
                            .first()
                            .map { it.map.setVideoId },
                    )
                    assertEquals(connected, requests > 0)
                }
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun unavailableRemotePlaylistDoesNotClassifyCachedDownloadsAsNonMembers() =
        runBlocking {
            val path = Files.createTempFile("unavailable-playlist-filter", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            var response = "{}"
            val client =
                HttpClient(
                    MockEngine { respond(response, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = true))
                val downloads = (1..4).map { SongItem("song-$it", "Song $it", null, emptyList(), null) }
                database.cacheRemotePlaylist("PLtest", PlaylistItem("PLtest", "Playlist", null), downloads)
                val playlist = checkNotNull(database.dao().findPlaylistEntity("PLtest"))
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}), databaseProvider = { database })
                val filter =
                    DownloadPlaylistFilter(
                        this,
                        localMembership = { error("Remote membership must not use the cached page") },
                        remoteMembership = { repository.playlistSongIds(it, allowOfflineFallback = false, refresh = true) },
                    )

                for (body in listOf("{}", """{"alerts":[{"alertRenderer":{"type":"ERROR","text":{"simpleText":"Unavailable"}}}]}""")) {
                    response = body
                    filter.select(playlist)
                    val failed = withTimeout(5_000) { filter.state.first { !it.loading } }
                    assertTrue(failed.failed)
                    assertEquals(downloads, failed.apply(downloads))
                    filter.setExcluded(true)
                    assertEquals(downloads, filter.state.value.apply(downloads))
                    assertEquals(downloads.map { it.id }, database.cachedRemotePlaylistSections("PLtest")?.playlistSongs()?.map { it.id })
                }

                val header = """"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Playlist"}}}"""
                val rows =
                    downloads.joinToString(",") { song ->
                        """{"musicResponsiveListItemRenderer":{
                      "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"${song.title}"}]}}}],
                      "playlistItemData":{"videoId":"${song.id}"}
                    }}"""
                    }
                response =
                    """{$header,"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$rows]}}]}}}"""
                filter.refresh()
                val loaded = withTimeout(5_000) { filter.state.first { !it.loading } }
                assertFalse(loaded.failed)
                assertTrue(loaded.apply(downloads).isEmpty())
                filter.setExcluded(false)
                assertEquals(downloads, filter.state.value.apply(downloads))

                response = """{$header}"""
                filter.refresh()
                val empty = withTimeout(5_000) { filter.state.first { !it.loading } }
                assertFalse(empty.failed)
                assertTrue(empty.apply(downloads).isEmpty())
                filter.setExcluded(true)
                assertEquals(downloads, filter.state.value.apply(downloads))
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun likedSongSyncRejectsOfflineSubsetsAndKeepsExistingLikes() =
        runBlocking {
            val path = Files.createTempFile("offline-likes-sync", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client =
                HttpClient(
                    MockEngine {
                        respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val songs = songs()
                database.cacheRemotePlaylist("VLLM", PlaylistItem("VLLM", "Likes", null), songs)
                database.dao().updateDownloadedInfo(songs.first().id, true, null)
                SongLikeState.replaceLikesLocally(songs)
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}), databaseProvider = { database })

                for (connected in listOf(false, true)) {
                    PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = connected))
                    assertEquals(listOf(songs.first().id), repository.getPlaylist("VLLM").playlistSongs().map { it.id })
                    val refreshed =
                        SongLikeState.refreshLikesFromRemote { onProgress ->
                            loadLikedSongPages(repository.getPlaylistPages("VLLM", applyContentFilters = false), onProgress)
                        }

                    assertFalse(refreshed)
                    assertEquals(songs, SongLikeState.likedSongs)
                    assertEquals(songs.map { it.id }.toSet(), SongLikeState.likedSongIds)
                }
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun successfulEmptyRemotePlaylistCanStillClearLikes() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Likes"}}}}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = true))
                SongLikeState.replaceLikesLocally(songs())
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))

                val refreshed =
                    SongLikeState.refreshLikesFromRemote { onProgress ->
                        loadLikedSongPages(repository.getPlaylistPages("VLLM", applyContentFilters = false), onProgress)
                    }

                assertTrue(refreshed)
                assertTrue(SongLikeState.likedSongs.isEmpty())
            } finally {
                client.close()
            }
        }

    private fun songs(): List<SongItem> =
        listOf(
            SongItem("downloaded", "Downloaded", null, emptyList(), null, playlistSetVideoId = "downloaded-set"),
            SongItem("online", "Online", null, emptyList(), null, playlistSetVideoId = "online-set"),
        )
}
