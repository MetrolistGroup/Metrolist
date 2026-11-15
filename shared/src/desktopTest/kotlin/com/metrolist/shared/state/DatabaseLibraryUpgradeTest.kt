package com.metrolist.shared.state

import androidx.sqlite.execSQL
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.backup.copyLegacyBackupTables
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.createHistoricalLibrary
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.playlist.setStoredPlaylistBookmarked
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseLibraryUpgradeTest {
    @Test
    fun upgradeAndLegacyRestoreKeepAlbumPlaylistAndArtistBookmarks() =
        runBlocking {
            val previousSettings = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val directory = createTempDirectory("library-upgrade").toFile()
            val client = HttpClient(MockEngine { error("Library recovery must not request the network") })
            val tube = InnerTube(client)
            val repository = YouTubeRepository(tube)
            try {
                for (restore in listOf(false, true)) {
                    val legacy = File(directory, "legacy-$restore.db")
                    createHistoricalLibrary(legacy.path, 38, JdbcSQLiteDriver())
                    JdbcSQLiteDriver().open(legacy.path).use { connection ->
                        connection.execSQL(
                            "INSERT INTO album (id, title, playlistId, thumbnailUrl, songCount, duration, lastUpdateTime, bookmarkedAt) " +
                                "VALUES ('album', 'Saved album', 'OLAKsaved', 'album-art', 7, 1234, 2000, 1000)",
                        )
                        connection.execSQL(
                            "INSERT INTO artist (id, name, thumbnailUrl, lastUpdateTime, bookmarkedAt) " +
                                "VALUES ('UCsaved', 'Saved artist', 'artist-art', 2000, 1000)",
                        )
                        connection.execSQL("INSERT INTO album_artist_map VALUES ('album', 'UCsaved', 0)")
                        connection.execSQL("UPDATE playlist SET browseId = 'PLsaved', bookmarkedAt = 1000 WHERE id = 'playlist'")
                        connection.execSQL(
                            "INSERT INTO album (id, title, songCount, duration, lastUpdateTime, inLibrary) " +
                                "VALUES ('library-album', 'Saved without a like', 0, 0, 2000, 1000), " +
                                "('song-album', 'Saved through tracks', 1, 240, 2000, NULL), " +
                                "('unliked', 'Unliked album', 0, 0, 2000, NULL)",
                        )
                        connection.execSQL("UPDATE album SET likedDate = 1000 WHERE id = 'unliked'")
                        connection.execSQL("UPDATE song SET albumId = 'song-album', inLibrary = 1000 WHERE id = 'song'")
                    }
                    val file = if (restore) File(directory, "restored.db") else legacy
                    if (restore) {
                        getDesktopDatabaseBuilder(file).build().let { database ->
                            try {
                                database.dao().eventCount().first()
                            } finally {
                                database.close()
                            }
                        }
                        JdbcSQLiteDriver().open(file.path).use { copyLegacyBackupTables(it, legacy.path, 38) }
                    }
                    val database = getDesktopDatabaseBuilder(file).build()
                    try {
                        val model = LibraryViewModel(CoroutineScope(Job().apply { cancel() }), repository = repository)
                        val items = model.loadLocalItems(database)
                        assertEquals(
                            setOf("album", "library-album", "song-album"),
                            items.filterIsInstance<AlbumItem>().map { it.id }.toSet(),
                        )
                        val album = items.filterIsInstance<AlbumItem>().single { it.id == "album" }
                        assertEquals("album", album.id)
                        assertEquals("Saved album", album.title)
                        assertEquals("OLAKsaved", album.playlistId)
                        assertEquals("album-art", album.thumbnail)
                        assertEquals(listOf("UCsaved"), album.artists.map { it.id })
                        assertEquals(7, album.songCount)
                        assertEquals(1234L, album.duration)
                        val artist = items.filterIsInstance<ArtistItem>().single()
                        assertEquals("UCsaved", artist.id)
                        assertTrue(artist.isSubscribed)
                        val playlist = items.filterIsInstance<PlaylistItem>().single()
                        assertEquals("PLsaved", playlist.id)
                        assertEquals("1", playlist.songCount)
                        assertEquals(listOf(1000L, 1000L, 1000L), listOf(album, artist, playlist).map { it.libraryAddedAt })
                        assertEquals(1000L, items.single { it.id == "library-album" }.libraryAddedAt)
                        assertEquals(
                            1,
                            database
                                .dao()
                                .albumsLikedByCreateDateAsc()
                                .first()
                                .size,
                        )
                        assertNull(
                            database
                                .dao()
                                .album("library-album")
                                .first()!!
                                .album.bookmarkedAt,
                        )
                        assertNull(
                            database
                                .dao()
                                .album("song-album")
                                .first()!!
                                .album.bookmarkedAt,
                        )

                        tube.cookie = "SAPISID=session"
                        assertEquals(listOf("album"), model.loadLocalItems(database).filterIsInstance<AlbumItem>().map { it.id })
                        tube.cookie = ""
                        assertFalse(database.toggleAlbumLiked(album, emptyList(), repository))
                        setStoredPlaylistBookmarked(database, repository, "playlist", playlist, false, null).getOrThrow()
                        assertFalse(database.updateArtistSubscribed("UCsaved", artist.title, false))
                        assertEquals(setOf("library-album", "song-album", "song"), model.loadLocalItems(database).map { it.id }.toSet())
                    } finally {
                        database.close()
                    }
                    val reopened = getDesktopDatabaseBuilder(file).build()
                    try {
                        val model = LibraryViewModel(CoroutineScope(Job().apply { cancel() }), repository = repository)
                        assertEquals(setOf("library-album", "song-album", "song"), model.loadLocalItems(reopened).map { it.id }.toSet())
                        assertNull(
                            reopened
                                .dao()
                                .album("album")
                                .first()!!
                                .album.bookmarkedAt,
                        )
                    } finally {
                        reopened.close()
                    }
                }
            } finally {
                client.close()
                Settings.replaceSharedForTest(previousSettings)
                directory.deleteRecursively()
            }
        }
}
