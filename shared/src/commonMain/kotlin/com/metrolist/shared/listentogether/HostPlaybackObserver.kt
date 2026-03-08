package com.metrolist.shared.listentogether

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

internal suspend fun observeHostPlayback(
    changes: Flow<Boolean>,
    intervalMs: Long,
    publish: suspend () -> Unit,
) {
    changes.collectLatest { playing ->
        publish()
        while (playing) {
            delay(intervalMs)
            publish()
        }
    }
}
