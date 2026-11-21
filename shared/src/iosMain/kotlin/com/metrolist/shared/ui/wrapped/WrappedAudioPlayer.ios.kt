@file:OptIn(ExperimentalForeignApi::class)

package com.metrolist.shared.ui.wrapped

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.IosKeyValueObservation
import com.metrolist.shared.playback.iosPlaybackAudioQuality
import com.metrolist.shared.playback.requiresIosAudioCapture
import com.metrolist.shared.playback.resolveIosAudioResource
import com.metrolist.shared.playback.withIosAudioCapabilities
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.storage.IosPlaybackCache
import com.metrolist.shared.storage.IosPlaybackCapture
import com.metrolist.shared.youtube.extraction.models.toContentHints
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val cache = IosPlaybackCache(GlobalYouTube.client, scope, directoryName = "wrapped-soundtrack")
    private var capture: IosPlaybackCapture? = null
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

    override suspend fun play(song: SongItem) {
        check(!closed)
        clearItem()
        val resolver = checkNotNull(GlobalYouTubeRepository.streamResolver)
        val resource =
            resolveIosAudioResource(iosPlaybackAudioQuality(Settings.shared.audioQuality)) { quality, refresh, allowHls ->
                val hints = song.toContentHints(wantVideo = false).withIosAudioCapabilities(allowHls)
                if (refresh) resolver.refresh(song.id, hints, quality, song) else resolver.resolve(song.id, hints, quality, song)
            }
        currentCoroutineContext().ensureActive()
        check(!closed)
        capture = cache.acquire(resource)?.also { it.mayPublish = false }
        check(!resource.requiresIosAudioCapture || capture != null)
        val asset =
            capture?.asset ?: AVURLAsset(
                uRL = checkNotNull(NSURL.URLWithString(resource.url)),
                options = resource.headers.takeIf { it.isNotEmpty() }?.let { mapOf("AVURLAssetHTTPHeaderFieldsKey" to it) },
            )
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
        player.play()
        sample()
        check(
            withTimeoutOrNull(120_000) {
                ready.await()
                true
            } == true,
        )
    }

    private fun clearItem() {
        player.pause()
        observation?.close()
        observation = null
        val center = NSNotificationCenter.defaultCenter
        itemObservers.forEach { center.removeObserver(it) }
        itemObservers.clear()
        player.replaceCurrentItemWithPlayerItem(null)
        cache.release(capture)
        capture = null
    }

    override fun close() {
        closed = true
        clearItem()
        val center = NSNotificationCenter.defaultCenter
        sessionObservers.forEach { center.removeObserver(it) }
        sessionObservers.clear()
        cache.close()
        scope.cancel()
    }
}
