package com.metrolist.music.ultra

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ultra.hooks.HookRegistry
import ultra.hooks.ResolvedStream
import ultra.hooks.StreamResolution
import ultra.hooks.StreamResolverContributor
import ultra.hooks.UltraHooks

class UltraPlaybackTest {
    @After
    fun tearDown() = UltraHooks.install(HookRegistry())

    private fun install(vararg resolvers: StreamResolverContributor) =
        UltraHooks.install(HookRegistry(streamResolvers = resolvers.toList()))

    private val stream =
        ResolvedStream(
            url = "https://homelab.invalid/audio/1",
            mimeType = "audio/mp4",
            sourceName = "homelab",
            contentLengthBytes = 4_200_000L,
            bitrate = 160_000,
            sampleRate = 44_100,
            loudnessDb = -6.5,
            headers = mapOf("Authorization" to "Bearer token"),
            ttlSeconds = 900,
        )

    @Test
    fun `with no resolver the seam falls through to upstream`() =
        runBlocking {
            install()

            assertNull(ultraPrePlayback("video", null))
        }

    @Test
    fun `a resolver that passes falls through to upstream`() =
        runBlocking {
            install(StreamResolverContributor { StreamResolution.Pass })

            assertNull(ultraPrePlayback("video", "playlist"))
        }

    @Test
    fun `the resolver receives the video and playlist ids`() =
        runBlocking {
            var seen: Pair<String, String?>? = null
            install(
                StreamResolverContributor {
                    seen = it.mediaId to it.playlistId
                    StreamResolution.Pass
                },
            )

            ultraPrePlayback("video", "playlist")

            assertEquals("video" to "playlist", seen)
        }

    @Test
    fun `a resolved stream is translated into playback data`() =
        runBlocking {
            install(StreamResolverContributor { StreamResolution.Resolved(stream) })

            val data = ultraPrePlayback("video", null)?.getOrNull()

            assertNotNull(data)
            data!!
            assertEquals(stream.url, data.streamUrl)
            assertEquals(stream.url, data.format.url)
            assertEquals("homelab", data.streamClient)
            assertEquals(900, data.streamExpiresInSeconds)
            assertEquals(mapOf("Authorization" to "Bearer token"), data.streamHeaders)
            assertEquals("audio/mp4", data.format.mimeType)
            assertEquals(4_200_000L, data.format.contentLength)
            assertEquals(160_000, data.format.bitrate)
            assertEquals(160_000, data.format.averageBitrate)
            assertEquals(44_100, data.format.audioSampleRate)
            assertEquals(-6.5, data.format.loudnessDb!!, 0.0)
            assertEquals(-6.5, data.audioConfig!!.loudnessDb!!, 0.0)
            assertTrue("a format without a video size is an audio format", data.format.isAudio)
            assertFalse(data.useRangeChunks)
            assertFalse(data.requireBoundedRange)
        }

    @Test
    fun `optional fields that are absent stay absent`() {
        val minimal = ResolvedStream(url = "https://x.invalid/a", mimeType = "audio/webm", sourceName = "bare")

        val data = minimal.toPlaybackData()

        assertNull(data.audioConfig)
        assertNull(data.format.contentLength)
        assertNull(data.format.audioSampleRate)
        assertNull(data.format.averageBitrate)
        assertEquals(0, data.format.bitrate)
        assertEquals(ResolvedStream.DEFAULT_TTL_SECONDS, data.streamExpiresInSeconds)
        assertTrue(data.streamHeaders.isEmpty())
    }

    @Test
    fun `a ttl below one second is clamped so the stream is not treated as already expired`() {
        assertEquals(1, stream.copy(ttlSeconds = 0).toPlaybackData().streamExpiresInSeconds)
        assertEquals(1, stream.copy(ttlSeconds = -30).toPlaybackData().streamExpiresInSeconds)
    }

    @Test
    fun `a failed resolution is returned as a failure result`() =
        runBlocking {
            val cause = IllegalStateException("blocked by policy")
            install(StreamResolverContributor { StreamResolution.Failed(cause) })

            val result = ultraPrePlayback("video", null)

            assertNotNull(result)
            assertSame(cause, result!!.exceptionOrNull())
        }
}
