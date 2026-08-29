package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.ThumbnailRenderer
import com.metrolist.innertube.models.Thumbnails
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSummaryPageTest {
    @Test
    fun `card song inherits artist when response only contains type and duration`() {
        val renderer = songRenderer(
            title = "Howling",
            metadata = listOf(
                Run("Song", null),
                Run(" • ", null),
                Run("3:04", null),
                Run("6m\u00a0plays", null),
            ),
        )
        val fallbackArtist = Artist("Lupus Nocte", "UC123")

        val song = SearchSummaryPage.fromMusicResponsiveListItemRenderer(
            renderer,
            fallbackArtists = listOf(fallbackArtist),
        ) as SongItem

        assertEquals(listOf(fallbackArtist), song.artists)
        assertEquals(184, song.duration)
    }

    private fun songRenderer(
        title: String,
        metadata: List<Run>,
    ) = MusicResponsiveListItemRenderer(
        badges = null,
        fixedColumns = null,
        flexColumns = listOf(
            flexColumn(listOf(Run(title, null))),
            flexColumn(metadata),
        ),
        thumbnail = ThumbnailRenderer(
            musicThumbnailRenderer = ThumbnailRenderer.MusicThumbnailRenderer(
                thumbnail = Thumbnails(listOf(Thumbnail("https://example.com/cover.jpg", null, null))),
                thumbnailCrop = null,
                thumbnailScale = null,
            ),
            musicAnimatedThumbnailRenderer = null,
            croppedSquareThumbnailRenderer = null,
        ),
        menu = null,
        playlistItemData = MusicResponsiveListItemRenderer.PlaylistItemData(
            playlistSetVideoId = null,
            videoId = "videoId",
        ),
        overlay = null,
        navigationEndpoint = null,
    )

    private fun flexColumn(runs: List<Run>) = MusicResponsiveListItemRenderer.FlexColumn(
        MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(Runs(runs)),
    )
}
