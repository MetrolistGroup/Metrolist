package com.metrolist.music.utils

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.*
import org.junit.Test

class SpotifyTrackMatcherTest {
    private val track = SpotifyLikedTrack("spotify", "Déjà Vu", listOf("Artist"), 200, false)
    private fun candidate(title: String = "Deja Vu", artist: String = "Artist", duration: Int = 201, explicit: Boolean = false) =
        SongItem("youtube", title, listOf(Artist(artist, null)), duration = duration, thumbnail = "", explicit = explicit)

    @Test fun matchesAccentAndPunctuationDifferences() {
        assertNotNull(SpotifyTrackMatcher.match(track, listOf(candidate("DEJA-VU"))))
    }
    @Test fun skipsWrongArtistVersionDurationAndExplicitness() {
        assertNull(SpotifyTrackMatcher.match(track, listOf(candidate(artist = "Cover artist"))))
        assertNull(SpotifyTrackMatcher.match(track, listOf(candidate(title = "Deja Vu Live"))))
        assertNull(SpotifyTrackMatcher.match(track, listOf(candidate(duration = 260))))
        assertNull(SpotifyTrackMatcher.match(track, listOf(candidate(explicit = true))))
    }
    @Test fun choosesClosestDuration() {
        val closest = candidate(duration = 200).copy(id = "closest")
        assertEquals("closest", SpotifyTrackMatcher.match(track, listOf(candidate(duration = 210), closest))?.id)
    }
    @Test fun matchesNonLatinNamesAndRejectsMissingArtist() {
        val hindi = track.copy(title = "तुम ही हो", artists = listOf("अरिजीत सिंह"))
        assertNotNull(SpotifyTrackMatcher.match(hindi, listOf(candidate(title = hindi.title, artist = hindi.artists.first()))))
        assertNull(SpotifyTrackMatcher.match(track.copy(title = "कल"), listOf(candidate(title = "काल"))))
        assertNull(SpotifyTrackMatcher.match(track.copy(artists = emptyList()), listOf(candidate())))
    }
}
