package com.metrolist.shared.playlist

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object PlaylistChanges {
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val flow = changes.asSharedFlow()

    suspend fun notifyChanged(id: String) {
        changes.emit(id.removePrefix("VL"))
    }
}
