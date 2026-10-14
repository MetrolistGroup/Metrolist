/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFileNamingTest {
    @Test
    fun `builds artist dash title with extension`() {
        assertEquals("Queen — Bohemian Rhapsody.m4a", ExportFileNaming.build(listOf("Queen"), "Bohemian Rhapsody", "m4a"))
    }

    @Test
    fun `joins several artists`() {
        assertEquals("A, B — Song.mp3", ExportFileNaming.build(listOf("A", "B"), "Song", "mp3"))
    }

    @Test
    fun `omits separator when there is no artist`() {
        assertEquals("Song.opus", ExportFileNaming.build(emptyList(), "Song", "opus"))
        assertEquals("Song.opus", ExportFileNaming.build(listOf("  "), "Song", "opus"))
    }

    @Test
    fun `replaces characters that are invalid on Android and Windows`() {
        val name = ExportFileNaming.build(listOf("AC/DC"), "Who Made Who? <live> \"88\" | a:b*c\\d", "m4a")
        assertFalse(name.any { it in "/\\:*?\"<>|" })
        assertTrue(name.endsWith(".m4a"))
        assertEquals("AC_DC — Who Made Who_ _live_ _88_ _ a_b_c_d.m4a", name)
    }

    @Test
    fun `removes control characters and collapses whitespace`() {
        assertEquals("A — B__ C.mp3", ExportFileNaming.build(listOf("A"), "B\u0000\n  C", "mp3"))
        assertEquals("A — B C.mp3", ExportFileNaming.build(listOf("A"), "B   C", "mp3"))
    }

    @Test
    fun `trailing dots and spaces are dropped`() {
        assertEquals("Song.mp3", ExportFileNaming.build(emptyList(), "Song...  ", "mp3"))
    }

    @Test
    fun `empty title falls back to a placeholder`() {
        assertEquals("Unknown.mp3", ExportFileNaming.build(emptyList(), "", "mp3"))
        assertEquals("Unknown.mp3", ExportFileNaming.build(emptyList(), " . ", "mp3"))
    }

    @Test
    fun `reserved Windows device names get a prefix`() {
        assertEquals("_CON.mp3", ExportFileNaming.build(emptyList(), "CON", "mp3"))
    }

    @Test
    fun `keeps Cyrillic and CJK intact`() {
        assertEquals("Земфира — Хочешь_.m4a", ExportFileNaming.build(listOf("Земфира"), "Хочешь?", "m4a"))
        assertEquals("宇多田ヒカル — First Love.opus", ExportFileNaming.build(listOf("宇多田ヒカル"), "First Love", "opus"))
    }

    @Test
    fun `long names are cut on a character boundary within the byte limit`() {
        val name = ExportFileNaming.build(listOf("Артист"), "Я".repeat(400), "m4a")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue(name.endsWith(".m4a"))
        assertFalse(name.contains('�'))
    }

    @Test
    fun `counter is inserted before the extension`() {
        assertEquals("A — B (1).m4a", ExportFileNaming.withCounter("A — B.m4a", 1))
        assertEquals("noext (2)", ExportFileNaming.withCounter("noext", 2))
    }

    @Test
    fun `unique name skips taken names`() {
        val taken = setOf("A.mp3", "A (1).mp3", "A (2).mp3")
        assertEquals("A (3).mp3", ExportFileNaming.uniqueName("A.mp3") { it in taken })
        assertEquals("B.mp3", ExportFileNaming.uniqueName("B.mp3") { it in taken })
    }
}
