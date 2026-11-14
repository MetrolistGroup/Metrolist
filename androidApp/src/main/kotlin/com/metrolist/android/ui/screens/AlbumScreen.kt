@file:OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.metrolist.android.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.LocalPlaylistAdder
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.ui.component.SwipeGestureHandler
import com.metrolist.android.ui.download.rememberDownloadPermissionGate
import com.metrolist.android.ui.menu.AlbumMenu
import com.metrolist.android.ui.menu.SongMenu
import com.metrolist.android.viewmodels.AlbumViewModel
import com.metrolist.shared.api.playlistPageForPlayback
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album_load_error
import com.metrolist.shared.generated.resources.album_no_songs
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.more_actions
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.queued_songs
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.CollectionLoadingPlaceholder
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.CollectionSongsLoadingPlaceholder
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.SongSelectionControls
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.screens.AlbumContent
import com.metrolist.shared.ui.screens.albumContentText
import com.metrolist.shared.ui.selection.rememberSelectionState
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.utils.ShareUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    albumId: String,
    viewModel: AlbumViewModel =
        koinViewModel(
            key = albumId,
            parameters = { parametersOf(albumId) },
        ),
    onBackClick: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onAlbumArtworkClick: ((String, String) -> Unit)? = null,
) {
    val playerConnection = LocalPlayerConnection.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val downloadManager: SongDownloadManager = koinInject()
    val database: MusicDatabase = koinInject()
    val requireDownloadNotifications = rememberDownloadPermissionGate()

    val albumPage by viewModel.albumPage
    val albumInfo = albumPage?.album
    val preview = remember(albumId) { CollectionPreviews.album(albumId) }
    val songs = albumPage?.songs ?: emptyList()
    val isLoading by viewModel.isLoading
    val isLoadingMore by viewModel.isLoadingMore
    val error by viewModel.errorMessage
    val isFavorite by viewModel.isFavorite.collectAsState()
    val favoriteFailed by viewModel.favoriteFailed.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val saveError = stringResource(Res.string.error_occurred)
    LaunchedEffect(favoriteFailed) {
        if (favoriteFailed) {
            snackbarHostState.showSnackbar(saveError)
            viewModel.consumeFavoriteFailure()
        }
    }

    val downloadedSongIds by
        remember(database) {
            database.dao().cachedAudioIdsByKind(KIND_DOWNLOAD).distinctUntilChanged().map { ids ->
                withContext(Dispatchers.Default) { ids.toSet() }
            }
        }.collectAsState(emptySet())

    val currentSong by playerConnection?.currentSong?.collectAsState(initial = null)
        ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) }

    val addToPlaylist = LocalPlaylistAdder.current
    val selection = rememberSelectionState(albumId)

    fun playQueue(
        songs: List<SongItem>,
        index: Int,
        shuffleModeEnabled: Boolean? = null,
        selectedOnly: Boolean = false,
    ) {
        playerConnection?.playQueue(
            songs,
            index,
            albumInfo?.title,
            finite = true,
            shuffleModeEnabled = shuffleModeEnabled,
            playlistPage = albumPage?.playlistPageForPlayback(selectedOnly),
        )
    }

    fun downloadSongs(targetSongs: List<SongItem>) {
        requireDownloadNotifications {
            coroutineScope.launch {
                val completed = downloadManager.downloadAll(targetSongs)
                val total = targetSongs.distinctBy { it.id }.size
                Toast
                    .makeText(
                        context,
                        getPluralString(Res.plurals.queued_songs, total, completed, total),
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }

    fun withAllSongs(action: (List<SongItem>) -> Unit) {
        coroutineScope.launch {
            try {
                action(viewModel.allSongs())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, getString(Res.string.error_unknown), Toast.LENGTH_SHORT).show()
            }
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var selectedMenuSong by remember { mutableStateOf<SongItem?>(null) }

    val listState = rememberLazyListState()
    val indexByKey = remember(songs) { songs.mapIndexed { index, song -> "${song.id}_$index" to index }.toMap() }
    LaunchedEffect(albumPage?.playlistPage, isLoading) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { indexByKey[it.key] }
                .maxOrNull() ?: -1
        }.distinctUntilChanged()
            .collect { if (error == null) viewModel.loadMore(it) }
    }
    val isTopBarCollapsed by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val miniPlayerInset = LocalMiniPlayerBottomInset.current
    val selectionBar: @Composable () -> Unit = {
        val selectedSongs = songs.filter { selection.isSelected(it.id) }
        val allIds = songs.mapTo(mutableSetOf()) { it.id }
        SongSelectionControls(
            selectedCount = selectedSongs.size,
            selectedSongs = selectedSongs,
            onPlay = { playQueue(selectedSongs, 0, selectedOnly = true) },
            onShuffle = {
                playerConnection?.shuffleQueue(selectedSongs, albumInfo?.title.orEmpty(), finite = true)
            },
            allSelected = selection.allSelected(allIds),
            onDone = selection::exit,
            onSelectAll = { selection.toggleAll(allIds) },
            onPlayNext = { playerConnection?.addToQueue(selectedSongs, playNext = true) },
            onAddToQueue = { playerConnection?.addToQueue(selectedSongs) },
            onDownload = { downloadSongs(selectedSongs) },
            onAddToPlaylist = { addToPlaylist(selectedSongs) },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.imePadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (selection.active) {
                selectionBar()
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = albumInfo?.title ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier.tvFocusHighlight(CircleShape),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                MetrolistIcons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            modifier = Modifier.tvFocusHighlight(CircleShape).navigationPopupSource(),
                            onClick = { showMenu = true },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(MetrolistIcons.Default.MoreVert, contentDescription = stringResource(Res.string.more_actions))
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = if (isTopBarCollapsed) MaterialTheme.colorScheme.surface else Color.Transparent,
                            scrolledContainerColor = if (isTopBarCollapsed) MaterialTheme.colorScheme.surface else Color.Transparent,
                        ),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(bottom = paddingValues.calculateBottomPadding())) {
            when {
                isLoading && albumInfo == null && preview == null -> {
                    CollectionLoadingPlaceholder(
                        modifier = Modifier.padding(top = paddingValues.calculateTopPadding()),
                    )
                }

                error != null && albumInfo == null -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(text = error?.takeIf(String::isNotBlank) ?: stringResource(Res.string.album_load_error))
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { viewModel.refresh() },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.retry)) }
                    }
                }

                !isLoading && songs.isEmpty() && albumInfo == null -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(Res.string.album_no_songs))
                    }
                }

                else -> {
                    (albumInfo ?: preview)?.let { info ->
                        AlbumContent(
                            album = info,
                            songs = songs,
                            text = albumContentText(),
                            paginationContent = {
                                if (albumInfo == null) {
                                    CollectionSongsLoadingPlaceholder()
                                } else {
                                    Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                                        if (isLoadingMore) {
                                            ContainedLoadingIndicator()
                                        } else if (error != null) {
                                            TextButton(onClick = { viewModel.loadMore(Int.MAX_VALUE) }, shapes = ButtonDefaults.shapes()) {
                                                Text(stringResource(Res.string.retry))
                                            }
                                        }
                                    }
                                }
                            },
                            listState = listState,
                            searchAvailable = true,
                            sortAvailable = true,
                            onPlayAll = { queue ->
                                if (queue.isNotEmpty()) {
                                    playQueue(queue, 0)
                                }
                            },
                            onShuffle = { queue ->
                                if (queue.isNotEmpty()) {
                                    playerConnection?.shuffleQueue(queue, info.title, finite = true, playlistPage = albumPage?.playlistPage)
                                }
                            },
                            onMoreOptions = { showMenu = true },
                            isFavorite = isFavorite,
                            onFavorite = { viewModel.toggleFavorite(info, songs) }.takeIf { albumInfo != null },
                            onArtistClick = onArtistClick,
                            onTrackLongClick = { song -> selection.begin(song.id) },
                            trackTrailingContent = { song, _ ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (selection.active) {
                                        Checkbox(checked = selection.isSelected(song.id), onCheckedChange = { selection.toggle(song.id) })
                                    } else {
                                        IconButton(
                                            modifier = Modifier.navigationPopupSource(),
                                            onClick = { selectedMenuSong = song },
                                            shapes = IconButtonDefaults.shapes(),
                                        ) {
                                            Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options))
                                        }
                                    }
                                }
                            },
                            onTrackClick = { song, queue, index ->
                                if (selection.active) {
                                    selection.toggle(song.id)
                                } else if (song.id == currentSong?.id) {
                                    playerConnection?.togglePlayPause()
                                } else {
                                    playQueue(queue, index)
                                }
                            },
                            onTrackMenuClick = { selectedMenuSong = it },
                            currentSongId = currentSong?.id,
                            isPlaying = isPlaying,
                            downloadedSongIds = downloadedSongIds,
                            description = albumPage?.description,
                            otherVersions = albumPage?.otherVersions.orEmpty(),
                            onAlbumClick = onAlbumClick,
                            onAlbumArtworkClick = onAlbumArtworkClick,
                            contentPadding =
                                PaddingValues(
                                    top = paddingValues.calculateTopPadding() + 16.dp,
                                    bottom = miniPlayerInset,
                                ),
                            trackContainer = { song, content ->
                                SwipeGestureHandler(
                                    onSwipeLeft = { playerConnection?.addToQueue(song) },
                                    onSwipeRight = { playerConnection?.addToQueue(song, playNext = true) },
                                    enabled = playerConnection != null && Settings.shared.swipeToSong,
                                    content = content,
                                )
                            },
                        )
                    }
                }
            }

            if (isLoading && albumInfo != null) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = paddingValues.calculateTopPadding()),
                ) {
                    ContainedLoadingIndicator()
                }
            }
        }
    }

    albumInfo?.let { album ->
        PopupVisibility(visible = showMenu) {
            AlbumMenu(
                album = album,
                isFavorite = isFavorite,
                onFavorite = { viewModel.toggleFavorite(album, songs) },
                loadSongs = viewModel::allSongs,
                onDismiss = { showMenu = false },
                onPlay = {
                    playQueue(songs, 0)
                    showMenu = false
                },
                onShuffle = {
                    playerConnection?.shuffleQueue(songs, album.title, finite = true, playlistPage = albumPage?.playlistPage)
                    showMenu = false
                },
                onDownload = { withAllSongs(::downloadSongs) },
                onPlayNext = { withAllSongs { all -> playerConnection?.addToQueue(all, playNext = true) } },
                onAddToQueue = { withAllSongs { all -> playerConnection?.addToQueue(all) } },
                onShare = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, ShareUrls.album(album.id))
                            },
                            null,
                        ),
                    )
                },
                onGoToArtist = onArtistClick,
            )
        }
    }

    PopupContent(selectedMenuSong) { song ->
        SongMenu(
            song = song,
            isLiked = SongLikeState.isLiked(song.id),
            onDismiss = { selectedMenuSong = null },
            onPlayNext = { playerConnection?.addToQueue(song, playNext = true) },
            onAddToQueue = { playerConnection?.addToQueue(song) },
            onLike = { SongLikeState.toggleLike(song) },
            onDownload = { downloadSongs(listOf(song)) },
            onGoToAlbum = song.album?.id?.let { albumId -> { onAlbumClick(albumId) } },
            onGoToArtist = onArtistClick,
        )
    }
}
