package com.metrolist.shared.playlistimport

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ImportTrackMatchTest {
    @Test
    fun reusesOnlyPersistedMatchesInTheSamePlaylistAndProvider() {
        runBlocking {
            val path = Files.createTempFile("import-match", ".db")
            Files.deleteIfExists(path)
            var searches = 0
            val client =
                HttpClient(
                    MockEngine {
                        searches++
                        respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            val track = PlaylistImportManager.CsvTrack("Original Title", "Artist", null)
            val song = SongItem("video123456", track.title, null, listOf(ArtistTiny(null, "Artist")), null)
            try {
                val firstDb = getDatabaseBuilder(path.toFile()).build()
                val playlistId =
                    try {
                        val manager = PlaylistImportManager(firstDb, YouTubeRepository(InnerTube(client)))
                        val imported =
                            manager
                                .importResolvedTracks(
                                    "Playlist",
                                    listOf(track to song),
                                    PlaylistImportManager.Destination.NewLocal,
                                    ImportSourceType.SPOTIFY,
                                ).getOrThrow()
                        assertEquals(1, imported.importedSongCount)
                        imported.playlist.id
                    } finally {
                        firstDb.close()
                    }
                val db = getDatabaseBuilder(path.toFile()).build()
                try {
                    val manager = PlaylistImportManager(db, YouTubeRepository(InnerTube(client)))
                    val existing = PlaylistImportManager.Destination.EXISTING(db.dao().findPlaylistEntity(playlistId)!!)
                    val updated = listOf(track, PlaylistImportManager.CsvTrack("New Song", "Artist", null))
                    val matches = manager.resolveImportTracks(listOf(track), existing, ImportSourceType.SPOTIFY)
                    assertEquals(listOf(song.id), matches.map { it.second.id })
                    assertEquals(
                        0,
                        manager
                            .importResolvedTracks(
                                "Playlist",
                                matches,
                                existing,
                                ImportSourceType.SPOTIFY,
                            ).getOrThrow()
                            .importedSongCount,
                    )
                    assertEquals(0, searches)
                    assertTrue(manager.resolveImportTracks(listOf(track), existing, ImportSourceType.DEEZER).isEmpty())
                    val other = PlaylistEntity(name = "Other", isLocal = true)
                    db.dao().upsert(other)
                    assertTrue(
                        manager
                            .resolveImportTracks(
                                listOf(track),
                                PlaylistImportManager.Destination.EXISTING(other),
                                ImportSourceType.SPOTIFY,
                            ).isEmpty(),
                    )
                    assertEquals(listOf(track), manager.resolveImportTracks(updated, existing, ImportSourceType.SPOTIFY).map { it.first })
                    assertEquals(
                        song.id,
                        db
                            .importTrackMatchDao()
                            .importTrackMatches(playlistId, ImportSourceType.SPOTIFY.name)
                            .single()
                            .songId,
                    )
                    val newSong = song.copy(id = "video234567", title = updated[1].title)
                    assertEquals(
                        1,
                        manager
                            .importResolvedTracks("Playlist", listOf(updated[1] to newSong), existing, ImportSourceType.SPOTIFY)
                            .getOrThrow()
                            .importedSongCount,
                    )
                    assertEquals(
                        listOf(song.id, newSong.id),
                        manager.resolveImportTracks(updated, existing, ImportSourceType.SPOTIFY).map { it.second.id },
                    )
                    db.dao().playlistSongMaps(playlistId, 0).forEach { db.dao().delete(it) }
                    assertTrue(db.importTrackMatchDao().importTrackMatches(playlistId, ImportSourceType.SPOTIFY.name).isEmpty())
                    assertTrue(manager.resolveImportTracks(listOf(track), existing, ImportSourceType.SPOTIFY).isEmpty())
                } finally {
                    db.close()
                }
            } finally {
                client.close()
                Files.deleteIfExists(path)
            }
        }
    }
}
