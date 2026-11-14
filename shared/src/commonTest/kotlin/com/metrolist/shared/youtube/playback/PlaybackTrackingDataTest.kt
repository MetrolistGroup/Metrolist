package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.extraction.PlaybackTrackingData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackTrackingDataTest {
    @Test
    fun recoveredResourceKeepsLogicalPlaybackNonce() {
        val resource =
            PlaybackResource(
                videoId = "video",
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?cpn=fresh",
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                playbackTracking =
                    PlaybackTrackingData(
                        clientPlaybackNonce = "fresh",
                        playbackUrl = null,
                        watchtimeUrl = null,
                        scheduledFlushWalltimeSeconds = null,
                        defaultFlushIntervalSeconds = null,
                        resolvedAtEpochMs = 0L,
                    ),
            )

        val recovered = resource.withClientPlaybackNonce("0123456789abcdef")

        assertEquals(
            "https://rr1---sn-test.googlevideo.com/videoplayback?cpn=0123456789abcdef",
            recovered.url,
        )
        assertEquals("0123456789abcdef", recovered.playbackTracking?.clientPlaybackNonce)
    }

    @Test
    fun restartingCancelledPlaybackUsesDistinctReportingIdentities() {
        val original =
            PlaybackResource(
                videoId = "video",
                url = "https://rr1---sn-test.googlevideo.com/videoplayback?cpn=cancelled&sig=signed",
                headers = mapOf("Range" to "bytes=0-"),
                loudnessDb = 4.0,
                expiresAt = null,
                playbackTracking =
                    PlaybackTrackingData(
                        clientPlaybackNonce = "cancelled",
                        playbackUrl = "https://music.youtube.com/api/stats/playback?docid=video",
                        watchtimeUrl = "https://music.youtube.com/api/stats/watchtime?docid=video",
                        scheduledFlushWalltimeSeconds = null,
                        defaultFlushIntervalSeconds = null,
                        resolvedAtEpochMs = 1L,
                    ),
            )
        val retry = original.withFreshClientPlaybackNonce()
        val nextRetry = retry.withFreshClientPlaybackNonce()

        assertEquals(3, listOf(original, retry, nextRetry).map { it.playbackTracking?.clientPlaybackNonce }.toSet().size)
        for (resource in listOf(retry, nextRetry)) {
            val nonce = requireNotNull(resource.playbackTracking).clientPlaybackNonce
            assertEquals(original.withClientPlaybackNonce(nonce), resource)
            assertTrue(resource.url.contains("cpn=$nonce&sig=signed"))
        }
    }
}
