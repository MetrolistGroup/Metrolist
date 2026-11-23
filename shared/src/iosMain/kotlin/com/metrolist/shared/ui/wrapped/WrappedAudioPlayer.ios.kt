@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.ui.wrapped

import com.metrolist.shared.playback.IosKeyValueObservation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.status
import platform.AVFoundation.volume
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol

internal actual fun createWrappedAudioPlayer(
    onEnded: () -> Unit,
    onFailure: () -> Unit,
    onInterrupted: () -> Unit,
    volume: Float,
): WrappedAudioPlayer = IosWrappedAudioPlayer(onEnded, onFailure, onInterrupted, volume)

private class IosWrappedAudioPlayer(
    private val onEnded: () -> Unit,
    private val onFailure: () -> Unit,
    onInterrupted: () -> Unit,
    initialVolume: Float,
) : WrappedAudioPlayer {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val player = AVPlayer().apply { volume = initialVolume }
    private var observation: IosKeyValueObservation? = null
    private val itemObservers = mutableListOf<NSObjectProtocol>()
    private val sessionObservers = mutableListOf<NSObjectProtocol>()
    private var closed = false

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    init {
        val center = NSNotificationCenter.defaultCenter
        sessionObservers +=
            center.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { notification ->
                val type = notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber
                if (!closed && type?.unsignedLongValue == AVAudioSessionInterruptionTypeBegan) onInterrupted()
            }
        sessionObservers +=
            center.addObserverForName(AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue) { notification ->
                val reason = notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber
                if (!closed && reason?.unsignedLongValue == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) onInterrupted()
            }
    }

    override suspend fun prepare(path: String) {
        check(!closed)
        clearItem()
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        val item = AVPlayerItem(asset = asset)
        val ready = CompletableDeferred<Unit>()

        fun sample() {
            if (closed || player.currentItem != item) return
            when (item.status) {
                AVPlayerItemStatusReadyToPlay -> {
                    ready.complete(Unit)
                }

                AVPlayerItemStatusFailed -> {
                    if (!ready.completeExceptionally(IllegalStateException("Wrapped audio playback failed"))) onFailure()
                }
            }
        }

        observation = IosKeyValueObservation(item, listOf("status"), scope) { sample() }
        val center = NSNotificationCenter.defaultCenter
        itemObservers +=
            center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) {
                if (!closed && player.currentItem == item) onEnded()
            }
        itemObservers +=
            center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) {
                if (!closed && player.currentItem == item) {
                    if (!ready.completeExceptionally(IllegalStateException("Wrapped audio playback failed"))) onFailure()
                }
            }
        player.replaceCurrentItemWithPlayerItem(item)
        val session = AVAudioSession.sharedInstance()
        if (session.category != AVAudioSessionCategoryPlayback) check(session.setCategory(AVAudioSessionCategoryPlayback, error = null))
        check(session.setActive(true, error = null))
        sample()
        check(
            withTimeoutOrNull(120_000) {
                ready.await()
                true
            } == true,
        )
    }

    override fun play() {
        check(!closed)
        player.play()
    }

    private fun clearItem() {
        player.pause()
        observation?.close()
        observation = null
        val center = NSNotificationCenter.defaultCenter
        itemObservers.forEach { center.removeObserver(it) }
        itemObservers.clear()
        player.replaceCurrentItemWithPlayerItem(null)
    }

    override fun close() {
        closed = true
        clearItem()
        val center = NSNotificationCenter.defaultCenter
        sessionObservers.forEach { center.removeObserver(it) }
        sessionObservers.clear()
        scope.cancel()
    }
}
