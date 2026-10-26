package com.metrolist.innertube.pages

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.NavigationEndpoint
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.WatchEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumPageTest {
    @Test
    fun `uploaded compilation track keeps its tagged performer instead of album artist`() {
        val album = AlbumItem(
            browseId = "FEmusic_library_privately_owned_release_detail_upload",
            playlistId = "uploaded-playlist",
            title = "Compilation",
            artists = listOf(Artist("Various Artists", null)),
            thumbnail = "cover",
        )
        val song = AlbumPage.getSong(row(listOf(Run("Tagged Performer", null))), album)!!

        assertEquals(listOf(Artist("Tagged Performer", null)), song.artists)
        assertEquals(album.browseId, song.album?.id)
        assertEquals("cover", song.thumbnail)
    }

    @Test
    fun `art track performer takes precedence over label in known album context`() {
        val label = Artist("Distributor", "UClabel")
        val album =
            AlbumItem(
                browseId = "MPREb_album",
                playlistId = "OLAK5uy_album",
                title = "Album",
                artists = listOf(label),
                thumbnail = "cover",
            )
        val performer = Run("10 Years and Song", null)
        val song = AlbumPage.getSong(row(listOf(performer)), album)!!

        assertEquals(listOf(Artist("10 Years and Song", null)), song.artists)
        assertEquals(listOf(label), AlbumPage.getSong(row(emptyList()), album)!!.artists)
        assertEquals(
            listOf(label),
            AlbumPage
                .getSong(
                    row(listOf(performer)),
                    album.copy(playlistId = "regular-playlist"),
                )!!
                .artists,
        )
        assertEquals(
            listOf(Artist("Linked Performer", "UCperformer")),
            AlbumPage
                .getSong(
                    row(
                        listOf(
                            Run(
                                "Linked Performer",
                                NavigationEndpoint(
                                    browseEndpoint =
                                        BrowseEndpoint("UCperformer"),
                                ),
                            ),
                        ),
                    ),
                    album,
                )!!
                .artists,
        )
    }

    private fun row(subtitle: List<Run>): MusicResponsiveListItemRenderer {
        fun column(runs: List<Run>) =
            MusicResponsiveListItemRenderer.FlexColumn(
                MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(Runs(runs)),
            )
        return MusicResponsiveListItemRenderer(
            badges = null,
            fixedColumns = listOf(column(listOf(Run("3:12", null)))),
            flexColumns =
                listOf(
                    column(
                        listOf(
                            Run(
                                "Track",
                                NavigationEndpoint(
                                    watchEndpoint =
                                        WatchEndpoint(
                                            videoId = "video",
                                            watchEndpointMusicSupportedConfigs =
                                                WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                                                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(
                                                        "MUSIC_VIDEO_TYPE_ATV",
                                                    ),
                                                ),
                                        ),
                                ),
                            ),
                        ),
                    ),
                    column(subtitle),
                ),
            thumbnail = null,
            menu = null,
            overlay = null,
            navigationEndpoint = null,
            playlistItemData =
                MusicResponsiveListItemRenderer.PlaylistItemData(
                    playlistSetVideoId = null,
                    videoId = "video",
                ),
        )
    }
}
