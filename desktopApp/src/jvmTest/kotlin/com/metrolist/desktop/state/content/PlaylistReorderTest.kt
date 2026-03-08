package com.metrolist.desktop.state.content

import com.metrolist.desktop.state.app.AppState
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.moveSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistReorderTest {
    @Test
    fun remoteFailurePreservesStoredOrderAndSuccessMovesOnlyTheRequestedOccurrence() =
        runBlocking {
            val directory = Files.createTempDirectory("playlist-reorder")
            val database = getDatabaseBuilder(directory.resolve("test.db").toFile()).build()
            try {
                val dao = database.dao()
                dao.insert(PlaylistEntity(id = "VLplaylist", name = "Playlist", browseId = "playlist", isEditable = true))
                dao.insert(SongEntity("duplicate", "Song"))
                listOf("first", "second", "third").forEachIndexed { position, occurrence ->
                    dao.insert(
                        PlaylistSongMap(
                            playlistId = "VLplaylist",
                            songId = "duplicate",
                            position = position,
                            setVideoId = occurrence,
                        ),
                    )
                }
                val original = dao.playlistSongMaps("VLplaylist", 0)
                val sections: Map<String, List<YTItem>> = mapOf("songs" to listOf(song("first"), song("second"), song("third")))
                val move = requireNotNull(sections.reorderPlaylistSong(song("first"), song("third"), afterTarget = true))
                assertTrue(
                    commitRemotePlaylistReorder(database, "playlist", move) { Result.failure(IllegalStateException("offline")) }.isFailure,
                )
                assertEquals(original, dao.playlistSongMaps("VLplaylist", 0))
                commitRemotePlaylistReorder(database, "playlist", move) {
                    assertEquals(original, dao.playlistSongMaps("VLplaylist", 0))
                    Result.success(Unit)
                }.getOrThrow()
                assertEquals(listOf("second", "third", "first"), dao.playlistSongMaps("VLplaylist", 0).map { it.setVideoId })
                val backward = requireNotNull(move.sections.reorderPlaylistSong(song("first"), song("second"), afterTarget = false))
                commitRemotePlaylistReorder(database, "playlist", backward) { Result.success(Unit) }.getOrThrow()
                assertEquals(original, dao.playlistSongMaps("VLplaylist", 0))
            } finally {
                database.close()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun dragMovesDuplicateOccurrencesAcrossSectionsAndPreservesSuggestions() {
        val first = song("first")
        val second = song("second")
        val third = song("third")
        val header = PlaylistItem("playlist", "Playlist", null)
        val suggestion = song("suggestion")
        val sections: Map<String, List<YTItem>> =
            mapOf(
                "header" to listOf(header),
                "Tracks" to listOf(first, second),
                "Continuation" to listOf(third),
                "Suggestions" to listOf(suggestion),
            )

        val forward = requireNotNull(sections.reorderPlaylistSong(first, third, afterTarget = true))
        assertEquals(listOf(second, third, first), forward.sections.playlistSongs())
        assertEquals("first", forward.setVideoId)
        assertNull(forward.successorSetVideoId)
        assertEquals(listOf(header), forward.sections["header"])
        assertEquals(listOf(suggestion), forward.sections["Suggestions"])

        val backward = requireNotNull(forward.sections.reorderPlaylistSong(first, second, afterTarget = false))
        assertEquals(listOf(first, second, third), backward.sections.playlistSongs())
        assertEquals("second", backward.successorSetVideoId)
    }

    @Test
    fun movingBeforeAVisibleTargetPreservesHiddenSongsAndUsesTheActualSuccessor() {
        val first = song("first")
        val hidden = song("hidden")
        val second = song("second")
        val third = song("third")
        val sections: Map<String, List<YTItem>> = mapOf("songs" to listOf(first, hidden, second, third))

        val move = requireNotNull(sections.reorderPlaylistSong(third, second, afterTarget = false))

        assertEquals(listOf(first, hidden, third, second), move.sections.playlistSongs())
        assertEquals("second", move.successorSetVideoId)
        val reversed = requireNotNull(move.sections.reorderPlaylistSong(third, first, afterTarget = true))
        assertEquals(listOf(first, third, hidden, second), reversed.sections.playlistSongs())
        assertEquals("hidden", reversed.successorSetVideoId)
    }

    @Test
    fun localDuplicateMovesUseMappingIdentityAndPreserveHiddenOccurrences() {
        val first = song("unused").copy(playlistSetVideoId = null, playlistLocalMappingId = 1)
        val hidden = first.copy(playlistLocalMappingId = 2)
        val third = first.copy(playlistLocalMappingId = 3)
        val sections: Map<String, List<YTItem>> = mapOf("songs" to listOf(first, hidden, third))
        val move = requireNotNull(sections.reorderPlaylistSong(third, first, afterTarget = true))
        assertEquals(listOf(first, third, hidden), move.sections.playlistSongs())
        assertEquals(3, move.localMappingId)
        assertEquals(2, move.successorLocalMappingId)
        assertNull(move.setVideoId)
        assertNull(move.successorSetVideoId)
    }

    @Test
    fun staleOrMissingOccurrencesDoNotPrepareRemoteMoves() {
        val first = song("first")
        val second = song("second")
        val sections: Map<String, List<YTItem>> = mapOf("songs" to listOf(first, second))

        assertNull(sections.reorderPlaylistSong(song("removed"), second, afterTarget = false))
        assertNull(sections.reorderPlaylistSong(first, song("removed"), afterTarget = true))
        assertNull(sections.reorderPlaylistSong(first, first, afterTarget = true))
        assertNull(sections.reorderPlaylistSong(first.copy(playlistSetVideoId = null), second, afterTarget = true))
    }

    @Test
    fun retainedReorderActionAfterPlaylistRefreshIsANoOp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val song = SongItem("removed", "Title", null, emptyList(), null, playlistSetVideoId = "occurrence")
        try {
            PlaylistAlbumState.selectedPlaylistId = "playlist"
            PlaylistAlbumState.playlistData = emptyMap()
            PlaylistAlbumState.moveSong(song, -1)
            PlaylistAlbumState.moveSong(song, 1)
            PlaylistAlbumState.moveDisplayedSong(-1, 0)
            PlaylistAlbumState.commitPendingReorder()
            assertEquals(emptyMap(), PlaylistAlbumState.playlistData)

            val replacement = mapOf("songs" to listOf(song.copy(id = "other", playlistSetVideoId = "other-occurrence")))
            PlaylistAlbumState.playlistData = replacement
            PlaylistAlbumState.moveSong(song, 1)
            assertEquals(replacement, PlaylistAlbumState.playlistData)
        } finally {
            PlaylistAlbumState.selectedPlaylistId = null
            PlaylistAlbumState.playlistData = emptyMap()
            AppState.scope.coroutineContext.cancelChildren()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun batchPlanPreservesDuplicateOccurrencesAndHiddenRows() {
        val songs = listOf("first", "hidden", "second", "third", "fourth").map(::song)
        val sections: Map<String, List<YTItem>> = mapOf("songs" to songs)
        val reordered = songs.moveSelection(0, 4, setOf(0, 3))
        val moves = requireNotNull(sections.reorderPlaylistSongs(reordered, setOf(songs[0], songs[3])))
        assertEquals(
            listOf<Pair<String?, String?>>("third" to null, "first" to "third"),
            moves.map {
                it.setVideoId to
                    it.successorSetVideoId
            },
        )
        assertEquals(
            listOf("hidden", "second", "fourth", "first", "third"),
            moves
                .last()
                .sections
                .playlistSongs()
                .map { it.playlistSetVideoId },
        )
        assertTrue(sections.reorderPlaylistSongs(songs, setOf(songs[0], songs[3]))!!.isEmpty())
    }

    private fun song(occurrence: String) = SongItem("duplicate", "Title", null, emptyList(), null, playlistSetVideoId = occurrence)
}
