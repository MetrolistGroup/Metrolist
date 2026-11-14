package com.metrolist.shared.api.lyrics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsArtistMatchTest {
    @Test
    fun acceptsSpellingAndCreditVariantsOfTheRequestedArtist() {
        assertTrue(LyricsUtils.lyricsArtistsMatch("Baby Jane", "Baby Jane"))
        assertTrue(LyricsUtils.lyricsArtistsMatch("Baby Jane", "BABY JANE, John Flynn"))
        assertTrue(LyricsUtils.lyricsArtistsMatch("Ninajirachi, Porter Robinson", "Porter Robinson & Ninajirachi"))
        assertTrue(LyricsUtils.lyricsArtistsMatch("Beyoncé", "Beyonce"))
        assertTrue(LyricsUtils.lyricsArtistsMatch("The Weeknd", "Weeknd"))
        assertTrue(LyricsUtils.lyricsArtistsMatch("Tyler, The Creator", "Tyler, The Creator feat. Kali Uchis"))
    }

    @Test
    fun rejectsArtistsSharingOnlyAWord() {
        assertFalse(LyricsUtils.lyricsArtistsMatch("Baby Jane", "Baby J."))
        assertFalse(LyricsUtils.lyricsArtistsMatch("Baby Jane", "Baby Gang"))
        assertFalse(LyricsUtils.lyricsArtistsMatch("Justice", "Justice Der"))
    }

    @Test
    fun cannotJudgeMissingOrNonLatinNames() {
        assertTrue(LyricsUtils.lyricsArtistsMatch("Baby Jane", ""))
        assertTrue(LyricsUtils.lyricsArtistsMatch("米津玄師", "Kenshi Yonezu"))
        assertTrue(LyricsUtils.lyricsArtistsMatch("Kenshi Yonezu", "米津玄師"))
    }
}
