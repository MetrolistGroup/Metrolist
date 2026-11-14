package com.metrolist.android.ui.menu

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDownloadActionTest {
    @Test
    fun collectionIsDownloadedOnlyWhenEverySongIsDownloaded() {
        assertFalse(areAllSongsDownloaded(emptySet(), emptySet()))
        assertFalse(areAllSongsDownloaded(setOf("one", "two"), setOf("one")))
        assertTrue(areAllSongsDownloaded(setOf("one", "two"), setOf("one", "two")))
    }

    @Test
    fun removalOnlyDeletesDownloadsFromTheSelectedCollection() =
        runTest {
            val removedIds = mutableSetOf<String>()

            val removed =
                removeMediaDownloads(
                    songIds = setOf("one", "two"),
                ) { songId ->
                    removedIds += songId
                    true
                }

            assertEquals(2, removed)
            assertEquals(setOf("one", "two"), removedIds)
            removedIds.clear()
            assertEquals(
                1,
                removeMediaDownloads(setOf("one", "two")) { id ->
                    if (id == "two") removedIds += id
                    id == "two"
                },
            )
            assertEquals(setOf("two"), removedIds)
        }
}
