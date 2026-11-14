package com.metrolist.shared.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCheckpointsTest {
    @Test
    fun checkpointsEveryThirtySecondsAndFlushesPauseWithoutAnotherTimer() =
        runTest {
            val playing = MutableStateFlow(true)
            val enabled = MutableStateFlow(true)
            val writes = mutableListOf<Long>()
            backgroundScope.launch {
                observePlaybackCheckpoints(playing, enabled) { writes += testScheduler.currentTime }
            }
            runCurrent()
            advanceTimeBy(29_999)
            assertEquals(listOf(0L), writes)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(0L, 30_000L), writes)
            advanceTimeBy(1_234)
            playing.value = false
            runCurrent()
            advanceTimeBy(120_000)
            assertEquals(listOf(0L, 30_000L, 31_234L), writes)
        }

    @Test
    fun disabledPersistenceClearsInitiallyAndOnDisableButNeverTicks() =
        runTest {
            val playing = MutableStateFlow(true)
            val enabled = MutableStateFlow(false)
            var writes = 0
            backgroundScope.launch {
                observePlaybackCheckpoints(playing, enabled) { writes++ }
            }
            runCurrent()
            advanceTimeBy(120_000)
            assertEquals(1, writes)
            enabled.value = true
            runCurrent()
            assertEquals(2, writes)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(3, writes)
            enabled.value = false
            runCurrent()
            advanceTimeBy(120_000)
            assertEquals(4, writes)
        }
}
