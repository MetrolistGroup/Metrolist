package com.metrolist.shared.playback

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ArtistShelfQueueTest {
    @Test
    fun copiedSongKeepsItsShelfOrderAndRepeatedOccurrences() {
        val first = song("first")
        val selected = song("selected")
        val last = song("last")
        val repeated = selected.copy(playlistSetVideoId = "another-occurrence")
        val sections =
            linkedMapOf(
                "releases" to listOf(AlbumItem(selected.id, "Album", null, emptyList())),
                "songs" to listOf(first, selected, last, repeated),
                "videos" to listOf(song("video")),
            )

        val queue = artistShelfQueue(selected.copy(title = "Edited title"), sections)

        assertEquals(listOf(first, selected, last, repeated), queue)
        assertSame(selected, queue[1])
    }

    @Test
    fun shelfQueueIncludesOnlySongsFromTheMatchingShelf() {
        val selected = song("selected")
        val next = song("next")
        val sections =
            mapOf(
                "songs" to listOf(selected, AlbumItem("album", "Album", null, emptyList()), next),
                "other" to listOf(song("other")),
            )

        assertEquals(listOf(selected, next), artistShelfQueue(selected, sections))
    }

    @Test
    fun missingSongKeepsSingleSongPlayback() {
        val selected = song("selected")

        assertEquals(listOf(selected), artistShelfQueue(selected, emptyMap()))
        assertEquals(listOf(selected), artistShelfQueue(selected, mapOf("songs" to listOf(song("other")))))
    }

    @Test
    fun fullShelfQueueLoadsThePlaylistBehindTheShelf() =
        runTest {
            val loaded = listOf(song("a"), song("b"))
            val full = loaded + song("c")
            val sections = mapOf("Top songs" to loaded)
            val browseIds = mapOf("Top songs" to ("VLOLAKtop" to null))

            assertEquals(full, fullArtistShelfQueue(song("b"), sections, browseIds) { full })
        }

    @Test
    fun fullShelfQueueKeepsLoadedRowsWhenThePlaylistCannotBeUsed() =
        runTest {
            val loaded = listOf(song("a"), song("b"))
            val sections = mapOf("Top songs" to loaded)
            val browseIds = mapOf("Top songs" to ("VLOLAKtop" to null))

            assertEquals(loaded, fullArtistShelfQueue(song("a"), sections, browseIds) { error("offline") })
            assertEquals(loaded, fullArtistShelfQueue(song("a"), sections, mapOf("Top songs" to ("MPLA1" to null))) { error("unused") })
            assertEquals(loaded, fullArtistShelfQueue(song("a"), sections, browseIds) { listOf(song("x"), song("y"), song("z")) })
        }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}
