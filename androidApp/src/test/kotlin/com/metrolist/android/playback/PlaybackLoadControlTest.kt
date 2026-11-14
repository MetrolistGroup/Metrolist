package com.metrolist.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.metrolist.shared.youtube.playback.PlaybackResource
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaybackLoadControlTest {
    private val timeline =
        SinglePeriodTimeline(600_000_000, true, false, false, null, MediaItem.fromUri("https://media.example.test/audio"))

    @Test
    fun audioFillsToNinetySecondsAndWaitsUntilTwentySecondsToRefill() {
        val control = createPlaybackLoadControl()
        val audio = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        control.onPrepared(PlayerId.UNSET)
        control.onTracksSelected(parameters(0), TrackGroupArray(audio), arrayOf(FixedTrackSelection(audio, 0)))
        assertTrue(control.shouldContinueLoading(parameters(0)))
        assertTrue(control.shouldContinueLoading(parameters(60_000_000)))
        assertFalse(control.shouldContinueLoading(parameters(90_000_000)))
        assertFalse(control.shouldContinueLoading(parameters(60_000_000)))
        assertFalse(control.shouldContinueLoading(parameters(20_000_000)))
        assertTrue(control.shouldContinueLoading(parameters(19_000_000)))
        assertTrue(control.shouldContinueLoading(parameters(60_000_000)))
        assertFalse(control.shouldContinueLoading(parameters(90_000_000)))
        assertTrue(control.shouldContinueLoading(parameters(0)))
        control.onReleased(PlayerId.UNSET)
    }

    @Test
    fun byteLimitedBurstWaitsForRefillAndFasterPlaybackRefillsEarlier() {
        val control = createPlaybackLoadControl()
        val audio = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        control.onPrepared(PlayerId.UNSET)
        control.onTracksSelected(parameters(0), TrackGroupArray(audio), arrayOf(FixedTrackSelection(audio, 0)))
        assertTrue(control.shouldContinueLoading(parameters(0)))
        val allocator = control.getAllocator(PlayerId.UNSET)
        val allocations = List(32) { allocator.allocate() }
        assertFalse(control.shouldContinueLoading(parameters(50_000_000)))
        allocations.forEach(allocator::release)
        assertFalse(control.shouldContinueLoading(parameters(30_000_000)))
        assertFalse(control.shouldContinueLoading(parameters(40_000_000, speed = 2f)))
        assertTrue(control.shouldContinueLoading(parameters(39_000_000, speed = 2f)))
        control.onReleased(PlayerId.UNSET)
    }

    @Test
    fun selectingVideoOrLocalAudioKeepsTheShortBuffer() {
        for (uri in listOf("https://media.example.test/audio", "file:///audio.m4a", "metrolist://sabr/audio")) {
            val control = createPlaybackLoadControl()
            val itemTimeline = SinglePeriodTimeline(600_000_000, true, false, false, null, MediaItem.fromUri(uri))
            val audio = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
            val video = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
            val selections =
                if (uri.startsWith("https")) {
                    arrayOf(FixedTrackSelection(audio, 0), FixedTrackSelection(video, 0))
                } else {
                    arrayOf(FixedTrackSelection(audio, 0))
                }
            control.onPrepared(PlayerId.UNSET)
            control.onTracksSelected(parameters(0, itemTimeline = itemTimeline), TrackGroupArray(audio, video), selections)
            assertTrue(control.shouldContinueLoading(parameters(0, itemTimeline = itemTimeline)))
            assertFalse(control.shouldContinueLoading(parameters(20_000_000, itemTimeline = itemTimeline)))
            assertFalse(control.shouldContinueLoading(parameters(10_000_000, itemTimeline = itemTimeline)))
            assertTrue(control.shouldContinueLoading(parameters(9_000_000, itemTimeline = itemTimeline)))
            control.onReleased(PlayerId.UNSET)
        }
    }

    @Test
    fun rangeReaderGrowsIntoBurstsAndKeepsStartupAndUncachedSeeksSmall() {
        val ranges = mutableListOf<LongRange>()
        val size = 10_000_000L
        val resource = PlaybackResource("audio", "https://media.example.test/audio", emptyMap(), null, null, bitrate = 128_000)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val (start, end) = checkNotNull(chain.request().header("Range")).removePrefix("bytes=").split('-').map(String::toLong)
                    ranges += start..end
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .header("Content-Range", "bytes $start-$end/$size")
                        .body(ByteArray((end - start + 1).toInt()) { ((start + it) % 251).toByte() }.toResponseBody())
                        .build()
                }.build()
        val control = createPlaybackLoadControl()
        val audio = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        control.onPrepared(PlayerId.UNSET)
        control.onTracksSelected(parameters(0), TrackGroupArray(audio), arrayOf(FixedTrackSelection(audio, 0)))
        try {
            HttpRangeInputStream(
                httpClient = client,
                url = resource.url,
                requestHeaders = emptyMap(),
                mediaSize = size,
                chunkSize = directMediaChunkSize(resource, video = false),
            ).use { input ->
                val buffer = ByteArray(16_000)
                var parsedBytes = 0L

                fun fill(positionSeconds: Int) {
                    while (control.shouldContinueLoading(parameters(parsedBytes * 1_000_000 / 16_000 - positionSeconds * 1_000_000L))) {
                        val count = input.read(buffer)
                        assertTrue(count > 0)
                        assertEquals((parsedBytes % 251).toByte(), buffer[0])
                        parsedBytes += count
                    }
                }
                fill(0)
                assertEquals(0L..65_535L, ranges.first())
                assertTrue(ranges.size <= 6)
                assertEquals(960_000L, ranges.last().last - ranges.last().first + 1)
                val initialRequests = ranges.size
                for (second in 1..70) fill(second)
                assertEquals(initialRequests, ranges.size)
                fill(72)
                assertTrue(ranges.size > initialRequests)
                assertTrue(ranges.all { it.last - it.first + 1 <= 960_000 })

                input.seekToMediaPosition(8_000_000)
                assertEquals(8_000_000 % 251, input.read())
                assertEquals(8_000_000L..8_065_535L, ranges.last())
            }
        } finally {
            control.onReleased(PlayerId.UNSET)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test
    fun audioStopsAtByteBudgetButVideoCanContinueAndNeitherRetainsBackBuffer() {
        val control = createPlaybackLoadControl()
        val audio = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())
        val video = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        val parameters = parameters(bufferedUs = 1_000_000)
        control.onPrepared(PlayerId.UNSET)
        control.onTracksSelected(parameters, TrackGroupArray(audio), arrayOf(FixedTrackSelection(audio, 0)))
        val allocator = control.getAllocator(PlayerId.UNSET)
        val allocations = List(32) { allocator.allocate() }
        assertFalse(control.shouldContinueLoading(parameters))
        control.onTracksSelected(
            parameters,
            TrackGroupArray(audio, video),
            arrayOf(FixedTrackSelection(audio, 0), FixedTrackSelection(video, 0)),
        )
        assertTrue(control.shouldContinueLoading(parameters))
        assertEquals(0L, control.getBackBufferDurationUs(PlayerId.UNSET))
        allocations.forEach(allocator::release)
        control.onReleased(PlayerId.UNSET)
    }

    @Test
    fun nextTrackCanWarmAtHalfASecondWithoutChangingPrimaryOrRebufferThresholds() {
        val primary = createPlaybackLoadControl()
        val next = createPlaybackLoadControl(500)
        primary.onPrepared(PlayerId.UNSET)
        next.onPrepared(PlayerId.UNSET)
        assertFalse(primary.shouldStartPlayback(parameters(500_000)))
        assertTrue(next.shouldStartPlayback(parameters(500_000)))
        assertTrue(primary.shouldStartPlayback(parameters(2_000_000)))
        assertFalse(next.shouldStartPlayback(parameters(2_000_000, rebuffering = true)))
        assertTrue(next.shouldStartPlayback(parameters(5_000_000, rebuffering = true)))
        primary.onReleased(PlayerId.UNSET)
        next.onReleased(PlayerId.UNSET)
    }

    private fun parameters(
        bufferedUs: Long,
        rebuffering: Boolean = false,
        speed: Float = 1f,
        itemTimeline: SinglePeriodTimeline = timeline,
    ) = LoadControl.Parameters(
        PlayerId.UNSET,
        itemTimeline,
        MediaPeriodId(itemTimeline.getUidOfPeriod(0)),
        0,
        bufferedUs,
        speed,
        true,
        rebuffering,
        C.TIME_UNSET,
        C.TIME_UNSET,
    )
}
