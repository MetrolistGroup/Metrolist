package com.metrolist.desktop.utils

import com.sun.jna.Platform
import dev.nucleusframework.media.control.MediaPlaybackState
import dev.nucleusframework.media.control.MediaPlaybackStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.sample

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun systemMediaPlaybackUpdates(
    changes: Flow<MediaPlaybackState>,
    positions: StateFlow<Long>,
    isLinux: Boolean = Platform.isLinux(),
    currentPositionMs: () -> Long,
): Flow<MediaPlaybackState> =
    changes.flatMapLatest { state ->
        flow {
            emit(state)
            if (isLinux && state.status == MediaPlaybackStatus.PLAYING) {
                while (true) {
                    delay(250L)
                    emit(state.copy(positionMs = currentPositionMs()))
                }
            }
            val updates = if (state.status == MediaPlaybackStatus.PLAYING) positions.sample(1_000L) else positions.drop(1)
            updates.collect { emit(state.copy(positionMs = currentPositionMs())) }
        }
    }
