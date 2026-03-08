package com.metrolist.shared.db.entities

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SpeedDialItemTest {
    @Test
    fun supportedItemsRoundTripToTheirOriginalType() {
        val artist = ArtistTiny(id = "artist", name = "Artist")
        val items =
            listOf(
                SongItem("song", "Song", "song.jpg", listOf(artist), AlbumTiny("album", "Album"), isExplicit = true),
                AlbumItem("album", "Album", "album.jpg", listOf(artist), playlistId = "playlist", isExplicit = true),
                ArtistItem("artist", "Artist", "artist.jpg"),
                PlaylistItem("playlist", "Playlist", "playlist.jpg", author = "Author"),
                PodcastItem("podcast", "Podcast", "podcast.jpg", author = "Host"),
            )

        val restored = items.map { SpeedDialItem.fromYTItem(it).toYTItem() }

        assertIs<SongItem>(restored[0])
        assertIs<AlbumItem>(restored[1])
        assertIs<ArtistItem>(restored[2])
        assertIs<PlaylistItem>(restored[3])
        assertIs<PodcastItem>(restored[4])
        assertEquals("PODCAST", SpeedDialItem.fromYTItem(items[4]).type)
        assertEquals("Host", (restored[4] as PodcastItem).author)
    }

    @Test
    fun artistNamesAndNullableIdsRoundTripWithoutDelimiterLoss() {
        val original =
            SongItem(
                id = "song",
                title = "Song",
                thumbnail = null,
                artists =
                    listOf(
                        ArtistTiny(id = "artist-1", name = "Earth, Wind & Fire"),
                        ArtistTiny(id = null, name = "Guest, Jr."),
                    ),
                album = null,
            )

        val restored = SpeedDialItem.fromYTItem(original).toYTItem() as SongItem

        assertEquals(original.artists, restored.artists)
    }

    @Test
    fun historyPlaceholderArtistIdsAreNotRestored() {
        val original =
            SongItem(
                id = "song",
                title = "Song",
                thumbnail = null,
                artists = listOf(ArtistTiny("unknown", "Artist"), ArtistTiny("LH:song:1", "Guest")),
                album = null,
            )

        val restored = SpeedDialItem.fromYTItem(original).toYTItem() as SongItem

        assertEquals(listOf(null, null), restored.artists.map { it.id })
    }
}
