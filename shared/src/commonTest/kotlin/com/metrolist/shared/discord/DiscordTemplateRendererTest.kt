package com.metrolist.shared.discord

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.utils.ShareUrls
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscordTemplateRendererTest {
    @Test
    fun albumButtonTemplatesUseTheSameBrowseLinkAsSharingAnAlbum() {
        for (id in listOf("MPREb_1234567890", "FEmusic_release_detail_sample")) {
            val activity = activity(id)
            assertEquals(ShareUrls.album(id), activity.button1Url)
            assertEquals(ShareUrls.album(id), activity.button2Url)
        }
    }

    @Test
    fun playlistBackedAlbumIdStillUsesAPlaylistLink() {
        val activity = activity("OLAK5uy_sample")
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_sample", activity.button1Url)
        assertEquals(activity.button1Url, activity.button2Url)
    }

    @Test
    fun missingAlbumIdStillLinksToTheSong() {
        val activity = activity(null)
        assertEquals(ShareUrls.song("song-id"), activity.button1Url)
        assertEquals(activity.button1Url, activity.button2Url)
    }

    @Test
    fun basicButtonsAreUnchangedWhenAnAlbumIdIsPresent() {
        val activity = activity("MPREb_1234567890", advanced = false)
        assertEquals(ShareUrls.song("song-id"), activity.button1Url)
        assertEquals(DiscordDefaults.BUTTON2_URL, activity.button2Url)
    }

    private fun activity(
        albumId: String?,
        advanced: Boolean = true,
    ): DiscordActivity =
        DiscordActivityBuilder.build(
            song = SongItem("song-id", "Track", null, emptyList(), AlbumTiny(albumId, "Release")),
            artistName = "Artist",
            albumName = "Release",
            artistThumbnail = null,
            songTitle = "Track",
            startTimestamp = 0L,
            endTimestamp = null,
            advancedMode = advanced,
            btn1Url = "{album.url}",
            btn2Url = "{album_url}",
        )
}
