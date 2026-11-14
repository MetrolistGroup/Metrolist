package com.metrolist.shared.playlistimport

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistImportCommonTest {
    @Test
    fun matchingRejectsDifferentRecordingsAndUsesAlbumAndDurationToRankCandidates() {
        val track = PlaylistImportManager.CsvTrack("Song!", "Artist", null, album = "Album", durationSeconds = 180)
        val exact = SongItem("exact", "Song", null, listOf(ArtistTiny(null, "Artist")), AlbumTiny(null, "Album"), 180)
        val wrongTitle = exact.copy(id = "wrong-title", title = "Song live")
        val wrongArtist = exact.copy(id = "wrong-artist", artists = listOf(ArtistTiny(null, "Cover band")))
        val wrongDuration = exact.copy(id = "wrong-duration", duration = 240)
        val otherAlbum = exact.copy(id = "other-album", album = AlbumTiny(null, "Compilation"))
        assertEquals(exact, PlaylistImportManager.bestTrackMatch(track, listOf(wrongTitle, wrongArtist, wrongDuration, otherAlbum, exact)))
        assertEquals(null, PlaylistImportManager.bestTrackMatch(track, listOf(wrongTitle, wrongArtist, wrongDuration)))
        assertEquals(
            exact,
            PlaylistImportManager.bestTrackMatch(track.copy(artist = "", album = "", durationSeconds = null), listOf(wrongTitle, exact)),
        )
    }

    @Test
    fun matchingAllowsDestinationMetadataToListOnlyOneOfSeveralSourceArtists() {
        val track = PlaylistImportManager.CsvTrack("Collaboration", "Artist one, Artist two", null, durationSeconds = 180)
        val destinationSong =
            SongItem("match", "Collaboration", null, listOf(ArtistTiny(null, "Artist one")), null, 180)

        assertEquals(destinationSong, PlaylistImportManager.bestTrackMatch(track, listOf(destinationSong)))
        assertEquals(
            null,
            PlaylistImportManager.bestTrackMatch(
                track,
                listOf(destinationSong.copy(artists = listOf(ArtistTiny(null, "Different artist")))),
            ),
        )
    }

    @Test
    fun parsesPortablePlaylistTextAndRejectsMalformedFiles() {
        val content = "Artist - Song"
        val utf16 =
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                ByteArray(content.length * 2).also { output ->
                    content.forEachIndexed { index, character ->
                        output[index * 2] = character.code.toByte()
                        output[index * 2 + 1] = (character.code shr 8).toByte()
                    }
                }

        assertEquals(content, decodePlaylistText(utf16))
        assertEquals("Artist" to "Song", UniversalPlaylistParser.parse(content).single().let { it.artist to it.title })
        assertFails { decodePlaylistText(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertFails { decodePlaylistText(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0xD8.toByte())) }
        assertFails { decodePlaylistText("oversized".encodeToByteArray(), maxCharacters = 2) }
    }

    @Test
    fun unresolvableLocalPlaylistReferencesAreNotMarkedImportable() {
        val error =
            assertFailsWith<ImportException> {
                UniversalPlaylistParser.parseImportable("#EXTM3U\n#EXTINF:-1,Artist - Song\n../Music/song.mp3")
            }
        assertEquals(ImportErrorKey.UnsupportedPlaylistEntries, error.errorKey)
    }

    @Test
    fun providerUrlsRemainAvailableFromCommonSources() {
        val providers =
            listOf(
                "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M" to ImportSourceType.SPOTIFY,
                "https://music.apple.com/gb/playlist/name/pl.abc123" to ImportSourceType.APPLE_MUSIC,
                "https://www.deezer.com/playlist/3155776842" to ImportSourceType.DEEZER,
                "https://music.youtube.com/playlist?list=PL1234567890" to ImportSourceType.YOUTUBE,
                "https://tidal.com/browse/playlist/12345" to ImportSourceType.TIDAL,
                "https://music.amazon.co.uk/playlists/B012345678" to ImportSourceType.AMAZON,
            )

        providers.forEach { (url, expected) ->
            assertEquals(expected, UnifiedPlaylistImporter.detectSource(url))
        }
        assertTrue(SpotifyUrlPlaylistImporter.canHandle(providers.first().first))
        val spotifyId = providers.first().first.substringAfterLast('/')
        assertEquals(ImportSourceType.SPOTIFY, UnifiedPlaylistImporter.detectSource(spotifyId))
        assertTrue(SpotifyUrlPlaylistImporter.canHandle(spotifyId))
        assertFalse(UnifiedPlaylistImporter.isYouTubePlaylistInput(spotifyId))
        assertEquals(ImportSourceType.UNKNOWN, UnifiedPlaylistImporter.detectSource("3155776842"))
        assertEquals(ImportSourceType.UNKNOWN, UnifiedPlaylistImporter.detectSource("1984"))
        assertEquals(ImportSourceType.UNKNOWN, UnifiedPlaylistImporter.detectSource("12345678901"))
        assertEquals(ImportSourceType.AMAZON, UnifiedPlaylistImporter.detectSource("B012345678"))
        assertFalse(DeezerPlaylistImporter.canHandle("3155776842"))
        assertFalse(UnifiedPlaylistImporter.isYouTubePlaylistInput("3155776842"))
        assertFalse(UnifiedPlaylistImporter.isYouTubePlaylistInput("12345678901"))
        assertFalse(UnifiedPlaylistImporter.isYouTubePlaylistInput("Thunderstruck"))
        assertEquals("Thunderstruck", UniversalPlaylistParser.parse("Thunderstruck").single().title)
        assertFalse(UnifiedPlaylistImporter.isYouTubePlaylistInput("B012345678"))
        assertTrue(UnifiedPlaylistImporter.isYouTubePlaylistInput("PL1234567890"))
        assertTrue(UnifiedPlaylistImporter.isYouTubePlaylistInput(providers[3].first))
        assertFalse(UnifiedPlaylistImporter.isYouTubePlaylistInput("https://example.com/playlist?list=PL1234567890"))
        assertTrue(AppleMusicPlaylistImporter.canHandle(providers[1].first))
        assertTrue(DeezerPlaylistImporter.canHandle(providers[2].first))
        assertTrue(TidalPlaylistImporter.canHandle(providers[4].first))
        assertTrue(AmazonMusicPlaylistImporter.canHandle(providers[5].first))
    }

    @Test
    fun unresolvedTracksFollowMatchesRatherThanSourceIds() {
        val spotifyMatched = PlaylistImportManager.CsvTrack("Song one", "Artist one", null, "spotify:track:abc")
        val searchMissed = PlaylistImportManager.CsvTrack("Song two", "Artist two", null)
        val youtubeMatched = PlaylistImportManager.CsvTrack("", "", "abcdefghijk")
        val duplicate = PlaylistImportManager.CsvTrack("Song four", "Artist four", null)
        val tracks = listOf(spotifyMatched, searchMissed, youtubeMatched, duplicate, duplicate)
        val matches =
            listOf(
                spotifyMatched to SongItem("searchResult", "Song one", null, emptyList(), null),
                youtubeMatched to SongItem("abcdefghijk", "Song three", null, emptyList(), null),
                duplicate to SongItem("duplicateResult", "Song four", null, emptyList(), null),
            )

        assertEquals(listOf(searchMissed, duplicate), UnifiedPlaylistImporter.unresolvedTracks(tracks, matches))
    }
}
