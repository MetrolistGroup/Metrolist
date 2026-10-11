/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rich-sync parser accepts single-digit minutes ([1:23.45]), so line-sync
 * detection must accept them too. Otherwise timed lyrics are parsed but shown
 * as an unsynced wall (no auto-follow, every line highlighted).
 */
class LyricsSyncDetectionTest {

    private val singleDigitRich = "[1:23.45]<1:23.45>Hello <1:23.80>world"

    private val twoDigitRich = "[01:23.45]<01:23.45>Hello <01:23.80>world"

    @Test
    fun `single-digit rich line counts as line-synced`() {
        assertTrue(LyricsUtils.isLineSynced(singleDigitRich))
    }

    @Test
    fun `two-digit rich line still counts as line-synced`() {
        assertTrue(LyricsUtils.isLineSynced(twoDigitRich))
    }

    @Test
    fun `plain text is not line-synced`() {
        assertFalse(LyricsUtils.isLineSynced("Hello world\nSecond line"))
    }

    @Test
    fun `detection agrees with the parser for single-digit rich lyrics`() {
        val parsed = LyricsUtils.parseLyrics(singleDigitRich)
        assertTrue("expected timed entries", parsed.isNotEmpty())
        assertTrue("first entry has a real timestamp", parsed.any { it.time > 0 })
        assertTrue(LyricsUtils.isLineSynced(singleDigitRich))
    }

    @Test
    fun `standard two-digit line is synced`() {
        assertTrue(LyricsUtils.isLineSynced("[01:23.45] Just text"))
        assertEquals(83450L, LyricsUtils.parseLyrics("[01:23.45] Just text").first().time)
    }

    @Test
    fun `standard single-digit line parses and counts as synced`() {
        // Standard lines accept single-digit minutes, so detection and parse agree.
        val lyrics = "[1:23.45] Just text"
        assertEquals(83450L, LyricsUtils.parseLyrics(lyrics).first().time)
        assertTrue(LyricsUtils.isLineSynced(lyrics))
    }

    @Test
    fun `single-digit credit prefix is stripped`() {
        val filtered = LyricsUtils.filterLyricsCreditLines("[1:23.45] Synced by Someone")
        assertFalse(filtered.contains("[1:23.45]"))
    }

    @Test
    fun `markers scattered across timestamp-less lines are not line-synced`() {
        // Neither line carries a line timestamp of its own, so the parser takes the
        // standard path and yields no timed entries; detection must agree instead
        // of enabling auto-follow on plain lines.
        val lyrics = "hello <1:00.00>foo\nbar <2:00.00>baz"
        assertFalse(LyricsUtils.isLineSynced(lyrics))
        assertTrue(LyricsUtils.parseLyrics(lyrics).none { it.time > 0 })
    }
}
