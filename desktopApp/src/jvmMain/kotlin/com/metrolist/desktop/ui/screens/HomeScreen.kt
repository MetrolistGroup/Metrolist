@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.BrowseState
import com.metrolist.desktop.state.content.HomeState
import com.metrolist.desktop.state.content.LibraryState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.content.PodcastState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.components.AlbumContextMenu
import com.metrolist.desktop.ui.components.ArtistContextMenu
import com.metrolist.desktop.ui.components.EpisodeContextMenu
import com.metrolist.desktop.ui.components.PlaylistContextMenu
import com.metrolist.desktop.ui.components.PodcastContextMenu
import com.metrolist.desktop.ui.components.RetryableError
import com.metrolist.desktop.ui.components.SongContextMenu
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.home_unavailable
import com.metrolist.shared.generated.resources.pin_to_speed_dial
import com.metrolist.shared.generated.resources.refetch
import com.metrolist.shared.generated.resources.refetch_desc
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.unpin_from_speed_dial
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.DesktopHorizontalScrollControls
import com.metrolist.shared.ui.components.HomeLoadingPlaceholder
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.MediaContextMenu
import com.metrolist.shared.ui.menu.contextMenuAnchor
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.screens.HomeContent
import com.metrolist.shared.ui.screens.homeContentText
import com.metrolist.shared.ui.theme.MdSpacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen() {
    val uiState = HomeState.uiState
    val listState = rememberLazyListState()
    val contextMenu = rememberCursorContextMenuState()
    var selectedMenuItem by remember { mutableStateOf<YTItem?>(null) }
    var showSurpriseMenu by remember { mutableStateOf(false) }

    fun dismissContextMenu() {
        contextMenu.dismiss()
    }

    if (uiState.showLoadingPlaceholder) {
        HomeLoadingPlaceholder(Modifier.fillMaxSize().padding(top = MdSpacing.sm))
        return
    }

    if (uiState.error != null && uiState.hasNoHomeContent()) {
        RetryableError(stringResource(Res.string.home_unavailable), HomeState::refresh)
        return
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.firstVisibleItemIndex > 0) {
                Int.MAX_VALUE
            } else {
                listState.firstVisibleItemScrollOffset
            }
        }.collect { ArtistState.topBarScrollOffset = it }
    }

    Box(
        Modifier
            .fillMaxSize()
            .contextMenuAnchor(contextMenu),
    ) {
        HomeContent(
            uiState = uiState,
            text = homeContentText(),
            onItemClick = ::openHomeItem,
            onItemLongClick = { item ->
                if (item !is BrowseItem) {
                    selectedMenuItem = item
                    showSurpriseMenu = false
                    contextMenu.open()
                }
            },
            onShuffleSongs = { songs -> songs.firstOrNull()?.let { PlayerState.playTrack(it, songs) } },
            onPlaySongs = { songs -> songs.firstOrNull()?.let { PlayerState.playTrack(it, songs) } },
            onSpeedDialItemSecondaryClick = { item ->
                if (item !is BrowseItem) {
                    selectedMenuItem = item
                    showSurpriseMenu = false
                    contextMenu.open()
                }
            },
            onShowSurpriseMenu = {
                selectedMenuItem = null
                showSurpriseMenu = true
                contextMenu.open()
            },
            onShowSurpriseSecondaryMenu = {
                selectedMenuItem = null
                showSurpriseMenu = true
                contextMenu.open()
            },
            onLoadMore = HomeState::loadMoreSections,
            shelves = if (uiState.isContentLoading) emptyList() else null,
            onArtistClick = ArtistState::fetchArtistData,
            onAlbumClick = PlaylistAlbumState::fetchAlbumData,
            onPlayAlbum = { PlaylistAlbumState.playAlbum(it.id) },
            currentSongId = remember { derivedStateOf { PlayerState.currentTrack?.id } },
            isPlaying = remember { derivedStateOf { PlayerState.isPlaying } },
            isLandscape = true,
            listState = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = MdSpacing.sm, bottom = 32.dp),
            header = {
                if (uiState.chips.isNotEmpty()) {
                    item(key = "chips") {
                        val scrollState = rememberScrollState()
                        DesktopHorizontalScrollControls(scrollState) {
                            ChipShelf(
                                options = uiState.chips,
                                selectedOption = uiState.selectedChip,
                                onSelectionChange = HomeState::toggleChip,
                                label = { it.title },
                                scrollState = scrollState,
                            )
                        }
                    }
                }
                if (uiState.isContentLoading) {
                    item(key = "home_loading") {
                        HomeLoadingPlaceholder(Modifier.fillMaxWidth().height(600.dp))
                    }
                } else if (!uiState.error.isNullOrBlank() && uiState.hasNoHomeContent()) {
                    item(key = "home_error") {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(homeContentText().homeUnavailable)
                            TextButton(onClick = HomeState::refresh, shapes = ButtonDefaults.shapes()) {
                                Text(homeContentText().tryAgain)
                            }
                        }
                    }
                }
            },
        )

        selectedMenuItem?.let { item ->
            when (item) {
                is SongItem -> {
                    SongContextMenu(
                        song = item,
                        expanded = contextMenu.expanded,
                        offset = contextMenu.offset,
                        additionalActions =
                            listOf(
                                PlayerMenuAction(
                                    MetrolistIcons.Outlined.Refresh,
                                    stringResource(Res.string.refetch),
                                    stringResource(Res.string.refetch_desc),
                                ) {
                                    HomeState.refetchSongMetadata(item)
                                    dismissContextMenu()
                                },
                            ),
                        onDismiss = ::dismissContextMenu,
                    )
                }

                is AlbumItem -> {
                    AlbumContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = ::dismissContextMenu)
                }

                is ArtistItem -> {
                    ArtistContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = ::dismissContextMenu)
                }

                is PlaylistItem -> {
                    PlaylistContextMenu(
                        item,
                        expanded = contextMenu.expanded,
                        offset = contextMenu.offset,
                        onDismiss = ::dismissContextMenu,
                    )
                }

                is PodcastItem -> {
                    PodcastContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = ::dismissContextMenu)
                }

                is EpisodeItem -> {
                    EpisodeContextMenu(item, expanded = contextMenu.expanded, offset = contextMenu.offset, onDismiss = ::dismissContextMenu)
                }

                is BrowseItem -> {
                    Unit
                }
            }
        }

        if (showSurpriseMenu) {
            val isPinned = uiState.speedDialSurprisePinned
            MediaContextMenu(
                expanded = contextMenu.expanded,
                offset = contextMenu.offset,
                onDismiss = ::dismissContextMenu,
                title = stringResource(Res.string.shuffle),
                subtitle = null,
                thumbnail = null,
                fallbackIcon = MetrolistIcons.Rounded.Shuffle,
                actionGroups =
                    listOf(
                        listOf(
                            PlayerMenuAction(
                                if (isPinned) MetrolistIcons.Outlined.KeepOff else MetrolistIcons.Filled.PushPin,
                                stringResource(
                                    if (isPinned) Res.string.unpin_from_speed_dial else Res.string.pin_to_speed_dial,
                                ),
                            ) {
                                HomeState.setSpeedDialSurprisePinned(!isPinned)
                                dismissContextMenu()
                            },
                        ),
                    ),
            )
        }
    }
}

