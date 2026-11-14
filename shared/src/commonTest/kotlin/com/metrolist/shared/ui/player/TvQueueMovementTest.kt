package com.metrolist.shared.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvQueueMovementTest {
    @Test
    fun movementFollowsTheGrabbedOccurrenceAcrossConsecutiveMoves() {
        val movement = TvQueueMovement().grab(12L)
        assertEquals(13L, movement.target(listOf(11L, 12L, 13L, 14L), 1))
        assertEquals(14L, movement.target(listOf(11L, 13L, 12L, 14L), 1))
        assertEquals(13L, movement.target(listOf(11L, 13L, 12L, 14L), -1))
    }

    @Test
    fun placingAllowsPickingAnotherOccurrenceWithoutLeavingMovementMode() {
        val placed = TvQueueMovement().grab(12L).place()
        assertTrue(placed.enabled)
        assertNull(placed.target(listOf(11L, 12L, 13L), 1))
        val next = placed.grab(13L)
        assertEquals(12L, next.target(listOf(11L, 12L, 13L), -1))
        assertEquals(13L, next.grabbedEntryKey)
    }

    @Test
    fun boundariesAndHorizontalDirectionsCannotMoveOrWrapTheQueue() {
        val keys = listOf(11L, 12L, 13L)
        assertNull(TvQueueMovement().grab(11L).target(keys, -1))
        assertNull(TvQueueMovement().grab(13L).target(keys, 1))
        assertNull(TvQueueMovement().grab(12L).target(keys, 0))
        assertNull(TvQueueMovement().grab(12L).target(keys, 2))
        assertNull(TvQueueMovement().grab(11L).target(listOf(11L), 1))
    }

    @Test
    fun externalQueueChangesDoNotRetargetTheGrabbedOccurrence() {
        val movement = TvQueueMovement().grab(12L)
        assertEquals(movement, movement.reconcile(listOf(15L, 13L, 12L, 11L)))
        assertEquals(11L, movement.target(listOf(15L, 13L, 12L, 11L), 1))
        assertNull(movement.target(listOf(11L, 13L), 1))
        val removed = movement.reconcile(listOf(11L, 13L))
        assertTrue(removed.enabled)
        assertNull(removed.grabbedEntryKey)
        assertNull(movement.reconcile(emptyList()).grabbedEntryKey)
        assertFalse(TvQueueMovement().enabled)
    }
}
