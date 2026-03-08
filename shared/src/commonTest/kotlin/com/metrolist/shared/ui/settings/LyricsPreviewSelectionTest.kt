package com.metrolist.shared.ui.settings

import com.metrolist.shared.model.LyricsEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsPreviewSelectionTest {
    private val lyrics = listOf(LyricsEntry(0L, ""), LyricsEntry(1000L, "Current song"))

    @Test
    fun requiresActivePlaybackAndUsableLyricsForACurrentSong() {
        assertTrue(useCurrentLyricsForPreview("track", true, lyrics))
        assertFalse(useCurrentLyricsForPreview("track", false, lyrics))
        assertFalse(useCurrentLyricsForPreview(null, true, lyrics))
        assertFalse(useCurrentLyricsForPreview("", true, lyrics))
        assertFalse(useCurrentLyricsForPreview("track", true, emptyList()))
        assertFalse(useCurrentLyricsForPreview("track", true, listOf(LyricsEntry(0L, " \n "))))
    }

    @Test
    fun switchesToTheFallbackOnPauseAndBackToTheSongOnResume() {
        assertEquals(
            listOf(true, false, true),
            listOf(true, false, true).map { playing -> useCurrentLyricsForPreview("track", playing, lyrics) },
        )
        assertFalse(useCurrentLyricsForPreview(null, false, emptyList()))
        assertFalse(useCurrentLyricsForPreview("next", true, emptyList()))
        assertTrue(useCurrentLyricsForPreview("next", true, lyrics))
    }
}
