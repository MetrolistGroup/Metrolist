package com.metrolist.shared.cast

import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.MusicQueueState

enum class CastProtocol {
    FCAST,
    GOOGLE_CAST,
    DLNA,
    SONOS,
}

data class CastDevice(
    val id: String,
    val name: String,
    val protocol: CastProtocol,
    val host: String,
    val port: Int,
    val endpoint: String? = null,
)

enum class CastConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

enum class CastError {
    DISCOVERY_FAILED,
    NO_MEDIA,
    UNSUPPORTED_STREAM,
    CONNECTION_FAILED,
    PLAYBACK_FAILED,
    STREAM_PORT_UNAVAILABLE,
    STREAM_UNREACHABLE,
}

data class CastState(
    val status: CastConnectionStatus = CastConnectionStatus.DISCONNECTED,
    val device: CastDevice? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1f,
    val hasEnded: Boolean = false,
    val error: CastError? = null,
    val mediaGeneration: Long = 0L,
    val queueEntry: MusicQueueEntry? = null,
    val mediaVideoId: String? = null,
    val seekGeneration: Long = 0L,
) {
    val isConnected: Boolean
        get() = status == CastConnectionStatus.CONNECTED && device != null
}

fun castVolumePercent(volume: Float): Int {
    val normalized = volume.coerceIn(0f, 1f)
    val truncated = (normalized * 100).toInt()
    // Recover an integer percentage converted to Float without rounding genuinely fractional levels.
    val nextPercent = truncated + 1
    return if (normalized == nextPercent / 100f) nextPercent else truncated
}

fun dlnaVolumePercent(volume: Float): Int = castVolumePercent(volume)

internal fun acceptedCastPlaybackEnd(
    receiverHasEnded: Boolean,
    remoteStopRequested: Boolean,
): Boolean = receiverHasEnded && !remoteStopRequested

fun shouldHandleCastPlaybackEnd(
    state: CastState,
    handledGeneration: Long?,
): Boolean = state.isConnected && state.hasEnded && state.mediaGeneration != handledGeneration

internal fun castPlaybackEndedNearDuration(
    wasPlaying: Boolean,
    isStopped: Boolean,
    positionMs: Long,
    durationMs: Long,
    lastPlayingPositionMs: Long = positionMs,
    pollIntervalMs: Long = 0L,
): Boolean =
    wasPlaying && isStopped && durationMs > 0L &&
        maxOf(positionMs, lastPlayingPositionMs) >= durationMs - 1_500L - pollIntervalMs.coerceAtLeast(0L)

internal fun castPlaybackRestartedNearDuration(
    wasPlaying: Boolean,
    isPlaying: Boolean,
    sameMedia: Boolean,
    positionMs: Long,
    durationMs: Long,
    lastPlayingPositionMs: Long,
    pollIntervalMs: Long = 0L,
): Boolean {
    val pollToleranceMs = pollIntervalMs.coerceAtLeast(0L)
    return wasPlaying && isPlaying && sameMedia && durationMs > 0L &&
        lastPlayingPositionMs >= durationMs - 1_500L - pollToleranceMs &&
        positionMs <= maxOf(1_500L, pollToleranceMs * 2L) &&
        lastPlayingPositionMs - positionMs >= durationMs / 2L
}

fun CastState.forQueueEntry(queue: MusicQueueState): CastState? {
    val current = queue.entries.getOrNull(queue.currentIndex) ?: return null
    val remote = queueEntry ?: return null
    return takeIf { isConnected && remote.key == current.key && remote.song.id == current.song.id }
}
