package com.metrolist.shared.playback

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentTime
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVKit.AVPictureInPictureController
import platform.AVKit.AVPictureInPictureControllerDelegateProtocol
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.math.abs

@OptIn(ExperimentalForeignApi::class)
internal fun iosPictureInPictureSupported(): Boolean = AVPictureInPictureController.isPictureInPictureSupported()

@OptIn(ExperimentalForeignApi::class)
internal fun requestIosPictureInPicture(
    controller: IosPlaybackController,
    isPlaying: Boolean,
    hasVideo: Boolean,
) {
    if (!isPlaying || !hasVideo || !iosPictureInPictureSupported()) return
    val surface = controller.pictureInPictureSurface() ?: return
    IosPictureInPictureBridge.request(controller, surface)
}

@OptIn(ExperimentalForeignApi::class)
private object IosPictureInPictureBridge : NSObject(), AVPictureInPictureControllerDelegateProtocol {
    private const val SEEK_TOLERANCE_MS = 5_000L
    private const val SEEK_JUMP_TOLERANCE_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: IosPlaybackController? = null
    private var surface: IosMusicVideoSurfacePlayer? = null
    private var pictureInPictureController: AVPictureInPictureController? = null
    private var readinessObservation: IosKeyValueObservation? = null
    private var transportObservation: IosKeyValueObservation? = null
    private var observedPlayer: AVPlayer? = null
    private var timeObserver: Any? = null
    private var pendingRequest = false
    private var forwardingPlayPause = false
    private var forwardingSeek = false
    private var lastSurfacePositionMs: Long? = null

    fun request(
        playbackController: IosPlaybackController,
        nextSurface: IosMusicVideoSurfacePlayer,
    ) {
        controller = playbackController
        surface = nextSurface
        playbackController.pictureInPictureSurfaceChanged = { retryPendingRequest() }
        val layer = nextSurface.layer
        if (pictureInPictureController?.playerLayer !== layer) {
            pictureInPictureController?.stopPictureInPicture()
            readinessObservation?.close()
            readinessObservation = null
            pictureInPictureController =
                AVPictureInPictureController(playerLayer = layer).also { pip ->
                    pip.delegate = this
                    readinessObservation =
                        IosKeyValueObservation(pip, listOf("isPictureInPicturePossible"), scope) { retryPendingRequest() }
                }
        }
        pendingRequest = true
        retryPendingRequest()
    }

    internal fun retryPendingRequest() {
        if (!pendingRequest) return
        val playbackController = controller ?: return
        val surfacePlayer = surface ?: return
        val pip = pictureInPictureController ?: return
        if (!pip.isPictureInPicturePossible() || pip.isPictureInPictureActive()) return
        pendingRequest = false
        surfacePlayer.pictureInPictureActive.value = true
        startTransportObservation(playbackController, surfacePlayer)
        pip.startPictureInPicture()
    }

    override fun pictureInPictureControllerDidStartPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        surface?.pictureInPictureActive?.value = true
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError: NSError,
    ) {
        pendingRequest = false
        surface?.pictureInPictureActive?.value = false
        stopTransportObservation()
    }

    override fun pictureInPictureControllerWillStopPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        pendingRequest = false
        surface?.pictureInPictureActive?.value = false
        stopTransportObservation()
    }

    override fun pictureInPictureControllerDidStopPictureInPicture(pictureInPictureController: AVPictureInPictureController) {
        surface?.pictureInPictureActive?.value = false
        stopTransportObservation()
    }

    private fun startTransportObservation(
        playbackController: IosPlaybackController,
        surfacePlayer: IosMusicVideoSurfacePlayer,
    ) {
        stopTransportObservation()
        val player = surfacePlayer.player
        observedPlayer = player
        lastSurfacePositionMs = player.positionMs()
        transportObservation = IosKeyValueObservation(player, listOf("rate"), scope) { onRateChanged(playbackController, surfacePlayer) }
        timeObserver =
            player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(1.0, 600), null) {
                onTransportTick(playbackController, surfacePlayer)
            }
    }

    private fun stopTransportObservation() {
        transportObservation?.close()
        transportObservation = null
        timeObserver?.let { observedPlayer?.removeTimeObserver(it) }
        timeObserver = null
        observedPlayer = null
        forwardingPlayPause = false
        forwardingSeek = false
        lastSurfacePositionMs = null
    }

    private fun onRateChanged(
        playbackController: IosPlaybackController,
        surfacePlayer: IosMusicVideoSurfacePlayer,
    ) {
        if (!surfacePlayer.pictureInPictureActive.value || forwardingPlayPause) return
        val snapshot = playbackController.state.value
        if (snapshot.isLoading) return
        val surfacePlaying = surfacePlayer.player.rate != 0f
        if (surfacePlaying == snapshot.isPlaying) return
        forwardingPlayPause = true
        playbackController.togglePlayPause()
        scope.launch {
            playbackController.state.first { it.isPlaying == surfacePlaying }
            forwardingPlayPause = false
        }
    }

    private fun onTransportTick(
        playbackController: IosPlaybackController,
        surfacePlayer: IosMusicVideoSurfacePlayer,
    ) {
        if (!surfacePlayer.pictureInPictureActive.value || forwardingSeek) return
        val snapshot = playbackController.state.value
        val surfacePositionMs = surfacePlayer.player.positionMs()
        val expectedSurfacePositionMs = snapshot.videoPositionMap?.mapPosition(snapshot.positionMs) ?: snapshot.positionMs
        val previousPositionMs = lastSurfacePositionMs
        lastSurfacePositionMs = surfacePositionMs
        if (abs(surfacePositionMs - expectedSurfacePositionMs) <= SEEK_TOLERANCE_MS) return
        val jumpMs = previousPositionMs?.let { abs(surfacePositionMs - it) } ?: 0L
        if (jumpMs < SEEK_JUMP_TOLERANCE_MS) return
        val sourcePositionMs =
            snapshot.videoPositionMap?.reversed()?.mapPosition(surfacePositionMs) ?: surfacePositionMs
        forwardingSeek = true
        playbackController.seekTo(sourcePositionMs.coerceAtLeast(0L))
        scope.launch {
            playbackController.state.first {
                val currentSurfacePositionMs = it.videoPositionMap?.mapPosition(it.positionMs) ?: it.positionMs
                abs(currentSurfacePositionMs - surfacePositionMs) <= SEEK_TOLERANCE_MS
            }
            forwardingSeek = false
        }
    }

    private fun AVPlayer.positionMs(): Long = (CMTimeGetSeconds(currentTime()) * 1000.0).toLong().coerceAtLeast(0L)
}
