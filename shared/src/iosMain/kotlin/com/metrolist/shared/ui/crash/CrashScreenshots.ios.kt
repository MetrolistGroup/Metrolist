package com.metrolist.shared.ui.crash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationUserDidTakeScreenshotNotification

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun ObserveCrashScreenshots(onScreenshot: () -> Unit) {
    val controller = LocalUIViewController.current
    val currentCallback = rememberUpdatedState(onScreenshot)
    DisposableEffect(controller) {
        val center = NSNotificationCenter.defaultCenter
        val observer =
            center.addObserverForName(UIApplicationUserDidTakeScreenshotNotification, null, NSOperationQueue.mainQueue) {
                if (controller.view.window != null) currentCallback.value()
            }
        onDispose { center.removeObserver(observer) }
    }
}
