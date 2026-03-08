package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTimeSegmentsTest {
    @Test
    fun seekReportsOnlyTraversedRanges() {
        val segments = WatchTimeSegments()

        segments.onPlay(0L)
        segments.onProgress(1_000L)
        segments.onSeek(fromPositionMs = 1_000L, toPositionMs = 5_000L, continuePlaying = true)
        segments.onProgress(6_000L)

        val drained = segments.drain(currentPositionMs = 6_000L, continuePlaying = true)

        assertEquals("0.0,5.0", drained?.startSeconds)
        assertEquals("1.0,6.0", drained?.endSeconds)
        assertTrue(segments.isOpen)
    }

    @Test
    fun pausedTimeIsNotAccumulated() {
        val segments = WatchTimeSegments()

        segments.onPlay(2_000L)
        segments.onPause(3_000L)
        val first = segments.drain(currentPositionMs = 10_000L, continuePlaying = false)
        val second = segments.drain(currentPositionMs = 20_000L, continuePlaying = false)

        assertEquals("2.0", first?.startSeconds)
        assertEquals("3.0", first?.endSeconds)
        assertNull(second)
        assertFalse(segments.isOpen)
    }

    @Test
    fun subHalfSecondJitterIsDropped() {
        val segments = WatchTimeSegments()

        segments.onPlay(1_000L)
        segments.onPause(1_499L)

        assertNull(segments.drain(currentPositionMs = 1_499L, continuePlaying = false))
    }

    @Test
    fun backwardTimestampJitterDoesNotOverlapRanges() {
        val segments = WatchTimeSegments()

        segments.onPlay(48_500L)
        segments.onProgress(60_000L)
        val first = segments.drain(currentPositionMs = 60_000L, continuePlaying = true)
        segments.onProgress(59_800L)
        segments.onProgress(61_000L)
        val second = segments.drain(currentPositionMs = 61_000L, continuePlaying = false)

        assertEquals("48.5", first?.startSeconds)
        assertEquals("60.0", first?.endSeconds)
        assertEquals("60.0", second?.startSeconds)
        assertEquals("61.0", second?.endSeconds)
    }

    @Test
    fun backwardSeekDoesNotReportAlreadyCoveredWatchtime() {
        val segments = WatchTimeSegments()

        segments.onPlay(0L)
        segments.onProgress(20_000L)
        val first = segments.drain(currentPositionMs = 20_000L, continuePlaying = true)
        segments.onSeek(fromPositionMs = 20_000L, toPositionMs = 10_000L, continuePlaying = true)
        segments.onProgress(25_000L)
        val second = segments.drain(currentPositionMs = 25_000L, continuePlaying = false)

        assertEquals(listOf(0L to 20_000L), first?.segments)
        assertEquals(listOf(20_000L to 25_000L), second?.segments)
    }
}
