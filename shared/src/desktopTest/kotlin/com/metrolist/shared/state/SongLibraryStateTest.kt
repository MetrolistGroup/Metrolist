package com.metrolist.shared.state

import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SongLibraryStateTest {
    @Test
    fun savingAnUnknownSongStoresItAndRemovingKeepsItsData() =
        runBlocking {
            val path = Files.createTempFile("song-library", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val song = SongItem("song", "Title", null, listOf(ArtistTiny("artist", "Artist")), null, duration = 180)
                val savedAt = LocalDateTime(2026, 11, 17, 0, 0)

                database.setSongInLibrary(song, saved = true, now = savedAt)
                val saved = database.dao().getSongById(song.id)!!
                assertEquals(savedAt, saved.song.inLibrary)
                assertEquals(listOf("artist"), saved.artists.map { it.id })

                database.setSongInLibrary(song, saved = false, now = savedAt)
                val removed = database.dao().getSongById(song.id)!!.song
                assertNull(removed.inLibrary)
                assertEquals("Title", removed.title)
                assertFalse(SongLibraryState.canToggle(song.copy(isLocal = true)))
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
