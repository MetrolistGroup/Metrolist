@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.metrolist.desktop.input

import androidx.compose.foundation.gestures.LocalScrollConfig
import androidx.compose.foundation.gestures.ScrollConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.abs

@Composable
internal fun desktopScrollConfig(): ProvidedValue<*> {
    val platformConfig = LocalScrollConfig.current
    val config = remember(platformConfig) { PreciseWheelScrollConfig(platformConfig) }
    return LocalScrollConfig provides config
}

// Foundation's internal config in 1.13.0-beta02 treats every Windows wheel event as non-precise.
internal class PreciseWheelScrollConfig(
    private val platformConfig: ScrollConfig,
) : ScrollConfig by platformConfig {
    override fun isPreciseWheelScroll(event: PointerEvent): Boolean {
        val wheel = event.awtEventOrNull as? MouseWheelEvent
        return (wheel != null && isPreciseWheelRotation(wheel.preciseWheelRotation, wheel.wheelRotation)) ||
            platformConfig.isPreciseWheelScroll(event)
    }
}

internal fun isPreciseWheelRotation(
    preciseRotation: Double,
    rotation: Int,
): Boolean = abs(preciseRotation - rotation) > 0.001
