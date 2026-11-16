package com.metrolist.shared.playback

import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class IosPreparedPlaybackTest {
    private val now = Instant.fromEpochMilliseconds(10_000L)
    private val resource = PlaybackResource("song", "https://example.test/audio", emptyMap(), null, null)

    private fun rejection(
        prepared: PlaybackResource = resource,
        requested: PlaybackResource = resource,
        ready: Boolean = true,
        failed: Boolean = false,
        tapInstalled: Boolean = true,
    ) = iosPreparedPlaybackRejection(prepared, requested, requested.url, requested.headers, ready, failed, tapInstalled, now)

    @Test
    fun onlyReadyMatchingItemsWithInstalledTapsAreReused() {
        assertNull(rejection())
        assertNull(iosPreparedPlaybackRejection(resource, null, resource.url, resource.headers, true, false, true, now))
        assertEquals("item-loading", rejection(ready = false))
        assertEquals("item-failed", rejection(ready = false, failed = true))
        assertEquals("tap-not-installed", rejection(tapInstalled = false))
        assertEquals("resource-mismatch", rejection(requested = resource.copy(videoId = "other")))
        assertEquals("resource-mismatch", rejection(requested = resource.copy(url = "https://example.test/other")))
        assertEquals("resource-mismatch", rejection(requested = resource.copy(headers = mapOf("Range" to "bytes=0-"))))
    }

    @Test
    fun expiryIncludesTheBoundaryAndHlsNeedsNoTap() {
        assertEquals("resource-expired", rejection(prepared = resource.copy(expiresAt = now)))
        assertEquals("resource-expired", rejection(prepared = resource.copy(expiresAt = Instant.fromEpochMilliseconds(9_999L))))
        assertNull(rejection(prepared = resource.copy(expiresAt = Instant.fromEpochMilliseconds(10_001L))))
        val hls = resource.copy(mimeType = "application/x-mpegURL")
        assertNull(rejection(prepared = hls, requested = hls, tapInstalled = false))
    }
}
