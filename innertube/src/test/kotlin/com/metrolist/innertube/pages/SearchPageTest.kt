package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PLAYLIST
import com.metrolist.innertube.models.BrowseEndpoint.BrowseEndpointContextSupportedConfigs.BrowseEndpointContextMusicConfig.Companion.MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE
import com.metrolist.innertube.models.MusicCardShelfRenderer
import com.metrolist.innertube.models.MusicCarouselShelfRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicShelfRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.NavigationEndpoint
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.SectionListRenderer
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.ThumbnailRenderer
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.WatchEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPageTest {
    @Test
    fun `card song inherits artist when response only contains type and duration`() {
        val renderer =
            songRenderer(
                title = "Howling",
                metadata =
                    listOf(
                        Run("Song", null),
                        Run(" • ", null),
                        Run("3:04", null),
                        Run("6m\u00a0plays", null),
                    ),
            )
        val fallbackArtist = Artist("Lupus Nocte", "UC123")

        val song =
            SearchPage.toYTItem(
                renderer,
                fallbackArtists = listOf(fallbackArtist),
            ) as SongItem

        assertEquals(listOf(fallbackArtist), song.artists)
        assertEquals(184, song.duration)
    }

    @Test
    fun `localized metadata only songs stay playable across search and artist continuations`() {
        val metadata =
            listOf("canción", " • ", "14 m reproducciones", " • ", "3,3 M reproducciones", " • ", "歌曲", " • ", "1億 回再生")
                .map { Run(it, null) }
        val row = songRenderer("Song", metadata)
        val song = SearchPage.toYTItem(row) as SongItem
        assertEquals(emptyList<Artist>(), song.artists)
        assertEquals("videoId", song.id)
        assertEquals(emptyList<Artist>(), (SearchPage.toYTItem(row.copy(flexColumns = row.flexColumns.take(1))) as SongItem).artists)
        assertEquals(emptyList<Artist>(), (SearchSuggestionPage.fromMusicResponsiveListItemRenderer(row) as SongItem).artists)
        assertEquals(emptyList<Artist>(), (ArtistItemsPage.fromMusicResponsiveListItemRenderer(row) as SongItem).artists)

        val shelf =
            MusicShelfRenderer(
                title = Runs(listOf(Run("Songs", null))),
                contents = listOf(MusicShelfRenderer.Content(row, null, null)),
                continuations = null,
                bottomEndpoint = null,
                moreContentButton = null,
            )
        val section = SectionListRenderer.Content(null, shelf, null, null, null, null, null, null, null)
        val artistSong = ArtistPage.fromSectionListRendererContent(section)?.items?.single() as SongItem
        assertEquals(emptyList<Artist>(), artistSong.artists)
        assertEquals("videoId", artistSong.id)

        val tile =
            MusicTwoRowItemRenderer(
                title = Runs(listOf(Run("Song", null))),
                subtitle = Runs(metadata),
                subtitleBadges = null,
                menu = null,
                thumbnailRenderer = row.thumbnail!!,
                navigationEndpoint = NavigationEndpoint(watchEndpoint = WatchEndpoint(videoId = "videoId")),
                thumbnailOverlay = null,
            )
        assertEquals(emptyList<Artist>(), (ArtistItemsPage.fromMusicTwoRowItemRenderer(tile) as SongItem).artists)
        val carousel =
            MusicCarouselShelfRenderer(
                header =
                    MusicCarouselShelfRenderer.Header(
                        MusicCarouselShelfRenderer.Header.MusicCarouselShelfBasicHeaderRenderer(
                            strapline = null,
                            title = Runs(listOf(Run("Songs", null))),
                            thumbnail = null,
                            moreContentButton = null,
                        ),
                    ),
                contents = listOf(MusicCarouselShelfRenderer.Content(tile, null, null, null)),
                itemSize = "SMALL",
                numItemsPerColumn = null,
            )
        val carouselSection = SectionListRenderer.Content(carousel, null, null, null, null, null, null, null, null)
        assertEquals(emptyList<Artist>(), (ArtistPage.fromSectionListRendererContent(carouselSection)?.items?.single() as SongItem).artists)
    }

    @Test
    fun `linked artists and card context survive localized metadata`() {
        val linked = Run("Song & Dance", NavigationEndpoint(browseEndpoint = BrowseEndpoint("UCsong")))
        val metadata = listOf(Run("canción", null), Run(" • ", null), linked, Run(" • ", null), Run("3,3 M reproducciones", null))
        val expected = listOf(Artist("Song & Dance", "UCsong"))
        assertEquals(expected, (SearchPage.toYTItem(songRenderer("Song", metadata)) as SongItem).artists)
        val linkedRow = songRenderer("Song", metadata)
        assertEquals(expected, (ArtistItemsPage.fromMusicResponsiveListItemRenderer(linkedRow) as SongItem).artists)
        assertEquals(expected, (SearchSuggestionPage.fromMusicResponsiveListItemRenderer(linkedRow) as SongItem).artists)
        val shelf =
            MusicShelfRenderer(
                Runs(listOf(Run("Songs", null))),
                listOf(MusicShelfRenderer.Content(linkedRow, null, null)),
                null,
                null,
                null,
            )
        assertEquals(
            expected,
            (
                ArtistPage
                    .fromSectionListRendererContent(
                        SectionListRenderer.Content(null, shelf, null, null, null, null, null, null, null),
                    )?.items
                    ?.single() as SongItem
            ).artists,
        )
        assertEquals(
            expected,
            (
                ArtistItemsPage.fromMusicTwoRowItemRenderer(
                    MusicTwoRowItemRenderer(
                        title = Runs(listOf(Run("Song", null))),
                        subtitle = Runs(metadata),
                        subtitleBadges = null,
                        menu = null,
                        thumbnailRenderer = songRenderer("Song", metadata).thumbnail!!,
                        navigationEndpoint = NavigationEndpoint(watchEndpoint = WatchEndpoint(videoId = "videoId")),
                        thumbnailOverlay = null,
                    ),
                ) as SongItem
            ).artists,
        )
        val card =
            MusicCardShelfRenderer(
                title = Runs(listOf(Run("Song", null))),
                subtitle = Runs(metadata),
                thumbnail = songRenderer("Song", metadata).thumbnail!!,
                header = null,
                contents = null,
                buttons = emptyList(),
                onTap = NavigationEndpoint(watchEndpoint = WatchEndpoint(videoId = "videoId")),
                subtitleBadges = null,
            )
        assertEquals(expected, (SearchSummaryPage.fromMusicCardShelfRenderer(card) as SongItem).artists)
        val fallback = Artist("21 Savage", "UC21")
        val unlinked = songRenderer("Song", listOf(Run("canción", null), Run(" • ", null), Run("14 m reproducciones", null)))
        assertEquals(listOf(fallback), (SearchPage.toYTItem(unlinked, listOf(fallback)) as SongItem).artists)
        assertEquals(
            emptyList<Artist>(),
            (
                SearchSummaryPage.fromMusicCardShelfRenderer(
                    card.copy(
                        subtitle =
                            Runs(
                                unlinked.flexColumns[1]
                                    .musicResponsiveListItemFlexColumnRenderer.text!!
                                    .runs,
                            ),
                    ),
                ) as SongItem
            ).artists,
        )
    }

    @Test
    fun `uploaded library artist columns retain tagged names without relaxing generic metadata parsing`() {
        val linked = Run("21 Savage", NavigationEndpoint(browseEndpoint = BrowseEndpoint("UC21")))
        val tagged = Run("Song & Dance", null)
        val row = songRenderer("Uploaded song", listOf(linked, Run(", ", null), tagged))

        assertEquals(
            listOf(Artist("21 Savage", "UC21"), Artist("Song & Dance", null)),
            (LibraryPage.fromMusicResponsiveListItemRenderer(row, isUploaded = true) as SongItem).artists,
        )
        assertEquals(
            listOf(Artist("21 Savage", "UC21")),
            (LibraryPage.fromMusicResponsiveListItemRenderer(row) as SongItem).artists,
        )
        val plainRow = songRenderer("Uploaded song", listOf(tagged))
        assertEquals(
            listOf(Artist("Song & Dance", null)),
            (LibraryPage.fromMusicResponsiveListItemRenderer(plainRow, isUploaded = true) as SongItem).artists,
        )
        val metadataRow = songRenderer("Song", listOf(Run("14 m reproducciones", null)))
        assertEquals(emptyList<Artist>(), (LibraryPage.fromMusicResponsiveListItemRenderer(metadataRow) as SongItem).artists)
    }

    @Test
    fun `library podcast bylines and playlist owners retain trusted credits`() {
        fun endpoint(
            type: String,
            id: String,
        ) = NavigationEndpoint(
            browseEndpoint =
                BrowseEndpoint(
                    browseId = id,
                    browseEndpointContextSupportedConfigs =
                        BrowseEndpointContextSupportedConfigs(
                            BrowseEndpointContextMusicConfig(type),
                        ),
                ),
        )
        val podcastEndpoint = endpoint(MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE, "MPSPshow")
        val bylines = listOf(Run("Owner & Friends", null), Run("Known Show", podcastEndpoint))
        bylines.forEach { byline ->
            val row =
                songRenderer("Podcast", listOf(byline, Run(" • ", null), Run("14 episodes", null)))
                    .copy(navigationEndpoint = podcastEndpoint)
            val expected = Artist(byline.text, null)
            assertEquals(expected, (LibraryPage.fromMusicResponsiveListItemRenderer(row) as PodcastItem).author)
            val tile =
                MusicTwoRowItemRenderer(
                    title = Runs(listOf(Run("Podcast", null))),
                    subtitle = row.flexColumns[1].musicResponsiveListItemFlexColumnRenderer.text,
                    subtitleBadges = null,
                    menu = null,
                    thumbnailRenderer = row.thumbnail!!,
                    navigationEndpoint = podcastEndpoint,
                    thumbnailOverlay = null,
                )
            assertEquals(expected, (LibraryPage.fromMusicTwoRowItemRenderer(tile) as PodcastItem).author)
            if (byline.navigationEndpoint == null) {
                assertEquals(
                    expected,
                    (
                        LibraryPage.fromMusicTwoRowItemRenderer(
                            tile.copy(navigationEndpoint = endpoint(MUSIC_PAGE_TYPE_PLAYLIST, "VLplaylist")),
                        ) as PlaylistItem
                    ).author,
                )
            }
        }
    }

    private fun songRenderer(
        title: String,
        metadata: List<Run>,
    ) = MusicResponsiveListItemRenderer(
        badges = null,
        fixedColumns = null,
        flexColumns =
            listOf(
                flexColumn(listOf(Run(title, null))),
                flexColumn(metadata),
            ),
        thumbnail =
            ThumbnailRenderer(
                musicThumbnailRenderer =
                    ThumbnailRenderer.MusicThumbnailRenderer(
                        thumbnail = Thumbnails(listOf(Thumbnail("https://example.com/cover.jpg", null, null))),
                        thumbnailCrop = null,
                        thumbnailScale = null,
                    ),
                musicAnimatedThumbnailRenderer = null,
                croppedSquareThumbnailRenderer = null,
            ),
        menu = null,
        playlistItemData =
            MusicResponsiveListItemRenderer.PlaylistItemData(
                playlistSetVideoId = null,
                videoId = "videoId",
            ),
        overlay = null,
        navigationEndpoint = null,
    )

    private fun flexColumn(runs: List<Run>) =
        MusicResponsiveListItemRenderer.FlexColumn(
            MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(Runs(runs)),
        )
}
