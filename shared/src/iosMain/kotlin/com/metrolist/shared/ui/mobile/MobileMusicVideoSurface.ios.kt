package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import com.metrolist.shared.generated.resources.enter_pip
import com.metrolist.shared.playback.IosMusicVideoSurfacePlayer
import com.metrolist.shared.playback.IosPlaybackController
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.player.LocalPlayerPositionActive
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

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun MobileMusicVideoSurface(
    modifier: Modifier,
    cornerRadius: Dp,
    fillContainer: Boolean,
    showControls: Boolean,
    onFullscreenClick: (() -> Unit)?,
    onPiPClick: (() -> Unit)?,
) {
    val controller = LocalPlaybackController.current
    val iosController = controller as? IosPlaybackController
    val surfaceVisible = LocalPlayerPositionActive.current
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
    LaunchedEffect(iosController, video, url) {
        iosController?.registerPictureInPictureSurface(video, url)
    }
    DisposableEffect(iosController, video, url) {
        onDispose { iosController?.unregisterPictureInPictureSurface(video, url) }
    }
    var firstFrameRendered by remember(video) { mutableStateOf(video.ready && video.positionSynchronized) }
    var foreground by remember {
        mutableStateOf(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
    }
    val pipActive by video.pictureInPictureActive.collectAsState()
    val surfaceActive = foreground || pipActive
    DisposableEffect(video) {
        val surfaceGeneration = video.attach()
        video.setForeground(foreground)
        val center = NSNotificationCenter.defaultCenter
        val activeObserver =
            center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
                video.setForeground(true)
                foreground = true
                firstFrameRendered = false
            }
        val inactiveObserver =
            center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
                video.setForeground(false)
                foreground = false
                firstFrameRendered = false
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
        surfaceActive,
        surfaceVisible,
        pipActive,
    ) {
        // While PiP is active the bridge owns the retained player's synchronization; synchronizing here
        // as well races its gap handling and can pause or seek the audible PiP transport.
        if (pipActive) return@LaunchedEffect
        val positionMap = state.videoPositionMap
        val targetPosition = positionMap?.mapPosition(state.positionMs) ?: state.positionMs
        val positionMapped = positionMap?.containsSourcePosition(state.positionMs) ?: true
        video.synchronize(
            targetPosition,
            state.isPlaying && !state.isLoading && state.isVideoModeActive && surfaceActive && positionMapped,
            state.playbackSpeed,
            surfaceVisible,
        )
    }
    LaunchedEffect(video, foreground, surfaceVisible) {
        while (isActive) {
            if (video.hasFailed()) {
                controller.reportVideoPlaybackFailure()
                break
            }
            if (video.driftRecoveryFailed) {
                controller.reportVideoPlaybackFailure()
                break
            }
            if (surfaceActive && surfaceVisible) video.applyPendingSeek()
            if (surfaceActive && surfaceVisible && video.ready && video.positionSynchronized) {
                firstFrameRendered = true
            } else {
                firstFrameRendered = false
            }
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
        if (showControls && (onPiPClick != null || onFullscreenClick != null)) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (onPiPClick != null) {
                    IconButton(onClick = onPiPClick) {
                        Icon(MetrolistIcons.Filled.PictureInPicture, contentDescription = stringResource(Res.string.enter_pip))
                    }
                }
                if (onFullscreenClick != null) {
                    IconButton(onClick = onFullscreenClick) {
                        Icon(MetrolistIcons.Default.Fullscreen, contentDescription = stringResource(Res.string.enter_fullscreen))
                    }
                }
            }
        }
    }
}
