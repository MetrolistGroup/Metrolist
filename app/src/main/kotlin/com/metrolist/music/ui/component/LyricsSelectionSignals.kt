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
 * [requestClearSelection] after a successful write; the reader clears.
 * Stale buffered clears are harmless (clearing is idempotent).
 */
object LyricsSelectionSignals {
    private val _clearSelection = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val clearSelection: SharedFlow<Unit> = _clearSelection.asSharedFlow()

    fun requestClearSelection() {
        _clearSelection.tryEmit(Unit)
    }
}
