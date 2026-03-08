package com.metrolist.shared.listentogether

import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

enum class PlaybackReadiness {
    IDLE,
    BUFFERING,
    READY,
    FAILED,
}

interface ListenTogetherPlayback {
    val connected: StateFlow<Boolean>
    val currentSong: StateFlow<SongItem?>
    val playing: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val volume: StateFlow<Float>
    val queue: StateFlow<List<SongItem>>
    val readiness: StateFlow<PlaybackReadiness>

    fun currentPositionMs(): Long = positionMs.value

    fun currentQueueTitle(): String?

    fun currentQueueIndex(): Int?

    fun currentQueueSnapshot(): MusicQueueSnapshot =
        MusicQueueSnapshot(
            displayedItems = queue.value,
            currentIndex =
                currentQueueIndex() ?: -1,
        )

    fun playbackSpeed(): Float

    fun playbackInstanceId(): Long? = null

    suspend fun playQueueFromRemote(
        songs: List<SongItem>,
        startIndex: Int = 0,
        title: String? = null,
    )

    fun setQueueTitleFromRemote(title: String?)

    suspend fun replaceUpcomingQueueFromRemote(songs: List<SongItem>)

    suspend fun addToQueueFromRemote(song: SongItem)

    suspend fun skipToNextFromRemote()

    suspend fun skipToPreviousFromRemote()

    suspend fun setPlayingFromRemote(playing: Boolean)

    suspend fun seekToFromRemote(positionMs: Long)

    suspend fun setPlaybackSpeedFromRemote(speed: Float)

    suspend fun setVolumeFromRemote(volume: Float)
}

fun interface ListenTogetherTrackResolver {
    suspend fun resolve(track: TrackInfo): SongItem?
}

fun interface ListenTogetherLogger {
    fun log(
        level: ListenTogetherLogLevel,
        message: String,
    )
}

fun interface ListenTogetherPresentationText {
    fun text(key: ListenTogetherText): String
}

enum class ListenTogetherText {
    CONNECTION_FAILED,
    ROOM_CREATED,
    JOINED_ROOM,
    JOIN_REJECTED,
    KICKED,
    SERVER_ERROR,
    BLOCKED_BY_HOST,
    BUFFER_WAIT,
    HOST_CHANGED,
    USER_JOINED,
    USER_LEFT,
    RECONNECTED,
    SUGGESTION_RECEIVED,
    SUGGESTION_SUBMITTED,
    SUGGESTION_APPROVED,
    SUGGESTION_REJECTED,
    QUEUE_TOO_LARGE,
    TRACK_LOAD_FAILED,
    BECAME_HOST,
}

enum class ListenTogetherLogLevel { INFO, WARNING, ERROR, DEBUG }

data class ListenTogetherLogEntry(
    val timestamp: String,
    val level: ListenTogetherLogLevel,
    val message: String,
)

@Serializable
enum class ListenTogetherConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

@Serializable
enum class ListenTogetherRoomRole { HOST, GUEST, NONE }

@Serializable
data class ListenTogetherServerMetadata(
    val name: String,
    val operator: String,
)

data class ListenTogetherServer(
    val name: String,
    val url: String,
    val location: String,
    val operator: String,
    val isCustom: Boolean = false,
)

data class ListenTogetherJoinRequest(
    val userId: String,
    val username: String,
)

data class ListenTogetherSuggestion(
    val id: String,
    val fromUserId: String,
    val fromUsername: String,
    val trackInfo: TrackInfo,
)

data class PendingListenTogetherQueue(
    val title: String,
    val songs: List<SongItem>,
)

sealed interface ListenTogetherEvent {
    data class SuggestionAutoApproved(
        val suggestion: ListenTogetherSuggestion,
    ) : ListenTogetherEvent

    data class Notice(
        val key: ListenTogetherText,
    ) : ListenTogetherEvent

    data class Failure(
        val key: ListenTogetherText,
        val reason: String? = null,
    ) : ListenTogetherEvent

    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
    ) : ListenTogetherEvent
}
