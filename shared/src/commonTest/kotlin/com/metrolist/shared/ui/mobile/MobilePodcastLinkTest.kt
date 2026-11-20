package com.metrolist.shared.ui.mobile

import com.metrolist.shared.utils.ShareUrls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MobilePodcastLinkTest {
    @Test
    fun copiedPodcastLinksOpenDedicatedPodcastRoute() {
        val link = ShareUrls.podcast("MPSP_example")
        val route = MobileRoute.Podcast("MPSP_example")

        assertEquals(route, resolveMobilePodcastBrowseLink(link))
        assertEquals(MobileDeepLink.Destination(route), resolveMobileDeepLink(link))
        assertEquals(
            MobileDeepLink.Destination(route),
            resolveMobileDeepLink("https://metrolist.cc/open?url=https%3A%2F%2Fmusic.youtube.com%2Fbrowse%2FMPSP_example"),
        )
        assertEquals(
            MobileDeepLink.Destination(route),
            resolveMobileDeepLink("metrolist://open?url=https%3A%2F%2Fmusic.youtube.com%2Fbrowse%2FMPSP_example"),
        )
    }

    @Test
    fun explicitCustomRoutesKeepTheirDestinationAndIdPrecedence() {
        assertEquals(
            MobileDeepLink.Destination(MobileRoute.Album("MPSP_example", "")),
            resolveMobileDeepLink("metrolist://album/MPSP_example"),
        )
        assertEquals(
            MobileDeepLink.Destination(MobileRoute.Browse(MobileBrowseDestination("other", null, ""))),
            resolveMobileDeepLink("metrolist://browse/MPSP_example?id=other"),
        )
        assertNull(resolveMobilePodcastBrowseLink("metrolist://album/MPSP_example"))
    }

    @Test
    fun nonPodcastAndInvalidBrowseLinksDoNotBecomePodcastRoutes() {
        assertNull(resolveMobilePodcastBrowseLink("https://music.youtube.com/browse/MPREb_album"))
        assertNull(resolveMobilePodcastBrowseLink("https://example.org/browse/MPSP_example"))
        assertNull(resolveMobilePodcastBrowseLink("https://music.youtube.com/browse/MPSP_example/extra"))
    }
}
