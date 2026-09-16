/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.metrolist.music.eq.audio.CustomEqualizerAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AudioProcessorBufferReuseTest {
    @Test
    fun `volume normalization reuses its direct output buffer`() {
        assertOutputBufferIsReused(VolumeNormalizationAudioProcessor())
    }

    @Test
    fun `silence detection reuses its direct output buffer`() {
        assertOutputBufferIsReused(SilenceDetectorAudioProcessor(onLongSilence = {}))
    }

    @Test
    fun `custom equalizer reuses its direct output buffer`() {
        assertOutputBufferIsReused(CustomEqualizerAudioProcessor())
    }

    @Suppress("DEPRECATION")
    private fun assertOutputBufferIsReused(processor: AudioProcessor) {
        processor.configure(INPUT_FORMAT)
        processor.flush()

        processor.queueInput(inputBuffer())
        val firstOutput = processor.output
        val firstBytes = ByteArray(firstOutput.remaining()).also(firstOutput::get)
        processor.queueInput(inputBuffer())
        val secondOutput = processor.output
        val secondBytes = ByteArray(secondOutput.remaining()).also(secondOutput::get)

        assertSame(firstOutput, secondOutput)
        assertArrayEquals(firstBytes, secondBytes)
    }

    private fun inputBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(BUFFER_SIZE)
            .order(ByteOrder.nativeOrder())
            .apply {
                repeat(BUFFER_SIZE / Short.SIZE_BYTES) { putShort(it.toShort()) }
                flip()
            }

    private companion object {
        const val BUFFER_SIZE = 4096
        val INPUT_FORMAT = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
    }
}
