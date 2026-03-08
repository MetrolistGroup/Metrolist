package com.metrolist.shared.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

@Composable
internal actual fun rememberPlatformHaptics(): (HapticEvent) -> Unit =
    remember {
        val light = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
        val medium = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
        val selection = UISelectionFeedbackGenerator()
        val notification = UINotificationFeedbackGenerator()
        val feedback: (HapticEvent) -> Unit = { event ->
            when (event) {
                HapticEvent.PRESS, HapticEvent.TOGGLE_OFF -> light.impactOccurred()
                HapticEvent.TOGGLE_ON, HapticEvent.DRAG_START, HapticEvent.LONG_PRESS, HapticEvent.DICE_ROLL -> medium.impactOccurred()
                HapticEvent.DRAG_SNAP, HapticEvent.SEGMENT_TICK -> selection.selectionChanged()
                HapticEvent.CONFIRM -> notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                HapticEvent.REJECT -> notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            }
        }
        feedback
    }
