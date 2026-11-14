package com.metrolist.desktop.storage

// Guarded by the cache index lock; access updates never inspect or republish files.
internal class CacheAccessTimes {
    private val pending = mutableMapOf<String, Long>()
    val isEmpty: Boolean get() = pending.isEmpty()

    fun record(
        songId: String,
        timestamp: Long,
    ) {
        pending[songId] = timestamp
    }

    fun applyTo(entries: List<CachedSongEntry>): List<CachedSongEntry> =
        entries.map { entry -> pending[entry.songId]?.let { entry.copy(lastAccessedAt = it) } ?: entry }

    fun clear() = pending.clear()
}
