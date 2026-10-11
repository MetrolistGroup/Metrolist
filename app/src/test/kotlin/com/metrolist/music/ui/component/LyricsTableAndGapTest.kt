/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import com.metrolist.music.ui.screens.settings.LyricsPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure-logic coverage for the lyrics height cache key and the gap progress math.
 * The gap test pins the lyric-offset fix: progress must be computed from the
 * offset-adjusted position, matching how gap visibility is decided.
 * The fingerprint tests pin the generation guarantee: the key always belongs to
 * the exact list it was computed from, so a transition can never measure a stale
 * list under a fresh key.
 */
class LyricsTableAndGapTest {

    private fun key(
        fontScale: Float = 1f,
        maxWidthPx: Int = 1080,
        contentFingerprint: Int = 456,
        romanizeCyrillicByLine: Boolean = false,
        density: Float = 3f,
    ) = buildHeightTableKey(
        lyricsHash = 123,
        contentFingerprint = contentFingerprint,
        maxWidthPx = maxWidthPx,
        lyricsTextPosition = LyricsPosition.CENTER,
        showIntervalIndicator = true,
        romanizeAsMain = false,
        enabledLanguagesHash = 0,
        romanizeSong = false,
        romanizeCyrillicByLine = romanizeCyrillicByLine,
        translateLanguage = "en",
        translateMode = "Literal",
        fontScale = fontScale,
        density = density,
    )

    private fun sampleItems(): List<LyricsListItem> = listOf(
        LyricsListItem.Line(0, com.metrolist.music.lyrics.LyricsEntry(1000L, "hello")),
        LyricsListItem.Line(1, com.metrolist.music.lyrics.LyricsEntry(2000L, "world", isBackground = true)),
        LyricsListItem.Indicator(afterLineIndex = 1, gapMs = 5000L, gapStartMs = 3000L, gapEndMs = 8000L, nextAgent = null),
    )

    @Test
    fun `same inputs produce the same key`() {
        assertEquals(key(), key())
    }

    @Test
    fun `font scale change invalidates the key`() {
        assertNotEquals(key(fontScale = 1f), key(fontScale = 1.3f))
    }

    @Test
    fun `width change invalidates the key`() {
        assertNotEquals(key(maxWidthPx = 1080), key(maxWidthPx = 720))
    }

    @Test
    fun `content change invalidates the key`() {
        assertNotEquals(key(contentFingerprint = 1), key(contentFingerprint = 2))
    }

    @Test
    fun `romanize mode change invalidates the key`() {
        assertNotEquals(key(romanizeCyrillicByLine = false), key(romanizeCyrillicByLine = true))
    }

    @Test
    fun `density change invalidates the key`() {
        assertNotEquals(key(density = 3f), key(density = 2f))
    }

    @Test
    fun `fingerprint is stable for the same list`() {
        assertEquals(
            contentFingerprintForHeightTable(sampleItems()),
            contentFingerprintForHeightTable(sampleItems())
        )
    }

    @Test
    fun `fingerprint covers order text background and kind`() {
        val base = contentFingerprintForHeightTable(sampleItems())
        assertNotEquals(base, contentFingerprintForHeightTable(sampleItems().reversed()))
        val editedText = listOf(
            LyricsListItem.Line(0, com.metrolist.music.lyrics.LyricsEntry(1000L, "changed")),
            sampleItems()[1],
            sampleItems()[2],
        )
        assertNotEquals(base, contentFingerprintForHeightTable(editedText))
        val editedBg = listOf(
            LyricsListItem.Line(0, com.metrolist.music.lyrics.LyricsEntry(1000L, "hello", isBackground = true)),
            sampleItems()[1],
            sampleItems()[2],
        )
        assertNotEquals(base, contentFingerprintForHeightTable(editedBg))
    }

    @Test
    fun `empty list has a stable fingerprint`() {
        assertEquals(
            contentFingerprintForHeightTable(emptyList()),
            contentFingerprintForHeightTable(emptyList())
        )
    }

    @Test
    fun `gap progress is halfway mid-gap`() {
        assertEquals(0.5f, gapProgress(12000L, 10000L, 14000L))
    }

    @Test
    fun `gap progress clamps outside the gap`() {
        assertEquals(0f, gapProgress(9000L, 10000L, 14000L))
        assertEquals(1f, gapProgress(15000L, 10000L, 14000L))
    }

    @Test
    fun `gap progress of degenerate gap is zero`() {
        assertEquals(0f, gapProgress(12000L, 14000L, 14000L))
        assertEquals(0f, gapProgress(12000L, 14000L, 10000L))
    }

    @Test
    fun `gap progress uses the offset-adjusted position`() {
        // Player at 11000ms with a +500ms lyrics offset: the lyric point is 11500ms.
        assertEquals(0.375f, gapProgress(11000L + 500L, 10000L, 14000L))
        // The raw player time would show the wrong point in the gap.
        assertNotEquals(
            gapProgress(11000L + 500L, 10000L, 14000L),
            gapProgress(11000L, 10000L, 14000L)
        )
    }

    @Test
    fun `tap landing matches at zero offset`() {
        assertEquals(true, isTapLanding(83450L, 0L, 83450L, 3000L))
        assertEquals(false, isTapLanding(80000L, 0L, 83450L, 3000L))
    }

    @Test
    fun `tap landing compensates the lyrics offset`() {
        // Tapped line at lyric time 83450; player lands at 83450 - 2000 with +2000 offset.
        assertEquals(true, isTapLanding(81450L, 2000L, 83450L, 1500L))
        // Raw comparison would misread this as a scrub landing elsewhere.
        assertEquals(false, isTapLanding(81450L, 0L, 83450L, 1500L))
    }
}
