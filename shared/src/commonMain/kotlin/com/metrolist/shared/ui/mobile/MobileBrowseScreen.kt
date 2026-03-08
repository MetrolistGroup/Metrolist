@file:Suppress("FunctionName")

package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.toPlaylistBrowseId
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
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.ui.screens.BrowseContent
import com.metrolist.shared.viewmodels.BrowseViewModel
import org.jetbrains.compose.resources.stringResource

internal data class MobileBrowseDestination(
    val id: String,
    val params: String?,
    val title: String,
)

internal fun YTItem.mobileBrowseDestination(): MobileBrowseDestination? {
    val browseId =
        when (this) {
            is PlaylistItem -> id.takeIf { it.isNotBlank() && !isLocal && !it.startsWith("LP") }?.toPlaylistBrowseId()
            is AlbumItem, is ArtistItem, is PodcastItem, is BrowseItem -> id.takeIf(String::isNotBlank)
            is SongItem, is EpisodeItem -> null
        } ?: return null
    return MobileBrowseDestination(
        id = browseId,
        params = (this as? BrowseItem)?.params,
        title = title,
    )
}

@Composable
internal fun MobileBrowseScreen(
    destination: MobileBrowseDestination,
    model: BrowseViewModel,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    onBack: () -> Unit,
    onItemClick: (YTItem, List<SongItem>) -> Unit,
    modifier: Modifier = Modifier,
    onSongMenu: ((YTItem) -> Unit)? = null,
    bottomContentPadding: Dp = 0.dp,
) {
    val blockedVersion by BlockedContent.version.collectAsState()
    val loadErrorText = stringResource(Res.string.error)
    val page = model.page

    BrowseContent(
        page = remember(page, blockedVersion) { page.copy(sections = BlockedContent.filterSections(page.sections)) },
        browseId = destination.id,
        isLoading = model.isLoading,
        isLoadingMore = model.isLoadingMore,
        onBackClick = onBack,
        onItemClick = onItemClick,
        onItemLongClick = onSongMenu,
        onLoadMore = model::loadMore,
        contentPadding = PaddingValues(bottom = bottomContentPadding),
        modifier = modifier,
        hasMore = model.hasMore,
        error = loadErrorText.takeIf { model.loadFailed },
        onRetry = model::load,
        loadMoreError = loadErrorText.takeIf { model.loadMoreFailed },
        onLoadMoreRetry = model::loadMore,
        currentSongId = currentSongId,
        isPlaying = isPlaying,
    )
}

@Composable
internal fun MobileLocalShelfScreen(
    title: String,
    model: BrowseViewModel,
    currentSongId: State<String?>,
    isPlaying: State<Boolean>,
    isItemEnabled: (YTItem) -> Boolean,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onAlbumPlay: (AlbumItem) -> Unit,
    onBack: () -> Unit,
    onItemClick: (YTItem, List<SongItem>) -> Unit,
    onSongMenu: ((YTItem) -> Unit)?,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val blockedVersion by BlockedContent.version.collectAsState()
    val page = model.page
    val loadErrorText = stringResource(Res.string.error)
    BrowseContent(
        page = remember(page, blockedVersion) { page.copy(sections = BlockedContent.filterSections(page.sections)) },
        title = title,
        sectionPage = true,
        isLoading = model.isLoading,
        isLoadingMore = model.isLoadingMore,
        onBackClick = onBack,
        onItemClick = onItemClick,
        onItemLongClick = onSongMenu,
        onLoadMore = model::loadMore,
        hasMore = model.hasMore,
        error = loadErrorText.takeIf { model.loadFailed },
        onRetry = model::load,
        loadMoreError = loadErrorText.takeIf { model.loadMoreFailed },
        currentSongId = currentSongId,
        isPlaying = isPlaying,
        isItemEnabled = isItemEnabled,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onAlbumPlay = onAlbumPlay,
        contentPadding = PaddingValues(bottom = bottomContentPadding),
        modifier = modifier,
    )
}
