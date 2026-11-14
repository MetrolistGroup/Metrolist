package com.metrolist.shared.playback

import com.metrolist.shared.model.RepeatMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

@Serializable
internal data class QueuePlaybackState(
    @Serializable(with = StoredQueueSnapshotSerializer::class)
    val queue: MusicQueueSnapshot,
    val positionMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queueTitle: String? = null,
    val radioSeedSongId: String? = null,
    val radioPlaylistId: String? = null,
    val radioParams: String? = null,
    val radioQueueFinite: Boolean = queue.displayedItems.size > 1,
    val revision: String? = null,
)

@Serializable
internal data class QueuePositionCheckpoint(
    val revision: String,
    val positionMs: Long,
    val currentIndex: Int,
    val repeatMode: RepeatMode,
)

internal class QueueCheckpointPersistence(
    private val writeQueue: (String) -> Unit,
    private val writePosition: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastState: QueuePlaybackState? = null
    private var lastPosition: QueuePositionCheckpoint? = null
    private var cleared = false

    fun persist(state: QueuePlaybackState?) {
        if (state == null) {
            if (cleared) return
            writeQueue("")
            writePosition("")
            lastState = null
            lastPosition = null
            cleared = true
            return
        }
        cleared = false
        val previous = lastState
        val unchanged =
            previous != null && previous.queue.hasSameStructure(state.queue) &&
                state.copy(
                    queue = previous.queue,
                    positionMs = previous.positionMs,
                    repeatMode = previous.repeatMode,
                    revision = previous.revision,
                ) ==
                previous
        val committed =
            if (unchanged) {
                checkNotNull(previous)
            } else {
                val next = state.copy(revision = Uuid.random().toString())
                writeQueue(json.encodeToString(QueuePlaybackState.serializer(), next))
                lastState = next
                next
            }
        val position =
            QueuePositionCheckpoint(checkNotNull(committed.revision), state.positionMs, state.queue.currentIndex, state.repeatMode)
        if (position != lastPosition) {
            writePosition(json.encodeToString(QueuePositionCheckpoint.serializer(), position))
            lastPosition = position
        }
    }
}

internal fun decodeQueuePlaybackState(
    raw: String,
    position: String,
): QueuePlaybackState {
    val json = Json { ignoreUnknownKeys = true }
    val state = json.decodeFromString(QueuePlaybackState.serializer(), raw)
    val checkpoint =
        runCatching { json.decodeFromString(QueuePositionCheckpoint.serializer(), position) }
            .getOrNull()
            ?.takeIf { it.revision == state.revision && it.currentIndex in state.queue.displayedItems.indices } ?: return state
    return state.copy(
        queue = state.queue.copy(currentIndex = checkpoint.currentIndex),
        positionMs = checkpoint.positionMs,
        repeatMode = checkpoint.repeatMode,
    )
}
