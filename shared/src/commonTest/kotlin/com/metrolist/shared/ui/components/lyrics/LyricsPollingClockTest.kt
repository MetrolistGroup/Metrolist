package com.metrolist.shared.ui.components.lyrics

import com.metrolist.shared.model.LyricsEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsPollingClockTest {
    @Test
    fun polledClocksRetainOverlappingDuetRowsAcrossEveryBoundary() {
        for (pollMs in listOf(50L, 100L, 200L)) {
            for (speed in listOf(0.5f, 1f, 2f)) {
                for (words in listOf(false, true)) {
                    runTest {
                        val lines =
                            listOf(
                                LyricsEntry(1_000L, "Long vocal", endTime = 7_000L, agent = "v1"),
                                LyricsEntry(2_000L, "Short vocal", endTime = 3_000L, agent = "v2"),
                                LyricsEntry(4_000L, "Next vocal", endTime = 5_000L, agent = "v2"),
                            )
                        val items = buildLyricsTimelineItems(buildLyricsListItems(lines), lines)
                        val engine = LyricsSyncEngine(items, 0)
                        val samples = MutableStateFlow(1_000L)
                        val nativePosition = { 1_000L + (testScheduler.currentTime * speed).toLong() }
                        var active = emptyList<Int>()
                        backgroundScope.launch {
                            val positions =
                                if (words) {
                                    smoothLyricsPositions(
                                        samples,
                                        true,
                                        speed,
                                        MutableStateFlow(true),
                                        positionReader = nativePosition,
                                        timeSource = testScheduler.timeSource,
                                        tickOrigin = testScheduler.timeSource.markNow(),
                                    )
                                } else {
                                    deadlineLyricsPositions(
                                        samples,
                                        buildLineLyricsDeadlines(items, lines),
                                        LyricsDeadlinePosition(1_000L),
                                        true,
                                        false,
                                        speed,
                                        0,
                                        MutableStateFlow(true),
                                        timeSource = testScheduler.timeSource,
                                        positionReader = nativePosition,
                                    ).map { it.positionMs }
                                }
                            positions.collect { active = engine.update(it, true, words, seekGeneration = 0).activeLineIndices }
                        }
                        backgroundScope.launch {
                            while (true) {
                                delay(pollMs)
                                samples.value = nativePosition()
                            }
                        }
                        for ((mediaTime, expected) in listOf(
                            1_100L to listOf(0),
                            2_000L to listOf(0, 1),
                            3_000L to listOf(0, 1),
                            4_000L to listOf(0, 2),
                            5_000L to listOf(0, 2),
                        )) {
                            val afterFirstFrame = ceil((mediaTime - 1_000L) / speed.toDouble()).toLong() + 15L
                            advanceTimeBy(afterFirstFrame - testScheduler.currentTime)
                            runCurrent()
                            assertEquals(expected, active, "poll=$pollMs speed=$speed words=$words at $mediaTime")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun lineAndCaptionDeadlinesFollowBufferedRateChangesWithinOneFrame() {
        for (oldSpeed in listOf(0.5f, 1f, 2f)) {
            for (newSpeed in listOf(0.5f, 1f, 2f)) {
                runTest {
                    val samples = MutableStateFlow(1_000L)
                    val nativePosition = {
                        val wall = testScheduler.currentTime
                        1_000L + (minOf(wall, 150L) * oldSpeed + (wall - 150L).coerceAtLeast(0L) * newSpeed).toLong()
                    }
                    var published = LyricsDeadlinePosition(1_000L)
                    val deadlines = listOf(1_096L, 1_253L, 1_500L)
                    backgroundScope.launch {
                        deadlineLyricsPositions(
                            samples,
                            deadlines,
                            published,
                            true,
                            false,
                            newSpeed,
                            0,
                            MutableStateFlow(true),
                            timeSource = testScheduler.timeSource,
                            positionReader = nativePosition,
                        ).collect { published = it }
                    }
                    backgroundScope.launch {
                        while (true) {
                            delay(100L)
                            samples.value = nativePosition()
                        }
                    }
                    val crossedAt = mutableMapOf<Long, Long>()
                    repeat(1_100) {
                        advanceTimeBy(1L)
                        runCurrent()
                        for (deadline in deadlines) {
                            val now = testScheduler.currentTime
                            if (nativePosition() >= deadline) crossedAt.getOrPut(deadline) { now }
                            if (published.positionMs >= deadline) {
                                val nativeCrossing = crossedAt[deadline]
                                assertTrue(nativeCrossing != null, "Early deadline $deadline at $now, rate=$oldSpeed to $newSpeed")
                            } else if (deadline in crossedAt) {
                                assertTrue(
                                    now - crossedAt.getValue(deadline) < 16L,
                                    "Late deadline $deadline at $now, rate=$oldSpeed to $newSpeed",
                                )
                            }
                        }
                    }
                    assertEquals(deadlines.toSet(), crossedAt.keys)
                }
            }
        }
    }

    @Test
    fun quantizedNativeOutputStaysMonotonicAndWithinOneFrameAtEveryPollCadence() {
        for (pollMs in listOf(50L, 100L, 200L)) {
            for (speed in listOf(0.5f, 1f, 2f)) {
                runTest {
                    val samples = MutableStateFlow(45_000L)
                    val nativePosition = { 45_000L + (testScheduler.currentTime / 10L * 10L * speed).toLong() }
                    var previous = samples.value
                    var position = previous
                    backgroundScope.launch {
                        smoothLyricsPositions(
                            samples,
                            true,
                            speed,
                            MutableStateFlow(true),
                            positionReader = nativePosition,
                            timeSource = testScheduler.timeSource,
                            tickOrigin = testScheduler.timeSource.markNow(),
                        ).collect {
                            assertTrue(it >= previous)
                            previous = it
                            position = it
                        }
                    }
                    backgroundScope.launch {
                        while (true) {
                            delay(pollMs)
                            samples.value = nativePosition()
                        }
                    }
                    repeat(100) {
                        advanceTimeBy(16L)
                        runCurrent()
                        val ideal = 45_000L + (testScheduler.currentTime * speed).toLong()
                        assertTrue((ideal - position) / speed < 16L, "poll=$pollMs speed=$speed at ${testScheduler.currentTime}")
                    }
                }
            }
        }
    }

    @Test
    fun liveWordClockFollowsBufferedRateChangesWithoutPollJumpsOrAFilter() {
        for (pollMs in listOf(50L, 100L, 200L)) {
            for (oldSpeed in listOf(0.5f, 1f, 2f)) {
                for (newSpeed in listOf(0.5f, 1f, 2f)) {
                    runTest {
                        val samples = MutableStateFlow(45_000L)
                        val nativePosition = {
                            val wall = testScheduler.currentTime
                            45_000L + (minOf(wall, 150L) * oldSpeed + (wall - 150L).coerceAtLeast(0L) * newSpeed).toLong()
                        }
                        var previous = 45_000L
                        var position = previous
                        backgroundScope.launch {
                            smoothLyricsPositions(
                                samples,
                                true,
                                newSpeed,
                                MutableStateFlow(true),
                                positionReader = nativePosition,
                                timeSource = testScheduler.timeSource,
                                tickOrigin = testScheduler.timeSource.markNow(),
                            ).collect {
                                assertTrue(it >= previous, "poll=$pollMs rate=$oldSpeed to $newSpeed at ${testScheduler.currentTime}")
                                previous = it
                                position = it
                            }
                        }
                        backgroundScope.launch {
                            while (true) {
                                delay(pollMs)
                                samples.value = nativePosition()
                            }
                        }
                        repeat(60) {
                            advanceTimeBy(16L)
                            runCurrent()
                            assertEquals(
                                nativePosition(),
                                position,
                                "poll=$pollMs rate=$oldSpeed to $newSpeed at ${testScheduler.currentTime}",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun wordClockAppliesNativeSeeksAndTrackChangesWithoutWaitingForInterpolation() =
        runTest {
            val samples = MutableStateFlow(45_000L)
            var native = samples.value
            var position = native
            val playing =
                backgroundScope.launch {
                    smoothLyricsPositions(
                        samples,
                        true,
                        1f,
                        MutableStateFlow(true),
                        positionReader = { native },
                        timeSource = testScheduler.timeSource,
                        tickOrigin = testScheduler.timeSource.markNow(),
                    ).collect { position = it }
                }
            for (target in listOf(45_100L, 45_060L, 20_000L, 58_000L)) {
                native = target
                samples.value = target
                advanceTimeBy(16L)
                runCurrent()
                assertEquals(target, position)
            }
            playing.cancelAndJoin()
            samples.value = 0L
            native = 0L
            backgroundScope.launch {
                smoothLyricsPositions(
                    samples,
                    true,
                    1f,
                    MutableStateFlow(true),
                    positionReader = { native },
                    timeSource = testScheduler.timeSource,
                    tickOrigin = testScheduler.timeSource.markNow(),
                ).collect { position = it }
            }
            advanceTimeBy(16L)
            runCurrent()
            assertEquals(0L, position)
        }

    @Test
    fun pausingBetweenPollsDoesNotRewind96MsAndPausedSeeksStillApply() =
        runTest {
            val samples = MutableStateFlow(45_000L)
            var nativePosition = 45_096L
            var position = 45_096L
            advanceTimeBy(96L)
            val paused =
                backgroundScope.launch {
                    smoothLyricsPositions(
                        samples,
                        false,
                        1f,
                        MutableStateFlow(true),
                        positionReader = { nativePosition },
                        timeSource = testScheduler.timeSource,
                        tickOrigin = testScheduler.timeSource.markNow(),
                    ).collect { position = it }
                }
            runCurrent()
            assertEquals(45_096L, position)
            advanceTimeBy(200L)
            runCurrent()
            assertEquals(45_096L, position)
            nativePosition = 20_000L
            samples.value = nativePosition
            runCurrent()
            assertEquals(20_000L, position)
            paused.cancelAndJoin()

            val resumedAt = testScheduler.currentTime
            backgroundScope.launch {
                smoothLyricsPositions(
                    samples,
                    true,
                    1f,
                    MutableStateFlow(true),
                    positionReader = { 20_000L + testScheduler.currentTime - resumedAt },
                    timeSource = testScheduler.timeSource,
                    tickOrigin = testScheduler.timeSource.markNow(),
                ).collect { position = it }
            }
            advanceTimeBy(49L)
            runCurrent()
            assertEquals(20_048L, position)
        }

    @Test
    fun delayedPlatformPollsDoNotDelayLineOrCaptionBoundaries() {
        for (platform in listOf("Android", "desktop", "iOS")) {
            for (speed in listOf(0.5f, 1f, 2f)) {
                for (caption in listOf(false, true)) {
                    runTest {
                        val pollMs =
                            when (platform) {
                                "desktop" -> 50L
                                "iOS" -> ceil(100.0 / speed).toLong()
                                else -> 100L
                            }
                        val lines =
                            listOf(
                                LyricsEntry(1_000L, "First", endTime = 4_000L, agent = "v1"),
                                LyricsEntry(2_053L, "Second", endTime = 3_000L, agent = "v2"),
                            )
                        val items = buildLyricsTimelineItems(buildLyricsListItems(lines), lines)
                        val deadlines = if (caption) buildCaptionLyricsDeadlines(lines, null) else buildLineLyricsDeadlines(items, lines)
                        val samples = MutableStateFlow(1_000L)
                        val livePosition = { 1_000L + (testScheduler.currentTime * speed).toLong() }
                        var published = LyricsDeadlinePosition(1_000L)
                        advanceTimeBy(40L)
                        backgroundScope.launch {
                            deadlineLyricsPositions(
                                samples,
                                deadlines,
                                published,
                                true,
                                false,
                                speed,
                                0,
                                MutableStateFlow(true),
                                timeSource = testScheduler.timeSource,
                                positionReader = livePosition,
                            ).collect { published = it }
                        }
                        runCurrent()
                        backgroundScope.launch {
                            var captured = samples.value
                            while (true) {
                                delay(pollMs)
                                samples.value = captured
                                captured = livePosition()
                            }
                        }
                        val boundary = if (caption) 1_954L else 2_053L
                        val wallBoundary = ceil((boundary - 1_000L) / speed.toDouble()).toLong()
                        advanceTimeBy(wallBoundary - testScheduler.currentTime)
                        runCurrent()
                        val message = "$platform speed=$speed caption=$caption at $wallBoundary ms: $published"
                        assertTrue(published.positionMs >= boundary, message)
                        assertTrue(abs(published.positionMs - livePosition()) / speed < 16L, message)
                        assertEquals(0, published.seekGeneration, message)
                    }
                }
            }
        }
    }
}
