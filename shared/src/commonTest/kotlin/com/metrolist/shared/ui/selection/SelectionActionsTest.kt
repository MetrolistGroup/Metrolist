package com.metrolist.shared.ui.selection

import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionActionsTest {
    @Test
    fun resizingPreservesEveryActionInOrderAndReservesOverflowSpace() {
        val primary = listOf("play", "next", "playlist", "download", "remove")
        val overflow = listOf("queue", "shuffle", "like")
        for (slots in -1..10) {
            val result = splitSelectionActions(primary, overflow, slots)
            assertEquals(primary + overflow, result.primary + result.overflow)
            assertEquals((slots - 1).coerceIn(0, primary.size), result.primary.size)
        }
    }

    @Test
    fun overflowAppearsOnlyWhenNeededIncludingAtExactCapacity() {
        val actions = listOf("play", "remove")
        assertEquals(SelectionActions(actions, emptyList()), splitSelectionActions(actions, emptyList(), 2))
        assertEquals(SelectionActions(emptyList(), actions), splitSelectionActions(actions, emptyList(), 1))
        assertEquals(SelectionActions(emptyList(), emptyList()), splitSelectionActions(emptyList<String>(), emptyList(), 0))
    }

    @Test
    fun duplicateActionsArePreservedAcrossTheOverflowBoundary() {
        val result = splitSelectionActions(listOf(1, 1, 2), listOf(2), 3)
        assertEquals(listOf(1, 1), result.primary)
        assertEquals(listOf(2, 2), result.overflow)
    }
}
