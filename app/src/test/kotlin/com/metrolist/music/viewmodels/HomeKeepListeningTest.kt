package com.metrolist.music.viewmodels

import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeKeepListeningTest {
    @Test
    fun `video candidates do not consume audio slots or remove artists`() {
        val videos = (1..10).map { song("video$it", isVideo = true) }
        val audio = (1..10).map { song("audio$it", isVideo = false) }
        val artist = Artist(ArtistEntity(id = "artist", name = "Artist"), songCount = 1)
        val cached = videos + audio + artist

        assertEquals(audio + artist, filterKeepListeningItems(cached, true))
        assertEquals(videos + artist, filterKeepListeningItems(cached, false))
    }

    private fun song(id: String, isVideo: Boolean) =
        Song(SongEntity(id = id, title = id, isVideo = isVideo), artists = emptyList())
}
