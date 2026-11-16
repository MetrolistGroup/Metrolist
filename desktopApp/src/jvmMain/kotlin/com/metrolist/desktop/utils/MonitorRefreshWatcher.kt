package com.metrolist.desktop.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

internal class MonitorRefreshWatcher {
    private val visibleWindows = mutableSetOf<Any>()
    private val visible = MutableStateFlow(false)
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun setWindowVisible(
        window: Any,
        isVisible: Boolean,
    ) {
        synchronized(visibleWindows) {
            if (isVisible) visibleWindows.add(window) else visibleWindows.remove(window)
            visible.value = visibleWindows.isNotEmpty()
        }
    }

    fun requestRefresh() {
        requests.trySend(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun watch(refresh: () -> Unit) {
        visible.collectLatest { hasVisibleWindow ->
            if (!hasVisibleWindow) return@collectLatest
            var requested = true
            while (true) {
                if (requested) {
                    delay(2_000L)
                    while (requests.tryReceive().isSuccess) Unit
                }
                refresh()
                requested =
                    select {
                        requests.onReceive { true }
                        onTimeout(30_000L) { false }
                    }
            }
        }
    }
}
