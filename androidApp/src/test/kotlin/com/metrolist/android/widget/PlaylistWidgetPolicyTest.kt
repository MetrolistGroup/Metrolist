package com.metrolist.android.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistWidgetPolicyTest {
    @Test
    fun historyIsObservedOnlyWhenItWouldOccupyAVisibleSlot() {
        val expectedPrecedingCounts = mapOf(0 to emptyList(), 1 to listOf(0), 4 to (0..3).toList(), 8 to listOf(0, 1, 2, 3, 7))
        for ((capacity, expected) in expectedPrecedingCounts) {
            assertEquals(expected, (0..8).filter { needsTopWidgetSong(it, capacity) })
        }
    }

    @Test
    fun resizingUsesTheSameCapacityAsCompactAndBalancedFullLayouts() {
        assertEquals(1, playlistWidgetQuickPickCapacity(200, 100))
        assertEquals(1, playlistWidgetQuickPickCapacity(339, 500))
        assertEquals(4, playlistWidgetQuickPickCapacity(340, 500))
        assertEquals(4, playlistWidgetQuickPickCapacity(360, 329))
        assertEquals(8, playlistWidgetQuickPickCapacity(360, 330))
        assertEquals(4, playlistWidgetDisplayCount(7, 8))
        assertEquals(8, playlistWidgetDisplayCount(8, 8))
    }
}
