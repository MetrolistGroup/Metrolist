package com.metrolist.shared.listentogether

import com.metrolist.shared.listentogether.network.PlaybackActionPayload
import com.metrolist.shared.listentogether.network.PlaybackActions
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.TrackInfo
import kotlin.math.abs

internal data class ListenTogetherControls(
    val settingsEnabled: Boolean,
    val playbackLocked: Boolean,
    val canModerate: Boolean,
)

internal fun listenTogetherControls(
    enabled: Boolean,
    role: ListenTogetherRoomRole,
    inRoom: Boolean,
): ListenTogetherControls =
    ListenTogetherControls(
        settingsEnabled = enabled,
        playbackLocked = inRoom && role == ListenTogetherRoomRole.GUEST,
        canModerate = enabled && inRoom && role == ListenTogetherRoomRole.HOST,
    )

class PlaybackRevisionFilter(
    initialRevision: Long = 0L,
) {
    var revision: Long = initialRevision
        private set

    fun accept(candidate: Long): Boolean {
        if (candidate <= 0L) return true
        if (candidate < revision) return false
        revision = maxOf(revision, candidate)
        return true
    }

    fun reset(value: Long = 0L) {
        revision = value
    }
}

object ListenTogetherProtocolReducer {
    fun reduce(
        state: RoomState,
        action: PlaybackActionPayload,
    ): RoomState {
        val updateTime = action.serverTime ?: state.lastUpdate
        val revision = maxOf(state.revision, action.revision)
        return when (action.action) {
            PlaybackActions.PLAY -> {
                state.copy(
                    isPlaying = true,
                    position = action.position ?: state.position,
                    lastUpdate = updateTime,
                    revision = revision,
                )
            }

            PlaybackActions.PAUSE -> {
                state.copy(
                    isPlaying = false,
                    position = action.position ?: state.position,
                    lastUpdate = updateTime,
                    revision = revision,
                )
            }

            PlaybackActions.SEEK -> {
                state.copy(
                    position = action.position ?: state.position,
                    lastUpdate = updateTime,
                    revision = revision,
                )
            }

            PlaybackActions.CHANGE_TRACK -> {
                state.copy(
                    currentTrack = action.trackInfo,
                    position = action.position ?: 0L,
                    isPlaying = false,
                    lastUpdate = updateTime,
                    queue = if (action.replaceQueue || action.revision > 0L) action.queue.orEmpty() else state.queue,
                    queueTitle = action.queueTitle,
                    revision = revision,
                )
            }

            PlaybackActions.SYNC_QUEUE -> {
                state.copy(
                    queue = action.queue.orEmpty(),
                    queueTitle = action.queueTitle,
                    revision = revision,
                )
            }

            PlaybackActions.QUEUE_ADD -> {
                state.copy(
                    queue = canonicalQueueAfterAdd(state.queue, action),
                    revision = revision,
                )
            }

            PlaybackActions.QUEUE_REMOVE -> {
                state.copy(
                    queue = if (action.revision > 0L) action.queue.orEmpty() else state.queue.filterNot { it.id == action.trackId },
                    revision = revision,
                )
            }

            PlaybackActions.QUEUE_CLEAR -> {
                state.copy(queue = emptyList(), revision = revision)
            }

            PlaybackActions.SET_VOLUME -> {
                state.copy(
                    volume = action.volume?.coerceIn(0f, 1f) ?: state.volume,
                    revision = revision,
                )
            }

            else -> {
                state.copy(revision = revision)
            }
        }
    }

    private fun canonicalQueueAfterAdd(
        current: List<TrackInfo>,
        action: PlaybackActionPayload,
    ): List<TrackInfo> {
        if (action.revision > 0L) return action.queue.orEmpty()
        val track = action.trackInfo ?: return current
        return if (action.insertNext == true) listOf(track) + current else current + track
    }
}

object ListenTogetherModeration {
    fun isBlocked(
        username: String,
        blocked: Set<String>,
    ): Boolean = blocked.any { it.equals(username.trim(), ignoreCase = true) }

    fun add(
        username: String,
        blocked: Set<String>,
    ): Set<String> {
        val normalized = username.trim()
        if (normalized.isBlank() || isBlocked(normalized, blocked)) return blocked
        return blocked + normalized
    }

    fun remove(
        username: String,
        blocked: Set<String>,
    ): Set<String> = blocked.filterNot { it.equals(username.trim(), ignoreCase = true) }.toSet()
}

fun canonicalUpcomingQueue(queue: List<TrackInfo>): List<TrackInfo> = queue

internal fun reconcileHostQueue(
    published: List<TrackInfo>,
    local: List<TrackInfo>,
    canonical: List<TrackInfo>,
): List<TrackInfo> {
    fun keys(tracks: List<TrackInfo>): List<Pair<String, Int>> {
        val counts = mutableMapOf<String, Int>()
        return tracks.map { track ->
            val occurrence = counts[track.id] ?: 0
            counts[track.id] = occurrence + 1
            track.id to occurrence
        }
    }

    val publishedKeys = keys(published)
    val localKeys = keys(local)
    val publishedSet = publishedKeys.toSet()
    val localSet = localKeys.toSet()
    val canonicalEntries = keys(canonical).zip(canonical)
    val canonicalByKey = canonicalEntries.toMap()
    val retained = localKeys.zip(local).filter { (key, _) -> key !in publishedSet || key in canonicalByKey }.toMutableList()
    val localOrder = localKeys.filter { it in publishedSet }
    if (localOrder == publishedKeys.filter { it in localSet }) {
        val remoteOrder = ArrayDeque(canonicalEntries.filter { it.first in localSet && it.first in publishedSet })
        for (index in retained.indices) {
            if (retained[index].first in publishedSet) retained[index] = remoteOrder.removeFirst()
        }
    }
    val retainedKeys = retained.mapTo(mutableSetOf()) { it.first }
    for ((index, entry) in canonicalEntries.withIndex()) {
        if (entry.first in publishedSet) continue
        val next = canonicalEntries.asSequence().drop(index + 1).firstOrNull { it.first in retainedKeys }
        val insertion = next?.let { candidate -> retained.indexOfFirst { it.first == candidate.first } } ?: retained.size
        retained.add(if (index == 0) 0 else insertion, entry)
    }
    return retained.map { (key, track) -> if (key in publishedSet) canonicalByKey[key] ?: track else track }
}

sealed interface DriftCorrection {
    data object None : DriftCorrection

    data class Soft(
        val speed: Float,
        val durationMs: Long,
    ) : DriftCorrection

    data class Seek(
        val positionMs: Long,
    ) : DriftCorrection
}

fun driftCorrection(
    currentPositionMs: Long,
    targetPositionMs: Long,
    isPlaying: Boolean,
    clockSynchronized: Boolean,
): DriftCorrection {
    val drift = targetPositionMs - currentPositionMs
    if (abs(drift) < 50L) return DriftCorrection.None
    if (!isPlaying || !clockSynchronized || abs(drift) > 750L) return DriftCorrection.Seek(targetPositionMs)
    val speedDelta = (drift / 20_000f).coerceIn(-0.015f, 0.015f)
    val duration = (abs(drift) / abs(speedDelta).coerceAtLeast(0.001f)).toLong().coerceAtMost(60_000L)
    return DriftCorrection.Soft(1f + speedDelta, duration)
}
