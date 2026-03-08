package com.metrolist.shared.ui.components.sliders

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class WavePhaseTest {
    @Test
    fun phaseKeepsItsSpeedWithoutRequestingFrames() =
        runTest {
            val clock = BroadcastFrameClock()
            val deltas = mutableListOf<Float>()
            backgroundScope.launch(clock) {
                animateWavePhase(MutableStateFlow(true), 2_500L, testScheduler.timeSource) { deltas += it }
            }
            advanceTimeBy(1_001)
            runCurrent()

            assertEquals(30, deltas.size)
            assertEquals(0.4f, deltas.sum(), 0.001f)
            assertFalse(clock.hasAwaiters)
        }

    @Test
    fun hidingStopsUpdatesAndResumingDoesNotAdvanceThroughHiddenTime() =
        runTest {
            val visible = MutableStateFlow(true)
            val deltas = mutableListOf<Float>()
            backgroundScope.launch {
                animateWavePhase(visible, 1_000L, testScheduler.timeSource) { deltas += it }
            }
            advanceTimeBy(101)
            visible.value = false
            runCurrent()
            val beforeHiding = deltas.sum()
            val count = deltas.size
            advanceTimeBy(60_000)
            assertEquals(count, deltas.size)

            visible.value = true
            runCurrent()
            advanceTimeBy(101)
            assertEquals(beforeHiding + 0.1f, deltas.sum(), 0.001f)
            assertEquals(count + 3, deltas.size)
        }
}
