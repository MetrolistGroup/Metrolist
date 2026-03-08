package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LibraryViewModel
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadedLibrarySyncTest {
    @Test
    fun completeUploadsRefreshRepairsOldUploadsEvenWhenOtherCategoriesFailWithoutDeletingLocalData() =
        withDatabase { database, library ->
            val dao = database.dao()
            val now = LocalDateTime(2026, 10, 1, 0, 0)
            val song =
                SongEntity(
                    "removed",
                    "Upload",
                    inLibrary = now,
                    isUploaded = true,
                    uploadEntityId = "entity",
                    isDownloaded = true,
                    dateDownload = now,
                    liked = true,
                    likedDate = now,
                    customTitle = "My title",
                    totalPlayTime = 1234,
                )
            val local = song.copy(id = "local", isLocal = true)
            val untracked = SongEntity("untracked", "Locally saved", inLibrary = now)
            dao.upsert(song)
            dao.upsert(local)
            dao.upsert(untracked)
            dao.upsert(PlaylistEntity("playlist", "My playlist", isLocal = true))
            dao.insert(PlaylistSongMap(playlistId = "playlist", songId = song.id))
            dao.insert(Event(songId = song.id, timestamp = now, playTime = 1234))
            val audio = Files.createTempFile("retained-upload", ".audio")
            val bytes = byteArrayOf(1, 2, 3)
            Files.write(audio, bytes)
            val download =
                CachedAudioEntity(
                    song.id,
                    song.title,
                    "Artist",
                    filePath = audio.toString(),
                    fileSize = bytes.size.toLong(),
                    cachedAt = 1,
                    lastAccessedAt = 1,
                    cacheLocation = audio.parent.toString(),
                    kind = KIND_DOWNLOAD,
                )
            dao.upsert(download)
            try {
                val current = SongItem("current", "Current upload", null, emptyList(), null, isUploaded = true)
                library.synchronizeRemote(
                    database,
                    listOf(current),
                    emptyList(),
                    emptyList(),
                    reconcile = false,
                    uploadedSongs = listOf(current),
                )
                assertNull(dao.getSongById(song.id)!!.song.inLibrary)
                val pending = Settings.shared.pendingRemoteLibraryRemovalKeysJson
                repeat(2) {
                    library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = false)
                }
                assertEquals(pending, Settings.shared.pendingRemoteLibraryRemovalKeysJson)
                assertNull(dao.getSongById(song.id)!!.song.inLibrary)

                library.synchronizeRemote(database, listOf(current), emptyList(), emptyList(), reconcile = true)
                assertEquals(song.copy(inLibrary = null), dao.getSongById(song.id)!!.song)
                assertEquals(local, dao.getSongById(local.id)!!.song)
                assertEquals(untracked, dao.getSongById(untracked.id)!!.song)
                assertEquals(
                    setOf("current", "local"),
                    dao
                        .uploadedSongsByNameAsc()
                        .first()
                        .map { it.song.id }
                        .toSet(),
                )
                assertEquals(1, dao.playlistSongs("playlist").first().size)
                assertEquals(1, dao.events().first().size)
                assertEquals(download, dao.cachedAudio(song.id))
                assertTrue(bytes.contentEquals(Files.readAllBytes(audio)))
            } finally {
                Files.deleteIfExists(audio)
            }
        }

    @Test
    fun failedUploadsRefreshKeepsOfflineLibraryMembership() =
        withDatabase { database, _ ->
            val song = SongEntity("upload", "Upload", isUploaded = true, inLibrary = LocalDateTime(2026, 10, 1, 0, 0))
            database.dao().upsert(song)
            withRepository(status = HttpStatusCode.ServiceUnavailable) { repository, _ ->
                assertFails { database.refreshUploadedSongs(repository) }
            }
            assertEquals(song, database.dao().getSongById(song.id)!!.song)
            assertEquals(
                listOf(song.id),
                database
                    .dao()
                    .uploadedSongsByNameAsc()
                    .first()
                    .map { it.id },
            )
        }

    @Test
    fun completeEmptyUploadsRefreshClearsMembershipImmediately() =
        withDatabase { database, _ ->
            val song = SongEntity("upload", "Upload", isUploaded = true, inLibrary = LocalDateTime(2026, 10, 1, 0, 0))
            database.dao().upsert(song)
            withRepository { repository, _ ->
                assertTrue(database.refreshUploadedSongs(repository).isEmpty())
            }
            assertEquals(song.copy(inLibrary = null), database.dao().getSongById(song.id)!!.song)
        }

    @Test
    fun deletionUsesStoredEntityForRowsWithoutUploadMetadataAndPreventsStaleRefreshFromRestoringIt() =
        withDatabase { database, _ ->
            val song =
                SongEntity(
                    "upload",
                    "Upload",
                    isUploaded = true,
                    uploadEntityId = "entity-upload",
                    inLibrary = LocalDateTime(2026, 10, 1, 0, 0),
                )
            database.dao().upsert(song)
            val row = SongItem(song.id, song.title, null, emptyList(), null)
            withRepository { repository, requests ->
                assertTrue(database.deleteUploadedSong(row, repository))
                assertTrue(requests.single().contains("entity-upload"))
            }
            database.synchronizeUploadedSongs(listOf(row.copy(isUploaded = true, uploadEntityId = "entity-upload")))
            assertEquals(song.copy(inLibrary = null), database.dao().getSongById(song.id)!!.song)
            assertTrue(
                database
                    .dao()
                    .uploadedSongsByNameAsc()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun missingEntityFetchesUploadsAndAlreadyDeletedSongsOnlyLoseMembership() =
        withDatabase { database, _ ->
            val song = SongEntity("upload", "Upload", inLibrary = LocalDateTime(2026, 10, 1, 0, 0))
            database.dao().upsert(song)
            withRepository { repository, requests ->
                assertTrue(
                    database.deleteUploadedSong(SongItem(song.id, song.title, null, emptyList(), null, isUploaded = true), repository),
                )
                assertTrue(requests.single().contains("FEmusic_library_privately_owned_tracks"))
            }
            assertEquals(song.copy(inLibrary = null), database.dao().getSongById(song.id)!!.song)
        }

    @Test
    fun missingEntityIsResolvedFromACompleteUploadsRefreshBeforeDeletion() =
        withDatabase { database, _ ->
            val song = SongEntity("upload", "Upload", isUploaded = true, inLibrary = LocalDateTime(2026, 10, 1, 0, 0))
            database.dao().upsert(song)
            val response = """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
                {"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Upload"}]}}}],
                "playlistItemData":{"videoId":"upload"},
                "delete":{"musicDeletePrivatelyOwnedEntityCommand":{"entityId":"entity-upload"}}
                }}]}}]}}}"""
            withRepository(response = response) { repository, requests ->
                assertTrue(
                    database.deleteUploadedSong(SongItem(song.id, song.title, null, emptyList(), null, isUploaded = true), repository),
                )
                assertEquals(2, requests.size)
                assertTrue(requests.first().contains("FEmusic_library_privately_owned_tracks"))
                assertTrue(requests.last().contains("entity-upload"))
            }
            assertNull(
                database
                    .dao()
                    .getSongById(song.id)!!
                    .song.inLibrary,
            )
            assertEquals(
                "entity-upload",
                database
                    .dao()
                    .getSongById(song.id)!!
                    .song.uploadEntityId,
            )
        }

    @Test
    fun failedRemoteDeletionAndLocalFilesKeepMembership() =
        withDatabase { database, _ ->
            val song =
                SongEntity(
                    "upload",
                    "Upload",
                    isUploaded = true,
                    uploadEntityId = "entity-upload",
                    inLibrary = LocalDateTime(2026, 10, 1, 0, 0),
                )
            database.dao().upsert(song)
            val row = SongItem(song.id, song.title, null, emptyList(), null, isUploaded = true)
            withRepository(status = HttpStatusCode.Unauthorized) { repository, _ ->
                assertFalse(database.deleteUploadedSong(row, repository))
            }
            withRepository { repository, requests ->
                assertFalse(database.deleteUploadedSong(row.copy(isLocal = true), repository))
                assertTrue(requests.isEmpty())
            }
            assertEquals(song, database.dao().getSongById(song.id)!!.song)
        }

    @Test
    fun reappearingUploadCancelsPendingRemovalAndEmptyLibraryCanRemoveItLater() =
        withDatabase { database, library ->
            val song = SongItem("upload", "Upload", null, emptyList(), null, isUploaded = true)
            library.synchronizeRemote(database, listOf(song), emptyList(), emptyList(), reconcile = true)
            library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = true)
            library.synchronizeRemote(database, listOf(song), emptyList(), emptyList(), reconcile = true)
            library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = true)
            assertTrue(
                database
                    .dao()
                    .getSongById(song.id)!!
                    .song.inLibrary != null,
            )
            library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = true)
            assertNull(
                database
                    .dao()
                    .getSongById(song.id)!!
                    .song.inLibrary,
            )
            assertTrue(
                database
                    .dao()
                    .uploadedSongsByNameAsc()
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun uploadedAlbumAndArtistBookmarksReconcileWithoutRemovingLocalCollections() =
        withDatabase { database, library ->
            val dao = database.dao()
            val now = LocalDateTime(2026, 10, 1, 0, 0)
            val album =
                AlbumEntity(
                    "upload-album",
                    title = "Album",
                    songCount = 1,
                    duration = 30,
                    lastUpdateTime = now,
                    bookmarkedAt = now,
                    isUploaded = true,
                )
            val artist = ArtistEntity("FEmusic_library_privately_owned_artist1", "Artist", lastUpdateTime = now, bookmarkedAt = now)
            val localAlbum = album.copy(id = "local-album", isLocal = true)
            val localArtist = artist.copy(id = "local-artist", isLocal = true)
            dao.upsert(album)
            dao.upsert(artist)
            dao.upsert(localAlbum)
            dao.upsert(localArtist)
            Settings.shared.lastRemoteLibraryItemKeysJson =
                Json.encodeToString(
                    setOf("album:${album.id}", "artist:${artist.id}", "album:${localAlbum.id}", "artist:${localArtist.id}"),
                )
            library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = true)
            library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = false)
            assertEquals(album, dao.album(album.id).first()!!.album)
            assertEquals(artist, dao.getArtistById(artist.id))
            library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = true)
            assertEquals(album.copy(bookmarkedAt = null), dao.album(album.id).first()!!.album)
            assertEquals(artist.copy(bookmarkedAt = null), dao.getArtistById(artist.id))
            assertEquals(localAlbum, dao.album(localAlbum.id).first()!!.album)
            assertEquals(localArtist, dao.getArtistById(localArtist.id))
        }

    private fun withDatabase(block: suspend (MusicDatabase, LibraryViewModel) -> Unit) =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            LibraryDeletionTracker.clear()
            val path = Files.createTempFile("upload-library-sync", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val scope = CoroutineScope(Job().apply { cancel() })
            try {
                block(database, LibraryViewModel(scope))
            } finally {
                database.close()
                Files.deleteIfExists(path)
                LibraryDeletionTracker.clear()
                Settings.replaceSharedForTest(null)
            }
        }

    private suspend fun withRepository(
        status: HttpStatusCode = HttpStatusCode.OK,
        response: String = """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
        block: suspend (YouTubeRepository, List<String>) -> Unit,
    ) {
        val requests = mutableListOf<String>()
        val client =
            HttpClient(
                MockEngine { request ->
                    requests += (request.body as TextContent).text
                    respond(
                        response,
                        status,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
        try {
            block(YouTubeRepository(tube), requests)
        } finally {
            tube.close()
            client.close()
        }
    }
}
