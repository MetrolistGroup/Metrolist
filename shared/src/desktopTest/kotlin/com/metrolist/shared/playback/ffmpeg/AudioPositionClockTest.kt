package com.metrolist.shared.playback.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioPositionClockTest {
    @Test
    fun silenceOffsetsApplyOnlyWhenOutputReachesThemAndSurviveSpeedChanges() {
        val clock = AudioPositionClock(1_000f)
        clock.reset(10_000, 0, 1f)
        clock.skipSilence(100, 400)
        clock.skipSilence(200, 800)
        clock.changeSpeed(200, 2f)
        assertEquals(10_050L, clock.position(50))
        assertEquals(10_500L, clock.position(100))
        assertEquals(11_400L, clock.position(200))
        assertEquals(11_600L, clock.position(300))
        clock.reset(500, 300, 1f)
        assertEquals(550L, clock.position(350))
    }

    @Test
    fun transientOutputCounterRegressionCannotMovePlaybackBackward() {
        val clock = AudioPositionClock(sampleRate = 48_000f)
        clock.reset(mediaMs = 10_000L, lineFrame = 1_000L, playbackSpeed = 1f)

        assertEquals(10_010L, clock.position(lineFrame = 1_480L))
        assertEquals(10_010L, clock.position(lineFrame = 1_200L))
        assertEquals(10_020L, clock.position(lineFrame = 1_960L))
    }

    @Test
    fun queuedOutputKeepsItsOldSpeedBeforeTheNewSpeedBoundary() {
        val clock = AudioPositionClock(sampleRate = 48_000f)
        clock.reset(mediaMs = 0L, lineFrame = 0L, playbackSpeed = 1f)
        clock.changeSpeed(firstFrameAtNewSpeed = 96_000L, playbackSpeed = 2f)

        assertEquals(1_000L, clock.position(lineFrame = 48_000L))
        assertEquals(2_000L, clock.position(lineFrame = 96_000L))
        assertEquals(3_000L, clock.position(lineFrame = 120_000L))
    }

    @Test
    fun explicitSeekCanMoveBackward() {
        val clock = AudioPositionClock(sampleRate = 48_000f)
        clock.reset(mediaMs = 10_000L, lineFrame = 0L, playbackSpeed = 1f)
        assertEquals(11_000L, clock.position(lineFrame = 48_000L))

        clock.reset(mediaMs = 500L, lineFrame = 48_000L, playbackSpeed = 2f)
        assertEquals(500L, clock.position(lineFrame = 48_000L))
    }
}
