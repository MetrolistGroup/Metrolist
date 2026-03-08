@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.metrolist.desktop.input

import androidx.compose.foundation.gestures.ScrollConfig
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopScrollConfigTest {
    @Test
    fun fractionalWheelRotationsArePrecise() {
        for (rotation in listOf(-1.25, -0.25, 0.25, 1.25)) {
            assertTrue(isPreciseWheelRotation(rotation, rotation.toInt()))
        }
    }

    @Test
    fun discreteWheelRotationsAndRoundingNoiseKeepSmoothing() {
        for (rotation in listOf(-3.0, -1.0001, -1.0, 0.0, 1.0, 1.0001, 3.0)) {
            assertFalse(isPreciseWheelRotation(rotation, rotation.toInt()))
        }
    }

    @Test
    fun platformPrecisionClassificationIsPreservedWithoutNativeWheelData() {
        for (precise in listOf(false, true)) {
            val config = PreciseWheelScrollConfig(PlatformConfig(precise = precise))
            assertEquals(precise, config.isPreciseWheelScroll(PointerEvent(emptyList())))
        }
    }

    @Test
    fun platformDistancesAndSmoothScrollingPreferenceArePreserved() {
        val event = PointerEvent(emptyList())
        val bounds = IntSize(500, 300)
        val density = Density(2f)
        for (smooth in listOf(false, true)) {
            val platform = PlatformConfig(isSmoothScrollingEnabled = smooth)
            val config = PreciseWheelScrollConfig(platform)
            assertEquals(smooth, config.isSmoothScrollingEnabled)
            assertEquals(
                with(platform) { density.calculateMouseWheelScroll(event, bounds) },
                with(config) { density.calculateMouseWheelScroll(event, bounds) },
            )
        }
    }

    private class PlatformConfig(
        override val isSmoothScrollingEnabled: Boolean = true,
        private val precise: Boolean = false,
    ) : ScrollConfig {
        override fun isPreciseWheelScroll(event: PointerEvent) = precise

        override fun Density.calculateMouseWheelScroll(
            event: PointerEvent,
            bounds: IntSize,
        ) = Offset(bounds.width * density, bounds.height * density)
    }
}
