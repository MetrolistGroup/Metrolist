package com.metrolist.shared.ui.utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlin.test.Test
import kotlin.test.assertEquals

class MouseDragScrollVelocityTest {
    @Test
    fun releaseVelocityMatchesTheDragAxisAndScrollDirection() {
        val tracker = VelocityTracker()
        repeat(6) { index ->
            tracker.addPosition(index * 10L, Offset(index * 12f, index * -8f))
        }
        val velocity = tracker.calculateVelocity()

        assertEquals(-1200f, velocity.mouseDragScrollVelocity(horizontal = true, reverseDirection = false), 1f)
        assertEquals(1200f, velocity.mouseDragScrollVelocity(horizontal = true, reverseDirection = true), 1f)
        assertEquals(800f, velocity.mouseDragScrollVelocity(horizontal = false, reverseDirection = false), 1f)
        assertEquals(-800f, velocity.mouseDragScrollVelocity(horizontal = false, reverseDirection = true), 1f)
    }

    @Test
    fun reverseDragProducesTheOppositeReleaseVelocity() {
        val velocity = Velocity(-1200f, 800f)

        assertEquals(1200f, velocity.mouseDragScrollVelocity(horizontal = true, reverseDirection = false))
        assertEquals(-1200f, velocity.mouseDragScrollVelocity(horizontal = true, reverseDirection = true))
        assertEquals(-800f, velocity.mouseDragScrollVelocity(horizontal = false, reverseDirection = false))
        assertEquals(800f, velocity.mouseDragScrollVelocity(horizontal = false, reverseDirection = true))
    }

    @Test
    fun releasingAfterAPauseDoesNotReuseTheDragVelocity() {
        val tracker = VelocityTracker()
        repeat(6) { index ->
            tracker.addPosition(index * 10L, Offset(index * 12f, index * -8f))
        }
        tracker.addPosition(200L, Offset(60f, -40f))
        val velocity = tracker.calculateVelocity()

        assertEquals(0f, velocity.mouseDragScrollVelocity(horizontal = true, reverseDirection = false), 0f)
        assertEquals(0f, velocity.mouseDragScrollVelocity(horizontal = false, reverseDirection = true), 0f)
    }
}
