/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.DownloadState
import com.metrolist.desktop.ui.components.AddSongDialog
import com.metrolist.desktop.ui.components.PlaylistContextMenu
import com.metrolist.desktop.ui.components.SongContextMenu
import com.metrolist.desktop.ui.components.SongSwipeActions
import com.metrolist.desktop.utils.playlistCoverFilenameFilter
import com.metrolist.desktop.utils.readPlaylistCover
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.shared.api.PlaylistNotFoundException
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.api.playlistSuggestions
import com.metrolist.shared.db.isHistoryPlaylistId
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_cannot_be_undone
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.choose_from_library
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.delete_playlist_confirm
import com.metrolist.shared.generated.resources.description
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.edit_playlist
import com.metrolist.shared.generated.resources.edit_playlist_cover
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.http_status_code
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.generated.resources.playlist_cover_update_failed
import com.metrolist.shared.generated.resources.playlist_name
import com.metrolist.shared.generated.resources.playlist_removed_from_youtube
import com.metrolist.shared.generated.resources.playlist_rename_action
import com.metrolist.shared.generated.resources.playlist_unavailable
import com.metrolist.shared.generated.resources.playlist_update_failed
import com.metrolist.shared.generated.resources.playlist_update_partially_failed
import com.metrolist.shared.generated.resources.remove_custom_image
import com.metrolist.shared.generated.resources.remove_from_library
import com.metrolist.shared.generated.resources.remove_from_playlist
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.song_already_in_playlist
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canEdit
import com.metrolist.shared.model.filterForPlaylistSearch
import com.metrolist.shared.model.playlistOccurrenceKey
import com.metrolist.shared.model.sortedForPlaylist
import com.metrolist.shared.playlist.PlaylistPartiallyUpdatedException
import com.metrolist.shared.settings.HiddenLibraryItems
import com.metrolist.shared.settings.SavedPlaylistSongSorting
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.CollectionHeaderNavigation
import com.metrolist.shared.ui.components.LoadingScreen
import com.metrolist.shared.ui.components.PlaylistOrderControls
import com.metrolist.shared.ui.components.PlaylistOrderControlsText
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.SongSelectionControls
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.contextMenuAnchor
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.screens.PlaylistContent
import com.metrolist.shared.ui.screens.playlistContentText
import com.metrolist.shared.ui.screens.playlistOrderControlsText
import com.metrolist.shared.ui.selection.rememberSelectionState
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.IdentityHashMap

