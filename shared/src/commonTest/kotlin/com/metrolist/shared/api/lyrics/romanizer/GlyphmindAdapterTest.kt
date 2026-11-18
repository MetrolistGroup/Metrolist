/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.model.WordTimestamp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GlyphmindAdapterTest {
    @Test
    fun preservesEveryTimingAndVocalField() =
        runTest {
            val source = listOf(WordTimestamp("한글", 1.25, 2.5, hasTrailingSpace = false, isBackground = true, isLineTimed = true))
            val result = assertNotNull(LyricsRomanizer.romanizeWords("한글", "한글", source, setOf("Korean"), false))
            assertEquals("hangeul", result.first)
            assertEquals(source.single().copy(text = "hangeul"), result.second.single())
        }

    @Test
    fun preparesOnlyEnabledDictionaryLanguages() =
        runTest {
            var calls = 0
            val prepare: suspend () -> Boolean = {
                calls++
                true
            }
            LyricsRomanizer.prepareDictionariesIfNeeded("你好", setOf("Chinese"), prepare)
            LyricsRomanizer.prepareDictionariesIfNeeded("かな", setOf("Japanese"), prepare)
            LyricsRomanizer.prepareDictionariesIfNeeded("한글", setOf("Korean"), prepare)
            LyricsRomanizer.prepareDictionariesIfNeeded("你好", emptySet(), prepare)
            assertEquals(2, calls)
        }
}
