package com.metrolist.music.models

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaMetadataVideoTypeTest {
    @Test
    fun `podcasts and audio tracks are not persisted as video songs`() {
        val types = listOf(
            null to false,
            "MUSIC_VIDEO_TYPE_ATV" to false,
            "MUSIC_VIDEO_TYPE_OMV" to true,
            "MUSIC_VIDEO_TYPE_UGC" to true,
            "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" to false,
        )
        for ((type, isVideo) in types) {
            val metadata = MediaMetadata(
                id = "song",
                title = "Song",
                artists = emptyList(),
                duration = 180,
                musicVideoType = type,
            )

            assertEquals(isVideo, metadata.isVideoSong)
            assertEquals(isVideo, metadata.toSongEntity().isVideo)
        }
    }
}
