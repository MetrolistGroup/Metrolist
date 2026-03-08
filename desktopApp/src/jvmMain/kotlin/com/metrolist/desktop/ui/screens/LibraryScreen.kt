/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.LibraryContentState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.content.PodcastState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.components.AlbumContextMenu
import com.metrolist.desktop.ui.components.ArtistContextMenu
import com.metrolist.desktop.ui.components.EpisodeContextMenu
import com.metrolist.desktop.ui.components.LibraryItemContextMenu
import com.metrolist.desktop.ui.components.PlaylistContextMenu
import com.metrolist.desktop.ui.components.PodcastContextMenu
import com.metrolist.desktop.ui.components.SongContextMenu
import com.metrolist.shared.db.isHistoryPlaylistId
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_cannot_be_undone
import com.metrolist.shared.generated.resources.artist
import com.metrolist.shared.generated.resources.artists
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.create_playlist
import com.metrolist.shared.generated.resources.create_playlist_dialog_title
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.delete_playlist_confirm
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.grid_view
import com.metrolist.shared.generated.resources.hidden_items_count
import com.metrolist.shared.generated.resources.hidden_library_items
import com.metrolist.shared.generated.resources.import_playlist
import com.metrolist.shared.generated.resources.library_empty
import com.metrolist.shared.generated.resources.library_sort_updated
import com.metrolist.shared.generated.resources.list_view
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.new_playlist
import com.metrolist.shared.generated.resources.playlist_create_action
import com.metrolist.shared.generated.resources.playlist_name
import com.metrolist.shared.generated.resources.playlist_rename_action
import com.metrolist.shared.generated.resources.remove_from_library
import com.metrolist.shared.generated.resources.rename_playlist_dialog_title
import com.metrolist.shared.generated.resources.restore
import com.metrolist.shared.generated.resources.sort_options
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.UserPlaylist
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.settings.HiddenLibraryItems
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.settings.LibraryViewType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.initialLibraryFilter
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.FabMenuPopup
import com.metrolist.shared.ui.components.LibraryItemContainer
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.ScrollingAddButton
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.rememberScrollingFabState
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.screens.LibraryAutoPlaylist
import com.metrolist.shared.ui.screens.LibraryContent
import com.metrolist.shared.ui.screens.LibraryContentText
import com.metrolist.shared.ui.screens.LibraryFilter
import com.metrolist.shared.ui.screens.libraryAutoPlaylists
import com.metrolist.shared.ui.screens.libraryContentText
import com.metrolist.shared.ui.screens.rememberLibrarySearchScrollState
import com.metrolist.shared.ui.theme.MdSpacing
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryScreen(
    sections: Map<String, List<YTItem>>,
    colorScheme: ColorScheme,
    libraryGridState: LazyGridState,
    onScrollStateChange: (LazyGridState) -> Unit,
) {
    var selectedFilter by
        remember {
            mutableStateOf(
                when (Settings.shared.initialLibraryFilter()) {
                    LibraryFilterType.DEFAULT,
                    LibraryFilterType.ALL,
                    -> null

                    LibraryFilterType.TRACKS -> LibraryFilter.TRACKS

                    LibraryFilterType.PLAYLISTS -> LibraryFilter.PLAYLISTS

                    LibraryFilterType.ARTISTS -> LibraryFilter.ARTISTS

                    LibraryFilterType.ALBUMS -> LibraryFilter.ALBUMS

                    LibraryFilterType.PODCASTS -> LibraryFilter.PODCASTS
                },
            )
        }
    var isGridView by remember { mutableStateOf(LibraryState.libraryViewType == LibraryViewType.GRID) }
    var manualOrder by remember { mutableStateOf(Settings.shared.libraryManualOrder) }
    var searchQuery by remember { mutableStateOf("") }
    val activeLibraryGridState = rememberLibrarySearchScrollState(searchQuery, libraryGridState)
    SideEffect { onScrollStateChange(activeLibraryGridState) }
    var isSearchOpen by remember { mutableStateOf(false) }
    val hiddenItemVersion by HiddenLibraryItems.version.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    LaunchedEffect(UiState.createPlaylistRequested) {
        if (UiState.createPlaylistRequested) {
            showCreateDialog = true
            UiState.createPlaylistRequested = false
        }
    }
    var showNewPlaylistMenu by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var playlistToRename by remember { mutableStateOf<PlaylistItem?>(null) }
    var renameName by remember { mutableStateOf("") }
    var playlistToDelete by remember { mutableStateOf<PlaylistItem?>(null) }
    var playlistDeleteFailed by remember { mutableStateOf(false) }
    var showHiddenItems by remember { mutableStateOf(false) }
    val hiddenItemKeys = remember(hiddenItemVersion) { HiddenLibraryItems.keys() }
    val hiddenItems = remember(hiddenItemVersion) { HiddenLibraryItems.all() }
    val autoPlaylists = libraryAutoPlaylists()
    val items = remember(sections) { sections.values.flatten() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LibraryContent(
                presentation = LibraryContentState.presentation,
                text = libraryContentText(),
                items = items,
                autoPlaylists = autoPlaylists,
                hiddenItemKeys = hiddenItemKeys,
                isLoading = LibraryContentState.isLibraryLoading,
                error = LibraryContentState.libraryError,
                onRetry = { LibraryContentState.fetchLibraryData(force = true) },
                isGridView = isGridView,
                selectedFilter = selectedFilter,
                manualOrder = manualOrder,
                topPadding = MdSpacing.sm,
                compactGrid = false,
                useGridItemSize = false,
                onFilterChange = {
                    selectedFilter = it
                    Settings.shared.libraryFilter =
                        when (it) {
                            null -> LibraryFilterType.ALL
                            LibraryFilter.TRACKS -> LibraryFilterType.TRACKS
                            LibraryFilter.PLAYLISTS -> LibraryFilterType.PLAYLISTS
                            LibraryFilter.ARTISTS -> LibraryFilterType.ARTISTS
                            LibraryFilter.ALBUMS -> LibraryFilterType.ALBUMS
                            LibraryFilter.PODCASTS -> LibraryFilterType.PODCASTS
                        }
                },
                onGridViewChange = {
                    isGridView = it
                    LibraryState.updateLibraryViewType(if (it) LibraryViewType.GRID else LibraryViewType.LIST)
                },
                onManualOrderChange = {
                    manualOrder = it
                    Settings.shared.libraryManualOrder = it
                },
                onItemClick = ::openLibraryItem,
                onPlaySongs = { songs, index -> PlayerState.playTrack(songs[index], songs) },
                currentSongId = PlayerState.currentTrack?.id,
                isPlaying = PlayerState.isPlaying,
                onAutoPlaylistClick = LibraryState::navigateToAutoPlaylist,
                onPlayAlbum = { album -> PlaylistAlbumState.playAlbum(album.id) },
                searchQuery = searchQuery,
                isSearchOpen = isSearchOpen,
                onSearchQueryChange = {
                    searchQuery = it
                    LibraryContentState.updateLibrarySearchQuery(it)
                },
                onSearchToggle = {
                    isSearchOpen = !isSearchOpen
                    if (!isSearchOpen) {
                        searchQuery = ""
                        LibraryContentState.updateLibrarySearchQuery("")
                    }
                },
                contentMatchedSongs = LibraryContentState.contentMatchedSongs,
                contentSearchHasMore = LibraryContentState.contentSearchHasMore.collectAsState().value,
                onLoadMoreContentMatches = LibraryContentState::loadMoreContentMatches,
                onSearchDescendingChange = LibraryContentState::setSearchDescending,
                scrollState = activeLibraryGridState,
                itemContainer = { hiddenItem, item, isGridView, onClick, itemContent ->
                    val playlist = item as? PlaylistItem
                    val contextMenu = rememberCursorContextMenuState()
                    val pinnedId = playlist?.id ?: "auto:${hiddenItem.id}"
                    LibraryItemContainer(
                        isGridView = isGridView,
                        onMenuClick = contextMenu::open,
                        modifier = Modifier.cursorContextMenu(contextMenu).clickable(onClick = onClick),
                    ) {
                        itemContent()
                    }
                    val hideFromLibrary = {
                        HiddenLibraryItems.hide(hiddenItem)
                        Unit
                    }
                    when (item) {
                        null -> {
                            val autoPlaylist = autoPlaylists.firstOrNull { it.type == hiddenItem.id }
                            if (autoPlaylist != null) {
                                PlaylistContextMenu(
                                    playlist =
                                        PlaylistItem(
                                            id = "auto:${autoPlaylist.type}",
                                            title = autoPlaylist.title,
                                            thumbnail = null,
                                            author = autoPlaylist.subtitle,
                                        ),
                                    fallbackIcon = autoPlaylist.icon,
                                    expanded = contextMenu.expanded,
                                    offset = contextMenu.offset,
                                    onHideFromLibrary = hideFromLibrary,
                                    onDismiss = contextMenu::dismiss,
                                )
                            }
                        }

                        is SongItem -> {
                            SongContextMenu(
                                song = item,
                                expanded = contextMenu.expanded,
                                offset = contextMenu.offset,
                                onHideFromLibrary = hideFromLibrary,
                                onDismiss = contextMenu::dismiss,
                            )
                        }

                        is EpisodeItem -> {
                            EpisodeContextMenu(
                                episode = item,
                                expanded = contextMenu.expanded,
                                offset = contextMenu.offset,
                                onHideFromLibrary = hideFromLibrary,
                                onDismiss = contextMenu::dismiss,
                            )
                        }

                        is AlbumItem -> {
                            AlbumContextMenu(
                                album = item,
                                expanded = contextMenu.expanded,
                                offset = contextMenu.offset,
                                onHideFromLibrary = hideFromLibrary,
                                pinnedId = pinnedId,
                                onDismiss = contextMenu::dismiss,
                            )
                        }

                        is ArtistItem -> {
                            ArtistContextMenu(
                                artist = item,
                                expanded = contextMenu.expanded,
                                offset = contextMenu.offset,
                                onHideFromLibrary = hideFromLibrary,
                                pinnedId = pinnedId,
                                onDismiss = contextMenu::dismiss,
                            )
                        }

                        is PlaylistItem -> {
                            val isHistoryPlaylist = isHistoryPlaylistId(item.id)
                            PlaylistContextMenu(
                                playlist = item,
                                expanded = contextMenu.expanded,
                                offset = contextMenu.offset,
                                onHideFromLibrary = hideFromLibrary.takeUnless { isHistoryPlaylist },
                                pinnedId = pinnedId,
                                onRename =
                                    item.takeIf { it.isEditable }?.let {
                                        {
                                            renameName = it.title
                                            playlistToRename = it
                                        }
                                    },
                                onDelete =
                                    {
                                        playlistDeleteFailed = false
                                        playlistToDelete = item
                                    }.takeUnless { isHistoryPlaylist },
                                onDismiss = contextMenu::dismiss,
                            )
                        }

                        is PodcastItem -> {
                            PodcastContextMenu(
                                podcast = item,
                                expanded = contextMenu.expanded,
                                offset = contextMenu.offset,
                                onRemoveFromLibrary = hideFromLibrary,
                                pinnedId = pinnedId,
                                onDismiss = contextMenu::dismiss,
                            )
                        }

                        else -> {
                            LibraryItemContextMenu(
                                playlist = playlist,
                                pinnedId = pinnedId,
                                expanded = contextMenu.expanded,
                                offset = contextMenu.offset,
                                onDismiss = contextMenu::dismiss,
                                onHide = hideFromLibrary,
                            )
                        }
                    }
                },
            )
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(activeLibraryGridState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp, horizontal = 4.dp),
            style =
                defaultScrollbarStyle().copy(
                    minimalHeight = 32.dp,
                    unhoverColor = colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    hoverColor = colorScheme.onSurfaceVariant,
                ),
        )
        ScrollingAddButton(
            label = stringResource(Res.string.new_playlist),
            onClick = { showNewPlaylistMenu = true },
            state = rememberScrollingFabState(activeLibraryGridState),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp),
        ) {
            FabMenuPopup(
                expanded = showNewPlaylistMenu,
                onDismissRequest = { showNewPlaylistMenu = false },
            ) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    DropdownMenuItem(
                        modifier = Modifier.navigationPopupSource(),
                        text = { Text(stringResource(Res.string.create_playlist)) },
                        onClick = {
                            showNewPlaylistMenu = false
                            showCreateDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.import_playlist)) },
                        onClick = {
                            showNewPlaylistMenu = false
                            UiState.openPlaylistImportRequested = true
                            UiState.showSettings = true
                        },
                    )
                }
            }
        }
        if (hiddenItems.isNotEmpty()) {
            FilledTonalButton(
                onClick = { showHiddenItems = true },
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.hidden_items_count, hiddenItems.size))
            }
        }
    }

    PopupVisibility(visible = showHiddenItems) {
        AlertDialog(
            onDismissRequest = { showHiddenItems = false },
            title = { Text(stringResource(Res.string.hidden_library_items)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScrollWithDrag(rememberScrollState())) {
                    hiddenItems.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    HiddenLibraryItems.restore(item.key)
                                    if (hiddenItems.size == 1) showHiddenItems = false
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) { Text(stringResource(Res.string.restore)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showHiddenItems = false },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.close)) }
            },
        )
    }

    PopupVisibility(visible = showCreateDialog) {
        PlaylistNameDialog(
            title = stringResource(Res.string.create_playlist_dialog_title),
            confirmActionLabel = stringResource(Res.string.playlist_create_action),
            value = createName,
            onValueChange = { createName = it },
            onDismiss = { showCreateDialog = false },
        ) {
            val name = createName
            showCreateDialog = false
            createName = ""
            LibraryContentState.createPlaylist(name) { result -> result.onSuccess { PlaylistAlbumState.fetchPlaylistData(it) } }
        }
    }
    PopupContent(playlistToRename) { playlist ->
        PlaylistNameDialog(
            title = stringResource(Res.string.rename_playlist_dialog_title),
            confirmActionLabel = stringResource(Res.string.playlist_rename_action),
            value = renameName,
            onValueChange = { renameName = it },
            onDismiss = { playlistToRename = null },
        ) {
            LibraryContentState.renamePlaylist(playlist, renameName) {}
            playlistToRename = null
        }
    }
    playlistToDelete?.let { playlist ->
        val deletesPlaylist = playlist.isLocal || playlist.isEditable
        val cancelFocusRequester = remember { FocusRequester() }
        LaunchedEffect(playlist.id) { cancelFocusRequester.requestFocus() }
        AlertDialog(
            onDismissRequest = {
                playlistToDelete = null
                playlistDeleteFailed = false
            },
            title = {
                Text(
                    if (deletesPlaylist) stringResource(Res.string.delete_playlist_confirm) else playlist.title,
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (deletesPlaylist) Res.string.action_cannot_be_undone else Res.string.remove_from_library,
                        ),
                    )
                    if (playlistDeleteFailed) {
                        Text(stringResource(Res.string.error_occurred), color = colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        LibraryContentState.deletePlaylist(playlist) { result ->
                            result.onSuccess {
                                playlistToDelete = null
                                playlistDeleteFailed = false
                            }
                            result.onFailure { playlistDeleteFailed = true }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(if (deletesPlaylist) Res.string.delete else Res.string.remove_from_library))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        playlistToDelete = null
                        playlistDeleteFailed = false
                    },
                    modifier = Modifier.focusRequester(cancelFocusRequester),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

private fun openLibraryItem(item: YTItem) {
    when (item) {
        is SongItem -> {
            PlayerState.playTrack(item)
        }

        is EpisodeItem -> {
            PlayerState.playTrack(item.toSongItem())
        }

        is PlaylistItem -> {
            PlaylistAlbumState.fetchPlaylistData(item.id, item.thumbnail)
        }

        is AlbumItem -> {
            val id = item.id
            PlaylistAlbumState.selectedAlbumIdSetter = id
            PlaylistAlbumState.fetchAlbumData(id)
        }

        is ArtistItem -> {
            ArtistState.fetchArtistData(item)
        }

        is PodcastItem -> {
            PodcastState.fetch(item.id)
        }

        else -> {}
    }
}

@Composable
internal fun PlaylistNameDialog(
    title: String,
    confirmActionLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, onValueChange, label = { Text(stringResource(Res.string.playlist_name)) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = onConfirm, shapes = ButtonDefaults.shapes()) {
                Text(confirmActionLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) } },
    )
}
