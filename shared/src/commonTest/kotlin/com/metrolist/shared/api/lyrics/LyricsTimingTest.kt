package com.metrolist.shared.api.lyrics

import com.metrolist.shared.api.lyrics.LyricsUtils.lyricsPositionForOffset
import com.metrolist.shared.api.lyrics.LyricsUtils.lyricsSeekPositionForOffset
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.WordTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsTimingTest {
    private val lines = LyricsUtils.parseLyrics("[00:01.00]First\n[00:02.00]Second")

    @Test
    fun repeatedLineTimestampsAreUnsyncedEvenWithSongDurationEnds() {
        for (time in listOf(0L, 1_000L)) {
            val entries = listOf(LyricsEntry(time, "First", endTime = 179_999L), LyricsEntry(time, "Second", endTime = 179_999L))
            assertEquals(LyricsTimingKind.UNSYNCED, lyricsTimingKind(entries))
            assertEquals(false, LyricsUtils.hasPlaybackTiming(entries))
            assertEquals(LyricsTimingKind.UNSYNCED, lyricsTimingKind(listOf(LyricsEntry.HEAD_LYRICS_ENTRY) + entries))
        }
    }

    @Test
    fun repeatedLineStartsRetainRealWordTimingAndDistinctHarmonyCues() {
        val entries = listOf(LyricsEntry(0L, "First"), LyricsEntry(0L, "Second"))
        val words = entries.mapIndexed { index, line -> line.copy(words = listOf(WordTimestamp(line.text, index.toDouble(), index + 1.0))) }
        assertEquals(LyricsTimingKind.WORD_SYNCED, lyricsTimingKind(words))
        assertEquals(true, LyricsUtils.hasPlaybackTiming(words))
        assertEquals(LyricsTimingKind.LINE_SYNCED, lyricsTimingKind(entries + LyricsEntry(2_000L, "Next")))
    }

    @Test
    fun finalLineEndLeavesRoomForTheTrailingGracePeriod() {
        val finalLine = lines.last().copy(endTime = null, words = null)
        assertEquals(Long.MAX_VALUE, LyricsUtils.getLineEndTime(finalLine, null) + 2_000L)
        assertEquals(
            7_000L,
            LyricsUtils.getLineEndTime(finalLine.copy(time = 0L), null, LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS) + 2_000L,
        )
        assertEquals(
            Long.MAX_VALUE,
            LyricsUtils.getLineEndTime(
                finalLine.copy(time = Long.MAX_VALUE - 1_000L),
                null,
                LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS,
            ) + 2_000L,
        )
        assertEquals(Long.MAX_VALUE, LyricsUtils.getLineEndTime(finalLine.copy(endTime = Long.MAX_VALUE), null) + 2_000L)
        assertEquals(5_000L, LyricsUtils.getLineEndTime(finalLine.copy(endTime = 3_000L), null) + 2_000L)
        assertEquals(lines[1].time, LyricsUtils.getLineEndTime(lines[0], lines[1]))
    }

    @Test
    fun dynamicLyricsRequireLineOrWordTiming() {
        assertEquals(false, LyricsUtils.hasPlaybackTiming(listOf(LyricsEntry(0L, "Unsynced chorus"))))
        assertEquals(true, LyricsUtils.hasPlaybackTiming(listOf(LyricsEntry(1_000L, "Synced line"))))
        assertEquals(true, LyricsUtils.hasPlaybackTiming(listOf(LyricsEntry(0L, "Synced opening", endTime = 2_000L))))
        assertEquals(false, LyricsUtils.hasPlaybackTiming(listOf(LyricsEntry(0L, "Untimed opening", endTime = 0L))))
        assertEquals(
            true,
            LyricsUtils.hasPlaybackTiming(
                listOf(
                    LyricsEntry(
                        time = 0L,
                        text = "Word-synced line",
                        words = listOf(WordTimestamp("Word", 0.1, 0.5)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun positiveOffsetAdvancesLineSelectionAndNegativeOffsetDelaysIt() {
        assertEquals(0, LyricsUtils.findCurrentLineIndex(lines, lyricsPositionForOffset(1_500, 0)))
        assertEquals(1, LyricsUtils.findCurrentLineIndex(lines, lyricsPositionForOffset(1_500, 500)))
        assertEquals(0, LyricsUtils.findCurrentLineIndex(lines, lyricsPositionForOffset(2_000, -500)))
        assertEquals(-1, LyricsUtils.findCurrentLineIndex(lines, lyricsPositionForOffset(0, -500)))
    }

    @Test
    fun seekingToALineInvertsThePlaybackOffset() {
        for (offset in listOf(-500, 0, 500)) {
            val seekPosition = lyricsSeekPositionForOffset(lines[1].time, offset)
            assertEquals(2_000L - offset, seekPosition)
            val lyricsPosition = lyricsPositionForOffset(seekPosition, offset)
            assertEquals(lines[1].time, lyricsPosition)
            assertEquals(1, LyricsUtils.findCurrentLineIndex(lines, lyricsPosition))
        }
    }

    @Test
    fun globalOffsetCombinesWithSongAdjustmentsForHighlightingAndSeeking() {
        for (globalOffset in listOf(-500, 0, 500)) {
            for (songOffset in listOf(-250, 0, 250)) {
                val offset = LyricsUtils.combinedLyricsOffsetMs(songOffset, globalOffset)
                val seekPosition = lyricsSeekPositionForOffset(lines[1].time, offset)
                assertEquals(2_000L - songOffset - globalOffset, seekPosition)
                assertEquals(lines[1].time, lyricsPositionForOffset(seekPosition, offset))
                assertEquals(1, LyricsUtils.findCurrentLineIndex(lines, lyricsPositionForOffset(seekPosition, offset)))
            }
        }
        assertEquals(0L, lyricsSeekPositionForOffset(lines[0].time, LyricsUtils.combinedLyricsOffsetMs(500, 1_000)))
    }

    @Test
    fun combinedOffsetsDoNotWrapAtIntegerLimits() {
        assertEquals(Int.MAX_VALUE, LyricsUtils.combinedLyricsOffsetMs(Int.MAX_VALUE, 50))
        assertEquals(Int.MIN_VALUE, LyricsUtils.combinedLyricsOffsetMs(Int.MIN_VALUE, -50))
        assertEquals(-1, LyricsUtils.combinedLyricsOffsetMs(Int.MAX_VALUE, Int.MIN_VALUE))
        assertEquals(250, LyricsUtils.combinedLyricsOffsetMs(250, 0))
        assertEquals(-500, LyricsUtils.combinedLyricsOffsetMs(0, -500))
    }

    @Test
    fun seekingAnAdvancedLineBeforeTrackStartClampsToZero() {
        assertEquals(0L, lyricsSeekPositionForOffset(lines[0].time, 1_500))
        assertEquals(0, LyricsUtils.findCurrentLineIndex(lines, lyricsPositionForOffset(0, 1_500)))
    }
}
