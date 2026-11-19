package com.metrolist.shared.ui.menu

import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongMenuNavigationTest {
    @Test
    fun storedLibrarySongsKeepArtistAndAlbumLinksInDownloadsAndCache() {
        val stored =
            Song(
                SongEntity("song", "Song", albumId = "MPREb_album", albumName = "Album"),
                artists = listOf(ArtistEntity("UCartist", "Artist")),
            )
        val expected = songMenuNavigation(stored.toSongItem())
        assertEquals(listOf("UCartist"), expected.artists.map { it.id })
        assertEquals("MPREb_album", expected.albumId)

        for (kind in listOf(KIND_DOWNLOAD, KIND_CACHE)) {
            val song = cachedAudio(kind).toSongItem(stored)
            assertEquals(expected, songMenuNavigation(song), kind)
            assertEquals("file:/artwork.jpg", song.thumbnail)
        }
    }

    @Test
    fun cacheSummaryWithoutStoredMetadataKeepsNamesWithoutInventingLinks() {
        val song = cachedAudio(KIND_CACHE).toSongItem(null)
        assertEquals(listOf("Artist"), song.artists.map { it.name })
        assertEquals("Album", song.album?.name)
        assertTrue(songMenuNavigation(song).artists.isEmpty())
        assertNull(songMenuNavigation(song).albumId)
    }

    @Test
    fun uploadedSongsOfferOnlyTheArtistAndAlbumTargetsTheyHave() {
        val artist = ArtistTiny("FEmusic_library_privately_owned_artist_artist", "Artist")
        val album = AlbumTiny("FEmusic_library_privately_owned_release_album", "Album")
        val upload = SongItem("upload", "Song", null, listOf(artist), album, isUploaded = true)
        assertEquals(SongMenuNavigation(listOf(artist), album.id), songMenuNavigation(upload))
        assertEquals(
            SongMenuNavigation(emptyList(), album.id),
            songMenuNavigation(upload.copy(artists = listOf(ArtistTiny(null, "Artist")))),
        )
        for (albumId in listOf(null, "", " ")) {
            assertEquals(
                SongMenuNavigation(listOf(artist), null),
                songMenuNavigation(upload.copy(album = album.copy(id = albumId))),
            )
        }
        val storedUpload =
            Song(
                SongEntity("upload", "Song", albumName = "Album", isUploaded = true),
                artists = listOf(ArtistEntity("LA:upload:0", "Artist", isLocal = true)),
            ).toSongItem()
        assertEquals(SongMenuNavigation(emptyList(), null), songMenuNavigation(storedUpload))
    }

    @Test
    fun artistPickerKeepsDistinctTargetsAndSkipsNamesWithoutIds() {
        val first = ArtistTiny("UCfirst", "First")
        val second = ArtistTiny("UCsecond", "Second")
        val song =
            SongItem(
                "song",
                "Song",
                null,
                listOf(first, ArtistTiny(null, "Unknown"), ArtistTiny(" ", "Blank"), second, first),
                null,
            )
        assertEquals(listOf(first, second), songMenuNavigation(song).artists)
    }

    private fun cachedAudio(kind: String) =
        CachedAudioEntity(
            songId = "song",
            title = "Song",
            artist = "Artist",
            album = "Album",
            thumbnailUrl = "file:/artwork.jpg",
            filePath = "audio.m4a",
            fileSize = 100,
            cachedAt = 1,
            lastAccessedAt = 1,
            cacheLocation = "internal",
            kind = kind,
        )
}
