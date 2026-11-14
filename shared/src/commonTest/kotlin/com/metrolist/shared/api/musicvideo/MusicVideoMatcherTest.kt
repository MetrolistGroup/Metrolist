package com.metrolist.shared.api.musicvideo

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicVideoMatcherTest {
    @Test
    fun variantPatternsPreserveWordBoundariesAndExistingVariants() {
        assertEquals(false, MusicVideoMatcher.isDisqualifiedVariant("Song", "Song recovered"))
        assertEquals(true, MusicVideoMatcher.isDisqualifiedVariant("Song", "Song (fan-made)"))
        assertEquals(false, MusicVideoMatcher.isDisqualifiedVariant("Song fan made", "Song (fan-made)"))
        assertEquals(true, MusicVideoMatcher.titleMatches("Song part 2", "Song"))
        assertEquals("Song", MusicVideoMatcher.stripArtistPrefix("Artist • Song", setOf("Artist")))
    }

    private fun song(
        id: String,
        title: String,
        artist: String,
        type: String? = null,
        duration: Long? = 213L,
        artistId: String? = null,
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            thumbnail = null,
            artists = listOf(ArtistTiny(id = artistId, name = artist)),
            album = null,
            duration = duration,
            musicVideoType = type,
        )

    private val track = song("TRACKID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")

    @Test
    fun findsOfficialVideo_overOtherVariants() {
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up (Bass Boosted)", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO", 213),
                song("V2", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V3", "Never Gonna Give You Up (Lyrics)", "Rick Astley", "MUSIC_VIDEO_TYPE_UGC", 211),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun prefersExactTitleMatch() {
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up 1987", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 220),
                song("V2", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun prefersArtistMatchingCandidate_overTitleOnlyMatch() {
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up", "Cover Band", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V2", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun penalizesNonOfficialVariants() {
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up (Karaoke Version)", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO", 214),
                song("V2", "Never Gonna Give You Up (Slowed + Reverb)", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO", 214),
                song("V3", "Never Gonna Give You Up (Remix)", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO", 214),
                song("V4", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO", 214),
            )
        assertEquals("V4", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun rejectsRemixWhenNoOriginalVideoIsAvailable() {
        val hootieFrutti = song("TRACK", "Hootie Frutti", "KATSEYE", "MUSIC_VIDEO_TYPE_ATV", 154)
        val candidates =
            listOf(
                song(
                    "REMIX",
                    "KATSEYE Hootie Frutti X M.I.A (DJ Remix)",
                    "KATSEYE",
                    "MUSIC_VIDEO_TYPE_VIDEO",
                    154,
                ),
            )

        assertNull(MusicVideoMatcher.findBestMatch(hootieFrutti, candidates))
    }

    @Test
    fun penalizesExtendedAndAiVariants() {
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up (Extended Version)", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V2", "Never Gonna Give You Up (AI Cover)", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V3", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO", 214),
            )
        assertEquals("V3", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun extendedAiCoverLosesToUgcOfficialWhenPenalized() {
        val studioTrack = song("TRACKID", "Brighter", "Hazbin Hotel", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Brighter - Extended Version | Hazbin Hotel S2", "ONE PROJECT", "MUSIC_VIDEO_TYPE_OMV", 170),
                song("V2", "Brighter Sing-Along - Hazbin Hotel S2 | Prime Video", "Prime Video", "MUSIC_VIDEO_TYPE_UGC", 177),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(studioTrack, candidates)?.id)
    }

    @Test
    fun skipsTitleScoringWhenTrackTitleIsBlank() {
        val blankTitleTrack = song("TRACK", "", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Unrelated Title", "Other Artist", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V2", "Whatever", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertNull(MusicVideoMatcher.findBestMatch(blankTitleTrack, candidates))
    }

    @Test
    fun treatsNullAndZeroDurationsAsNonMatching() {
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", null),
                song("V2", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 0),
            )
        assertEquals("V1", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun durationBoundaryAtThirtySeconds() {
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 183),
                song("V2", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 182),
            )
        assertEquals("V1", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun prefersArtistIdMatch_overSameNameCoverBand() {
        val trackWithId = song("TRACKID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV", artistId = "ARTIST_A")
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214, artistId = "ARTIST_B"),
                song("V2", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214, artistId = "ARTIST_A"),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(trackWithId, candidates)?.id)
    }

    @Test
    fun fallsBackToNameMatching_whenNoArtistIdMatches() {
        val trackWithId = song("TRACKID", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV", artistId = "ARTIST_A")
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214, artistId = "ARTIST_B"),
                song("V2", "Never Gonna Give You Up", "Cover Band", "MUSIC_VIDEO_TYPE_OMV", 214, artistId = "ARTIST_C"),
            )
        assertNull(MusicVideoMatcher.findBestMatch(trackWithId, candidates))
    }

    @Test
    fun fallsBackToNameMatching_whenTrackHasNoArtistIds() {
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214, artistId = "ARTIST_B"),
                song("V2", "Never Gonna Give You Up", "Cover Band", "MUSIC_VIDEO_TYPE_OMV", 214, artistId = "ARTIST_C"),
            )
        assertEquals("V1", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun returnsNullWhenNoCandidateIsAVideoSong() {
        val candidates = listOf(song("V1", "Never Gonna Give You Up", "Rick Astley", null))
        assertNull(MusicVideoMatcher.findBestMatch(track, candidates))
    }

    @Test
    fun titleMatchingIgnoresPunctuationAndCase() {
        val candidates =
            listOf(
                song("V1", "NEVER GONNA GIVE YOU UP!", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertEquals("V1", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun matchesExtendedTitleWhenExactUnavailable() {
        val candidates =
            listOf(
                song("V1", "Unrelated Song", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V2", "Never Gonna Give You Up (Official Music Video)", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun durationClosenessBreaksTies() {
        val candidates =
            listOf(
                song("V1", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 500),
                song("V2", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 215),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun nameMatchingUsesContains_notExactMatch() {
        val studioTrack = song("TRACKID", "Brighter", "Hazbin Hotel", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Brighter", "Unrelated Artist", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V2", "Brighter", "Hazbin Hotel Music", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(studioTrack, candidates)?.id)
    }

    @Test
    fun matchesTrackWithMultipleArtists() {
        val trackWithMultipleArtists =
            SongItem(
                id = "TRACKID",
                title = "Song Title",
                thumbnail = null,
                artists = listOf(ArtistTiny(id = "A1", name = "Artist A"), ArtistTiny(id = "A2", name = "Artist B")),
                album = null,
                duration = 213L,
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            )
        val candidates =
            listOf(
                song("V1", "Song Title", "Other Artist", "MUSIC_VIDEO_TYPE_OMV", 214, artistId = "OTHER"),
                song("V2", "Song Title", "Artist A", "MUSIC_VIDEO_TYPE_OMV", 214, artistId = "A1"),
                song("V3", "Song Title", "Artist B", "MUSIC_VIDEO_TYPE_OMV", 214, artistId = "A2"),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(trackWithMultipleArtists, candidates)?.id)
    }

    @Test
    fun matchesCandidateWithMultipleArtists() {
        val track = song("TRACKID", "Song Title", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV", artistId = "RA")
        val candidates =
            listOf(
                SongItem(
                    id = "V1",
                    title = "Song Title",
                    thumbnail = null,
                    artists = listOf(ArtistTiny(id = "RA", name = "Rick Astley"), ArtistTiny(id = "E", name = "Estelle")),
                    album = null,
                    duration = 214L,
                    musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
                ),
            )
        assertEquals("V1", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun handlesEmptyArtistListOnTrack() {
        val trackWithNoArtists =
            SongItem(
                id = "TRACKID",
                title = "Song Title",
                thumbnail = null,
                artists = emptyList(),
                album = null,
                duration = 213L,
                musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            )
        val candidates =
            listOf(
                song("V1", "Song Title", "Artist A", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V2", "Unrelated Song", "Artist B", "MUSIC_VIDEO_TYPE_OMV", 300),
            )
        assertEquals("V1", MusicVideoMatcher.findBestMatch(trackWithNoArtists, candidates)?.id)
    }

    @Test
    fun shortCandidateTitleDoesNotFalseMatch() {
        val track = song("TRACKID", "A Long Song Title", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "A", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V2", "A Long Song Title", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun shortArtistNameDoesNotFalseMatch() {
        val track = song("TRACKID", "Song Title", "Ed Sheeran", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Song Title", "Ed", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V2", "Song Title", "Ed Sheeran", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertEquals("V2", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun artistBonusOnlyAppliedInFallbackTier() {
        val studioTrack = song("TRACKID", "Song Title", "Hazbin Hotel", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Song Title", "Hazbin Hotel", "MUSIC_VIDEO_TYPE_OMV", 214),
                song("V2", "Song Title", "Hazbin Hotel Music", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertEquals("V1", MusicVideoMatcher.findBestMatch(studioTrack, candidates)?.id)
    }

    @Test
    fun rejectsCandidateBeyondTwofoldDuration() {
        val candidates =
            listOf(
                song("VLONG", "Never Gonna Give You Up (Special Edition)", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO", 500),
            )
        assertNull(MusicVideoMatcher.findBestMatch(track, candidates))
    }

    @Test
    fun prefersShortOfficialOverLongLiveVideo() {
        val candidates =
            listOf(
                song("VLIVE", "Never Gonna Give You Up (Live at Wembley)", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO", 3600),
                song("VOMV", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_OMV", 214),
            )
        assertEquals("VOMV", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun rejectsOverlongLiveVideoAndDeclinesLyricOnlyFallback() {
        val candidates =
            listOf(
                song("VLIVE", "Never Gonna Give You Up (Live)", "Rick Astley", "MUSIC_VIDEO_TYPE_VIDEO", 3600),
                song("VLYRICS", "Never Gonna Give You Up (Lyrics)", "Rick Astley", "MUSIC_VIDEO_TYPE_UGC", 214),
            )
        // The remaining lyric video fails the high-confidence gate after duration excludes the live video.
        assertNull(MusicVideoMatcher.findBestMatch(track, candidates))
    }

    @Test
    fun rejectsTitleMatchFromUnrelatedArtist() {
        val track = song("TRACK", "The Last Page", "Spencer Sanders", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "The Last Page Gameplay", "Unrelated Gamer", "MUSIC_VIDEO_TYPE_UGC", 213),
                song("V2", "Another Song", "Another Artist", "MUSIC_VIDEO_TYPE_OMV", 213),
            )

        assertNull(MusicVideoMatcher.findBestMatch(track, candidates))
    }

    @Test
    fun rejectsCoverByOtherSingerEvenWhenOriginalArtistInTitle() {
        val originalTrack = song("TRACK", "Hello", "Adele", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Adele - Hello (Cover by Luciana Zogbi)", "Luciana Zogbi", "MUSIC_VIDEO_TYPE_VIDEO", 295),
                song("V2", "Hello - Adele | Guitar Tutorial", "Guitar Lessons", "MUSIC_VIDEO_TYPE_VIDEO", 300),
                song("V3", "Hello (Karaoke Version) - In the style of Adele", "Sing King", "MUSIC_VIDEO_TYPE_VIDEO", 295),
            )

        assertNull(MusicVideoMatcher.findBestMatch(originalTrack, candidates))
    }

    @Test
    fun matchesOfficialVideoWithArtistPrefixInTitle() {
        val originalTrack = song("TRACK", "Never Gonna Give You Up", "Rick Astley", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Rick Astley - Never Gonna Give You Up (Official Music Video)", "RickAstleyVEVO", "MUSIC_VIDEO_TYPE_OMV", 214),
            )

        assertEquals("V1", MusicVideoMatcher.findBestMatch(originalTrack, candidates)?.id)
    }

    @Test
    fun rejectsSubstringTitleCollisions() {
        val track = song("TRACK", "Lover", "Taylor Swift", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "I Need a Lover", "Taylor Swift", "MUSIC_VIDEO_TYPE_VIDEO", 220),
                song("V2", "Lover of Mine", "Taylor Swift", "MUSIC_VIDEO_TYPE_VIDEO", 220),
            )

        assertNull(MusicVideoMatcher.findBestMatch(track, candidates))
    }

    @Test
    fun matchesArtistColonPrefixedVideoTitle() {
        val track = song("TRACK", "Blue Sky", "Artist Name", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Artist Name: Blue Sky - Official Music Video", "Artist Channel", "MUSIC_VIDEO_TYPE_UGC", 214),
            )

        assertEquals("V1", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun prefersStandardVersionOverNewVersionWhenPlayingStandard() {
        val standardTrack = song("TRACK", "123456", "Artist", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "123456 (New Version)", "Artist", "MUSIC_VIDEO_TYPE_OMV", 213),
                song("V2", "Artist - 123456 (Official Video)", "Record Label", "MUSIC_VIDEO_TYPE_VIDEO", 213),
            )

        assertEquals("V2", MusicVideoMatcher.findBestMatch(standardTrack, candidates)?.id)
    }

    @Test
    fun prefersNewVersionWhenPlayingNewVersion() {
        val newVersionTrack = song("TRACK", "123456 (New Version)", "Artist", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Artist - 123456 (Official Video)", "Record Label", "MUSIC_VIDEO_TYPE_VIDEO", 213),
                song("V2", "123456 (New Version)", "Artist", "MUSIC_VIDEO_TYPE_OMV", 213),
            )

        assertEquals("V2", MusicVideoMatcher.findBestMatch(newVersionTrack, candidates)?.id)
    }

    @Test
    fun matchesTitleWithMinorTypoOrTransliterationUsingLevenshtein() {
        val track = song("TRACK", "Langit Biru", "Artist", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Langit Biruu (Official Music Video)", "Artist", "MUSIC_VIDEO_TYPE_VIDEO", 213),
            )

        assertEquals("V1", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun matchesArtistWithMinorSpellingVariationUsingLevenshtein() {
        val track = song("TRACK", "Song Title", "Artist Name", "MUSIC_VIDEO_TYPE_ATV")
        val candidates =
            listOf(
                song("V1", "Song Title (Official Video)", "ArtistName", "MUSIC_VIDEO_TYPE_VIDEO", 213),
            )

        assertEquals("V1", MusicVideoMatcher.findBestMatch(track, candidates)?.id)
    }

    @Test
    fun computesAccurateLevenshteinSimilarity() {
        assertEquals(0, MusicVideoMatcher.levenshteinDistance("kitten", "kitten"))
        assertEquals(3, MusicVideoMatcher.levenshteinDistance("kitten", "sitting"))
        assertEquals(1.0, MusicVideoMatcher.levenshteinSimilarity("hello", "hello"), 0.001)
        assertEquals(0.8, MusicVideoMatcher.levenshteinSimilarity("helo", "hello"), 0.001)
    }

    @Test
    fun findsSelfWhenSearchConfirmsTrackIsOfficialVideo() {
        val trackWithoutType = song("fLdVq8n-KQg", "Astaga Bercanda", "Akbar Chalay, Mingse", type = null, duration = 240)
        val candidates =
            listOf(
                song("wPb_miODtDI", "Astaga Bercanda", "Akbar Chalay", "MUSIC_VIDEO_TYPE_OMV", duration = 240),
                song("fLdVq8n-KQg", "Astaga Bercanda", "Akbar Chalay, Mingse", "MUSIC_VIDEO_TYPE_OMV", duration = 240),
            )
        assertEquals("fLdVq8n-KQg", MusicVideoMatcher.findBestMatch(trackWithoutType, candidates)?.id)
    }

    @Test
    fun skipsSelfWhenCandidateWithSameIdIsArtTrack() {
        val atvTrack = song("TRACK_ATV", "Astaga Bercanda", "Akbar Chalay", type = "MUSIC_VIDEO_TYPE_ATV", duration = 240)
        val candidates =
            listOf(
                song("TRACK_ATV", "Astaga Bercanda", "Akbar Chalay", "MUSIC_VIDEO_TYPE_ATV", duration = 240),
                song("MVID_OMV", "Astaga Bercanda", "Akbar Chalay", "MUSIC_VIDEO_TYPE_OMV", duration = 240),
            )
        assertEquals("MVID_OMV", MusicVideoMatcher.findBestMatch(atvTrack, candidates)?.id)
    }
}
