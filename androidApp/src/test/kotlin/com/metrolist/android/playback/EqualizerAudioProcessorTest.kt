package com.metrolist.android.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.ParametricEqualizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EqualizerAudioProcessorTest {
    @Test
    fun activeProfileShapesPcmAndDisabledProfilePassesThrough() {
        var profile: EqualizerProfile? = EqualizerProfile("half", "half", emptyList(), preamp = -6.0206)
        val processor = EqualizerAudioProcessor(outputBoostDb = { 0f }, activeProfile = { profile })
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        fun process(sample: Short): Short {
            processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, sample))
            return processor.output.getShort(0)
        }

        assertEquals(500.toShort(), process(1000))
        profile = null
        assertEquals(1000.toShort(), process(1000))
    }

    @Test
    fun formatChangesUpdateDiagnosticsAndKeepPcmAudible() {
        val processor = EqualizerAudioProcessor(outputBoostDb = { 0f }, activeProfile = { null })
        for ((rate, channels) in listOf(48_000 to 2, 44_100 to 1, 48_000 to 2)) {
            processor.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val input =
                ByteBuffer.allocateDirect(channels * 2).order(ByteOrder.nativeOrder()).apply {
                    repeat(channels) { putShort(1_000) }
                    flip()
                }
            processor.queueInput(input)
            val output = processor.output
            repeat(channels) { assertEquals(1_000.toShort(), output.short) }
            assertEquals(rate.toString(), processor.diagnosticDetails()["eqSampleRate"])
            assertEquals(channels.toString(), processor.diagnosticDetails()["eqChannels"])
        }
        processor.reset()
        assertEquals("0.0", processor.diagnosticDetails()["eqGainReductionDb"])
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET.sampleRate
                .toString(),
            processor.diagnosticDetails()["eqSampleRate"],
        )
    }

    @Test
    fun floatPcmBypassesProcessor() {
        val processor = EqualizerAudioProcessor(outputBoostDb = { 0f }, activeProfile = { null })
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertFalse(processor.isActive)
    }

    @Test
    fun outputBoostIsAppliedWithEqualizerDisabled() {
        val processor = EqualizerAudioProcessor(activeProfile = { null }, outputBoostDb = { 6f })
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, 5_000))

        assertEquals(9_976.toShort(), processor.output.getShort(0))
    }

    @Test
    fun outputBoostLimitsNearFullScalePeaks() {
        AudioSpectrumMeter.addConsumer()
        try {
            val processor = EqualizerAudioProcessor(activeProfile = { null }, outputBoostDb = { 6f })
            processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
            processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, 30_000))

            assertEquals(32_113.toShort(), processor.output.getShort(0))
            assertTrue(AudioSpectrumMeter.gainReductionDb > 5f)
        } finally {
            AudioSpectrumMeter.removeConsumer()
        }
    }

    @Test
    fun boostChangesTakeEffectWithoutReconfiguringAudioPipeline() {
        var boost = 6f
        val processor = EqualizerAudioProcessor(activeProfile = { null }, outputBoostDb = { boost })
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val reference = ParametricEqualizer(48_000, 1)

        fun process(sample: Short) {
            val expected = shortArrayOf(sample)
            reference.processPcm16(expected, outputGainDb = boost.toDouble())
            processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(0, sample))
            assertEquals(expected.single(), processor.output.getShort(0))
        }
        process(30000)
        boost = 0f
        repeat(8) { process(1000) }
        boost = -6f
        process(5000)
        boost = 12f
        process(5000)
        reference.reset()
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        process(1000)
    }
}
