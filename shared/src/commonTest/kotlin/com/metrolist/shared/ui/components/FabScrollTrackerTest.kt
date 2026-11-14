package com.metrolist.shared.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class FabScrollTrackerTest {
    @Test
    fun collapsesThenHidesAndReturnsWhenScrollingUp() {
        val tracker = FabScrollTracker(48f)
        assertEquals(ScrollingFabState.Expanded, tracker.update(FabScrollPosition(0, 0, atStart = true, nearEnd = false)))
        assertEquals(ScrollingFabState.Expanded, tracker.update(FabScrollPosition(0, 24, atStart = false, nearEnd = false)))
        assertEquals(ScrollingFabState.Collapsed, tracker.update(FabScrollPosition(0, 48, atStart = false, nearEnd = false)))
        assertEquals(ScrollingFabState.Hidden, tracker.update(FabScrollPosition(0, 80, atStart = false, nearEnd = true)))
        assertEquals(ScrollingFabState.Hidden, tracker.update(FabScrollPosition(0, 80, atStart = false, nearEnd = true)))
        assertEquals(ScrollingFabState.Collapsed, tracker.update(FabScrollPosition(0, 79, atStart = false, nearEnd = true)))
        assertEquals(ScrollingFabState.Expanded, tracker.update(FabScrollPosition(0, 31, atStart = false, nearEnd = true)))
    }

    @Test
    fun shortAndEmptyListsStayExpanded() {
        val tracker = FabScrollTracker(48f)
        repeat(2) {
            assertEquals(ScrollingFabState.Expanded, tracker.update(FabScrollPosition(0, 0, atStart = true, nearEnd = true)))
        }
        assertEquals(ScrollingFabState.Expanded, tracker.update(FabScrollPosition(0, 20, atStart = false, nearEnd = true)))
    }

    @Test
    fun directionChangesResetTheDistanceAndTheTopRestoresTheLabel() {
        val tracker = FabScrollTracker(48f)
        tracker.update(FabScrollPosition(0, 0, atStart = true, nearEnd = false))
        tracker.update(FabScrollPosition(0, 40, atStart = false, nearEnd = false))
        tracker.update(FabScrollPosition(0, 30, atStart = false, nearEnd = false))
        assertEquals(ScrollingFabState.Expanded, tracker.update(FabScrollPosition(0, 70, atStart = false, nearEnd = false)))
        assertEquals(ScrollingFabState.Collapsed, tracker.update(FabScrollPosition(0, 78, atStart = false, nearEnd = false)))
        assertEquals(ScrollingFabState.Expanded, tracker.update(FabScrollPosition(0, 0, atStart = true, nearEnd = true)))
    }

    @Test
    fun crossingRowsWorksForListsAndGrids() {
        val tracker = FabScrollTracker(48f)
        tracker.update(FabScrollPosition(0, 0, atStart = true, nearEnd = false))
        assertEquals(ScrollingFabState.Collapsed, tracker.update(FabScrollPosition(3, 0, atStart = false, nearEnd = false)))
        assertEquals(ScrollingFabState.Hidden, tracker.update(FabScrollPosition(6, 0, atStart = false, nearEnd = true)))
        assertEquals(ScrollingFabState.Expanded, tracker.update(FabScrollPosition(3, 0, atStart = false, nearEnd = true)))
    }
}
