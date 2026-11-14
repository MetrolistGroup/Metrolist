package com.metrolist.shared.ui.menu

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.CancellationException

class MediaMenuPlaybackActions(
    private val item: YTItem,
    private val playback: PlaybackController,
    private val withSongs: ((List<SongItem>) -> Unit) -> Unit,
    private val resetArtistShuffle: Boolean = false,
) {
    fun play() = withSongs { songs -> play(songs.filterNot { it.isUnavailable }, shuffle = false) }

    fun shuffle() = withSongs { songs -> play(songs.filterNot { it.isUnavailable }, shuffle = true) }

    // Podcast episodes load newest first.
    fun playOldestFirst() = withSongs { songs -> play(songs.filterNot { it.isUnavailable }.asReversed(), shuffle = false) }

    fun playNext() = withSongs { songs -> songs.filterNot { it.isUnavailable }.takeIf { it.isNotEmpty() }?.let(playback::playNext) }

    fun addToQueue() = withSongs { songs -> songs.filterNot { it.isUnavailable }.takeIf { it.isNotEmpty() }?.let(playback::addToQueue) }

    private fun play(
        songs: List<SongItem>,
        shuffle: Boolean,
    ) {
        if (songs.isEmpty()) return
        val songsOnly = item is ArtistItem
        if (!shuffle && songsOnly && resetArtistShuffle && playback.state.value.isShuffled) playback.toggleShuffle()
        val queue = if (shuffle) songs.shuffled() else songs
        if (item is AlbumItem) {
            playback.playQueue(queue)
        } else {
            playback.play(queue.first(), queue, songsOnly = songsOnly)
        }
    }
}

internal suspend fun withMediaMenuSongs(
    resolveSongs: suspend () -> List<SongItem>,
    action: (List<SongItem>) -> Unit,
) {
    val songs =
        try {
            resolveSongs()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
    if (songs.isNotEmpty()) action(songs)
}
