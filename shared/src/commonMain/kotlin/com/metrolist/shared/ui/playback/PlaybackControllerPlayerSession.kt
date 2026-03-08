package com.metrolist.shared.ui.playback

import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.model.RadioQueueChip
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.PlayerSession
import com.metrolist.shared.playback.QUEUE_SOURCE_RECOMMENDED
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.settings.MusicVideoQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaybackControllerPlayerSession(
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
) : PlayerSession {
    private val fieldUpdates = mutableListOf<(PlaybackUiState) -> Unit>()

    private fun <T> derive(select: (PlaybackUiState) -> T): StateFlow<T> {
        val flow = MutableStateFlow(select(controller.state.value))
        fieldUpdates += { flow.value = select(it) }
        return flow.asStateFlow()
    }

    private var pendingRemovals = emptyList<QueueRemoval>()
    private var nextQueueKey = 0L
    private var queuedSongs = emptyList<SongItem>()
    private var queuedEntries = emptyList<MusicQueueEntry>()
    private var nextSuggestionKey = -1L
    private val suggestionKeys = mutableMapOf<String, Long>()

    private fun PlaybackUiState.queuedEntries(): List<MusicQueueEntry> {
        if (queue != queuedSongs) {
            // shortcut: Reordered duplicate songs may swap keys; use backend occurrence IDs to distinguish them.
            val previousKeys = queuedEntries.groupBy({ it.song.id }, { it.key }).mapValues { ArrayDeque(it.value) }
            queuedSongs = queue
            queuedEntries = queue.map { song -> MusicQueueEntry(previousKeys[song.id]?.removeFirstOrNull() ?: nextQueueKey++, song) }
        }
        return queuedEntries
    }

    private fun PlaybackUiState.queueEntries(): List<MusicQueueEntry> {
        val queuedIds = queue.mapTo(mutableSetOf()) { it.id } + listOfNotNull(currentSong?.id)
        val suggestions = suggestedTracks.filterNot { it.id in queuedIds }.distinctBy { it.id }
        suggestionKeys.keys.retainAll(suggestions.mapTo(mutableSetOf()) { it.id })
        return queuedEntries() +
            suggestions.map { song ->
                MusicQueueEntry(
                    suggestionKeys.getOrPut(song.id) { nextSuggestionKey-- },
                    song.copy(queueSourceTitle = QUEUE_SOURCE_RECOMMENDED),
                )
            }
    }

    override val isConnected: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    override val currentSong = derive { it.currentSong }
    override val isPlaying = derive { it.isPlaying }
    override val isReconnecting = derive { it.isReconnecting }
    override val isPlaybackLoading = derive { it.isLoading }
    override val volume: StateFlow<Float> = MutableStateFlow(1f).asStateFlow()
    override val playbackSpeedFlow = derive { it.playbackSpeed }
    override val currentPosition = derive { it.positionMs }
    override val bufferedPosition = derive { it.bufferedPositionMs }
    override val duration = derive { it.durationMs }
    override val progress = derive { if (it.durationMs > 0L) (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) else 0f }
    override val bufferedProgress =
        derive {
            if (it.durationMs > 0L) {
                (it.bufferedPositionMs.toFloat() / it.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    override val shuffleModeEnabled = derive { it.isShuffled }
    override val repeatMode =
        derive {
            when (it.repeatMode) {
                RepeatMode.OFF -> 0
                RepeatMode.ONE -> 1
                RepeatMode.ALL -> 2
            }
        }
    override val queueSongs = derive { it.queue }
    private var previousQueueState = controller.state.value
    private val mutableQueueState =
        MutableStateFlow(
            MusicQueueState(previousQueueState.queueEntries(), previousQueueState.currentQueueIndex),
        )
    override val queueState = mutableQueueState.asStateFlow()
    override val currentMediaItemIndex = derive { it.currentQueueIndex }
    override val isQueueLoading = derive { it.isQueueLoading }
    override val queueTitle = derive { it.queueTitle }
    override val queueChips: StateFlow<List<RadioQueueChip>> = derive { it.queueChips }
    override val isVideoModeActive = derive { it.isVideoModeActive }
    override val isVideoModeResolving = derive { it.isVideoResolving }
    override val isVideoViewerVisible: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    override val currentPlaybackResource = derive { it.currentPlaybackResource }
    override val sleepTimerState: StateFlow<SleepTimerState> = derive { it.sleepTimerState }
    override val castDevices: StateFlow<List<CastDevice>> = derive { it.castDevices }
    override val castState: StateFlow<CastState> = derive { it.castState }

    init {
        scope.launch {
            controller.state.collect { state ->
                fieldUpdates.forEach { it(state) }
                val previous = previousQueueState
                if (previous.queue != state.queue || previous.suggestedTracks != state.suggestedTracks ||
                    previous.currentSong?.id != state.currentSong?.id || previous.currentQueueIndex != state.currentQueueIndex
                ) {
                    mutableQueueState.value = MusicQueueState(state.queueEntries(), state.currentQueueIndex)
                }
                previousQueueState = state
            }
        }
    }

    override fun currentPositionMs(): Long = controller.currentPositionMs()

    override fun togglePlayPause() = controller.togglePlayPause()

    override fun seekTo(position: Long) = controller.seekTo(position)

    override fun seekTo(
        position: Long,
        source: PlaybackSeekSource,
    ) = controller.seekTo(position, source)

    override fun setVolume(volume: Float) = Unit

    override fun setPlaybackSpeed(speed: Float) = controller.setPlaybackSpeed(speed)

    override fun skipToNext() = controller.skipToNext()

    override fun skipToPrevious() = controller.skipToPrevious()

    override fun skipToQueueEntry(entryKey: Long) {
        val song = queueSong(entryKey) ?: return
        if (entryKey < 0) {
            controller.playSuggestedTrack(song)
        } else {
            controller.playQueueIndex(
                controller.state.value
                    .queuedEntries()
                    .indexOfFirst { it.key == entryKey },
            )
        }
    }

    override fun cycleRepeatMode() = controller.toggleRepeat()

    override fun toggleShuffle() = controller.toggleShuffle()

    override fun startRadio(song: SongItem) = controller.startRadio(song)

    override fun selectQueueChip(chipId: String) = controller.selectQueueChip(chipId)

    override fun addQueueEntry(entryKey: Long) {
        queueSong(entryKey)?.let { controller.addToQueue(listOf(it)) }
    }

    override fun addToQueue(
        song: SongItem,
        playNext: Boolean,
    ) = if (playNext) controller.playNext(listOf(song)) else controller.addToQueue(listOf(song))

    override fun playNextQueueEntry(entryKey: Long) {
        queueSong(entryKey)?.let { controller.playNext(listOf(it)) }
    }

    override fun queueSong(entryKey: Long): SongItem? {
        val state = controller.state.value
        return if (entryKey < 0) {
            state.suggestedTracks.firstOrNull {
                suggestionKeys[it.id] == entryKey && it.id != state.currentSong?.id && state.queue.none { queued -> queued.id == it.id }
            }
        } else {
            state.queuedEntries().firstOrNull { it.key == entryKey }?.song
        }
    }

    override fun queueSongs(entryKeys: Set<Long>): List<SongItem> =
        controller.state.value.let { state ->
            val suggestions = state.suggestedTracks.associateBy { it.id }
            state.queueEntries().filter { it.key in entryKeys }.mapNotNull {
                if (it.key < 0) suggestions[it.song.id] else it.song
            }
        }

    override fun removeQueueEntry(entryKey: Long): QueueRemoval? = removeQueueEntries(setOf(entryKey))

    override fun removeQueueEntries(entryKeys: Set<Long>): QueueRemoval? {
        val indices =
            controller.state.value
                .queuedEntries()
                .withIndex()
                .filter { it.value.key in entryKeys }
                .mapTo(mutableSetOf()) { it.index }
        if (indices.isEmpty()) return null
        return controller.removeQueueItems(indices)?.also { pendingRemovals = pendingRemovals + it }
    }

    override fun pendingQueueRemovals(): List<QueueRemoval> = pendingRemovals

    override fun discardQueueUndo(removals: List<QueueRemoval>) {
        pendingRemovals = pendingRemovals - removals.toSet()
    }

    override fun restoreQueueItems(removals: List<QueueRemoval>) {
        discardQueueUndo(removals)
        controller.restoreQueueItems(removals)
    }

    override fun moveQueueEntry(
        fromEntryKey: Long,
        toEntryKey: Long,
        updatedItem: SongItem?,
    ): Boolean {
        val entries = controller.state.value.queuedEntries()
        val from = entries.indexOfFirst { it.key == fromEntryKey }
        val to = entries.indexOfFirst { it.key == toEntryKey }
        if (from < 0 || to < 0 || from == to) return false
        controller.moveQueueItem(from, to)
        return true
    }

    override fun reorderQueueEntries(entryKeys: List<Long>): Boolean {
        val entries = controller.state.value.queuedEntries()
        if (entryKeys.size != entries.size || entryKeys.toSet() != entries.map { it.key }.toSet()) return false
        val indices = entries.withIndex().associate { it.value.key to it.index }
        val order = entryKeys.map(indices::getValue)
        val currentIndex = controller.state.value.currentQueueIndex
        val suggestions =
            controller.state.value
                .queueEntries()
                .filter { it.key < 0 }
        queuedEntries = entryKeys.map { entries[indices.getValue(it)] }
        queuedSongs = queuedEntries.map { it.song }
        if (!controller.reorderQueue(order)) {
            queuedEntries = entries
            queuedSongs = entries.map { it.song }
            return false
        }
        mutableQueueState.value = MusicQueueState(queuedEntries + suggestions, order.indexOf(currentIndex))
        return true
    }

    override fun onSongDisliked(songId: String) {
        val song = controller.state.value.currentSong
        if (song?.id == songId) skipToNext()
    }

    override fun blockArtist(artistId: String) = Unit

    override fun toggleVideoMode() = controller.toggleVideoMode()

    override fun setVideoMode(wantVideo: Boolean) = controller.setVideoMode(wantVideo)

    override fun changeMusicVideoResolution(quality: MusicVideoQuality) = Unit

    override fun startSleepTimer(
        durationMs: Long,
        fadeOut: Boolean,
        finishCurrentSong: Boolean,
    ) = controller.startSleepTimer(durationMs, fadeOut, finishCurrentSong)

    override fun stopAfterCurrentSong(fadeOut: Boolean) = controller.stopSleepTimerAfterCurrentSong(fadeOut)

    override fun cancelSleepTimer() = controller.cancelSleepTimer()

    override fun startCastDiscovery() = controller.startCastDiscovery()

    override fun stopCastDiscovery() = controller.stopCastDiscovery()

    override fun castTo(device: CastDevice) = controller.castTo(device)

    override fun disconnectCast() = controller.disconnectCast()
}
