package com.metrolist.android.playback

/** The saved position belongs to one queue occurrence, not every copy of the same song. */
internal class PendingQueueRestorePosition {
    private var entryKey: Long? = null
    private var positionMs: Long? = null

    fun restore(
        selectedEntryKey: Long?,
        savedPositionMs: Long,
    ) {
        entryKey = selectedEntryKey
        positionMs = savedPositionMs.takeIf { it > 0L }
    }

    fun update(
        selectedEntryKey: Long?,
        requestedPositionMs: Long,
    ) {
        if (entryKey != null && selectedEntryKey == entryKey) positionMs = requestedPositionMs.coerceAtLeast(0L)
    }

    fun take(
        selectedEntryKey: Long?,
        requestedPositionMs: Long,
    ): Long {
        val saved = positionMs?.takeIf { entryKey != null && selectedEntryKey == entryKey }
        clear()
        return (saved ?: requestedPositionMs).coerceAtLeast(0L)
    }

    fun clear() {
        entryKey = null
        positionMs = null
    }
}
