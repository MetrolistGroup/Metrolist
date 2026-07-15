package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class StreamUrlCacheTest {
    @Test
    fun `fresh entry is returned`() {
        var now = 1_000L
        val cache = StreamUrlCache(currentTimeMillis = { now })

        cache.put("song", "https://example.com/stream", expiresInSeconds = 10)
        now += 9_999L

        assertEquals("https://example.com/stream", cache["song"])
    }

    @Test
    fun `entry is evicted at expiry boundary`() {
        var now = 1_000L
        val cache = StreamUrlCache(currentTimeMillis = { now })

        cache.put("song", "https://example.com/stream", expiresInSeconds = 10)
        now += 10_000L

        assertNull(cache["song"])
        now = 1_000L
        assertNull(cache["song"])
    }

    @Test
    fun `entry can be invalidated explicitly`() {
        val cache = StreamUrlCache(currentTimeMillis = { 1_000L })
        cache.put("song", "https://example.com/stream", expiresInSeconds = 10)

        cache.invalidate("song")

        assertNull(cache["song"])
    }

    @Test
    fun `least recently used entry is evicted at capacity`() {
        val cache = StreamUrlCache(maxEntries = 2, currentTimeMillis = { 1_000L })
        cache.put("first", "https://example.com/first", expiresInSeconds = 10)
        cache.put("second", "https://example.com/second", expiresInSeconds = 10)
        assertEquals("https://example.com/first", cache["first"])

        cache.put("third", "https://example.com/third", expiresInSeconds = 10)

        assertNull(cache["second"])
        assertEquals("https://example.com/first", cache["first"])
        assertEquals("https://example.com/third", cache["third"])
    }

    @Test
    fun `concurrent access remains consistent`() {
        val cache = StreamUrlCache(maxEntries = 32, currentTimeMillis = { 1_000L })
        val executor = Executors.newFixedThreadPool(8)

        try {
            val tasks =
                (0 until 1_000).map { index ->
                    Callable {
                        val mediaId = "song-${index % 32}"
                        val url = "https://example.com/$index"
                        cache.put(mediaId, url, expiresInSeconds = 10)
                        cache[mediaId]
                        if (index % 5 == 0) cache.invalidate(mediaId)
                    }
                }

            executor.invokeAll(tasks).forEach { it.get() }
            cache.put("final", "https://example.com/final", expiresInSeconds = 10)

            assertEquals("https://example.com/final", cache["final"])
        } finally {
            executor.shutdownNow()
        }
    }
}
