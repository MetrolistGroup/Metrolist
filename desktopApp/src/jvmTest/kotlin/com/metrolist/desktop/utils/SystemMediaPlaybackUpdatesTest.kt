package com.metrolist.desktop.utils

import dev.nucleusframework.media.control.MediaPlaybackState
import dev.nucleusframework.media.control.MediaPlaybackStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SystemMediaPlaybackUpdatesTest {
    @Test
    fun nonLinuxPositionsArePublishedOncePerSecondButSeeksAndTransportAreImmediate() =
        runTest {
            val changes = MutableSharedFlow<MediaPlaybackState>()
            val positions = MutableStateFlow(0L)
            val updates = mutableListOf<MediaPlaybackState>()
            var livePosition: Long? = null
            backgroundScope.launch {
                systemMediaPlaybackUpdates(changes, positions, isLinux = false) { livePosition ?: positions.value }
                    .collect { updates += it }
            }
            runCurrent()
            changes.emit(MediaPlaybackState(MediaPlaybackStatus.PLAYING, 0L))
            runCurrent()
            repeat(19) {
                advanceTimeBy(50L)
                positions.value += 50L
                runCurrent()
            }
            assertEquals(listOf(0L), updates.map { it.positionMs })
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(listOf(0L, 950L), updates.map { it.positionMs })

            positions.value = 1_000L
            livePosition = 2_000L
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(2_000L, updates.last().positionMs, "Periodic anchors read the live clock instead of a stale sample")
            livePosition = null

            changes.emit(MediaPlaybackState(MediaPlaybackStatus.PLAYING, 40_000L))
            runCurrent()
            assertEquals(40_000L, updates.last().positionMs)
            positions.value = 40_000L
            changes.emit(MediaPlaybackState(MediaPlaybackStatus.PAUSED, 40_000L))
            runCurrent()
            assertEquals(MediaPlaybackStatus.PAUSED, updates.last().status)
            val pausedCount = updates.size
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(pausedCount, updates.size)
            positions.value = 50_000L
            runCurrent()
            assertEquals(50_000L, updates.last().positionMs)
            changes.emit(MediaPlaybackState(MediaPlaybackStatus.PAUSED, 0L))
            runCurrent()
            assertEquals(0L, updates.last().positionMs, "A paused track change is immediate")
        }

    @Test
    fun linuxPositionsReadTheLiveClockEveryQuarterSecondWithoutUiSamples() =
        runTest {
            val changes = MutableSharedFlow<MediaPlaybackState>()
            val positions = MutableStateFlow(137L)
            val updates = mutableListOf<MediaPlaybackState>()
            var livePosition = 137L
            backgroundScope.launch {
                systemMediaPlaybackUpdates(changes, positions, isLinux = true) { livePosition }
                    .collect { updates += it }
            }
            runCurrent()
            changes.emit(MediaPlaybackState(MediaPlaybackStatus.PLAYING, livePosition))
            runCurrent()
            repeat(4) {
                advanceTimeBy(249L)
                runCurrent()
                assertEquals(it + 1, updates.size)
                livePosition += 500L
                advanceTimeBy(1L)
                runCurrent()
                assertEquals(livePosition, updates.last().positionMs)
            }
            assertEquals(listOf(137L, 637L, 1_137L, 1_637L, 2_137L), updates.map { it.positionMs })

            advanceTimeBy(100L)
            livePosition = 40_123L
            changes.emit(MediaPlaybackState(MediaPlaybackStatus.PLAYING, livePosition))
            runCurrent()
            assertEquals(livePosition, updates.last().positionMs, "Seeks publish immediately between ticks")
            changes.emit(MediaPlaybackState(MediaPlaybackStatus.PAUSED, livePosition))
            runCurrent()
            assertEquals(MediaPlaybackStatus.PAUSED, updates.last().status)
            val pausedCount = updates.size
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(pausedCount, updates.size)

            livePosition = 50_123L
            positions.value = livePosition
            runCurrent()
            assertEquals(livePosition, updates.last().positionMs, "Paused position changes publish immediately")
            changes.emit(MediaPlaybackState(MediaPlaybackStatus.PLAYING, livePosition))
            runCurrent()
            assertEquals(MediaPlaybackStatus.PLAYING, updates.last().status)
            livePosition += 250L
            advanceTimeBy(250L)
            runCurrent()
            assertEquals(livePosition, updates.last().positionMs, "Resume restarts the live clock updates")

            changes.emit(MediaPlaybackState(MediaPlaybackStatus.STOPPED, 0L))
            runCurrent()
            assertEquals(MediaPlaybackState(MediaPlaybackStatus.STOPPED, 0L), updates.last())
            val stoppedCount = updates.size
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(stoppedCount, updates.size)
        }
}
