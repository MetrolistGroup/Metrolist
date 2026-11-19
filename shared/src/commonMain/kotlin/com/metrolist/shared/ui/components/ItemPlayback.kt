package com.metrolist.shared.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy

internal data class ItemPlayback(
    val isCurrent: Boolean,
    val isPlaying: Boolean,
)

@Composable
internal fun rememberItemPlayback(
    itemId: String,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    occurrenceKey: String? = null,
    currentOccurrenceKey: State<String?>? = null,
): State<ItemPlayback> =
    remember(itemId, currentSongId, isPlaying, occurrenceKey, currentOccurrenceKey) {
        itemPlayback(itemId, currentSongId, isPlaying, occurrenceKey, currentOccurrenceKey)
    }

internal fun itemPlayback(
    itemId: String,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    occurrenceKey: String? = null,
    currentOccurrenceKey: State<String?>? = null,
): State<ItemPlayback> =
    derivedStateOf(structuralEqualityPolicy()) {
        val currentKey = currentOccurrenceKey?.value
        val isCurrent =
            itemId == currentSongId.value &&
                (occurrenceKey == null || currentKey == null || occurrenceKey == currentKey)
        ItemPlayback(isCurrent = isCurrent, isPlaying = isCurrent && isPlaying.value)
    }
