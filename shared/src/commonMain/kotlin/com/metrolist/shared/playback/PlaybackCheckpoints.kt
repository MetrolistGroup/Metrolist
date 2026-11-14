package com.metrolist.shared.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

const val PLAYBACK_CHECKPOINT_INTERVAL_MS = 30_000L

suspend fun observePlaybackCheckpoints(
    playing: Flow<Boolean>,
    enabled: Flow<Boolean>,
    checkpoint: suspend () -> Unit,
) {
    combine(playing, enabled) { isPlaying, isEnabled -> isPlaying to isEnabled }
        .distinctUntilChanged()
        .collectLatest { (isPlaying, isEnabled) ->
            checkpoint()
            while (isPlaying && isEnabled) {
                delay(PLAYBACK_CHECKPOINT_INTERVAL_MS)
                checkpoint()
            }
        }
}
