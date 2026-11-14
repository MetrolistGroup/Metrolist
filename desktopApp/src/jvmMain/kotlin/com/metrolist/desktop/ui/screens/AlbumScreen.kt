/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.screens
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.ui.components.AlbumContextMenu
import com.metrolist.desktop.ui.components.RetryableError
import com.metrolist.desktop.ui.components.SongContextMenu
import com.metrolist.desktop.ui.components.SongSwipeActions
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.songs
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.filterForPlaylistSearch
import com.metrolist.shared.state.AlbumLikeState
import com.metrolist.shared.ui.components.CollectionHeaderNavigation
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.CollectionSongsLoadingPlaceholder
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.LoadingScreen
import com.metrolist.shared.ui.components.SongSelectionControls
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.screens.AlbumContent
import com.metrolist.shared.ui.screens.albumContentText
import com.metrolist.shared.ui.selection.rememberSelectionState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private class RetainedAlbumPage {
    var page: AlbumPage? = null
}

@Composable
fun AlbumScreen(
    albumId: String,
    colorScheme: ColorScheme,
) {
    val isFavorite by remember(albumId) { AlbumLikeState.albumLiked(albumId) }.collectAsState(false)

    val selected = PlaylistAlbumState.selectedAlbumId == albumId
    val retainedPage = remember(albumId) { RetainedAlbumPage() }
    val preview = remember(albumId) { CollectionPreviews.albumPage(albumId) }
    val currentPage = PlaylistAlbumState.albumPageData.takeIf { selected }
    SideEffect {
        if (currentPage != null) {
            retainedPage.page = currentPage
            CollectionPreviews.record(currentPage)
        }
    }
    val loaded = currentPage ?: retainedPage.page
    val page = loaded ?: preview
    if (selected && PlaylistAlbumState.albumLoadFailed && loaded == null) {
        val errorNavigator = LocalNavigator.current
        CollectionHeaderNavigation({ errorNavigator.goBack() }) {
            RetryableError(stringResource(Res.string.error_occurred)) {
                PlaylistAlbumState.fetchAlbumData(albumId, addToHistory = false)
            }
        }
        return
    }
    if (page == null) {
        val loadingNavigator = LocalNavigator.current
        CollectionHeaderNavigation({ loadingNavigator.goBack() }) { LoadingScreen() }
        return
    }

    val album = page.album
    val songs = page.songs
    val listState = if (loaded != null) HomeState.rememberListState("album_$albumId") else rememberLazyListState()
    val albumMenu = rememberCursorContextMenuState()
    val songMenu = rememberCursorContextMenuState()
    var menuSong by remember { mutableStateOf<SongItem?>(null) }
    var searchQuery by remember(album.id) { mutableStateOf("") }
    val selection = rememberSelectionState(album.id)
    val onFavorite: () -> Unit = {
        AlbumLikeState.toggleAlbumLike(album, songs) { result ->
            if (result.isFailure) {
                AppState.scope.launch {
                    AppState.snackbarHostState.showSnackbar(getString(Res.string.error_occurred))
                }
            }
        }
    }
    val displayedSongs =
        remember(songs, searchQuery) {
            songs.filterForPlaylistSearch(searchQuery)
        }

    Box(
        Modifier
            .fillMaxSize()
            .cursorContextMenu(
                songMenu,
                openOnSecondaryClick = false,
            ).cursorContextMenu(albumMenu, openOnSecondaryClick = false),
    ) {
        val navigator = LocalNavigator.current
        AlbumContent(
            onBackClick = { navigator.goBack() },
            trackContainer = { song, content -> SongSwipeActions(song, content = content) },
            album = album,
            songs = songs,
            paginationContent = {
                if (selected && PlaylistAlbumState.isAlbumLoading) {
                    CollectionSongsLoadingPlaceholder()
                } else if (selected && PlaylistAlbumState.albumLoadFailed) {
                    TextButton(
                        onClick = { PlaylistAlbumState.fetchAlbumData(albumId, addToHistory = false) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.try_again))
                    }
                }
            },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            text = albumContentText(),
            onMoreOptions = { albumMenu.open() }.takeIf { loaded != null },
            isFavorite = isFavorite,
            onFavorite = onFavorite.takeIf { loaded != null },
            onPlayAll = { queue -> queue.firstOrNull { !it.isUnavailable }?.let { PlayerState.playTrack(it, queue) } },
            onShuffle = { queue ->
                if (queue.isNotEmpty()) {
                    val shuffled = queue.shuffled()
                    shuffled.firstOrNull { !it.isUnavailable }?.let { PlayerState.playTrack(it, shuffled) }
                }
            },
            onArtistClick = { ArtistState.fetchArtistData(it) },
            onTrackClick = { song, queue, _ ->
                if (selection.active) selection.toggle(song.id) else PlayerState.playTrack(song, queue)
            },
            onTrackLongClick = { song -> selection.begin(song.id) },
            selectionActive = selection.active,
            selectionControls = {
                val selectedSongs = songs.filter { selection.isSelected(it.id) }
                val displayedIds = displayedSongs.mapTo(mutableSetOf()) { it.id }
                SongSelectionControls(
                    selectedCount = selectedSongs.size,
                    selectedSongs = selectedSongs,
                    onPlay = { selectedSongs.firstOrNull { !it.isUnavailable }?.let { PlayerState.playTrack(it, selectedSongs) } },
                    onShuffle = {
                        selectedSongs.shuffled().let { s -> s.firstOrNull { !it.isUnavailable }?.let { PlayerState.playTrack(it, s) } }
                    },
                    allSelected = selection.allSelected(displayedIds),
                    onDone = selection::exit,
                    onSelectAll = { selection.toggleAll(displayedIds) },
                    onPlayNext = { PlayerState.playNext(selectedSongs) },
                    onAddToQueue = { PlayerState.addToQueue(selectedSongs) },
                    onDownload = { DownloadManager.downloadAll(selectedSongs) },
                    onAddToPlaylist = { PlayerState.showAddToPlaylistSongs = selectedSongs },
                )
            },
            trackTrailingContent = { song, _ ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selection.active) {
                        Checkbox(checked = selection.isSelected(song.id), onCheckedChange = { selection.toggle(song.id) })
                    } else {
                        IconButton(
                            modifier = Modifier.navigationPopupSource(),
                            onClick = {
                                menuSong = song
                                songMenu.open()
                            },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options))
                        }
                    }
                }
            },
            onTrackMenuClick = {
                menuSong = it
                songMenu.open()
            },
            listState = listState,
            selectionBottomPadding = 0.dp,
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            currentSongId = PlayerState.currentTrack?.id,
            isPlaying = PlayerState.isPlaying,
            description = page.description,
            otherVersions = page.otherVersions,
            onAlbumClick = { PlaylistAlbumState.fetchAlbumData(it) },
            onAlbumArtworkClick = { id, token -> PlaylistAlbumState.fetchAlbumData(id, artworkSource = token) },
        )
        if (loaded != null && selected && PlaylistAlbumState.isAlbumLoading) {
            ContainedLoadingIndicator(Modifier.align(Alignment.TopCenter))
        }
        AlbumContextMenu(
            album = album,
            isFavorite = isFavorite,
            onFavorite = onFavorite,
            songs = songs,
            expanded = albumMenu.expanded,
            offset = albumMenu.offset,
            onDismiss = albumMenu::dismiss,
        )
        menuSong?.let { song ->
            SongContextMenu(
                song = song,
                expanded = songMenu.expanded,
                offset = songMenu.offset,
                onDismiss = songMenu::dismiss,
            )
        }
    }
}
