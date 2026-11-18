package com.metrolist.android.utils

import android.content.BroadcastReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

internal fun BroadcastReceiver.launchAsync(block: suspend CoroutineScope.() -> Unit): Job {
    val pendingResult = goAsync()
    return CoroutineScope(Dispatchers.Main).launchAsyncBroadcast(finish = { pendingResult?.finish() }, block = block)
}

internal fun CoroutineScope.launchAsyncBroadcast(
    finish: () -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val finished = AtomicBoolean()

    fun finishOnce() {
        if (!finished.compareAndSet(false, true)) return
        try {
            finish()
        } catch (error: IllegalStateException) {
            if (error.message != "Broadcast already finished") throw error
            // Android may have finished the broadcast before the async work completes.
            Timber.w(error, "Async broadcast was already finished")
        }
    }
    return try {
        launch {
            try {
                withTimeout(8_000L, block)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "Async broadcast failed")
            }
        }.also { job -> job.invokeOnCompletion { finishOnce() } }
    } catch (error: Throwable) {
        finishOnce()
        throw error
    }
}
