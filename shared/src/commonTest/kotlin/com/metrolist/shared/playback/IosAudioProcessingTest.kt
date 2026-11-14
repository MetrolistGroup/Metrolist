package com.metrolist.shared.playback

import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAudioProcessingTest {
    @Test
    fun audioTapAvailabilityTracksTheSelectedTransport() {
        val direct = PlaybackResource("song", "https://example.test/audio", emptyMap(), null, null, requireBoundedRange = false)
        assertTrue(canUseIosAudioProcessingTap(direct, direct.url))
        assertFalse(canUseIosAudioProcessingTap(direct.copy(mimeType = "application/x-mpegURL"), direct.url))
        assertFalse(canUseIosAudioProcessingTap(direct.copy(url = "https://example.test/stream.M3U8?token=test"), direct.url))
        assertTrue(canUseIosAudioProcessingTap(direct.copy(requireBoundedRange = true), direct.url))
        val opus = direct.copy(requireBoundedRange = true, mimeType = "audio/webm", codecs = "opus")
        assertTrue(canUseIosAudioProcessingTap(opus, opus.url))
        assertFalse(canUseIosAudioProcessingTap(opus.copy(mimeType = "application/x-mpegURL"), opus.url))
        assertTrue(canUseIosAudioProcessingTap(null, "file:///music/song.m4a"))
        assertFalse(canUseIosAudioProcessingTap(null, "https://example.test/stream.m3u8?token=test"))
    }
}
