package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeedDialPolicyTest {
    @Test
    fun fullPinCapacityPreservesEveryPin() {
        val pins = List(27) { song("pin-$it") }
        val result = mergeSpeedDialItems(pins, listOf(song("extra")))

        assertEquals(pins, result)
    }

    @Test
    fun mergedSpeedDialKeepsPinsBeforeDeduplicatedRecommendations() {
        val pins = listOf(song("pinned"), song("duplicate"))
        val recommendations = listOf(song("duplicate")) + List(30) { song("recommendation-$it") }

        val result = mergeSpeedDialItems(pins, recommendations)

        assertEquals(pins + recommendations.drop(1).take(24), result)
    }

    @Test
    fun duplicatePinAliasesDoNotDisplaceRecommendations() {
        val playlist = PlaylistItem("PLplaylist", "Playlist", null)
        val pins = List(25) { song("pin-$it") } + playlist + playlist.copy(id = "VLPLplaylist")

        assertEquals(pins.take(26), mergeSpeedDialItems(pins, listOf(song("extra"))))
    }

    @Test
    fun quickPicksPrecedeBalancedHomeShelves() {
        val result =
            speedDialRecommendations(
                quickPicks = listOf(song("quick-1"), song("quick-2")),
                otherShelves =
                    listOf(
                        listOf(song("shelf-a-1"), song("shelf-a-2")),
                        listOf(PlaylistItem("shelf-b-1", "", null), song("shelf-b-2")),
                    ),
                localFallback = listOf(song("local-history")),
            )

        assertEquals(
            listOf("quick-1", "quick-2", "shelf-a-1", "shelf-b-1", "shelf-a-2", "shelf-b-2"),
            result.map { it.id },
        )
    }

    @Test
    fun localSignalsFillAnEmptyHomeSpeedDial() {
        val result =
            speedDialRecommendations(
                quickPicks = emptyList(),
                otherShelves = emptyList(),
                localFallback = listOf(song("local-history"), song("local-picks")),
            )

        assertEquals(listOf("local-history", "local-picks"), result.map { it.id })
    }

    @Test
    fun richerRecommendationEnrichesPinnedSongWithoutMovingIt() {
        val pin =
            song("song").copy(
                artists = listOf(ArtistTiny("artist", "Artist", "artist.jpg")),
                album = AlbumTiny("album", "Album"),
                musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            )
        val recommendation =
            pin.copy(
                artists = listOf(ArtistTiny(null, "Artist")),
                album = AlbumTiny(null, ""),
                duration = 180,
                musicVideoType = null,
            )

        val result = mergeSpeedDialItems(listOf(pin), listOf(song("other"), recommendation))

        assertEquals(listOf(pin.copy(duration = 180), song("other")), result)
    }

    @Test
    fun cachedPinnedMetadataSurvivesWithoutCurrentRecommendation() {
        val rawPin = song("song")
        val cachedPin =
            rawPin.copy(
                duration = 180,
                musicVideoType = "MUSIC_VIDEO_TYPE_VIDEO",
                isLocal = true,
                endpointParams = "params",
            )

        assertEquals(listOf(cachedPin), enrichSpeedDialPins(listOf(rawPin), mapOf(rawPin.id to cachedPin)))
    }

    @Test
    fun matchingCollectionRecommendationReplacesThinPin() {
        val pin = PlaylistItem("playlist", "Playlist", null)
        val recommendation = pin.copy(playEndpointParams = "play", radioEndpointParams = "radio")

        assertEquals(listOf(recommendation), mergeSpeedDialItems(listOf(pin), listOf(recommendation)))
    }

    @Test
    fun sparseAlbumRecommendationKeepsPinnedMetadata() {
        val pin =
            AlbumItem(
                id = "album",
                title = "Pinned album",
                thumbnail = "pinned.jpg",
                artists = listOf(ArtistTiny("artist", "Artist", "artist.jpg")),
                playlistId = "playlist",
                isExplicit = true,
            )
        val recommendation =
            AlbumItem(
                id = "album",
                title = "",
                thumbnail = null,
                artists = listOf(ArtistTiny(null, "Artist")),
                year = "2026",
            )

        assertEquals(listOf(pin.copy(year = "2026")), mergeSpeedDialItems(listOf(pin), listOf(recommendation)))
    }

    @Test
    fun differentCollectionSubtypeCannotReplacePin() {
        val pin = PlaylistItem("item", "Playlist", "playlist.jpg", author = "Author")
        val recommendation = ArtistItem("item", "Artist", "artist.jpg")

        assertEquals(listOf(pin), mergeSpeedDialItems(listOf(pin), listOf(recommendation)))
    }

    @Test
    fun speedDialRecommendationsDeduplicateCanonicalPlaylists() {
        val vlPlaylist = PlaylistItem("VLPLabc", "Playlist", null)
        val plainPlaylist = PlaylistItem("PLabc", "Playlist", null)

        val result =
            speedDialRecommendations(
                quickPicks = emptyList(),
                otherShelves = listOf(listOf(vlPlaylist, plainPlaylist)),
                localFallback = emptyList(),
            )

        assertEquals(1, result.size)
        assertEquals("VLPLabc", result.single().id)
    }

    @Test
    fun speedDialRecommendationsFilterOutUserPlaylists() {
        val userPlaylist = PlaylistItem("PL_user", "My Playlist", null, isEditable = true)
        val libraryPlaylist = PlaylistItem("PL_saved", "Saved Playlist", null, isEditable = false)
        val editorialPlaylist = PlaylistItem("PL_editorial", "Editorial", null, isEditable = false)
        val recommendedSong = song("song_rec")

        val result =
            speedDialRecommendations(
                quickPicks = emptyList(),
                otherShelves = listOf(listOf(userPlaylist, libraryPlaylist, editorialPlaylist, recommendedSong)),
                localFallback = emptyList(),
                excludedPlaylistIds = setOf("PL_saved"),
            )

        assertEquals(listOf("PL_editorial", "song_rec"), result.map { it.id })
    }

    @Test
    fun pinnedUserPlaylistsArePreservedInSpeedDial() {
        val pinnedUserPlaylist = PlaylistItem("PL_user", "My Playlist", null, isEditable = true)
        val recSong = song("song_rec")

        val result =
            mergeSpeedDialItems(
                pinned = listOf(pinnedUserPlaylist),
                recommendations = listOf(recSong),
            )

        assertEquals(listOf("PL_user", "song_rec"), result.map { it.id })
    }

    private fun song(id: String) = SongItem(id, "", null, emptyList(), null)
}
