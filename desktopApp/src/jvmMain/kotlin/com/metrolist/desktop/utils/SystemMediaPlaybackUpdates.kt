package com.metrolist.desktop.utils

import com.sun.jna.Platform
import dev.nucleusframework.media.control.MediaPlaybackState
import dev.nucleusframework.media.control.MediaPlaybackStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun systemMediaPlaybackUpdates(
    changes: Flow<MediaPlaybackState>,
    positions: StateFlow<Long>,
    isLinux: Boolean = Platform.isLinux(),
    currentPositionMs: () -> Long,
): Flow<MediaPlaybackState> {
    if (isLinux) return linuxSystemMediaPlaybackUpdates(changes, positions, currentPositionMs)
    return changes.flatMapLatest { state ->
        flow {
            emit(state)
            val updates = if (state.status == MediaPlaybackStatus.PLAYING) positions.sample(1_000L) else positions.drop(1)
            updates.collect { emit(state.copy(positionMs = currentPositionMs())) }
        }
    }
}

private fun linuxSystemMediaPlaybackUpdates(
    changes: Flow<MediaPlaybackState>,
    positions: StateFlow<Long>,
    currentPositionMs: () -> Long,
): Flow<MediaPlaybackState> =
    channelFlow {
        var anchors: Job? = null
        changes.collect { state ->
            anchors?.cancelAndJoin()
            // Explicit playback and seek events must be delivered in order.
            send(state)
            anchors =
                launch {
                    if (state.status == MediaPlaybackStatus.PLAYING) {
                        while (true) {
                            delay(250L)
                            trySend(state.copy(positionMs = currentPositionMs()))
                        }
                    } else {
                        positions.drop(1).collect { send(state.copy(positionMs = currentPositionMs())) }
                    }
                }
        }
        anchors?.join()
    }.buffer(0) // Skip only periodic Playing anchors when the publisher is busy.
