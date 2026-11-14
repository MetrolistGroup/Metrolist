package com.metrolist.shared.model

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class PlaylistPresentationOptions(
    val sort: PlaylistSongSort = PlaylistSongSort.ORIGINAL,
    val direction: SortDirection = SortDirection.ASCENDING,
    val reorder: Boolean = false,
    val query: String = "",
)

data class PlaylistPresentation(
    val songs: List<SongItem> = emptyList(),
    val keys: List<String> = emptyList(),
    val indexByKey: Map<String, Int> = emptyMap(),
)

suspend fun preparePlaylistPresentation(
    songs: List<SongItem>,
    options: PlaylistPresentationOptions,
): PlaylistPresentation {
    val context = currentCoroutineContext()
    context.ensureActive()
    val ordered = if (options.reorder) songs else songs.sortedForPlaylist(options.sort, options.direction)
    context.ensureActive()
    val displayed = ordered.filterForPlaylistSearch(options.query)
    context.ensureActive()
    val keys = displayed.playlistLazyItemKeys()
    context.ensureActive()
    return PlaylistPresentation(displayed, keys, keys.withIndex().associate { (index, key) -> key to index })
}
