package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.LyricsTimingKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LyricsSourceTimingTest {
    @Test
    fun plainLyprioLinesDoNotGainTimingFromTheTtmlBodyDuration() {
        // Apple Music and BetterLyrics return timing="None" for r-906's Candy.
        val raw =
            """{"version":2,"provider":"LYPRIO","subprovider":"BETTERLYRICS","sourceFormat":"ttml","syncType":"plain",
                "song":{"durationMs":179999},"lines":[
                {"endMs":179999,"text":"零れ落ちた","romanizedText":"koboreochita","translatedText":"Spilled"},
                {"endMs":179999,"text":"坂道は最寄駅の前","backgroundText":"Echo"}]}"""
        val document = MlxParser.parse(raw)
        assertEquals(LyricsTimingKind.UNSYNCED, lyricsTimingKind(document.lines))
        assertEquals(false, LyricsUtils.hasPlaybackTiming(document.lines))
        assertNull(document.lines.first().endTime)
        assertEquals("koboreochita", document.lines.first().romanizedText)
        assertEquals("Spilled", document.lines.first().translatedText)
        assertEquals(
            0.0,
            document.lines
                .last()
                .words!!
                .single()
                .endTime,
        )
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(LyricsEditor(raw).current.json))
    }

    @Test
    fun fractionalTimestampsUseTheSameUnitsForLinesAndWords() {
        for ((fraction, expected) in listOf("4" to 1_400L, "40" to 1_400L, "400" to 1_400L, "047" to 1_047L)) {
            val line = LyricsUtils.parseLyrics("[00:01.$fraction]<00:01.$fraction>Word<00:02.500>").single()
            assertEquals(expected, line.time)
            assertEquals(expected / 1000.0, line.words!!.single().startTime)
            assertEquals(2.5, line.words.single().endTime)
        }
    }

    @Test
    fun missingFinalWordEndUsesTheNextLineButExplicitEndsArePreserved() {
        for (content in listOf("<00:01.000>Long word", "<00:01.000>Long word<00:03.250>")) {
            val lrc = "[00:01.000]$content\n[00:05.000]Next"
            val expectedEnd = if (content.endsWith('>')) 3.25 else 5.0
            val raw = LyricsUtils.parseLyrics(lrc).first()
            val converted = LyricsUtils.parseLyrics(assertNotNull(enhancedLrcToMlx(lrc, "Song", "Artist", "Test"))).first()
            assertEquals(expectedEnd, raw.words!!.single().endTime)
            assertEquals(expectedEnd, converted.words!!.single().endTime)
        }
    }

    @Test
    fun sourceOffsetSurvivesConversionAndMovesEveryVocalBoundaryOnce() {
        for (offset in listOf(400L, -400L)) {
            val lrc = "[offset:$offset]\n[00:01.000]<00:01.000>Word<00:03.000>\n[00:05.000]Next"
            for (text in listOf(lrc, assertNotNull(enhancedLrcToMlx(lrc, "Song", "Artist", "Test")))) {
                val lines = LyricsUtils.parseLyrics(text)
                assertEquals(1_000L + offset, lines[0].time)
                assertEquals((1_000L + offset) / 1000.0, lines[0].words!!.single().startTime)
                assertEquals((3_000L + offset) / 1000.0, lines[0].words!!.single().endTime)
                assertEquals(5_000L + offset, lines[1].time)
            }
            val lineOnly = assertNotNull(lrcToMlx("[00:01.000]Word\n[offset:$offset]", "Song", "Artist"))
            assertEquals(1_000L + offset, LyricsUtils.parseLyrics(lineOnly).single().time)
        }
    }

    @Test
    fun sourceOffsetMovesBackgroundRomanizationAndSectionTimesTogether() {
        val document =
            MlxParser.parse(
                """
                [lyrics]
                [offset:400]
                [part:00:01.000~00:05.000]Verse
                [00:01.000~00:03.000]<00:01.000>Lead<00:03.000> [bg:<00:02.000>Echo<00:03.000>]
                [romanLyrics]
                [00:01.000~00:03.000]<00:01.000>Roman<00:03.000>
                """.trimIndent(),
            )
        val line = document.lines.single()
        assertEquals(1_400L, line.time)
        assertEquals(3_400L, line.endTime)
        assertEquals(2.4, line.words!!.last().startTime)
        assertEquals(1.4, line.romanizedWords!!.single().startTime)
        assertEquals(1_400L, document.songParts.single().startMs)
        assertEquals(5_400L, document.songParts.single().endMs)
    }

    @Test
    fun negativeSourceOffsetClampsAtZeroAndDoesNotShiftPlainText() {
        val lines = LyricsUtils.parseLyrics("[offset:-2000]\n[00:01.000]<00:01.000>Word<00:03.000>")
        assertEquals(0L, lines.single().time)
        assertEquals(
            0.0,
            lines
                .single()
                .words!!
                .single()
                .startTime,
        )
        assertEquals(
            1.0,
            lines
                .single()
                .words!!
                .single()
                .endTime,
        )
        assertEquals(0L, LyricsUtils.parseLyrics("[offset:2000]\nPlain").single().time)
    }

    @Test
    fun jsonLineTimingUsesTheNextBoundaryWithoutInventingAnInstrumentalGap() {
        val lines =
            LyricsUtils.parseLyrics(
                """{"version":2,"syncType":"line","lines":[
                {"startMs":0,"text":"Long phrase"},
                {"startMs":12000,"text":"Next"},
                {"startMs":12000,"text":"Harmony"},
                {"startMs":16000,"endMs":18000,"text":"Last"}
            ]}""",
            )
        assertEquals(listOf(12_000L, 16_000L, 16_000L, 18_000L), lines.map { it.endTime })
    }

    @Test
    fun finalJsonLineUsesSongDurationWhenItsEndIsMissing() {
        val line =
            LyricsUtils
                .parseLyrics(
                    """{"version":2,"song":{"durationMs":20000},"lines":[{"startMs":10000,"text":"Held phrase"}]}""",
                ).single()
        assertEquals(20_000L, line.endTime)
    }

    @Test
    fun jsonWordMillisecondsAndIndependentBackgroundEndsArePreserved() {
        val line =
            LyricsUtils
                .parseLyrics(
                    """{"version":2,"lines":[{"startMs":447,"endMs":900,"text":"Lead",
                "words":[{"text":"Lead","startMs":447,"endMs":2500}],
                "backgroundWords":[{"text":"Echo","startMs":500,"endMs":1000}]}]}""",
                ).single()
        assertEquals(447L, line.time)
        assertEquals(0.447, line.words!![0].startTime)
        assertEquals(2_500L, LyricsUtils.getLineEndTime(line, null))
    }
}
