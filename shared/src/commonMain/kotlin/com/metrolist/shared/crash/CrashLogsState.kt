package com.metrolist.shared.crash

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class CrashLogsState(
    private val store: CrashStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _reports = MutableStateFlow<List<CrashReport>>(emptyList())
    val reports = _reports.asStateFlow()

    suspend fun load() =
        withContext(dispatcher) {
            mutex.withLock { _reports.value = store.list() }
        }

    suspend fun clear() =
        withContext(dispatcher) {
            mutex.withLock {
                store.clear()
                _reports.value = store.list()
            }
        }
}
