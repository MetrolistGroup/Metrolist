package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatUtilsTest {
    @Test
    fun playbackPitchConvertsToSemitonesAndBack() {
        assertEquals(0f, playbackPitchToSemitones(1f))
        assertEquals(-12f, playbackPitchToSemitones(0.5f))
        assertEquals(12f, playbackPitchToSemitones(2f))
        assertEquals(12f, playbackPitchToSemitones(4f))
        assertEquals(-12f, playbackPitchToSemitones(0.25f))
        assertEquals(0.5f, semitonesToPlaybackPitch(-12f))
        assertEquals(1f, semitonesToPlaybackPitch(0f))
        assertEquals(2f, semitonesToPlaybackPitch(12f))
        assertEquals(2f, semitonesToPlaybackPitch(24f))
        assertEquals(0.5f, semitonesToPlaybackPitch(-24f))
        for (step in -240..240) {
            val semitones = step / 20f
            assertEquals(semitones, playbackPitchToSemitones(semitonesToPlaybackPitch(semitones)), 0.00001f)
        }
    }

    @Test
    fun normalizePlaybackTempoCoercesToSupportedRange() {
        assertEquals(1f, normalizePlaybackTempo(1f))
        assertEquals(0.5f, normalizePlaybackTempo(0f))
        assertEquals(2f, normalizePlaybackTempo(10f))
        assertEquals(null, normalizePlaybackTempo(Float.NaN))
        assertEquals(null, normalizePlaybackTempo(Float.POSITIVE_INFINITY))
        assertEquals(null, normalizePlaybackTempo(Float.NEGATIVE_INFINITY))
    }
}
