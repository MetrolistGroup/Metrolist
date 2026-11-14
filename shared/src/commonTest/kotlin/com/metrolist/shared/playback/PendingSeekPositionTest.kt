package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingSeekPositionTest {
    @Test
    fun forwardSeekHoldsThroughOldAndIntermediateSamples() {
        val seek = PendingSeekPosition(targetMs = 90_000L, previousPositionMs = 30_000L)

        assertFalse(seek.isAcknowledged(30_000L, 30_000L))
        assertFalse(seek.isAcknowledged(30_100L, 30_100L))
        assertFalse(seek.isAcknowledged(60_000L, 60_000L))
        assertTrue(seek.isAcknowledged(90_000L, 90_000L))
        assertTrue(seek.isAcknowledged(90_100L, 90_120L))
    }

    @Test
    fun forwardSeekAcknowledgesOvershootPastTheTarget() {
        val seek = PendingSeekPosition(targetMs = 90_000L, previousPositionMs = 30_000L)

        assertTrue(seek.isAcknowledged(90_800L, 91_000L))
        assertTrue(seek.isAcknowledged(95_000L, 95_100L))
        assertFalse(seek.isAcknowledged(90_800L, 30_000L))
        assertFalse(seek.isAcknowledged(30_000L, 91_000L))
    }

    @Test
    fun forwardSeekAcknowledgesAKeyframeBeforeTheTarget() {
        val seek = PendingSeekPosition(targetMs = 90_000L, previousPositionMs = 30_000L)

        assertTrue(seek.isAcknowledged(89_400L, 89_300L))
        assertFalse(seek.isAcknowledged(89_400L, 60_000L))
    }

    @Test
    fun backwardSeekWaitsForTheRenderedPositionToo() {
        val seek = PendingSeekPosition(targetMs = 30_000L, previousPositionMs = 90_000L)

        assertFalse(seek.isAcknowledged(90_000L, 90_000L))
        assertFalse(seek.isAcknowledged(30_000L, 90_000L))
        assertFalse(seek.isAcknowledged(90_000L, 30_000L))
        assertFalse(seek.isAcknowledged(60_000L, 30_000L))
        assertFalse(seek.isAcknowledged(30_000L, 60_000L))
        assertTrue(seek.isAcknowledged(30_000L, 30_000L))
        assertTrue(seek.isAcknowledged(28_800L, 28_600L))
        assertTrue(seek.isAcknowledged(30_600L, 30_700L))
    }

    @Test
    fun shortSeeksAcknowledgeSnappingAndOvershootWithoutAcceptingTheOldPosition() {
        for (target in listOf(29_400L, 29_900L, 30_100L, 30_600L)) {
            val seek = PendingSeekPosition(targetMs = target, previousPositionMs = 30_000L)
            val overshoot = if (target > 30_000L) target + 700L else target - 700L
            val snapped = target - (target - 30_000L) / 3L

            assertFalse(seek.isAcknowledged(30_000L, 30_000L))
            assertFalse(seek.isAcknowledged(30_000L, target))
            assertFalse(seek.isAcknowledged(target, 30_000L))
            assertTrue(seek.isAcknowledged(target, target))
            assertTrue(seek.isAcknowledged(overshoot, overshoot))
            assertTrue(seek.isAcknowledged(snapped, snapped))
        }
    }

    @Test
    fun samplesCloserToThePreviousPositionDoNotAcknowledgeTheSeek() {
        val seek = PendingSeekPosition(targetMs = 120_000L, previousPositionMs = 30_000L)

        assertFalse(seek.isAcknowledged(60_000L, 60_000L))
        assertFalse(seek.isAcknowledged(75_000L, 75_000L))
        assertTrue(seek.isAcknowledged(120_000L, 120_000L))
    }

    @Test
    fun unchangedAndBoundaryTargetsCanCompleteWhilePaused() {
        assertTrue(PendingSeekPosition(30_000L, 30_000L).isAcknowledged(30_000L, 30_000L))
        assertTrue(PendingSeekPosition(30_000L, 30_000L).isAcknowledged(30_100L, 30_120L))
        assertTrue(PendingSeekPosition(0L, 30_000L).isAcknowledged(0L, 0L))
        assertTrue(PendingSeekPosition(180_000L, 30_000L).isAcknowledged(180_000L, 180_000L))
    }
}
