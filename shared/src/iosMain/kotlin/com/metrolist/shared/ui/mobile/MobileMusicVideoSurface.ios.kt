package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.enter_fullscreen
import com.metrolist.shared.playback.IosMusicVideoSurfacePlayer
import com.metrolist.shared.playback.IosPlaybackController
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.LocalPlaybackController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.compose.resources.stringResource
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
internal actual fun MobileMusicVideoSurface(
    modifier: Modifier,
    cornerRadius: Dp,
    fillContainer: Boolean,
    showControls: Boolean,
    onFullscreenClick: (() -> Unit)?,
) {
    val controller = LocalPlaybackController.current
    val iosController = controller as? IosPlaybackController
    val state by controller.state.collectAsState()
    val resource = state.videoPlaybackResource
    val url = resource?.videoUrl?.takeIf { state.isVideoModeActive }
    if (url == null || resource == null) {
        Box(modifier)
        return
    }

    val video =
        remember(controller, url, resource.headers) {
            iosController?.musicVideoSurfacePlayer(url, resource.headers) ?: IosMusicVideoSurfacePlayer(url, resource.headers)
        }
    var firstFrameRendered by remember(video) { mutableStateOf(video.ready) }
    var foreground by remember {
        mutableStateOf(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
    }
    DisposableEffect(video) {
        val surfaceGeneration = video.attach()
        val center = NSNotificationCenter.defaultCenter
        val activeObserver =
            center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
                foreground = true
            }
        val inactiveObserver =
            center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
                foreground = false
            }
        onDispose {
            center.removeObserver(activeObserver)
            center.removeObserver(inactiveObserver)
            if (iosController == null) video.close() else video.detach(surfaceGeneration)
        }
    }
    LaunchedEffect(video, fillContainer) { video.setFillContainer(fillContainer) }
    LaunchedEffect(
        video,
        state.positionMs,
        state.isPlaying,
        state.playbackSpeed,
        state.isLoading,
        state.isVideoModeActive,
        state.videoPositionMap,
        foreground,
    ) {
        val positionMap = state.videoPositionMap
        val targetPosition = positionMap?.mapPosition(state.positionMs) ?: state.positionMs
        val positionMapped = positionMap?.containsSourcePosition(state.positionMs) ?: true
        video.synchronize(
            targetPosition,
            state.isPlaying && !state.isLoading && state.isVideoModeActive && foreground && positionMapped,
            state.playbackSpeed,
            foreground,
        )
    }
    LaunchedEffect(video) {
        val started = TimeSource.Monotonic.markNow()
        while (isActive) {
            if (video.hasFailed(started.elapsedNow().inWholeMilliseconds)) {
                controller.reportVideoPlaybackFailure()
                break
            }
            if (foreground) video.applyPendingSeek()
            if (video.ready) firstFrameRendered = true
            delay(250)
        }
    }

    Box(modifier) {
        key(video) {
            UIKitView(
                factory = {
                    object : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
                        init {
                            clipsToBounds = true
                            layer.addSublayer(video.layer)
                        }

                        override fun layoutSubviews() {
                            super.layoutSubviews()
                            CATransaction.begin()
                            CATransaction.setDisableActions(true)
                            video.layer.frame = bounds
                            CATransaction.commit()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius)),
                properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
            )
        }
        if (!firstFrameRendered) {
            AsyncImage(
                url = state.currentSong?.thumbnail,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius)),
                contentScale = ContentScale.Crop,
                previewUrl = null,
            )
        }
        if (showControls && onFullscreenClick != null) {
            IconButton(
                onClick = onFullscreenClick,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Icon(MetrolistIcons.Default.Fullscreen, contentDescription = stringResource(Res.string.enter_fullscreen))
            }
        }
    }
}
