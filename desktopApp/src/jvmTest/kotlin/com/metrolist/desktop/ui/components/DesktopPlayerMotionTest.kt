package com.metrolist.desktop.ui.components

import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPlayerMotionTest {
    @Test
    fun artworkStaysBetweenItsCompactAndExpandedBoundsInBothDirections() {
        val expanded = Rect(600f, 120f, 1156f, 676f)
        for (compact in listOf(Rect(24f, 880f, 72f, 928f), Rect(480f, 16f, 512f, 48f), Rect(24f, 880f, 102f, 924f))) {
            for ((start, end) in listOf(compact to expanded, expanded to compact)) {
                assertNoOvershoot(TargetBasedAnimation(DesktopPlayerMotion.artworkBounds, Rect.VectorConverter, start, end), start, end)
            }
        }
    }

    @Test
    fun reversingAFlightDoesNotCarryItsVelocityPastTheNewTarget() {
        val compact = Rect(24f, 880f, 72f, 928f)
        val expanded = Rect(600f, 120f, 1156f, 676f)
        val opening = TargetBasedAnimation(DesktopPlayerMotion.artworkBounds, Rect.VectorConverter, compact, expanded)
        val reverseAt = 120_000_000L
        val start = opening.getValueFromNanos(reverseAt)
        val closing =
            TargetBasedAnimation(
                DesktopPlayerMotion.artworkBounds,
                Rect.VectorConverter,
                start,
                compact,
                opening.getVelocityVectorFromNanos(reverseAt),
            )
        assertNoOvershoot(closing, start, compact)
    }

    private fun assertNoOvershoot(
        animation: TargetBasedAnimation<Rect, AnimationVector4D>,
        start: Rect,
        end: Rect,
    ) {
        assertTrue(animation.durationNanos <= 400_000_000L)
        val initial = listOf(start.left, start.top, start.right, start.bottom)
        val target = listOf(end.left, end.top, end.right, end.bottom)
        for (millis in 0..400) {
            val bounds = animation.getValueFromNanos(millis * 1_000_000L)
            val value = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
            for (axis in 0..3) {
                assertTrue(value[axis] in minOf(initial[axis], target[axis])..maxOf(initial[axis], target[axis]))
            }
        }
        assertEquals(end, animation.getValueFromNanos(animation.durationNanos))
    }
}
