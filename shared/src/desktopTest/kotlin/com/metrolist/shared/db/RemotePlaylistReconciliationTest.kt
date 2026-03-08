package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.PlaylistNotFoundException
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.playlist.removeSongsFromStoredPlaylist
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LibraryViewModel
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemotePlaylistReconciliationTest {
    @Test
    fun completeFetchReconcilesRestoredRowsWithoutSettingsHistoryAndKeepsLocalPlaylists() =
        withDatabase { db ->
            val dao = db.dao()
            val missing =
                PlaylistEntity(
                    "LPmissing",
                    "Music",
                    browseId = "PLmissing",
                    remoteSongCount = 8,
                    createdAt = storedAt,
                    lastUpdateTime = storedAt,
                    bookmarkedAt = storedAt,
                )
            val current = missing.copy(id = "LPcurrent", browseId = "VLPLcurrent")
            val local = missing.copy(id = "LPlocal", browseId = null)
            val localFlag = missing.copy(id = "local-flag", isLocal = true)
            listOf(missing, current, local, localFlag).forEach { dao.upsert(it) }
            dao.upsert(SongEntity("song", "Downloaded song", isDownloaded = true))
            dao.insert(PlaylistSongMap(playlistId = missing.id, songId = "song"))
            val library = LibraryViewModel(CoroutineScope(Job().apply { cancel() }))
            val items = listOf(PlaylistItem("PLcurrent", "Current", null))

            library.synchronizeRemote(db, items, emptyList(), emptyList(), reconcile = false)
            assertEquals(missing, dao.findPlaylistEntity(missing.id))
            library.synchronizeRemote(db, items, emptyList(), emptyList(), reconcile = true)
            assertNull(dao.findPlaylistEntity(missing.id)?.bookmarkedAt)
            assertNotNull(dao.findPlaylistEntity(current.id)?.bookmarkedAt)
            assertEquals(local, dao.findPlaylistEntity(local.id))
            assertEquals(localFlag, dao.findPlaylistEntity(localFlag.id))
            assertTrue(dao.getSongById("song")!!.song.isDownloaded)
            assertEquals(1, dao.localPlaylistSongCount(missing.id))
            library.synchronizeRemote(db, emptyList(), emptyList(), emptyList(), reconcile = true)
            assertNull(dao.findPlaylistEntity(current.id)?.bookmarkedAt)
        }

    @Test
    fun freshReadOnlyMetadataRevokesCachedEditingAndRejectsRemovalBeforeNetwork() =
        withDatabase { db ->
            val entity = PlaylistEntity("LPsaved", "Playlist", browseId = "PLsaved", isEditable = true)
            db.dao().upsert(entity)
            val playlist = PlaylistItem("PLsaved", "Read only", null)
            db.cacheRemotePlaylist(entity.id, playlist, listOf(song))
            assertFalse(db.dao().findPlaylistEntity(entity.id)!!.isEditable)
            withRepository(db, HttpStatusCode.OK, expectNoRequests = true) { repository ->
                val result = removeSongsFromStoredPlaylist(db, repository, entity.id, listOf(song))
                assertTrue(result.isFailure)
            }
            db.syncRemotePlaylists(listOf(playlist.copy(id = "OLAK5uy_album", isEditable = true)))
            assertFalse(db.dao().findPlaylistEntity("OLAK5uy_album")!!.isEditable)
        }

    @Test
    fun onlyAuthenticatedDefinitive404RemovesRemoteRowsAndKeepsDownloadedAudio() =
        withDatabase { db ->
            val path = Files.createTempFile("retained-playlist-audio", ".audio")
            Files.write(path, byteArrayOf(1, 2, 3))
            try {
                for (edit in listOf(false, true)) {
                    for (status in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
                        for (authenticated in listOf(false, true)) {
                            LibraryDeletionTracker.clear()
                            val entity = PlaylistEntity("LPsaved", "Music", browseId = "PLsaved", isEditable = true)
                            db.dao().upsert(entity)
                            db.cacheRemotePlaylist(entity.id, PlaylistItem("PLsaved", "Music", null, isEditable = true), listOf(song))
                            db.dao().upsert(
                                CachedAudioEntity(
                                    song.id,
                                    song.title,
                                    "",
                                    filePath = path.toString(),
                                    fileSize = 3,
                                    cachedAt = 1,
                                    lastAccessedAt = 1,
                                    cacheLocation = "internal",
                                    kind = KIND_DOWNLOAD,
                                ),
                            )
                            val local = PlaylistEntity("local", "Local", isLocal = true)
                            db.dao().upsert(local)
                            db.dao().clearPlaylist(local.id)
                            db.dao().insert(PlaylistSongMap(playlistId = local.id, songId = song.id))
                            withRepository(db, status, authenticated) { repository ->
                                val result =
                                    runCatching {
                                        if (edit) {
                                            removeSongsFromStoredPlaylist(db, repository, entity.id, listOf(song)).getOrThrow()
                                        } else {
                                            db.loadAndCachePlaylistPage(repository, entity.id)
                                        }
                                    }
                                val gone = authenticated && status == HttpStatusCode.NotFound
                                assertEquals(gone, result.exceptionOrNull() is PlaylistNotFoundException)
                                assertEquals(gone, db.dao().findPlaylistEntity(entity.id) == null)
                                assertEquals(if (gone) 0 else 1, db.dao().localPlaylistSongCount(entity.id))
                                assertNotNull(db.dao().getSongById(song.id))
                                assertEquals(1, db.dao().localPlaylistSongCount(local.id))
                                assertEquals(listOf(song.id), db.dao().cachedAudioIdsByKind(KIND_DOWNLOAD).first())
                                assertTrue(Files.exists(path))
                                if (gone) {
                                    db.cacheRemotePlaylist(
                                        entity.id,
                                        PlaylistItem("PLsaved", "Stale feed", null, isEditable = true),
                                        listOf(song),
                                    )
                                    assertNull(db.dao().findPlaylistEntity(entity.id))
                                }
                            }
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun unrecognizedSuccessfulBrowseCannotDeleteCachedData() =
        withDatabase { db ->
            val entity =
                PlaylistEntity(
                    "LPsaved",
                    "Music",
                    browseId = "PLsaved",
                    createdAt = storedAt,
                    lastUpdateTime = storedAt,
                    bookmarkedAt = storedAt,
                )
            db.dao().upsert(entity)
            withRepository(db, HttpStatusCode.OK) { repository ->
                assertFailsWith<IllegalStateException> { db.loadAndCachePlaylistPage(repository, entity.id) }
            }
            assertEquals(entity, db.dao().findPlaylistEntity(entity.id))
        }

    private val storedAt = LocalDateTime(2026, 11, 5, 12, 0)
    private val song = SongItem("song", "Song", null, emptyList(), null, playlistSetVideoId = "set")

    private suspend fun withRepository(
        db: MusicDatabase,
        status: HttpStatusCode,
        authenticated: Boolean = true,
        expectNoRequests: Boolean = false,
        block: suspend (YouTubeRepository) -> Unit,
    ) {
        val engine =
            MockEngine {
                respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val tube = InnerTube(client, retryDelay = {}).apply { if (authenticated) cookie = "SAPISID=test" }
        try {
            block(YouTubeRepository(tube, databaseProvider = { db }))
            if (expectNoRequests) assertTrue(engine.requestHistory.isEmpty())
        } finally {
            tube.close()
            client.close()
        }
    }

    private fun withDatabase(block: suspend (MusicDatabase) -> Unit) =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            LibraryDeletionTracker.clear()
            val network = PlaybackNetworkStateStore.current
            PlaybackNetworkStateStore.publish(network.copy(connected = true))
            val path = Files.createTempFile("remote-playlist-reconciliation", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                block(db)
            } finally {
                db.close()
                Files.deleteIfExists(path)
                PlaybackNetworkStateStore.publish(network)
                LibraryDeletionTracker.clear()
                Settings.replaceSharedForTest(null)
            }
        }
}
