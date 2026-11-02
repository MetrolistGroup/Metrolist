package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDailyDiscoverTest {
    @Test
    fun `hiding a video selects an audio alternative from the same cached seed`() {
        val seed = Song(SongEntity(id = "seed", title = "Seed"), artists = emptyList())
        val video = SongItem(
            id = "video",
            title = "Video",
            artists = emptyList(),
            musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            thumbnail = "cover",
        )
        val videoItem = DailyDiscoverItem(seed, video, null)
        val audioItem = videoItem.copy(
            recommendation = video.copy(id = "audio", musicVideoType = "MUSIC_VIDEO_TYPE_ATV"),
        )
        val cached = listOf(videoItem, audioItem)

        assertEquals(listOf(audioItem), filterDailyDiscoverItems(cached, true))
        assertEquals(listOf(videoItem), filterDailyDiscoverItems(cached, false))
    }
}
