package com.metrolist.shared.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object YouTubeSyncState {
    class Failure internal constructor(
        val operation: String,
        val retry: suspend () -> Unit,
    )

    private val pending = MutableStateFlow<Map<String, Failure>>(emptyMap())
    val failures = pending.asStateFlow()

    fun failed(
        operation: String,
        retry: suspend () -> Unit,
    ) {
        pending.update { if (operation in it) it else it + (operation to Failure(operation, retry)) }
    }

    fun succeeded(operation: String) {
        pending.update { it - operation }
    }

    fun clear() {
        pending.value = emptyMap()
    }
}
