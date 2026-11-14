package com.metrolist.shared.ui.menu

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaMenuPlaybackActionsTest {
    @Test
    fun unavailableRowsCannotSeedCollectionPlaybackOrEnterSelectionQueues() {
        val controller = MenuPlaybackController()
        val unavailable = SongItem("private", "Private video", null, emptyList(), null, isUnavailable = true)
        val playable = SongItem("playable", "Playable", null, emptyList(), null)
        val actions =
            MediaMenuPlaybackActions(PlaylistItem("playlist", "Playlist", null), controller, { it(listOf(unavailable, playable)) })
        actions.play()
        assertEquals(playable, controller.playedSong)
        assertEquals(listOf(playable), controller.queue)
        actions.shuffle()
        assertEquals(listOf(playable), controller.queue)
        actions.playNext()
        assertEquals(listOf(playable), controller.nextSongs)
        actions.addToQueue()
        assertEquals(listOf(playable), controller.addedSongs)
    }

    private val songs =
        listOf(
            SongItem("first", "First", null, emptyList(), null),
            SongItem("second", "Second", null, emptyList(), null),
        )

    @Test
    fun albumAndLocalAndRemotePlaylistPlayResolvedTracksInOrder() =
        runTest {
            val collections =
                listOf(
                    AlbumItem("album", "Album", null, emptyList()),
                    PlaylistItem("remote", "Remote", null),
                    PlaylistItem("local", "Local", null, isLocal = true),
                )
            for (item in collections) {
                val controller = MenuPlaybackController()
                MediaMenuPlaybackActions(item, controller, { it(songs) }).play()
                assertEquals(songs.first(), controller.playedSong)
                assertEquals(songs, controller.queue)
                assertFalse(controller.songsOnly)
                assertEquals(if (item is AlbumItem) 1 else 0, controller.queueStarts)
            }
        }

    @Test
    fun podcastPlaysFromTheOldestEpisode() {
        val controller = MenuPlaybackController()
        val unavailable = SongItem("gone", "Gone", null, emptyList(), null, isUnavailable = true)
        MediaMenuPlaybackActions(PodcastItem("podcast", "Podcast", null), controller, { it(songs + unavailable) })
            .playOldestFirst()

        assertEquals(songs.last(), controller.playedSong)
        assertEquals(songs.reversed(), controller.queue)
    }

    @Test
    fun albumShuffleInitializesTheQueueAndPreservesOccurrences() {
        val controller = MenuPlaybackController()
        val albumSongs = songs + songs.first()
        MediaMenuPlaybackActions(AlbumItem("album", "Album", null, emptyList()), controller, { it(albumSongs) }).shuffle()

        assertEquals(1, controller.queueStarts)
        assertEquals(albumSongs.groupingBy { it.id }.eachCount(), controller.queue.groupingBy { it.id }.eachCount())
        assertEquals(controller.queue.first(), controller.playedSong)
        assertFalse(controller.songsOnly)
    }

    @Test
    fun artistPlayUsesSongsOnlyAndClearsShuffle() =
        runTest {
            val controller = MenuPlaybackController()
            controller.state.value = PlaybackUiState(isShuffled = true)
            MediaMenuPlaybackActions(ArtistItem("artist", "Artist", null), controller, { it(songs) }, resetArtistShuffle = true).play()
            assertEquals(songs.first(), controller.playedSong)
            assertEquals(songs, controller.queue)
            assertTrue(controller.songsOnly)
            assertFalse(controller.state.value.isShuffled)
        }

    @Test
    fun emptyAndFailedLoadsLeavePlaybackAloneAndCancellationPropagates() =
        runTest {
            val controller = MenuPlaybackController()
            val item = PlaylistItem("playlist", "Playlist", null)
            MediaMenuPlaybackActions(item, controller, { it(emptyList()) }).play()
            assertNull(controller.playedSong)
            withMediaMenuSongs({ error("Load failed") }) { controller.play(it.first(), it) }
            assertNull(controller.playedSong)
            assertFailsWith<CancellationException> {
                withMediaMenuSongs({ throw CancellationException() }) { controller.play(it.first(), it) }
            }
        }

    @Test
    fun desktopArtistPlayPreservesShuffleAndShuffleKeepsEveryOccurrence() {
        val controller = MenuPlaybackController()
        controller.state.value = PlaybackUiState(isShuffled = true)
        val duplicates = songs + songs.first()
        val actions = MediaMenuPlaybackActions(ArtistItem("artist", "Artist", null), controller, { it(duplicates) })
        actions.play()
        assertTrue(controller.state.value.isShuffled)
        assertEquals(duplicates, controller.queue)
        actions.shuffle()
        assertEquals(duplicates.groupingBy { it.id }.eachCount(), controller.queue.groupingBy { it.id }.eachCount())
        assertEquals(controller.queue.first(), controller.playedSong)
        assertTrue(controller.songsOnly)
    }

    @Test
    fun queueActionsPreserveOrderAndIgnoreEmptyCollections() {
        val controller = MenuPlaybackController()
        var resolved = songs + songs.first()
        val actions = MediaMenuPlaybackActions(PlaylistItem("playlist", "Playlist", null), controller, { it(resolved) })
        actions.playNext()
        assertEquals(resolved, controller.nextSongs)
        actions.addToQueue()
        assertEquals(resolved, controller.addedSongs)
        assertNull(controller.playedSong)
        resolved = emptyList()
        actions.playNext()
        actions.addToQueue()
        actions.shuffle()
        assertEquals(songs + songs.first(), controller.nextSongs)
        assertEquals(songs + songs.first(), controller.addedSongs)
        assertNull(controller.playedSong)
    }

    @Test
    fun playbackFailuresPropagateAfterSuccessfulLoading() =
        runTest {
            val failure = IllegalStateException("Playback failed")
            val thrown =
                assertFailsWith<IllegalStateException> {
                    withMediaMenuSongs({ songs }) { throw failure }
                }
            assertEquals(failure, thrown)
        }
}

private class MenuPlaybackController : PlaybackController {
    override val state = MutableStateFlow(PlaybackUiState())
    var playedSong: SongItem? = null
    var queue = emptyList<SongItem>()
    var songsOnly = false
    var queueStarts = 0
    var nextSongs = emptyList<SongItem>()
    var addedSongs = emptyList<SongItem>()

    override fun play(
        song: SongItem,
        queue: List<SongItem>,
        songsOnly: Boolean,
    ) {
        playedSong = song
        this.queue = queue
        this.songsOnly = songsOnly
    }

    override fun toggleShuffle() {
        state.value = state.value.copy(isShuffled = !state.value.isShuffled)
    }

    override fun playQueue(
        songs: List<SongItem>,
        startIndex: Int,
        queueTitle: String?,
    ) {
        queueStarts++
        super.playQueue(songs, startIndex, queueTitle)
    }

    override fun togglePlayPause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun skipToNext() = Unit

    override fun skipToPrevious() = Unit

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun startRadio(song: SongItem) = Unit

    override fun playNext(songs: List<SongItem>) {
        nextSongs = songs
    }

    override fun addToQueue(songs: List<SongItem>) {
        addedSongs = songs
    }
}
