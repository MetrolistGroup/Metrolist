package com.metrolist.android.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MusicAudioProcessorChainTest {
    @Test
    fun quietMusicInEitherChannelIsPreservedAcrossBuffers() {
        listOf(0, 1).forEach { audibleChannel ->
            val processor = processor()
            val input =
                pcm(2_000) { frame, channel ->
                    if (channel == audibleChannel) {
                        if (frame % 2 == 0) {
                            512
                        } else {
                            -512
                        }
                    } else {
                        0
                    }
                }
            assertTrue(input.contentEquals(process(processor, input)))
            assertEquals(0L, processor.skippedFrames)
        }
    }

    @Test
    fun shortPausesBetweenNotesKeepTheirOriginalTiming() {
        val processor = processor()
        val input = pcm(1_200) { frame, _ -> if (frame in 400 until 800) 0 else 2_000 }
        assertTrue(input.contentEquals(process(processor, input)))
        assertEquals(0L, processor.skippedFrames)
    }

    @Test
    fun sustainedSilenceIsShortenedAndAccountedForWithoutChangingPlaybackSpeed() {
        val chain = musicAudioProcessorChain()
        val parameters = PlaybackParameters(1.25f, 1.1f)
        assertEquals(parameters, chain.applyPlaybackParameters(parameters))
        assertTrue(chain.applySkipSilenceEnabled(true))
        val processor = chain.audioProcessors.filterIsInstance<SilenceSkippingAudioProcessor>().single()
        processor.configure(AudioProcessor.AudioFormat(1_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = pcm(3_000) { frame, _ -> if (frame in 500 until 2_500) 0 else 2_000 }
        val output = process(processor, input)
        assertTrue(chain.skippedOutputFrameCount > 0)
        assertEquals(input.size / 4L, output.size / 4L + chain.skippedOutputFrameCount)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(0L, chain.skippedOutputFrameCount)
        assertFalse(chain.applySkipSilenceEnabled(false))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(processor.isActive)
        assertEquals(parameters, chain.applyPlaybackParameters(parameters))
    }

    private fun processor(): SilenceSkippingAudioProcessor =
        musicAudioProcessorChain().run {
            applySkipSilenceEnabled(true)
            audioProcessors.filterIsInstance<SilenceSkippingAudioProcessor>().single().apply {
                configure(AudioProcessor.AudioFormat(1_000, 2, C.ENCODING_PCM_16BIT))
                flush(AudioProcessor.StreamMetadata.DEFAULT)
            }
        }

    private fun pcm(
        frames: Int,
        sample: (Int, Int) -> Int,
    ): ByteArray =
        ByteBuffer
            .allocate(frames * 4)
            .order(ByteOrder.nativeOrder())
            .apply {
                repeat(frames) { frame -> repeat(2) { channel -> putShort(sample(frame, channel).toShort()) } }
            }.array()

    private fun process(
        processor: SilenceSkippingAudioProcessor,
        input: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()

        fun drain() {
            val buffer = processor.output
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
        }
        input.asList().chunked(148).forEach { chunk ->
            val buffer = ByteBuffer.allocateDirect(chunk.size).order(ByteOrder.nativeOrder())
            buffer.put(chunk.toByteArray()).flip()
            while (buffer.hasRemaining()) {
                processor.queueInput(buffer)
                drain()
            }
        }
        processor.queueEndOfStream()
        drain()
        return output.toByteArray()
    }
}
