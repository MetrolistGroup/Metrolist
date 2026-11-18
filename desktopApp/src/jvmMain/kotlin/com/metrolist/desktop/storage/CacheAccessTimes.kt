package com.metrolist.desktop.storage

// Guarded by the cache index lock; access updates never inspect or republish files.
internal class CacheAccessTimes {
    private val pending = mutableMapOf<String, Long>()
    val isEmpty: Boolean get() = pending.isEmpty()

    fun record(
        songId: String,
        timestamp: Long,
    ) {
        pending[songId] = pending[songId]?.let { maxOf(it, timestamp) } ?: timestamp
    }

    fun effective(entry: CachedSongEntry): Long = maxOf(entry.lastAccessedAt, pending[entry.songId] ?: Long.MIN_VALUE)

    fun applyTo(entries: List<CachedSongEntry>): List<CachedSongEntry> =
        entries.map { entry ->
            val latest = effective(entry)
            if (latest != entry.lastAccessedAt) entry.copy(lastAccessedAt = latest) else entry
        }

    fun clear() = pending.clear()
}
