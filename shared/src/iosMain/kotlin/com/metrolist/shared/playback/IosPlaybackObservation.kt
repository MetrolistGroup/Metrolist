package com.metrolist.shared.playback

import com.metrolist.shared.playback.observation.MetrolistKeyValueObserverProtocol
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.addBoundaryTimeObserverForTimes
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.valueWithCMTime
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSValue
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
internal class IosKeyValueObservation(
    private val target: NSObject,
    private val keys: List<String>,
    private val scope: CoroutineScope,
    private val onChange: (String) -> Unit,
) : NSObject(),
    MetrolistKeyValueObserverProtocol {
    private var active = true

    init {
        keys.forEach { target.addObserver(this, it, NSKeyValueObservingOptionNew, null) }
    }

    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        // KVO can arrive off-main or during an item replacement. Serialize with player commands.
        scope.launch {
            if (active && keyPath in keys) onChange(requireNotNull(keyPath))
        }
    }

    fun close() {
        if (!active) return
        active = false
        keys.forEach { target.removeObserver(this, it) }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosPlaybackObservation(
    private val player: AVPlayer,
    item: AVPlayerItem,
    scope: CoroutineScope,
    private val onSample: () -> Unit,
    onItemChange: (String) -> Unit,
    private val onFade: () -> Unit,
) {
    private var active = true
    private val playerObservation = IosKeyValueObservation(player, listOf("timeControlStatus", "rate"), scope) { onSample() }
    private val itemObservation = IosKeyValueObservation(item, listOf("status", "duration", "loadedTimeRanges"), scope, onItemChange)
    private var periodicToken: Any? = null
    private var intervalMs: Long? = null
    private var boundaryToken: Any? = null
    private var boundaryMs: Long? = null
    private var fadeToken: Any? = null
    private var fadeIntervalMs: Long? = null

    fun update(
        intervalMs: Long,
        boundaryMs: Long?,
        fadeIntervalMs: Long?,
    ) {
        if (this.intervalMs != intervalMs) {
            periodicToken?.let(player::removeTimeObserver)
            this.intervalMs = intervalMs
            periodicToken =
                player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(intervalMs / 1000.0, 1000), null) {
                    if (active) onSample()
                }
        }
        if (this.boundaryMs != boundaryMs) {
            boundaryToken?.let(player::removeTimeObserver)
            boundaryToken = null
            this.boundaryMs = boundaryMs
            if (boundaryMs != null) {
                boundaryToken =
                    player.addBoundaryTimeObserverForTimes(
                        listOf(NSValue.valueWithCMTime(CMTimeMakeWithSeconds(boundaryMs / 1000.0, 1000))),
                        null,
                    ) {
                        if (active) onSample()
                    }
            }
        }
        if (this.fadeIntervalMs != fadeIntervalMs) {
            fadeToken?.let(player::removeTimeObserver)
            fadeToken = null
            this.fadeIntervalMs = fadeIntervalMs
            if (fadeIntervalMs != null) {
                fadeToken =
                    player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(fadeIntervalMs / 1000.0, 1000), null) {
                        if (active) onFade()
                    }
            }
        }
    }

    fun close() {
        if (!active) return
        active = false
        playerObservation.close()
        itemObservation.close()
        periodicToken?.let(player::removeTimeObserver)
        boundaryToken?.let(player::removeTimeObserver)
        fadeToken?.let(player::removeTimeObserver)
        periodicToken = null
        boundaryToken = null
        fadeToken = null
    }
}
