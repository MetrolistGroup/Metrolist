package com.metrolist.shared.deviceconnect

import kotlin.time.TimeSource

class DeviceConnectControllerClock(
    val playback: DcLocalPlaybackState,
) {
    private val anchor = TimeSource.Monotonic.markNow()

    fun positionMs(): Long {
        val elapsed = if (playback.playing && playback.ready) anchor.elapsedNow().inWholeMilliseconds else 0L
        return (playback.positionMs + (elapsed * playback.speed).toLong()).coerceIn(
            0L,
            playback.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
        )
    }
}
