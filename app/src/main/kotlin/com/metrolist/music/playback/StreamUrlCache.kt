/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

internal class StreamUrlCache(
    private val maxEntries: Int = 500,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val url: String,
        val expiresAtMillis: Long,
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > maxEntries
        }

    init {
        require(maxEntries > 0) { "maxEntries must be greater than zero" }
    }

    operator fun get(mediaId: String): String? =
        synchronized(entries) {
            val entry = entries[mediaId] ?: return@synchronized null
            if (entry.expiresAtMillis <= currentTimeMillis()) {
                entries.remove(mediaId)
                null
            } else {
                entry.url
            }
        }

    fun put(
        mediaId: String,
        url: String,
        expiresInSeconds: Int,
    ) {
        val now = currentTimeMillis()
        val ttlMillis = expiresInSeconds.coerceAtLeast(0).toLong() * 1_000L
        val expiresAtMillis =
            runCatching { Math.addExact(now, ttlMillis) }
                .getOrDefault(Long.MAX_VALUE)

        synchronized(entries) {
            entries[mediaId] = Entry(url, expiresAtMillis)
        }
    }

    fun invalidate(mediaId: String) {
        synchronized(entries) {
            entries.remove(mediaId)
        }
    }
}
