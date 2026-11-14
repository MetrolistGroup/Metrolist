package com.metrolist.shared.deviceconnect

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot

internal data class DcPlaybackChange(
    val playback: DcLocalPlaybackState,
    val trackChanged: Boolean,
)

internal fun validDcPlayback(state: DcLocalPlaybackState): Boolean {
    val queue = state.queue
    return validDcPlaybackScalars(state) &&
        queue.displayedItems.all(::validDcSong) &&
        (
            queue.originalIndices.isEmpty() || (
                queue.originalIndices.size == queue.displayedItems.size &&
                    queue.originalIndices.toSet().size == queue.displayedItems.size &&
                    queue.originalIndices.withIndex().all { (index, originalIndex) ->
                        originalIndex in queue.originalItems.indices && queue.originalItems[originalIndex] == queue.displayedItems[index]
                    }
            )
        ) &&
        queue.originalItems.groupingBy { it }.eachCount() == queue.displayedItems.groupingBy { it }.eachCount()
}

internal fun validDcPlaybackScalars(state: DcLocalPlaybackState): Boolean =
    state.positionMs >= 0 && state.durationMs >= 0 && state.speed.isFinite() && state.speed in 0.5f..2f &&
        (state.pitch == null || (state.pitch.isFinite() && state.pitch in 0.5f..2f)) &&
        state.volume.isFinite() && state.volume in 0f..1f &&
        state.queue.currentIndex in -1..state.queue.displayedItems.lastIndex &&
        (!state.playing || state.queue.currentIndex >= 0)

internal class DcWireQueueCache {
    private var source: MusicQueueSnapshot? = null
    private var sanitized: MusicQueueSnapshot? = null

    fun clear() {
        source = null
        sanitized = null
    }

    fun sanitize(state: DcLocalPlaybackState): DcLocalPlaybackState {
        val queue = state.queue
        val previous = source
        if (previous == null || previous.displayedItems !== queue.displayedItems ||
            previous.originalItems !== queue.originalItems || previous.originalIndices !== queue.originalIndices ||
            previous.isShuffled != queue.isShuffled || previous.recommendationsSongsOnly != queue.recommendationsSongsOnly
        ) {
            sanitized = queue.forDeviceConnect()
            source = queue
        }
        return state.copy(queue = checkNotNull(sanitized).copy(currentIndex = queue.currentIndex))
    }
}

internal fun DcLocalPlaybackState.forWire(): DcLocalPlaybackState = copy(queue = queue.forDeviceConnect())

internal fun validDcSong(song: SongItem): Boolean =
    song.id.isNotBlank() && (song.duration == null || song.duration in 0L..Long.MAX_VALUE / 1_000L)

internal fun DcLocalPlaybackState.isShareableDeviceConnectState(): Boolean =
    queue.displayedItems.none { it.isLocal || it.id.startsWith("local_") } &&
        queue.originalItems.none { it.isLocal || it.id.startsWith("local_") }

internal fun MusicQueueSnapshot.forDeviceConnect(): MusicQueueSnapshot =
    copy(
        displayedItems = displayedItems.map(SongItem::forDeviceConnect),
        originalItems = originalItems.map(SongItem::forDeviceConnect),
    )

private fun SongItem.forDeviceConnect(): SongItem = copy(libraryAddToken = null, libraryRemoveToken = null)

internal fun DcPlaybackCommand.isQueueIndexed(): Boolean =
    action in
        setOf(
            DcPlaybackAction.SELECT,
            DcPlaybackAction.REMOVE,
            DcPlaybackAction.MOVE,
            DcPlaybackAction.REORDER,
            DcPlaybackAction.RESTORE_QUEUE,
            DcPlaybackAction.REPLACE_CURRENT,
        )

