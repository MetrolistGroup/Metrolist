package com.metrolist.desktop.state

import com.metrolist.desktop.state.content.DesktopSearchLink
import com.metrolist.desktop.state.content.resolveDesktopSearchLink
import com.metrolist.innertubex.utils.YouTubeUrlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DesktopSearchLinkTest {
    @Test
    fun normalAndSharedPodcastUrlsOpenThePodcast() {
        for (
        url in listOf(
            "https://music.youtube.com/browse/MPSP_show?si=shared",
            "metrolist://browse/MPSP_show",
            "https://metrolist.cc/browse/MPSP_show",
        )
        ) {
            assertEquals(DesktopSearchLink.Podcast("MPSP_show"), resolveDesktopSearchLink(url))
        }
    }

    @Test
    fun existingMediaLinksKeepTheirDestinations() {
        fun media(url: String) = assertIs<DesktopSearchLink.Media>(resolveDesktopSearchLink(url)).destination

        assertIs<YouTubeUrlParser.ParsedUrl.Video>(media("https://music.youtube.com/watch?v=R93xV3v718s"))
        assertIs<YouTubeUrlParser.ParsedUrl.Playlist>(media("https://music.youtube.com/playlist?list=PL1234567890abcdef"))
        assertIs<YouTubeUrlParser.ParsedUrl.Album>(media("https://music.youtube.com/browse/MPREb_1234567890"))
        assertIs<YouTubeUrlParser.ParsedUrl.Artist>(media("https://music.youtube.com/channel/UC1234567890abcdef"))
    }

    @Test
    fun onlyRecognizedUrlsAreOpenedDirectly() {
        assertNull(resolveDesktopSearchLink("podcasts about music"))
        assertNull(resolveDesktopSearchLink("https://youtube.com.example/browse/MPSP_show"))
    }
}
