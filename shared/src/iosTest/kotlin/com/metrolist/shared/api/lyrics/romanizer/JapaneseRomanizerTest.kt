package com.metrolist.shared.api.lyrics.romanizer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class JapaneseRomanizerTest {
    @Test
    fun skipsDictionaryPreparationForKanaOnlyLyrics() =
        runBlocking {
            assertEquals(false, LyricsRomanizer.prepareJapaneseRomanizationIfNeeded("かなカタカナ", setOf("Japanese")))
        }

    @Test
    fun chineseLinesWithKanaOnlyJapaneseDoNotDownloadJapaneseDictionary() =
        runBlocking {
            assertEquals(false, LyricsRomanizer.prepareJapaneseRomanizationIfNeeded("你好\n世界\nかな", setOf("Japanese", "Chinese")))
        }

    @Test
    fun offlineFallbackKeepsKanjiUnchangedInsteadOfApplyingChineseReadings() {
        assertEquals("君", romanizeJapanese("君"))
        assertEquals("君の名前", romanizeJapanese("君の名前"))
        assertEquals("𠮷の", romanizeJapanese("𠮷の"))

        val kana = "かなカタカナ"
        assertEquals(LyricsRomanizer.romanizeKana(kana), romanizeJapanese(kana))
        assertEquals(" ", romanizeJapanese(" "))
    }
}
