package com.metrolist.shared.ui.screens

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseContentTest {
    @Test
    fun browseQueuePreservesTrackOrderAndEpisodePlaybackMetadata() {
        val song = SongItem("song", "Song", null, emptyList(), null)
        val author = ArtistTiny("author", "Author")
        val episode =
            EpisodeItem(
                "episode",
                "Episode",
                "artwork",
                author = author,
                duration = 120,
                podcastId = "podcast",
                playlistSetVideoId = "playlist-entry",
            )
        val album = AlbumItem("album", "Album", null, emptyList())

        val playable = listOf(song, album, episode, BrowseItem("browse", "Browse"), song).toPlayableSongs()

        assertEquals(listOf("song", "episode", "song"), playable.map { it.id })
        assertEquals(song, playable.first())
        assertEquals(song, playable.last())
        assertEquals("podcast", playable[1].podcastId)
        assertEquals(listOf(author), playable[1].artists)
        assertEquals(120L, playable[1].duration)
        assertEquals("artwork", playable[1].thumbnail)
        assertEquals("playlist-entry", playable[1].playlistSetVideoId)
    }
}
