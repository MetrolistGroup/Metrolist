package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class StreamUrlCacheTest {
    @Test
    fun `fresh entry is returned with request headers`() {
        var now = 1_000L
        val cache = StreamUrlCache(currentTimeMillis = { now })
        val headers = mapOf("User-Agent" to "test-client")

        cache.put("song", "https://example.com/stream", headers, "WEB_REMIX", expiresInSeconds = 10)
        now += 9_999L

        assertEquals(CachedStreamUrl("https://example.com/stream", headers, "WEB_REMIX"), cache["song"])
    }

    @Test
    fun `entry is evicted at expiry boundary`() {
        var now = 1_000L
        val cache = StreamUrlCache(currentTimeMillis = { now })
        val generationBeforeExpiry = cache.generation("song")

        cache.put("song", "https://example.com/stream", emptyMap(), "WEB_REMIX", expiresInSeconds = 10)
        now += 10_000L

        assertNull(cache["song"])
        assertEquals(generationBeforeExpiry + 1, cache.generation("song"))
        now = 1_000L
        assertNull(cache["song"])
    }

    @Test
    fun `entry can be invalidated explicitly`() {
        val cache = StreamUrlCache(currentTimeMillis = { 1_000L })
        cache.put("song", "https://example.com/stream", emptyMap(), "WEB_REMIX", expiresInSeconds = 10)

        cache.invalidate("song")

        assertNull(cache["song"])
    }

    @Test
    fun `least recently used entry is evicted at capacity`() {
        val cache = StreamUrlCache(maxEntries = 2, currentTimeMillis = { 1_000L })
        cache.put("first", "https://example.com/first", emptyMap(), "WEB_REMIX", expiresInSeconds = 10)
        cache.put("second", "https://example.com/second", emptyMap(), "WEB_REMIX", expiresInSeconds = 10)
        assertEquals("https://example.com/first", cache["first"]?.url)

        cache.put("third", "https://example.com/third", emptyMap(), "WEB_REMIX", expiresInSeconds = 10)

        assertNull(cache["second"])
        assertEquals("https://example.com/first", cache["first"]?.url)
        assertEquals("https://example.com/third", cache["third"]?.url)
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
                        cache.put(mediaId, url, emptyMap(), "WEB_REMIX", expiresInSeconds = 10)
                        cache[mediaId]
                        if (index % 5 == 0) cache.invalidate(mediaId)
                    }
                }

            executor.invokeAll(tasks).forEach { it.get() }
            cache.put("final", "https://example.com/final", emptyMap(), "WEB_REMIX", expiresInSeconds = 10)

            assertEquals("https://example.com/final", cache["final"]?.url)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `invalidation rejects a stale in-flight write`() {
        val cache = StreamUrlCache(currentTimeMillis = { 1_000L })
        val generationBeforeResolution = cache.generation("song")

        cache.invalidate("song")
        val inserted = cache.put(
            mediaId = "song",
            url = "https://example.com/stale",
            requestHeaders = emptyMap(),
            clientName = "WEB_REMIX",
            expiresInSeconds = 10,
            expectedGeneration = generationBeforeResolution,
        )

        assertEquals(false, inserted)
        assertNull(cache["song"])
    }

    @Test
    fun `invalidation does not reject another media item write`() {
        val cache = StreamUrlCache(currentTimeMillis = { 1_000L })
        val firstGeneration = cache.generation("first")

        cache.invalidate("second")
        val inserted = cache.put(
            mediaId = "first",
            url = "https://example.com/first",
            requestHeaders = emptyMap(),
            clientName = "WEB_REMIX",
            expiresInSeconds = 10,
            expectedGeneration = firstGeneration,
        )

        assertEquals(true, inserted)
        assertEquals("https://example.com/first", cache["first"]?.url)
    }
}
