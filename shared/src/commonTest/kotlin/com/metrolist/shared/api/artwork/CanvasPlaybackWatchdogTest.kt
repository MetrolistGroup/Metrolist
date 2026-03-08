package com.metrolist.shared.api.artwork

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasPlaybackWatchdogTest {
    @Test
    fun pauseOrDetachedSurfaceStopsSamplingAndResumeStartsANewStallDeadline() =
        runTest {
            val active = MutableStateFlow(false)
            val watchdog = CanvasPlaybackWatchdog()
            var reads = 0
            var failures = 0
            backgroundScope.launch {
                watchdog.observe(active, { testScheduler.currentTime }, {
                    reads++
                    1L
                }, { failures++ })
            }
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, reads)
            active.value = true
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(0, failures)
            active.value = false
            runCurrent()
            val pausedReads = reads
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(pausedReads, reads)
            active.value = true
            advanceTimeBy(14_999)
            runCurrent()
            assertEquals(0, failures)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, failures)
            val failedReads = reads
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(failedReads, reads)
        }
}
