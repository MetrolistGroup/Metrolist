package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Background vocals overlap the line they accompany instead of following it, so a
 * background line must not be treated as "the next line" when working out how long
 * the previous line's last word lasts.
 */
@RunWith(RobolectricTestRunner::class)
class LyricsBackgroundTimingTest {

    /**
     * The background line starts at 10.20, which is *before* the main line's last
     * word starts at 10.50. Deriving the last word's end from it produced an end
     * time earlier than its own start, so the word rendered as already finished.
     */
    private val lyricsWithBackground = """
        [00:10.00]<00:10.00>Main <00:10.50>line
        [bg: <00:10.20>ooh<00:11.00>]
        [00:12.00]<00:12.00>Next <00:12.40>line
    """.trimIndent()

    private fun mainLines(parsed: List<LyricsEntry>) = parsed.filter { !it.isBackground }

    @Test
    fun `last word of a line with background vocals does not end before it starts`() {
        val parsed = LyricsUtils.parseLyrics(lyricsWithBackground)
        val firstLine = mainLines(parsed).first()
        val words = firstLine.words
        assertNotNull("expected word-level timings", words)

        val lastWord = words!!.last()
        assertEquals("line", lastWord.text)
        assertTrue(
            "last word ends at ${lastWord.endTime} but starts at ${lastWord.startTime}",
            lastWord.endTime > lastWord.startTime,
        )
    }

    @Test
    fun `last word runs to the next main line rather than to the background line`() {
        val parsed = LyricsUtils.parseLyrics(lyricsWithBackground)
        val lastWord = mainLines(parsed).first().words!!.last()

        // The next main line starts at 12.0; the overlapping background line at 10.2
        // must not cut the word short.
        assertEquals(12.0, lastWord.endTime, 0.001)
    }

    @Test
    fun `background line itself is still parsed with its own timings`() {
        val parsed = LyricsUtils.parseLyrics(lyricsWithBackground)
        val background = parsed.filter { it.isBackground }

        assertEquals(1, background.size)
        assertEquals("ooh", background.first().text)
    }

    @Test
    fun `the bracketed bg spelling is skipped too`() {
        // The parser also accepts [MM:SS.mm]{bg}... for background vocals.
        val braceStyle = """
            [00:10.00]<00:10.00>Main <00:10.50>line
            [00:10.20]{bg}<00:10.20>ooh
            [00:12.00]<00:12.00>Next <00:12.40>line
        """.trimIndent()

        val lastWord = mainLines(LyricsUtils.parseLyrics(braceStyle)).first().words!!.last()

        assertTrue(
            "last word ends at ${lastWord.endTime} but starts at ${lastWord.startTime}",
            lastWord.endTime > lastWord.startTime,
        )
        assertEquals(12.0, lastWord.endTime, 0.001)
    }

    @Test
    fun `a background line whose timings sit on a continuation line is skipped whole`() {
        // A [bg: ...] line without inline timestamps carries its timings on the
        // following standalone <word:start:end> line. That continuation belongs to
        // the background, so it must be skipped along with it.
        val withContinuation = """
            [00:10.00]<00:10.00>Main <00:10.50>line
            [bg: ooh]
            <ooh:10.2:11.0>
            [00:12.00]<00:12.00>Next <00:12.40>line
        """.trimIndent()

        val parsed = LyricsUtils.parseLyrics(withContinuation)
        val lastWord = mainLines(parsed).first().words!!.last()

        assertEquals("line", lastWord.text)
        assertEquals(12.0, lastWord.endTime, 0.001)

        // The background entry still gets its timings from the continuation line.
        val background = parsed.filter { it.isBackground }
        assertEquals(1, background.size)
        assertEquals(10.2, background.first().words!!.first().startTime, 0.001)
    }

    @Test
    fun `a line followed by a normal line is unaffected`() {
        val plain = """
            [00:10.00]<00:10.00>Main <00:10.50>line
            [00:12.00]<00:12.00>Next <00:12.40>line
        """.trimIndent()

        val lastWord = mainLines(LyricsUtils.parseLyrics(plain)).first().words!!.last()

        assertEquals(12.0, lastWord.endTime, 0.001)
    }
}
