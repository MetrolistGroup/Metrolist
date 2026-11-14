package com.metrolist.shared.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PlaylistPresentationTest {
    @Test
    fun preparationPreservesDuplicateOccurrencesAndUsesDisplayedOrderForIndices() =
        runTest {
            val songs = listOf(song("repeat", "Zulu", "entry-1"), song("other", "Alpha"), song("repeat", "Zulu", "entry-2"))
            val sorted = preparePlaylistPresentation(songs, PlaylistPresentationOptions(sort = PlaylistSongSort.TITLE))
            assertEquals(listOf(songs[1], songs[0], songs[2]), sorted.songs)
            assertEquals(3, sorted.keys.toSet().size)
            sorted.keys.forEachIndexed { index, key -> assertEquals(index, sorted.indexByKey[key]) }
            val filtered = preparePlaylistPresentation(songs, PlaylistPresentationOptions(query = "Zulu"))
            assertEquals(listOf(songs[0], songs[2]), filtered.songs)
            assertEquals(sorted.keys.drop(1), filtered.keys)
            val reorder = preparePlaylistPresentation(songs, PlaylistPresentationOptions(sort = PlaylistSongSort.TITLE, reorder = true))
            assertEquals(songs, reorder.songs)
            val duplicateWithoutSetId = preparePlaylistPresentation(listOf(songs[1], songs[1].copy()), PlaylistPresentationOptions())
            assertEquals(2, duplicateWithoutSetId.keys.toSet().size)
        }

    @Test
    fun cancellationDuringFilteringStopsBeforeKeyPreparation() =
        runTest {
            val worker = Job()
            var reads = 0
            var prepared = false
            val source =
                object : AbstractList<SongItem>() {
                    override val size = 4

                    override fun get(index: Int): SongItem {
                        reads++
                        if (index == 1) worker.cancel()
                        return song("$index", "match")
                    }
                }
            assertFailsWith<CancellationException> {
                withContext(worker) {
                    preparePlaylistPresentation(source, PlaylistPresentationOptions(query = "match"))
                    prepared = true
                }
            }
            assertEquals(source.size, reads)
            assertFalse(prepared)
        }

    private fun song(
        id: String,
        title: String,
        setId: String? = null,
    ) = SongItem(id, title, null, emptyList(), null, playlistSetVideoId = setId)
}
