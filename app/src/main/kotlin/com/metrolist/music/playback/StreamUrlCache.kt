/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec

internal data class CachedStreamUrl(
    val url: String,
    val requestHeaders: Map<String, String>,
    val clientName: String,
    val requireBoundedRange: Boolean = false,
    val rangeChunkSizeBytes: Long = 0L,
    val useRangeChunks: Boolean = false,
)

internal fun DataSpec.withResolvedStream(stream: CachedStreamUrl): DataSpec {
    val resolved =
        withUri(stream.url.toUri())
            .withRequestHeaders(httpRequestHeaders + stream.requestHeaders)
    if ((!stream.requireBoundedRange && !stream.useRangeChunks) || stream.rangeChunkSizeBytes <= 0L) {
        return resolved
    }
    val boundedLength =
        if (length == C.LENGTH_UNSET.toLong()) {
            stream.rangeChunkSizeBytes
        } else {
            minOf(length, stream.rangeChunkSizeBytes)
        }
    return resolved.subrange(0, boundedLength)
}

internal class StreamUrlCache(
    private val maxEntries: Int = 500,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val stream: CachedStreamUrl,
        val expiresAtMillis: Long,
    )

    private data class GenerationState(
        var generation: Long = 0L,
        var activeFetches: Int = 0,
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
                size > maxEntries
        }
    // Historical invalidations are not retained: only in-flight resolutions need this state.
    private val generations = HashMap<String, GenerationState>()

    init {
        require(maxEntries > 0) { "maxEntries must be greater than zero" }
    }

    operator fun get(mediaId: String): CachedStreamUrl? =
        synchronized(entries) {
            val entry = entries[mediaId] ?: return@synchronized null
            if (entry.expiresAtMillis <= currentTimeMillis()) {
                entries.remove(mediaId)
                advanceGeneration(mediaId)
                null
            } else {
                entry.stream
            }
        }

    fun clientName(mediaId: String): String? =
        synchronized(entries) { entries[mediaId]?.stream?.clientName }

    fun generation(mediaId: String): Long =
        synchronized(entries) { generations[mediaId]?.generation ?: 0L }

    fun beginFetch(mediaId: String): Long =
        synchronized(entries) {
            val state = generations.getOrPut(mediaId) { GenerationState() }
            state.activeFetches++
            state.generation
        }

    fun finishFetch(mediaId: String) {
        synchronized(entries) {
            val state = generations[mediaId] ?: return
            state.activeFetches--
            if (state.activeFetches <= 0) generations.remove(mediaId)
        }
    }

    fun put(
        mediaId: String,
        url: String,
        requestHeaders: Map<String, String>,
        clientName: String,
        expiresInSeconds: Int,
        requireBoundedRange: Boolean = false,
        rangeChunkSizeBytes: Long = 0L,
        useRangeChunks: Boolean = false,
        expectedGeneration: Long = generation(mediaId),
    ): Boolean {
        val now = currentTimeMillis()
        val ttlMillis = expiresInSeconds.coerceAtLeast(0).toLong() * 1_000L
        val expiresAtMillis =
            runCatching { Math.addExact(now, ttlMillis) }
                .getOrDefault(Long.MAX_VALUE)

        synchronized(entries) {
            if ((generations[mediaId]?.generation ?: 0L) != expectedGeneration) return false
            entries[mediaId] =
                Entry(
                    stream =
                        CachedStreamUrl(
                            url = url,
                            requestHeaders = requestHeaders.toMap(),
                            clientName = clientName,
                            requireBoundedRange = requireBoundedRange,
                            rangeChunkSizeBytes = rangeChunkSizeBytes,
                            useRangeChunks = useRangeChunks,
                        ),
                    expiresAtMillis = expiresAtMillis,
                )
            return true
        }
    }

    fun invalidate(mediaId: String) {
        synchronized(entries) {
            entries.remove(mediaId)
            advanceGeneration(mediaId)
        }
    }

    private fun advanceGeneration(mediaId: String) {
        // Without an active resolver there is no stale writer to reject.
        generations[mediaId]?.let { state ->
            if (state.activeFetches > 0) state.generation++
        }
    }
}
