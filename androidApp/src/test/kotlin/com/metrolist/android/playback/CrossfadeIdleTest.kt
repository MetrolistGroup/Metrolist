package com.metrolist.android.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrossfadeIdleTest {
    @Test
    fun pauseSuspendsGainUpdatesAndResumeContinuesTheSameFade() =
        runTest {
            val requested = MutableStateFlow(true)
            var frames = 0
            val fade = backgroundScope.launch { runCrossfadeFrames(requested) { ++frames < 4 } }
            runCurrent()
            assertEquals(1, frames)
            requested.value = false
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(1, frames)
            requested.value = true
            runCurrent()
            assertEquals(2, frames)
            advanceTimeBy(32)
            runCurrent()
            assertEquals(4, frames)
            assertTrue(fade.isCompleted)
        }

    @Test
    fun cancellingAPausedFadeDoesNotWaitForResume() =
        runTest {
            var frames = 0
            val fade =
                backgroundScope.launch {
                    runCrossfadeFrames(MutableStateFlow(false)) {
                        frames++
                        true
                    }
                }
            runCurrent()
            fade.cancelAndJoin()
            assertEquals(0, frames)
            assertTrue(fade.isCancelled)
        }
}
