package com.metrolist.shared.ui.components.lyrics

import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.parseTtmlToMlx
import com.metrolist.shared.model.LyricsEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsLineTimingTest {
    @Test
    fun predictiveOverlapsKeepTheLongerLineActiveWhenTheNextLineEndsFirst() {
        for (wordSync in listOf(false, true)) {
            val engine = engine(listOf(line(1000, 9000), line(3000, 5000), line(8000, 10000)))
            assertActive(engine, 1000, wordSync, 0)
            assertActive(engine, 2400, wordSync, 0)
            assertActive(engine, 2999, wordSync, 0, 1)
            assertActive(engine, 3000, wordSync, 0, 1)
            assertActive(engine, 4999, wordSync, 0, 1)
            assertActive(engine, 6500, wordSync, 0, 1)
            assertActive(engine, 8000, wordSync, 0, 2)
            assertActive(engine, 10800, wordSync)
            assertActive(engine, 11000, wordSync)
            assertActive(engine, 3000, wordSync, 0, 1)
        }
    }

    @Test
    fun touchingLinesSwitchAtThePredictiveBoundary() {
        val engine = engine(listOf(line(1000, 3000), line(3000, 5000)))
        assertActive(engine, 2339, false, 0)
        assertActive(engine, 2340, false, 1)
        assertActive(engine, 2999, false, 1)
        assertActive(engine, 3000, false, 1)
    }

    @Test
    fun aGapStopsHighlightingAfterTheExistingAnimationTail() {
        val engine = engine(listOf(line(1000, 2000), line(6000, 7000)))
        assertActive(engine, 1999, false, 0)
        assertActive(engine, 3000, false)
        assertActive(engine, 3500, false)
        assertActive(engine, 5339, false)
        assertActive(engine, 5340, false, 1)
        assertActive(engine, 5999, false, 1)
        assertActive(engine, 6000, false, 1)
    }

    @Test
    fun predictiveDuetsRetainTheGroupAndAnchorTheFirstContiguousRow() {
        val engine = engine(listOf(line(1000, 9000, "v1"), line(3000, 5000, "v2"), line(7000, 8000, "v2")))
        assertActive(engine, 1000, false, 0)
        assertActive(engine, 3000, false, 0, 1)
        assertEquals(0, engine.update(3500, true, false).primaryLineIndex)
        assertActive(engine, 5000, false, 0)
        assertActive(engine, 7000, false, 0, 2)
        assertActive(engine, 8000, false, 0, 2)
    }

    @Test
    fun skippedUpdatesRetainTheDuetGroupOnlyThroughTheAnimationTail() {
        val engine = engine(listOf(line(1000, 5000, "v1"), line(3000, 6000, "v2")))
        assertActive(engine, 4000, false, 0, 1)
        assertActive(engine, 6500, false, 0, 1)
        assertActive(engine, 6800, false)
    }

    @Test
    fun aLongDuetLineSurvivesSeveralShorterLines() {
        val engine = engine(listOf(line(1000, 12000, "v1"), line(2000, 3000, "v2"), line(4000, 5000, "v2"), line(6000, 7000, "v2")))
        assertActive(engine, 6500, false, 0, 3)
        assertActive(engine, 7500, false, 0, 3)
        assertActive(engine, 9500, false, 0, 3)
        assertActive(engine, 12800, false)
    }

    @Test
    fun markedBackgroundLinesCanOverlapTheLead() {
        val engine =
            engine(
                listOf(
                    line(1000, 5000),
                    line(2000, 6000).copy(isBackground = true),
                    line(3000, 7000),
                    line(4000, 5000).copy(isBackground = true),
                ),
            )
        assertActive(engine, 2500, false, 0, 1)
        assertActive(engine, 3000, false, 0, 1, 2)
        assertActive(engine, 4000, false, 0, 1, 2, 3)
        assertActive(engine, 5000, false, 1, 2)
        assertActive(engine, 6000, false, 1, 2)
        assertActive(engine, 7500, false, 1, 2)
        assertActive(engine, 8000, false)
    }

    @Test
    fun mlxWordAndBackgroundTimingStayOnTheirSourceTimeline() {
        val lines =
            LyricsUtils.parseLyrics(
                """
                [lyrics]
                [00:01.000~00:06.000]<00:01.000>Lead<00:05.000> [bg:<00:02.000>Echo<00:06.000>]
                [00:03.000~00:07.000]<00:03.000>Next<00:07.000>
                """.trimIndent(),
            )
        val before = lines.map { it.words }
        val timeline = timeline(lines)
        assertActive(LyricsSyncEngine(timeline, 0), 3500, true, 0, 1)
        assertActive(LyricsSyncEngine(timeline, 0), 6000, true, 1)
        assertEquals(before, lines.map { it.words })
        assertEquals(5.0, lines[0].words!!.first().endTime)
    }

    @Test
    fun inlineBackgroundVocalsRetainTheEarlierRowThroughTheGroupAnimationTail() {
        val lines =
            LyricsUtils.parseLyrics(
                """
                [lyrics]
                [00:01.000~00:12.000]<00:01.000>Lead<00:03.000> [bg:<00:10.000>Echo<00:12.000>]
                [00:04.000~00:13.000]<00:04.000>Next<00:13.000>
                """.trimIndent(),
            )
        val engine = engine(lines)
        assertActive(engine, 4500, true, 0, 1)
        assertActive(engine, 10000, true, 0, 1)
        assertActive(engine, 12000, true, 0, 1)
        assertActive(engine, 13500, true, 0, 1)
        assertActive(engine, 13800, true)
    }

    @Test
    fun predictiveTtmlOverlapsPreserveTheSourceWordTimings() {
        val lines =
            LyricsUtils.parseLyrics(
                parseTtmlToMlx(
                    """
                    <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                      <head><metadata><ttm:agent xml:id="a" type="person"/><ttm:agent xml:id="b" type="person"/></metadata></head>
                      <body><div>
                        <p begin="1s" end="6s" ttm:agent="a"><span begin="1s" end="6s">First</span></p>
                        <p begin="3s" end="5s" ttm:agent="a"><span begin="3s" end="5s">Next</span></p>
                        <p begin="4s" end="7s" ttm:agent="b"><span begin="4s" end="7s">Duet</span></p>
                      </div></body>
                    </tt>
                    """.trimIndent(),
                    "Song",
                    "Artist",
                    "Test",
                ),
            )
        val engine = engine(lines)
        assertActive(engine, 3000, true, 0, 1)
        assertActive(engine, 4000, true, 0, 1, 2)
        assertActive(engine, 5000, true, 0, 1, 2)
        assertEquals(6.0, lines[0].words!!.single().endTime)
        assertEquals(6000, timeline(lines)[0].sourceEndTimeMs)
    }

    @Test
    fun gapIndicatorsWaitUntilEveryActiveVocalStreamEnds() {
        val duet = listOf(line(1000, 30000, "v1"), line(2000, 3000, "v2"), line(12000, 14000, "v2"))
        assertTrue(buildLyricsListItems(duet).none { it is LyricsListItem.Indicator })
        val overlappingLead = listOf(line(1000, 9000), line(3000, 5000), line(20000, 22000))
        val gap = buildLyricsListItems(overlappingLead).filterIsInstance<LyricsListItem.Indicator>().single()
        assertEquals(9000L + LYRICS_GAP_START_LAG_MS, gap.gapStartMs)
        assertEquals(20000L - LYRICS_GAP_END_LEAD_MS, gap.gapEndMs)
    }

    @Test
    fun lineDeadlinesIncludeTheExactStartAndUnclippedEnd() {
        val lines = listOf(line(1000, 9000), line(3000, 5000))
        val timeline = timeline(lines)
        assertEquals(9000, timeline[0].endTimeMs)
        val deadlines = buildLineLyricsDeadlines(timeline, lines)
        assertTrue(1000L in deadlines)
        assertTrue(3000L in deadlines)
        assertTrue(9000L in deadlines)
    }

    @Test
    fun resetAndSeekUsePredictiveActivationForTheUpcomingLine() {
        for (wordSync in listOf(false, true)) {
            val engine = engine(listOf(line(1000, 2000), line(6000, 7000)))
            val initial = engine.update(5999, true, wordSync, seekGeneration = 0)
            assertEquals(listOf(1), initial.activeLineIndices)
            assertEquals(1, initial.primaryLineIndex)
            assertActive(engine, 6000, wordSync, 1)

            val backward = engine.update(999, true, wordSync, seekGeneration = 1)
            assertTrue(backward.isSeek)
            assertEquals(listOf(0), backward.activeLineIndices)
            assertEquals(0, backward.primaryLineIndex)
            assertEquals(listOf(0), engine.update(1000, true, wordSync, seekGeneration = 1).activeLineIndices)

            val forward = engine.update(5999, true, wordSync, seekGeneration = 2)
            assertTrue(forward.isSeek)
            assertEquals(listOf(1), forward.activeLineIndices)
            assertEquals(1, forward.primaryLineIndex)
            assertEquals(listOf(1), engine.update(6000, true, wordSync, seekGeneration = 2).activeLineIndices)
        }
    }

    @Test
    fun scrollingAndHighlightingFollowTheUpcomingLineThroughAGap() {
        val engine = engine(listOf(line(1000, 2000), line(6000, 7000)))
        assertActive(engine, 1000, false, 0)
        assertActive(engine, 3500, false)
        val approaching = engine.update(5800, true, false, seekGeneration = 0)
        assertEquals(listOf(1), approaching.activeLineIndices)
        assertEquals(1, approaching.primaryLineIndex)
        assertEquals(1, approaching.playbackListIndex)
        assertActive(engine, 6000, false, 1)
    }

    @Test
    fun predictiveHighlightingAndScrollingAdvanceTogetherBeforeTheCurrentLineEnds() {
        val engine = engine(listOf(line(1000, 4000), line(4000, 7000)))
        assertActive(engine, 1000, false, 0)
        assertActive(engine, 3339, false, 0)
        assertActive(engine, 3500, false, 1)
        val approaching = engine.update(3850, true, false, seekGeneration = 0)
        assertEquals(listOf(1), approaching.activeLineIndices)
        assertEquals(1, approaching.primaryLineIndex)
        assertEquals(1, approaching.playbackListIndex)
        assertActive(engine, 4000, false, 1)
    }

    @Test
    fun sourceTimingHighlightsSameSingerOverlapsUntilEachSourceEnd() {
        val lines = listOf(line(1000, 9000, "v1"), line(3000, 5000, "v1"))
        val engine = LyricsSyncEngine(timeline(lines), 0, activeByRealTiming = true)

        assertActive(engine, 999, false)
        assertActive(engine, 1000, false, 0)
        assertActive(engine, 2999, false, 0)
        assertActive(engine, 3000, false, 0, 1)
        assertActive(engine, 4999, false, 0, 1)
        assertActive(engine, 5000, false, 0)
        assertActive(engine, 8999, false, 0)
        assertActive(engine, 9000, false)
        assertActive(engine(lines), 3000, false, 0, 1)
    }

    @Test
    fun sourceTimingGapIndicatorsWaitForEverySourceEnd() {
        val lines = listOf(line(1000, 30000), line(2000, 3000), line(12000, 14000))
        assertTrue(buildLyricsListItems(lines).none { it is LyricsListItem.Indicator })
        assertTrue(buildLyricsListItems(lines, activeByRealTiming = true).none { it is LyricsListItem.Indicator })
        val items = buildLyricsTimelineItems(buildLyricsListItems(lines), lines)
        val engine = LyricsSyncEngine(items, 0, activeByRealTiming = true)
        assertActive(engine, 15000, false, 0)
        assertActive(engine, 30000, false)
    }

    @Test
    fun sourceTimingKeepsTheRowHighlightedBetweenItsMainAndBackgroundVocals() {
        val lines =
            LyricsUtils.parseLyrics(
                """
                [lyrics]
                [00:01.000~00:12.000]<00:01.000>Lead<00:03.000> [bg:<00:10.000>Echo<00:12.000>]
                [00:04.000~00:13.000]<00:04.000>Next<00:13.000>
                """.trimIndent(),
            )
        val engine = LyricsSyncEngine(timeline(lines), 0, activeByRealTiming = true)
        assertActive(engine, 4500, true, 0, 1)
        assertActive(engine, 12000, true, 1)
        assertActive(engine, 13000, true)
    }

    @Test
    fun contiguousOverlapsUseTheAnchorForTheirTimingModeOnOpenSeekAndPlayback() {
        val lines = listOf(line(1000, 9000, "v1"), line(2000, 8000, "v2"), line(3000, 7000, "v3"), line(4000, 6000, "v4"))
        for (sourceTiming in listOf(false, true)) {
            val engine = LyricsSyncEngine(timeline(lines), 0, activeByRealTiming = sourceTiming)
            for ((time, generation) in listOf(4500L to 0, 4600L to 0, 4700L to 1)) {
                val snapshot = engine.update(time, true, false, seekGeneration = generation)
                assertEquals(listOf(0, 1, 2, 3), snapshot.activeLineIndices)
                val expectedAnchor =
                    if (sourceTiming) {
                        0
                    } else if (time == 4600L) {
                        1
                    } else {
                        3
                    }
                assertEquals(expectedAnchor, snapshot.primaryLineIndex)
                assertEquals(expectedAnchor, snapshot.playbackListIndex)
                assertEquals(3, snapshot.lastActiveGroupEndListIndex)
            }
        }
    }

    @Test
    fun predictiveLookaheadChangesTheHighlightAndScrollTargetAtItsBoundary() {
        val engine = engine(listOf(line(1000, 4000), line(4000, 7000)))
        for ((time, target) in listOf(1000L to 0, 3339L to 0, 3340L to 1, 3999L to 1)) {
            val snapshot = engine.update(time, true, false, seekGeneration = 0)
            assertEquals(listOf(target), snapshot.activeLineIndices, "at $time ms")
            assertEquals(target, snapshot.primaryLineIndex)
            assertEquals(target, snapshot.playbackListIndex)
        }
        assertActive(engine, 4000, false, 1)
    }

    @Test
    fun predictiveHighlightingAdvancesDuringTheOutgoingHold() {
        val engine = engine(listOf(line(1000, 2000), line(2600, 5000)))
        assertActive(engine, 1939, false, 0)
        for (time in listOf(1940L, 1999L, 2000L, 2100L, 2599L)) {
            val snapshot = engine.update(time, true, false, seekGeneration = 0)
            assertEquals(listOf(1), snapshot.activeLineIndices)
            assertEquals(1, snapshot.playbackListIndex)
        }
        assertActive(engine, 2600, false, 1)
    }

    @Test
    fun scrollAnticipatesTheTopOfTheUpcomingOverlapGroup() {
        val lines = listOf(line(1000, 9000, "v1"), line(3000, 5000, "v2"), line(4000, 7000, "v3"))
        for (sourceTiming in listOf(false, true)) {
            val engine = LyricsSyncEngine(timeline(lines), 0, activeByRealTiming = sourceTiming)
            for ((time, expected) in listOf(
                1000L to listOf(0),
                2340L to listOf(0),
                3000L to listOf(0, 1),
                3340L to listOf(0, 1),
                4000L to listOf(0, 1, 2),
            )) {
                val snapshot = engine.update(time, true, false, seekGeneration = 0)
                assertEquals(expected, snapshot.activeLineIndices)
                assertEquals(0, snapshot.playbackListIndex)
            }
        }
    }

    private fun line(
        start: Long,
        end: Long,
        agent: String? = null,
    ) = LyricsEntry(start, "Line", agent = agent, endTime = end)

    private fun timeline(lines: List<LyricsEntry>) =
        buildLyricsTimelineItems(lines.mapIndexed { index, entry -> LyricsListItem.Line(index, entry) }, lines)

    private fun engine(lines: List<LyricsEntry>) = LyricsSyncEngine(timeline(lines), 0)

    private fun assertActive(
        engine: LyricsSyncEngine,
        time: Long,
        words: Boolean,
        vararg expected: Int,
    ) {
        val snapshot = engine.update(time, true, words, seekGeneration = 0)
        assertEquals(expected.toList(), snapshot.activeLineIndices, "at $time ms")
        if (expected.isNotEmpty()) assertTrue(snapshot.primaryLineIndex in snapshot.activeLineIndices)
    }
}
