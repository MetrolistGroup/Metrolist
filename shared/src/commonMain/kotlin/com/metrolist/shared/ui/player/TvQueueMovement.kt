package com.metrolist.shared.ui.player

internal data class TvQueueMovement(
    val enabled: Boolean = false,
    val grabbedEntryKey: Long? = null,
) {
    fun grab(entryKey: Long) = TvQueueMovement(enabled = true, grabbedEntryKey = entryKey)

    fun place() = copy(grabbedEntryKey = null)

    fun reconcile(entryKeys: List<Long>): TvQueueMovement = if (grabbedEntryKey != null && grabbedEntryKey !in entryKeys) place() else this

    fun target(
        entryKeys: List<Long>,
        direction: Int,
    ): Long? {
        if (!enabled || grabbedEntryKey == null || (direction != -1 && direction != 1)) return null
        val index = entryKeys.indexOf(grabbedEntryKey)
        return if (index >= 0) entryKeys.getOrNull(index + direction) else null
    }
}
