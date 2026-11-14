package com.metrolist.desktop.window

import androidx.compose.animation.core.FloatSpringSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonotonicRenderTimeTest {
    @Test
    fun resizeFramesCannotSendAnAnimationBackBeforeItsStart() {
        val clock = MonotonicRenderTime()
        val start = clock.next(14_400_000_000_000L)
        val spring = FloatSpringSpec()
        for (frame in listOf(start + 16_000_000L, 9_108_094_976L, start + 32_000_000L, 9_120_000_000L)) {
            val elapsed = clock.next(frame) - start
            assertTrue(elapsed >= 0)
            assertTrue(spring.getValueFromNanos(elapsed, 0.65f, 0.25f, 0f).isFinite())
            assertTrue(spring.getVelocityFromNanos(elapsed, 0.65f, 0.25f, 0f).isFinite())
        }
        assertEquals(start + 48_000_000L, clock.next(start + 48_000_000L))
    }

    @Test
    fun clocksBelongToTheirRenderingLayerAndPreserveNormalFrameTimes() {
        val first = MonotonicRenderTime()
        val second = MonotonicRenderTime()
        assertEquals(100L, first.next(100L))
        assertEquals(120L, first.next(120L))
        assertEquals(120L, first.next(120L))
        assertEquals(5L, second.next(5L))
        assertEquals(120L, first.next(0L))
    }
}
