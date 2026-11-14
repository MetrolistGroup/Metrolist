package com.metrolist.android.playback

import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AudioCacheResourceTest {
    @Test
    fun fileCachingAcceptsFreshDirectAudioAndRejectsLocalExpiredAndManifestResources() {
        val now = Instant.fromEpochSeconds(1_000L)
        val direct = PlaybackResource("video", "https://example.com/audio", emptyMap(), null, now + 120.seconds, mimeType = "audio/webm")
        assertSame(direct, cacheableAudioResource(direct, now))
        assertNull(cacheableAudioResource(null, now))
        assertNull(cacheableAudioResource(direct.copy(expiresAt = now + 60.seconds), now))
        assertNull(cacheableAudioResource(direct.copy(url = "file:/cached/audio"), now))
        assertNull(cacheableAudioResource(direct.copy(profileId = "cached-audio"), now))
        assertNull(cacheableAudioResource(direct.copy(mimeType = "application/dash+xml"), now))
        assertNull(cacheableAudioResource(direct.copy(url = "https://example.com/audio.m3u8"), now))
    }

    @Test
    fun fileCachingAcceptsResolvedSabrAudioAndPreservesFreshnessAndLocalResourceGuards() {
        val now = Instant.fromEpochSeconds(1_000L)
        val sabr =
            SabrBootstrap(
                videoId = "video",
                serverAbrStreamingUrl = "https://example.com/sabr",
                videoPlaybackUstreamerConfig = byteArrayOf(1),
                clientName = 1,
                clientVersion = "test",
                audioFormat = SabrFormatId(251),
                discardVideoFormat = SabrFormatId(136),
                discardVideoHeight = 720,
                durationMs = 30_000L,
                contentLengthBytes = 1_000L,
                mimeType = "audio/webm",
            )
        val resource =
            PlaybackResource(
                videoId = "video",
                url = "sabr://video",
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = now + 120.seconds,
                sabrBootstrap = sabr,
            )
        assertSame(resource, cacheableAudioResource(resource, now))
        val withoutExpiry = resource.copy(expiresAt = null)
        assertSame(withoutExpiry, cacheableAudioResource(withoutExpiry, now))
        assertNull(cacheableAudioResource(resource.copy(expiresAt = now + 60.seconds), now))
        assertNull(cacheableAudioResource(resource.copy(expiresAt = now - 1.seconds), now))
        assertNull(cacheableAudioResource(resource.copy(url = "file:/cached/audio"), now))
        assertNull(cacheableAudioResource(resource.copy(profileId = "cached-audio"), now))
    }
}
