package com.metrolist.android.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.metrolist.shared.playback.playbackNormalizationGain
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NormalizationAudioProcessorTest {
    @Test
    fun playersUseTheirOwnMetadataAndLivePreferencesWithoutApplyingAttenuationTwice() {
        var enabled = true
        var calculations = 0

        fun processor(lufs: Double) =
            NormalizationAudioProcessor {
                calculations++
                playbackNormalizationGain(it?.loudnessDb, enabled, perceptualLoudnessDb = it?.perceptualLoudnessDb)
            }.apply {
                resource = PlaybackResource("song", "file:///song", emptyMap(), 0.0, null, perceptualLoudnessDb = lufs)
                configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
                flush(AudioProcessor.StreamMetadata.DEFAULT)
            }

        fun process(
            processor: NormalizationAudioProcessor,
            sample: Short,
        ): Short {
            processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, sample))
            return processor.output.getShort(0)
        }
        val outgoing = processor(-8.0)
        val incoming = processor(-17.0)
        assertEquals(1000.toShort(), process(outgoing, 1000))
        assertEquals(1412.toShort(), process(incoming, 1000))
        assertTrue(process(incoming, Short.MAX_VALUE) in 30_000..Short.MAX_VALUE)
        assertTrue(process(incoming, 30_000) < process(incoming, Short.MAX_VALUE))
        incoming.resource = incoming.resource
        assertEquals(2, calculations)
        enabled = false
        incoming.refreshGain()
        assertEquals(1000.toShort(), process(incoming, 1000))
        enabled = true
        incoming.resource = incoming.resource?.copy(perceptualLoudnessDb = -16.0)
        assertEquals(1258.toShort(), process(incoming, 1000))
        incoming.resource = null
        assertEquals(1000.toShort(), process(incoming, 1000))
    }

    @Test
    fun emptyInputBeforeAudioDoesNotCopyTheSharedEmptyBufferIntoItself() {
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        val processors =
            listOf(
                NormalizationAudioProcessor { 2f },
                SilenceDetectorAudioProcessor().apply { enabled = true },
            )
        processors.forEach { processor ->
            processor.configure(format)
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, processor.output.remaining())
            processor.queueInput(ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()))
            assertEquals(4, processor.output.remaining())
        }
    }
}
