package com.metrolist.android.viewmodels

import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.model.PlaylistPresentationOptions
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistViewModelTest {
    @Test
    fun loadedPlaylistPreparesRowsAndSourceIndicesTogether() =
        runTest {
            val first = SongItem(id = "duplicate", title = "First", artists = emptyList(), thumbnail = null, album = null)
            val second = first.copy(title = "Second")
            val songs = listOf(first, second)
            val loaded = prepareLoadedPlaylist(songs, listOf(first), PlaylistPresentationOptions(query = "Second"))
            assertSame(songs, loaded.songs)
            assertEquals(listOf(second), loaded.presentation.songs)
            assertEquals(0, loaded.sourceIndices[first])
            assertEquals(1, loaded.sourceIndices[second])
            assertEquals(listOf(first), loaded.suggestions)
            assertEquals(0, loaded.presentation.indexByKey[loaded.presentation.keys.single()])
        }

    @Test
    fun sortingWaitsForAllPagesButOriginalOrderCanShowTheFirstPage() =
        runTest {
            val z = SongItem("z", "Z", null, emptyList(), null)
            val a = SongItem("a", "A", null, emptyList(), null)
            val sort = PlaylistPresentationOptions(sort = PlaylistSongSort.TITLE)
            assertTrue(prepareLoadedPlaylist(listOf(z), emptyList(), sort, hasMore = true).presentation.songs.isEmpty())
            assertEquals(listOf(a, z), prepareLoadedPlaylist(listOf(z, a), emptyList(), sort).presentation.songs)
            assertEquals(
                listOf(z),
                prepareLoadedPlaylist(listOf(z), emptyList(), PlaylistPresentationOptions(), hasMore = true).presentation.songs,
            )
        }

    @Test
    fun localDuplicateReorderResolvesTheExactMapAfterItsPositionChanges() {
        val song = SongItem("a", "A", null, emptyList(), null)
        val maps =
            listOf(
                PlaylistSongMap(11, "playlist", "a", 0),
                PlaylistSongMap(12, "playlist", "b", 1),
                PlaylistSongMap(13, "playlist", "a", 2),
                PlaylistSongMap(14, "playlist", "c", 3),
            )
        assertEquals(maps[2], findPlaylistSongMap(song, 13, maps))
        val reordered = maps.map { if (it.id == 13) it.copy(position = 3) else it }
        assertEquals(13, findPlaylistSongMap(song, 13, reordered)?.id)
        assertNull(findPlaylistSongMap(song, null, maps))
        assertNull(findPlaylistSongMap(song, 99, maps))
    }

    @Test
    fun cancelledContinuationDoesNotCountAsCompleted() =
        runTest {
            assertFalse(Job().apply { cancel() }.joinSuccessfully())
            assertTrue(Job().apply { complete() }.joinSuccessfully())
        }
}
