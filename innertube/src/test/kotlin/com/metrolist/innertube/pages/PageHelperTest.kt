package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_USER_CHANNEL
import com.metrolist.innertube.models.NavigationEndpoint
import com.metrolist.innertube.models.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageHelperTest {
    @Test
    fun `duration is not parsed as an artist`() {
        val artists = PageHelper.extractArtists(listOf(Run("3:42", null)))

        assertTrue(artists.isEmpty())
    }

    @Test
    fun `search metadata is not parsed as an artist`() {
        val runs =
            listOf(
                Run("Song", null),
                Run(" • ", null),
                Run("3:04", null),
                Run("6m\u00a0plays", null),
            )

        assertTrue(PageHelper.extractArtists(runs).isEmpty())
        assertEquals(184, PageHelper.extractDuration(runs))
    }

    @Test
    fun `linked artist is found after search type label`() {
        val runs =
            listOf(
                Run("Song", null),
                Run(" • ", null),
                Run(
                    "Lupus Nocte",
                    NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123")),
                ),
            )

        assertEquals(listOf(Artist("Lupus Nocte", "UC123")), PageHelper.extractArtists(runs))
    }

    @Test
    fun `unlinked metadata is never promoted to artists`() {
        val artists =
            PageHelper.extractArtists(
                listOf(
                    Run("Artist", null),
                    Run(" • ", null),
                    Run("Album", null),
                    Run(" • ", null),
                    Run("3:42", null),
                ),
            )

        assertTrue(artists.isEmpty())
    }

    @Test
    fun `commas between linked artists are not parsed as artists`() {
        val artists =
            PageHelper.extractArtists(
                listOf(
                    Run(
                        "Primary Artist",
                        NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123")),
                    ),
                    Run(", ", null),
                    Run(
                        "Featured Artist",
                        NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC456")),
                    ),
                ),
            )

        assertEquals(
            listOf(Artist("Primary Artist", "UC123"), Artist("Featured Artist", "UC456")),
            artists,
        )
    }

    @Test
    fun `linked artist does not authorize unlinked neighbors`() {
        val artists =
            PageHelper.extractArtists(
                listOf(
                    Run(
                        "Primary Artist",
                        NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123")),
                    ),
                    Run(" & ", null),
                    Run("Featured Artist", null),
                ),
            )

        assertEquals(
            listOf(Artist("Primary Artist", "UC123")),
            artists,
        )
    }

    @Test
    fun `localized counts and labels cannot become artists but linked names remain whole`() {
        val metadata = listOf("canción", "14 m reproducciones", "3,3 M reproducciones", "歌曲", "1億 回再生").map { Run(it, null) }
        assertTrue(PageHelper.extractArtists(metadata).isEmpty())
        assertEquals(
            listOf(Artist("21 Savage", "UC21"), Artist("10 Years and Song & Dance", "UC10")),
            PageHelper.extractArtists(
                metadata +
                    listOf(
                        Run("21 Savage", NavigationEndpoint(browseEndpoint = BrowseEndpoint("UC21"))),
                        Run(" & ", null),
                        Run("10 Years and Song & Dance", NavigationEndpoint(browseEndpoint = BrowseEndpoint("UC10"))),
                        Run(" ", NavigationEndpoint(browseEndpoint = BrowseEndpoint("UCblank"))),
                        Run("Album title", NavigationEndpoint(browseEndpoint = BrowseEndpoint("MPREb_album"))),
                    ),
            ),
        )
    }

    @Test
    fun `user channel link is trusted but album typed link is not`() {
        fun linked(
            name: String,
            id: String,
            type: String,
        ) = Run(
            name,
            NavigationEndpoint(
                browseEndpoint =
                    BrowseEndpoint(
                        id,
                        browseEndpointContextSupportedConfigs =
                            BrowseEndpointContextSupportedConfigs(
                                BrowseEndpointContextMusicConfig(type),
                            ),
                    ),
            ),
        )
        assertEquals(
            listOf(Artist("Song", "MPLA_channel")),
            PageHelper.extractArtists(
                listOf(
                    linked("Song", "MPLA_channel", MUSIC_PAGE_TYPE_USER_CHANNEL),
                    linked("Album", "UCnotAnArtist", MUSIC_PAGE_TYPE_ALBUM),
                ),
            ),
        )
    }

    @Test
    fun `typed artist endpoint does not require a channel id`() {
        val endpoint =
            BrowseEndpoint(
                browseId = "MPLA123",
                browseEndpointContextSupportedConfigs =
                    BrowseEndpointContextSupportedConfigs(
                        BrowseEndpointContextMusicConfig(MUSIC_PAGE_TYPE_ARTIST),
                    ),
            )

        assertEquals(
            listOf(Artist("Artist", "MPLA123")),
            PageHelper.extractArtists(
                listOf(Run("Artist", NavigationEndpoint(browseEndpoint = endpoint))),
            ),
        )
    }
}
