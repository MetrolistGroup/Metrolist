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
    currentQueueItemId: State<String?>? = null,
    preferQueueItemId: State<Boolean>? = null,
): State<ItemPlayback> =
    remember(itemId, currentSongId, isPlaying, currentQueueItemId, preferQueueItemId) {
        itemPlayback(itemId, currentSongId, isPlaying, currentQueueItemId, preferQueueItemId)
    }

internal fun itemPlayback(
    itemId: String,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    currentQueueItemId: State<String?>? = null,
    preferQueueItemId: State<Boolean>? = null,
): State<ItemPlayback> =
    derivedStateOf(structuralEqualityPolicy()) {
        val queueItemId = currentQueueItemId?.value
        val prefersQueueEntry = queueItemId != null && preferQueueItemId?.value != false
        val isCurrent =
            if (prefersQueueEntry) {
                itemId == queueItemId
            } else {
                itemId == currentSongId.value
            }
        ItemPlayback(isCurrent = isCurrent, isPlaying = isCurrent && isPlaying.value)
    }