@Composable
fun PlaylistScreen(colorScheme: ColorScheme) {
    if (PlaylistAlbumState.isPlaylistLoading && PlaylistAlbumState.playlistData.isEmpty()) {
        val loadingNavigator = LocalNavigator.current
        CollectionHeaderNavigation({ loadingNavigator.goBack() }) { LoadingScreen() }
        return
    }

    val playlistInfo =
        (PlaylistAlbumState.playlistData["header"]?.firstOrNull() as? PlaylistItem)?.let {
            if (it.thumbnail.isNullOrBlank()) it.copy(thumbnail = PlaylistAlbumState.pendingPlaylistThumbnail) else it
        }
    val currentSongs = PlaylistAlbumState.playlistData.playlistSongs()
    val songs = remember(currentSongs) { currentSongs }
    val suggestions = PlaylistAlbumState.playlistData.playlistSuggestions()
    val sorting =
        Settings.shared.playlistSongSorting(
            "playlist:${playlistInfo?.id.orEmpty()}",
            Settings.shared.playlistSongSort,
            Settings.shared.playlistSongSortDirection,
        )
    val sortMode = sorting.sort
    val sortDirection = sorting.direction
    var reorderMode by remember(playlistInfo?.id) { mutableStateOf(false) }
    var searchQuery by remember(playlistInfo?.id) { mutableStateOf("") }
    val selection = rememberSelectionState(playlistInfo?.id)
    val metadataRevision by SongMetadataState.revision.collectAsState()
    val displayedSongs =
        remember(songs, sortMode, sortDirection, reorderMode, searchQuery, metadataRevision, PlaylistAlbumState.playlistHasMore) {
            val ordered = if (reorderMode || PlaylistAlbumState.playlistHasMore) songs else songs.sortedForPlaylist(sortMode, sortDirection)
            ordered.filterForPlaylistSearch(searchQuery)
        }

    val occurrenceKeys =
        remember(songs) {
            IdentityHashMap<SongItem, String>().apply {
                songs.forEachIndexed { index, song ->
                    if (!containsKey(song)) put(song, song.playlistOccurrenceKey(index))
                }
            }
        }

    fun occurrenceKey(song: SongItem): String = occurrenceKeys.getValue(song)
    val selectedSongs = if (selection.count == 0) emptyList() else displayedSongs.filter { selection.isSelected(occurrenceKey(it)) }

    val listState = HomeState.rememberListState("playlist_${playlistInfo?.id ?: "unknown"}")

    val playlistMenu = rememberCursorContextMenuState()
    var menuSong by remember { mutableStateOf<SongItem?>(null) }

    val playlistActionsMenu = rememberCursorContextMenuState()
    val coverScope = rememberCoroutineScope()
    var coverReadJob by remember { mutableStateOf<Job?>(null) }
    var coverReadGeneration by remember { mutableStateOf(0) }
    var coverLoading by remember { mutableStateOf(false) }
    var showRenameDialog by remember(playlistInfo?.id) { mutableStateOf(false) }
    DisposableEffect(playlistInfo?.id) {
        onDispose {
            coverReadGeneration++
            coverReadJob?.cancel()
            coverLoading = false
        }
    }
    var editSaving by remember(playlistInfo?.id) { mutableStateOf(false) }
    var editFailed by remember(playlistInfo?.id) { mutableStateOf(false) }
    var removingSongs by remember(playlistInfo?.id) { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editDescription by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    var showAddSongDialog by remember { mutableStateOf(false) }

    val isEditable = playlistInfo.isUserCreatedPlaylist() && !PlaylistAlbumState.playlistHasMore
    val isLocal = playlistInfo?.isLocal == true || playlistInfo?.id?.startsWith("LP") == true
    val canRemoveFromLibrary = !isLocal && (PlaylistAlbumState.isPlaylistBookmarked || isEditable)
    val canEditSingle = playlistInfo?.canEdit == true
    var editError by remember { mutableStateOf<Throwable?>(null) }
    var coverError by remember { mutableStateOf(false) }
    val editCoverLabel = stringResource(Res.string.edit_playlist_cover)

    fun coverFiles(id: String): List<File> {
        val dir = File(XdgUtils.getXdgDataHome(), "metrolist/playlist_covers")
        return dir
            .listFiles { _, name ->
                name == "$id.jpg" || (name.startsWith("${id}_") && name.endsWith(".jpg"))
            }?.toList()
            .orEmpty()
    }

    fun persistDesktopCoverBytes(
        bytes: ByteArray,
        id: String,
    ): String? =
        runCatching {
            val coversDir = File(XdgUtils.getXdgDataHome(), "metrolist/playlist_covers").apply { mkdirs() }
            val dest = File(coversDir, "${id}_${System.currentTimeMillis()}.jpg")
            dest.outputStream().use { output -> output.write(bytes) }
            dest.absolutePath
        }.getOrNull()

    fun handleRemoveDesktopCover() {
        val id = playlistInfo?.id ?: return
        PlaylistAlbumState.removeSelectedPlaylistCover { result ->
            result.onSuccess { coverScope.launch(Dispatchers.IO) { coverFiles(id).forEach { runCatching { it.delete() } } } }
            result.onFailure { coverError = true }
        }
    }

    var stagedDesktopCoverPath by remember { mutableStateOf<String?>(null) }
    var stagedDesktopCoverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var stagedDesktopCoverRemove by remember { mutableStateOf(false) }

    fun clearStagedDesktopCover() {
        coverReadGeneration++
        coverReadJob?.cancel()
        coverLoading = false
        stagedDesktopCoverPath = null
        stagedDesktopCoverBytes = null
        stagedDesktopCoverRemove = false
    }

    fun applyStagedDesktopCover() {
        val info = playlistInfo ?: return
        if (stagedDesktopCoverRemove) {
            handleRemoveDesktopCover()
            return
        }
        val bytes = stagedDesktopCoverBytes ?: return
        if (info.isLocal || !info.isEditable) {
            coverScope.launch {
                val path = withContext(Dispatchers.IO) { persistDesktopCoverBytes(bytes, info.id) }
                if (PlaylistAlbumState.selectedPlaylistId != info.id) {
                    if (path != null) withContext(Dispatchers.IO) { File(path).delete() }
                    return@launch
                }
                if (path == null) {
                    coverError = true
                    return@launch
                }
                PlaylistAlbumState.setSelectedPlaylistCover(path) { result ->
                    result.onFailure { coverError = true }
                    result.onSuccess {
                        coverScope.launch(Dispatchers.IO) {
                            coverFiles(info.id).filter { it.absolutePath != path }.forEach { it.delete() }
                        }
                    }
                }
            }
        } else {
            PlaylistAlbumState.uploadSelectedPlaylistCover(bytes) { result -> result.onFailure { coverError = true } }
        }
    }

    fun openDesktopEdit() {
        editFailed = false
        editName = playlistInfo?.title.orEmpty()
        editDescription = playlistInfo?.description.orEmpty()
        clearStagedDesktopCover()
        showRenameDialog = true
    }

    fun saveDesktopEdit() {
        if (editSaving) return
        if (isEditable) {
            if (editName.isBlank()) return
            editSaving = true
            editFailed = false
            PlaylistAlbumState.updateSelectedPlaylist(editName, editDescription) { result ->
                editSaving = false
                val error = result.exceptionOrNull()
                if (error == null || error is PlaylistPartiallyUpdatedException) {
                    applyStagedDesktopCover()
                    clearStagedDesktopCover()
                    showRenameDialog = false
                    editError = error
                } else {
                    editFailed = true
                }
            }
        } else {
            applyStagedDesktopCover()
            clearStagedDesktopCover()
            showRenameDialog = false
        }
    }

    fun cancelDesktopEdit() {
        if (editSaving) return
        clearStagedDesktopCover()
        showRenameDialog = false
    }

    fun pickDesktopCover() {
        val dialog = FileDialog(null as Frame?, editCoverLabel, FileDialog.LOAD).apply { filenameFilter = playlistCoverFilenameFilter }
        dialog.isVisible = true
        val chosen =
            dialog.directory?.let { dir -> dialog.file?.let { file -> File(dir, file) } }?.takeIf { it.exists() }
        dialog.dispose()
        if (chosen == null) return
        coverReadJob?.cancel()
        val generation = ++coverReadGeneration
        val playlistId = playlistInfo?.id
        coverLoading = true
        coverReadJob =
            coverScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { runCatching { readPlaylistCover(chosen) } }
                    if (generation != coverReadGeneration || !showRenameDialog ||
                        PlaylistAlbumState.selectedPlaylistId != playlistId
                    ) {
                        return@launch
                    }
                    result
                        .onSuccess { bytes ->
                            stagedDesktopCoverPath = chosen.absolutePath
                            stagedDesktopCoverBytes = bytes
                            stagedDesktopCoverRemove = false
                        }.onFailure { coverError = true }
                } finally {
                    if (generation == coverReadGeneration) coverLoading = false
                }
            }
    }
    val navigator = LocalNavigator.current

    Box(
        Modifier
            .fillMaxSize()
            .cursorContextMenu(playlistMenu, openOnSecondaryClick = false)
            .contextMenuAnchor(playlistActionsMenu),
    ) {
        PlaylistContent(
            onBackClick = { navigator.goBack() },
            trackContainer = { song, _, content ->
                SongSwipeActions(song, enabled = !reorderMode && !selection.active, content = content)
            },
            isLoading = PlaylistAlbumState.isPlaylistLoading && PlaylistAlbumState.playlistHasMore,
            downloadedSongIds = LibraryState.downloadStates.filterValues { it == DownloadState.DOWNLOADED }.keys,
            playlist = playlistInfo,
            songs = displayedSongs,
            artworkSongs = songs,
            durationSongs = PlaylistAlbumState.playlistDurationSongs,
            hasMoreSongs = PlaylistAlbumState.playlistHasMore,
            hiddenSongCount = (PlaylistAlbumState.playlistTrackCount - songs.size).coerceAtLeast(0),
            reorderMode = reorderMode && !PlaylistAlbumState.isPlaylistReordering,
            onReorder = { from, to -> PlaylistAlbumState.moveDisplayedSong(from, to, selectedSongs) },
            onReorderFinished = PlaylistAlbumState::commitPendingReorder,
            header = {
                PlaylistAlbumState.playlistLoadError?.let { error ->
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            stringResource(
                                when (error) {
                                    is PlaylistNotFoundException -> Res.string.playlist_removed_from_youtube
                                    else -> Res.string.playlist_unavailable
                                },
                            ),
                            color = colorScheme.error,
                        )
                        (error as? InnerTubeHttpException)?.status?.value?.let { status ->
                            Text(stringResource(Res.string.http_status_code, status))
                        }
                        TextButton(
                            enabled = !PlaylistAlbumState.isPlaylistLoading,
                            onClick = {
                                PlaylistAlbumState.selectedPlaylistId?.let { id ->
                                    PlaylistAlbumState.fetchPlaylistData(
                                        id,
                                        PlaylistAlbumState.pendingPlaylistThumbnail,
                                        addToHistory = false,
                                    )
                                }
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.retry)) }
                    }
                }
            },
            onEdit = null,
            text = playlistContentText(),
            onPlayAll = {
                PlaylistAlbumState.withCompletePlaylist { all ->
                    val queue = all.sortedForPlaylist(sortMode, sortDirection).filterForPlaylistSearch(searchQuery)
                    queue.firstOrNull { !it.isUnavailable }?.let { PlayerState.playTrack(it, queue) }
                }
            },
            onShuffle = {
                PlaylistAlbumState.withCompletePlaylist { all ->
                    val shuffled = all.filterForPlaylistSearch(searchQuery).shuffled()
                    shuffled.firstOrNull { !it.isUnavailable }?.let { PlayerState.playTrack(it, shuffled) }
                }
            },
            isFavorite = PlaylistAlbumState.isPlaylistBookmarked,
            onFavorite =
                PlaylistAlbumState::toggleSelectedPlaylistBookmark.takeUnless {
                    isLocal || isEditable || playlistInfo?.id == "LM"
                },
            onAddSong = if (isEditable) ({ showAddSongDialog = true }) else null,
            onTrackClick = { song, index ->
                if (index >= displayedSongs.size) {
                    PlayerState.playTrack(song, suggestions)
                } else if (selection.active) {
                    selection.toggle(occurrenceKey(song))
                } else {
                    PlaylistAlbumState.withCompletePlaylist { all ->
                        PlayerState.playTrack(song, if (reorderMode) all else all.sortedForPlaylist(sortMode, sortDirection))
                    }
                }
            },
            onTrackLongClick = { song ->
                if (!reorderMode) selection.begin(occurrenceKey(song))
            },
            trackTrailingContent = { song, _ ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selection.active) {
                        val key = occurrenceKey(song)
                        Checkbox(checked = selection.isSelected(key), onCheckedChange = { selection.toggle(key) })
                    } else {
                        IconButton(
                            modifier = Modifier.navigationPopupSource(),
                            onClick = {
                                menuSong = song
                                playlistMenu.open()
                            },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options))
                        }
                    }
                }
            },
            onTrackMenuClick = { song, _ ->
                menuSong = song
                playlistMenu.open()
            },
            listState = listState,
            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
            currentSongId = PlayerState.currentTrack?.id,
            isPlaying = PlayerState.isPlaying,
            suggestions = suggestions.takeIf { searchQuery.isBlank() && !(songs.isEmpty() && isEditable) }.orEmpty(),
            onArtistClick = ArtistState::fetchArtistData,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onMoreOptions = { playlistActionsMenu.open() },
            orderControls = {
                PlaylistOrderControls(
                    text = playlistOrderControlsText(),
                    sort = sortMode,
                    sortDirection = sortDirection,
                    reorderMode = reorderMode,
                    canReorder = isEditable && !PlaylistAlbumState.playlistHasMore && !PlaylistAlbumState.isPlaylistReordering,
                    onSortChange = {
                        val selected = it
                        PlaylistAlbumState.withCompletePlaylist {
                            Settings.shared.setPlaylistSongSorting(
                                "playlist:${playlistInfo?.id.orEmpty()}",
                                SavedPlaylistSongSorting(selected, Settings.shared.playlistSongSortDirection),
                            )
                        }
                    },
                    onSortDirectionChange = {
                        Settings.shared.setPlaylistSongSorting(
                            "playlist:${playlistInfo?.id.orEmpty()}",
                            SavedPlaylistSongSorting(sortMode, it),
                        )
                    },
                    onReorderModeChange = {
                        searchQuery = ""
                        reorderMode = it
                    },
                )
            },
            selectionActive = selection.active,
            selectionControls = {
                val displayedKeys = displayedSongs.map(::occurrenceKey)
                SongSelectionControls(
                    selectedCount = selectedSongs.size,
                    selectedSongs = selectedSongs,
                    onPlay = { selectedSongs.firstOrNull { !it.isUnavailable }?.let { PlayerState.playTrack(it, selectedSongs) } },
                    onShuffle = {
                        selectedSongs.shuffled().let { s ->
                            s.firstOrNull { !it.isUnavailable }?.let { PlayerState.playTrack(it, s) }
                        }
                    },
                    allSelected = selection.allSelected(displayedKeys),
                    onDone = selection::exit,
                    onSelectAll = { selection.toggleAll(displayedKeys) },
                    onPlayNext = { PlayerState.playNext(selectedSongs) },
                    onAddToQueue = { PlayerState.addToQueue(selectedSongs) },
                    onDownload = { DownloadManager.downloadAll(selectedSongs) },
                    onAddToPlaylist = { PlayerState.showAddToPlaylistSongs = selectedSongs },
                    onRemove =
                        if (isEditable) {
                            {
                                if (!removingSongs) {
                                    removingSongs = true
                                    PlaylistAlbumState.removeSongs(selectedSongs) { result ->
                                        removingSongs = false
                                        if (result.isSuccess) selection.exit()
                                    }
                                }
                            }
                        } else {
                            null
                        },
                )
            },
        )

        menuSong?.let { song ->
            SongContextMenu(
                song = song,
                expanded = playlistMenu.expanded,
                offset = playlistMenu.offset,
                onRemoveFromPlaylist =
                    if (isEditable) {
                        { PlaylistAlbumState.removeSong(song) }
                    } else {
                        null
                    },
                removeLabel = stringResource(Res.string.remove_from_playlist),
                onMoveUp =
                    if (isEditable && !reorderMode && !PlaylistAlbumState.isPlaylistReordering) {
                        {
                            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            PlaylistAlbumState.moveSong(song, -1)
                        }
                    } else {
                        null
                    },
                onMoveDown =
                    if (isEditable && !reorderMode && !PlaylistAlbumState.isPlaylistReordering) {
                        {
                            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                            PlaylistAlbumState.moveSong(song, 1)
                        }
                    } else {
                        null
                    },
                onDismiss = playlistMenu::dismiss,
            )
        }

        playlistInfo?.let { playlist ->
            PlaylistContextMenu(
                playlist = playlist,
                isFavorite = PlaylistAlbumState.isPlaylistBookmarked,
                onFavorite = PlaylistAlbumState::toggleSelectedPlaylistBookmark.takeUnless { isLocal || isEditable || playlist.id == "LM" },
                songs = songs.takeUnless { PlaylistAlbumState.playlistHasMore }.orEmpty(),
                expanded = playlistActionsMenu.expanded,
                offset = playlistActionsMenu.offset,
                onRename =
                    if (isEditable) {
                        {
                            editName = playlist.title
                            editDescription = playlist.description.orEmpty()
                            showRenameDialog = true
                        }
                    } else {
                        null
                    },
                onDelete =
                    if ((isEditable && isLocal) || canRemoveFromLibrary) {
                        {
                            deleteFailed = false
                            showDeleteDialog = true
                        }
                    } else {
                        null
                    },
                onHideFromLibrary = { HiddenLibraryItems.hide(playlist) }.takeUnless { isHistoryPlaylistId(playlist.id) },
                onDismiss = playlistActionsMenu::dismiss,
            )
        }
    }

    if (showRenameDialog) {
        val dialogPreview =
            when {
                stagedDesktopCoverRemove -> null
                stagedDesktopCoverPath != null -> stagedDesktopCoverPath
                else -> playlistInfo?.customThumbnail ?: playlistInfo?.thumbnail
            }
        val hasDesktopCustom = !playlistInfo?.customThumbnail.isNullOrBlank() && !stagedDesktopCoverRemove
        AlertDialog(
            onDismissRequest = { cancelDesktopEdit() },
            title = { Text(stringResource(Res.string.edit_playlist)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (dialogPreview != null) {
                            AsyncImage(
                                url = dialogPreview,
                                modifier =
                                    Modifier.size(72.dp).clip(
                                        RoundedCornerShape(12.dp),
                                    ),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Image(
                                painterResource(Res.drawable.logo),
                                contentDescription = null,
                                modifier =
                                    Modifier.size(72.dp).clip(
                                        RoundedCornerShape(12.dp),
                                    ),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { pickDesktopCover() }, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(Res.string.choose_from_library))
                            }
                            if (stagedDesktopCoverPath != null || hasDesktopCustom) {
                                TextButton(
                                    onClick = {
                                        clearStagedDesktopCover()
                                        stagedDesktopCoverRemove = true
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                ) { Text(stringResource(Res.string.remove_custom_image)) }
                            }
                        }
                    }
                    if (editFailed) {
                        Text(stringResource(Res.string.playlist_update_failed), color = colorScheme.error)
                    }
                    if (isEditable) {
                        OutlinedTextField(
                            enabled = !editSaving,
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text(stringResource(Res.string.playlist_name)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            enabled = !editSaving,
                            value = editDescription,
                            onValueChange = { editDescription = it },
                            label = { Text(stringResource(Res.string.description)) },
                            singleLine = false,
                        )
                    }
                }
            },
            confirmButton = {
                if (isEditable) {
                    TextButton(
                        enabled = editName.isNotBlank() && !coverLoading && !editSaving,
                        onClick = { saveDesktopEdit() },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.playlist_rename_action)) }
                } else {
                    TextButton(enabled = !coverLoading, onClick = {
                        saveDesktopEdit()
                    }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.done)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelDesktopEdit() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }

    if (showDeleteDialog) {
        val cancelFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocusRequester.requestFocus() }
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteFailed = false
            },
            title = {
                Text(
                    stringResource(
                        if (isLocal || isEditable) Res.string.delete_playlist_confirm else Res.string.remove_from_library,
                    ),
                )
            },
            text =
                if (isLocal || isEditable || deleteFailed) {
                    {
                        Column {
                            if (isLocal || isEditable) Text(stringResource(Res.string.action_cannot_be_undone))
                            if (deleteFailed) {
                                Text(stringResource(Res.string.error_occurred), color = colorScheme.error)
                            }
                        }
                    }
                } else {
                    null
                },
            confirmButton = {
                Button(
                    onClick = {
                        PlaylistAlbumState.deleteSelectedPlaylist { result ->
                            result.onSuccess {
                                showDeleteDialog = false
                                deleteFailed = false
                                navigator.goBack()
                            }
                            result.onFailure { deleteFailed = true }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(if (isLocal || isEditable) Res.string.delete else Res.string.remove_from_library))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        deleteFailed = false
                    },
                    modifier = Modifier.focusRequester(cancelFocusRequester),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }

    PopupVisibility(visible = showAddSongDialog) {
        AddSongDialog(
            existingSongIds = songs.mapTo(mutableSetOf()) { it.id },
            onDismiss = { showAddSongDialog = false },
            onAddSong = { song, allowDuplicate ->
                PlaylistAlbumState.addSong(song, allowDuplicate) { result ->
                    result.onFailure { error ->
                        coverScope.launch {
                            val message =
                                if (error is PlaylistSongAlreadyPresentException) {
                                    getString(Res.string.song_already_in_playlist, song.title)
                                } else {
                                    getString(Res.string.error_occurred)
                                }
                            AppState.snackbarHostState.showSnackbar(message)
                        }
                    }
                }
            },
        )
    }

    PopupContent(editError) { error ->
        AlertDialog(
            onDismissRequest = { editError = null },
            confirmButton = {
                TextButton(onClick = { editError = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.ok)) }
            },
            text = {
                Text(
                    stringResource(
                        if (error is PlaylistPartiallyUpdatedException) {
                            Res.string.playlist_update_partially_failed
                        } else {
                            Res.string.playlist_update_failed
                        },
                    ),
                )
            },
        )
    }
    PopupVisibility(visible = coverError) {
        AlertDialog(
            onDismissRequest = { coverError = false },
            title = { Text(stringResource(Res.string.edit_playlist_cover)) },
            text = { Text(stringResource(Res.string.playlist_cover_update_failed)) },
            confirmButton = {
                TextButton(
                    onClick = { coverError = false },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.ok)) }
            },
        )
    }
}

private fun PlaylistItem?.isUserCreatedPlaylist(): Boolean =
    this?.canEdit == true &&
        id !in setOf("liked", "downloaded", "LP_LIKED", "LP_DOWNLOADED")
