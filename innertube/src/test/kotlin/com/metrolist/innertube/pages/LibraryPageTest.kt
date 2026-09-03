package com.metrolist.innertube.pages

import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer.FlexColumn
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer
import com.metrolist.innertube.models.NavigationEndpoint
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.ThumbnailRenderer
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.oddElements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPageTest {

    private fun thumbnailRenderer() = ThumbnailRenderer(
        musicThumbnailRenderer = ThumbnailRenderer.MusicThumbnailRenderer(
            thumbnail = Thumbnails(listOf(Thumbnail("https://example.com/thumb.jpg", 480, 480))),
            thumbnailCrop = "CENTER",
            thumbnailScale = "SCALE_UP",
        ),
        musicAnimatedThumbnailRenderer = null,
        croppedSquareThumbnailRenderer = null,
    )

    /**
     * Builds a song-row MusicResponsiveListItemRenderer.
     * [secondaryRuns] is the flexColumns[1] content (artist · album, etc.).
     * Pass null to simulate a row with no secondary line.
     */
    private fun buildSongRenderer(
        title: String,
        secondaryRuns: List<Run>? = null,
        videoId: String = "dQw4w9WgXcQ",
    ) = MusicResponsiveListItemRenderer(
        badges = null,
        fixedColumns = null,
        flexColumns = buildList {
            add(
                FlexColumn(
                    MusicResponsiveListItemFlexColumnRenderer(Runs(listOf(Run(title, null)))),
                ),
            )
            if (secondaryRuns != null) {
                add(FlexColumn(MusicResponsiveListItemFlexColumnRenderer(Runs(secondaryRuns))))
            }
        },
        thumbnail = thumbnailRenderer(),
        menu = null,
        playlistItemData = MusicResponsiveListItemRenderer.PlaylistItemData(
            playlistSetVideoId = null,
            videoId = videoId,
        ),
        overlay = null,
        navigationEndpoint = null,
    )

    @Test
    fun `uploaded song with linked artist and album extracts artist correctly`() {
        val renderer = buildSongRenderer(
            title = "My Song",
            secondaryRuns = listOf(
                Run("Artist Name", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123456"))),
                Run(" \u2022 ", null),
                Run("Album Name", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "OLAK5uy"))),
            ),
        )

        val item = LibraryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem

        assertEquals("My Song", item.title)
        val artistNames = item.artists.map { it.name }

        // Before the fix, oddElements() returned both Artist and Album runs, so the
        // album leaked into the artist list. extractArtists() splits by separator.
        assertTrue("Album must not appear as artist, got: $artistNames", "Album Name" !in artistNames)
        assertTrue("Artist Name must appear, got: $artistNames", "Artist Name" in artistNames)
    }

    @Test
    fun `uploaded song with unlinked artist extracts artist without browse endpoint`() {
        val renderer = buildSongRenderer(
            title = "My Upload",
            secondaryRuns = listOf(
                Run("Solo Artist", null),
                Run(" \u2022 ", null),
                Run("My Album", null),
            ),
        )

        val item = LibraryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem

        assertEquals("My Upload", item.title)

        // extractArtists() strips the separator, takes the first section, and returns the
        // unlinked "Solo Artist" as Artist(name, null). "My Album" must NOT appear.
        val artistNames = item.artists.map { it.name }

        assertTrue("My Album must not appear as artist, got: $artistNames", "My Album" !in artistNames)
        assertEquals(1, item.artists.size)
        assertEquals("Solo Artist", item.artists[0].name)
        assertEquals(null, item.artists[0].id)
    }

    @Test
    fun `uploaded song with multiple linked artists extracts all artists`() {
        val renderer = buildSongRenderer(
            title = "Duet Song",
            secondaryRuns = listOf(
                Run(
                    "Primary Artist",
                    NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC111111")),
                ),
                Run(" & ", null),
                Run(
                    "Featured Artist",
                    NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC222222")),
                ),
            ),
        )

        val item = LibraryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem

        assertEquals("Duet Song", item.title)

        // extractArtists() uses splitArtistsByConjunction() to split the "&" run, returning both.
        assertEquals(2, item.artists.size)
        assertEquals("Primary Artist", item.artists[0].name)
        assertEquals("UC111111", item.artists[0].id)
        assertEquals("Featured Artist", item.artists[1].name)
        assertEquals("UC222222", item.artists[1].id)
    }

    @Test
    fun `uploaded song without secondary flex column returns empty artists`() {
        val renderer = buildSongRenderer(title = "Title Only")

        val item = LibraryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem

        assertEquals("Title Only", item.title)
        assertTrue(item.artists.isEmpty())
    }

    @Test
    fun `uploaded song with duration in secondary line does not produce duration as artist`() {
        val renderer = buildSongRenderer(
            title = "Instrumental Track",
            secondaryRuns = listOf(
                Run("Composer", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC999999"))),
                Run(" \u2022 ", null),
                Run("3:42", null),
            ),
        )

        val item = LibraryPage.fromMusicResponsiveListItemRenderer(renderer) as SongItem

        assertEquals("Instrumental Track", item.title)

        // extractArtists() recognizes "3:42" as duration metadata via isMetadataText() and
        // excludes it. oddElements() would have included it as an artist.
        val artistNames = item.artists.map { it.name }

        assertTrue("Duration '3:42' must not appear as artist, got: $artistNames", "3:42" !in artistNames)
        assertEquals(1, item.artists.size)
        assertEquals("Composer", item.artists[0].name)
    }

    @Test
    fun `oddElements produces different result than extractArtists for separator-containing runs`() {
        val runs = listOf(
            Run("Artist Name", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "UC123456"))),
            Run(" \u2022 ", null),
            Run("Album Name", NavigationEndpoint(browseEndpoint = BrowseEndpoint(browseId = "OLAK5uy"))),
        )

        // oddElements() returns even-indexed runs [Artist Name, Album Name]; both get mapped
        // as artists, so the album is incorrectly treated as an artist.
        val oddResult = runs.oddElements()
        assertEquals(2, oddResult.size)

        // extractArtists() splits by separator, then filters: Section 0 (Artist Name, UC123456)
        // is kept; Section 1 (Album Name, OLAK5uy) is dropped because OLAK5uy is not a UC/* id.
        val extractResult = PageHelper.extractArtists(runs)
        assertEquals(1, extractResult.size)
        assertEquals("Artist Name", extractResult[0].name)
    }
}
