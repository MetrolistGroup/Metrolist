package com.metrolist.shared.ui.components.sliders

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.playback.PendingSeekPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun rememberPendingSeekPosition(
    songId: String?,
    durationMs: Long,
    enabled: Boolean,
    position: () -> Long,
    renderedPosition: () -> Long = position,
): MutableState<PendingSeekPosition?> {
    val pending = remember(songId, durationMs, enabled) { mutableStateOf<PendingSeekPosition?>(null) }
    val latestPosition by rememberUpdatedState(position)
    val latestRenderedPosition by rememberUpdatedState(renderedPosition)
    LaunchedEffect(pending, pending.value) {
        val seek = pending.value ?: return@LaunchedEffect
        // Release a rejected seek instead of leaving the slider pinned indefinitely.
        withTimeoutOrNull(5_000L) {
            snapshotFlow { seek.isAcknowledged(latestPosition(), latestRenderedPosition()) }.first { it }
        }
        if (pending.value == seek) pending.value = null
    }
    return pending
}
