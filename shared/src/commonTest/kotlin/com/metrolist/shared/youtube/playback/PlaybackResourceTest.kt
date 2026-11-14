package com.metrolist.shared.youtube.playback

import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackResourceTest {
    @Test
    fun liveClassificationFollowsTheResourceAndItsCopies() {
        val recorded = PlaybackResource("video", "https://media.example.test/audio", emptyMap(), null, null)
        assertFalse(recorded.isLive)
        val liveProfile = recorded.copy(profileId = "ANDROID_LIVE")
        assertTrue(liveProfile.isLive)
        assertFalse(liveProfile.copy(profileId = "WEB_REMIX").isLive)
        for (marker in listOf("yt_live_broadcast", "/live/1", "live=1", "/hls_playlist/", "/hls_variant/")) {
            val liveUrl = "https://media.example.test/$marker".uppercase()
            val audio = recorded.copy(url = liveUrl)
            val video = recorded.copy(videoUrl = liveUrl)
            assertTrue(audio.isLive)
            assertTrue(video.isLive)
            assertFalse(audio.copy(url = recorded.url).isLive)
            assertFalse(video.copy(videoUrl = null).isLive)
        }
    }

    @Test
    fun audioCacheReuseRequiresTheSameMediaRevisionAndAudioTrack() {
        val bootstrap =
            SabrBootstrap(
                videoId = "video",
                serverAbrStreamingUrl = "https://example.com/sabr",
                videoPlaybackUstreamerConfig = byteArrayOf(1),
                clientName = 1,
                clientVersion = "test",
                audioFormat = SabrFormatId(251, 123L),
                discardVideoFormat = SabrFormatId(136),
                discardVideoHeight = 720,
                durationMs = 30_000L,
                contentLengthBytes = 1_000L,
                mimeType = "audio/webm",
            )
        val resource = PlaybackResource("video", "sabr://video", emptyMap(), null, null, sabrBootstrap = bootstrap)
        assertEquals(bootstrap.serverAbrStreamingUrl, resource.networkUrl)
        val otherClient = resource.copy(sabrBootstrap = bootstrap.copy(clientName = 2, serverAbrStreamingUrl = "https://example.com/other"))
        assertTrue(resource.hasSameSabrAudio(otherClient))
        for (different in listOf(
            bootstrap.copy(videoId = "counterpart"),
            bootstrap.copy(audioFormat = bootstrap.audioFormat.copy(itag = 140)),
            bootstrap.copy(audioFormat = bootstrap.audioFormat.copy(lastModified = 456L)),
            bootstrap.copy(audioFormat = bootstrap.audioFormat.copy(xtags = "different-track")),
            bootstrap.copy(audioTrackId = "dubbed"),
            bootstrap.copy(isDrc = true),
            bootstrap.copy(contentLengthBytes = 999L),
            bootstrap.copy(durationMs = 31_000L),
            bootstrap.copy(mimeType = "audio/mp4"),
        )) {
            assertFalse(resource.hasSameSabrAudio(resource.copy(sabrBootstrap = different)))
        }
        val unversioned = resource.copy(sabrBootstrap = bootstrap.copy(audioFormat = SabrFormatId(251)))
        assertFalse(unversioned.hasSameSabrAudio(unversioned))
        assertFalse(resource.hasSameSabrAudio(resource.copy(sabrBootstrap = null)))
    }

    @Test
    fun routeChecksUseOnlyHttpTransportUrls() {
        val direct = PlaybackResource("video", "https://media.example.test/audio", emptyMap(), null, null)
        assertEquals(direct.url, direct.networkUrl)
        assertEquals("http://localhost/audio", direct.copy(url = "http://localhost/audio").networkUrl)
        for (url in listOf("sabr://video", "file:///audio", "/cache/audio", "content://downloads/audio")) {
            assertNull(direct.copy(url = url).networkUrl)
        }
    }
}
