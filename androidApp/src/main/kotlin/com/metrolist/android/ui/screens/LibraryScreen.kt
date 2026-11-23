/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.extensions.isInternetConnected
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.android.ui.component.SwipeGestureHandler
import com.metrolist.android.ui.component.tabContentTopPadding
import com.metrolist.android.ui.components.handleItemClick
import com.metrolist.android.ui.menu.AlbumMenu
import com.metrolist.android.ui.menu.ArtistMenu
import com.metrolist.android.ui.menu.CollectionExportDialog
import com.metrolist.android.ui.menu.PlaylistMenu
import com.metrolist.android.ui.menu.PodcastMenu
import com.metrolist.android.ui.menu.SongMenu
import com.metrolist.android.ui.utils.playSong
import com.metrolist.android.utils.startActivitySafely
import com.metrolist.android.viewmodels.LibraryViewModel
import com.metrolist.android.viewmodels.loadOrderedCachedSongs
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.constants.ThumbnailCornerRadius
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.isHistoryPlaylistId
import com.metrolist.shared.db.refreshUploadedSongs
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_cannot_be_undone
import com.metrolist.shared.generated.resources.android_auto_youtube_playlist
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.create_playlist
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.delete_playlist_question
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.hide_from_library
import com.metrolist.shared.generated.resources.import_playlist
import com.metrolist.shared.generated.resources.local_playlist
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.new_playlist
import com.metrolist.shared.generated.resources.playlist_name
import com.metrolist.shared.generated.resources.remove_from_library
import com.metrolist.shared.generated.resources.rename
import com.metrolist.shared.generated.resources.rename_playlist
import com.metrolist.shared.generated.resources.share_album
import com.metrolist.shared.generated.resources.share_artist
import com.metrolist.shared.generated.resources.share_playlist
import com.metrolist.shared.generated.resources.share_podcast
import com.metrolist.shared.generated.resources.share_song
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.HiddenLibraryItem
import com.metrolist.shared.settings.HiddenLibraryItems
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.initialLibraryFilter
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.UploadNotificationState
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.FabMenuPopup
import com.metrolist.shared.ui.components.LibraryItemContainer
import com.metrolist.shared.ui.components.PlaylistDeleteConfirmation
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.components.ScrollingAddButton
import com.metrolist.shared.ui.components.rememberScrollingFabState
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.groupedMenuItemShapes
import com.metrolist.shared.ui.menu.loadMediaItemSongs
import com.metrolist.shared.ui.navigation.LocalRootContentActive
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.screens.LibraryAutoPlaylist
import com.metrolist.shared.ui.screens.LibraryContent
import com.metrolist.shared.ui.screens.LibraryFilter
import com.metrolist.shared.ui.screens.libraryAutoPlaylists
import com.metrolist.shared.ui.screens.libraryContentText
import com.metrolist.shared.ui.screens.rememberLibrarySearchScrollState
import com.metrolist.shared.ui.theme.gridCellShape
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.utils.ShareUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun LibraryScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToPlaylist: (String, String?) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPodcast: (String) -> Unit = {},
    onNavigateToAutoPlaylist: ((String) -> Unit)? = null,
    onNavigateToPlaylistImport: () -> Unit = {},
    floatingActionsBottomInset: State<Dp>,
    scrollToTopEvents: Flow<Long> = emptyFlow(),
    viewModel: LibraryViewModel = koinViewModel(),
) {
    val shareAlbumText = stringResource(Res.string.share_album)
    val shareArtistText = stringResource(Res.string.share_artist)
    val sharePlaylistText = stringResource(Res.string.share_playlist)
    val sharePodcastText = stringResource(Res.string.share_podcast)
    val shareSongText = stringResource(Res.string.share_song)
    val errorOccurredText = stringResource(Res.string.error_occurred)

    val playerConnection = LocalPlayerConnection.current
    val isGuestPlaybackLocked by AndroidListenTogetherManager.isGuestPlaybackLockedFlow.collectAsStateWithLifecycle()
    val currentSong by playerConnection?.currentSong?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val rootActive = LocalRootContentActive.current
    val currentRootActive by rememberUpdatedState(rootActive)
    val navigationScope = key(rootActive) { rememberCoroutineScope() }
    val context = LocalContext.current
    val database: MusicDatabase = koinInject()
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    val libraryGridState = rememberLazyGridState()
    val activeLibraryGridState = rememberLibrarySearchScrollState(searchQuery, libraryGridState)
    val currentLibraryGridState by rememberUpdatedState(activeLibraryGridState)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val contentMatchedSongs by viewModel.contentMatchedSongs.collectAsStateWithLifecycle()
    val contentSearchHasMore by viewModel.contentSearchHasMore.collectAsStateWithLifecycle()
    val contentFilterVersion by BlockedContent.version.collectAsStateWithLifecycle()

    var lastScrollToTopEvent by rememberSaveable { mutableLongStateOf(-1L) }
    LaunchedEffect(scrollToTopEvents) {
        scrollToTopEvents.collect { event ->
            if (event != lastScrollToTopEvent) {
                lastScrollToTopEvent = event
                currentLibraryGridState.scrollToItem(0)
            }
        }
    }
    var showNewPlaylistMenu by remember { mutableStateOf(false) }
    var selectedFilter by
        remember {
            mutableStateOf(
                when (Settings.shared.initialLibraryFilter()) {
                    LibraryFilterType.DEFAULT, LibraryFilterType.ALL -> {
                        null
                    }

                    LibraryFilterType.TRACKS -> {
                        LibraryFilter.TRACKS
                    }

                    LibraryFilterType.PLAYLISTS -> {
                        LibraryFilter.PLAYLISTS
                    }

                    LibraryFilterType.ARTISTS -> {
                        LibraryFilter.ARTISTS
                    }

                    LibraryFilterType.ALBUMS -> {
                        LibraryFilter.ALBUMS
                    }

                    LibraryFilterType.PODCASTS -> {
                        LibraryFilter.PODCASTS
                    }
                },
            )
        }

    BackHandler(enabled = rootActive && selectedFilter != null) {
        selectedFilter = null
        Settings.shared.libraryFilter = LibraryFilterType.ALL
    }
    val gridFilter = selectedFilter?.let { LibraryFilterType.valueOf(it.name) } ?: LibraryFilterType.ALL
    val isGridView = Settings.shared.libraryGridView(gridFilter)
    var manualOrder by remember { mutableStateOf(Settings.shared.libraryManualOrder) }
    var isSearchOpen by remember { mutableStateOf(false) }
    val hiddenItemVersion by HiddenLibraryItems.version.collectAsStateWithLifecycle()
    var menuItem by remember { mutableStateOf<Pair<HiddenLibraryItem, YTItem?>?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var remoteCreationAvailable by remember { mutableStateOf(false) }
    var createLocally by remember { mutableStateOf(true) }
    var playlistToRename by remember { mutableStateOf<PlaylistItem?>(null) }
    var renameName by remember { mutableStateOf("") }
    val deleteConfirmation = remember { PlaylistDeleteConfirmation(scope) }
    var exportItem by remember { mutableStateOf<YTItem?>(null) }
    var autoPlaylistToExport by remember { mutableStateOf<LibraryAutoPlaylist?>(null) }
    var networkAvailable by remember { mutableStateOf(context.isInternetConnected()) }
    var observedOffline by remember { mutableStateOf(!networkAvailable) }
    val hiddenItemKeys = remember(hiddenItemVersion, contentFilterVersion) { HiddenLibraryItems.keys() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val miniPlayerInset = LocalMiniPlayerBottomInset.current
    val topPadding = tabContentTopPadding()
    val hasNewUploads by UploadNotificationState.hasNewUploads.collectAsStateWithLifecycle()
    val moreOptionsLabel = stringResource(Res.string.more_options)
    val autoPlaylists = libraryAutoPlaylists(hasNewUploads)

    fun share(
        url: String,
        chooserTitle: String,
    ) {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
        context.startActivitySafely(
            Intent.createChooser(intent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    suspend fun loadSongs(item: YTItem): List<SongItem> {
        if (item is PlaylistItem && item.isLocal) {
            return database
                .dao()
                .playlistSongs(item.id)
                .first()
                .map { it.song.toSongItem() }
        }
        return loadMediaItemSongs(item)
    }

    fun withSongs(
        item: YTItem,
        action: (List<SongItem>) -> Unit,
    ) {
        scope.launch {
            val songs =
                try {
                    loadSongs(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(context, errorOccurredText, Toast.LENGTH_SHORT).show()
                    return@launch
                }
            if (songs.isNotEmpty()) action(songs)
        }
    }

    fun queue(
        item: YTItem,
        playNext: Boolean,
    ) {
        when {
            item is PlaylistItem && !item.isLocal -> playerConnection?.queuePlaylist(item.id, playNext)
            item is AlbumItem -> playerConnection?.queueAlbum(item.id, playNext)
            else -> withSongs(item) { songs -> playerConnection?.addToQueue(songs, playNext) }
        }
    }

    suspend fun loadAutoPlaylistSongs(type: String): List<SongItem> {
        val songs =
            when (type) {
                "liked" -> {
                    SongLikeState.likedSongs.ifEmpty {
                        database
                            .dao()
                            .likedSongsByCreateDateAsc()
                            .first()
                            .map { it.toSongItem() }
                    }
                }

                "downloaded" -> {
                    database
                        .dao()
                        .downloadedSongsByCreateDateAsc()
                        .first()
                        .map { it.toSongItem() }
                }

                "cached" -> {
                    loadOrderedCachedSongs(database)
                }

                "top100" -> {
                    database
                        .dao()
                        .mostPlayedSongs(0L, limit = 100)
                        .first()
                        .map { it.toSongItem() }
                }

                "uploaded" -> {
                    runCatching {
                        database.refreshUploadedSongs(GlobalYouTubeRepository.instance)
                    }.getOrElse {
                        database
                            .dao()
                            .uploadedSongsByNameAsc()
                            .first()
                            .map { it.toSongItem() }
                    }
                }

                else -> {
                    emptyList()
                }
            }
        return BlockedContent.filterSongs(songs)
    }

    fun withAutoPlaylistSongs(
        playlist: LibraryAutoPlaylist,
        action: (List<SongItem>) -> Unit,
    ) {
        scope.launch {
            val songs = runCatching { loadAutoPlaylistSongs(playlist.type) }.getOrDefault(emptyList())
            if (songs.isNotEmpty()) action(songs)
        }
    }

    fun queueAutoPlaylist(
        playlist: LibraryAutoPlaylist,
        playNext: Boolean,
    ) {
        withAutoPlaylistSongs(playlist) { songs ->
            playerConnection?.addToQueue(songs, playNext)
        }
    }

    DisposableEffect(context, rootActive) {
        if (!rootActive) return@DisposableEffect onDispose { }
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mainHandler = Handler(Looper.getMainLooper())

        fun publishCurrentState() {
            mainHandler.post { networkAvailable = context.isInternetConnected() }
        }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publishCurrentState()

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = publishCurrentState()

                override fun onLost(network: Network) = publishCurrentState()
            }
        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose {
            connectivityManager.unregisterNetworkCallback(callback)
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    LaunchedEffect(networkAvailable, rootActive) {
        if (!rootActive) return@LaunchedEffect
        if (!networkAvailable) {
            observedOffline = true
        } else if (observedOffline) {
            observedOffline = false
            viewModel.refresh(force = true)
        }
    }

    val onFilterChange: (LibraryFilter?) -> Unit = {
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
    }

    val content: @Composable () -> Unit = {
        if (selectedFilter == LibraryFilter.PODCASTS && !Settings.shared.hidePodcasts) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = topPadding),
            ) {
                val text = libraryContentText()
                ChipShelf(
                    options = LibraryFilter.entries,
                    selectedOption = selectedFilter,
                    onSelectionChange = onFilterChange,
                    label = { text.filterLabels.getValue(it) },
                    modifier = Modifier.padding(top = 1.dp),
                )
                PodcastLibraryScreen(
                    onNavigateToPodcast = onNavigateToPodcast,
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToAutoPlaylist = onNavigateToAutoPlaylist ?: {},
                )
            }
        } else {
            LibraryContent(
                presentation = viewModel.presentation,
                text = libraryContentText(),
                items = uiState.items,
                autoPlaylists = autoPlaylists,
                hiddenItemKeys = hiddenItemKeys,
                isLoading = uiState.isLoading,
                error = uiState.error,
                onRetry = { viewModel.refresh() },
                isGridView = isGridView,
                selectedFilter = selectedFilter,
                manualOrder = manualOrder,
                topPadding = topPadding,
                compactGrid = !isLandscape,
                onFilterChange = onFilterChange,
                onGridViewChange = {
                    Settings.shared.setLibraryGridView(gridFilter, it)
                },
                onManualOrderChange = {
                    manualOrder = it
                    Settings.shared.libraryManualOrder = it
                },
                onItemClick = { item ->
                    handleItemClick(
                        item,
                        onNavigateToAlbum,
                        onNavigateToArtist,
                        onNavigateToPlaylist,
                        onNavigateToPodcast,
                        playerConnection,
                        scope,
                    )
                },
                onPlaySongs = { songs, index -> playerConnection?.playQueue(songs, index) },
                currentSongId = currentSong?.id,
                isPlaying = isPlaying,
                onAutoPlaylistClick = {
                    if (it == "uploaded") UploadNotificationState.markSeen()
                    onNavigateToAutoPlaylist?.invoke(it)
                },
                onPlayAlbum = { album ->
                    withSongs(album) { songs ->
                        playerConnection?.playQueue(songs, queueTitle = album.title, finite = true)
                    }
                },
                searchQuery = searchQuery,
                isSearchOpen = isSearchOpen,
                onSearchQueryChange = {
                    searchQuery = it
                    viewModel.setSearchQuery(it)
                },
                onSearchToggle = { isSearchOpen = !isSearchOpen },
                contentMatchedSongs = contentMatchedSongs,
                contentSearchHasMore = contentSearchHasMore,
                onLoadMoreContentMatches = viewModel::loadMoreContentMatches,
                onSearchDescendingChange = viewModel::setSearchDescending,
                itemContainer = { hiddenItem, item, isGridView, onClick, itemContent ->
                    val content: @Composable () -> Unit = {
                        LibraryItemContainer(
                            isGridView = isGridView,
                            onMenuClick = { menuItem = hiddenItem to item },
                            modifier =
                                Modifier.combinedClickable(
                                    onClick = onClick,
                                    onLongClickLabel = moreOptionsLabel,
                                    onLongClick = { menuItem = hiddenItem to item },
                                ),
                            content = itemContent,
                        )
                    }
                    if (item is SongItem || item is AlbumItem || item is ArtistItem || item is PlaylistItem) {
                        SwipeGestureHandler(
                            onSwipeLeft = { queue(item, playNext = false) },
                            onSwipeRight = { queue(item, playNext = true) },
                            enabled =
                                playerConnection != null &&
                                    Settings.shared.swipeToSong &&
                                    ((item is SongItem && !item.isUnavailable && !isGridView) || searchQuery.isNotBlank()) &&
                                    !isGuestPlaybackLocked,
                            shape = if (isGridView) gridCellShape() else RoundedCornerShape(ThumbnailCornerRadius),
                            content = content,
                        )
                    } else {
                        content()
                    }
                },
                bottomPadding = miniPlayerInset,
                extraBottomPadding = 0.dp,
                scrollState = activeLibraryGridState,
            )
        }
    }

    menuItem?.takeIf { rootActive }?.let { (hiddenItem, item) ->
        val hideFromLibrary = {
            HiddenLibraryItems.hide(hiddenItem)
            Unit
        }
        val autoPlaylist = autoPlaylists.firstOrNull { item == null && it.type == hiddenItem.id }
        when {
            autoPlaylist != null -> {
                val playlistItem =
                    PlaylistItem(
                        id = "auto:${autoPlaylist.type}",
                        title = autoPlaylist.title,
                        thumbnail = null,
                        author = autoPlaylist.subtitle,
                    )
                PlaylistMenu(
                    playlist = playlistItem,
                    onDismiss = { menuItem = null },
                    onPlay = {
                        withAutoPlaylistSongs(
                            autoPlaylist,
                        ) { playerConnection?.playQueue(it, queueTitle = autoPlaylist.title, finite = true) }
                    },
                    onShuffle = {
                        withAutoPlaylistSongs(
                            autoPlaylist,
                        ) {
                            playerConnection?.shuffleQueue(it, queueTitle = autoPlaylist.title, finite = true)
                        }
                    },
                    onPlayNext = { queueAutoPlaylist(autoPlaylist, playNext = true) },
                    onAddToQueue = { queueAutoPlaylist(autoPlaylist, playNext = false) },
                    onHideFromLibrary = hideFromLibrary,
                    onExport = { autoPlaylistToExport = autoPlaylist },
                    fallbackIcon = autoPlaylist.icon,
                    loadSongs = { loadAutoPlaylistSongs(autoPlaylist.type) },
                )
            }

            item is SongItem -> {
                SongMenu(
                    song = item,
                    isLiked = SongLikeState.isLiked(item.id),
                    onDismiss = { menuItem = null },
                    onPlay = { playSong(item, playerConnection, scope) },
                    onStartRadio = { playerConnection?.startRadio(item) },
                    onPlayNext = { playerConnection?.addToQueue(item, playNext = true) },
                    onAddToQueue = { playerConnection?.addToQueue(item) },
                    onLike = { SongLikeState.toggleLike(item) },
                    onShare = { share(ShareUrls.song(item.id), shareSongText) },
                    onGoToAlbum = item.album?.id?.let { albumId -> { onNavigateToAlbum(albumId) } },
                    onGoToArtist = onNavigateToArtist,
                    onHideFromLibrary = hideFromLibrary,
                )
            }

            item is AlbumItem -> {
                AlbumMenu(
                    album = item,
                    onDismiss = { menuItem = null },
                    onPlay = { playerConnection?.playAlbum(item.id, item.title) },
                    onShuffle = {
                        playerConnection?.playAlbum(item.id, item.title, shuffle = true)
                    },
                    onShare = { share(ShareUrls.album(item.id), shareAlbumText) },
                    onPlayNext = { queue(item, playNext = true) },
                    onAddToQueue = { queue(item, playNext = false) },
                    onGoToArtist = onNavigateToArtist,
                    onHideFromLibrary = hideFromLibrary,
                    onExport = { exportItem = item },
                )
            }

            item is ArtistItem -> {
                ArtistMenu(
                    artist = item,
                    onDismiss = { menuItem = null },
                    onShuffle = {
                        withSongs(item) {
                            playerConnection?.shuffleQueue(it, queueTitle = item.title)
                        }
                    },
                    onShare = { share(ShareUrls.artist(item.id), shareArtistText) },
                    onHideFromLibrary = hideFromLibrary,
                )
            }

            item is PlaylistItem -> {
                val isHistoryPlaylist = isHistoryPlaylistId(item.id)
                PlaylistMenu(
                    playlist = item,
                    onDismiss = { menuItem = null },
                    onPlay = {
                        if (item.isLocal) {
                            withSongs(item) { playerConnection?.playQueue(it, queueTitle = item.title, finite = true) }
                        } else {
                            playerConnection?.playPlaylist(item.id, item.title)
                        }
                    },
                    onShuffle = {
                        if (item.isLocal) {
                            withSongs(item) { playerConnection?.shuffleQueue(it, queueTitle = item.title, finite = true) }
                        } else {
                            playerConnection?.playPlaylist(item.id, item.title, shuffle = true)
                        }
                    },
                    onShare =
                        item.takeUnless { it.isLocal }?.let {
                            {
                                share(
                                    ShareUrls.playlist(item.id),
                                    sharePlaylistText,
                                )
                            }
                        },
                    onPlayNext = { queue(item, playNext = true) },
                    onAddToQueue = { queue(item, playNext = false) },
                    onRename =
                        item.takeIf { it.isEditable }?.let { playlist ->
                            {
                                renameName = playlist.title
                                playlistToRename = playlist
                            }
                        },
                    onDelete = { deleteConfirmation.open(item) }.takeUnless { isHistoryPlaylist },
                    onHideFromLibrary = hideFromLibrary.takeUnless { isHistoryPlaylist },
                    onExport = { exportItem = item },
                    loadSongs = { loadSongs(item) },
                )
            }

            item is PodcastItem -> {
                PodcastMenu(
                    podcast = item,
                    onDismiss = { menuItem = null },
                    onPlay = { withSongs(item) { playerConnection?.playQueue(it, queueTitle = item.title, finite = true) } },
                    onShuffle = {
                        withSongs(item) {
                            playerConnection?.shuffleQueue(it, queueTitle = item.title, finite = true)
                        }
                    },
                    onShare = { share(ShareUrls.podcast(item.id), sharePodcastText) },
                    onPlayNext = { queue(item, playNext = true) },
                    onAddToQueue = { queue(item, playNext = false) },
                    onRemoveFromLibrary = hideFromLibrary,
                )
            }

            else -> {
                AdaptiveModalBottomSheet(onDismissRequest = { menuItem = null }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
                        ListItem(
                            leadingContent = { Icon(MetrolistIcons.Filled.VisibilityOff, contentDescription = null) },
                            modifier =
                                Modifier
                                    .tvFocusHighlight(RoundedCornerShape(12.dp))
                                    .clickable {
                                        hideFromLibrary()
                                        menuItem = null
                                    }.padding(horizontal = 4.dp),
                            elevation = ListItemDefaults.elevation(elevation = 0.dp),
                        ) { Text(stringResource(Res.string.hide_from_library)) }
                    }
                }
            }
        }
    }

    exportItem?.takeIf { rootActive }?.let { item ->
        CollectionExportDialog(
            title = item.title,
            onDismiss = { exportItem = null },
            loadSongs = {
                if (item is PlaylistItem && !item.isLocal) {
                    GlobalYouTubeRepository.instance
                        .getPlaylistResult(item.id)
                        .getOrThrow()
                        .playlistSongs()
                } else {
                    loadSongs(item)
                }
            },
        )
    }

    autoPlaylistToExport?.takeIf { rootActive }?.let { playlist ->
        CollectionExportDialog(
            title = playlist.title,
            onDismiss = { autoPlaylistToExport = null },
            loadSongs = { loadAutoPlaylistSongs(playlist.type) },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (LocalIsTv.current) {
            Box(Modifier.fillMaxSize()) { content() }
        } else {
            var userRefreshing by remember { mutableStateOf(false) }
            val isLoadingNow by rememberUpdatedState(uiState.isRefreshing)
            LaunchedEffect(userRefreshing) {
                if (!userRefreshing) return@LaunchedEffect
                withTimeoutOrNull(1_500L) { snapshotFlow { isLoadingNow }.first { it } }
                snapshotFlow { isLoadingNow }.first { !it }
                userRefreshing = false
            }
            val refreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = userRefreshing,
                onRefresh = {
                    userRefreshing = true
                    if (networkAvailable && !isLoadingNow) viewModel.refresh()
                },
                state = refreshState,
                indicator = {
                    RefreshIndicator(
                        state = refreshState,
                        isRefreshing = userRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            ) { content() }
        }

        ScrollingAddButton(
            label = stringResource(Res.string.new_playlist),
            onClick = { showNewPlaylistMenu = true },
            state = rememberScrollingFabState(activeLibraryGridState),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = floatingActionsBottomInset.value + 16.dp),
        ) {
            FabMenuPopup(
                expanded = rootActive && showNewPlaylistMenu,
                onDismissRequest = { showNewPlaylistMenu = false },
            ) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    DropdownMenuItem(
                        modifier = Modifier.tvFocusHighlight(groupedMenuItemShapes(0, 2).shape).navigationPopupSource(),
                        text = { Text(stringResource(Res.string.create_playlist)) },
                        shape = groupedMenuItemShapes(0, 2).shape,
                        onClick = {
                            showNewPlaylistMenu = false
                            remoteCreationAvailable = GlobalYouTubeRepository.instance.canManagePlaylists()
                            createLocally = !remoteCreationAvailable
                            showCreateDialog = true
                        },
                    )
                    DropdownMenuItem(
                        modifier = Modifier.tvFocusHighlight(groupedMenuItemShapes(1, 2).shape),
                        text = { Text(stringResource(Res.string.import_playlist)) },
                        shape = groupedMenuItemShapes(1, 2).shape,
                        onClick = {
                            showNewPlaylistMenu = false
                            onNavigateToPlaylistImport()
                        },
                    )
                }
            }
        }
    }

    PopupVisibility(visible = rootActive && showCreateDialog) {
        PlaylistNameDialog(
            title = stringResource(Res.string.create_playlist),
            confirmLabel = stringResource(Res.string.create_playlist),
            value = createName,
            onValueChange = { createName = it },
            onDismiss = { showCreateDialog = false },
            destination = {
                if (remoteCreationAvailable) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().toggleable(
                                value = createLocally,
                                role = Role.Checkbox,
                                onValueChange = { createLocally = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = createLocally, onCheckedChange = null)
                        Text(stringResource(Res.string.local_playlist))
                    }
                }
                if (!remoteCreationAvailable || !createLocally) {
                    Text(
                        stringResource(
                            if (createLocally) Res.string.local_playlist else Res.string.android_auto_youtube_playlist,
                        ),
                    )
                }
            },
        ) {
            val name = createName
            val local = createLocally
            showCreateDialog = false
            createName = ""
            viewModel.createPlaylist(name, local) { result ->
                result
                    .onSuccess { id ->
                        navigationScope.launch {
                            if (currentRootActive) onNavigateToPlaylist(id, null)
                        }
                    }.onFailure {
                        if (currentRootActive) Toast.makeText(context, errorOccurredText, Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }
    playlistToRename?.takeIf { rootActive }?.let { playlist ->
        PlaylistNameDialog(
            title = stringResource(Res.string.rename_playlist),
            confirmLabel = stringResource(Res.string.rename),
            value = renameName,
            onValueChange = { renameName = it },
            onDismiss = { playlistToRename = null },
        ) {
            viewModel.renamePlaylist(playlist, renameName) {}
            playlistToRename = null
        }
    }
    deleteConfirmation.request?.takeIf { rootActive }?.let { request ->
        val playlist = request.playlist
        val deletesPlaylist = playlist.isLocal || playlist.isEditable
        AlertDialog(
            onDismissRequest = { deleteConfirmation.dismiss(request) },
            title = {
                Text(
                    if (deletesPlaylist) stringResource(Res.string.delete_playlist_question) else playlist.title,
                )
            },
            text = {
                Text(
                    stringResource(if (deletesPlaylist) Res.string.action_cannot_be_undone else Res.string.remove_from_library),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleteConfirmation.isSubmitting(request),
                    onClick = {
                        deleteConfirmation.confirm(request, viewModel::deletePlaylist) {
                            if (currentRootActive) {
                                Toast.makeText(context, errorOccurredText, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(if (deletesPlaylist) Res.string.delete else Res.string.remove_from_library))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteConfirmation.dismiss(request) },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    destination: @Composable () -> Unit = {},
    onConfirm: () -> Unit,
) {
    val isTv = LocalIsTv.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            var tvKeyboardRequested by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value,
                    onValueChange,
                    label = { Text(stringResource(Res.string.playlist_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(showKeyboardOnFocus = !isTv || tvKeyboardRequested),
                    modifier =
                        Modifier.onFocusChanged { if (!it.isFocused) tvKeyboardRequested = false }.onPreviewKeyEvent { event ->
                            if (!isTv) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter -> {
                                    if (event.type == KeyEventType.KeyUp) {
                                        tvKeyboardRequested = true
                                        keyboardController?.show()
                                    }
                                    true
                                }

                                Key.DirectionDown, Key.DirectionRight, Key.DirectionUp, Key.DirectionLeft -> {
                                    if (event.type == KeyEventType.KeyDown) {
                                        keyboardController?.hide()
                                        focusManager.moveFocus(
                                            if (event.key == Key.DirectionDown ||
                                                event.key == Key.DirectionRight
                                            ) {
                                                FocusDirection.Next
                                            } else {
                                                FocusDirection.Previous
                                            },
                                        )
                                    }
                                    true
                                }

                                else -> {
                                    false
                                }
                            }
                        },
                )
                destination()
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = onConfirm,
                modifier = Modifier.tvFocusHighlight(CircleShape),
                shapes = ButtonDefaults.shapes(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusHighlight(CircleShape), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
