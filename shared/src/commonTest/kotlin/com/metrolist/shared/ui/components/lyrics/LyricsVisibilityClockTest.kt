package com.metrolist.shared.ui.components.lyrics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsVisibilityClockTest {
    @Test
    fun bothClocksResumeImmediatelyAndOnlyTickWhileVisible() {
        for (wordSynced in listOf(false, true)) {
            runTest {
                val samples = MutableStateFlow(1_000L)
                val visible = MutableStateFlow(false)
                var reads = 0
                val nativePosition = { 5_000L + testScheduler.currentTime }
                val publishedPositions = mutableListOf<Long>()
                backgroundScope.launch {
                    positions(wordSynced, samples, visible, positionReader = {
                        reads++
                        nativePosition()
                    }).collect { publishedPositions += it }
                }
                runCurrent()
                assertEquals(listOf(1_000L), publishedPositions)
                advanceTimeBy(1_000L)
                runCurrent()
                assertEquals(0, reads)
                assertEquals(1, publishedPositions.size)
                samples.value = 2_000L
                runCurrent()
                assertEquals(2_000L, publishedPositions.last())
                assertEquals(0, reads)

                visible.value = true
                runCurrent()
                assertEquals(nativePosition(), publishedPositions.last())
                val readsAtOpen = reads
                advanceTimeBy(200L)
                runCurrent()
                assertTrue(reads > readsAtOpen)
                assertTrue(publishedPositions.last() > 6_000L)

                visible.value = false
                runCurrent()
                val readsAtClose = reads
                val updatesAtClose = publishedPositions.size
                advanceTimeBy(1_000L)
                runCurrent()
                assertEquals(readsAtClose, reads)
                assertEquals(updatesAtClose, publishedPositions.size)
                samples.value = 3_000L
                runCurrent()
                assertEquals(3_000L, publishedPositions.last())
                assertEquals(readsAtClose, reads)

                visible.value = true
                runCurrent()
                assertEquals(nativePosition(), publishedPositions.last())
            }
        }
    }

    @Test
    fun pausedAndCastClocksStaySampleDrivenAfterReopening() {
        for (wordSynced in listOf(false, true)) {
            for (casting in listOf(false, true)) {
                runTest {
                    val samples = MutableStateFlow(1_000L)
                    val visible = MutableStateFlow(false)
                    var nativePosition = 2_000L
                    var reads = 0
                    var position = 0L
                    backgroundScope.launch {
                        positions(wordSynced, samples, visible, isPlaying = casting, isCasting = casting, positionReader = {
                            reads++
                            nativePosition
                        }).collect { position = it }
                    }
                    runCurrent()
                    assertEquals(0, reads)
                    visible.value = true
                    runCurrent()
                    assertEquals(if (casting) samples.value else nativePosition, position)
                    val readsAtOpen = reads
                    advanceTimeBy(1_000L)
                    runCurrent()
                    assertEquals(readsAtOpen, reads)

                    nativePosition = 500L
                    samples.value = 400L
                    runCurrent()
                    assertEquals(if (casting) samples.value else nativePosition, position)
                    if (casting) assertEquals(0, reads)
                }
            }
        }
    }

    private fun TestScope.positions(
        wordSynced: Boolean,
        samples: MutableStateFlow<Long>,
        visible: MutableStateFlow<Boolean>,
        isPlaying: Boolean = true,
        isCasting: Boolean = false,
        positionReader: () -> Long,
    ): Flow<Long> =
        if (wordSynced) {
            smoothLyricsPositions(
                positionFlow = samples,
                isPlaying = isPlaying,
                playbackSpeed = 1f,
                visibility = visible,
                isCasting = isCasting,
                positionReader = positionReader,
                timeSource = testScheduler.timeSource,
                tickOrigin = testScheduler.timeSource.markNow(),
            )
        } else {
            deadlineLyricsPositions(
                positionFlow = samples,
                deadlines = (0L..20_000L step 100L).toList(),
                initial = LyricsDeadlinePosition(samples.value),
                isPlaying = isPlaying,
                isCasting = isCasting,
                playbackSpeed = 1f,
                timingOffsetMs = 0,
                visibility = visible,
                timeSource = testScheduler.timeSource,
                positionReader = positionReader,
            ).map { it.positionMs }
        }
}
