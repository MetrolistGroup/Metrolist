/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Cross-composition signal for lyric selection.
 *
 * Selection state lives in the lyrics reader, but the writes that invalidate it
 * (refetch, search-pick, manual edit) happen in the menu — and may rewrite
 * byte-identical text, which no state key can observe. Emitters call
 * [requestClearSelection] after a successful write; the reader clears only if
 * it is still showing that song, so a late signal from a previous song cannot
 * wipe a fresh selection. Clearing is otherwise idempotent.
 */
object LyricsSelectionSignals {
    private val _clearSelection = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val clearSelection: SharedFlow<String> = _clearSelection.asSharedFlow()

    fun requestClearSelection(songId: String) {
        _clearSelection.tryEmit(songId)
    }
}
