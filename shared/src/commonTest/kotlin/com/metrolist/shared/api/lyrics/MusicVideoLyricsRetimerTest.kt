package com.metrolist.shared.api.lyrics

import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.MlxMeta
import com.metrolist.shared.model.MlxSong
import com.metrolist.shared.model.WordTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicVideoLyricsRetimerTest {
    private fun cue(
        text: String,
        startMs: Long,
        durationMs: Long = 2000L,
    ) = YouTubeTranscriptCue(text = text, startMs = startMs, durationMs = durationMs)

    private fun lineEntry(
        time: Long,
        text: String,
        endTime: Long? = null,
        words: List<WordTimestamp>? = null,
        romanizedWords: List<WordTimestamp>? = null,
    ) = LyricsEntry(time = time, text = text, endTime = endTime, words = words, romanizedWords = romanizedWords)

    private fun word(
        text: String,
        startSec: Double,
        endSec: Double,
    ) = WordTimestamp(text = text, startTime = startSec, endTime = endSec, hasTrailingSpace = true)

    private fun doc(vararg lines: LyricsEntry) =
        MlxDocument(
            meta = MlxMeta(),
            song = MlxSong(),
            agents = emptyMap(),
            lines = lines.toList(),
        )

    @Test
    fun retimesSingleTimestampAtZeroWithoutTreatingPlainLyricsAsTimed() {
        val text = "never gonna give you up"
        val cues = listOf(cue(text, 10000L, 5000L))
        val timed = MlxParser.parse("[00:00.000]$text")
        val result = assertNotNull(MusicVideoLyricsRetimer.retimeLyrics(timed, cues))
        assertEquals(
            10000L,
            result.document.lines
                .single()
                .time,
        )
        assertNull(MusicVideoLyricsRetimer.retimeLyrics(MlxParser.parse(text), cues))
    }

    @Test
    fun positionMapPreservesPlainLyricsAndRetimesZeroTimestampLyrics() {
        val text = "never gonna give you up"
        val map = CounterpartPositionMap(listOf(CounterpartSegment(0, 10_000, 60_000)))
        val plain = MlxParser.parse(text)
        assertEquals(plain, MusicVideoLyricsRetimer.retimeLyricsWithPositionMap(plain, map))
        val timed = MlxParser.parse("[00:00.000]$text")
        assertEquals(
            10_000L,
            MusicVideoLyricsRetimer
                .retimeLyricsWithPositionMap(timed, map)
                .lines
                .single()
                .time,
        )
    }

    @Test
    fun retimesLineStartingAtZeroWithAnEndTime() {
        val lyrics = doc(lineEntry(0L, "never gonna give you up", endTime = 5000L))
        val result = assertNotNull(MusicVideoLyricsRetimer.retimeLyrics(lyrics, listOf(cue("never gonna give you up", 10000L, 5000L))))
        assertEquals(
            10000L,
            result.document.lines
                .single()
                .time,
        )
    }

    @Test
    fun retimeLyrics_wordSyncedAsr_preservesTimestampsAndDeduplicates() {
        val lyrics =
            doc(
                lineEntry(
                    time = 2000L,
                    text = "never gonna give you up",
                    endTime = 6000L,
                    words =
                        listOf(
                            word("never", 2.0, 2.4),
                            word("gonna", 2.4, 2.9),
                            word("give", 2.9, 3.3),
                            word("you", 3.3, 3.7),
                            word("up", 3.7, 4.2),
                        ),
                ),
            )

        val asrCue =
            YouTubeTranscriptCue(
                text = "Never gonna give you up",
                startMs = 12000L,
                durationMs = 4000L,
                words =
                    listOf(
                        YouTubeTranscriptWord("Never", 12000L),
                        YouTubeTranscriptWord("gonna", 15000L),
                        YouTubeTranscriptWord("give", 15200L),
                        YouTubeTranscriptWord("you", 17000L),
                        YouTubeTranscriptWord("up", 17200L),
                    ),
            )

        val result = MusicVideoLyricsRetimer.retimeLyrics(lyrics, listOf(asrCue))
        assertNotNull(result)
        assertEquals(1.0f, result.score, 0.01f)

        val retimedLine = result.document.lines.first()
        assertEquals(12000L, retimedLine.time)
        assertEquals(12.0, retimedLine.words?.get(0)?.startTime ?: 0.0, 0.01)
        assertEquals(12.4, retimedLine.words?.get(1)?.startTime ?: 0.0, 0.01)
        assertEquals(12.9, retimedLine.words?.get(2)?.startTime ?: 0.0, 0.01)
        assertEquals(13.3, retimedLine.words?.get(3)?.startTime ?: 0.0, 0.01)
        assertEquals(13.7, retimedLine.words?.get(4)?.startTime ?: 0.0, 0.01)
    }

    @Test
    fun retimeLyrics_keepsSyllablesInAWordInSyncDespiteChangingAnchorOffset() {
        val lyrics =
            doc(
                lineEntry(
                    time = 1000L,
                    text = "hello",
                    endTime = 2500L,
                    words =
                        listOf(
                            word("hel", 1.0, 1.5).copy(hasTrailingSpace = false),
                            word("lo", 2.0, 2.5),
                        ),
                ),
                lineEntry(
                    time = 3000L,
                    text = "there",
                    endTime = 3500L,
                    words = listOf(word("there", 3.0, 3.5)),
                ),
            )

        val result =
            MusicVideoLyricsRetimer.retimeLyrics(
                lyrics,
                listOf(cue("hello", 11_000L, 1_000L), cue("there", 15_000L, 1_000L)),
            )

        assertNotNull(result)
        val retimedSyllables =
            result.document.lines
                .first()
                .words
                .orEmpty()
        assertEquals(11.0, retimedSyllables[0].startTime, 0.01)
        assertEquals(12.0, retimedSyllables[1].startTime, 0.01)
        assertEquals(12.5, retimedSyllables[1].endTime, 0.01)
    }

    @Test
    fun retimeLyrics_twoBlocksDetectedForGap() {
        val lyrics =
            doc(
                lineEntry(1000L, "verse one", endTime = 5000L),
                lineEntry(6000L, "still verse", endTime = 10000L),
                lineEntry(30000L, "chorus now", endTime = 34000L),
                lineEntry(35000L, "outro line", endTime = 38000L),
            )

        val cues =
            listOf(
                cue("verse one", 11_000L, 4000L),
                cue("still verse", 16_000L, 4000L),
                cue("chorus now", 58_000L, 4000L),
                cue("outro line", 63_000L, 4000L),
            )

        val result = MusicVideoLyricsRetimer.retimeLyrics(lyrics, cues)
        assertNotNull(result)

        assertTrue(result.blocks.size >= 2, "Expected at least 2 offset blocks, got ${result.blocks.size}")

        val lines = result.document.lines
        assertTrue(lines[0].time in 10_000L..12_000L, "Verse line should be ~+10s, got ${lines[0].time}")
        assertTrue(lines[2].time in 56_000L..60_000L, "Chorus line should be ~+28s, got ${lines[2].time}")
    }

    @Test
    fun retimeLyrics_splitsOffsetChangesUnderThreeSeconds() {
        val starts = listOf(1_000L, 5_000L, 9_000L, 13_000L, 17_000L, 21_000L, 25_000L, 29_000L)
        val lines =
            starts.map { start ->
                lineEntry(
                    time = start,
                    text = "one two three",
                    endTime = start + 3_000L,
                    words =
                        listOf(
                            word("one", start / 1000.0, start / 1000.0 + 0.6),
                            word("two", start / 1000.0 + 1.0, start / 1000.0 + 1.6),
                            word("three", start / 1000.0 + 2.0, start / 1000.0 + 2.6),
                        ),
                )
            }
        val lyrics = doc(*lines.toTypedArray())
        val cues =
            starts.mapIndexed { index, start ->
                val offset = if (index < 4) 0L else 1_800L
                val videoStart = start + offset
                YouTubeTranscriptCue(
                    text = "one two three",
                    startMs = videoStart,
                    durationMs = 3_000L,
                    words =
                        listOf(
                            YouTubeTranscriptWord("one", videoStart, 600L),
                            YouTubeTranscriptWord("two", videoStart + 1_000L, 600L),
                            YouTubeTranscriptWord("three", videoStart + 2_000L, 600L),
                        ),
                )
            }

        val result = MusicVideoLyricsRetimer.retimeLyrics(lyrics, cues)

        assertNotNull(result)
        assertTrue(result.blocks.size >= 2, "Expected sub-three-second offset change to split into blocks")
        assertEquals(18_800L, result.document.lines[4].time)
    }

    @Test
    fun retimeLyrics_interpolatesTimingDriftWithinAnOffsetBlock() {
        val starts = listOf(1_000L, 9_000L, 17_000L, 25_000L)
        val phrases = listOf("first phrase", "second phrase", "third phrase", "fourth phrase")
        val lyrics =
            doc(
                *starts
                    .mapIndexed { index, start ->
                        val words = phrases[index].split(' ')
                        lineEntry(
                            time = start,
                            text = phrases[index],
                            endTime = start + 3_000L,
                            words =
                                words.mapIndexed { wordIndex, text ->
                                    val wordStart = start + wordIndex * 1_000L
                                    word(text, wordStart / 1000.0, wordStart / 1000.0 + 0.5)
                                },
                        )
                    }.toTypedArray(),
            )
        val cues =
            starts.mapIndexed { index, start ->
                val videoStart = start + index * 1_000L
                val words = phrases[index].split(' ')
                YouTubeTranscriptCue(
                    text = phrases[index],
                    startMs = videoStart,
                    durationMs = 2_000L,
                    words =
                        words.mapIndexed { wordIndex, text ->
                            YouTubeTranscriptWord(text, videoStart + wordIndex * 1_000L, 500L)
                        },
                )
            }

        val result = MusicVideoLyricsRetimer.retimeLyrics(lyrics, cues)

        assertNotNull(result)
        assertEquals(19_000L, result.document.lines[2].time)
        assertEquals(
            19.0,
            result.document.lines[2]
                .words
                ?.first()
                ?.startTime ?: 0.0,
            0.01,
        )
        assertEquals(
            20.125,
            result.document.lines[2]
                .words
                ?.last()
                ?.startTime ?: 0.0,
            0.01,
        )
    }

    @Test
    fun retimeLyrics_smoothsAnIsolatedAsrTimingOutlier() {
        val starts = listOf(1_000L, 5_000L, 9_000L, 13_000L, 17_000L)
        val lyrics = doc(*starts.map { lineEntry(it, "hello", endTime = it + 2_000L) }.toTypedArray())
        val cueOffsets = listOf(0L, 0L, 1_800L, 0L, 0L)
        val cues = starts.mapIndexed { index, start -> cue("hello", start + cueOffsets[index], 2_000L) }

        val result = MusicVideoLyricsRetimer.retimeLyrics(lyrics, cues)

        assertNotNull(result)
        assertEquals(starts, result.document.lines.map { it.time })
    }

    @Test
    fun retimeLyricsWithPositionMap_appliesExactSegmentMapping() {
        val lyrics =
            doc(
                lineEntry(
                    time = 5000L,
                    text = "first line",
                    endTime = 8000L,
                    words =
                        listOf(
                            word("first", 5.0, 6.0),
                            word("line", 6.5, 8.0),
                        ),
                ),
                lineEntry(
                    time = 9000L,
                    text = "boundary line",
                    endTime = 11000L,
                    words =
                        listOf(
                            word("boundary", 9.5, 10.5),
                            word("line", 10.5, 11.0),
                        ),
                    romanizedWords = listOf(word("romanized", 9.5, 10.5)),
                ),
                lineEntry(
                    time = 20000L,
                    text = "second line",
                    endTime = 23000L,
                    words =
                        listOf(
                            word("second", 20.0, 21.0),
                            word("line", 21.5, 23.0),
                        ),
                ),
            )

        val posMap =
            CounterpartPositionMap(
                segments =
                    listOf(
                        CounterpartSegment(
                            sourceStartMs = 0L,
                            targetStartMs = 15000L,
                            durationMs = 10000L,
                        ),
                        CounterpartSegment(
                            sourceStartMs = 10000L,
                            targetStartMs = 30000L,
                            durationMs = 20000L,
                        ),
                    ),
            )

        val retimed = MusicVideoLyricsRetimer.retimeLyricsWithPositionMap(lyrics, posMap)
        val lines = retimed.lines

        assertEquals(20000L, lines[0].time)
        assertEquals(23000L, lines[0].endTime)
        assertEquals(20.0, lines[0].words?.get(0)?.startTime ?: 0.0, 0.01)
        assertEquals(21.0, lines[0].words?.get(0)?.endTime ?: 0.0, 0.01)

        assertEquals(24000L, lines[1].time)
        assertEquals(31000L, lines[1].endTime)
        assertEquals(24.5, lines[1].words?.get(0)?.startTime ?: 0.0, 0.01)
        assertEquals(30.5, lines[1].words?.get(0)?.endTime ?: 0.0, 0.01)
        assertEquals(24.5, lines[1].romanizedWords?.get(0)?.startTime ?: 0.0, 0.01)
        assertEquals(30.5, lines[1].romanizedWords?.get(0)?.endTime ?: 0.0, 0.01)

        assertEquals(40000L, lines[2].time)
        assertEquals(43000L, lines[2].endTime)
        assertEquals(40.0, lines[2].words?.get(0)?.startTime ?: 0.0, 0.01)
        assertEquals(41.0, lines[2].words?.get(0)?.endTime ?: 0.0, 0.01)
    }

    @Test
    fun retimeLyrics_multiActiveConcurrentLinesRemainInSync() {
        val lyrics =
            doc(
                lineEntry(
                    time = 10000L,
                    text = "lead singer sings the chorus",
                    endTime = 16000L,
                    words =
                        listOf(
                            word("lead", 10.0, 11.0),
                            word("singer", 11.2, 12.5),
                            word("sings", 12.8, 13.8),
                            word("the", 14.0, 14.5),
                            word("chorus", 14.8, 16.0),
                        ),
                ),
                lineEntry(
                    time = 11000L,
                    text = "(backing harmony chorus)",
                    endTime = 15000L,
                    words =
                        listOf(
                            word("backing", 11.0, 12.0),
                            word("harmony", 12.2, 13.5),
                            word("chorus", 13.8, 15.0),
                        ),
                ),
            )

        val cues =
            listOf(
                cue("[Music]\nlead singer sings the chorus", 16000L, 6000L),
            )

        val result = MusicVideoLyricsRetimer.retimeLyrics(lyrics, cues)
        assertNotNull(result)

        val lines = result.document.lines
        assertEquals(2, lines.size)
        assertEquals(16000L, lines[0].time)
        assertEquals(22000L, lines[0].endTime)
        assertEquals(17000L, lines[1].time)
        assertEquals(21000L, lines[1].endTime)

        assertEquals(16.0, lines[0].words?.get(0)?.startTime ?: 0.0, 0.01)
        assertEquals(17.0, lines[1].words?.get(0)?.startTime ?: 0.0, 0.01)
    }
}
