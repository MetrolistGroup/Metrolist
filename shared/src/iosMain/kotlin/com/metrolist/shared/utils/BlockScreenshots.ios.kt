package com.metrolist.shared.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIScreenCapturedDidChangeNotification
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun BlockScreenshots() {
    val controller = LocalUIViewController.current
    DisposableEffect(controller) {
        val view = controller.view
        val cover =
            UIView(frame = view.bounds).apply {
                backgroundColor = UIColor.blackColor
                autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
            }
        var active = UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

        fun updateCover() {
            val screen = view.window?.screen ?: UIScreen.mainScreen
            cover.hidden = active && !screen.captured
            if (!cover.hidden) view.bringSubviewToFront(cover)
        }
        view.addSubview(cover)
        updateCover()
        val center = NSNotificationCenter.defaultCenter
        val observers =
            listOf(
                center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
                    active = false
                    updateCover()
                },
                center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
                    active = true
                    updateCover()
                },
                center.addObserverForName(UIScreenCapturedDidChangeNotification, null, NSOperationQueue.mainQueue) {
                    updateCover()
                },
            )
        onDispose {
            observers.forEach(center::removeObserver)
            cover.removeFromSuperview()
        }
    }
}
