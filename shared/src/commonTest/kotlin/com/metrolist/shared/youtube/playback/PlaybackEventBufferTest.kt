package com.metrolist.shared.youtube.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackEventBufferTest {
    @Test
    fun evictsOldestWithoutFormattingAndKeepsSnapshotsAndIdsAcrossClear() {
        val buffer = PlaybackEventBuffer(3)
        var formatted = 0
        repeat(10) { index ->
            buffer.append { id ->
                formatted++
                event(id, index.toString())
            }
        }
        assertEquals(0, formatted)
        val snapshot = buffer.value
        assertEquals(listOf("7", "8", "9"), snapshot.map { it.message })
        assertEquals(listOf(8L, 9L, 10L), snapshot.map { it.id })
        assertEquals(3, formatted)
        assertEquals(snapshot, buffer.value)
        assertEquals(3, formatted)

        buffer.clear()
        assertTrue(buffer.value.isEmpty())
        buffer.append { event(it, "after clear") }
        assertEquals(11L, buffer.value.single().id)
        assertEquals(listOf("7", "8", "9"), snapshot.map { it.message })
    }

    @Test
    fun batchesBurstsAndPublishesTheLastEventAndClearWithoutAnotherAppend() =
        runTest {
            val buffer = PlaybackEventBuffer(500, StandardTestDispatcher(testScheduler))
            val snapshots = mutableListOf<List<PlaybackEvent>>()
            backgroundScope.launch { buffer.updates.collect { snapshots.add(it) } }
            runCurrent()
            assertEquals(1, snapshots.size)

            repeat(1_000) { index -> buffer.append { event(it, index.toString()) } }
            runCurrent()
            assertEquals(1, snapshots.size)
            advanceTimeBy(250)
            runCurrent()
            assertEquals(2, snapshots.size)
            assertEquals("999", snapshots.last().last().message)
            assertEquals(500, snapshots.last().size)

            buffer.clear()
            advanceTimeBy(250)
            runCurrent()
            assertTrue(snapshots.last().isEmpty())
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(3, snapshots.size)
        }

    @Test
    fun concurrentProducersRetainOrderedUniqueIdsWithinTheLimit() =
        runTest {
            val buffer = PlaybackEventBuffer(500)
            coroutineScope {
                repeat(8) { producer ->
                    launch(Dispatchers.Default) {
                        repeat(1_000) { index -> buffer.append { event(it, "$producer/$index") } }
                    }
                }
            }
            assertEquals((7_501L..8_000L).toList(), buffer.value.map { it.id })
        }

    private fun event(
        id: Long,
        message: String,
    ) = PlaybackEvent(id, 0L, PlaybackEventLevel.DEBUG, PlaybackEventKind.TRACE, message)
}
