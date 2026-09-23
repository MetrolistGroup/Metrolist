package com.metrolist.innertube.pages

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_ALBUM
import com.metrolist.innertube.models.MusicCarouselShelfRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.NavigationEndpoint
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.ThumbnailRenderer
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.WatchEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePageAlbumTest {

    @Test
    fun `album card artists contain only actual artists and the year is propagated`() {
        val album = parseAlbum(
            albumRenderer(
                listOf(
                    Run("Album", null),
                    Run(" • ", null),
                    Run("Lupus Nocte", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123"))),
                    Run(" • ", null),
                    Run("2023", null),
                ),
            ),
        )

        assertEquals(listOf(Artist("Lupus Nocte", "UC123")), album.artists)
        assertEquals(2023, album.year)
    }

    @Test
    fun `album card with unlinked artist keeps the artist and drops the year`() {
        val album = parseAlbum(
            albumRenderer(
                listOf(
                    Run("Album", null),
                    Run(" • ", null),
                    Run("Lupus Nocte", null),
                    Run(" • ", null),
                    Run("2023", null),
                ),
            ),
        )

        assertEquals(listOf(Artist("Lupus Nocte", null)), album.artists)
        assertEquals(2023, album.year)
    }

    @Test
    fun `album card with multiple linked artists keeps all artists`() {
        val album = parseAlbum(
            albumRenderer(
                listOf(
                    Run("Album", null),
                    Run(" • ", null),
                    Run("Lupus Nocte", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC1"))),
                    Run(" • ", null),
                    Run("Second Artist", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC2"))),
                    Run(" • ", null),
                    Run("2023", null),
                ),
            ),
        )

        assertEquals(
            listOf(Artist("Lupus Nocte", "UC1"), Artist("Second Artist", "UC2")),
            album.artists,
        )
        assertEquals(2023, album.year)
    }

    @Test
    fun `album card never presents the year as an artist`() {
        val album = parseAlbum(
            albumRenderer(
                listOf(
                    Run("Album", null),
                    Run(" • ", null),
                    Run("Lupus Nocte", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123"))),
                    Run(" • ", null),
                    Run("2023", null),
                ),
            ),
        )

        assertTrue(album.artists.orEmpty().none { it.name == "2023" })
    }

    private fun albumRenderer(subtitleRuns: List<Run>) =
        MusicTwoRowItemRenderer(
            title = Runs(listOf(Run("Midnight", null))),
            subtitle = Runs(subtitleRuns),
            subtitleBadges = null,
            menu = null,
            thumbnailRenderer = ThumbnailRenderer(
                musicThumbnailRenderer = ThumbnailRenderer.MusicThumbnailRenderer(
                    thumbnail = Thumbnails(listOf(Thumbnail("https://example.com/cover.jpg", null, null))),
                    thumbnailCrop = null,
                    thumbnailScale = null,
                ),
                musicAnimatedThumbnailRenderer = null,
                croppedSquareThumbnailRenderer = null,
            ),
            navigationEndpoint = NavigationEndpoint(
                browseEndpoint = BrowseEndpoint(
                    browseId = "MPREb_album",
                    browseEndpointContextSupportedConfigs = BrowseEndpointContextSupportedConfigs(
                        BrowseEndpointContextMusicConfig(MUSIC_PAGE_TYPE_ALBUM),
                    ),
                ),
            ),
            thumbnailOverlay = MusicResponsiveListItemRenderer.Overlay(
                MusicResponsiveListItemRenderer.Overlay.MusicItemThumbnailOverlayRenderer(
                    MusicResponsiveListItemRenderer.Overlay.MusicItemThumbnailOverlayRenderer.Content(
                        MusicResponsiveListItemRenderer.Overlay.MusicItemThumbnailOverlayRenderer.Content.MusicPlayButtonRenderer(
                            NavigationEndpoint(watchPlaylistEndpoint = WatchEndpoint(playlistId = "OLAK5uy_playlist")),
                        ),
                    ),
                ),
            ),
        )

    private fun parseAlbum(renderer: MusicTwoRowItemRenderer): AlbumItem {
        val shelf = MusicCarouselShelfRenderer(
            header = MusicCarouselShelfRenderer.Header(
                MusicCarouselShelfRenderer.Header.MusicCarouselShelfBasicHeaderRenderer(
                    strapline = null,
                    title = Runs(listOf(Run("Quick Picks", null))),
                    thumbnail = null,
                    moreContentButton = null,
                ),
            ),
            contents = listOf(
                MusicCarouselShelfRenderer.Content(
                    musicTwoRowItemRenderer = renderer,
                    musicResponsiveListItemRenderer = null,
                    musicMultiRowListItemRenderer = null,
                    musicNavigationButtonRenderer = null,
                ),
            ),
            itemSize = "MUSIC_TWO_ROW_ITEM_RENDERER",
            numItemsPerColumn = null,
        )
        val section = HomePage.Section.fromMusicCarouselShelfRenderer(shelf)
            ?: throw AssertionError("Section was not parsed")
        return section.items.singleOrNull() as? AlbumItem
            ?: throw AssertionError("Expected a single AlbumItem, got ${section.items}")
    }
}
