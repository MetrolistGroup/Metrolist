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
): State<ItemPlayback> = remember(itemId, currentSongId, isPlaying) { itemPlayback(itemId, currentSongId, isPlaying) }

internal fun itemPlayback(
    itemId: String,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
): State<ItemPlayback> =
    derivedStateOf(structuralEqualityPolicy()) {
        val isCurrent = itemId == currentSongId.value
        ItemPlayback(isCurrent = isCurrent, isPlaying = isCurrent && isPlaying.value)
    }
