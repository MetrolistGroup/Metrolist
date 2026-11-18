package com.metrolist.shared.db

import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteLibrarySyncTest {
    @Test
    fun cleanLibraryScansRemoveTrackedStaleMembershipWithoutDeletingSongDataOrUntrackedSaves() =
        runBlocking {
            val previousSettings = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val path = Files.createTempFile("library-membership", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val library = LibraryViewModel(CoroutineScope(Job().apply { cancel() }))
            try {
                val dao = database.dao()
                val savedAt = LocalDateTime(2026, 1, 1, 0, 0)
                val tracked = SongEntity("tracked", "Old remote row", inLibrary = savedAt, isDownloaded = true, totalPlayTime = 3000)
                val untracked = tracked.copy(id = "untracked", title = "Local save")
                val local = tracked.copy(id = "local", isLocal = true)
                dao.upsert(tracked)
                dao.upsert(untracked)
                dao.upsert(local)
                Settings.shared.lastRemoteLibraryItemKeysJson = """["song:tracked","song:local"]"""

                library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = true)
                assertNotNull(dao.getSongById(tracked.id)!!.song.inLibrary)
                library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = false)
                assertNotNull(dao.getSongById(tracked.id)!!.song.inLibrary)
                library.synchronizeRemote(database, emptyList(), emptyList(), emptyList(), reconcile = true)
                assertEquals(tracked.copy(inLibrary = null), dao.getSongById(tracked.id)!!.song)
                assertEquals(untracked, dao.getSongById(untracked.id)!!.song)
                assertEquals(local, dao.getSongById(local.id)!!.song)
            } finally {
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(previousSettings)
            }
        }

    @Test
    fun largeBatchPreservesLocalMetadataAndMergesSharedArtistsInOrder() =
        runBlocking {
            val path = Files.createTempFile("remote-library-sync", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                val earlier = LocalDateTime(2026, 1, 1, 0, 0)
                val now = LocalDateTime(2026, 10, 1, 0, 0)
                dao.upsert(
                    SongEntity(
                        "song-0",
                        "Old",
                        customTitle = "My title",
                        customArtist = "My artist",
                        liked = true,
                        totalPlayTime = 3000,
                        inLibrary = earlier,
                        isDownloaded = true,
                        duration = 123,
                    ),
                )
                dao.upsert(ArtistEntity("artist-0", "Old artist", thumbnailUrl = "saved-art", bookmarkedAt = earlier))
                dao.upsert(AlbumEntity("album-0", title = "Old album", songCount = 7, duration = 1234, bookmarkedAt = earlier))
                val songs =
                    (0..1000).map { index ->
                        SongItem("song-$index", "New $index", null, listOf(ArtistTiny("artist-$index", "Artist $index")), null)
                    }
                val albums =
                    (0..1000).map { index ->
                        AlbumItem("album-$index", "Album $index", null, listOf(ArtistTiny("artist-$index", "Album artist $index")))
                    }
                val bookmarkedArtist = ArtistItem("artist-0", "Bookmarked artist", null)
                database.syncRemoteLibraryItems(songs + bookmarkedArtist + albums, now)
                val stored = dao.getSongById("song-0")!!
                assertEquals("New 0", stored.song.title)
                assertEquals("My title", stored.song.customTitle)
                assertEquals("My artist", stored.song.customArtist)
                assertTrue(stored.song.liked)
                assertTrue(stored.song.isDownloaded)
                assertEquals(3000L, stored.song.totalPlayTime)
                assertEquals(123, stored.song.duration)
                assertEquals(earlier, stored.song.inLibrary)
                assertEquals(earlier, dao.getArtistById("artist-0")!!.bookmarkedAt)
                assertEquals("saved-art", dao.getArtistById("artist-0")!!.thumbnailUrl)
                assertEquals("Album artist 0", stored.artists.single().name)
                assertEquals(
                    7,
                    dao
                        .album("album-0")
                        .first()!!
                        .album.songCount,
                )
                assertEquals(
                    1234,
                    dao
                        .album("album-0")
                        .first()!!
                        .album.duration,
                )
                assertEquals(
                    earlier,
                    dao
                        .album("album-0")
                        .first()!!
                        .album.bookmarkedAt,
                )
                assertEquals(1001, dao.allSongs().first().size)
                assertEquals("artist-1000", dao.songArtistMap("song-1000").single().artistId)
                assertEquals(
                    "artist-1000",
                    dao
                        .album("album-1000")
                        .first()!!
                        .artists
                        .single()
                        .id,
                )
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
