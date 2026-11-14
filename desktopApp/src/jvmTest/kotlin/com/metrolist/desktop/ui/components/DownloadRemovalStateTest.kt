package com.metrolist.desktop.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadRemovalStateTest {
    @Test
    fun openingAndCancellingPreservesDownloads() {
        val downloads = mutableSetOf("one", "two", "three", "four")
        val removal = DownloadRemovalState()
        removal.request(downloads)

        assertEquals(4, downloads.size)
        assertEquals(downloads, removal.pendingIds)
        removal.dismiss()
        removal.confirm { downloads.removeAll(it) }

        assertNull(removal.pendingIds)
        assertEquals(setOf("one", "two", "three", "four"), downloads)
    }

    @Test
    fun confirmationRemovesOnlyTheRequestedSnapshotOnce() {
        val downloads = mutableSetOf("one", "two", "three", "four")
        val removal = DownloadRemovalState()
        removal.request(downloads)
        downloads.add("new")
        var calls = 0
        val remove: (Set<String>) -> Unit = {
            calls++
            downloads.removeAll(it)
        }

        removal.confirm(remove)
        removal.confirm(remove)

        assertEquals(setOf("new"), downloads)
        assertEquals(1, calls)
        assertNull(removal.pendingIds)
    }
}
