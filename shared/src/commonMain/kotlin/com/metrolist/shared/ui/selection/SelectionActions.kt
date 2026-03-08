package com.metrolist.shared.ui.selection

internal data class SelectionActions<T>(
    val primary: List<T>,
    val overflow: List<T>,
)

internal fun <T> splitSelectionActions(
    primary: List<T>,
    overflow: List<T>,
    slots: Int,
): SelectionActions<T> {
    val needsOverflow = overflow.isNotEmpty() || primary.size > slots
    val visibleCount = (slots - if (needsOverflow) 1 else 0).coerceAtLeast(0)
    return SelectionActions(primary.take(visibleCount), primary.drop(visibleCount) + overflow)
}
