package com.metrolist.desktop.state.content

import com.metrolist.shared.db.entities.PlaylistEntity
import kotlinx.coroutines.Job

internal fun hasCompleteStoredPlaylist(
    playlist: PlaylistEntity,
    songCount: Int,
): Boolean = playlist.isLocal || playlist.browseId == null || playlist.remoteSongCount?.let { songCount >= it } == true

internal suspend fun awaitCompletePlaylist(
    loadJob: Job?,
    hasMore: () -> Boolean,
): Boolean {
    if (hasMore()) loadJob?.join()
    return !hasMore()
}
