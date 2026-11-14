package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification

@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun rememberVoiceOverEnabled(): Boolean {
    var enabled by remember { mutableStateOf(UIAccessibilityIsVoiceOverRunning()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer =
            center.addObserverForName(UIAccessibilityVoiceOverStatusDidChangeNotification, null, NSOperationQueue.mainQueue) {
                enabled = UIAccessibilityIsVoiceOverRunning()
            }
        enabled = UIAccessibilityIsVoiceOverRunning()
        onDispose { center.removeObserver(observer) }
    }
    return enabled
}
