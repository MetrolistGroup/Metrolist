package com.metrolist.shared.ui.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvReorderScrollTargetTest {
    @Test
    fun waitsForTheReorderBeforeFollowingTheOccurrence() {
        val target = TvReorderScrollTarget("song:second", 1)
        assertNull(target.listIndex(listOf("song:first", "song:second", "other"), 0))
        assertEquals(2, target.listIndex(listOf("song:first", "other", "song:second"), 0))
        assertEquals(0, target.listIndex(listOf("song:second", "song:first", "other"), 0))
    }

    @Test
    fun includesQueueAndPlaylistHeadersWithoutConfusingDuplicateSongs() {
        val target = TvReorderScrollTarget("song:second", 1)
        val reordered = listOf("song:first", "other", "song:second")
        assertEquals(3, target.listIndex(reordered, 1))
        assertEquals(4, target.listIndex(reordered, 2))
        assertEquals(5, target.listIndex(reordered, 3))
        assertNull(target.listIndex(listOf("song:first", "other"), 1))
        assertNull(target.listIndex(emptyList(), 0))
    }
}
