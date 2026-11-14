package com.metrolist.android.playback

import android.app.Application
import android.media.AudioTimestamp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class TimestampAudioOutputTest {
    @Test
    fun presentationTimestampAvoidsLatencySubtractionAndAcceptsRouteJitter() {
        val track = FakeTimestamp()
        val clock = track.clock()
        assertNull(clock.positionUs(144_000, 1f))
        track.advance(20_000_000, 1_080) // 2.5 ms of timestamp jitter exceeds Media3's 1 ms gate.
        assertEquals(1_022_500L, clock.positionUs(144_000, 1f))
        track.nowNs += 46_000_000
        assertEquals(1_068_500L, clock.positionUs(144_000, 1f))
        assertEquals(2, track.reads)
    }

    @Test
    fun hardwareSpeedScalesElapsedTimeButNotAlreadyPresentedFrames() {
        val track = FakeTimestamp()
        val clock = track.clock()
        clock.positionUs(144_000, 2f)
        track.advance(20_000_000, 1_920)
        assertEquals(1_040_000L, clock.positionUs(144_000, 2f))
        track.nowNs += 30_000_000
        assertEquals(1_100_000L, clock.positionUs(144_000, 2f))

        clock.reset()
        assertNull(clock.positionUs(144_000, 0.5f))
        track.timestampNs = track.nowNs
        track.advance(20_000_000, 480)
        assertNull(clock.positionUs(144_000, 0.5f))
        track.advance(20_000_000, 480)
        assertEquals(1_060_000L, clock.positionUs(144_000, 0.5f))
        track.nowNs += 30_000_000
        assertEquals(1_075_000L, clock.positionUs(144_000, 0.5f))
    }

    @Test
    fun committedFutureTimestampProjectsBackToTheAudibleFrame() {
        val track = FakeTimestamp().apply { timestampNs += 100_000_000 }
        val clock = track.clock()
        clock.positionUs(144_000, 1f)
        track.advance(20_000_000, 960)
        assertEquals(920_000L, clock.positionUs(144_000, 1f))
    }

    @Test
    fun unavailableStaleAndUnwrittenTimestampsCannotSeedTheClock() {
        val track = FakeTimestamp().apply { available = false }
        val clock = track.clock()
        assertNull(clock.positionUs(144_000, 1f))
        track.available = true
        track.nowNs += 20_000_000
        track.timestampNs--
        assertNull(clock.positionUs(144_000, 1f))
        track.advance(20_000_000, 960)
        assertNull(clock.positionUs(1, 1f))
        track.advance(20_000_000, 960)
        assertNull(clock.positionUs(144_000, 1f))
        track.advance(20_000_000, 960)
        assertEquals(1_080_000L, clock.positionUs(144_000, 1f))
    }

    @Test
    fun timestampFrameWrapDoesNotLookLikeASeek() {
        val track = FakeTimestamp().apply { frames = (1L shl 32) - 480 }
        val clock = track.clock()
        clock.positionUs((1L shl 32) + 48_000, 1f)
        track.advance(20_000_000, 960)
        track.frames = track.frames and 0xffffffffL
        assertEquals(
            Util.sampleCountToDurationUs((1L shl 32) + 480, 48_000),
            clock.positionUs((1L shl 32) + 48_000, 1f),
        )
    }

    @Test
    fun brokenTimestampApiAndImpossiblePresentationTimesUseTheFallback() {
        val broken = AudioTimestampClock(48_000, { throw IllegalStateException() }, { 1_000_000_000L })
        assertNull(broken.positionUs(144_000, 1f))
        val track = FakeTimestamp().apply { timestampNs += 10_000_000_000L }
        val clock = track.clock()
        assertNull(clock.positionUs(144_000, 1f))
        track.advance(20_000_000, 960)
        assertNull(clock.positionUs(144_000, 1f))
        track.timestampNs = track.nowNs
        track.advance(20_000_000, 960)
        assertNull(clock.positionUs(144_000, 1f))
        track.advance(20_000_000, 960)
        assertEquals(1_060_000L, clock.positionUs(144_000, 1f))
    }

    @Test
    fun underrunCapsExtrapolationAtWrittenFramesAndRequiresFreshSamples() {
        val track = FakeTimestamp()
        val clock = track.clock()
        clock.positionUs(49_920, 1f)
        track.advance(20_000_000, 960)
        assertEquals(1_020_000L, clock.positionUs(49_920, 1f))
        track.nowNs += 100_000_000
        assertEquals(1_040_000L, clock.positionUs(49_920, 1f))
        assertNull(clock.positionUs(96_000, 1f))
        track.timestampNs = track.nowNs
        track.advance(20_000_000, 960)
        assertNull(clock.positionUs(96_000, 1f))
        track.advance(20_000_000, 960)
        assertEquals(1_060_000L, clock.positionUs(96_000, 1f))
    }

    @Test
    fun outputKeepsPauseRouteSeekPartialWritesAndDrainInTheOutputTimeline() {
        val track = FakeTimestamp()
        var route = 1
        var speed = PlaybackParameters.DEFAULT
        var fallbackUs = 800_000L
        var writeLimit = Int.MAX_VALUE
        val platform =
            Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
                when (method.name) {
                    "getSampleRate" -> {
                        48_000
                    }

                    "getPositionUs" -> {
                        fallbackUs
                    }

                    "getPlaybackParameters" -> {
                        speed
                    }

                    "setPlaybackParameters" -> {
                        speed = args!![0] as PlaybackParameters
                        null
                    }

                    "write" -> {
                        val buffer = args!![0] as ByteBuffer
                        buffer.position(buffer.position() + minOf(buffer.remaining(), writeLimit))
                        !buffer.hasRemaining()
                    }

                    else -> {
                        null
                    }
                }
            } as AudioOutput
        val output = TimestampAudioOutput(platform, 4, track::read, { route }, { track.nowNs })
        output.play()
        output.write(ByteBuffer.allocate(144_000 * 4), 1, 30_000_000)
        assertEquals(fallbackUs, output.positionUs)
        track.advance(20_000_000, 960)
        assertEquals(1_020_000L, output.positionUs)
        output.pause()
        track.nowNs += 1_000_000_000
        assertEquals(1_020_000L, output.positionUs)

        output.play()
        assertEquals(1_020_000L, output.positionUs)
        track.timestampNs = track.nowNs
        track.advance(20_000_000, 960)
        assertEquals(1_020_000L, output.positionUs)
        track.advance(20_000_000, 960)
        assertEquals(1_060_000L, output.positionUs)

        route++
        assertEquals(1_060_000L, output.positionUs)
        track.advance(20_000_000, 960)
        assertEquals(1_080_000L, output.positionUs)
        output.setPlaybackParameters(PlaybackParameters(2f))
        output.positionUs
        track.advance(20_000_000, 1_920)
        assertEquals(1_120_000L, output.positionUs)

        output.stop()
        track.available = false
        track.nowNs += 30_000_000
        assertEquals(1_180_000L, output.positionUs)
        track.nowNs += 2_000_000_000
        assertEquals(3_000_000L, output.positionUs)

        output.flush()
        fallbackUs = 0
        track.frames = 0
        track.available = true
        track.timestampNs = track.nowNs
        output.play()
        writeLimit = 4_800 * 4
        output.write(ByteBuffer.allocate(48_000 * 4), 1, 5_000_000)
        assertEquals(0L, output.positionUs)
        track.advance(20_000_000, 1_920)
        assertEquals(40_000L, output.positionUs)
        track.nowNs += 100_000_000
        assertEquals(100_000L, output.positionUs)
    }

    private class FakeTimestamp {
        var nowNs = 1_000_000_000L
        var timestampNs = nowNs
        var frames = 48_000L
        var available = true
        var reads = 0

        fun read(timestamp: AudioTimestamp): Boolean {
            reads++
            timestamp.framePosition = frames
            timestamp.nanoTime = timestampNs
            return available
        }

        fun advance(
            nanos: Long,
            frameCount: Long,
        ) {
            nowNs += nanos
            timestampNs += nanos
            frames += frameCount
        }

        fun clock() = AudioTimestampClock(48_000, ::read, { nowNs })
    }
}
