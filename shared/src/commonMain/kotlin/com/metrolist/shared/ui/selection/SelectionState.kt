package com.metrolist.shared.ui.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
class SelectionState {
    var active by mutableStateOf(false)
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    val count: Int get() = selectedIds.size

    fun isSelected(id: String): Boolean = id in selectedIds

    fun begin(id: String) {
        active = true
        selectedIds = selectedIds + id
    }

    fun toggle(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun allSelected(ids: Collection<String>): Boolean = ids.isNotEmpty() && selectedIds.containsAll(ids)

    fun toggleAll(ids: Collection<String>) {
        selectedIds = if (allSelected(ids)) selectedIds - ids.toSet() else selectedIds + ids
    }

    fun retain(ids: Collection<String>) {
        val visible = ids.toSet()
        if (!visible.containsAll(selectedIds)) selectedIds = selectedIds.intersect(visible)
    }

    fun replaceWith(ids: Set<String>) {
        selectedIds = ids
    }

    fun exit() {
        active = false
        selectedIds = emptySet()
    }
}

@Composable
fun rememberSelectionState(vararg keys: Any?): SelectionState = remember(*keys) { SelectionState() }
