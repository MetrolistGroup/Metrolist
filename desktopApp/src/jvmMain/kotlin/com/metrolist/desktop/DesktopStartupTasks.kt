package com.metrolist.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun CoroutineScope.launchVisitorDataFetch(
    shouldFetch: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    fetch: suspend () -> String?,
    save: (String) -> Unit,
    onBlank: () -> Unit = {},
    onFailure: (Throwable) -> Unit = {},
): Job? {
    if (!shouldFetch) return null
    return launch {
        try {
            val visitorData = withContext(dispatcher) { fetch() }
            if (visitorData.isNullOrBlank()) onBlank() else save(visitorData)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onFailure(error)
        }
    }
}
