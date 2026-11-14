package com.metrolist.shared.api.lyrics.romanizer

import kotlin.test.Test
import kotlin.test.assertEquals

class JapaneseRomanizerFallbackTest {
    @Test
    fun unavailableDictionaryStoragePreservesKanji() {
        assertEquals("君", romanizeJapanese("君"))
        assertEquals("𠮷の", romanizeJapanese("𠮷の"))
    }
}
