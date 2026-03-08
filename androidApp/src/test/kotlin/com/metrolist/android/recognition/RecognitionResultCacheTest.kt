package com.metrolist.android.recognition

import com.metrolist.shared.recognition.RecognitionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecognitionResultCacheTest {
    @Test
    fun boundsEntriesExpiresResultsAndDistinguishesHashCollisions() {
        val cache = RecognitionResultCache()
        val first = RecognitionResult("first", "First", "Artist")
        val second = first.copy(trackId = "second")
        assertEquals("Aa".hashCode(), "BB".hashCode())
        cache.put("Aa", first, 0)
        cache.put("BB", second, 0)
        assertEquals(first, cache.get("Aa", 1))
        assertEquals(second, cache.get("BB", 1))
        repeat(31) { cache.put("signature-$it", first, 0) }
        assertNull(cache.get("Aa", 1))
        assertEquals(second, cache.get("BB", 1))
        assertEquals(first, cache.get("signature-30", 299_999))
        assertNull(cache.get("signature-30", 300_000))
    }
}
