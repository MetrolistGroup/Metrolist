package com.metrolist.shared.youtube.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CachedPlaybackMetadataTest {
    @Test
    fun cachedResourceRetainsOnlineStreamMetadata() {
        val online =
            PlaybackResource(
                videoId = "song",
                url = "https://example.test/audio",
                headers = emptyMap(),
                loudnessDb = -7.5,
                perceptualLoudnessDb = -17.0,
                expiresAt = null,
                contentLengthBytes = 12_345L,
                itag = 251,
                mimeType = "audio/webm",
                codecs = "opus",
                bitrate = 128_000,
                sampleRate = 48_000,
            )

        val format = requireNotNull(online.toCachedFormatEntity("song", online.mimeType, online.contentLengthBytes!!))
        val offline =
            PlaybackResource(
                videoId = "song",
                url = "file:///audio.webm",
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
            ).withCachedFormat(format)

        assertEquals(online.itag, offline.itag)
        assertEquals(online.mimeType, offline.mimeType)
        assertEquals(online.codecs, offline.codecs)
        assertEquals(online.bitrate, offline.bitrate)
        assertEquals(online.sampleRate, offline.sampleRate)
        assertEquals(online.loudnessDb, offline.loudnessDb)
        assertEquals(online.perceptualLoudnessDb, format.perceptualLoudnessDb)
        assertEquals(online.perceptualLoudnessDb, offline.perceptualLoudnessDb)
        assertEquals(online.contentLengthBytes, offline.contentLengthBytes)
    }

    @Test
    fun incompleteStreamMetadataDoesNotInventFormatValues() {
        val resource =
            PlaybackResource(
                videoId = "song",
                url = "https://example.test/audio",
                headers = emptyMap(),
                loudnessDb = -7.5,
                perceptualLoudnessDb = -17.0,
                expiresAt = null,
                itag = 251,
                mimeType = "audio/webm",
            )

        assertNull(resource.toCachedFormatEntity("song", resource.mimeType, 12_345L))
    }
}
