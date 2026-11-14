package com.metrolist.android.playback

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomotiveMediaIdsTest {
    @Test
    fun encodedIdsRoundTripReservedCharacters() {
        val rawId = "VL:playlist/id+with=characters"

        assertEquals(rawId, AutomotiveMediaIds.songId(AutomotiveMediaIds.song(rawId)))
        val browseId = AutomotiveMediaIds.browseSong("collection:/one", 2, rawId)
        assertEquals(AutomotiveMediaIds.BrowseSong("collection:/one", 2, rawId), AutomotiveMediaIds.browseSong(browseId))
        assertEquals(rawId, AutomotiveMediaIds.songId(browseId))
        assertEquals(rawId, AutomotiveMediaIds.albumId(AutomotiveMediaIds.album(rawId)))
        assertEquals(rawId, AutomotiveMediaIds.artistId(AutomotiveMediaIds.artist(rawId)))
        assertEquals(rawId, AutomotiveMediaIds.localPlaylistId(AutomotiveMediaIds.localPlaylist(rawId)))
        assertEquals(rawId, AutomotiveMediaIds.youtubePlaylistId(AutomotiveMediaIds.youtubePlaylist(rawId)))
        assertEquals(rawId, AutomotiveMediaIds.pinnedLocalPlaylistId(AutomotiveMediaIds.pinnedLocalPlaylist(rawId)))
        assertEquals(rawId, AutomotiveMediaIds.pinnedYoutubePlaylistId(AutomotiveMediaIds.pinnedYoutubePlaylist(rawId)))
        assertEquals(rawId, AutomotiveMediaIds.pinnedAutoPlaylistId(AutomotiveMediaIds.pinnedAutoPlaylist(rawId)))
        assertEquals(
            AutomotiveMediaIds.QueueSong(12L, rawId),
            AutomotiveMediaIds.queueSong(AutomotiveMediaIds.queueSong(12L, rawId)),
        )
    }

    @Test
    fun decoderRejectsWrongOrMalformedType() {
        assertNull(AutomotiveMediaIds.albumId(AutomotiveMediaIds.song("song")))
        assertNull(AutomotiveMediaIds.songId("auto:song:not-valid-base64!"))
        assertNull(AutomotiveMediaIds.queueSong("auto:queue:-1:c29uZw"))
        assertNull(AutomotiveMediaIds.browseSong("auto:browse-song:cGFyZW50:-1:c29uZw"))
        assertNull(AutomotiveMediaIds.browseSong("auto:browse-song:invalid!:0:c29uZw"))
        assertNull(AutomotiveMediaIds.browseSong("auto:browse-song:cGFyZW50:0"))
        assertNull(AutomotiveMediaIds.queueSong("auto:queue:not-an-index:c29uZw"))
        assertNull(AutomotiveMediaIds.queueSong("auto:queue:1:not-valid-base64!"))
    }

    @Test
    fun storedPlaylistsKeepLocalAndRemoteSourcesDistinct() {
        val local = PlaylistEntity(id = "LP1", name = "Local", isLocal = true)
        val legacyLocal = PlaylistEntity(id = "LP2", name = "Legacy local")
        val remote = PlaylistEntity(id = "stored", name = "Remote", browseId = "VLPLremote")

        assertEquals("LP1", AutomotiveMediaIds.localPlaylistId(automotiveStoredPlaylistMediaId(local)))
        assertEquals("LP2", AutomotiveMediaIds.localPlaylistId(automotiveStoredPlaylistMediaId(legacyLocal)))
        assertEquals("VLPLremote", AutomotiveMediaIds.youtubePlaylistId(automotiveStoredPlaylistMediaId(remote)))
    }

    @Test
    fun queueIdsFollowTheirOccurrenceAcrossEarlierRemovalAndReorder() {
        val queue = MusicQueue().apply { setQueue(listOf(song("duplicate"), song("other"), song("duplicate"))) }
        val selected = queue.state.value.entries[2]
        val mediaId = AutomotiveMediaIds.queueSong(selected.key, selected.song.id)

        assertEquals(2, automotiveQueueIndex(mediaId, queue.state.value.entries))
        queue.removeAt(setOf(0))
        assertEquals(1, automotiveQueueIndex(mediaId, queue.state.value.entries))
        queue.move(1, 0)
        assertEquals(0, automotiveQueueIndex(mediaId, queue.state.value.entries))
        assertNull(
            automotiveQueueIndex(
                AutomotiveMediaIds.queueSong(selected.key, "other"),
                queue.state.value.entries,
            ),
        )
        queue.removeAt(setOf(0))
        assertNull(automotiveQueueIndex(mediaId, queue.state.value.entries))
    }

    @Test
    fun likedSongsPreferAccountSnapshotAndUseRoomOnlyForSignedOutLegacyLibrary() =
        runBlocking {
            val remote = listOf(song("account-song"))
            val local = listOf(song("local-song"), song("older-local-song"))
            var roomReads = 0
            val loadRoom: suspend () -> List<SongItem> = {
                roomReads++
                local
            }

            assertEquals(remote, automotiveLikedSongs(remote, signedOut = false, loadLegacy = loadRoom))
            assertEquals(remote, automotiveLikedSongs(remote, signedOut = true, loadLegacy = loadRoom))
            assertEquals(emptyList<SongItem>(), automotiveLikedSongs(emptyList(), signedOut = false, loadLegacy = loadRoom))
            assertEquals(local, automotiveLikedSongs(emptyList(), signedOut = true, loadLegacy = loadRoom))
            assertEquals(1, roomReads)
        }

    @Test
    fun likeRefreshInvalidatesOnlyActiveLikedQueueContexts() {
        val sharedId = AutomotiveMediaIds.song("shared")
        val likedOnlyId = AutomotiveMediaIds.song("liked-only")
        val albumId = AutomotiveMediaIds.song("album")
        val liked = AutomotiveSongQueue(listOf(song("shared"), song("liked-only")), 0)
        val likedOnly = AutomotiveSongQueue(liked.songs, 1)
        val album = AutomotiveSongQueue(listOf(song("album"), song("shared")), 0)
        val queues = mutableMapOf(sharedId to liked, likedOnlyId to likedOnly, albumId to album)
        val likedContexts = mutableMapOf(sharedId to liked, likedOnlyId to likedOnly)

        val newer = AutomotiveSongQueue(liked.songs, 0)
        queues[sharedId] = newer
        val emptySnapshot = emptyList<SongItem>()
        val signedOut = AutomotiveLikedQueueSource(emptySnapshot, "signed-out", signedOut = true)
        val signedIn = AutomotiveLikedQueueSource(emptySnapshot, "account-a", signedOut = false)
        assertFalse(invalidateLikedQueueContexts(queues, likedContexts, signedOut, signedOut))
        assertSame(likedOnly, queues[likedOnlyId])
        assertTrue(invalidateLikedQueueContexts(queues, likedContexts, signedOut, signedIn))

        assertSame(newer, queues[sharedId])
        assertEquals(album, queues[albumId])
        assertNull(queues[likedOnlyId])
        assertEquals(emptyMap<String, AutomotiveSongQueue>(), likedContexts)

        queues[likedOnlyId] = likedOnly
        likedContexts[likedOnlyId] = likedOnly
        assertTrue(invalidateLikedQueueContexts(queues, likedContexts, signedIn, signedIn.copy(account = "account-b")))
        assertNull(queues[likedOnlyId])
        assertEquals(album, queues[albumId])
    }

    @Test
    fun pagingHandlesBoundsAndOverflow() {
        val items = (0 until 10).toList()

        assertEquals(listOf(0, 1, 2), AutomotivePaging.page(items, page = 0, pageSize = 3))
        assertEquals(listOf(9), AutomotivePaging.page(items, page = 3, pageSize = 3))
        assertEquals(emptyList<Int>(), AutomotivePaging.page(items, page = 4, pageSize = 3))
        assertEquals(emptyList<Int>(), AutomotivePaging.page(items, page = Int.MAX_VALUE, pageSize = Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), AutomotivePaging.page(items, page = -1, pageSize = 3))
        assertEquals(emptyList<Int>(), AutomotivePaging.page(items, page = 0, pageSize = 0))
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = id,
            thumbnail = null,
            artists = emptyList(),
            album = null,
        )
}
