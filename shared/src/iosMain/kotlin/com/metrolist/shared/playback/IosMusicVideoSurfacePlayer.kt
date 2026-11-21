package com.metrolist.shared.playback

import com.metrolist.shared.api.artwork.CanvasPlaybackWatchdog
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.cancelPendingSeeks
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.status
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import kotlin.math.abs
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
internal class IosMusicVideoSurfacePlayer(
    url: String,
    headers: Map<String, String>,
) {
    private val player = AVPlayer()
    val layer = AVPlayerLayer.playerLayerWithPlayer(player)
    private var closed = false
    private var surfaceGeneration = 0L
    private var attachedSurfaceGeneration: Long? = null
    private val seekLifecycle = MusicVideoSeekLifecycle()
    private val driftRetryPolicy = MusicVideoDriftRetryPolicy(SEEK_POST_SEEK_DRIFT_TOLERANCE_MS, MAX_SLOW_SEEK_CORRECTIONS)
    var driftRecoveryFailed = false
        private set
    private var latestTargetPositionMs = 0L
    private var latestTargetUpdatedAt: TimeMark? = null
    private var userSeekPending = false
    val positionSynchronized: Boolean
        get() = seekLifecycle.positionSynchronized
    private val foreground: Boolean
        get() = seekLifecycle.isForeground
    private var expectingProgress = false
    private var lastPositionMs: Long? = null
    private var renderedProgress = 0L
    private var completedSeekGeneration = 0L
    private var lastProgressSeekGeneration = 0L
    private var seekCooldownStartedAt: TimeMark? = null
    private val watchdog = CanvasPlaybackWatchdog()
    private val watchdogClock = TimeSource.Monotonic.markNow()
    private var failureObserver: Any? = null
    private var didFail = false

    init {
        player.muted = true
        setFillContainer(false)
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

    val ready: Boolean
        get() = !closed && layer.readyForDisplay

    fun setFillContainer(fillContainer: Boolean) {
        if (!closed) {
            layer.videoGravity = if (fillContainer) AVLayerVideoGravityResizeAspectFill else AVLayerVideoGravityResizeAspect
        }
    }

    fun attach(): Long = (++surfaceGeneration).also { attachedSurfaceGeneration = it }

    fun synchronize(
        positionMs: Long,
        playing: Boolean,
        playbackSpeed: Float,
        surfaceVisible: Boolean = true,
    ) {
        if (closed) return
        if (!surfaceVisible) {
            expectingProgress = false
            watchdog.reset()
            cancelPendingSeek()
            seekLifecycle.markUnsynchronized()
            player.pause()
            return
        }
        if (expectingProgress != playing) {
            expectingProgress = playing
            watchdog.reset()
        }
        val targetPositionMs = positionMs.coerceAtLeast(0L)
        val targetDeltaMs = targetPositionMs - latestTargetPositionMs
        val targetUpdateElapsedMs = latestTargetUpdatedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
        val expectedPlaybackAdvanceMs = if (expectingProgress) (targetUpdateElapsedMs * playbackSpeed).toLong() else 0L
        if (isMaterialMusicVideoTargetChange(targetDeltaMs, expectedPlaybackAdvanceMs, SEEK_USER_TARGET_CHANGE_TOLERANCE_MS)) {
            driftRetryPolicy.reset()
            driftRecoveryFailed = false
            userSeekPending = true
            seekLifecycle.markUnsynchronized()
        }
        latestTargetPositionMs = targetPositionMs
        latestTargetUpdatedAt = TimeSource.Monotonic.markNow()
        applyPendingSeek()
        if (playing && player.rate != playbackSpeed) {
            player.rate = playbackSpeed
        } else if (!playing && player.rate != 0f) {
            player.pause()
        }
    }

    fun setForeground(isForeground: Boolean) {
        if (closed || foreground == isForeground) return
        val wasForeground = foreground
        seekLifecycle.setForeground(isForeground)
        if (!isForeground) {
            expectingProgress = false
            watchdog.reset()
            cancelPendingSeek()
            userSeekPending = false
            seekCooldownStartedAt = null
            driftRetryPolicy.reset()
            driftRecoveryFailed = false
            player.pause()
        } else if (!wasForeground && isForeground) {
            userSeekPending = false
            seekCooldownStartedAt = null
            driftRetryPolicy.reset()
            driftRecoveryFailed = false
        }
    }

    fun applyPendingSeek() {
        val itemReady = player.currentItem?.status == AVPlayerItemStatusReadyToPlay
        if (closed || !itemReady || !foreground) return
        if (seekLifecycle.seekInFlight) return
        val sinceLastSeekMs = seekCooldownStartedAt?.elapsedNow()?.inWholeMilliseconds
        if (sinceLastSeekMs != null && sinceLastSeekMs < SEEK_RETRY_COOLDOWN_MS && !userSeekPending) return
        val currentMs = (CMTimeGetSeconds(player.currentTime()) * 1000.0).toLong()
        val targetPositionMs = latestTargetPositionMs
        val driftToleranceMs = if (sinceLastSeekMs == null) SEEK_DRIFT_TOLERANCE_MS else SEEK_POST_SEEK_DRIFT_TOLERANCE_MS
        if (abs(currentMs - targetPositionMs) <= driftToleranceMs) {
            userSeekPending = false
            seekLifecycle.markSynchronized()
            return
        }
        if (!driftRetryPolicy.shouldRetry(userSeekPending)) {
            driftRecoveryFailed = true
            return
        }
        userSeekPending = false
        val currentSeekGeneration = seekLifecycle.beginSeek() ?: return
        val target = CMTimeMakeWithSeconds(targetPositionMs / 1000.0, 600)
        val zero = CMTimeMakeWithSeconds(0.0, 600)
        val seekStartedAt = TimeSource.Monotonic.markNow()
        player.seekToTime(target, toleranceBefore = zero, toleranceAfter = zero) { finished ->
            if (!seekLifecycle.isCurrentSeek(currentSeekGeneration)) return@seekToTime
            val seekDurationMs = seekStartedAt.elapsedNow().inWholeMilliseconds
            val currentPositionMs = (CMTimeGetSeconds(player.currentTime()) * 1000.0).toLong()
            val stillAligned = abs(currentPositionMs - latestTargetPositionMs) <= SEEK_POST_SEEK_DRIFT_TOLERANCE_MS
            if (!seekLifecycle.completeSeek(currentSeekGeneration, finished, synchronized = stillAligned)) {
                driftRetryPolicy.recordSeek(seekDurationMs, succeeded = false)
                if (driftRetryPolicy.exhausted) {
                    driftRecoveryFailed = true
                    seekLifecycle.markUnsynchronized()
                    return@seekToTime
                }
                if (foreground) {
                    if (userSeekPending) {
                        seekCooldownStartedAt = null
                        applyPendingSeek()
                    } else {
                        seekCooldownStartedAt = TimeSource.Monotonic.markNow()
                    }
                }
                return@seekToTime
            }
            driftRetryPolicy.recordSeek(seekDurationMs, succeeded = stillAligned)
            completedSeekGeneration++
            seekCooldownStartedAt = TimeSource.Monotonic.markNow()
            if (!stillAligned) {
                seekLifecycle.markUnsynchronized()
                if (driftRetryPolicy.exhausted) {
                    driftRecoveryFailed = true
                    return@seekToTime
                }
            }
            if (userSeekPending) {
                seekCooldownStartedAt = null
                applyPendingSeek()
            }
        }
    }

    fun hasFailed(): Boolean {
        if (failed) return true
        val seconds = CMTimeGetSeconds(player.currentTime())
        if (seconds.isFinite()) {
            val positionMs = (seconds * 1000.0).toLong()
            val previousPositionMs = lastPositionMs
            if (ready && previousPositionMs != null &&
                positionMs - previousPositionMs in 1..MAX_NATURAL_PROGRESS_STEP_MS &&
                completedSeekGeneration == lastProgressSeekGeneration
            ) {
                renderedProgress++
            }
            lastPositionMs = positionMs
            lastProgressSeekGeneration = completedSeekGeneration
        }
        return watchdog.isStalled(watchdogClock.elapsedNow().inWholeMilliseconds, renderedProgress, expectingProgress && foreground)
    }

    fun detach(surfaceGeneration: Long) {
        if (closed || attachedSurfaceGeneration != surfaceGeneration) return
        attachedSurfaceGeneration = null
        seekLifecycle.setForeground(false)
        expectingProgress = false
        cancelPendingSeek()
        userSeekPending = false
        seekCooldownStartedAt = null
        watchdog.reset()
        player.pause()
    }

    fun close() {
        if (closed) return
        attachedSurfaceGeneration = null
        seekLifecycle.setForeground(false)
        expectingProgress = false
        cancelPendingSeek()
        userSeekPending = false
        watchdog.reset()
        player.pause()
        closed = true
        failureObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        failureObserver = null
        layer.player = null
        player.replaceCurrentItemWithPlayerItem(null)
    }

    private companion object {
        const val MAX_NATURAL_PROGRESS_STEP_MS = 2_000L
        const val SEEK_DRIFT_TOLERANCE_MS = 750L
        const val SEEK_POST_SEEK_DRIFT_TOLERANCE_MS = 1_500L
        const val SEEK_USER_TARGET_CHANGE_TOLERANCE_MS = 1_500L
        const val SEEK_RETRY_COOLDOWN_MS = 2_000L
        const val MAX_SLOW_SEEK_CORRECTIONS = 2
    }

    private fun cancelPendingSeek() {
        seekLifecycle.cancelSeek()
        player.currentItem?.cancelPendingSeeks()
    }
}
