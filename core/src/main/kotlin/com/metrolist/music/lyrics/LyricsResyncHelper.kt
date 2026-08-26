/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object LyricsResyncHelper {
    private val _resyncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resyncTrigger: SharedFlow<Unit> = _resyncTrigger.asSharedFlow()

    fun triggerResync() {
        _resyncTrigger.tryEmit(Unit)
    }
}
