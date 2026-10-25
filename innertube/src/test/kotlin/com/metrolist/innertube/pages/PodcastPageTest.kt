package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import com.metrolist.innertube.models.Icon
import com.metrolist.innertube.models.Menu
import com.metrolist.innertube.models.MusicMultiRowListItemRenderer
import com.metrolist.innertube.models.NavigationEndpoint
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastPageTest {
    @Test
    fun `episode credits require a trusted artist or podcast link`() {
        val podcast =
            Run(
                "Known Show",
                NavigationEndpoint(
                    browseEndpoint =
                        BrowseEndpoint(
                            browseId = "MPSPshow",
                            browseEndpointContextSupportedConfigs =
                                BrowseEndpointContextSupportedConfigs(
                                    BrowseEndpointContextMusicConfig(MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE),
                                ),
                        ),
                ),
            )
        val ambiguous = listOf(Run("canción", null), Run(" • ", null), Run("14 m reproducciones", null))
        val episode =
            MusicMultiRowListItemRenderer(
                title = Runs(listOf(Run("Episode", null))),
                subtitle = null,
                secondSubtitle = Runs(ambiguous),
                secondarySubtitle = null,
                thumbnail = null,
                onTap = null,
                playbackProgress = null,
                displayStyle = null,
                menu = null,
            )

        assertEquals(emptyList<Artist>(), PodcastPage.extractPodcastArtists(episode))
        assertEquals(emptyList<Artist>(), PodcastPage.extractPodcastArtists(episode, listOf(Run("New episodes", null))))
        assertEquals(listOf(Artist("Known Show", null)), PodcastPage.extractPodcastArtists(episode, listOf(podcast)))
        assertEquals(
            listOf(Artist("Known Show", null)),
            PodcastPage.extractPodcastArtists(episode.copy(secondarySubtitle = Runs(listOf(podcast)))),
        )
        val linkedArtist = Run("Song & Dance", NavigationEndpoint(browseEndpoint = BrowseEndpoint("UCartist")))
        assertEquals(
            listOf(Artist("Song & Dance", "UCartist")),
            PodcastPage.extractPodcastArtists(episode.copy(secondSubtitle = Runs(listOf(linkedArtist)))),
        )
    }

    @Test
    fun `saved episodes retain the channel byline without assigning podcast or album ids to artists`() {
        val channel = Run("Known Show & Friends", null)
        val podcast =
            Run(
                "Known Show",
                NavigationEndpoint(
                    browseEndpoint =
                        BrowseEndpoint(
                            browseId = "MPSPshow",
                            browseEndpointContextSupportedConfigs =
                                BrowseEndpointContextSupportedConfigs(
                                    BrowseEndpointContextMusicConfig(MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE),
                                ),
                        ),
                ),
            )
        val album = Run("Album", NavigationEndpoint(browseEndpoint = BrowseEndpoint("MPREb_album")))
        val metadata = listOf(Run(" • ", null), Run("3,3 M reproducciones", null))

        assertEquals(listOf(Artist(channel.text, null)), PodcastPage.extractPodcastByline(listOf(channel) + metadata))
        assertEquals(listOf(Artist(podcast.text, null)), PodcastPage.extractPodcastByline(listOf(podcast) + metadata))
        assertEquals(listOf(Artist(channel.text, null)), PodcastPage.extractPodcastByline(listOf(channel, Run(" • ", null), album)))
        assertEquals(emptyList<Artist>(), PodcastPage.extractPodcastByline(listOf(album)))
        val linked = Run("21 Savage and Song", NavigationEndpoint(browseEndpoint = BrowseEndpoint("UCartist")))
        assertEquals(
            listOf(Artist(linked.text, "UCartist")),
            PodcastPage.extractPodcastByline(listOf(channel, Run(" • ", null), linked)),
        )
        assertEquals(emptyList<Artist>(), PodcastPage.extractPodcastByline(null))
    }

    @Test
    fun `menu podcast link validates a plain show byline without using menu label as artist`() {
        val podcastMenu =
            Menu(
                Menu.MenuRenderer(
                    items =
                        listOf(
                            Menu.MenuRenderer.Item(
                                menuNavigationItemRenderer =
                                    Menu.MenuRenderer.Item.MenuNavigationItemRenderer(
                                        text = Runs(listOf(Run("Go to podcast", null))),
                                        icon = Icon("PODCAST"),
                                        navigationEndpoint =
                                            NavigationEndpoint(
                                                browseEndpoint =
                                                    BrowseEndpoint(
                                                        browseId = "MPSPshow",
                                                        browseEndpointContextSupportedConfigs =
                                                            BrowseEndpointContextSupportedConfigs(
                                                                BrowseEndpointContextMusicConfig(MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE),
                                                            ),
                                                    ),
                                            ),
                                    ),
                                menuServiceItemRenderer = null,
                                toggleMenuServiceItemRenderer = null,
                            ),
                        ),
                    topLevelButtons = null,
                ),
            )
        val episode =
            MusicMultiRowListItemRenderer(
                title = Runs(listOf(Run("Episode", null))),
                subtitle = null,
                secondSubtitle = Runs(listOf(Run("Known Show", null))),
                secondarySubtitle = null,
                thumbnail = null,
                onTap = null,
                playbackProgress = null,
                displayStyle = null,
                menu = podcastMenu,
            )

        assertEquals(listOf(Artist("Known Show", null)), PodcastPage.extractPodcastArtists(episode))
        assertEquals(emptyList<Artist>(), PodcastPage.extractPodcastArtists(episode.copy(menu = null)))
        assertEquals(
            emptyList<Artist>(),
            PodcastPage.extractPodcastArtists(
                episode.copy(secondSubtitle = Runs(listOf(Run("canción", null), Run("14 m reproducciones", null)))),
            ),
        )
    }
}
