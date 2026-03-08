package com.metrolist.shared.playback

import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SabrCacheDeferralTest {
    @Test
    fun waitsForCurrentAndPreloadedOwnersButReleasesEndedAndReplacedTracks() {
        val resource =
            PlaybackResource(
                "song",
                "sabr://song",
                emptyMap(),
                null,
                null,
                sabrBootstrap =
                    SabrBootstrap(
                        videoId = "song",
                        serverAbrStreamingUrl = "https://example.com/sabr",
                        videoPlaybackUstreamerConfig = byteArrayOf(1),
                        clientName = 1,
                        clientVersion = "test",
                        audioFormat = SabrFormatId(251),
                        discardVideoFormat = SabrFormatId(136),
                        discardVideoHeight = 720,
                        durationMs = 30_000L,
                        contentLengthBytes = 1024L,
                        mimeType = "audio/webm",
                    ),
            )
        assertTrue(shouldDeferSabrCache("song", resource, 1, 0, false))
        assertFalse(shouldDeferSabrCache("song", resource, 1, 1, false))
        assertTrue(shouldDeferSabrCache("song", resource, 1, 1, true))
        assertFalse(shouldDeferSabrCache("song", resource.copy(videoId = "next"), 2, 1, false))
        assertTrue(shouldDeferSabrCache("song", resource, 2, 1, false))
        assertTrue(shouldDeferSabrCache("counterpart", resource.copy(musicVideoId = "counterpart"), 2, 1, false))
        assertFalse(shouldDeferSabrCache("song", null, 0, 1, false))
    }
}
