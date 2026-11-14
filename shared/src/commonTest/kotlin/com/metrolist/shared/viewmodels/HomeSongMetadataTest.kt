package com.metrolist.shared.viewmodels

import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canSwitchToAudioTrack
import com.metrolist.shared.model.isNonMusicalVideo
import com.metrolist.shared.model.isVideoSong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeSongMetadataTest {
    @Test
    fun apiHomeSongUsesMissingMetadataFromLocalSong() {
        val local =
            SongItem(
                id = "song",
                title = "Local title",
                thumbnail = "local.jpg",
                artists = listOf(ArtistTiny("artist", "Artist", "artist.jpg")),
                album = AlbumTiny("album", "Album"),
                duration = 180,
                isExplicit = true,
                endpointParams = "params",
                libraryAddToken = "add",
                libraryRemoveToken = "remove",
                musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
                isUploaded = true,
                isLocal = true,
                uploadEntityId = "upload",
                podcastId = "podcast",
                year = 2025,
                explicitness = true,
            )
        val api = SongItem("song", "API title", null, listOf(ArtistTiny(null, "Artist")), AlbumTiny(null, ""))

        assertEquals(
            local.copy(title = api.title),
            api.withFallbackMetadata(local),
        )
    }

    @Test
    fun localCustomMetadataIsUsedForHomeSongs() {
        val item =
            Song(
                song = SongEntity(id = "song", title = "Remote title", customTitle = "Edited title", customArtist = "Edited artist"),
                artists = listOf(ArtistEntity(id = "artist", name = "Remote artist")),
            ).toSongItem()

        assertEquals("Edited title", item.title)
        assertEquals("Edited title", item.customTitle)
        assertEquals("Remote title", item.canonicalTitle)
        assertEquals(listOf("Edited artist"), item.artists.map { it.name })
        assertEquals(listOf("Remote artist"), item.canonicalArtists?.map { it.name })
        assertEquals("artist", item.artists.single().id)
        assertEquals("Edited artist", item.customArtist)
    }

    @Test
    fun apiAlbumDoesNotBorrowIdFromDifferentLocalAlbum() {
        val api = SongItem("song", "Song", null, emptyList(), AlbumTiny(null, "API Album"))
        val local = SongItem("song", "Song", null, emptyList(), AlbumTiny("local-album", "Local Album"))

        assertNull(api.withFallbackMetadata(local).album?.id)
    }

    @Test
    fun refreshedMetadataOnlyReplacesTheMatchingSong() {
        val target = SongItem("target", "Old", "old.jpg", emptyList(), null, endpointParams = "play-target")
        val untouched = SongItem("other", "Other", "other.jpg", emptyList(), null)
        val refreshed = target.copy(title = "New", thumbnail = "new.jpg", endpointParams = null)
        val state =
            HomeViewModel
                .HomeUiState(
                    speedDialItems = listOf(target, untouched),
                    data = listOf(HomeSection("Section", listOf(target, untouched))),
                    discoverySections = listOf(HomeSection("Discovery", listOf(target, untouched))),
                ).withUpdatedSongMetadata(refreshed)

        assertEquals(listOf("New", "Other"), state.speedDialItems.map { it.title })
        assertEquals(
            listOf("New", "Other"),
            state.discoverySections
                .single()
                .items
                .map { it.title },
        )
        assertEquals(
            "new.jpg",
            state.data
                ?.single()
                ?.items
                ?.first()
                ?.thumbnail,
        )
        assertEquals("play-target", (state.speedDialItems.first() as SongItem).endpointParams)
    }

    @Test
    fun localDatabaseSongRemainsLocalOnHome() {
        val item =
            Song(
                song = SongEntity(id = "local-song", title = "Song", albumName = "Album", isLocal = true),
                artists = emptyList(),
            ).toSongItem()

        assertTrue(item.isLocal)
        assertTrue(item.artists.isEmpty())
    }

    @Test
    fun databaseVideoUsesUnspecifiedVideoType() {
        val item =
            Song(
                song = SongEntity(id = "video", title = "Video", isVideo = true),
                artists = emptyList(),
            ).toSongItem()

        assertEquals(MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO, item.musicVideoType)
        assertTrue(item.isVideoSong)
        assertTrue(item.canSwitchToAudioTrack)
        assertFalse(item.isNonMusicalVideo)
    }

    @Test
    fun storedSongMapperPreservesUploadAndLibraryMetadata() {
        val item =
            Song(
                song =
                    SongEntity(
                        id = "file:track",
                        title = "Track",
                        isLocal = true,
                        albumId = "album",
                        albumName = "Album",
                        year = 2024,
                        libraryAddToken = "add",
                        libraryRemoveToken = "remove",
                        uploadEntityId = "upload",
                        explicitness = false,
                    ),
                artists = emptyList(),
            ).toSongItem()

        assertEquals("file:track", item.endpointParams)
        assertEquals(AlbumTiny("album", "Album"), item.album)
        assertEquals(2024, item.year)
        assertEquals("add", item.libraryAddToken)
        assertEquals("remove", item.libraryRemoveToken)
        assertEquals("upload", item.uploadEntityId)
        assertEquals(false, item.explicitness)
    }

    @Test
    fun localArtistIdsStayInternal() {
        val item =
            Song(
                song = SongEntity(id = "song", title = "Song"),
                artists = listOf(ArtistEntity(id = "LH:song:0", name = "Artist", isLocal = true)),
            ).toSongItem()

        assertNull(item.artists.single().id)
    }

    @Test
    fun cachedHistoryPlaceholderArtistIdsAreDiscarded() {
        val cached = SongItem("song", "Song", null, listOf(ArtistTiny("unknown", "Artist")), null)
        val fallback = cached.copy(artists = listOf(ArtistTiny("LH:song:0", "Artist")))

        assertNull(
            cached
                .withFallbackMetadata(null)
                .artists
                .single()
                .id,
        )
        assertNull(
            cached
                .withFallbackMetadata(fallback)
                .artists
                .single()
                .id,
        )
    }
}
