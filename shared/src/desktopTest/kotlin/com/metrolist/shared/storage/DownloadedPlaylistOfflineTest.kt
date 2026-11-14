package com.metrolist.shared.storage

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.cacheRemotePlaylist
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadedPlaylistOfflineTest {
    private lateinit var path: Path
    private lateinit var database: MusicDatabase
    private lateinit var client: HttpClient
    private lateinit var repository: YouTubeRepository
    private lateinit var previousNetwork: PlaybackNetworkState
    private var previousSettings: Settings? = null
    private val songs = (0 until 2_000).map { SongItem("video-$it", "Song $it", null, emptyList(), null, playlistSetVideoId = "set-$it") }
    private var requests = 0
    private var failContinuation = false

    @BeforeTest
    fun setUp() {
        previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        LibraryDeletionTracker.clear()
        previousNetwork = PlaybackNetworkStateStore.current
        PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = true))
        path = Files.createTempDirectory("downloaded-playlist-offline")
        database = getDesktopDatabaseBuilder(path.resolve("music.db").toFile()).build()
        client =
            HttpClient(
                MockEngine { request ->
                    requests++
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val offset = payload["continuation"]?.jsonPrimitive?.content?.toInt() ?: 0
                    val rows =
                        songs.drop(offset).take(100).joinToString(",") { song ->
                            """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"${song.title}"}]}}}],"playlistItemData":{"videoId":"${song.id}","playlistSetVideoId":"${song.playlistSetVideoId}"}}}"""
                        }
                    val continuation =
                        if (offset + 100 < songs.size) {
                            """, "continuations":[{"nextContinuationData":{"continuation":"${offset + 100}"}}]"""
                        } else {
                            ""
                        }
                    val shelf = """{"contents":[$rows]$continuation}"""
                    val body =
                        if (offset == 0) {
                            """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Playlist"}}},"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":$shelf}]}}}"""
                        } else {
                            """{"continuationContents":{"musicPlaylistShelfContinuation":$shelf}}"""
                        }
                    respond(
                        body,
                        if (failContinuation && offset > 0) HttpStatusCode.BadRequest else HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        repository = YouTubeRepository(InnerTube(client, retryDelay = {}), databaseProvider = { database })
    }

    @AfterTest
    fun tearDown() {
        client.close()
        database.close()
        path.toFile().deleteRecursively()
        PlaybackNetworkStateStore.publish(previousNetwork)
        LibraryDeletionTracker.clear()
        Settings.replaceSharedForTest(previousSettings)
    }

    @Test
    fun remoteDownloadRefreshStoresEveryPageForOfflineBrowsing() =
        runBlocking {
            database.dao().insert(PlaylistEntity(id = "LPsaved", name = "Playlist", browseId = "PLsaved"))
            database.cacheRemotePlaylist("LPsaved", PlaylistItem("PLsaved", "Playlist", null), songs.take(100))
            storeDownloads()
            val ids = songs.map { it.id }
            assertEquals(ids, DownloadedPlaylists.songs("VLPLsaved", database, repository).map { it.id })
            assertEquals(20, requests)

            PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = false))
            for (id in listOf("LPsaved", "PLsaved", "VLPLsaved")) {
                val offline = repository.getPlaylistPageData(id, applyContentFilters = false)
                assertTrue(offline.isOffline)
                assertFalse(offline.hasMore)
                assertEquals(songs.size, offline.songs.size)
                assertEquals(ids, offline.songs.map { it.id })
            }
            assertEquals(20, requests)
            assertEquals(
                songs.map { it.playlistSetVideoId },
                database
                    .dao()
                    .playlistSongs("LPsaved")
                    .first()
                    .map { it.map.setVideoId },
            )
        }

    @Test
    fun localDownloadsKeepEveryMemberOfflineWithoutBrowsing() =
        runBlocking {
            storeDownloads()
            database.dao().insert(PlaylistEntity(id = "LPlocal", name = "Local", isLocal = true))
            database.withDatabaseTransaction {
                songs.forEachIndexed { index, song ->
                    database.dao().insert(PlaylistSongMap(playlistId = "LPlocal", songId = song.id, position = index))
                }
            }
            PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = false))
            val ids = songs.map { it.id }
            assertEquals(ids, DownloadedPlaylists.songs("LPlocal", database, repository).map { it.id })
            assertEquals(ids, repository.getPlaylistPageData("LPlocal", applyContentFilters = false).songs.map { it.id })
            assertEquals(0, requests)
        }

    @Test
    fun downloadFiltersDoNotRemoveStoredPlaylistMembers() =
        runBlocking {
            BlockedContent.rememberDislikedSong(songs.last())
            val downloads = DownloadedPlaylists.songs("PLsaved", database, repository)
            assertEquals(songs.size - 1, downloads.size)
            assertFalse(downloads.any { it.id == songs.last().id })
            assertEquals(
                songs.map { it.id },
                database
                    .dao()
                    .playlistSongs("PLsaved")
                    .first()
                    .map { it.song.id },
            )
        }

    @Test
    fun failedAndOfflineDownloadRefreshesKeepStoredMembership() =
        runBlocking {
            database.cacheRemotePlaylist("PLsaved", PlaylistItem("PLsaved", "Playlist", null), songs.take(100))
            storeDownloads()
            val stored = database.dao().playlistSongs("PLsaved").first()
            val header = database.dao().findPlaylistEntity("PLsaved")
            failContinuation = true
            assertFails { DownloadedPlaylists.songs("PLsaved", database, repository) }
            assertEquals(stored, database.dao().playlistSongs("PLsaved").first())
            assertEquals(header, database.dao().findPlaylistEntity("PLsaved"))
            PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = false))
            val onlineRequests = requests
            assertFailsWith<IllegalStateException> { DownloadedPlaylists.songs("PLsaved", database, repository) }
            assertEquals(onlineRequests, requests)
            assertEquals(stored, database.dao().playlistSongs("PLsaved").first())
            assertEquals(header, database.dao().findPlaylistEntity("PLsaved"))
        }

    private suspend fun storeDownloads() {
        database.withDatabaseTransaction {
            songs.forEach { song ->
                database.dao().upsertHistorySong(song)
                database.dao().updateDownloadedInfo(song.id, true, null)
            }
        }
    }
}
