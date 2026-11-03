package com.metrolist.music.playback

import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDecisionTest {
    private fun song(id: String, title: String, vararg artists: String): Song {
        val songEntity = SongEntity(
            id = id,
            title = title,
            duration = 200,
            thumbnailUrl = null,
        )
        val artistEntities = artists.map { name ->
            ArtistEntity(id = name.lowercase(), name = name)
        }
        return Song(
            song = songEntity,
            artists = artistEntities,
            album = null,
        )
    }

    @Test
    fun `Unavailable explicit selected ID returns no replacement song`() {
        val results = listOf(
            song("1", "Song A", "Artist X"),
            song("2", "Song B", "Artist X")
        )
        val explicitId = "999"

        // When not a voice search, we ask for explicitId (isVoiceSearch is false when songId is not null)
        val decision = resolvePlaybackSelection(
            isVoiceSearch = false,
            songId = explicitId,
            searchQuery = "whatever",
            searchResults = results
        )
        assertNull("Explicitly requested ID that is unavailable should return null", decision)
    }

    @Test
    fun `Artist query filters out unrelated results even when those results sort first`() {
        val results = listOf(
            song("1", "Hit Song 2015", "Pop Star"),
            song("2", "Bohemian Rhapsody", "Queen"),
            song("3", "We Will Rock You", "Queen"),
            song("4", "Radio Ga Ga", "Queen")
        )

        val decision = resolvePlaybackSelection(
            isVoiceSearch = true,
            songId = "",
            searchQuery = "Queen",
            searchResults = results
        )

        assertTrue("Decision should be MultipleSongs", decision is PlaybackSelection.MultipleSongs)
        val multiple = decision as PlaybackSelection.MultipleSongs

        assertEquals("Should only contain artist's songs", 3, multiple.songs.size)
        assertTrue("All songs should be by the matched artist", multiple.songs.all { it.artists.first().name == "Queen" })
        assertTrue("Unrelated sorted-first hit song should be filtered out", multiple.songs.none { it.song.id == "1" })
    }

    @Test
    fun `Broad voice request with no confident song match can still queue the results`() {
        val results = listOf(
            song("1", "Apple", "Artist A"),
            song("2", "Banana", "Artist B"),
            song("3", "Cherry", "Artist C")
        )

        val decision = resolvePlaybackSelection(
            isVoiceSearch = true,
            songId = "",
            searchQuery = "2015 hits",
            searchResults = results
        )

        assertTrue("Decision should be MultipleSongs", decision is PlaybackSelection.MultipleSongs)
        val multiple = decision as PlaybackSelection.MultipleSongs

        assertEquals("Should queue all fallback results", 3, multiple.songs.size)
        assertEquals("Queue title should match search query", "2015 hits", multiple.title)
    }
}