private fun openHomeItem(item: YTItem) {
    when (item) {
        is SongItem -> {
            PlayerState.playTrack(item)
        }

        is AlbumItem -> {
            PlaylistAlbumState.fetchAlbumData(item.id)
        }

        is ArtistItem -> {
            ArtistState.fetchArtistData(item)
        }

        is PlaylistItem -> {
            val builtin = BuiltinPlaylist.fromId(item.id)
            val collectionType = builtin?.collectionType
            when {
                builtin == BuiltinPlaylist.HISTORY -> DesktopNavigation.navigate(Screen.History)
                builtin == BuiltinPlaylist.NEW_EPISODES -> BrowseState.fetchBrowseData("VLRDPN", title = item.title)
                builtin == BuiltinPlaylist.SAVED_EPISODES -> BrowseState.fetchBrowseData("VLSE", title = item.title)
                collectionType != null -> LibraryState.navigateToAutoPlaylist(collectionType)
                else -> PlaylistAlbumState.fetchPlaylistData(item.id, item.thumbnail)
            }
        }

        is BrowseItem -> {
            BrowseState.fetchBrowseData(item.id, item.params, item.title)
        }

        is PodcastItem -> {
            PodcastState.fetch(item.id)
        }

        is EpisodeItem -> {
            PlayerState.playTrack(item.toSongItem())
        }
    }
}
