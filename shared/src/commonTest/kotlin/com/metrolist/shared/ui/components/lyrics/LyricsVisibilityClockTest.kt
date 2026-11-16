package com.metrolist.shared.ui.components.lyrics

import com.metrolist.shared.model.LyricsEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun pausedClocksStaySampleDrivenAfterReopening() {
        for (wordSynced in listOf(false, true)) {
            for (casting in listOf(false, true)) {
                runTest {
                    val samples = MutableStateFlow(1_000L)
                    val visible = MutableStateFlow(false)
                    var nativePosition = 2_000L
                    var reads = 0
                    var position = 0L
                    backgroundScope.launch {
                        positions(wordSynced, samples, visible, isPlaying = false, isCasting = casting, positionReader = {
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

    @Test
    fun castClocksInterpolateAtUnitySpeedWithoutReadingLocalPlayer() {
        for (wordSynced in listOf(false, true)) {
            runTest {
                val samples = MutableStateFlow(1_000L)
                val visible = MutableStateFlow(true)
                var reads = 0
                var position = 0L
                val reader = {
                    reads++
                    99_999L
                }
                backgroundScope.launch {
                    val flow =
                        if (wordSynced) {
                            smoothLyricsPositions(
                                positionFlow = samples,
                                isPlaying = true,
                                playbackSpeed = 2f,
                                visibility = visible,
                                isCasting = true,
                                positionReader = reader,
                                timeSource = testScheduler.timeSource,
                                tickOrigin = testScheduler.timeSource.markNow(),
                            )
                        } else {
                            deadlineLyricsPositions(
                                positionFlow = samples,
                                deadlines = listOf(1_000L, 1_250L, 1_500L),
                                initial = LyricsDeadlinePosition(samples.value),
                                isPlaying = true,
                                isCasting = true,
                                playbackSpeed = 2f,
                                timingOffsetMs = 0,
                                visibility = visible,
                                timeSource = testScheduler.timeSource,
                                positionReader = reader,
                            ).map { it.positionMs }
                        }
                    flow.collect { position = it }
                }
                runCurrent()
                assertEquals(1_000L, position)

                advanceTimeBy(300L)
                runCurrent()
                assertEquals(0, reads)
                if (wordSynced) {
                    assertTrue(position in 1_280L..1_300L, "Expected ~1300ms while casting, got $position")
                } else {
                    assertEquals(1_250L, position)
                }

                samples.value = 5_000L
                runCurrent()
                assertEquals(5_000L, position)
                assertEquals(0, reads)
            }
        }
    }

    @Test
    fun castClocksStaySampleDrivenWhileBufferingAndResumeOnRecovery() {
        for (wordSynced in listOf(false, true)) {
            runTest {
                val samples = MutableStateFlow(1_000L)
                val visible = MutableStateFlow(true)
                val buffering = MutableStateFlow(true)
                var reads = 0
                var position = 0L
                var seekGeneration = 0
                val reader = {
                    reads++
                    99_999L
                }
                val anchor = LyricsPlaybackAnchor(samples.value, isPlaying = false, playbackSpeed = 1f, testScheduler.timeSource)
                backgroundScope.launch {
                    buffering.collectLatest { isBuffering ->
                        if (wordSynced) {
                            smoothLyricsPositions(
                                positionFlow = samples,
                                isPlaying = true,
                                playbackSpeed = 1.5f,
                                visibility = visible,
                                isCasting = true,
                                isBuffering = isBuffering,
                                positionReader = reader,
                                timeSource = testScheduler.timeSource,
                                tickOrigin = testScheduler.timeSource.markNow(),
                            ).collect { position = it }
                        } else {
                            deadlineLyricsPositions(
                                positionFlow = samples,
                                deadlines = listOf(1_000L, 1_400L, 1_650L, 2_000L),
                                initial = LyricsDeadlinePosition(position.coerceAtLeast(samples.value), seekGeneration),
                                isPlaying = true,
                                isCasting = true,
                                playbackSpeed = 1.5f,
                                timingOffsetMs = 0,
                                visibility = visible,
                                isBuffering = isBuffering,
                                timeSource = testScheduler.timeSource,
                                anchor = anchor,
                                positionReader = reader,
                            ).collect {
                                position = it.positionMs
                                seekGeneration = it.seekGeneration
                            }
                        }
                    }
                }
                runCurrent()
                assertEquals(1_000L, position)

                advanceTimeBy(1_000L)
                runCurrent()
                assertEquals(1_000L, position)
                assertEquals(0, reads)

                samples.value = 1_400L
                runCurrent()
                assertEquals(1_400L, position)
                advanceTimeBy(500L)
                runCurrent()
                assertEquals(1_400L, position)

                buffering.value = false
                runCurrent()
                assertEquals(0, seekGeneration)
                advanceTimeBy(300L)
                runCurrent()
                assertEquals(0, reads)
                assertEquals(0, seekGeneration)
                if (wordSynced) {
                    assertTrue(position in 1_680L..1_700L, "Expected ~1700ms after recovery, got $position")
                } else {
                    assertEquals(1_650L, position)
                }
            }
        }
    }

    @Test
    fun castClocksIgnoreQuantizedPollJitterButDetectRealSeeks() {
        for (wordSynced in listOf(false, true)) {
            runTest {
                val samples = MutableStateFlow(10_000L)
                val visible = MutableStateFlow(true)
                var position = 0L
                var seekGeneration = 0
                backgroundScope.launch {
                    if (wordSynced) {
                        smoothLyricsPositions(
                            positionFlow = samples,
                            isPlaying = true,
                            playbackSpeed = 1f,
                            visibility = visible,
                            isCasting = true,
                            timeSource = testScheduler.timeSource,
                            tickOrigin = testScheduler.timeSource.markNow(),
                        ).collect { position = it }
                    } else {
                        deadlineLyricsPositions(
                            positionFlow = samples,
                            deadlines = listOf(10_000L, 12_500L, 20_000L),
                            initial = LyricsDeadlinePosition(samples.value),
                            isPlaying = true,
                            isCasting = true,
                            playbackSpeed = 1f,
                            timingOffsetMs = 0,
                            visibility = visible,
                            timeSource = testScheduler.timeSource,
                        ).collect {
                            position = it.positionMs
                            seekGeneration = it.seekGeneration
                        }
                    }
                }
                runCurrent()
                assertEquals(10_000L, position)

                advanceTimeBy(1_400L)
                runCurrent()
                samples.value = 11_000L
                runCurrent()
                if (wordSynced) {
                    assertTrue(position >= 11_380L, "Expected smooth clock not to rewind on 1s DLNA poll, got $position")
                } else {
                    assertEquals(0, seekGeneration)
                }

                samples.value = 20_000L
                runCurrent()
                assertEquals(20_000L, position)
                if (!wordSynced) {
                    assertEquals(1, seekGeneration)
                }
            }
        }
    }

    @Test
    fun castDeadlineSyncEngineDoesNotTreatLongNormalGapsAsSeeks() {
        runTest {
            val lyrics =
                listOf(
                    LyricsEntry(time = 1_000L, text = "First line"),
                    LyricsEntry(time = 5_000L, text = "Second line"),
                    LyricsEntry(time = 12_000L, text = "Third line"),
                )
            val items = buildLyricsTimelineItems(lyrics.mapIndexed { index, entry -> LyricsListItem.Line(index, entry) }, lyrics)
            val engine = LyricsSyncEngine(items, firstLineListIndex = 0, activeByRealTiming = true)
            val deadlines = buildLineLyricsDeadlines(items, lyrics)
            val samples = MutableStateFlow(1_000L)
            val visible = MutableStateFlow(true)
            val snapshots = mutableListOf<LyricsSyncSnapshot>()
            backgroundScope.launch {
                deadlineLyricsPositions(
                    positionFlow = samples,
                    deadlines = deadlines,
                    initial = LyricsDeadlinePosition(samples.value),
                    isPlaying = true,
                    isCasting = true,
                    playbackSpeed = 1f,
                    timingOffsetMs = 0,
                    visibility = visible,
                    timeSource = testScheduler.timeSource,
                ).collect { published ->
                    snapshots +=
                        engine.update(
                            published.positionMs,
                            shouldFollowPlayback = true,
                            isWordByWord = false,
                            seekGeneration = published.seekGeneration,
                        )
                }
            }
            runCurrent()
            advanceTimeBy(4_100L)
            runCurrent()
            assertTrue(snapshots.any { it.primaryLineIndex == 1 })
            assertFalse(snapshots.last().isSeek)

            samples.value = 12_000L
            runCurrent()
            assertTrue(snapshots.last().isSeek)
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
