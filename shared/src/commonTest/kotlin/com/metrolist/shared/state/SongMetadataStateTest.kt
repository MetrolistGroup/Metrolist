package com.metrolist.shared.state

import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SongMetadataStateTest {
    @Test
    fun metadataChangesIgnorePlaybackWritesButKeepEditsArtistChangesAndRemoval() =
        runTest {
            val original = Song(SongEntity("song", "Title", customTitle = "Custom"), listOf(ArtistEntity("artist", "Artist")))
            val checkpoint = original.copy(song = original.song.copy(playbackPosition = 5000, totalPlayTime = 5000, liked = true))
            val edited = checkpoint.copy(song = checkpoint.song.copy(customTitle = "Edited"))
            val artistChanged = edited.copy(artists = listOf(ArtistEntity("artist", "Renamed artist")))
            val emissions =
                listOf(listOf(original), listOf(checkpoint), listOf(edited), listOf(artistChanged), emptyList(), emptyList())
                    .asFlow()
                    .displayMetadataChanges()
                    .toList()
            assertEquals(4, emissions.size)
            assertEquals("Custom", emissions[0].getValue("song").customTitle)
            assertEquals("Edited", emissions[1].getValue("song").customTitle)
            assertEquals(
                "Renamed artist",
                emissions[2]
                    .getValue("song")
                    .canonicalArtists
                    .single()
                    .name,
            )
            assertEquals(emptyMap(), emissions[3])
        }

    @Test
    fun unchangedMetadataReusesSongsAcrossRepeatedQueueRefreshes() {
        for (customArtist in listOf(null, "Edited artist")) {
            val metadata =
                SongDisplayMetadata(
                    canonicalTitle = "Title",
                    canonicalArtists = listOf(ArtistTiny("artist", "Artist")),
                    customTitle = "Edited title",
                    customArtist = customArtist,
                )
            val songs =
                List(1_000) { index ->
                    SongItem("song-$index", "Title", null, metadata.canonicalArtists, null, duration = 120L)
                        .withCustomMetadata(metadata)
                }
            repeat(10) {
                for (song in songs) {
                    assertSame(song, song.withCustomMetadata(metadata))
                    assertSame(song.artists, song.withCustomMetadata(metadata).artists)
                }
            }
            val edited = songs.first().withCustomMetadata(metadata.copy(customTitle = "New title"))
            assertNotSame(songs.first(), edited)
            assertEquals("New title", edited.title)
            assertEquals("Title", edited.canonicalTitle)
            val cleared = edited.withCustomMetadata(metadata.copy(customTitle = null, customArtist = null))
            assertEquals("Title", cleared.title)
            assertEquals(metadata.canonicalArtists, cleared.artists)
        }
    }

    @Test
    fun unchangedDurationDoesNotCopyTheMetadataResult() {
        val song = SongItem("song", "Title", null, listOf(ArtistTiny("artist", "Artist")), null, duration = 120L)
        val displayed = SongMetadataState.apply(song)
        assertEquals(120L, displayed.duration)
        assertSame(displayed, SongMetadataState.apply(displayed))
        assertEquals(null, SongMetadataState.apply(song.copy(duration = 0L)).duration)
    }

    @Test
    fun clearedOverrideBridgesStaleItemWithoutMaskingNewMetadata() {
        val oldArtists = listOf(ArtistTiny("old", "Old artist"))
        val freshArtists = listOf(ArtistTiny("fresh", "Fresh artist"))
        val reset =
            SongDisplayMetadata(
                canonicalTitle = "Fresh title",
                canonicalArtists = freshArtists,
                customTitle = null,
                customArtist = null,
                replacedCanonicalTitle = "Old title",
                replacedCanonicalArtists = oldArtists,
            )
        val stale =
            SongItem(
                id = "song",
                title = "Edited title",
                thumbnail = null,
                artists = listOf(ArtistTiny("old", "Edited artist")),
                album = null,
                customTitle = "Edited title",
                customArtist = "Edited artist",
                canonicalTitle = "Old title",
                canonicalArtists = oldArtists,
            )
        val newer = SongItem("song", "Newest title", null, listOf(ArtistTiny("newest", "Newest artist")), null)

        assertEquals("Fresh title", stale.withStoredMetadata(reset).title)
        assertEquals(
            "Fresh artist",
            stale
                .withStoredMetadata(reset)
                .artists
                .single()
                .name,
        )
        assertEquals("Newest title", newer.withStoredMetadata(reset).title)
        assertEquals(
            "Newest artist",
            newer
                .withStoredMetadata(reset)
                .artists
                .single()
                .name,
        )
    }
}
