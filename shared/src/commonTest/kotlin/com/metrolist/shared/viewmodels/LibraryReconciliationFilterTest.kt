package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.playlist.LibraryBookmarkSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryReconciliationFilterTest {
    @Test
    fun filterRetainsItsAccountSnapshotAndNewerSaves() {
        val old = AlbumItem("old", "Old", null, emptyList(), libraryAddedAt = 10)
        val current = old.copy(id = "current")
        val local = old.copy(id = "local")
        val filter =
            LibraryReconciliationFilter(
                "first",
                setOf("album:old", "album:current"),
                setOf("album:current"),
                20,
                LibraryBookmarkSnapshot("first", 0),
            )
        repeat(2) {
            assertEquals(listOf(current, local), filter.filter(listOf(old, current, local), "first"))
        }
        val savedAgain = old.copy(libraryAddedAt = 21)
        assertEquals(listOf(savedAgain), filter.filter(listOf(savedAgain), "first"))
        assertEquals(listOf(old), filter.filter(listOf(old), "second"))
    }
}
