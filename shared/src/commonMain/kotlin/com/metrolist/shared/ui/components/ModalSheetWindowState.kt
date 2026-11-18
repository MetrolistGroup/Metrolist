package com.metrolist.shared.ui.components

internal class ModalSheetWindowState {
    private var wasRequested = false
    private var hasBeenVisible = false

    fun shouldMount(
        requestedVisible: Boolean,
        sheetVisible: Boolean,
    ): Boolean {
        // A new presentation needs a hidden window for anchor measurement, but later hidden frames must release it.
        if (requestedVisible && !wasRequested) hasBeenVisible = false
        wasRequested = requestedVisible
        if (sheetVisible) hasBeenVisible = true
        return sheetVisible || (requestedVisible && !hasBeenVisible)
    }
}
