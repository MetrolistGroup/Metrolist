package com.metrolist.desktop.state.content

import com.metrolist.desktop.state.app.AppState
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.db.hasCompleteStoredSongs
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.PropertiesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistAlbumStateTest {
    @Test
    fun reopeningSavedPlaylistWithoutAHeaderFetchesOnlyOnce() =
        runBlocking {
            val previousSettings = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())).apply { checkForUpdates = false })
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val previousSelection = PlaylistAlbumState.selectedPlaylistId
            val previousNetwork = PlaybackNetworkStateStore.current
            PlaybackNetworkStateStore.publish(previousNetwork.copy(connected = true))
            val directory = Files.createTempDirectory("saved-playlist-load")
            val database = getDatabaseBuilder(directory.resolve("songs.db").toFile()).build()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(
                            """{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Updated"}}},"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"First"}}}],"playlistItemData":{"videoId":"first"}}},{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"Second"}}}],"playlistItemData":{"videoId":"second"}}}]}}]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {})
            try {
                val saved =
                    PlaylistEntity(
                        id = "LPsaved",
                        name = "Saved",
                        browseId = "PLsaved",
                        remoteSongCount = 1,
                        isEditable = false,
                        customThumbnailUrl = "custom-cover",
                    )
                database.dao().upsert(saved)
                database.dao().upsertHistorySong(SongItem("old", "Old song", null, emptyList(), null))
                database.dao().insert(PlaylistSongMap(playlistId = saved.id, songId = "old", position = 0))
                val bookmarkedAt = database.dao().findPlaylistEntity(saved.id)!!.bookmarkedAt
                val repository = YouTubeRepository(tube)
                repeat(2) {
                    PlaylistAlbumState.selectedPlaylistId = saved.id
                    PlaylistAlbumState.loadPlaylistData(saved.id, database = database, repository = { repository })
                    assertNull(PlaylistAlbumState.playlistLoadError)
                    assertEquals(listOf("first", "second"), PlaylistAlbumState.playlistData.playlistSongs().map { it.id })
                }
                assertEquals(1, requests)
                val stored = assertNotNull(database.dao().findPlaylistEntity(saved.id))
                assertNotNull(stored.remoteHeader)
                assertEquals(2, stored.remoteSongCount)
                assertTrue(stored.isAutoSync)
                assertEquals(bookmarkedAt, stored.bookmarkedAt)
                assertEquals("custom-cover", stored.customThumbnailUrl)
                assertEquals("custom-cover", (PlaylistAlbumState.playlistData.getValue("header").single() as PlaylistItem).thumbnail)
                assertTrue(
                    stored.hasCompleteStoredSongs(
                        database
                            .dao()
                            .playlistSongs(saved.id)
                            .first()
                            .map { it.toSongItem() },
                    ),
                )
            } finally {
                AppState.scope.coroutineContext.cancelChildren()
                PlaylistAlbumState.selectedPlaylistId = previousSelection
                PlaylistAlbumState.playlistData = emptyMap()
                PlaybackNetworkStateStore.publish(previousNetwork)
                database.close()
                tube.close()
                client.close()
                directory.toFile().deleteRecursively()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(previousSettings)
            }
        }
}
