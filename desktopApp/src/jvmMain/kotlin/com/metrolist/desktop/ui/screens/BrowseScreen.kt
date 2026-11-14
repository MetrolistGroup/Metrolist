package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.BrowseState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.content.PodcastState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.components.AlbumContextMenu
import com.metrolist.desktop.ui.components.SongContextMenu
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.ui.menu.cursorContextMenu
import com.metrolist.shared.ui.menu.rememberCursorContextMenuState
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.screens.BrowseContent
import org.jetbrains.compose.resources.stringResource

@Composable
fun BrowseScreen(colorScheme: ColorScheme) {
    val navigator = LocalNavigator.current
    val menu = rememberCursorContextMenuState()
    val selectedSong = remember { mutableStateOf<SongItem?>(null) }
    val selectedAlbum = remember { mutableStateOf<AlbumItem?>(null) }

    Box(Modifier.fillMaxSize().cursorContextMenu(menu, openOnSecondaryClick = false)) {
        BrowseContent(
            page = BrowseState.browsePageData,
            browseId = BrowseState.selectedBrowseId,
            title = BrowseState.browsePageData.title ?: BrowseState.selectedBrowseTitle,
            isLoading = BrowseState.isBrowseLoading,
            isLoadingMore = BrowseState.isLoadingMore,
            onBackClick = { navigator.goBack() },
            onItemClick = ::openBrowseItem,
            onItemLongClick = {
                if (it is SongItem) {
                    selectedAlbum.value = null
                    selectedSong.value = it
                    menu.open()
                }
                if (it is AlbumItem) {
                    selectedSong.value = null
                    selectedAlbum.value = it
                    menu.open()
                }
            },
            onLoadMore = BrowseState::loadMore,
            loadMoreError = if (BrowseState.loadMoreFailed) stringResource(Res.string.error) else null,
            hasMore = BrowseState.hasMore,
            currentSongId = remember { derivedStateOf { PlayerState.currentTrack?.id } },
            isPlaying = remember { derivedStateOf { PlayerState.isPlaying } },
        )
        selectedAlbum.value?.let { album ->
            AlbumContextMenu(
                album = album,
                expanded = menu.expanded,
                offset = menu.offset,
                onDismiss = {
                    menu.dismiss()
                },
            )
        }
        selectedSong.value?.let { song ->
            SongContextMenu(
                song = song,
                expanded = menu.expanded,
                offset = menu.offset,
                onDismiss = {
                    menu.dismiss()
                },
            )
        }
    }
}

private fun openBrowseItem(
    item: YTItem,
    songs: List<SongItem>,
) {
    when (item) {
        is SongItem -> PlayerState.playTrack(item, songs.ifEmpty { null })
        is AlbumItem -> PlaylistAlbumState.fetchAlbumData(item.id)
        is ArtistItem -> ArtistState.fetchArtistData(item)
        is PlaylistItem -> PlaylistAlbumState.fetchPlaylistData(item.id, item.thumbnail)
        is BrowseItem -> BrowseState.fetchBrowseData(item.id, item.params, item.title)
        is PodcastItem -> PodcastState.fetch(item.id)
        is EpisodeItem -> PlayerState.playTrack(item.toSongItem())
    }
}
