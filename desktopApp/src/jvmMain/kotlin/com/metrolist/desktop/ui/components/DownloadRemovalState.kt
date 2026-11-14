package com.metrolist.desktop.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class DownloadRemovalState {
    var pendingIds by mutableStateOf<Set<String>?>(null)
        private set

    fun request(ids: Collection<String>) {
        pendingIds = ids.toSet().takeIf { it.isNotEmpty() }
    }

    fun dismiss() {
        pendingIds = null
    }

    fun confirm(remove: (Set<String>) -> Unit) {
        val ids = pendingIds ?: return
        pendingIds = null
        remove(ids)
    }
}
