package com.metrolist.desktop.state.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

internal suspend fun awaitSabrCacheReady(
    changes: Flow<Unit>,
    enabled: () -> Boolean,
    deferred: () -> Boolean,
) {
    // The state flows emit on subscription, so release before subscription cannot strand a waiter.
    changes.first {
        if (!enabled()) throw CancellationException("Song cache disabled")
        !deferred()
    }
}
