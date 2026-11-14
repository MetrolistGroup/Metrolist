package com.metrolist.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryManualOrderTest {
    private val album = HiddenLibraryItemKey(HiddenLibraryItemType.ALBUM, "album")
    private val artist = HiddenLibraryItemKey(HiddenLibraryItemType.ARTIST, "artist")
    private val playlist = HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, "playlist")

    @Test
    fun normalizesStoredOrderAndAppendsNewItems() {
        val order = normalizedLibraryOrder(listOf(album, artist, playlist), "PLAYLIST:playlist\nALBUM:missing\nARTIST:artist")

        assertEquals(listOf(playlist, artist, album), order)
    }

    @Test
    fun movingAcrossMultiplePositionsShiftsInterveningItems() {
        val available = listOf(album, artist, playlist)
        val moved = moveLibraryOrder(available, available.toSet(), "", album, playlist)
        assertEquals(listOf(artist, playlist, album), normalizedLibraryOrder(available, moved))

        val restored = moveLibraryOrder(available, available.toSet(), moved, album, artist)
        assertEquals(available, normalizedLibraryOrder(available, restored))
    }

    @Test
    fun movingFilteredItemsPreservesHiddenAndOtherCategorySlots() {
        val secondAlbum = album.copy(id = "second")
        val thirdAlbum = album.copy(id = "third")
        val available = listOf(album, artist, secondAlbum, playlist, thirdAlbum)
        val visible = setOf(album, secondAlbum, thirdAlbum)
        val moved = moveLibraryOrder(available, visible, "", album, thirdAlbum)

        assertEquals(listOf(secondAlbum, artist, thirdAlbum, playlist, album), normalizedLibraryOrder(available, moved))
    }

    @Test
    fun ignoresMissingOrNonVisibleEndpoints() {
        val available = listOf(album, artist, playlist)
        val stored = "PLAYLIST:playlist\nARTIST:artist\nALBUM:album"
        val missing = album.copy(id = "missing")
        assertEquals(stored, moveLibraryOrder(available, available.toSet(), stored, missing, playlist))
        assertEquals(stored, moveLibraryOrder(available, available.toSet(), stored, album, missing))
        assertEquals(stored, moveLibraryOrder(available, setOf(album, playlist), stored, album, artist))
        assertEquals(stored, moveLibraryOrder(available, available.toSet(), stored, album, album))
    }

    @Test
    fun movesNormalizeStaleAndDuplicateKeysWithoutLosingNewItems() {
        val available = listOf(album, artist, playlist, album)
        val stored = "PLAYLIST:playlist\nALBUM:missing\nARTIST:artist\nPLAYLIST:playlist\ninvalid"
        val moved = moveLibraryOrder(available, available.toSet(), stored, album, playlist)

        assertEquals("ALBUM:album\nPLAYLIST:playlist\nARTIST:artist", moved)
    }

    @Test
    fun identicalIdsInDifferentCategoriesRemainDistinct() {
        val sameIdPlaylist = playlist.copy(id = album.id)
        val available = listOf(album, artist, sameIdPlaylist)
        val moved = moveLibraryOrder(available, available.toSet(), "", sameIdPlaylist, album)

        assertEquals(listOf(sameIdPlaylist, album, artist), normalizedLibraryOrder(available, moved))
    }
}
