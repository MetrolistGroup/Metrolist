package com.metrolist.shared.equalizer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqualizerPcmTest {
    @Test
    fun unityGainPreservesUnrestrictedSamplesAndUnusedBufferTail() {
        for (channels in listOf(1, 2, 6)) {
            val input = ShortArray(64_225) { (it - 32_112).toShort() }
            val samples = input.copyOf()
            val eq = ParametricEqualizer(48_000, channels)
            eq.processPcm16(samples, count = samples.size - 3)
            assertContentEquals(input, samples)
            assertEquals(0.0, eq.gainReductionDb, 0.0)

            val floats = FloatArray(input.size) { input[it] / 32768f }
            floats[0] = -0.0f
            val expected = floats.copyOf()
            eq.processPcmFloat(floats, count = floats.size - 3)
            assertContentEquals(expected, floats)
        }
    }

    @Test
    fun zeroGainShapingBandsLeavePcmUntouched() {
        val bands =
            EqualizerPreset.FLAT.gains.indices
                .map { ParametricEqBand(31.25 * (1 shl it), 0.0) } +
                ParametricEqBand(80.0, 0.0, filterType = FilterType.LSC) +
                ParametricEqBand(8_000.0, 0.0, filterType = FilterType.HSC)
        val eq = ParametricEqualizer(48_000, 2)
        eq.update(EqualizerProfile("flat", "flat", bands))
        val input = ShortArray(4_096) { ((it * 7_919) % 16_000 - 8_000).toShort() }
        val samples = input.copyOf()
        eq.processPcm16(samples)
        assertContentEquals(input, samples)
    }

    @Test
    fun pcmMatchesOriginalProcessingAcrossPeaksReleaseAndControlChanges() {
        for (channels in listOf(1, 2, 6)) {
            val pcm16 = ParametricEqualizer(1_000, channels)
            val floats = ParametricEqualizer(1_000, channels)
            val reference16 = ReferencePcmEqualizer(1_000, channels)
            val referenceFloat = ReferencePcmEqualizer(1_000, channels)
            val random = Random(42)
            val profile = EqualizerProfile("test", "test", listOf(ParametricEqBand(100.0, 6.0)), preamp = 3.0)
            val disabled = profile.copy(bands = profile.bands.map { it.copy(enabled = false) }, preamp = 0.0)
            val configurations =
                listOf(
                    null to 0.0,
                    profile to 6.0,
                    null to 0.0,
                    disabled to 0.0,
                    null to -6.0,
                    profile.copy(bands = emptyList()) to 0.0,
                    null to 0.0,
                )
            for ((selected, gainDb) in configurations) {
                pcm16.update(selected)
                floats.update(selected)
                reference16.equalizer.update(selected)
                referenceFloat.equalizer.update(selected)
                for (frames in listOf(0, 1, 49, 51, 151, 2_000)) {
                    val count = frames * channels + if (frames == 151) channels - 1 else 0
                    val input =
                        ShortArray(count + 3) { i ->
                            when {
                                frames == 49 && i == channels * 24 -> Short.MIN_VALUE
                                frames == 49 && i == channels * 25 -> Short.MAX_VALUE
                                else -> random.nextInt(-8_000, 8_001).toShort()
                            }
                        }
                    val expected16 = reference16.process(DoubleArray(count) { input[it] / 32768.0 }, gainDb)
                    val expected = input.copyOf()
                    for (i in 0 until count) expected[i] = (expected16[i] * 32768.0).coerceIn(-32768.0, 32767.0).roundToInt().toShort()
                    val actual = input.copyOf()
                    pcm16.processPcm16(actual, count, gainDb)
                    assertContentEquals(expected, actual, "PCM16: $channels channels, $frames frames, $selected, $gainDb dB")
                    assertEquals(reference16.gainReductionDb, pcm16.gainReductionDb, 0.0)

                    val actualFloat = FloatArray(input.size) { input[it] / 32768f * 1.25f }
                    val expectedFloat = actualFloat.copyOf()
                    val processed = referenceFloat.process(DoubleArray(count) { actualFloat[it].toDouble() }, gainDb)
                    for (i in 0 until count) expectedFloat[i] = processed[i].coerceIn(-1.0, 1.0).toFloat()
                    floats.processPcmFloat(actualFloat, count, gainDb)
                    assertContentEquals(expectedFloat, actualFloat, "Float: $channels channels, $frames frames, $selected, $gainDb dB")
                    assertEquals(referenceFloat.gainReductionDb, floats.gainReductionDb, 0.0)
                }
            }
        }
    }

    @Test
    fun unrestrictedPeaksStillLimitAfterPreampIsEnabled() {
        val eq = ParametricEqualizer(48_000, 2)
        eq.processPcmFloat(floatArrayOf(0.9f, -0.3f))
        val samples = floatArrayOf(0.1f, -0.05f)
        eq.processPcmFloat(samples, outputGainDb = 12.0)
        assertEquals((0.1f.toDouble() * (0.98 / 0.9f)).toFloat(), samples[0])
        assertEquals(-samples[0] / 2, samples[1])
        assertTrue(eq.gainReductionDb > 10.0)
    }

    @Test
    fun floatFastPathPreservesOriginalNonFiniteSampleHandling() {
        val eq = ParametricEqualizer(1_000, 2)
        val reference = ReferencePcmEqualizer(1_000, 2)
        val inputs =
            listOf(
                floatArrayOf(Float.NaN, 1.2f),
                floatArrayOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
                FloatArray(104) { 0.1f },
                floatArrayOf(Float.NaN, -1.2f),
                floatArrayOf(1.2f, -1.2f),
            )
        for (samples in inputs) {
            val expected = reference.process(DoubleArray(samples.size) { samples[it].toDouble() }, 0.0)
            eq.processPcmFloat(samples)
            assertContentEquals(FloatArray(samples.size) { expected[it].coerceIn(-1.0, 1.0).toFloat() }, samples)
        }
    }

    private class ReferencePcmEqualizer(
        sampleRate: Int,
        private val channels: Int,
    ) {
        val equalizer = ParametricEqualizer(sampleRate, channels)
        private var envelope = 0.0
        private var hold = 0
        private val holdDuration = (sampleRate * 0.05).toInt()
        private val decay = exp(-1.0 / (sampleRate * 0.15))
        var gainReductionDb = 0.0

        fun process(
            input: DoubleArray,
            gainDb: Double,
        ): DoubleArray {
            val samples = DoubleArray(input.size) { equalizer.process(input[it], it % channels) }
            val outputGain = 10.0.pow(gainDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB) / 20.0)
            var lastGain = outputGain
            for (start in samples.indices step channels) {
                val end = minOf(start + channels, samples.size)
                var peak = 0.0
                for (i in start until end) peak = maxOf(peak, abs(samples[i]))
                if (peak >= envelope) {
                    envelope = peak
                    hold = holdDuration
                } else if (hold > 0) {
                    hold--
                } else {
                    envelope = peak + (envelope - peak) * decay
                }
                lastGain = if (envelope > 0.0) minOf(outputGain, 0.98 / envelope) else outputGain
                for (i in start until end) samples[i] *= lastGain
            }
            gainReductionDb = -20.0 * log10(lastGain / outputGain)
            return samples
        }
    }
}
