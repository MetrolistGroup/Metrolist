package com.metrolist.android.playback

import android.app.Application
import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class AudioOutputDiagnosticsTest {
    @Test
    fun stalledHeadReportsOnceAndResetsAfterProgressPauseAndHeadReset() {
        val probe = AudioOutputProbe()

        fun sample(
            time: Long,
            head: Long,
            playing: Boolean = true,
            written: Long = 100_000,
        ) = probe.observe(time, head, written, 0, 48_000, playing)
        assertNull(sample(0, 100))
        assertNull(sample(1_000, 100))
        assertEquals("playback_head_stalled", sample(2_000, 100))
        assertNull(sample(3_000, 100))
        assertEquals("playback_head_stalled_cleared", sample(4_000, 200))
        assertEquals("playback_head_stalled", sample(6_000, 200))
        assertNull(sample(7_000, 200, playing = false))
        assertNull(sample(8_000, 200))
        assertNull(sample(9_000, 200))
        assertEquals("playback_head_stalled", sample(10_000, 200))
        probe.reset()
        assertNull(sample(11_000, 0, written = 0))
        assertNull(sample(13_000, 0, written = 0))
        assertNull(sample(14_000, 0))
        assertNull(sample(15_000, 0))
        assertEquals("playback_head_stalled", sample(16_000, 0))
    }

    @Test
    fun advancingHeadDoesNotMislabelDownstreamSilenceOrUnsignedWrapAsAStall() {
        val probe = AudioOutputProbe()
        assertNull(probe.observe(0, 0xffffff00L, 0x100001000L, 0, 48_000, true))
        assertNull(probe.observe(5_000, 100, 0x100002000L, 0, 48_000, true))
        for (second in 6L..30L) {
            assertNull(probe.observe(second * 1_000, second * 48_000, (second + 1) * 48_000, 0, 48_000, true))
        }
    }

    @Test
    fun zeroPcmUsesCurrentSampleRateAndDoesNotReportWhileMuted() {
        val probe = AudioOutputProbe()
        assertNull(probe.observe(0, 0, 48_000, 48_000, 48_000, true))
        assertEquals("zero_pcm", probe.observe(1_000, 48_000, 96_000, 96_000, 48_000, true))
        assertNull(probe.observe(2_000, 96_000, 144_000, 144_000, 48_000, true))
        assertNull(probe.observe(3_000, 144_000, 192_000, 192_000, 48_000, false))
        probe.reset()
        assertNull(probe.observe(4_000, 0, 88_199, 88_199, 44_100, true))
        assertEquals("zero_pcm", probe.observe(5_000, 44_100, 88_200, 88_200, 44_100, true))
        assertEquals("zero_pcm_cleared", probe.observe(6_000, 88_200, 100_000, 0, 44_100, true))
    }

    @Test
    fun partialWritesCountOnlyConsumedPcmWithoutChangingItsBytesOrCursor() = verifyPartialWrites(C.ENCODING_PCM_16BIT)

    @Test
    fun compressedOutputIsNotInterpretedAsPcm() = verifyPartialWrites(C.ENCODING_AAC_LC)

    private fun verifyPartialWrites(encoding: Int) {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        var writeLimit = 4
        var failParameters = false
        val output =
            Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
                when (method.name) {
                    "write" -> {
                        val buffer = args!![0] as ByteBuffer
                        buffer.position(buffer.position() + minOf(writeLimit, buffer.remaining()))
                        !buffer.hasRemaining()
                    }

                    "getAudioSessionId" -> {
                        17
                    }

                    "getPlaybackParameters" -> {
                        check(!failParameters)
                        PlaybackParameters.DEFAULT
                    }

                    else -> {
                        null
                    }
                }
            } as AudioOutput
        val config =
            AudioOutputProvider.OutputConfig
                .Builder()
                .setEncoding(encoding)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
        val diagnostic = DiagnosticAudioOutput(output, config, { event, details -> events += event to details }, nowMs = { 10L })
        val input =
            ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).apply {
                putShort(Short.MIN_VALUE)
                putShort(Short.MAX_VALUE)
                putShort(0)
                putShort(0)
                flip()
            }
        diagnostic.play()
        diagnostic.setVolume(0.4f)
        assertEquals(false, diagnostic.write(input, 1, 0))
        assertEquals(4, input.position())
        writeLimit = 0
        assertEquals(false, diagnostic.write(input, 1, 0))
        assertEquals(4, input.position())
        writeLimit = 4
        assertEquals(true, diagnostic.write(input, 1, 0))
        assertEquals(8, input.position())
        assertEquals(Short.MIN_VALUE, input.getShort(0))
        assertEquals(Short.MAX_VALUE, input.getShort(2))
        diagnostic.pause()
        val details = events.last().second
        assertEquals("17", details["outputSessionId"])
        assertEquals("8", details["outputWrittenBytes"])
        if (encoding == C.ENCODING_PCM_16BIT) {
            assertEquals("2", details["outputWrittenFrames"])
            assertEquals("1", details["outputSilentFrames"])
            assertEquals("32768", details["outputPcmPeak"])
        } else {
            assertNull(details["outputWrittenFrames"])
            assertNull(details["outputSilentFrames"])
            assertNull(details["outputPcmPeak"])
        }
        assertEquals("0.4", details["outputVolume"])
        diagnostic.flush()
        assertEquals("0", events.last().second["outputWrittenBytes"])
        if (encoding == C.ENCODING_PCM_16BIT) {
            assertEquals("0", events.last().second["outputSilentFrames"])
        }
        failParameters = true
        diagnostic.release()
        assertEquals("IllegalStateException", events.last().second["outputDiagnosticsError"])
        assertEquals("release", events.last().first)
    }
}
