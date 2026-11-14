package com.metrolist.shared.ui.components.lyrics

import androidx.compose.runtime.BroadcastFrameClock
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsHighlightTimingTest {
    private val inactive = LineLook(0.35f, 0.35f, 0.35f, 0f, 0.97f)
    private val active = LineLook(1f, 0.5f, 0.35f, 1f, 1f)

    @Test
    fun highlightFadesInOverAlphaDuration() =
        runTest {
            val clock = BroadcastFrameClock()
            val look = LineLookState(inactive)
            val animation = launch(clock) { look.animateTo(active, alphaMs = 750, scaleMs = 800) }
            runCurrent()

            assertEquals(inactive.alpha, look.alpha)
            assertEquals(inactive.translationAlpha, look.translationAlpha)
            assertEquals(inactive.scale, look.scale)
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(375_000_000L)
            runCurrent()
            assertTrue(look.alpha > inactive.alpha && look.alpha < active.alpha)
            assertTrue(look.translationAlpha > inactive.translationAlpha && look.translationAlpha < active.translationAlpha)
            clock.sendFrame(800_000_000L)
            runCurrent()
            assertEquals(active.alpha, look.alpha)
            assertEquals(active.translationAlpha, look.translationAlpha)
            assertEquals(active.scale, look.scale)
            animation.join()
        }

    @Test
    fun reactivationSmoothlyTransitionsAlpha() =
        runTest {
            val clock = BroadcastFrameClock()
            val look = LineLookState(active)
            val fade = launch(clock) { look.animateTo(inactive, alphaMs = 750, scaleMs = 800) }
            runCurrent()
            clock.sendFrame(0L)
            runCurrent()
            clock.sendFrame(100_000_000L)
            runCurrent()
            assertTrue(look.alpha > inactive.alpha && look.alpha < active.alpha)

            fade.cancel()
            val intermediateAlpha = look.alpha
            val intermediateTranslationAlpha = look.translationAlpha
            val activation = launch(clock) { look.animateTo(active, alphaMs = 750, scaleMs = 800) }
            runCurrent()
            assertEquals(intermediateAlpha, look.alpha, 0.0001f)
            assertEquals(intermediateTranslationAlpha, look.translationAlpha, 0.0001f)
            clock.sendFrame(100_000_000L)
            runCurrent()
            assertEquals(intermediateAlpha, look.alpha, 0.0001f)
            assertEquals(intermediateTranslationAlpha, look.translationAlpha, 0.0001f)
            clock.sendFrame(475_000_000L)
            runCurrent()
            assertTrue(look.alpha > intermediateAlpha && look.alpha < active.alpha)
            assertTrue(look.translationAlpha > intermediateTranslationAlpha && look.translationAlpha < active.translationAlpha)
            clock.sendFrame(850_000_000L)
            runCurrent()
            assertEquals(active.alpha, look.alpha)
            assertEquals(active.translationAlpha, look.translationAlpha)
            clock.sendFrame(900_000_000L)
            runCurrent()
            assertEquals(active.scale, look.scale)
            activation.join()
        }

    @Test
    fun sampledPlaybackActivatesAtThePredictiveOverlapDeadlineBetweenPollsAtEveryRateAndOffset() {
        for (speed in listOf(0.5f, 1f, 2f)) {
            for (offset in listOf(-200, 0, 200)) {
                runTest {
                    val lyrics =
                        listOf(
                            LyricsEntry(time = 1_000L, text = "First", endTime = 4_000L, agent = "v1"),
                            LyricsEntry(time = 2_053L, text = "Second", endTime = 3_000L, agent = "v2"),
                        )
                    val items = buildLyricsTimelineItems(buildLyricsListItems(lyrics, null, emptyList()), lyrics)
                    val engine = LyricsSyncEngine(items, items.first().listIndex)
                    val samples = MutableStateFlow(1_000L)
                    var snapshot: LyricsSyncSnapshot? = null
                    backgroundScope.launch {
                        deadlineLyricsPositions(
                            positionFlow = samples,
                            deadlines = buildLineLyricsDeadlines(items, lyrics),
                            initial = LyricsDeadlinePosition(samples.value),
                            isPlaying = true,
                            isCasting = false,
                            playbackSpeed = speed,
                            timingOffsetMs = offset,
                            visibility = MutableStateFlow(true),
                            timeSource = testScheduler.timeSource,
                        ).collect {
                            snapshot =
                                engine.update(
                                    LyricsUtils.lyricsPositionForOffset(it.positionMs, offset),
                                    shouldFollowPlayback = true,
                                    isWordByWord = false,
                                    seekGeneration = it.seekGeneration,
                                )
                        }
                    }
                    val boundaryMs = ceil((2_053L - 410L - offset - 1_000L) / speed.toDouble()).toLong()
                    var elapsedMs = 0L
                    while (elapsedMs + 100L < boundaryMs) {
                        advanceTimeBy(100L)
                        elapsedMs += 100L
                        samples.value = 1_000L + (elapsedMs * speed).toLong()
                        runCurrent()
                    }
                    advanceTimeBy(boundaryMs - elapsedMs - 1L)
                    runCurrent()
                    val beforeBoundary = assertNotNull(snapshot)
                    assertTrue(1 !in beforeBoundary.activeLineIndices)
                    advanceTimeBy(1L)
                    runCurrent()
                    val afterBoundary = assertNotNull(snapshot)
                    assertEquals(listOf(0, 1), afterBoundary.activeLineIndices)
                    assertEquals(0, afterBoundary.primaryLineIndex)
                }
            }
        }
    }

    @Test
    fun sourceTimingPublishesEveryOverlapBoundaryBetweenPollsAtEveryRateAndOffset() {
        for (speed in listOf(0.5f, 1f, 2f)) {
            for (offset in listOf(-200, 0, 200)) {
                runTest {
                    val lyrics =
                        listOf(
                            LyricsEntry(1_003L, "First", endTime = 9_009L, agent = "v1"),
                            LyricsEntry(2_053L, "Second", endTime = 3_057L, agent = "v1"),
                        )
                    val items = buildLyricsTimelineItems(buildLyricsListItems(lyrics, activeByRealTiming = true), lyrics)
                    val engine = LyricsSyncEngine(items, 0, activeByRealTiming = true)
                    val samples = MutableStateFlow(500L)
                    var active = emptyList<Int>()
                    backgroundScope.launch {
                        deadlineLyricsPositions(
                            positionFlow = samples,
                            deadlines = buildLineLyricsDeadlines(items, lyrics),
                            initial = LyricsDeadlinePosition(samples.value),
                            isPlaying = true,
                            isCasting = false,
                            playbackSpeed = speed,
                            timingOffsetMs = offset,
                            visibility = MutableStateFlow(true),
                            timeSource = testScheduler.timeSource,
                        ).collect {
                            active =
                                engine
                                    .update(
                                        LyricsUtils.lyricsPositionForOffset(it.positionMs, offset),
                                        shouldFollowPlayback = true,
                                        isWordByWord = false,
                                        seekGeneration = it.seekGeneration,
                                    ).activeLineIndices
                        }
                    }
                    backgroundScope.launch {
                        while (true) {
                            delay(100L)
                            samples.value = 500L + (testScheduler.currentTime * speed).toLong()
                        }
                    }
                    runCurrent()
                    var previous = emptyList<Int>()
                    for ((deadline, expected) in listOf(
                        1_003L to listOf(0),
                        2_053L to listOf(0, 1),
                        3_057L to listOf(0),
                        9_009L to emptyList(),
                    )) {
                        val boundary = ceil((deadline - offset - 500L) / speed.toDouble()).toLong()
                        advanceTimeBy(boundary - testScheduler.currentTime - 1L)
                        runCurrent()
                        assertEquals(previous, active, "before $deadline, speed=$speed offset=$offset")
                        advanceTimeBy(1L)
                        runCurrent()
                        assertEquals(expected, active, "at $deadline, speed=$speed offset=$offset")
                        previous = expected
                    }
                }
            }
        }
    }
}
