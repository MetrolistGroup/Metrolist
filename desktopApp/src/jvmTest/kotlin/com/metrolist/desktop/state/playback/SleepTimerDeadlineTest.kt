package com.metrolist.desktop.state.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerDeadlineTest {
    @Test
    fun endOfSongFadeTracksDurationDiscoverySeeksAndPlaybackSpeed() =
        runTest {
            val position = MutableStateFlow(0L)
            val duration = MutableStateFlow(0L)
            val speed = MutableStateFlow(1f)
            var latest: Pair<Long?, Float>? = null
            backgroundScope.launch {
                endOfSongSleepTimerProgress(position, duration, speed).collect { latest = it }
            }
            runCurrent()
            assertEquals(null to 1f, latest)
            duration.value = 120_000L
            runCurrent()
            assertEquals(120_000L to 1f, latest)
            position.value = 90_000L
            runCurrent()
            assertEquals(30_000L to 0.5f, latest)
            speed.value = 2f
            runCurrent()
            assertEquals(15_000L to 0.25f, latest)
            position.value = 0L
            runCurrent()
            assertEquals(60_000L to 1f, latest)
            position.value = 120_000L
            runCurrent()
            assertEquals(0L to 0f, latest)
        }

    @Test
    fun finishSongCountdownDoesNotFadeBeforeHandingOffAtExpiry() =
        runTest {
            val remaining = MutableStateFlow(0L)
            val visible = MutableStateFlow(false)
            val gains = mutableListOf<Float>()
            val deadline = SleepTimerDeadline(120_000L) { testScheduler.currentTime }
            val job =
                backgroundScope.launch {
                    deadline.awaitExpiry(true, remaining, visible, applyGain = gains::add, finishCurrentSong = true)
                }
            runCurrent()
            advanceTimeBy(119_999L)
            runCurrent()
            assertTrue(job.isActive)
            assertTrue(gains.isEmpty())
            advanceTimeBy(1L)
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(0L, remaining.value)
            assertTrue(gains.isEmpty())
        }

    @Test
    fun hiddenCountdownOnlyWakesForFadeAndExpiry() =
        runTest {
            val remaining = MutableStateFlow(0L)
            val visible = MutableStateFlow(false)
            val gains = mutableListOf<Float>()
            val timer = SleepTimerDeadline(600_000L) { testScheduler.currentTime }
            val job = backgroundScope.launch { timer.awaitExpiry(true, remaining, visible, applyGain = gains::add) }
            runCurrent()
            assertEquals(listOf(1f), gains)
            advanceTimeBy(539_999L)
            runCurrent()
            assertEquals(listOf(1f), gains)
            advanceTimeBy(501L)
            runCurrent()
            assertTrue(gains.last() < 1f)
            advanceTimeBy(59_500L)
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(0L, remaining.value)
            assertEquals(0f, gains.last())
        }

    @Test
    fun resumedPlaybackDetectsExpiryEvenIfTheCoroutineDelayDidNotAdvanceDuringSuspend() =
        runTest {
            var wallTime = 0L
            val remaining = MutableStateFlow(0L)
            val visible = MutableStateFlow(false)
            val position = MutableStateFlow(0L)
            val timer = SleepTimerDeadline(600_000L) { wallTime }
            val job =
                backgroundScope.launch {
                    timer.awaitExpiry(false, remaining, visible, position.map { Unit }) { }
                }
            runCurrent()
            wallTime = 900_000L
            position.value = 10L
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(0L, remaining.value)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun cancellationAndClockJumpsAreRespectedAtWakeup() =
        runTest {
            var offset = 0L
            val remaining = MutableStateFlow(0L)
            val visible = MutableStateFlow(false)
            val timer = SleepTimerDeadline(60_000L) { testScheduler.currentTime + offset }
            val job = backgroundScope.launch { timer.awaitExpiry(false, remaining, visible) { } }
            runCurrent()
            offset = 120_000L
            val observer = backgroundScope.launch { remaining.collect { } }
            visible.value = true
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(0L, remaining.value)
            observer.cancel()

            val cancelled =
                backgroundScope.launch {
                    SleepTimerDeadline(60_000L) { testScheduler.currentTime }.awaitExpiry(true, remaining, visible) { }
                }
            runCurrent()
            cancelled.cancel()
            val last = remaining.value
            advanceTimeBy(120_000L)
            runCurrent()
            assertEquals(last, remaining.value)
        }

    @Test
    fun schedulerStallsAndSuspendConsumeTheElapsedTime() {
        var now = 10_000L
        val timer = SleepTimerDeadline(60_000) { now }
        now += 825
        assertEquals(59_175L, timer.remainingMs())
        now += 600_000
        assertEquals(0L, timer.remainingMs())
    }

    @Test
    fun wallClockCorrectionsShiftTheRemainingTime() {
        var now = 100_000L
        val timer = SleepTimerDeadline(60_000) { now }
        now -= 10_000
        assertEquals(70_000L, timer.remainingMs())
        now += 80_000
        assertEquals(0L, timer.remainingMs())
    }
}
