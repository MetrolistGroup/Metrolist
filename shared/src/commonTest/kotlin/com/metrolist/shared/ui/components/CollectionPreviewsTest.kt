package com.metrolist.shared.ui.components

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.navigation.AlbumArtworkSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionPreviewsTest {
    @Test
    fun artistLinksRetainKnownHeaderDataWithoutMixingArtists() {
        val artist = ArtistItem("preview-artist", "Artist", "portrait", subscribers = "42", banner = "banner", isSubscribed = true)
        CollectionPreviews.record(artist)
        CollectionPreviews.record(ArtistTiny(artist.id, "Updated artist"))
        assertEquals(artist.copy(title = "Updated artist"), CollectionPreviews.artist(artist.id))

        val other = ArtistTiny("preview-other-artist", "Other artist", "other portrait")
        CollectionPreviews.record(other)
        assertEquals(ArtistItem(requireNotNull(other.id), other.name, other.thumbnail), CollectionPreviews.artist(other.id))
        assertEquals(artist.copy(title = "Updated artist"), CollectionPreviews.artist(artist.id))

        CollectionPreviews.record(ArtistTiny(null, "Unlinked"))
        CollectionPreviews.record(ArtistTiny(" ", "Unlinked"))
        assertNull(CollectionPreviews.artist(" "))
        assertNull(CollectionPreviews.artist("uncached-artist"))
    }

    @Test
    fun thinCardsRetainKnownMetadataBeforeNavigationWithoutRetainingSongs() {
        val artist = ArtistTiny("artist", "Artist")
        val album = AlbumItem("retained-preview", "Album", null, listOf(artist), year = "2026", type = "EP", songCount = 6, duration = 1200)
        val other = album.copy(id = "other-preview")
        CollectionPreviews.record(
            AlbumPage(
                album,
                listOf(SongItem("song", "Song", null, listOf(artist), album = null, duration = 200)),
                otherVersions = listOf(other),
            ),
        )
        val card = album.copy(artists = emptyList(), year = null, type = null, songCount = null, duration = null)
        var navigated = false
        AlbumArtworkSource(card, "card") {
            val preview = checkNotNull(CollectionPreviews.albumPage(album.id))
            assertEquals(album, preview.album)
            assertTrue(preview.songs.isEmpty())
            assertEquals(listOf(other), preview.otherVersions)
            navigated = true
        }.select()
        assertTrue(navigated)
        val updated = card.copy(songCount = 7, type = "Album")
        CollectionPreviews.record(updated)
        assertEquals(album.copy(songCount = 7, type = "Album"), CollectionPreviews.albumPage(album.id)?.album)
    }
}
