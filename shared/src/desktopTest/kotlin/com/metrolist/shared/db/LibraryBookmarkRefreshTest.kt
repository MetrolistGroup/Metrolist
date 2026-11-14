package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.playlist.LibraryBookmarkMutations
import com.metrolist.shared.playlist.setStoredPlaylistBookmarked
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.toggleAlbumLiked
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class LibraryBookmarkRefreshTest {
    @Test
    fun unlikeDuringPodcastReadSurvivesStaleMusicImportAndPublication() =
        runBlocking<Unit> {
            Settings.replaceSharedForTest(
                Settings(MapSettings()).apply {
                    dataSyncId = "overlap-account"
                    lastLibraryRefreshAccount = "overlap-account|0"
                    lastLibraryRefreshEpoch = Clock.System.now().toEpochMilliseconds()
                },
            )
            val path = Files.createTempFile("bookmark-refresh", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        val id =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["browseId"]
                                ?.jsonPrimitive
                                ?.content
                        if (id == "FEmusic_library_non_music_audio_list") {
                            started.complete(Unit)
                            release.await()
                        }
                        val response =
                            when (id) {
                                "FEmusic_liked_playlists", "VLPLsaved" -> shelf("VLPLsaved", "Remote playlist")
                                "FEmusic_liked_albums" -> shelf("MPREbsaved", "Remote album")
                                null -> "{}"
                                else -> emptyResponse
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            val job = Job()
            try {
                val bookmark = LocalDateTime(2026, 1, 1, 0, 0)
                database.dao().upsert(
                    AlbumEntity(
                        "MPREbsaved",
                        title = "Album",
                        playlistId = "OLAKsaved",
                        songCount = 0,
                        duration = 0,
                        bookmarkedAt = bookmark,
                    ),
                )
                database.dao().upsert(PlaylistEntity("stored", "Playlist", browseId = "PLsaved", bookmarkedAt = bookmark))
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
                val repository = YouTubeRepository(tube)
                val model = LibraryViewModel(CoroutineScope(coroutineContext + job), database, repository = repository)
                model.setActive(true)
                withTimeout(30_000) { model.uiState.first { it.items.size == 2 } }
                model.refresh()
                withTimeout(30_000) { started.await() }
                val album = AlbumItem("MPREbsaved", "Album", null, emptyList(), playlistId = "OLAKsaved")
                assertFalse(database.toggleAlbumLiked(album, emptyList(), repository))
                setStoredPlaylistBookmarked(
                    database,
                    repository,
                    "stored",
                    PlaylistItem("VLPLsaved", "Playlist", null),
                    false,
                    null,
                ).getOrThrow()
                release.complete(Unit)
                withTimeout(30_000) { model.uiState.first { !it.isRefreshing && it.items.isEmpty() } }
                assertNull(
                    database
                        .dao()
                        .album("MPREbsaved")
                        .first()
                        ?.album
                        ?.bookmarkedAt,
                )
                assertNull(database.dao().findPlaylistEntity("stored")?.bookmarkedAt)
                assertEquals(
                    "Remote album",
                    database
                        .dao()
                        .album("MPREbsaved")
                        .first()
                        ?.album
                        ?.title,
                )
                assertEquals("Remote playlist", database.dao().findPlaylistEntity("stored")?.name)
            } finally {
                release.complete(Unit)
                job.cancelAndJoin()
                client.close()
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun newerSavesSurviveAbsenceAndLaterScansCanImportExternalChanges() =
        runBlocking<Unit> {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { dataSyncId = "new-save-account" })
            val path = Files.createTempFile("bookmark-choices", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Signed-out saves stay local") })
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val model = LibraryViewModel(CoroutineScope(Job().apply { cancel() }))
                val scan = LibraryBookmarkMutations.capture()
                val album = AlbumItem("album", "Album", null, emptyList())
                val playlist = PlaylistItem("PLsaved", "Playlist", null)
                database.toggleAlbumLiked(album, emptyList(), repository)
                setStoredPlaylistBookmarked(database, repository, playlist.id, playlist, true, null).getOrThrow()
                Settings.shared.lastRemoteLibraryItemKeysJson = "[\"album:album\"]"
                Settings.shared.pendingRemoteLibraryRemovalKeysJson = "[\"album:album\"]"
                model.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), true, bookmarkSnapshot = scan)
                assertNotNull(
                    database
                        .dao()
                        .album("album")
                        .first()
                        ?.album
                        ?.bookmarkedAt,
                )
                assertNotNull(database.dao().findPlaylistEntity(playlist.id)?.bookmarkedAt)

                val stale = LibraryBookmarkMutations.capture()
                database.toggleAlbumLiked(album, emptyList(), repository)
                setStoredPlaylistBookmarked(database, repository, playlist.id, playlist, false, null).getOrThrow()
                model.synchronizeRemote(database, listOf(album, playlist), emptyList(), emptyList(), false, bookmarkSnapshot = stale)
                assertNull(
                    database
                        .dao()
                        .album("album")
                        .first()
                        ?.album
                        ?.bookmarkedAt,
                )
                assertNull(database.dao().findPlaylistEntity(playlist.id)?.bookmarkedAt)
                model.synchronizeRemote(database, listOf(album, playlist), emptyList(), emptyList(), true)
                assertNotNull(
                    database
                        .dao()
                        .album("album")
                        .first()
                        ?.album
                        ?.bookmarkedAt,
                )
                assertNotNull(database.dao().findPlaylistEntity(playlist.id)?.bookmarkedAt)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    private val emptyResponse = """{"contents":{"sectionListRenderer":{"contents":[]}}}"""

    private fun shelf(
        id: String,
        title: String,
    ) = """{
      "header": {"musicEditablePlaylistDetailHeaderRenderer": {}},
      "contents": {"sectionListRenderer": {"contents": [{"gridRenderer": {"items": [
        {"musicTwoRowItemRenderer": {
          "title": {"runs": [{"text": "$title"}]},
          "navigationEndpoint": {"browseEndpoint": {"browseId": "$id"}}
        }}
      ]}}]}}
    }"""
}
