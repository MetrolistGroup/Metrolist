package com.metrolist.android.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class QueuePreCacheScheduler(
    private val scope: CoroutineScope,
) {
    private var cacheJob: Job? = null
    private var cacheRequest: (suspend () -> Unit)? = null

    fun schedule(cache: suspend () -> Unit) {
        cacheRequest = cache
        if (cacheJob?.isActive != true) startCache()
    }

    fun cancel() {
        cacheRequest = null
        cacheJob?.cancel()
    }

    private fun startCache() {
        val request = cacheRequest ?: return
        cacheJob =
            scope.launch {
                try {
                    request()
                } finally {
                    cacheJob = null
                    if (cacheRequest === request) cacheRequest = null
                    startCache()
                }
            }
    }
}
