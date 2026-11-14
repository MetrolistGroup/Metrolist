package com.metrolist.shared.utils

import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.listentogether.parseListenTogetherInvitation
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.mobile.resolveMobileDeepLink
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubeUrlParsingTest {
    private var previousSettings: Settings? = null

    @BeforeTest
    fun setUp() {
        previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(previousSettings)

    @Test
    fun podcastBrowseLinksUseValidatedHostsAndDistinctIds() {
        listOf(
            "https://music.youtube.com/browse/MPSP_show?si=shared",
            "metrolist://browse/MPSP_show",
            "https://metrolist.cc/browse/MPSP_show",
        ).forEach {
            assertEquals("MPSP_show", parseYouTubePodcastId(it))
        }
        listOf(
            "https://youtube.com.example/browse/MPSP_show",
            "https://example.com/browse/MPSP_show",
            "https://music.youtube.com/browse/MPRE_album",
            "https://music.youtube.com/browse/MPSP_show/extra",
            "https://music.youtube.com/browse/MPSP",
        ).forEach {
            assertNull(parseYouTubePodcastId(it))
        }
    }

    @Test
    fun parsesWatchUrlWithTimestamp() {
        val parsed = parseYouTubeUrl("https://www.youtube.com/watch?v=R93xV3v718s&t=143s")

        assertEquals("R93xV3v718s", assertIs<YouTubeUrlParser.ParsedUrl.Video>(parsed).id)
    }

    @Test
    fun parsesSupportedTimestampsAndRejectsMalformedOrOverflowingValues() {
        val url = "https://youtu.be/R93xV3v718s"
        assertEquals(143_000L, parseYouTubeStartPositionMs("$url?t=143s"))
        assertEquals(143_000L, parseYouTubeStartPositionMs("$url?start=143"))
        assertEquals(3_723_000L, parseYouTubeStartPositionMs("$url?t=1h2m3s"))
        assertEquals(120_000L, parseYouTubeStartPositionMs("$url#t=2m"))
        for (timestamp in listOf("-1", "bad", "1m2h", "9223372036854775807h", "9223372036854775807")) {
            assertEquals(0L, parseYouTubeStartPositionMs("$url?t=$timestamp"))
        }
        assertEquals(0L, parseYouTubeStartPositionMs("https://example.org/watch?v=R93xV3v718s&t=60"))
    }

    @Test
    fun rejectsLookalikeHost() {
        assertNull(parseYouTubeUrl("https://youtube.com.example/watch?v=R93xV3v718s"))
    }

    @Test
    fun parsesMusicWatchUrl() {
        val parsed = parseYouTubeUrl("https://music.youtube.com/watch?v=R93xV3v718s")
        assertEquals("R93xV3v718s", assertIs<YouTubeUrlParser.ParsedUrl.Video>(parsed).id)
    }

    @Test
    fun parsesMusicPlaylistUrl() {
        val parsed = parseYouTubeUrl("https://music.youtube.com/playlist?list=PL1234567890abcdef")
        assertEquals("PL1234567890abcdef", assertIs<YouTubeUrlParser.ParsedUrl.Playlist>(parsed).id)
    }

    @Test
    fun parsesMusicChannelUrl() {
        val parsed = parseYouTubeUrl("https://music.youtube.com/channel/UC1234567890abcdef")
        assertEquals("UC1234567890abcdef", assertIs<YouTubeUrlParser.ParsedUrl.Artist>(parsed).id)
    }

    @Test
    fun parsesMusicAlbumBrowseUrl() {
        val parsed = parseYouTubeUrl("https://music.youtube.com/browse/MPREb_1234567890")
        assertEquals("MPREb_1234567890", assertIs<YouTubeUrlParser.ParsedUrl.Album>(parsed).id)
    }

    @Test
    fun parsesMusicWatchUrlWithRadioList() {
        val parsed = parseYouTubeUrl("https://music.youtube.com/watch?v=R93xV3v718s&list=RDAMVMR93xV3v718s")
        assertEquals("R93xV3v718s", assertIs<YouTubeUrlParser.ParsedUrl.Video>(parsed).id)
    }

    @Test
    fun parsesMetrolistCcWatchUrl() {
        val parsed = parseYouTubeUrl("https://metrolist.cc/watch?v=R93xV3v718s")
        assertEquals("R93xV3v718s", assertIs<YouTubeUrlParser.ParsedUrl.Video>(parsed).id)
    }

    @Test
    fun parsesMetrolistCustomScheme() {
        val parsed = parseYouTubeUrl("metrolist://watch?v=R93xV3v718s")
        assertEquals("R93xV3v718s", assertIs<YouTubeUrlParser.ParsedUrl.Video>(parsed).id)
    }

    @Test
    fun parsesMetrolistCustomSchemeNineDigitId() {
        val parsed = parseYouTubeUrl("metrolist://watch?v=123456789")
        assertEquals("123456789", assertIs<YouTubeUrlParser.ParsedUrl.Video>(parsed).id)
    }

    @Test
    fun extractsListenTogetherRoomCodes() {
        assertEquals("LISTENTOGETHERID", extractListenTogetherRoomCode("metrolist://party?r=listentogetherid"))
        assertEquals("LISTENTOGETHERID", extractListenTogetherRoomCode("https://metrolist.cc/party?r=listentogetherid"))
        assertEquals("ROOM1234", extractListenTogetherRoomCode("metrolist://party?r=ROOM1234"))
        assertEquals("ROOM1234", extractListenTogetherRoomCode("metrolist://listen?code=ROOM1234"))
        assertEquals("ROOM1234", extractListenTogetherRoomCode("https://metrolist.cc/listen?code=ROOM1234"))
        assertEquals("ROOM1234", extractListenTogetherRoomCode("metrolist://listen-together/ROOM1234"))
        assertTrue(isListenTogetherUrl("metrolist://party?r=listentogetherid"))
        assertNull(extractListenTogetherRoomCode("https://example.com/party?r=listentogetherid"))
        assertNull(extractListenTogetherRoomCode("not a url"))
    }

    @Test
    fun rejectsPreviewAndLoopbackLinks() {
        for (host in listOf(
            "localhost:8080",
            "127.0.0.1:8080",
            "feat-redesign2-metrosite.metrolist.workers.dev",
            "other.metrolist.workers.dev",
        )) {
            for (path in listOf(
                "watch?v=R93xV3v718s",
                "playlist?list=PL1234567890abcdef",
                "browse/MPSP_show",
                "channel/UC1234567890abcdef",
            )) {
                val url = "https://$host/$path"
                assertEquals(url, normalizeMetrolistShareUrl(url))
                assertNull(parseYouTubeUrl(url), url)
                assertNull(parseYouTubePodcastId(url), url)
                assertNull(resolveMobileDeepLink(url), url)
            }
            for (path in listOf("party?r=ROOM1234", "listen?code=ROOM1234")) {
                val url = "https://$host/$path"
                assertNull(extractListenTogetherRoomCode(url), url)
                assertNull(parseListenTogetherInvitation(url), url)
                assertNull(resolveMobileDeepLink(url), url)
            }
        }
    }
}
