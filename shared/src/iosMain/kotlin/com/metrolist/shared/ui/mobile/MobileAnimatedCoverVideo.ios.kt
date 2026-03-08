package com.metrolist.shared.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.metrolist.shared.playback.IosAnimatedCoverPlayer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIView
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun MobileAnimatedCoverVideo(
    url: String,
    headers: Map<String, String>,
    playing: Boolean,
    onError: (() -> Unit)?,
    onReadyChanged: ((Boolean) -> Unit)?,
    modifier: Modifier,
) {
    val cover = remember(url, headers) { IosAnimatedCoverPlayer(url, headers) }
    val currentOnError by rememberUpdatedState(onError)
    val currentOnReadyChanged by rememberUpdatedState(onReadyChanged)
    var foreground by remember {
        mutableStateOf(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
    }

    DisposableEffect(cover) {
        val center = NSNotificationCenter.defaultCenter
        val activeObserver =
            center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
                foreground = true
            }
        val inactiveObserver =
            center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
                cover.setPlaying(false)
                foreground = false
            }
        onDispose {
            center.removeObserver(activeObserver)
            center.removeObserver(inactiveObserver)
            cover.close()
            currentOnReadyChanged?.invoke(false)
        }
    }
    LaunchedEffect(cover, playing, foreground) {
        cover.setPlaying(playing && foreground)
    }
    LaunchedEffect(cover) {
        val started = TimeSource.Monotonic.markNow()
        var wasReady = false
        while (isActive) {
            if (cover.hasFailed(started.elapsedNow().inWholeMilliseconds)) {
                cover.close()
                currentOnReadyChanged?.invoke(false)
                currentOnError?.invoke()
                break
            }
            val ready = cover.ready
            if (ready != wasReady) {
                wasReady = ready
                currentOnReadyChanged?.invoke(ready)
            }
            delay(100)
        }
    }
    key(cover) {
        UIKitView(
            factory = {
                object : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
                    init {
                        clipsToBounds = true
                        layer.addSublayer(cover.layer)
                    }

                    override fun layoutSubviews() {
                        super.layoutSubviews()
                        CATransaction.begin()
                        CATransaction.setDisableActions(true)
                        cover.layer.frame = bounds
                        CATransaction.commit()
                    }
                }
            },
            modifier = modifier,
            properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
        )
    }
}
