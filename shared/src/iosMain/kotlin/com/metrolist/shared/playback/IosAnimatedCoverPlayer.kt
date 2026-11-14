package com.metrolist.shared.playback

import com.metrolist.shared.api.artwork.CanvasPlaybackWatchdog
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVPlayerLooperStatusFailed
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.muted
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.status
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL

@OptIn(ExperimentalForeignApi::class)
internal class IosAnimatedCoverPlayer(
    url: String,
    headers: Map<String, String>,
) {
    internal val player = AVQueuePlayer()
    val layer = AVPlayerLayer.playerLayerWithPlayer(player)
    private val looper: AVPlayerLooper?
    private val watchdog = CanvasPlaybackWatchdog()
    private var expectingFrames = false
    private var closed = false
    private var lastPosition: Double? = null
    private var frameProgress = 0L

    init {
        player.muted = true
        layer.videoGravity = AVLayerVideoGravityResizeAspectFill
        looper =
            NSURL.URLWithString(url)?.let { nativeUrl ->
                val asset =
                    AVURLAsset(
                        uRL = nativeUrl,
                        options = headers.takeIf { it.isNotEmpty() }?.let { mapOf("AVURLAssetHTTPHeaderFieldsKey" to it) },
                    )
                AVPlayerLooper.playerLooperWithPlayer(player, AVPlayerItem(asset = asset))
            }
    }

    fun setPlaying(playing: Boolean) {
        if (closed || expectingFrames == playing) return
        expectingFrames = playing
        watchdog.reset()
        if (playing) player.play() else player.pause()
    }

    val ready: Boolean get() = !closed && layer.readyForDisplay

    fun hasFailed(nowMs: Long): Boolean {
        if (closed) return false
        if (looper == null || looper.status == AVPlayerLooperStatusFailed ||
            player.currentItem?.status == AVPlayerItemStatusFailed
        ) {
            return true
        }
        val position = CMTimeGetSeconds(player.currentTime())
        // AVPlayerLayer supplies no frame callback; count playback progress only after its first displayed frame.
        if (ready && position.isFinite() && position != lastPosition) {
            frameProgress++
            lastPosition = position
        }
        return watchdog.isStalled(nowMs, frameProgress, expectingFrames)
    }

    fun close() {
        if (closed) return
        closed = true
        expectingFrames = false
        player.pause()
        looper?.disableLooping()
        layer.player = null
        player.removeAllItems()
    }
}
