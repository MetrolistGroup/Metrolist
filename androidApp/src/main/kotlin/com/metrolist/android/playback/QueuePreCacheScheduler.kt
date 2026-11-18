package com.metrolist.android.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class QueuePreCacheScheduler(
    private val scope: CoroutineScope,
) {
    private var cacheJob: Job? = null
    private var cacheRequest: (suspend () -> Unit)? = null

    fun schedule(cache: suspend () -> Unit) {
        cacheRequest = cache
        if (cacheJob == null) startCache()
    }

    fun cancel() {
        cacheRequest = null
        cacheJob?.cancel()
    }

    private fun startCache() {
        val request = cacheRequest ?: return
        cacheRequest = null
        // Install ownership and cleanup even when cancellation happens before the worker starts.
        val job = scope.launch(start = CoroutineStart.LAZY) { request() }
        cacheJob = job
        job.invokeOnCompletion {
            if (cacheJob === job) {
                cacheJob = null
                startCache()
            }
        }
        job.start()
    }
}
