package com.metrolist.desktop.state.content

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.hasCompleteStoredSongs
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.Job

internal fun hasCompleteStoredPlaylist(
    playlist: PlaylistEntity,
    songs: List<SongItem>,
): Boolean = playlist.hasCompleteStoredSongs(songs)

internal suspend fun awaitCompletePlaylist(
    loadJob: Job?,
    hasMore: () -> Boolean,
): Boolean {
    if (hasMore()) loadJob?.join()
    return !hasMore()
}
