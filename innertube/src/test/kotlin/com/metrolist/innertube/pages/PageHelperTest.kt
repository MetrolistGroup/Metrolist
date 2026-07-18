package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ARTIST
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
        val runs = listOf(
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
        val runs = listOf(
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
    fun `unlinked artist name is retained`() {
        val artists = PageHelper.extractArtists(
            listOf(
                Run("Artist", null),
                Run(" • ", null),
                Run("Album", null),
                Run(" • ", null),
                Run("3:42", null),
            ),
        )

        assertEquals(listOf(Artist("Artist", null)), artists)
    }

    @Test
    fun `linked and unlinked artists are both retained`() {
        val artists = PageHelper.extractArtists(
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
            listOf(Artist("Primary Artist", "UC123"), Artist("Featured Artist", null)),
            artists,
        )
    }

    @Test
    fun `typed artist endpoint does not require a channel id`() {
        val endpoint = BrowseEndpoint(
            browseId = "MPLA123",
            browseEndpointContextSupportedConfigs = BrowseEndpointContextSupportedConfigs(
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
