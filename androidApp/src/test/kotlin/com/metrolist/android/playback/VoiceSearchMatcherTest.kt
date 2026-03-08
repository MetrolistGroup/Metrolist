package com.metrolist.android.playback

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchMatcherTest {
    private fun song(
        title: String,
        artist: String = "Muse",
        album: String? = null,
    ) = SongItem(title + artist, title, null, listOf(ArtistTiny(null, artist)), album?.let { AlbumTiny("album", it) })

    private fun matchRequest(
        query: String,
        candidates: List<SongItem>,
    ) = VoiceSearchRequest(query).bestMatch(candidates)

    @Test
    fun exactTitlesRankAbovePartialMatchesAndKeepStableTies() {
        val live = song("Starlight Live")
        val exact = song("Starlight")
        val duplicate = exact.copy(id = "other")
        assertSame(exact, matchRequest("starlight", listOf(live, exact, duplicate)))
        assertEquals(
            listOf(exact, duplicate, live),
            VoiceSearchMatcher.rankAll("starlight", listOf(live, exact, duplicate)).map { it.song },
        )
    }

    @Test
    fun matchingNormalizesUnicodePunctuationAndWhitespace() {
        val candidate = song("Żółta łódź — 東京")
        assertSame(candidate, matchRequest("  ŻÓŁTA\tłódź\n東京 ", listOf(candidate)))
        assertNull(matchRequest(" -- ", listOf(candidate)))
    }

    @Test
    fun matchingStripsArtistAndAcceptsSpeechTypos() {
        val candidate = song("Starlight")
        assertSame(candidate, matchRequest("Muse Starlight", listOf(candidate)))
        assertSame(candidate, matchRequest("Starlite", listOf(candidate)))
        assertNull(matchRequest("Muse", listOf(candidate)))
        assertNull(matchRequest("Completely unrelated", listOf(candidate)))
    }

    @Test
    fun shortQueriesNeedExactTitles() {
        assertNull(matchRequest("One", listOf(song("One More Time"))))
        val one = song("One")
        assertSame(one, matchRequest("One", listOf(one)))
        assertNull(matchRequest("", listOf(one)))
    }

    @Test
    fun focusedSongUsesTitleAndDisambiguatesArtistAndAlbum() {
        val request = VoiceSearchRequest("play something", VoiceSearchFocus.SONG, "Muse", "Black Holes", "Starlight")
        val right = song("Starlight", album = "Black Holes")
        val wrongArtist = song("Starlight", "Other", "Black Holes")
        val wrongAlbum = song("Starlight", album = "Live")
        assertEquals("Starlight Muse", request.searchQuery)
        assertSame(right, request.bestMatch(listOf(wrongArtist, wrongAlbum, right)))
        assertNull(request.bestMatch(listOf(wrongArtist, wrongAlbum)))
    }

    @Test
    fun focusSelectsOnlyRequestedKindWithSameName() {
        val track = song("Starlight")
        val artist = ArtistItem("artist", "Starlight", null)
        val album = AlbumItem("album", "Starlight", null, track.artists)
        val playlist = PlaylistItem("playlist", "Starlight", null)
        val candidates = listOf(track, artist, album, playlist)
        assertSame(artist, VoiceSearchRequest("Starlight", VoiceSearchFocus.ARTIST).bestMatch(candidates))
        assertSame(album, VoiceSearchRequest("Starlight", VoiceSearchFocus.ALBUM).bestMatch(candidates))
        assertSame(playlist, VoiceSearchRequest("Starlight", VoiceSearchFocus.PLAYLIST).bestMatch(candidates))
        assertNull(VoiceSearchRequest("Starlight", VoiceSearchFocus.PLAYLIST).bestMatch(listOf(track)))
    }

    @Test
    fun artistOnlyQueryDoesNotChooseAnArbitraryTrack() {
        val artist = ArtistItem("artist", "Muse", null)
        val request = VoiceSearchRequest("Muse")
        assertNull(request.bestMatch(listOf(song("Starlight"))))
        assertSame(artist, request.bestMatch(listOf(song("Starlight"), artist)))
    }

    @Test
    fun collectionExtrasOverrideQueryAndMissingExtrasUseQuery() {
        assertEquals("Muse", VoiceSearchRequest("anything", VoiceSearchFocus.ARTIST, artist = "Muse").matchQuery)
        assertEquals(
            "Black Holes Muse",
            VoiceSearchRequest("anything", VoiceSearchFocus.ALBUM, artist = "Muse", album = "Black Holes").searchQuery,
        )
        assertEquals("Road Trip", VoiceSearchRequest("anything", VoiceSearchFocus.PLAYLIST, playlist = "Road Trip").matchQuery)
        assertEquals("Starlight", VoiceSearchRequest(" Starlight ", VoiceSearchFocus.SONG).matchQuery)
        assertTrue(VoiceSearchRequest("   ").matchQuery.isBlank())
        assertNull(VoiceSearchRequest("unrelated").bestMatch(emptyList<SongItem>()))
    }

    @Test
    fun assistantQueueAccessRequiresVerifiedConfiguredPackage() {
        val packages = setOf("configured.assistant")
        assertTrue(isVoiceSearchController("configured.assistant", true, packages))
        assertFalse(isVoiceSearchController("configured.assistant", false, packages))
        assertFalse(isVoiceSearchController("unrelated.app", true, packages))
        assertFalse(isVoiceSearchController("configured.assistant", true, emptySet()))
    }
}
