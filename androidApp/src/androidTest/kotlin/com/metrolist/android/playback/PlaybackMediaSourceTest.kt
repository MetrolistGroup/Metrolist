package com.metrolist.android.playback

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.platform.app.InstrumentationRegistry
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.playback.SeekableMediaSource
import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PlaybackMediaSourceTest {
    @Test
    fun terminalSabrLoadFailureIsSurfacedWithoutReopening() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            for (kind in SabrFailureKind.entries) {
                val attempts = AtomicInteger()
                val factory =
                    DataSource.Factory {
                        object : DataSource by ByteArrayDataSource(ByteArray(1)) {
                            override fun open(dataSpec: DataSpec): Long {
                                attempts.incrementAndGet()
                                throw IOException("source failed", SabrProtocolException("terminal", kind))
                            }
                        }
                    }
                withContext(Dispatchers.Main) {
                    val player = ExoPlayer.Builder(context).build()
                    try {
                        val result =
                            withTimeout(5_000L) {
                                awaitPlayerReady(player) {
                                    player.setMediaSource(buildStreamingMediaSource(factory, "metrolist://sabr/test", "audio/webm"))
                                    player.prepare()
                                }
                            }
                        assertTrue(result is MediaPreparationResult.Failed)
                        assertEquals(1, attempts.get())
                    } finally {
                        player.release()
                    }
                }
            }
        }

    @Test
    fun knownLengthAudioAndVideoUseBoundedRangesEvenForUnrestrictedClients() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = HttpClient()
        val sources = mutableListOf<SeekableMediaSource>()
        val resource =
            PlaybackResource(
                videoId = "video",
                url = "https://rr1.googlevideo.com/videoplayback?audio=1",
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                requireBoundedRange = false,
                contentLengthBytes = 2_097_152L,
                videoUrl = "https://rr1.googlevideo.com/videoplayback?video=1",
                videoContentLengthBytes = 8_388_608L,
            )
        try {
            assertTrue(
                buildMediaSource(
                    context,
                    resource,
                    client,
                    onAudioSourceOpened = { sources += it },
                    onVideoSourceOpened = { sources += it },
                ) is MergingMediaSource,
            )
            assertEquals(2, sources.size)
            assertTrue(sources.all { it is HttpRangeInputStream })
        } finally {
            sources.forEach(SeekableMediaSource::close)
            client.close()
        }
    }

    @Test
    fun selectsNativeTransportWithoutMergingTheSameManifestTwice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = HttpClient()
        val audio =
            PlaybackResource(
                videoId = "video",
                url = "https://r.googlevideo.com/videoplayback",
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                mimeType = "audio/webm",
            )
        client.use { client ->
            assertTrue(buildMediaSource(context, audio, client) is ProgressiveMediaSource)
            assertTrue(
                buildMediaSource(context, audio.copy(videoUrl = "https://r.googlevideo.com/videoplayback?video=1"), client)
                    is MergingMediaSource,
            )
            val manifest = "https://www.youtube.com/api/manifest/hls_variant/id/video"
            val hls = audio.copy(url = manifest, videoUrl = manifest, mimeType = "application/x-mpegURL")
            assertTrue(buildMediaSource(context, hls, client) is HlsMediaSource)
            assertTrue(buildMediaSource(context, hls.copy(videoUrl = null), client) is HlsMediaSource)
        }
    }
}
