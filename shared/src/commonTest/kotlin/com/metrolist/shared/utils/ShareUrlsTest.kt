package com.metrolist.shared.utils

import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.listentogether.parseListenTogetherInvitation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ShareUrlsTest {
    @Test
    fun songsAndVideosUseMusicWatchLinks() {
        for (id in listOf("R93xV3v718s", "IgxYypxZDTQ")) {
            val url = ShareUrls.song(id)
            assertEquals("https://music.youtube.com/watch?v=$id", url)
            assertEquals(id, assertIs<YouTubeUrlParser.ParsedUrl.Video>(parseYouTubeUrl(url)).id)
        }
    }

    @Test
    fun timestampCopyLinksKeepTheirPosition() {
        val url = ShareUrls.song("R93xV3v718s", startSeconds = 143)
        assertEquals("https://music.youtube.com/watch?v=R93xV3v718s&t=143s", url)
        assertEquals(143_000L, parseYouTubeStartPositionMs(url))
        assertEquals("https://music.youtube.com/watch?v=R93xV3v718s&t=0s", ShareUrls.song("R93xV3v718s", startSeconds = -1))
    }

    @Test
    fun playlistsRemoveTheBrowsePrefix() {
        for (id in listOf("PL1234567890abcdef", "VLPL1234567890abcdef")) {
            val url = ShareUrls.playlist(id)
            assertEquals("https://music.youtube.com/playlist?list=PL1234567890abcdef", url)
            assertEquals("PL1234567890abcdef", assertIs<YouTubeUrlParser.ParsedUrl.Playlist>(parseYouTubeUrl(url)).id)
        }
    }

    @Test
    fun albumsUseMusicBrowseLinks() {
        val url = ShareUrls.album("MPREb_1234567890")
        assertEquals("https://music.youtube.com/browse/MPREb_1234567890", url)
        assertEquals("MPREb_1234567890", assertIs<YouTubeUrlParser.ParsedUrl.Album>(parseYouTubeUrl(url)).id)
    }

    @Test
    fun artistsUseMusicChannelLinks() {
        val url = ShareUrls.artist("UC1234567890abcdef")
        assertEquals("https://music.youtube.com/channel/UC1234567890abcdef", url)
        assertEquals("UC1234567890abcdef", assertIs<YouTubeUrlParser.ParsedUrl.Artist>(parseYouTubeUrl(url)).id)
    }

    @Test
    fun podcastsAndEpisodesKeepDistinctRoutes() {
        val showUrl = ShareUrls.podcast("MPSP_show")
        assertEquals("https://music.youtube.com/browse/MPSP_show", showUrl)
        assertEquals("MPSP_show", parseYouTubePodcastId(showUrl))
        val episodeUrl = ShareUrls.song("R93xV3v718s")
        assertEquals("https://music.youtube.com/watch?v=R93xV3v718s", episodeUrl)
        assertEquals("R93xV3v718s", assertIs<YouTubeUrlParser.ParsedUrl.Video>(parseYouTubeUrl(episodeUrl)).id)
    }

    @Test
    fun roomInvitationsKeepTheProductionHost() {
        val url = ShareUrls.party("ROOM1234", usePartyParam = false)
        assertEquals("https://metrolist.cc/listen?code=ROOM1234", url)
        assertEquals("ROOM1234", parseListenTogetherInvitation(url))
        val partyUrl = ShareUrls.party("ROOM1234")
        assertEquals("https://metrolist.cc/party?r=ROOM1234", partyUrl)
        assertEquals("ROOM1234", extractListenTogetherRoomCode(partyUrl))
    }
}
