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
}

data class CastState(
    val status: CastConnectionStatus = CastConnectionStatus.DISCONNECTED,
    val device: CastDevice? = null,
    val isPlaying: Boolean = false,
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
): Boolean = wasPlaying && isStopped && durationMs > 0L && positionMs >= durationMs - 1_500L

fun CastState.forQueueEntry(queue: MusicQueueState): CastState? {
    val current = queue.entries.getOrNull(queue.currentIndex) ?: return null
    val remote = queueEntry ?: return null
    return takeIf { isConnected && remote.key == current.key && remote.song.id == current.song.id }
}
