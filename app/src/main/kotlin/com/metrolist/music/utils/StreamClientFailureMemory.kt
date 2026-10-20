package com.metrolist.music.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers stream clients that failed recently, across tracks.
 *
 * A client that cannot deliver data on the current network fails the same way on the next track;
 * retrying it for every new video costs one full stall timeout per song.
 */
internal class StreamClientFailureMemory(
    private val ttlMs: Long = 5 * 60 * 1000L,
) {
    private val failedAtMs = ConcurrentHashMap<String, Long>()

    fun mark(clientName: String, nowMs: Long = System.currentTimeMillis()) {
        failedAtMs[clientName] = nowMs
    }

    fun recent(nowMs: Long = System.currentTimeMillis()): Set<String> {
        failedAtMs.entries.removeAll { (nowMs - it.value) !in 0 until ttlMs }
        return failedAtMs.keys.toSet()
    }

    fun clear() {
        failedAtMs.clear()
    }
}
