package com.metrolist.shared.api.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnhancedLrcPrefixTest {
    @Test
    fun lineTimestampStartsTextBeforeTheFirstWordMarker() {
        val line = MlxParser.parse("[00:01.00]First <00:01.50>word<00:02.00>").lines.single()
        val words = assertNotNull(line.words)

        assertEquals("First word", line.text)
        assertEquals(listOf("First", "word"), words.map { it.text })
        assertEquals(listOf(true, false), words.map { it.hasTrailingSpace })
        assertEquals(listOf(1.0, 1.5), words.map { it.startTime })
        assertEquals(listOf(1.5, 2.0), words.map { it.endTime })
    }

    @Test
    fun importAndExportKeepTheLeadingTextAndItsTiming() {
        val editor = LyricsEditor("[00:00.00]Replace this")
        assertTrue(editor.import("[00:01.00]First <00:01.50>word<00:02.00>"))
        assertTrue(editor.current.isValid)
        assertEquals("[00:01.00] <00:01.00>First <00:01.50>word", editor.current.text)

        val exported = MlxParser.parse(editor.current.json).lines.single()
        assertEquals("First word", exported.text)
        assertEquals(listOf(1.0, 1.5), exported.words?.map { it.startTime })
        assertEquals(listOf(1.5, 2.0), exported.words?.map { it.endTime })
    }

    @Test
    fun aPrefixWithoutALineTimestampDoesNotMoveTheLineEarlier() {
        val line = MlxParser.parse("First <00:01.50>word<00:02.00>").lines.single()
        val words = assertNotNull(line.words)

        assertEquals(1500L, line.time)
        assertEquals("First word", line.text)
        assertEquals(listOf(1.5, 1.5), words.map { it.startTime })
        assertEquals(listOf(1.5, 2.0), words.map { it.endTime })
    }

    @Test
    fun inlineBackgroundKeepsItsOwnLeadingText() {
        val line =
            MlxParser
                .parse("[00:01.00]<00:01.00>Lead<00:02.00> [bg:Echo <00:01.50>now<00:02.00>]")
                .lines
                .single()
        val words = assertNotNull(line.words)

        assertEquals("Lead", line.text)
        assertEquals("Echo now", line.backgroundText)
        assertEquals(listOf("Lead", "Echo", "now"), words.map { it.text })
        assertEquals(listOf(false, true, true), words.map { it.isBackground })
        assertEquals(listOf(1.0, 1.0, 1.5), words.map { it.startTime })
        assertEquals(listOf(2.0, 1.5, 2.0), words.map { it.endTime })
    }

    @Test
    fun anExplicitStartAtOrAfterTheFirstMarkerDoesNotProduceABackwardWord() {
        for (lineTag in listOf("00:01.50", "00:01.75")) {
            val line = MlxParser.parse("[$lineTag]First <00:01.50>word<00:02.00>").lines.single()
            val first = assertNotNull(line.words).first()

            assertEquals("First", first.text)
            assertEquals(1.5, first.startTime)
            assertEquals(1.5, first.endTime)
        }
    }

    @Test
    fun regularTimingMarkersAndPlainLinesAreUnchanged() {
        val lines =
            MlxParser
                .parse("[00:01.00] <00:01.20>First <00:01.50>word<00:02.00>\n[00:03.00] Plain line")
                .lines
        val words = assertNotNull(lines.first().words)

        assertEquals(listOf("First word", "Plain line"), lines.map { it.text })
        assertEquals(listOf(1.2, 1.5), words.map { it.startTime })
        assertEquals(listOf(1.5, 2.0), words.map { it.endTime })
        assertEquals(null, lines.last().words)
    }
}
