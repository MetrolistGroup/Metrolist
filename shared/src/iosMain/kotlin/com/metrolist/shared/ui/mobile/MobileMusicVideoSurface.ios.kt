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
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.LocalPlaybackController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.status
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIView
import kotlin.math.abs

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun MobileMusicVideoSurface(
    modifier: Modifier,
    cornerRadius: Dp,
    showControls: Boolean,
    onFullscreenClick: (() -> Unit)?,
) {
    val controller = LocalPlaybackController.current
    val state by controller.state.collectAsState()
    val resource = state.videoPlaybackResource
    val url = resource?.videoUrl?.takeIf { state.isVideoModeActive }
    if (url == null || resource == null) {
        Box(modifier)
        return
    }

    val video = remember(url, resource.headers) { IosMusicVideoSurfacePlayer(url, resource.headers) }
    var firstFrameRendered by remember(video) { mutableStateOf(false) }
    var foreground by remember {
        mutableStateOf(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
    }
    DisposableEffect(video) {
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
            video.close()
        }
    }
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
        )
    }
    LaunchedEffect(video) {
        while (isActive) {
            if (video.failed) {
                controller.reportVideoPlaybackFailure()
                break
            }
            video.applyPendingSeek()
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
                Icon(MetrolistIcons.Default.Fullscreen, contentDescription = null)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosMusicVideoSurfacePlayer(
    url: String,
    headers: Map<String, String>,
) {
    private val player = AVPlayer()
    val layer = AVPlayerLayer.playerLayerWithPlayer(player)
    private var closed = false
    private var seekInFlight = false
    private var latestTargetPositionMs = 0L
    private var failureObserver: Any? = null

    init {
        player.muted = true
        layer.videoGravity = AVLayerVideoGravityResizeAspect
        val nativeUrl = NSURL.URLWithString(url)
        if (nativeUrl != null) {
            val asset =
                AVURLAsset(
                    uRL = nativeUrl,
                    options = headers.takeIf { it.isNotEmpty() }?.let { mapOf("AVURLAssetHTTPHeaderFieldsKey" to it) },
                )
            val item = AVPlayerItem(asset = asset)
            player.replaceCurrentItemWithPlayerItem(item)
            failureObserver =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    AVPlayerItemFailedToPlayToEndTimeNotification,
                    item,
                    NSOperationQueue.mainQueue,
                ) {
                    didFail = true
                }
        }
    }

    val failed: Boolean
        get() = closed || didFail || player.currentItem == null || player.currentItem?.status == AVPlayerItemStatusFailed

    private var didFail = false

    val ready: Boolean
        get() = !closed && layer.readyForDisplay

    fun synchronize(
        positionMs: Long,
        playing: Boolean,
        playbackSpeed: Float,
    ) {
        if (closed) return
        latestTargetPositionMs = positionMs.coerceAtLeast(0L)
        applyPendingSeek()
        if (playing && player.rate != playbackSpeed) {
            player.rate = playbackSpeed
        } else if (!playing && player.rate != 0f) {
            player.pause()
        }
    }

    fun applyPendingSeek() {
        if (closed || seekInFlight || player.currentItem?.status != AVPlayerItemStatusReadyToPlay) return
        val currentMs = (CMTimeGetSeconds(player.currentTime()) * 1000.0).toLong()
        val targetPositionMs = latestTargetPositionMs
        if (abs(currentMs - targetPositionMs) <= 750L) return
        seekInFlight = true
        val target = CMTimeMakeWithSeconds(targetPositionMs / 1000.0, 600)
        val zero = CMTimeMakeWithSeconds(0.0, 600)
        player.seekToTime(target, toleranceBefore = zero, toleranceAfter = zero) { completed ->
            seekInFlight = false
            if (completed && targetPositionMs != latestTargetPositionMs) applyPendingSeek()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        failureObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        failureObserver = null
        player.pause()
        layer.player = null
        player.replaceCurrentItemWithPlayerItem(null)
    }
}
