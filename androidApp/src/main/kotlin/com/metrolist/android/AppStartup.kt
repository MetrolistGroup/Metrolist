package com.metrolist.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

internal class AppStartup {
    private val completion = CompletableDeferred<Unit>()
    val isReady: Boolean get() = completion.isCompleted && !completion.isCancelled

    suspend fun await() = completion.await()

    suspend fun initialize(
        restore: suspend () -> Unit,
        start: suspend () -> Unit,
    ) {
        try {
            restore()
            start()
            completion.complete(Unit)
        } catch (error: Throwable) {
            completion.cancel(CancellationException("Application startup failed", error))
            throw error
        }
    }
}
