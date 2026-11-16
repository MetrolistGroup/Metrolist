package com.metrolist.desktop.state.content

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistCompletionTest {
    @Test
    fun completeStoredMembershipDoesNotWaitForRefresh() =
        runTest {
            val refresh = CompletableDeferred<Unit>()
            val action = async { awaitCompletePlaylist(refresh) { false } }
            runCurrent()
            assertTrue(action.isCompleted)
            assertTrue(action.await())
            refresh.cancel()
        }

    @Test
    fun partialMembershipWaitsAndAFailedContinuationDoesNotEnableActions() =
        runTest {
            for (succeeds in listOf(false, true)) {
                var hasMore = true
                val finish = CompletableDeferred<Unit>()
                val load =
                    launch {
                        finish.await()
                        if (succeeds) hasMore = false
                    }
                val action = async { awaitCompletePlaylist(load) { hasMore } }
                runCurrent()
                assertFalse(action.isCompleted)
                finish.complete(Unit)
                assertTrue(action.await() == succeeds)
            }
        }

    @Test
    fun remoteBookmarksNeedCompleteMembershipButLocalPlaylistsDoNot() {
        val remote =
            PlaylistEntity(
                id = "playlist",
                name = "Playlist",
                browseId = "remote",
                remoteSongCount = 100,
                remoteHeader = PlaylistItem("remote", "Playlist", null),
            )
        val songs = List(100) { SongItem("song-$it", "Song", null, emptyList(), null, playlistSetVideoId = "set-$it") }
        assertFalse(hasCompleteStoredPlaylist(remote, songs.take(20)))
        assertTrue(hasCompleteStoredPlaylist(remote, songs))
        assertFalse(hasCompleteStoredPlaylist(remote.copy(remoteHeader = null), songs))
        assertFalse(hasCompleteStoredPlaylist(remote, songs.map { it.copy(playlistSetVideoId = null) }))
        assertTrue(hasCompleteStoredPlaylist(remote.copy(isEditable = false), songs.map { it.copy(playlistSetVideoId = null) }))
        assertFalse(hasCompleteStoredPlaylist(remote.copy(remoteSongCount = null), songs.take(20)))
        assertFalse(hasCompleteStoredPlaylist(remote.copy(remoteSongCount = 0), emptyList()))
        assertTrue(hasCompleteStoredPlaylist(remote.copy(remoteSongCount = 0, isAutoSync = true), emptyList()))
        assertTrue(hasCompleteStoredPlaylist(remote.copy(isLocal = true), songs.take(20)))
        assertTrue(hasCompleteStoredPlaylist(remote.copy(browseId = null), songs.take(20)))
    }
}
