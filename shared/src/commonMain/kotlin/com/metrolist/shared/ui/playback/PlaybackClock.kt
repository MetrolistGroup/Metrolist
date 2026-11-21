package com.metrolist.shared.ui.playback

import com.metrolist.shared.model.playlistOccurrenceIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

fun Flow<Long>.wholeSecondPosition(): Flow<Long> = map { it / 1_000L * 1_000L }.distinctUntilChanged()

data class PlaybackListState(
    val currentSongId: String?,
    val isPlaying: Boolean,
    val originalQueueItemId: String? = null,
    val currentSongOccurrenceKey: String? = null,
)

fun PlaybackUiState.listState(): PlaybackListState =
    PlaybackListState(
        currentSong?.id,
        isPlaying,
        originalQueueItemId = currentSong?.originalQueueItemId,
        currentSongOccurrenceKey = currentSong?.playlistOccurrenceIdentity(),
    )

fun Flow<PlaybackUiState>.listStates(): Flow<PlaybackListState> = map { it.listState() }.distinctUntilChanged()
