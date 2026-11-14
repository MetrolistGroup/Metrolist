/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.models

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.StoredQueueSnapshotSerializer
import com.metrolist.shared.playback.hasSameStructure
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal val playbackPersistenceJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

@Serializable
data class PersistRadioQueueChip(
    val id: String,
    val title: String,
    val playlistId: String,
    val params: String? = null,
    val isSelected: Boolean = false,
)

@Serializable
data class PersistPlayerState(
    val playWhenReady: Boolean,
    val repeatMode: Int,
    val shuffleModeEnabled: Boolean,
    val volume: Float,
    val currentPosition: Long,
    val currentMediaItemIndex: Int,
    val playbackState: Int,
    val queueTitle: String? = null,
    val queueSongs: List<SongItem> = emptyList(),
    @Serializable(with = StoredQueueSnapshotSerializer::class)
    val queueSnapshot: MusicQueueSnapshot? = null,
    val radioQueueSeedSongId: String? = null,
    val radioQueueFinite: Boolean? = null,
    val radioQueue: Boolean = false,
    val queueSessionId: Long? = null,
    val radioQueueChips: List<PersistRadioQueueChip> = emptyList(),
    val radioQueueSelectedChipId: String? = null,
    val radioQueueContinuation: String? = null,
    val radioQueuePlaylistId: String? = null,
    val radioQueueParams: String? = null,
    val revision: String? = null,
) : java.io.Serializable

internal class PlaybackPersistenceWriter(
    private val writePosition: (String) -> Unit,
    private val writeStoredValue: (String) -> Unit,
) {
    class Request internal constructor(
        internal val revision: Long,
        internal val state: PersistPlayerState?,
    )

    private val lock = Any()
    private val revision = AtomicLong()

    fun request(state: PersistPlayerState?): Request = Request(revision.incrementAndGet(), state)

    private var lastState: PersistPlayerState? = null
    private var lastCheckpoint: PlayerPositionCheckpoint? = null

    fun write(request: Request) {
        synchronized(lock) {
            if (request.revision != revision.get()) return
            val state = request.state
            if (state == null) {
                writeStoredValue("")
                writePosition("")
                lastState = null
                lastCheckpoint = null
                return
            }
            val previous = lastState
            val sameQueue =
                if (state.queueSnapshot != null) {
                    previous?.queueSnapshot?.hasSameStructure(state.queueSnapshot) == true
                } else {
                    previous?.queueSongs == state.queueSongs
                }
            val structureUnchanged =
                previous != null && sameQueue &&
                    state.copy(
                        queueSnapshot = previous.queueSnapshot,
                        currentPosition = previous.currentPosition,
                        currentMediaItemIndex = previous.currentMediaItemIndex,
                        repeatMode = previous.repeatMode,
                        playWhenReady = previous.playWhenReady,
                        playbackState = previous.playbackState,
                        volume = previous.volume,
                        revision = previous.revision,
                    ) == previous
            val committed =
                if (structureUnchanged) {
                    checkNotNull(previous)
                } else {
                    val next = state.copy(revision = UUID.randomUUID().toString())
                    writeStoredValue(playbackPersistenceJson.encodeToString(PersistPlayerState.serializer(), next))
                    lastState = next
                    next
                }
            val checkpoint =
                PlayerPositionCheckpoint(
                    checkNotNull(committed.revision),
                    state.currentPosition,
                    state.currentMediaItemIndex,
                    state.repeatMode,
                    state.playWhenReady,
                    state.playbackState,
                    state.volume,
                )
            if (checkpoint != lastCheckpoint) {
                writePosition(playbackPersistenceJson.encodeToString(PlayerPositionCheckpoint.serializer(), checkpoint))
                lastCheckpoint = checkpoint
            }
        }
    }
}

@Serializable
internal data class PlayerPositionCheckpoint(
    val revision: String,
    val positionMs: Long,
    val currentIndex: Int,
    val repeatMode: Int,
    val playWhenReady: Boolean,
    val playbackState: Int,
    val volume: Float,
)

internal fun decodePersistPlayerState(
    raw: String,
    position: String,
): PersistPlayerState {
    val state = playbackPersistenceJson.decodeFromString(PersistPlayerState.serializer(), raw)
    val checkpoint =
        runCatching {
            playbackPersistenceJson.decodeFromString(PlayerPositionCheckpoint.serializer(), position)
        }.getOrNull()?.takeIf {
            it.revision == state.revision &&
                it.currentIndex in (state.queueSnapshot?.displayedItems ?: state.queueSongs).indices
        }
            ?: return state
    return state.copy(
        currentPosition = checkpoint.positionMs,
        currentMediaItemIndex = checkpoint.currentIndex,
        queueSnapshot = state.queueSnapshot?.copy(currentIndex = checkpoint.currentIndex),
        repeatMode = checkpoint.repeatMode,
        playWhenReady = checkpoint.playWhenReady,
        playbackState = checkpoint.playbackState,
        volume = checkpoint.volume,
    )
}
