package com.metrolist.shared.viewmodels

import com.metrolist.shared.viewmodels.PlayerViewModel.SongUiModel
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerArtworkSourceTest {
    @Test
    fun fullPlayerUsesTheSameUnletterboxedVideoThumbnailAsSongRows() {
        val video =
            SongUiModel(
                id = "video",
                title = "Video",
                artists = emptyList(),
                thumbnail = "https://i.ytimg.com/vi/video/hqdefault.jpg",
                duration = null,
                isVideoSong = true,
                musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            )
        assertEquals("https://i.ytimg.com/vi/video/mqdefault.jpg", video.displayThumbnail)
        assertEquals(video.displayThumbnail, video.copy(thumbnail = null).displayThumbnail)
        assertEquals("custom-artwork", video.copy(thumbnail = "custom-artwork").displayThumbnail)
        assertEquals(video.thumbnail, video.copy(isVideoSong = false, musicVideoType = "MUSIC_VIDEO_TYPE_ATV").displayThumbnail)
        assertEquals(video.thumbnail, video.copy(isUploaded = true).displayThumbnail)
    }
}
