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

    @Test
    fun `shared candidates produce distinct cards for both seeds`() {
        val firstSeed = Song(SongEntity(id = "first", title = "First"), artists = emptyList())
        val secondSeed = Song(SongEntity(id = "second", title = "Second"), artists = emptyList())
        val firstTrack = SongItem(
            id = "x",
            title = "X",
            artists = emptyList(),
            musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            thumbnail = "cover",
        )
        val secondTrack = firstTrack.copy(id = "y", title = "Y")
        val firstCard = DailyDiscoverItem(firstSeed, firstTrack, null)
        val secondCard = DailyDiscoverItem(secondSeed, secondTrack, null)
        val cached = listOf(
            firstCard,
            firstCard.copy(recommendation = secondTrack),
            secondCard.copy(recommendation = firstTrack),
            secondCard,
        )

        assertEquals(listOf(firstCard, secondCard), filterDailyDiscoverItems(cached, true))
        assertEquals(listOf(firstCard, secondCard), filterDailyDiscoverItems(cached, false))
    }
}