internal fun reduceDcPlayback(
    state: DcLocalPlaybackState,
    command: DcPlaybackCommand,
    legacyShuffle: Boolean = false,
): DcPlaybackChange? {
    val scalar =
        when (command.action) {
            DcPlaybackAction.PLAY -> {
                if (state.queue.currentIndex >= 0) state.copy(playing = true) else return null
            }

            DcPlaybackAction.PAUSE -> {
                state.copy(playing = false)
            }

            DcPlaybackAction.SEEK -> {
                if (command.positionMs < 0 || state.queue.currentIndex < 0) return null
                state.copy(positionMs = command.positionMs.coerceAtMost(state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
            }

            DcPlaybackAction.SPEED -> {
                if (!command.speed.isFinite() || command.speed !in 0.5f..2f) return null
                state.copy(speed = command.speed)
            }

            DcPlaybackAction.PITCH -> {
                val pitch = command.pitch ?: return null
                if (!pitch.isFinite() || pitch !in 0.5f..2f) return null
                state.copy(pitch = pitch)
            }

            DcPlaybackAction.VOLUME -> {
                val volume = command.volume ?: return null
                if (!volume.isFinite() || volume !in 0f..1f) return null
                state.copy(volume = volume)
            }

            DcPlaybackAction.REPEAT -> {
                state.copy(
                    repeatMode =
                        command.repeatMode ?: when (state.repeatMode) {
                            DcRepeatMode.OFF -> DcRepeatMode.ALL
                            DcRepeatMode.ALL -> DcRepeatMode.ONE
                            DcRepeatMode.ONE -> DcRepeatMode.OFF
                        },
                )
            }

            else -> {
                null
            }
        }
    if (scalar != null) return DcPlaybackChange(scalar, false)
    val queue = MusicQueue().apply { restore(state.queue) }
    var next = state
    var trackChanged = false

    fun select(
        index: Int,
        position: Long = 0L,
    ) {
        queue.skipTo(index)
        next =
            next.copy(
                positionMs = position.coerceAtLeast(0L),
                durationMs = (queue.currentItem()?.duration ?: 0L) * 1_000L,
                playing = true,
                ready = false,
            )
        trackChanged = true
    }
    when (command.action) {
        DcPlaybackAction.PLAY, DcPlaybackAction.PAUSE, DcPlaybackAction.SEEK,
        DcPlaybackAction.REPEAT, DcPlaybackAction.SPEED, DcPlaybackAction.PITCH, DcPlaybackAction.VOLUME,
        -> {
            return null
        }

        DcPlaybackAction.NEXT -> {
            val index = queue.currentIndex.value + 1
            when {
                index in queue.items.value.indices -> select(index)
                state.repeatMode == DcRepeatMode.ALL && queue.items.value.isNotEmpty() -> select(0)
                else -> next = next.copy(playing = false)
            }
        }

        DcPlaybackAction.PREVIOUS -> {
            if (queue.currentItem() == null) return null
            if (state.positionMs > 3_000L) next = next.copy(positionMs = 0L) else select((queue.currentIndex.value - 1).coerceAtLeast(0))
        }

        DcPlaybackAction.SELECT -> {
            if (command.index !in queue.items.value.indices || command.positionMs < 0 || command.toIndex < -1 ||
                (command.toIndex >= 0 && command.toIndex !in queue.items.value.indices)
            ) {
                return null
            }
            if (command.toIndex >= 0) queue.move(command.index, command.toIndex)
            select(command.toIndex.takeIf { it >= 0 } ?: command.index, command.positionMs)
        }

        DcPlaybackAction.REPLACE_QUEUE -> {
            if (command.songs.isEmpty() || command.index !in command.songs.indices || command.songs.any { !validDcSong(it) } ||
                command.positionMs < 0
            ) {
                return null
            }
            val preserveCurrent =
                command.preserveCurrentTrack && state.ready && queue.currentItem()?.id == command.songs[command.index].id
            queue.setQueue(command.songs, command.index)
            if (command.enabled == true) queue.toggleShuffle(command.preserveHistory, legacyShuffle)
            if (!preserveCurrent) {
                select(queue.currentIndex.value, if (command.preserveCurrentTrack) 0L else command.positionMs)
            }
            next = next.copy(title = command.title, finite = command.finite)
        }

        DcPlaybackAction.REPLACE_CURRENT -> {
            val replacement = command.songs.singleOrNull() ?: return null
            if (!validDcSong(replacement) || command.positionMs < 0 || queue.currentItem() == null) {
                return null
            }
            queue.replaceCurrent(replacement)
            val durationMs = (replacement.duration ?: 0L) * 1_000L
            next =
                next.copy(
                    positionMs = command.positionMs.coerceAtMost(durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE),
                    durationMs = durationMs,
                    ready = false,
                )
            trackChanged = true
        }

        DcPlaybackAction.ADD, DcPlaybackAction.PLAY_NEXT -> {
            if (command.songs.isEmpty() || command.songs.any { !validDcSong(it) }) return null
            if (command.action == DcPlaybackAction.ADD) queue.addToQueue(command.songs) else queue.playNext(command.songs)
        }

        DcPlaybackAction.REMOVE -> {
            val indices = command.indices.ifEmpty { setOf(command.index) }
            if (indices.any { it !in queue.items.value.indices }) return null
            val removingCurrent = queue.currentIndex.value in indices
            queue.removeAt(indices)
            if (removingCurrent) {
                trackChanged = true
                next =
                    next.copy(
                        positionMs = 0L,
                        durationMs = (queue.currentItem()?.duration ?: 0L) * 1_000L,
                        playing =
                            next.playing && queue.currentItem() != null,
                        ready = false,
                    )
            }
        }

        DcPlaybackAction.MOVE -> {
            if (command.index !in queue.items.value.indices || command.toIndex !in queue.items.value.indices) return null
            val updatedItem = command.songs.singleOrNull()
            if (
                command.songs.size > 1 ||
                updatedItem?.let { !validDcSong(it) || it.id != queue.items.value[command.index].id } == true
            ) {
                return null
            }
            queue.move(command.index, command.toIndex, updatedItem)
        }

        DcPlaybackAction.REORDER -> {
            if (command.order.size != queue.items.value.size || command.order.toSet() !=
                queue.items.value.indices
                    .toSet()
            ) {
                return null
            }
            queue.reorder(command.order)
        }

        DcPlaybackAction.CLEAR -> {
            queue.setQueue(emptyList(), -1)
            next = next.copy(playing = false, positionMs = 0L, durationMs = 0L, title = null)
            trackChanged = true
        }

        DcPlaybackAction.SHUFFLE -> {
            val enabled = command.enabled ?: !queue.isShuffled
            if (enabled != queue.isShuffled) queue.toggleShuffle(legacyShuffle = legacyShuffle)
        }

        DcPlaybackAction.RESTORE_QUEUE -> {
            val restored = command.queue ?: return null
            if (!validDcPlayback(state.copy(queue = restored)) ||
                command.indices.any { it !in restored.displayedItems.indices }
            ) {
                return null
            }
            val currentSong = queue.currentItem()
            queue.restore(restored)
            if (currentSong != queue.currentItem() || restored.currentIndex in command.indices) {
                trackChanged = true
                next = next.copy(positionMs = 0L, durationMs = (queue.currentItem()?.duration ?: 0L) * 1_000L, ready = false)
            }
        }
    }
    return DcPlaybackChange(next.copy(queue = queue.snapshot()), trackChanged)
}

internal fun sameDcQueue(
    left: MusicQueueSnapshot,
    right: MusicQueueSnapshot,
): Boolean =
    left.displayedItems == right.displayedItems && left.originalItems == right.originalItems && left.isShuffled == right.isShuffled &&
        normalizedOriginalIndices(left) == normalizedOriginalIndices(right)

private fun normalizedOriginalIndices(queue: MusicQueueSnapshot): List<Int> =
    queue.originalIndices.ifEmpty {
        if (!queue.isShuffled && queue.displayedItems == queue.originalItems) queue.displayedItems.indices.toList() else emptyList()
    }
