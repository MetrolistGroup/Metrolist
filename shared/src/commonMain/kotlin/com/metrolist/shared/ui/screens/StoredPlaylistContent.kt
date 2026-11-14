@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.clear_collection_confirm
import com.metrolist.shared.generated.resources.confirm_clear_collection
import com.metrolist.shared.generated.resources.confirm_delete_stored_audio
import com.metrolist.shared.generated.resources.menu
import com.metrolist.shared.generated.resources.remove
import com.metrolist.shared.generated.resources.remove_from_device_desc
import com.metrolist.shared.generated.resources.remove_selected_count
import com.metrolist.shared.generated.resources.remove_songs_from_device_confirm
import com.metrolist.shared.generated.resources.search_no_results
import com.metrolist.shared.model.CollectionMeta
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.defaultDirection
import com.metrolist.shared.model.filterForPlaylistSearch
import com.metrolist.shared.model.indexOfPlaylistOccurrence
import com.metrolist.shared.model.presentCollection
import com.metrolist.shared.model.sortedForLikedPlaylist
import com.metrolist.shared.settings.SavedPlaylistCollectionSorting
import com.metrolist.shared.settings.SavedPlaylistSongSorting
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.state.moveLikedSong
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.CollectionFilterBar
import com.metrolist.shared.ui.components.DownloadPlaylistFilterBar
import com.metrolist.shared.ui.components.PlaylistOrderControls
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.SongSelectionControls
import com.metrolist.shared.ui.components.libraryCover
import com.metrolist.shared.ui.components.rememberDownloadPlaylistFilter
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.listState
import com.metrolist.shared.ui.selection.rememberSelectionState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun StoredPlaylistContent(
    id: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    songs: List<SongItem>,
    emptyContent: @Composable () -> Unit,
    listState: LazyListState,
    onBackClick: (() -> Unit)? = null,
    onCollectionMenu: (List<SongItem>) -> Unit,
    onTrackMenu: (SongItem) -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    extraInfo: (@Composable (Int, SongItem) -> Unit)? = null,
    headerAction: (@Composable () -> Unit)? = null,
    collectionMetadata: Map<String, CollectionMeta>? = null,
    downloadFilterDatabase: MusicDatabase? = null,
    playCounts: Map<String, Int> = emptyMap(),
    onCollectionSortChange: (CollectionSort) -> Unit = {},
    onRemoveSongs: ((Set<String>) -> Unit)? = null,
    onClearCollection: (() -> Unit)? = null,
    onReorderSongs: ((List<SongItem>) -> Unit)? = null,
    hasMoreSongs: Boolean = false,
    loadCompleteSongs: (suspend () -> List<SongItem>?)? = null,
    onDownload: (List<SongItem>) -> Unit,
    onAddToPlaylist: (List<SongItem>) -> Unit,
    trackContainer: @Composable (SongItem, Boolean, @Composable () -> Unit) -> Unit = { _, _, content -> content() },
) {
    val isDownloadedCollection = id == "local:downloaded"
    val playlistSortingKey = "special:${id.removePrefix("local:")}"
    val defaultCollectionSort = if (isDownloadedCollection) Settings.shared.downloadedCollectionSort else CollectionSort.NEWEST
    val defaultCollectionDirection =
        if (isDownloadedCollection) Settings.shared.downloadedCollectionSortDirection else SortDirection.DESCENDING
    val defaultPlaylistSorting =
        Settings.shared.playlistSongSorting(
            playlistSortingKey,
            Settings.shared.likedSongSort,
            Settings.shared.likedSongSortDirection,
        )
    var collectionDirection by remember(id) {
        mutableStateOf(
            Settings.shared
                .playlistCollectionSorting(
                    playlistSortingKey,
                    defaultCollectionSort,
                    defaultCollectionDirection,
                ).direction,
        )
    }
    val playback = LocalPlaybackController.current
    val playbackState by remember(playback) { playback.listState }.collectAsState(playback.state.value.listState())
    var query by remember(id) { mutableStateOf("") }
    var collectionSort by remember(id) {
        mutableStateOf(
            Settings.shared
                .playlistCollectionSorting(
                    playlistSortingKey,
                    defaultCollectionSort,
                    defaultCollectionDirection,
                ).sort,
        )
    }
    LaunchedEffect(id, collectionSort) { onCollectionSortChange(collectionSort) }
    var playlistSort by remember(id) { mutableStateOf(defaultPlaylistSorting.sort) }
    var playlistSortDirection by remember(id) { mutableStateOf(defaultPlaylistSorting.direction) }
    var reorderMode by remember(id) { mutableStateOf(false) }
    val selection = rememberSelectionState(id)
    val downloadFilter = downloadFilterDatabase?.let { rememberDownloadPlaylistFilter(it) }
    val downloadFilterState = downloadFilter?.state?.collectAsState()?.value
    LaunchedEffect(downloadFilterState) { selection.exit() }
    var pendingRemoval by remember(id) { mutableStateOf<Set<String>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val metadataRevision by SongMetadataState.revision.collectAsState()
    val likedSongRecency = SongLikeState.likedSongRecency
    val orderedSongs =
        remember(
            songs,
            likedSongRecency,
            downloadFilterState,
            metadataRevision,
            collectionMetadata,
            playCounts,
            collectionDirection,
            collectionSort,
            playlistSort,
            playlistSortDirection,
            reorderMode,
            onReorderSongs != null,
        ) {
            when {
                collectionMetadata != null -> {
                    (downloadFilterState?.apply(songs) ?: songs).presentCollection("", collectionSort, collectionDirection) { song ->
                        collectionMetadata[song.id]?.copy(playCount = playCounts[song.id] ?: 0)
                    }
                }

                onReorderSongs != null && !reorderMode -> {
                    songs
                        .sortedForLikedPlaylist(
                            playlistSort,
                            playlistSortDirection,
                            likedSongRecency,
                        )
                }

                else -> {
                    songs
                }
            }
        }
    val displayedSongs = remember(orderedSongs, query) { orderedSongs.filterForPlaylistSearch(query) }
    val displayedIds = remember(displayedSongs) { displayedSongs.mapTo(mutableSetOf()) { it.id } }
    val selectedSongs =
        remember(displayedSongs, selection.active, selection.selectedIds) {
            if (selection.active) displayedSongs.filter { selection.isSelected(it.id) } else emptyList()
        }
    val selectionEnabled = collectionMetadata != null && onRemoveSongs != null

    LaunchedEffect(songs) {
        selection.retain(songs.map { it.id })
    }

    val customCover = Settings.shared.collectionCovers[id.removePrefix("local:")]
    val scope = rememberCoroutineScope()

    fun playCollection(
        song: SongItem? = null,
        shuffle: Boolean = false,
    ) {
        scope.launch {
            val complete =
                if (hasMoreSongs && loadCompleteSongs != null) {
                    loadCompleteSongs()
                        ?.sortedForLikedPlaylist(
                            playlistSort,
                            playlistSortDirection,
                            SongLikeState.likedSongRecency,
                        ) ?: return@launch
                } else {
                    orderedSongs
                }
            val playable = (if (song == null) complete.filterForPlaylistSearch(query) else complete).filterNot { it.isUnavailable }
            val queue = if (shuffle) playable.shuffled() else playable
            val target = song?.let { queue.indexOfPlaylistOccurrence(it) } ?: 0
            if (target in queue.indices) playback.playQueue(queue, target)
        }
    }

    PlaylistContent(
        isTrackSelected = { selection.isSelected(it.id) },
        trackContainer = { song, _, content ->
            trackContainer(song, reorderMode || selection.active, content)
        },
        playlist =
            PlaylistItem(
                id = id,
                title = title,
                thumbnail = customCover ?: songs.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail,
                customThumbnail = customCover,
                author = subtitle,
                isLocal = true,
            ),
        songs = displayedSongs,
        artworkSongs = songs,
        text = playlistContentText(useTrackCount = id.removePrefix("local:") in setOf("podcasts", "SE")),
        hasMoreSongs = hasMoreSongs,
        isLoading = hasMoreSongs,
        onMoreOptions = { onCollectionMenu(displayedSongs) },
        onPlayAll = { playCollection() },
        onShuffle = { playCollection(shuffle = true) },
        onTrackClick = { song, _ ->
            if (selection.active) {
                selection.toggle(song.id)
            } else {
                playCollection(song)
            }
        },
        onTrackMenuClick = { song, _ -> onTrackMenu(song) },
        searchQuery = query,
        onSearchQueryChange = { query = it },
        onTrackLongClick = { song ->
            if (!reorderMode) {
                selection.begin(song.id)
            }
        },
        selectionActive = selection.active,
        selectionControls = {
            SongSelectionControls(
                selectedCount = selectedSongs.size,
                selectedSongs = selectedSongs,
                onPlay = { selectedSongs.firstOrNull { !it.isUnavailable }?.let { playback.play(it, selectedSongs) } },
                onShuffle = { selectedSongs.shuffled().let { s -> s.firstOrNull { !it.isUnavailable }?.let { playback.play(it, s) } } },
                allSelected = selection.allSelected(displayedIds),
                onDone = selection::exit,
                onSelectAll = { selection.toggleAll(displayedIds) },
                onPlayNext = { playback.playNext(selectedSongs) },
                onAddToQueue = { playback.addToQueue(selectedSongs) },
                onDownload = { onDownload(selectedSongs) },
                onAddToPlaylist = { onAddToPlaylist(selectedSongs) },
                onRemove =
                    when {
                        id == "local:liked" -> {
                            { SongLikeState.removeLikes(selectedSongs.map { it.id }) }
                        }

                        selectionEnabled -> {
                            { pendingRemoval = selectedSongs.mapTo(mutableSetOf()) { it.id } }
                        }

                        else -> {
                            null
                        }
                    },
                removeLabel =
                    if (id != "local:liked" && selectionEnabled) {
                        stringResource(Res.string.remove_selected_count, selectedSongs.size)
                    } else {
                        null
                    },
            )
        },
        onArtistClick = onArtistClick,
        onBackClick = onBackClick,
        fallbackIcon = icon,
        cover = libraryCover(id.removePrefix("local:")).takeIf { customCover == null },
        listState = listState,
        currentSongId = playbackState.currentSongId,
        isPlaying = playbackState.isPlaying,
        header =
            headerAction?.let { action ->
                {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        action()
                    }
                }
            },
        aboveOrderControls =
            if (selectionEnabled && downloadFilter != null) {
                { DownloadPlaylistFilterBar(downloadFilter, downloadFilterDatabase) }
            } else {
                null
            },
        orderControls =
            when {
                selectionEnabled -> {
                    {
                        Column {
                            CollectionFilterBar(
                                showSearch = false,
                                text = collectionFilterBarText(includeClear = true),
                                query = query,
                                onQueryChange = { query = it },
                                sort = collectionSort,
                                onSortChange = {
                                    collectionSort = it
                                    collectionDirection = it.defaultDirection
                                    Settings.shared.setPlaylistCollectionSorting(
                                        playlistSortingKey,
                                        SavedPlaylistCollectionSorting(it, it.defaultDirection),
                                    )
                                },
                                sortDirection = collectionDirection,
                                onSortDirectionChange = {
                                    collectionDirection = it
                                    Settings.shared.setPlaylistCollectionSorting(
                                        playlistSortingKey,
                                        SavedPlaylistCollectionSorting(collectionSort, it),
                                    )
                                },
                                onClear = { confirmClear = true },
                            )
                        }
                    }
                }

                onReorderSongs != null -> {
                    {
                        PlaylistOrderControls(
                            text = playlistOrderControlsText(),
                            sort = playlistSort,
                            sortDirection = playlistSortDirection,
                            reorderMode = reorderMode,
                            canReorder = true,
                            onSortChange = {
                                playlistSort = it
                                Settings.shared.setPlaylistSongSorting(
                                    playlistSortingKey,
                                    SavedPlaylistSongSorting(it, playlistSortDirection),
                                )
                            },
                            onSortDirectionChange = {
                                playlistSortDirection = it
                                Settings.shared.setPlaylistSongSorting(
                                    playlistSortingKey,
                                    SavedPlaylistSongSorting(playlistSort, it),
                                )
                            },
                            onReorderModeChange = {
                                reorderMode = it
                                selection.exit()
                            },
                        )
                    }
                }

                else -> {
                    null
                }
            },
        reorderMode = reorderMode,
        onReorder = { fromIndex, toIndex ->
            val from = displayedSongs.getOrNull(fromIndex)
            val to = displayedSongs.getOrNull(toIndex)
            if (from != null && to != null) {
                onReorderSongs?.invoke(moveLikedSong(songs, from.id, to.id))
            }
        },
        emptyContent = {
            if (downloadFilterState?.playlist != null) {
                if (!downloadFilterState.loading && !downloadFilterState.failed) {
                    Text(stringResource(Res.string.search_no_results), modifier = Modifier.padding(16.dp))
                }
            } else {
                emptyContent()
            }
        },
        trackTrailingContent =
            if (selectionEnabled || selection.active || extraInfo != null) {
                { song, index ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        extraInfo?.invoke(index, song)
                        if (selection.active) {
                            Checkbox(
                                checked = selection.isSelected(song.id),
                                onCheckedChange = { selection.toggle(song.id) },
                            )
                        } else {
                            IconButton(
                                modifier = Modifier.navigationPopupSource(),
                                onClick = { onTrackMenu(song) },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.menu))
                            }
                            if (selectionEnabled) {
                                IconButton(onClick = { onRemoveSongs(setOf(song.id)) }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(
                                        MetrolistIcons.Outlined.Delete,
                                        contentDescription = stringResource(Res.string.remove_from_device_desc),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                null
            },
    )

    val removalIds = pendingRemoval
    PopupContent(removalIds) { removalIds ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = {
                Text(pluralStringResource(Res.plurals.remove_songs_from_device_confirm, removalIds.size, removalIds.size))
            },
            text = { Text(stringResource(Res.string.confirm_delete_stored_audio)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveSongs?.invoke(removalIds)
                        selection.exit()
                        pendingRemoval = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.remove)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRemoval = null },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
    PopupVisibility(visible = confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(Res.string.clear_collection_confirm, title)) },
            text = { Text(stringResource(Res.string.confirm_clear_collection)) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearCollection?.invoke()
                        selection.exit()
                        confirmClear = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
