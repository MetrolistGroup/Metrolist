package com.metrolist.android.widget

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

internal fun shouldSampleWidgetProgress(
    isPlaying: Boolean,
    isReady: Boolean,
    hasSong: Boolean,
    duration: Long,
    isInteractive: Boolean,
    hasProgressWidget: Boolean,
): Boolean = isPlaying && isReady && hasSong && duration > 0 && isInteractive && hasProgressWidget

internal suspend fun sampleWidgetProgress(
    active: Flow<Boolean>,
    update: suspend () -> Unit,
) {
    active.distinctUntilChanged().collectLatest { enabled ->
        if (enabled) {
            while (currentCoroutineContext().isActive) {
                update()
                delay(10_000)
            }
        }
    }
}
