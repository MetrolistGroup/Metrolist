package com.metrolist.shared.playlistimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class UniversalPlaylistParserTest {
    @Test
    fun onlyExplicitFileTitlesBecomeCustomMetadata() {
        val inputs =
            listOf(
                """[{"title":"Renamed song","videoId":"abcdefghijk"},{"videoId":"bcdefghijkl"}]""",
                "title,url\nRenamed song,abcdefghijk\n,bcdefghijkl",
                "#EXTM3U\n#EXTINF:-1,Artist - Renamed song\nhttps://youtu.be/abcdefghijk\nhttps://youtu.be/bcdefghijkl",
            )
        for (input in inputs) {
            val tracks = UniversalPlaylistParser.parse(input)
            assertEquals(listOf("Renamed song", null), tracks.map { it.customTitle })
        }
        val text = UniversalPlaylistParser.parse("Artist - Renamed song\nhttps://youtu.be/abcdefghijk\nbcdefghijkl")
        assertEquals(listOf("Renamed song", null, null), text.map { it.customTitle })
        val local = UniversalPlaylistParser.parse("#EXTM3U\nfile:///music/filename.mp3")
        assertNull(local.single().customTitle)
    }

    @Test
    fun parsesJsonTrackList() {
        val jsonInput =
            """
            [
                {"title": "Bohemian Rhapsody", "artist": "Queen"},
                {"name": "Hotel California", "artist": "Eagles", "videoId": "abcdefghijk"},
                {"trackName": "Stairway to Heaven", "artists": [{"name": "Led Zeppelin"}]}
            ]
            """.trimIndent()

        val tracks = UniversalPlaylistParser.parse(jsonInput)
        assertEquals(3, tracks.size)
        assertEquals("Bohemian Rhapsody", tracks[0].title)
        assertEquals("Queen", tracks[0].artist)
        assertEquals("Hotel California", tracks[1].title)
        assertEquals("abcdefghijk", tracks[1].videoId)
        assertEquals("Stairway to Heaven", tracks[2].title)
        assertEquals("Led Zeppelin", tracks[2].artist)
    }

    @Test
    fun parsesM3uFileWithExtInf() {
        val m3uInput =
            """
            #EXTM3U
            #EXTINF:354,Queen - Bohemian Rhapsody
            https://music.youtube.com/watch?v=fJ9rUzIMcZQ
            #EXTINF:390,Eagles - Hotel California
            /storage/emulated/0/Music/hotel_california.mp3
            """.trimIndent()

        val tracks = UniversalPlaylistParser.parse(m3uInput)
        assertEquals(2, tracks.size)
        assertEquals("Bohemian Rhapsody", tracks[0].title)
        assertEquals("Queen", tracks[0].artist)
        assertEquals("fJ9rUzIMcZQ", tracks[0].videoId)
        assertEquals("Hotel California", tracks[1].title)
        assertEquals("Eagles", tracks[1].artist)
    }

    @Test
    fun parsesM3uBomUtf16AndDoesNotTreatLocalNamesAsVideoIds() {
        val input =
            "#EXTM3U\r\n#EXT-X-VERSION:3\r\n#EXTINF:-1,Custom title\r\n" +
                "../Music/abcdefghijk.mp3\r\n#EXTINF:-1,Artist - YouTube song\r\n" +
                "https://www.youtube.com/watch?list=PL1234567890&v=dQw4w9WgXcQ\r\n"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + input.toByteArray(Charsets.UTF_16LE)

        val tracks = UniversalPlaylistParser.parse(readPlaylistText(ByteArrayInputStream(utf16)))

        assertEquals(2, tracks.size)
        assertEquals("Custom title", tracks[0].title)
        assertNull(tracks[0].videoId)
        assertEquals("YouTube song", tracks[1].title)
        assertEquals("Artist", tracks[1].artist)
        assertEquals("dQw4w9WgXcQ", tracks[1].videoId)
        assertNull(UniversalPlaylistParser.extractVideoId("https://example.invalid/watch?v=dQw4w9WgXcQ"))
        assertNull(UniversalPlaylistParser.extractVideoId("file://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(UniversalPlaylistParser.extractVideoId("http:/watch?v=dQw4w9WgXcQ"))

        val csv = UniversalPlaylistParser.parse("title,artist,url\nLocal song,Local artist,file:///music/song.mp3?v=dQw4w9WgXcQ")
        assertEquals(1, csv.size)
        assertNull(csv.single().videoId)

        val relative = UniversalPlaylistParser.parse("#EXTM3U\nrelative/my+song.mp3")
        assertEquals("my+song", relative.single().title)
    }

    @Test
    fun resolvesM3uFilesWithoutSearchingForLookalikeYouTubeIds() {
        val tracks =
            UniversalPlaylistParser.parse(
                "#EXTM3U\n#EXTINF:-1,Local song\n../music/abcdefghijk.mp3\nfile:///music/dQw4w9WgXcQ.flac",
            ) { reference ->
                "file:///resolved/${reference.substringAfterLast('/')}"
            }

        assertEquals(2, tracks.size)
        assertNull(tracks[0].videoId)
        assertEquals("file:///resolved/abcdefghijk.mp3", tracks[0].directReference)
        assertTrue(tracks[0].isResolvedLocalReference)
        assertNull(tracks[1].videoId)
        assertTrue(tracks[1].isResolvedLocalReference)
    }

    @Test
    fun boundedReaderHandlesUtf16WithoutBomAndRejectsMalformedOrOversizedInput() {
        val utf16 = "#EXTM3U\nArtist - Song".toByteArray(Charsets.UTF_16LE)
        assertEquals("#EXTM3U\nArtist - Song", readPlaylistText(ByteArrayInputStream(utf16)))

        org.junit.Assert.assertThrows(Exception::class.java) {
            readPlaylistText(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
        }
        org.junit.Assert.assertThrows(Exception::class.java) {
            readPlaylistText(ByteArrayInputStream("too long".toByteArray()), maxCharacters = 3)
        }
    }

    @Test
    fun parsesPlainTextTracklist() {
        val textInput =
            """
            1. Queen - Bohemian Rhapsody
            2. "Hotel California" by Eagles
            3. Pink Floyd - Comfortably Numb
            4. https://music.youtube.com/watch?v=dQw4w9WgXcQ
            """.trimIndent()

        val tracks = UniversalPlaylistParser.parse(textInput)
        assertEquals(4, tracks.size)
        assertEquals("Bohemian Rhapsody", tracks[0].title)
        assertEquals("Queen", tracks[0].artist)
        assertEquals("Hotel California", tracks[1].title)
        assertEquals("Eagles", tracks[1].artist)
        assertEquals("Comfortably Numb", tracks[2].title)
        assertEquals("Pink Floyd", tracks[2].artist)
        assertEquals("dQw4w9WgXcQ", tracks[3].videoId)
    }

    @Test
    fun retainsProviderReferencesAndTheirSearchMetadata() {
        val tracks =
            UniversalPlaylistParser.parse(
                "title,artist,uri\n" +
                    "Spotify song,Spotify artist,spotify:track:abc123\n" +
                    "Apple song,Apple artist,https://music.apple.com/us/song/example/123\n" +
                    "Deezer song,Deezer artist,https://www.deezer.com/track/456",
            )

        assertEquals(listOf("Spotify song", "Apple song", "Deezer song"), tracks.map { it.title })
        assertEquals(listOf("Spotify artist", "Apple artist", "Deezer artist"), tracks.map { it.artist })
        assertEquals(
            listOf(
                "spotify:track:abc123",
                "https://music.apple.com/us/song/example/123",
                "https://www.deezer.com/track/456",
            ),
            tracks.map { it.directReference },
        )
        assertTrue(tracks.none { it.isResolvedLocalReference })
    }

    @Test
    fun parsesTabAndSemicolonDelimitedLists() {
        val tsvInput =
            """
            Track	Artist
            Song A	Artist A
            Song B	Artist B
            """.trimIndent()

        val tsvTracks = UniversalPlaylistParser.parse(tsvInput)
        assertEquals(2, tsvTracks.size)
        assertEquals("Song A", tsvTracks[0].title)
        assertEquals("Artist A", tsvTracks[0].artist)

        val csvSemicolon =
            """
            Song X;Artist X
            Song Y;Artist Y
            """.trimIndent()

        val semiTracks = UniversalPlaylistParser.parse(csvSemicolon)
        assertEquals(2, semiTracks.size)
        assertEquals("Song X", semiTracks[0].title)
        assertEquals("Artist X", semiTracks[0].artist)
    }

    @Test
    fun detectsProviderUrlsCorrectly() {
        assertEquals(
            ImportSourceType.SPOTIFY,
            UnifiedPlaylistImporter.detectSource("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"),
        )
        assertEquals(
            ImportSourceType.APPLE_MUSIC,
            UnifiedPlaylistImporter.detectSource("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb"),
        )
        assertEquals(
            ImportSourceType.DEEZER,
            UnifiedPlaylistImporter.detectSource("https://www.deezer.com/playlist/3155776842"),
        )
        assertEquals(
            ImportSourceType.YOUTUBE,
            UnifiedPlaylistImporter.detectSource("https://music.youtube.com/playlist?list=RDCLAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56sY"),
        )
        assertEquals(
            ImportSourceType.TIDAL,
            UnifiedPlaylistImporter.detectSource("https://tidal.com/browse/playlist/12345"),
        )
        assertEquals(
            ImportSourceType.AMAZON,
            UnifiedPlaylistImporter.detectSource("https://music.amazon.com/playlists/B012345"),
        )
    }

    @Test
    fun extractsAppleMusicUserTokenFromCookieHeader() {
        val cookie1 = "media-user-token=AgAAAA...xyz; other-cookie=123"
        assertEquals("AgAAAA...xyz", AppleMusicPlaylistImporter.extractAppleMusicUserToken(cookie1))

        val cookie2 = "itunes=test; media-user-token=token_abc_123"
        assertEquals("token_abc_123", AppleMusicPlaylistImporter.extractAppleMusicUserToken(cookie2))

        val cookieNone = "session=xyz; token=abc"
        assertEquals(null, AppleMusicPlaylistImporter.extractAppleMusicUserToken(cookieNone))

        assertEquals(null, AppleMusicPlaylistImporter.extractAppleMusicUserToken(null))
        assertEquals(null, AppleMusicPlaylistImporter.extractAppleMusicUserToken(""))
    }

    @Test
    fun handlesLargeTracklistSafely() {
        val largeBuilder = StringBuilder()
        for (i in 1..15_000) {
            largeBuilder.append("Artist $i - Song $i\n")
        }
        val tracks = UniversalPlaylistParser.parse(largeBuilder.toString())
        assertEquals(15_000, tracks.size)
        assertEquals("Song 10000", tracks[9999].title)
        assertEquals("Artist 10000", tracks[9999].artist)
    }
}
