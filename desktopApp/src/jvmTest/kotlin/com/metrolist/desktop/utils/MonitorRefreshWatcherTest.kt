package com.metrolist.desktop.utils

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorRefreshWatcherTest {
    @Test
    fun hiddenWindowsStopProbesAndRestoringOneSettlesBeforeRefreshing() =
        runTest {
            val watcher = MonitorRefreshWatcher()
            val main = Any()
            val secondary = Any()
            var refreshes = 0
            backgroundScope.launch { watcher.watch { refreshes++ } }
            runCurrent()
            watcher.requestRefresh()
            advanceTimeBy(60_000L)
            assertEquals(0, refreshes)

            watcher.setWindowVisible(main, true)
            runCurrent()
            advanceTimeBy(1_999L)
            assertEquals(0, refreshes)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, refreshes)
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(2, refreshes)

            watcher.setWindowVisible(secondary, true)
            watcher.setWindowVisible(main, false)
            runCurrent()
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(3, refreshes)
            watcher.setWindowVisible(secondary, false)
            runCurrent()
            watcher.requestRefresh()
            advanceTimeBy(90_000L)
            runCurrent()
            assertEquals(3, refreshes)

            watcher.setWindowVisible(main, true)
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(4, refreshes)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(4, refreshes)
        }

    @Test
    fun requestedRefreshesCoalesceAndHidingCancelsTheSettle() =
        runTest {
            val watcher = MonitorRefreshWatcher()
            val window = Any()
            var refreshes = 0
            backgroundScope.launch { watcher.watch { refreshes++ } }
            watcher.setWindowVisible(window, true)
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(1, refreshes)

            watcher.requestRefresh()
            runCurrent()
            advanceTimeBy(1_000L)
            watcher.requestRefresh()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(2, refreshes)
            watcher.requestRefresh()
            runCurrent()
            advanceTimeBy(1_000L)
            watcher.setWindowVisible(window, false)
            runCurrent()
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(2, refreshes)
        }
}
