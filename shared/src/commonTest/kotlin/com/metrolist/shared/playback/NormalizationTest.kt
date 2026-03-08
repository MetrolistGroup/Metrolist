package com.metrolist.shared.playback

import com.metrolist.shared.settings.LoudnessLevel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizationTest {
    @Test
    fun perceptualLoudnessOverridesRelativeLoudnessAndAllowsLegacyBoost() {
        assertEquals(1.4125376f, playbackNormalizationGain(12.0, true, perceptualLoudnessDb = -17.0), 0.000001f)
        assertEquals(1f, playbackNormalizationGain(-20.0, true, perceptualLoudnessDb = -14.0))
        assertEquals(0.5011872f, playbackNormalizationGain(null, true, perceptualLoudnessDb = -8.0), 0.000001f)
        assertEquals(1f, playbackNormalizationGain(null, false, perceptualLoudnessDb = -17.0))
        assertEquals(1f, playbackNormalizationGain(null, true, perceptualLoudnessDb = Double.NaN))
        assertEquals(0.4466836f, playbackNormalizationGain(0.0, true, perceptualLoudnessDb = Double.POSITIVE_INFINITY), 0.000001f)
        assertEquals(1.4125376f, playbackNormalizationGain(null, true, LoudnessLevel.QUIET, -50.0), 0.000001f)
        assertEquals(0.17782794f, playbackNormalizationGain(null, true, perceptualLoudnessDb = 20.0), 0.000001f)
        assertEquals(1f, playbackNormalizationGain(null, true, perceptualLoudnessDb = -14.009))
    }

    @Test
    fun boostIsLinearBelowTheKneeAndBypassedAtUnity() {
        val gain = playbackNormalizationGain(null, true, perceptualLoudnessDb = -17.0)
        assertEquals(1412.toShort(), normalizationBoostPcm16(1000, gain))
        assertEquals((-1412).toShort(), normalizationBoostPcm16(-1000, gain))
        for (sample in listOf(Short.MIN_VALUE, -1000, 0, 1000, Short.MAX_VALUE)) {
            assertEquals(sample, normalizationBoostPcm16(sample, 1f))
        }
    }

    @Test
    fun limiterIsMonotonicSymmetricAndDoesNotFlattenOrWrapFullScalePeaks() {
        val gain = playbackNormalizationGain(null, true, perceptualLoudnessDb = -17.0)
        var previous = Short.MIN_VALUE
        for (sample in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) {
            val output = normalizationBoostPcm16(sample.toShort(), gain)
            assertTrue(output >= previous)
            if (sample > Short.MIN_VALUE) {
                assertEquals(-output.toInt(), normalizationBoostPcm16((-sample).toShort(), gain).toInt())
            }
            previous = output
        }
        val peak = normalizationBoostPcm16(Short.MAX_VALUE, gain)
        assertTrue(peak > normalizationBoostPcm16(30_000, gain))
        assertTrue(abs(normalizationBoostPcm16(Short.MIN_VALUE, gain).toInt()) < 32768)
    }
}
