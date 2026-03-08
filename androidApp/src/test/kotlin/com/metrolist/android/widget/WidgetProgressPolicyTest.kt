package com.metrolist.android.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetProgressPolicyTest {
    @Test
    fun samplerRequiresPlaybackAndAVisibleTimeline() {
        assertTrue(shouldSampleWidgetProgress(true, true, true, 100, true, true))
        assertFalse(shouldSampleWidgetProgress(false, true, true, 100, true, true))
        assertFalse(shouldSampleWidgetProgress(true, false, true, 100, true, true))
        assertFalse(shouldSampleWidgetProgress(true, true, false, 100, true, true))
        assertFalse(shouldSampleWidgetProgress(true, true, true, 0, true, true))
        assertFalse(shouldSampleWidgetProgress(true, true, true, -1, true, true))
        assertFalse(shouldSampleWidgetProgress(true, true, true, 100, false, true))
        assertFalse(shouldSampleWidgetProgress(true, true, true, 100, true, false))
    }

    @Test
    fun oneSamplerEmitsImmediatelyThenEveryTenSecondsAndStopsWithItsOwner() =
        runTest {
            val active = MutableStateFlow(false)
            var samples = 0
            val job = launch { sampleWidgetProgress(active) { samples++ } }
            runCurrent()
            advanceTimeBy(30_000)
            assertEquals(0, samples)
            active.value = true
            runCurrent()
            assertEquals(1, samples)
            advanceTimeBy(9_999)
            assertEquals(1, samples)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, samples)
            active.value = true
            runCurrent()
            assertEquals(2, samples)
            active.value = false
            runCurrent()
            advanceTimeBy(30_000)
            assertEquals(2, samples)
            active.value = true
            runCurrent()
            assertEquals(3, samples)
            job.cancel()
            runCurrent()
            advanceTimeBy(30_000)
            assertEquals(3, samples)
        }

    @Test
    fun screenOffOrRemovalCancelsAnUpdateWaitingToPublish() =
        runTest {
            val active = MutableStateFlow(true)
            val publication = CompletableDeferred<Unit>()
            var samples = 0
            val job =
                launch {
                    sampleWidgetProgress(active) {
                        publication.await()
                        samples++
                    }
                }
            runCurrent()
            active.value = false
            runCurrent()
            publication.complete(Unit)
            runCurrent()
            assertEquals(0, samples)
            active.value = true
            runCurrent()
            assertEquals(1, samples)
            job.cancel()
        }

    @Test
    fun progressLevelsClampSeeksAndQuantizeUnchangedPositions() {
        assertEquals(0, progressLevel(0, 10))
        assertEquals(0, progressLevel(100, -1))
        assertEquals(10_000, progressLevel(100, 101))
        assertEquals(5_000, progressLevel(200, 100))
        assertEquals(progressLevel(1_000_000, 100_000), progressLevel(1_000_000, 100_001))
    }
}
