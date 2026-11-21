package com.metrolist.android.viewmodels

import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.collectionPlayCounts
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.CollectionMeta
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.filterForPlaylistSearch
import com.metrolist.shared.model.isReordering
import com.metrolist.shared.model.presentCollection
import com.metrolist.shared.model.sortedForLikedPlaylist
import com.metrolist.shared.model.sortedForPlaylist
import com.metrolist.shared.model.sortedForTopPlaylist
import com.metrolist.shared.settings.SavedPlaylistCollectionSorting
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.DownloadPlaylistFilterState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    val reorder: Boolean = false,
    val likedSongRecency: List<String> = emptyList(),
    val listeningTimes: Map<String, Long> = emptyMap(),
    val downloadFilter: DownloadPlaylistFilterState = DownloadPlaylistFilterState(),
    val cachedCustomOrder: List<String> = emptyList(),
)

internal fun AutoPlaylistPresentationInput.withSongSorting(
    locked: Boolean,
    listeningTimes: () -> Map<String, Long>,
): AutoPlaylistPresentationInput =
    copy(
        reorder =
            when (type) {
                "liked" -> playlistSort.isReordering(locked, true)
                "cached" -> collectionSort.isReordering(locked, true)
                else -> false
            },
        listeningTimes = if (playlistSort == PlaylistSongSort.LISTENING_TIME) listeningTimes() else emptyMap(),
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
                    val effectiveSort =
                        if (type == "cached") {
                            collectionSort
                        } else {
                            collectionSort.takeUnless { it == CollectionSort.MANUAL } ?: CollectionSort.NEWEST
                        }
                    filtered.presentCollection(
                        query,
                        effectiveSort,
                        collectionDirection,
                        customOrder = if (type == "cached") cachedCustomOrder else emptyList(),
                    ) { song ->
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
                    val ordered =
                        if (reorder) {
                            songs
                        } else {
                            songs.sortedForLikedPlaylist(
                                playlistSort,
                                playlistDirection,
                                likedSongRecency,
                                listeningTimes,
                            )
                        }
                    ordered.filterForPlaylistSearch(query)
                }

                "uploaded" -> {
                    songs.sortedForPlaylist(playlistSort, playlistDirection, listeningTimes = listeningTimes).filterForPlaylistSearch(query)
                }

                else -> {
                    songs.filterForPlaylistSearch(query)
                }
            }
        }
    }

internal suspend fun prepareCachedCollectionSongs(
    entries: List<CachedAudioEntity>,
    storedSongs: Map<String, Song>,
    playCounts: Map<String, Int>,
    sorting: SavedPlaylistCollectionSorting,
    customOrder: List<String>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): List<SongItem> {
    if (entries.isEmpty()) return emptyList()
    val rawSongs =
        withContext(dispatcher) {
            entries.map { entry -> entry.toSongItem(storedSongs[entry.songId]) }
        }
    return prepareAutoPlaylistSongs(
        AutoPlaylistPresentationInput(
            type = "cached",
            songs = rawSongs,
            metadataRevision = 0L,
            collectionEntries = entries,
            query = "",
            collectionSort = sorting.sort,
            collectionDirection = sorting.direction,
            playCounts = playCounts,
            playlistSort = PlaylistSongSort.ORIGINAL,
            playlistDirection = SortDirection.ASCENDING,
            topDirection = SortDirection.DESCENDING,
            cachedCustomOrder = customOrder,
        ),
        dispatcher = dispatcher,
    )
}

internal suspend fun loadOrderedCachedSongs(
    database: MusicDatabase,
    settings: Settings = Settings.shared,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    filterEntries: suspend (List<CachedAudioEntity>) -> List<CachedAudioEntity> = { it },
): List<SongItem> {
    val sorting =
        settings.playlistCollectionSorting(
            "special:cached",
            settings.cachedCollectionSort,
            settings.cachedCollectionSortDirection,
        )
    val customOrder = if (sorting.sort == CollectionSort.MANUAL) settings.cachedSongsOrder() else emptyList()
    val cachedEntries = filterEntries(database.dao().cachedAudioByKind(KIND_CACHE).first())
    if (cachedEntries.isEmpty()) return emptyList()
    val storedSongs =
        withContext(Dispatchers.IO) {
            database.dao().getSongsByIds(cachedEntries.map { it.songId }).associateBy { it.id }
        }
    val playCounts =
        if (sorting.sort == CollectionSort.MOST_PLAYED) {
            database.collectionPlayCounts(cachedEntries.map { it.songId }).first()
        } else {
            emptyMap()
        }
    return prepareCachedCollectionSongs(
        entries = cachedEntries,
        storedSongs = storedSongs,
        playCounts = playCounts,
        sorting = sorting,
        customOrder = customOrder,
        dispatcher = dispatcher,
    )
}
