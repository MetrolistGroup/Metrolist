package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongGroupingTest {
    private fun song(
        id: String,
        artist: String?,
        album: String?,
    ) = SongItem(
        id = id,
        title = "Song $id",
        thumbnail = null,
        artists = listOfNotNull(artist?.let { ArtistTiny(id = null, name = it) }),
        album = album?.let { AlbumTiny(id = null, name = it) },
    )

    private val songs =
        listOf(
            song("1", "queen", "A Night at the Opera"),
            song("2", null, null),
            song("3", "Abba", "Gold"),
            song("4", "Queen", "Greatest Hits"),
            song("5", "Abba", ""),
        )

    @Test
    fun noneReturnsOneUngroupedListInOrder() {
        val groups = songs.groupedBy(SongGroupBy.NONE)

        assertEquals(1, groups.size)
        assertNull(groups[0].key)
        assertEquals(listOf("1", "2", "3", "4", "5"), groups[0].songs.map { it.id })
    }

    @Test
    fun artistGroupsIgnoreCaseSortAndPutUnknownLast() {
        val groups = songs.groupedBy(SongGroupBy.ARTIST)

        assertEquals(listOf("Abba", "queen", null), groups.map { it.key })
        assertEquals(listOf("3", "5"), groups[0].songs.map { it.id })
        assertEquals(listOf("1", "4"), groups[1].songs.map { it.id })
        assertEquals(listOf("2"), groups[2].songs.map { it.id })
    }

    @Test
    fun albumGroupsTreatBlankNamesAsUnknown() {
        val groups = songs.groupedBy(SongGroupBy.ALBUM)

        assertEquals(listOf("A Night at the Opera", "Gold", "Greatest Hits", null), groups.map { it.key })
        assertEquals(listOf("2", "5"), groups.last().songs.map { it.id })
    }

    @Test
    fun emptyListProducesNoGroups() {
        assertEquals(emptyList(), emptyList<SongItem>().groupedBy(SongGroupBy.ARTIST))
    }
}
