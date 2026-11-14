package com.metrolist.shared.playback.opus

import com.metrolist.shared.playback.ffmpeg.AudioDecoder
import com.metrolist.shared.playback.ffmpeg.FFmpegFrameGrabber
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpusMp4DecodeTest {
    @Test
    fun remuxPreservesFloatSamplesIncludingPeaksAboveFullScale() {
        val peaks = checkNotNull(javaClass.getResourceAsStream("/ffmpeg/opus-peaks.webm")).use { it.readBytes() }
        assertFloatPcmMatches(opusWebmFixture())
        assertTrue(assertFloatPcmMatches(peaks) > 1f, "The fixture must exercise unclipped Opus output")
        System.getenv("METROLIST_OPUS_WEBM")?.let { assertFloatPcmMatches(File(it).readBytes()) }
    }

    private fun assertFloatPcmMatches(webm: ByteArray): Float {
        val audio = WebmOpusDemuxer.demux(webm)
        FloatPcm(webm).use { expected ->
            FloatPcm(OpusMp4Remuxer.remux(webm)).use { actual ->
                var count = 0L
                var peak = 0f
                while (true) {
                    val sample = expected.next() ?: break
                    val remuxed = actual.next()
                    if (!sample.isFinite()) error("Non-finite source PCM at sample $count")
                    if (sample.toRawBits() != remuxed?.toRawBits()) {
                        assertEquals(sample.toRawBits(), remuxed?.toRawBits(), "Remux changed PCM at sample $count")
                    }
                    peak = maxOf(peak, abs(sample))
                    count++
                }
                assertEquals(audio.durationSamples * audio.channels, count)
                var padding = 0
                while (actual.next() != null) padding++
                assertTrue(padding in 0..audio.discardSamples * audio.channels)
                println(
                    "Opus PCM matched: frames=${count / audio.channels}, channels=${audio.channels}, " +
                        "peak=$peak, paddingSamples=$padding",
                )
                return peak
            }
        }
    }

    private class FloatPcm(
        bytes: ByteArray,
    ) : AutoCloseable {
        private val decoder =
            FFmpegFrameGrabber(ByteArrayInputStream(bytes), bytes.size).apply {
                sampleFormat = AV_SAMPLE_FMT_FLT
                start()
            }
        private var buffer: FloatBuffer? = null

        fun next(): Float? {
            while (buffer?.hasRemaining() != true) {
                val frame = decoder.grabSamples() ?: return null
                buffer = frame.samples?.single() as? FloatBuffer
            }
            return buffer!!.get()
        }

        override fun close() {
            decoder.close()
        }
    }

    @Test
    fun remuxedPacketsDecodeToTheSamePcmAndRemainSeekable() {
        val webm = opusWebmFixture()
        val mp4 = OpusMp4Remuxer.remux(webm)
        val expected = decode(webm)
        val actual = decode(mp4)
        val paddingBytes = WebmOpusDemuxer.demux(webm).discardSamples * 2 * 2
        assertEquals(expected.size + paddingBytes, actual.size)
        assertContentEquals(expected, actual.copyOf(expected.size))

        val sought = decode(mp4, seekMs = 1000L)
        assertTrue(sought.size in (48_000 - 960) * 4..(48_000 + 960) * 4 + paddingBytes)
        val samples = ByteBuffer.wrap(sought).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var energy = 0.0
        val count = samples.remaining()
        while (samples.hasRemaining()) {
            val value = samples.get().toDouble()
            energy += value * value
        }
        assertTrue(sqrt(energy / count) / Short.MAX_VALUE in 0.04..0.09)
    }

    private fun decode(
        bytes: ByteArray,
        seekMs: Long? = null,
    ): ByteArray {
        val decoder = AudioDecoder(ByteArrayInputStream(bytes), maximumSize = bytes.size)
        try {
            assertEquals(48_000, decoder.sampleRate)
            assertEquals(2, decoder.audioChannels)
            if (seekMs != null) decoder.seekTo(seekMs)
            val pcm = ByteArrayOutputStream()
            while (true) {
                val frame = decoder.readPCMFrame() ?: break
                if (pcm.size() == 0 && seekMs != null) assertTrue(abs(decoder.getCurrentPosition() - seekMs) <= 20)
                pcm.write(frame)
            }
            return pcm.toByteArray()
        } finally {
            decoder.close()
        }
    }
}
