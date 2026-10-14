package com.metrolist.music.utils

import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.music.db.entities.PlaylistSongMap
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSyncTest {
    @Test
    fun `incomplete liked songs skip remote removals`() {
        assertEquals(false, hasCompleteLikedSongsResponse(fetchedCount = 296, advertisedCount = 321))
        assertEquals(true, hasCompleteLikedSongsResponse(fetchedCount = 296, advertisedCount = null))
    }

    @Test
    fun `only items previously seen remotely are removed`() {
        val local = listOf("removedOnYouTube", "localOnly", "stillRemote")
        assertEquals(
            setOf("removedOnYouTube"),
            idsRemovedRemotely(local, previousRemoteIds = setOf("removedOnYouTube", "stillRemote"), remoteIds = setOf("stillRemote")),
        )
        assertEquals(emptySet<String>(), idsRemovedRemotely(local, previousRemoteIds = null, remoteIds = setOf("stillRemote")))
    }

    @Test
    fun `local-only songs are preserved`() {
        assertEquals(
            listOf(2),
            localSongIndexesAbsentFromRemote(listOf("a", "b", "c"), listOf("a", "b")),
        )
    }

    @Test
    fun `duplicate occurrences are compared separately`() {
        assertEquals(
            listOf(1),
            localSongIndexesAbsentFromRemote(listOf("a", "a"), listOf("a")),
        )
    }

    @Test
    fun `remote ordering does not create local-only songs`() {
        assertEquals(
            emptyList<Int>(),
            localSongIndexesAbsentFromRemote(listOf("a", "b"), listOf("b", "a")),
        )
    }

    private fun emptyPage(songCountText: String?) =
        PlaylistPage(
            playlist =
                PlaylistItem(
                    id = "playlist",
                    title = "Playlist",
                    author = null,
                    songCountText = songCountText,
                    thumbnail = null,
                    playEndpoint = null,
                    shuffleEndpoint = null,
                    radioEndpoint = null,
                ),
            songs = emptyList(),
            songsContinuation = null,
            continuation = null,
        )

    @Test
    fun `empty fetch with advertised songs is not genuine`() {
        assertEquals(false, isGenuineEmptyPlaylist(emptyPage("37 songs")))
    }

    @Test
    fun `empty fetch with zero advertised songs is genuine`() {
        assertEquals(true, isGenuineEmptyPlaylist(emptyPage("0 songs")))
    }

    @Test
    fun `empty fetch without advertised count is not genuine`() {
        assertEquals(false, isGenuineEmptyPlaylist(emptyPage(null)))
    }

    @Test
    fun `non-empty fetch is never genuine empty`() {
        val page =
            emptyPage("37 songs").copy(
                songs = listOf(SongItem(id = "a", title = "A", artists = emptyList(), thumbnail = "")),
            )
        assertEquals(false, isGenuineEmptyPlaylist(page))
    }

    private fun localMap(songId: String, position: Int, setVideoId: String? = null) =
        PlaylistSongMap(playlistId = "playlist", songId = songId, position = position, setVideoId = setVideoId)

    private fun remoteSong(id: String, setVideoId: String? = null) =
        SongItem(id = id, title = id, artists = emptyList(), thumbnail = "", setVideoId = setVideoId)

    @Test
    fun `backfill fills null setVideoIds positionally`() {
        assertEquals(
            listOf(localMap("a", 0, "set-a")),
            setVideoIdBackfills(
                listOf(localMap("a", 0), localMap("b", 1, "set-b")),
                listOf("set-a", "set-b"),
            ),
        )
    }

    @Test
    fun `backfill returns empty when already in sync`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            setVideoIdBackfills(
                listOf(localMap("a", 0, "set-a")),
                listOf("set-a"),
            ),
        )
    }

    @Test
    fun `backfill returns empty on size mismatch`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            setVideoIdBackfills(
                listOf(localMap("a", 0)),
                listOf("set-a", "set-b"),
            ),
        )
    }

    @Test
    fun `backfill skips null remote setVideoIds`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            setVideoIdBackfills(
                listOf(localMap("a", 0)),
                listOf(null),
            ),
        )
    }

    @Test
    fun `added songs map newest remote occurrence onto pending row appended at end`() {
        assertEquals(
            listOf(localMap("x", 1, "set-x-new")),
            setVideoIdUpdatesForAddedSongs(
                addedSongIds = listOf("x"),
                remoteSongs = listOf(remoteSong("y", "set-y"), remoteSong("x", "set-x-new")),
                localSongs = listOf(localMap("y", 0, "set-y"), localMap("x", 1)),
            ),
        )
    }

    @Test
    fun `added songs map newest remote occurrence onto pending row prepended at start`() {
        assertEquals(
            listOf(localMap("x", 0, "set-x-new")),
            setVideoIdUpdatesForAddedSongs(
                addedSongIds = listOf("x"),
                remoteSongs = listOf(remoteSong("x", "set-x-new"), remoteSong("y", "set-y")),
                localSongs = listOf(localMap("x", 0), localMap("y", 1, "set-y")),
            ),
        )
    }

    @Test
    fun `added songs leave rows that already carry a setVideoId untouched`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            setVideoIdUpdatesForAddedSongs(
                addedSongIds = listOf("x"),
                remoteSongs = listOf(remoteSong("x", "set-x-new")),
                localSongs = listOf(localMap("x", 0, "set-x-old")),
            ),
        )
    }

    @Test
    fun `added songs return empty when song is missing remotely`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            setVideoIdUpdatesForAddedSongs(
                addedSongIds = listOf("x"),
                remoteSongs = listOf(remoteSong("y", "set-y")),
                localSongs = listOf(localMap("x", 0), localMap("y", 1, "set-y")),
            ),
        )
    }

    @Test
    fun `added songs map duplicate occurrences from newest backwards`() {
        assertEquals(
            listOf(localMap("x", 1, "set-x-2"), localMap("x", 0, "set-x-1")),
            setVideoIdUpdatesForAddedSongs(
                addedSongIds = listOf("x", "x"),
                remoteSongs = listOf(remoteSong("x", "set-x-1"), remoteSong("x", "set-x-2")),
                localSongs = listOf(localMap("x", 0), localMap("x", 1)),
            ),
        )
    }

    @Test
    fun `added songs never reuse an already known setVideoId`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            setVideoIdUpdatesForAddedSongs(
                addedSongIds = listOf("x"),
                remoteSongs = listOf(remoteSong("y", "set-y"), remoteSong("x", "set-y")),
                localSongs = listOf(localMap("x", 0), localMap("y", 1, "set-y")),
            ),
        )
    }

    @Test
    fun `added songs ignore remote setVideoIds already stored locally`() {
        assertEquals(
            listOf(localMap("x", 1, "set-x-new")),
            setVideoIdUpdatesForAddedSongs(
                addedSongIds = listOf("x"),
                remoteSongs = listOf(remoteSong("x", "set-x-new"), remoteSong("x", "set-x-old")),
                localSongs = listOf(localMap("x", 0, "set-x-old"), localMap("x", 1)),
            ),
        )
    }

    @Test
    fun `added songs skip ambiguous duplicates when only one add succeeded`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            setVideoIdUpdatesForAddedSongs(
                addedSongIds = listOf("x"),
                remoteSongs = listOf(remoteSong("x", "set-x-1")),
                localSongs = listOf(localMap("x", 0), localMap("x", 1)),
            ),
        )
    }

    @Test
    fun `added songs skip when remote has more new occurrences than confirmed adds`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            setVideoIdUpdatesForAddedSongs(
                addedSongIds = listOf("x"),
                remoteSongs = listOf(remoteSong("x", "set-x-1"), remoteSong("x", "set-x-2")),
                localSongs = listOf(localMap("x", 0)),
            ),
        )
    }

    @Test
    fun `confirmed remote deletion is dropped while unconfirmed local addition is preserved`() {
        assertEquals(
            listOf(localMap("b", 1)),
            preservedLocalSongs(
                listOf(localMap("a", 0, "set-a"), localMap("b", 1), localMap("c", 2, "set-c")),
                listOf("a"),
                listOf("set-a"),
            ),
        )
    }

    @Test
    fun `duplicate pending copy is preserved while synced copy is consumed`() {
        assertEquals(
            listOf(localMap("x", 1)),
            preservedLocalSongs(
                listOf(localMap("x", 0, "set-x"), localMap("x", 1)),
                listOf("x"),
                listOf("set-x"),
            ),
        )
    }

    @Test
    fun `unresolved duplicate stays pending and the next sync keeps one pending copy`() {
        assertEquals(
            listOf(localMap("x", 1)),
            preservedLocalSongs(
                listOf(localMap("x", 0), localMap("x", 1)),
                listOf("x"),
                listOf("set-x"),
            ),
        )
    }

    @Test
    fun `pending duplicate is preserved when a confirmed copy still exists remotely`() {
        assertEquals(
            listOf(localMap("x", 0)),
            preservedLocalSongs(
                listOf(localMap("x", 0), localMap("x", 1, "set-old")),
                listOf("x"),
                listOf("set-old"),
            ),
        )
    }

    @Test
    fun `pending and confirmed duplicates are both handled when remote has none`() {
        assertEquals(
            listOf(localMap("x", 0)),
            preservedLocalSongs(
                listOf(localMap("x", 0), localMap("x", 1, "set-old")),
                emptyList(),
                emptyList(),
            ),
        )
    }

    @Test
    fun `confirmed occurrence claimed by setVideoId, not by songId`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            preservedLocalSongs(
                listOf(localMap("x", 0), localMap("x", 1, "set-old")),
                listOf("x"),
                listOf("set-new"),
            ),
        )
    }

    @Test
    fun `readded occurrence does not confirm the deleted copy in reverse order`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            preservedLocalSongs(
                listOf(localMap("x", 0, "set-old"), localMap("x", 1)),
                listOf("x"),
                listOf("set-new"),
            ),
        )
    }

    @Test
    fun `extra confirmed copy beyond remote count is dropped`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            preservedLocalSongs(
                listOf(localMap("x", 0, "set-1"), localMap("x", 1, "set-2")),
                listOf("x"),
                listOf("set-1"),
            ),
        )
    }

    @Test
    fun `confirmed row is dropped when the remote occurrence has no setVideoId`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            preservedLocalSongs(
                listOf(localMap("x", 0, "set-a")),
                listOf("x"),
                listOf(null),
            ),
        )
    }

    @Test
    fun `mixed confirmed and pending duplicates keep only the unmatched pending copy`() {
        assertEquals(
            listOf(localMap("x", 2)),
            preservedLocalSongs(
                listOf(localMap("x", 0, "set-1"), localMap("x", 1), localMap("x", 2)),
                listOf("x", "x"),
                listOf("set-1", "set-new"),
            ),
        )
    }

    @Test
    fun `pending row takes the leftover occurrence when remote has no setVideoId`() {
        assertEquals(
            emptyList<PlaylistSongMap>(),
            preservedLocalSongs(
                listOf(localMap("x", 0, "set-a"), localMap("x", 1)),
                listOf("x"),
                listOf(null),
            ),
        )
    }
}
