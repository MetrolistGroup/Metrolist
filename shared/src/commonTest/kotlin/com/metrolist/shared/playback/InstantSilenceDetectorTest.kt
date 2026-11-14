package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstantSilenceDetectorTest {
    @Test fun requiresTwoContinuousSecondsAndResetsAtThreshold() {
        val detector = InstantSilenceDetector(1_000)
        repeat(1_999) { detector.frame(255) }
        assertFalse(detector.isSilent)
        detector.frame(0)
        assertTrue(detector.isSilent)
        detector.frame(256)
        assertFalse(detector.isSilent)
        repeat(2_000) { detector.frame(0) }
        detector.reset()
        assertFalse(detector.isSilent)
    }

    @Test fun instantSeekUsesLegacyStepAndPreservesTrackEnd() {
        assertEquals(20_000L, instantSilenceSeekTarget(5_000L, 60_000L))
        assertEquals(59_500L, instantSilenceSeekTarget(55_000L, 60_000L))
        assertNull(instantSilenceSeekTarget(59_500L, 60_000L))
        assertNull(instantSilenceSeekTarget(0L, 15_000L))
        assertNull(instantSilenceSeekTarget(0L, -1L))
    }
}
