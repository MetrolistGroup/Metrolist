package com.metrolist.shared.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.metrolist.shared.settings.Settings

enum class HapticEvent {
    PRESS,
    TOGGLE_ON,
    TOGGLE_OFF,
    CONFIRM,
    REJECT,
    DRAG_START,
    DRAG_SNAP,
    LONG_PRESS,
    SEGMENT_TICK,
    DICE_ROLL,
}

class MetrolistHaptics(
    private val enabled: () -> Boolean,
    private val platformFeedback: (HapticEvent) -> Unit,
) {
    fun perform(event: HapticEvent) {
        if (enabled()) platformFeedback(event)
    }

    fun toggle(checked: Boolean) = perform(if (checked) HapticEvent.TOGGLE_ON else HapticEvent.TOGGLE_OFF)
}

@Composable
fun rememberMetrolistHaptics(): MetrolistHaptics {
    val platformFeedback = rememberPlatformHaptics()
    return remember(platformFeedback) { MetrolistHaptics({ Settings.shared.enableHapticFeedback }, platformFeedback) }
}

@Composable
internal expect fun rememberPlatformHaptics(): (HapticEvent) -> Unit

@Composable
fun MetrolistHapticsProvider(content: @Composable () -> Unit) {
    val haptics = rememberMetrolistHaptics()
    val feedback =
        remember(haptics) {
            object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    haptics.perform(hapticFeedbackType.toHapticEvent())
                }
            }
        }
    CompositionLocalProvider(LocalHapticFeedback provides feedback, content = content)
}

internal fun HapticFeedbackType.toHapticEvent(): HapticEvent =
    when (this) {
        HapticFeedbackType.LongPress -> HapticEvent.LONG_PRESS

        HapticFeedbackType.ToggleOn -> HapticEvent.TOGGLE_ON

        HapticFeedbackType.ToggleOff -> HapticEvent.TOGGLE_OFF

        HapticFeedbackType.Confirm -> HapticEvent.CONFIRM

        HapticFeedbackType.Reject -> HapticEvent.REJECT

        HapticFeedbackType.GestureThresholdActivate -> HapticEvent.DRAG_SNAP

        HapticFeedbackType.TextHandleMove,
        HapticFeedbackType.SegmentTick,
        HapticFeedbackType.SegmentFrequentTick,
        -> HapticEvent.SEGMENT_TICK

        else -> HapticEvent.PRESS
    }
