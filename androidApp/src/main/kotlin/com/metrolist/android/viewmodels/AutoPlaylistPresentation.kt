package com.metrolist.android.viewmodels

import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.model.CollectionMeta
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.filterForPlaylistSearch
import com.metrolist.shared.model.presentCollection
import com.metrolist.shared.model.sortedForLikedPlaylist
import com.metrolist.shared.model.sortedForPlaylist
import com.metrolist.shared.model.sortedForTopPlaylist
import com.metrolist.shared.viewmodels.DownloadPlaylistFilterState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AutoPlaylistPresentationInput(
    val type: String,
    val songs: List<SongItem>,
    val metadataRevision: Long,
    val collectionEntries: List<CachedAudioEntity>,
    val query: String,
    val collectionSort: CollectionSort,
    val collectionDirection: SortDirection,
    val playCounts: Map<String, Int>,
    val playlistSort: PlaylistSongSort,
    val playlistDirection: SortDirection,
    val topDirection: SortDirection,
    val reorder: Boolean,
    val likedSongRecency: List<String> = emptyList(),
    val downloadFilter: DownloadPlaylistFilterState = DownloadPlaylistFilterState(),
)

internal suspend fun prepareAutoPlaylistSongs(
    input: AutoPlaylistPresentationInput,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): List<SongItem> =
    withContext(dispatcher) {
        with(input) {
            when (type) {
                "downloaded", "cached" -> {
                    val entries = collectionEntries.associateBy(CachedAudioEntity::songId)
                    val filtered = if (type == "downloaded") downloadFilter.apply(songs) else songs
                    filtered.presentCollection(query, collectionSort, collectionDirection) { song ->
                        entries[song.id]?.let { entry ->
                            CollectionMeta(
                                timestamp = entry.cachedAt,
                                fileSize = entry.fileSize,
                                lastAccessed = entry.lastAccessedAt,
                                playCount = playCounts[song.id] ?: 0,
                            )
                        }
                    }
                }

                "top100" -> {
                    songs.sortedForTopPlaylist(topDirection).filterForPlaylistSearch(query)
                }

                "liked" -> {
                    val ordered = if (reorder) songs else songs.sortedForLikedPlaylist(playlistSort, playlistDirection, likedSongRecency)
                    ordered.filterForPlaylistSearch(query)
                }

                "uploaded" -> {
                    songs.sortedForPlaylist(playlistSort, playlistDirection).filterForPlaylistSearch(query)
                }

                else -> {
                    songs.filterForPlaylistSearch(query)
                }
            }
        }
    }
