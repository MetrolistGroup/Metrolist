package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TempoPitchInputTest {
    private val speedRange = 0.5f..2f
    private val pitchRange = -12f..12f

    @Test
    fun parsesExactValuesWithoutSnappingToSliderSteps() {
        assertEquals(1.53f, parseTempoPitchInput("1.53", speedRange, '.'))
        assertEquals(1.5f, parseTempoPitchInput(" +1.5 ", pitchRange, '.'))
        assertEquals(-0.2f, parseTempoPitchInput("-0.2", pitchRange, '.'))
        assertEquals(1.53f, parseTempoPitchInput("1,53", speedRange, ','))
        assertEquals(-1.5f, parseTempoPitchInput("-1,5", pitchRange, ','))
        assertEquals(1.53f, parseTempoPitchInput("١٫٥٣", speedRange, '٫'))
        assertEquals(0.5f, parseTempoPitchInput(".5", speedRange, '.'))
        assertEquals(2f, parseTempoPitchInput("2", speedRange, '.'))
        assertEquals(-12f, parseTempoPitchInput("-12", pitchRange, '.'))
        assertEquals(12f, parseTempoPitchInput("+12", pitchRange, '.'))
    }

    @Test
    fun rejectsMalformedAndOutOfRangeValuesInsteadOfClampingInput() {
        for (input in listOf("", " ", "-", "+", ".", "1..5", "1,5", "1.5x", "1 500", "NaN", "Infinity", "1e0", "0x1p0")) {
            assertNull(parseTempoPitchInput(input, speedRange, '.'), input)
        }
        assertNull(parseTempoPitchInput("1.5", speedRange, ','))
        for (input in listOf("0", "0.49", "2.01", "2.00000001", "-1", "9".repeat(400))) {
            assertNull(parseTempoPitchInput(input, speedRange, '.'), input)
        }
        assertNull(parseTempoPitchInput("-12.01", pitchRange, '.'))
        assertNull(parseTempoPitchInput("12.01", pitchRange, '.'))
    }

    @Test
    fun stepsFromTypedValuesAndClampsAtBothLimits() {
        assertEquals(1.58f, stepTempoPitchValue(1.53f, 0.05f, speedRange))
        assertEquals(1.48f, stepTempoPitchValue(1.53f, -0.05f, speedRange))
        assertEquals(2f, stepTempoPitchValue(1.98f, 0.05f, speedRange))
        assertEquals(0.5f, stepTempoPitchValue(0.52f, -0.05f, speedRange))
        assertEquals(12f, stepTempoPitchValue(11.98f, 0.05f, pitchRange))
        assertEquals(-12f, stepTempoPitchValue(-11.98f, -0.05f, pitchRange))
        assertEquals(0f, stepTempoPitchValue(-0.05f, 0.05f, pitchRange))
        assertEquals(0f, stepTempoPitchValue(0.05f, -0.05f, pitchRange))
        var pitch = -12f
        repeat(480) {
            pitch = stepTempoPitchValue(playbackPitchToSemitones(semitonesToPlaybackPitch(pitch)), 0.05f, pitchRange)
        }
        assertEquals(12f, pitch)
    }
}
