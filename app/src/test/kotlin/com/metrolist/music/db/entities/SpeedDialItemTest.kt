package com.metrolist.music.db.entities

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test
import com.metrolist.innertube.models.Artist as YouTubeArtist

class SpeedDialItemTest {
    @Test
    fun `song pin round trip retains video type without a song database entry`() {
        val types = listOf(
            null,
            "MUSIC_VIDEO_TYPE_ATV",
            "MUSIC_VIDEO_TYPE_OMV",
            "MUSIC_VIDEO_TYPE_UGC",
            "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
        )
        for (type in types) {
            val song = SongItem(
                id = "song",
                title = "Song",
                artists = listOf(YouTubeArtist("Artist", "UCartist")),
                musicVideoType = type,
                thumbnail = "cover",
            )

            assertEquals(song, SpeedDialItem.fromYTItem(song).toYTItem())
        }
    }

    @Test
    fun `album pins retain their playlist id`() {
        val album = AlbumItem(
            browseId = "album",
            playlistId = "playlist",
            title = "Album",
            artists = emptyList(),
            thumbnail = "cover",
        )

        val restored = SpeedDialItem.fromYTItem(album).toYTItem() as AlbumItem

        assertEquals("playlist", restored.playlistId)
    }
}
