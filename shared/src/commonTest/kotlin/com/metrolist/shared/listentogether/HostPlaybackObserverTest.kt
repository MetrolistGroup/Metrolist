package com.metrolist.shared.listentogether

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class HostPlaybackObserverTest {
    @Test
    fun pausedHostsPublishChangesWithoutPollingAndResumePeriodicPositionUpdates() =
        runTest {
            val changes = MutableSharedFlow<Boolean>(replay = 1).apply { tryEmit(false) }
            var publications = 0
            backgroundScope.launch { observeHostPlayback(changes, 800) { publications++ } }
            runCurrent()
            assertEquals(1, publications)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, publications)
            changes.emit(false)
            runCurrent()
            assertEquals(2, publications)
            changes.emit(true)
            runCurrent()
            advanceTimeBy(1_600)
            runCurrent()
            assertEquals(5, publications)
            changes.emit(false)
            runCurrent()
            assertEquals(6, publications)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(6, publications)
        }
}
