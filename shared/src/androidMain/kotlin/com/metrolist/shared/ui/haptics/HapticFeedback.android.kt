package com.metrolist.shared.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat

@Composable
internal actual fun rememberPlatformHaptics(): (HapticEvent) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { event ->
            val feedback =
                when (event) {
                    HapticEvent.PRESS -> HapticFeedbackConstantsCompat.VIRTUAL_KEY
                    HapticEvent.TOGGLE_ON -> HapticFeedbackConstantsCompat.TOGGLE_ON
                    HapticEvent.TOGGLE_OFF -> HapticFeedbackConstantsCompat.TOGGLE_OFF
                    HapticEvent.CONFIRM -> HapticFeedbackConstantsCompat.CONFIRM
                    HapticEvent.REJECT -> HapticFeedbackConstantsCompat.REJECT
                    HapticEvent.DRAG_START -> HapticFeedbackConstantsCompat.DRAG_START
                    HapticEvent.DRAG_SNAP -> HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE
                    HapticEvent.LONG_PRESS -> HapticFeedbackConstantsCompat.LONG_PRESS
                    HapticEvent.SEGMENT_TICK -> HapticFeedbackConstantsCompat.SEGMENT_TICK
                    HapticEvent.DICE_ROLL -> HapticFeedbackConstantsCompat.CONTEXT_CLICK
                }
            ViewCompat.performHapticFeedback(view, feedback)
        }
    }
}
