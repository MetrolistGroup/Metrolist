@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.PlaylistNotFoundException
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.cacheRemotePlaylist
import com.metrolist.shared.db.cachedRemotePlaylistSections
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.loadAndCachePlaylistPage
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.error
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.playlist_removed_from_youtube
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.unable_to_load_podcast
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canEdit
import com.metrolist.shared.model.filterForPlaylistSearch
import com.metrolist.shared.model.indexOfPlaylistOccurrence
import com.metrolist.shared.model.playlistOccurrenceKey
import com.metrolist.shared.model.playlistSearchIndices
import com.metrolist.shared.model.sortedForPlaylist
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.playback.fullArtistShelfQueue
import com.metrolist.shared.playlist.PlaylistChanges
import com.metrolist.shared.playlist.addSongToStoredPlaylist
import com.metrolist.shared.playlist.moveSongInStoredPlaylist
import com.metrolist.shared.playlist.removeSongsFromStoredPlaylist
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.SavedPlaylistSongSorting
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.AlbumLikeState
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.components.AddSongSheetContent
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.LoadingScreen
import com.metrolist.shared.ui.components.ModalBottomSheet
import com.metrolist.shared.ui.components.PlaylistOrderControls
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.SongSelectionControls
import com.metrolist.shared.ui.components.SongSwipeActions
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.listState
import com.metrolist.shared.ui.screens.AboutArtistContent
import com.metrolist.shared.ui.screens.AlbumContent
import com.metrolist.shared.ui.screens.ArtistScreen
import com.metrolist.shared.ui.screens.PlaylistContent
import com.metrolist.shared.ui.screens.PodcastContent
import com.metrolist.shared.ui.screens.albumContentText
import com.metrolist.shared.ui.screens.playlistContentText
import com.metrolist.shared.ui.screens.playlistOrderControlsText
import com.metrolist.shared.ui.selection.rememberSelectionState
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.viewmodels.AboutArtistViewModel
import com.metrolist.shared.viewmodels.ArtistSubscriptionState
import com.metrolist.shared.viewmodels.PlaylistPageLoader
import com.metrolist.shared.viewmodels.artistCatalogTitles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.time.TimeSource

@Composable
internal fun MobileAlbumScreen(
    albumId: String,
    loader: MobileDetailLoader<AlbumPage>,
    fallbackTitle: String? = null,
    playbackController: PlaybackController,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onMoreOptions: (AlbumItem) -> Unit,
    onSongMenu: (SongItem) -> Unit,
    onSwipeFeedback: (String) -> Unit,
    onDownload: (List<SongItem>) -> Unit,
    onAddToPlaylist: (List<SongItem>) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val isFavorite by remember(albumId) { AlbumLikeState.albumLiked(albumId) }.collectAsState(false)
    val favoriteErrorText = stringResource(Res.string.error_occurred)
    val selection = rememberSelectionState(albumId)
    val albumPage = loader.page
    val isLoading = loader.isLoading
    val playbackState by remember(playbackController) {
        playbackController.listState
    }.collectAsState(playbackController.state.value.listState())
    val errorText = stringResource(Res.string.error)

    val blockedVersion by BlockedContent.version.collectAsState()

    val loadError = errorText.takeIf { loader.failed }

    when {
        isLoading -> {
            LoadingScreen(modifier.fillMaxSize())
        }

        loadError != null || albumPage == null -> {
            Column(
                modifier = modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(loadError ?: errorText)
                Button(onClick = loader::load, modifier = Modifier.padding(top = 12.dp), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.retry))
                }
            }
        }

        else -> {
            val loaded = albumPage
            val page = remember(loaded, blockedVersion) { BlockedContent.filterAlbumPage(loaded) }
            AlbumContent(
                isTrackSelected = { selection.isSelected(it.id) },
                trackContainer = { song, content -> SongSwipeActions(song, onSwipeFeedback, content = content) },
                album = page.album,
                songs = page.songs,
                text = albumContentText(),
                onPlayAll = { queue ->
                    playbackController.playQueue(queue)
                },
                onShuffle = { queue ->
                    playbackController.playQueue(queue.shuffled())
                },
                onMoreOptions = { onMoreOptions(page.album) },
                isFavorite = isFavorite,
                onFavorite = {
                    AlbumLikeState.toggleAlbumLike(page.album, page.songs) { result ->
                        if (result.isFailure) onSwipeFeedback(favoriteErrorText)
                    }
                },
                onArtistClick = onArtistClick,
                onAlbumClick = onAlbumClick,
                onTrackClick = { song, queue, index ->
                    if (selection.active) {
                        selection.toggle(song.id)
                    } else {
                        playbackController.playQueue(queue, index)
                    }
                },
                onTrackLongClick = { song -> selection.begin(song.id) },
                selectionActive = selection.active,
                selectionControls = {
                    val selectedSongs = page.songs.filter { selection.isSelected(it.id) }
                    val allIds = page.songs.mapTo(mutableSetOf()) { it.id }
                    SongSelectionControls(
                        selectedCount = selectedSongs.size,
                        selectedSongs = selectedSongs,
                        onPlay = { playbackController.playQueue(selectedSongs) },
                        onShuffle = {
                            playbackController.playQueue(selectedSongs.shuffled())
                        },
                        allSelected = selection.allSelected(allIds),
                        onDone = selection::exit,
                        onSelectAll = { selection.toggleAll(allIds) },
                        onPlayNext = { playbackController.playNext(selectedSongs) },
                        onAddToQueue = { playbackController.addToQueue(selectedSongs) },
                        onDownload = { onDownload(selectedSongs) },
                        onAddToPlaylist = { onAddToPlaylist(selectedSongs) },
                    )
                },
                trackTrailingContent = { song, _ ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selection.active) {
                            Checkbox(checked = selection.isSelected(song.id), onCheckedChange = { selection.toggle(song.id) })
                        } else {
                            IconButton(
                                modifier = Modifier.navigationPopupSource(),
                                onClick = { onSongMenu(song) },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options))
                            }
                        }
                    }
                },
                onTrackMenuClick = onSongMenu,
                onBackClick = onBack,
                currentSongId = playbackState.currentSongId,
                isPlaying = playbackState.isPlaying,
                description = page.description,
                otherVersions = page.otherVersions,
                contentPadding = PaddingValues(bottom = 88.dp + bottomContentPadding),
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun MobileArtistScreen(
    artistId: String,
    loader: MobileDetailLoader<ArtistPageResult>,
    subscription: ArtistSubscriptionState,
    fallbackTitle: String? = null,
    playbackController: PlaybackController,
    onBack: () -> Unit,
    onAbout: (ArtistItem, List<String>) -> Unit,
    onItemClick: (YTItem) -> Unit,
    onSongMenu: (YTItem) -> Unit,
    onError: (String) -> Unit,
    onBrowse: ((MobileBrowseDestination) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val playbackState by remember(playbackController) {
        playbackController.listState
    }.collectAsState(playbackController.state.value.listState())
    val artistPage = loader.page
    val isLoading = loader.isLoading
    var operationError by remember(loader) { mutableStateOf<String?>(null) }
    val errorText = stringResource(Res.string.error)

    val blockedVersion by BlockedContent.version.collectAsState()

    val loadError = operationError ?: errorText.takeIf { loader.failed }

    if (!isLoading && artistPage == null) {
        Column(
            modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(loadError ?: errorText)
            Button(onClick = loader::load, modifier = Modifier.padding(top = 12.dp), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.retry))
            }
        }
        return
    }

    val page = remember(artistPage, blockedVersion) { artistPage?.let(BlockedContent::filterArtistPage) }
    val sections = page?.sections.orEmpty()
    val artist =
        remember(page, fallbackTitle) {
            (sections["header"]?.firstOrNull() as? ArtistItem)
                ?: page?.let {
                    ArtistItem(
                        id = artistId,
                        title = fallbackTitle.orEmpty(),
                        thumbnail = null,
                    )
                }
        }
    val allSongs =
        remember(sections) {
            sections.values
                .flatten()
                .filterIsInstance<SongItem>()
                .distinctBy { it.id }
        }

    fun playArtistSongs(shuffle: Boolean) {
        scope.launch {
            val songs = GlobalYouTubeRepository.instance.getArtistSongs(artistId, page) { operationError = errorText }
            val queue = if (shuffle) songs.shuffled() else songs
            queue.firstOrNull()?.let {
                if (!shuffle && playbackController.state.value.isShuffled) playbackController.toggleShuffle()
                playbackController.play(it, queue, songsOnly = true)
            }
        }
    }

    ArtistScreen(
        currentSongId = playbackState.currentSongId,
        isPlaying = playbackState.isPlaying,
        artist = artist,
        artistId = artistId,
        initialArtistName = fallbackTitle,
        sections = sections,
        sectionBrowseIds = page?.sectionBrowseIds.orEmpty(),
        isLoading = isLoading,
        errorMessage = loadError,
        onRefresh = {
            operationError = null
            loader.load()
        },
        onItemClick = { item ->
            if (item is SongItem) {
                scope.launch {
                    val songs = fullArtistShelfQueue(item, sections, page?.sectionBrowseIds.orEmpty())
                    playbackController.play(songs.first { it.id == item.id }, songs)
                }
            } else {
                onItemClick(item)
            }
        },
        onItemLongClick = onSongMenu,
        onSectionClick =
            onBrowse?.let { browse ->
                { browseId, params, title ->
                    browse(MobileBrowseDestination(browseId, params, title))
                }
            },
        isSubscribed = subscription.isSubscribed,
        subscribeSuccessCount = subscription.subscribeSuccessCount,
        showSubscribe = true,
        onSubscribeClick = {
            subscription.toggle(
                scope,
                request = { GlobalYouTubeRepository.instance.subscribeChannel(artistId, it) },
                onError = { onError(errorText) },
            )
        },
        isSongLiked = { songId -> SongLikeState.isLiked(songId) },
        onLikeSong = { SongLikeState.toggleLike(it) },
        onBackClick = onBack,
        onAboutClick = { artist?.let { onAbout(it, artistCatalogTitles(artistPage?.sections.orEmpty())) } },
        description = artist?.description,
        aboutImageUrl = artist?.banner ?: artist?.thumbnail,
        onMenuClick = {
            onSongMenu(artist ?: CollectionPreviews.artist(artistId) ?: ArtistItem(artistId, fallbackTitle.orEmpty(), null))
        },
        onPlayAllClick = { playArtistSongs(false) },
        onShuffleClick = { playArtistSongs(true) },
        onMixClick = {
            allSongs.firstOrNull { !it.isUnavailable }?.let { playbackController.startRadio(it) }
        },
        contentPadding = PaddingValues(bottom = 88.dp),
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
internal fun MobilePlaylistScreen(
    playlistId: String,
    database: MusicDatabase,
    fallbackTitle: String? = null,
    playbackController: PlaybackController,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onMoreOptions: (PlaylistItem) -> Unit,
    onEdit: (PlaylistItem) -> Unit,
    onSongMenu: (SongItem) -> Unit,
    onDownload: (List<SongItem>) -> Unit,
    onAddToPlaylist: (List<SongItem>) -> Unit,
    onSwipeFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    val editMutex = remember(playlistId) { Mutex() }
    val missingPlaylistText = stringResource(Res.string.playlist_removed_from_youtube)
    var isLoading by remember(playlistId) { mutableStateOf(true) }
    var loadError by remember(playlistId) { mutableStateOf<String?>(null) }
    var reloadKey by remember(playlistId) { mutableIntStateOf(0) }
    val loader = remember(playlistId, reloadKey) { PlaylistPageLoader(GlobalYouTubeRepository.instance, playlistId) }
    val page by loader.page.collectAsState()
    var pageFailed by remember(loader) { mutableStateOf(false) }
    var editingPrepared by remember(loader) { mutableStateOf(false) }
    var cachedSections by remember(loader) { mutableStateOf<Map<String, List<YTItem>>?>(null) }
    var syncedPage by remember(loader) { mutableStateOf<PlaylistPage?>(null) }
    var storedRemote by remember(loader) { mutableStateOf(false) }
    val playlistData = syncedPage?.sections ?: page?.sections ?: cachedSections
    val selection = rememberSelectionState(playlistId)
    var selectionQuery by remember(playlistId) { mutableStateOf("") }
    val playbackState by remember(playbackController) {
        playbackController.listState
    }.collectAsState(playbackController.state.value.listState())
    val errorText = stringResource(Res.string.error)
    val sorting =
        Settings.shared.playlistSongSorting(
            "playlist:$playlistId",
            Settings.shared.playlistSongSort,
            Settings.shared.playlistSongSortDirection,
        )
    var reorderMode by remember(playlistId) { mutableStateOf(false) }
    var showAddSong by remember(playlistId) { mutableStateOf(false) }
    val reorder =
        remember(playlistId, reloadKey, database, scope) {
            MobilePlaylistReorder(
                scope,
                persist = {
                    mapping,
                    successor,
                    ->
                    editMutex.withLock {
                        moveSongInStoredPlaylist(
                            database,
                            GlobalYouTubeRepository.instance,
                            playlistId,
                            mapping,
                            successor,
                        )
                    }
                },
                reload = {
                    database
                        .dao()
                        .findPlaylistEntity(playlistId)
                        ?.let {
                            database.dao().playlistSongs(it.id).first().map { entry ->
                                entry.toSongItem() to
                                    entry.map
                            }
                        }.orEmpty()
                },
            )
        }

    val blockedVersion by BlockedContent.version.collectAsState()

    LaunchedEffect(playlistId) {
        PlaylistChanges.flow.collect { changedId ->
            if (playlistId.removePrefix("VL") == changedId) reloadKey++
        }
    }
    LaunchedEffect(playlistId, reloadKey) {
        isLoading = true
        loadError = null
        try {
            val started = TimeSource.Monotonic.markNow()
            cachedSections = withContext(Dispatchers.Default) { database.cachedRemotePlaylistSections(playlistId) }
            Logger.d("MobilePlaylist", "cached lookup ms=${started.elapsedNow().inWholeMilliseconds} hit=${cachedSections != null}")
            if (cachedSections != null) {
                isLoading = false
                editMutex.withLock {
                    val refreshStarted = TimeSource.Monotonic.markNow()
                    val refreshed =
                        withContext(Dispatchers.Default) {
                            database.loadAndCachePlaylistPage(GlobalYouTubeRepository.instance, playlistId)
                        }
                    Logger.d(
                        "MobilePlaylist",
                        "full refresh ms=${refreshStarted.elapsedNow().inWholeMilliseconds} songs=${refreshed.songs.size}",
                    )
                    if (!refreshed.isOffline) syncedPage = refreshed
                }
            } else {
                val fetchStarted = TimeSource.Monotonic.markNow()
                withContext(Dispatchers.Default) { loader.loadFirst() }
                Logger.d(
                    "MobilePlaylist",
                    "first page ms=${fetchStarted.elapsedNow().inWholeMilliseconds} songs=${loader.page.value?.songs?.size}",
                )
                isLoading = false
                storedRemote = withContext(Dispatchers.Default) { database.dao().findPlaylistEntity(playlistId)?.browseId != null }
                if (storedRemote && loader.page.value?.hasMore == true) {
                    try {
                        val syncStarted = TimeSource.Monotonic.markNow()
                        val complete = withContext(Dispatchers.Default) { loader.complete() }
                        Logger.d(
                            "MobilePlaylist",
                            "initial sync ms=${syncStarted.elapsedNow().inWholeMilliseconds} songs=${complete.songs.size}",
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        pageFailed = true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            if (error is PlaylistNotFoundException) {
                cachedSections = null
                syncedPage = null
                loadError = missingPlaylistText
            } else if (cachedSections == null) {
                loadError = errorText
            }
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(page, storedRemote) {
        val complete = page?.takeIf { storedRemote && !it.hasMore && !it.isOffline } ?: return@LaunchedEffect
        val header = complete.sections["header"]?.firstOrNull() as? PlaylistItem ?: return@LaunchedEffect
        try {
            editMutex.withLock {
                if (!editingPrepared) withContext(Dispatchers.Default) { database.cacheRemotePlaylist(playlistId, header, complete.songs) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("MobilePlaylist", "cache write failed", e)
        }
    }

    fun loadRemaining(action: (List<SongItem>) -> Unit = {}) {
        scope.launch {
            try {
                val complete =
                    reorder.entries.takeIf { it.isNotEmpty() }?.map { it.first }
                        ?: syncedPage?.songs ?: cachedSections?.playlistSongs()
                        ?: withContext(Dispatchers.Default) { loader.complete().songs }
                pageFailed = false
                action(BlockedContent.filterSongs(complete))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                pageFailed = true
            }
        }
    }

    LaunchedEffect(selectionQuery, loader) {
        if (selectionQuery.isNotBlank()) loadRemaining()
    }

    when {
        isLoading -> {
            LoadingScreen(modifier.fillMaxSize())
        }

        loadError != null || playlistData == null -> {
            Column(
                modifier = modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(loadError ?: errorText)
                Button(onClick = { reloadKey++ }, modifier = Modifier.padding(top = 12.dp), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.retry))
                }
            }
        }

        else -> {
            val data = playlistData
            val loadedSongs = remember(data) { data.playlistSongs() }
            val metadataRevision by SongMetadataState.revision.collectAsState()
            val sourceSongs = if (reorder.entries.isNotEmpty()) reorder.entries.map { it.first } else loadedSongs
            val songs =
                remember(sourceSongs, blockedVersion, sorting, reorderMode, metadataRevision) {
                    val visible = BlockedContent.filterSongs(sourceSongs)
                    if (reorderMode) visible else visible.sortedForPlaylist(sorting.sort, sorting.direction)
                }
            val playlist =
                remember(data, fallbackTitle, songs) {
                    (data["header"]?.firstOrNull() as? PlaylistItem)
                        ?: PlaylistItem(
                            id = playlistId,
                            title = fallbackTitle.orEmpty(),
                            thumbnail = songs.firstOrNull()?.thumbnail,
                            author = null,
                            songCount = "${songs.size}",
                        )
                }

            val bookmark = rememberPlaylistBookmark(playlist, database, scope) { onSwipeFeedback(errorText) }
            val canEdit = playlist.canEdit && GlobalYouTubeRepository.instance.canManagePlaylists()

            fun withEditablePlaylist(action: () -> Unit) {
                scope.launch {
                    try {
                        editMutex.withLock {
                            if (!editingPrepared) {
                                val complete = syncedPage?.songs ?: cachedSections?.playlistSongs() ?: loader.complete().songs
                                database.cacheRemotePlaylist(playlistId, playlist, complete)
                                val stored = checkNotNull(database.dao().findPlaylistEntity(playlistId))
                                reorder.update(
                                    database
                                        .dao()
                                        .playlistSongs(stored.id)
                                        .first()
                                        .map { it.toSongItem() to it.map },
                                )
                                editingPrepared = true
                            }
                            action()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        onSwipeFeedback(errorText)
                    }
                }
            }
            PopupVisibility(visible = showAddSong) {
                ModalBottomSheet(onDismissRequest = { showAddSong = false }) {
                    AddSongSheetContent(
                        existingSongIds = loadedSongs.mapTo(mutableSetOf()) { it.id },
                        onDismiss = { showAddSong = false },
                        onAddSong = { song, allowDuplicate ->
                            scope.launch {
                                editMutex.withLock {
                                    addSongToStoredPlaylist(database, GlobalYouTubeRepository.instance, playlistId, song, allowDuplicate)
                                        .onFailure { onSwipeFeedback(errorText) }
                                }
                            }
                        },
                    )
                }
            }
            val displayedSongs = remember(songs, selectionQuery, metadataRevision) { songs.filterForPlaylistSearch(selectionQuery) }

            fun key(song: SongItem) = song.playlistOccurrenceKey(songs.indexOfFirst { it === song }.coerceAtLeast(0))
            val displayedRows =
                remember(songs, displayedSongs) {
                    val visible = displayedSongs.toSet()
                    songs.mapIndexedNotNull { index, song ->
                        if (song in visible) song to song.playlistOccurrenceKey(index) else null
                    }
                }
            val selectedSongs =
                remember(displayedRows, selection.active, selection.selectedIds) {
                    if (selection.active) displayedRows.filter { selection.isSelected(it.second) }.map { it.first } else emptyList()
                }
            val displayedKeys = remember(displayedRows) { displayedRows.mapTo(mutableSetOf()) { it.second } }
            PlaylistContent(
                isTrackSelected = { selection.isSelected(key(it)) },
                trackContainer = {
                    song,
                    _,
                    content,
                    ->
                    SongSwipeActions(song, onSwipeFeedback, enabled = !reorderMode && !selection.active, content = content)
                },
                playlist = playlist,
                songs = displayedSongs,
                artworkSongs = loadedSongs,
                isFavorite = bookmark.isBookmarked,
                onFavorite = bookmark.toggle,
                onAddSong = if (canEdit) ({ withEditablePlaylist { showAddSong = true } }) else null,
                reorderMode = reorderMode,
                onReorder = { from, to ->
                    val moving = displayedSongs.getOrNull(from)?.playlistLocalMappingId
                    val target = displayedSongs.getOrNull(to)?.playlistLocalMappingId
                    if (moving != null &&
                        target != null
                    ) {
                        reorder.move(moving, target, selectedSongs.mapNotNullTo(mutableSetOf()) { it.playlistLocalMappingId })
                    }
                },
                onReorderFinished = { reorder.commit { onSwipeFeedback(errorText) } },
                orderControls = {
                    PlaylistOrderControls(
                        text = playlistOrderControlsText(),
                        sort = sorting.sort,
                        sortDirection = sorting.direction,
                        reorderMode = reorderMode,
                        canReorder = canEdit,
                        onSortChange = {
                            Settings.shared.setPlaylistSongSorting(
                                "playlist:$playlistId",
                                SavedPlaylistSongSorting(it, sorting.direction),
                            )
                        },
                        onSortDirectionChange = {
                            Settings.shared.setPlaylistSongSorting(
                                "playlist:$playlistId",
                                SavedPlaylistSongSorting(sorting.sort, it),
                            )
                        },
                        onReorderModeChange = { enabled ->
                            selectionQuery = ""
                            if (!enabled) {
                                reorderMode = false
                            } else {
                                withEditablePlaylist { reorderMode = true }
                            }
                        },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                },
                durationSongs = (syncedPage ?: page)?.takeUnless { it.isOffline }?.songs.orEmpty(),
                hasMoreSongs = page?.hasMore == true,
                hiddenSongCount = loadedSongs.size - songs.size,
                paginationContent = {
                    if (page?.hasMore == true) {
                        if (pageFailed) {
                            Button(
                                onClick = {
                                    pageFailed = false
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.Default) { loader.loadMore() }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            pageFailed = true
                                        }
                                    }
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(Res.string.retry))
                            }
                        } else {
                            ContainedLoadingIndicator()
                            LaunchedEffect(page) {
                                try {
                                    withContext(Dispatchers.Default) { loader.loadMore() }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    pageFailed = true
                                }
                            }
                        }
                    }
                },
                searchQuery = selectionQuery,
                onSearchQueryChange = { selectionQuery = it },
                text = playlistContentText(),
                onPlayAll = {
                    loadRemaining { complete ->
                        val queue = complete.sortedForPlaylist(sorting.sort, sorting.direction).filterForPlaylistSearch(selectionQuery)
                        queue.firstOrNull { !it.isUnavailable }?.let { playbackController.play(it, queue) }
                    }
                },
                onShuffle = {
                    loadRemaining { complete ->
                        val shuffled = complete.filterForPlaylistSearch(selectionQuery).shuffled()
                        shuffled.firstOrNull { !it.isUnavailable }?.let { playbackController.play(it, shuffled) }
                    }
                },
                onMoreOptions = { onMoreOptions(playlist) },
                onEdit = if (canEdit) ({ onEdit(playlist) }) else null,
                onTrackClick = { song, index ->
                    if (selection.active) {
                        selection.toggle(displayedRows[index].second)
                    } else {
                        loadRemaining { complete ->
                            val queue = if (reorderMode) songs else complete.sortedForPlaylist(sorting.sort, sorting.direction)
                            val target = queue.indexOfPlaylistOccurrence(song)
                            if (target >= 0) playbackController.playQueue(queue, target)
                        }
                    }
                },
                onTrackLongClick = { song -> if (!reorderMode) selection.begin(key(song)) },
                selectionActive = selection.active,
                selectionControls = {
                    SongSelectionControls(
                        selectedCount = selectedSongs.size,
                        selectedSongs = selectedSongs,
                        onPlay = { selectedSongs.firstOrNull { !it.isUnavailable }?.let { playbackController.play(it, selectedSongs) } },
                        onShuffle = {
                            selectedSongs.shuffled().let { s ->
                                s.firstOrNull { !it.isUnavailable }?.let { playbackController.play(it, s) }
                            }
                        },
                        allSelected = selection.allSelected(displayedKeys),
                        onDone = selection::exit,
                        onSelectAll = {
                            loadRemaining { complete ->
                                val ordered = if (reorderMode) complete else complete.sortedForPlaylist(sorting.sort, sorting.direction)
                                selection.toggleAll(
                                    ordered.playlistSearchIndices(selectionQuery).mapTo(mutableSetOf()) { index ->
                                        ordered[index].playlistOccurrenceKey(index)
                                    },
                                )
                            }
                        },
                        onPlayNext = { playbackController.playNext(selectedSongs) },
                        onAddToQueue = { playbackController.addToQueue(selectedSongs) },
                        onDownload = { onDownload(selectedSongs) },
                        onAddToPlaylist = { onAddToPlaylist(selectedSongs) },
                        onRemove =
                            if (canEdit) {
                                {
                                    scope.launch {
                                        removeSongsFromStoredPlaylist(
                                            database,
                                            GlobalYouTubeRepository.instance,
                                            playlistId,
                                            selectedSongs,
                                        ).onSuccess {
                                            selection.exit()
                                            reloadKey++
                                        }.onFailure { loadError = if (it is PlaylistNotFoundException) missingPlaylistText else errorText }
                                    }
                                }
                            } else {
                                null
                            },
                    )
                },
                trackTrailingContent = { song, index ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selection.active) {
                            val occurrenceKey = displayedRows[index].second
                            Checkbox(checked = selection.isSelected(occurrenceKey), onCheckedChange = { selection.toggle(occurrenceKey) })
                        } else {
                            IconButton(
                                modifier = Modifier.navigationPopupSource(),
                                onClick = { onSongMenu(song) },
                                enabled = !reorderMode,
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options))
                            }
                        }
                    }
                },
                onTrackMenuClick = { song, _ -> if (!reorderMode) onSongMenu(song) },
                onBackClick = onBack,
                onArtistClick = onArtistClick,
                currentSongId = playbackState.currentSongId,
                isPlaying = playbackState.isPlaying,
                contentPadding = PaddingValues(bottom = 88.dp + bottomContentPadding),
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun MobilePodcastScreen(
    podcastId: String,
    fallbackTitle: String? = null,
    database: MusicDatabase,
    playbackController: PlaybackController,
    onBack: () -> Unit,
    onShare: (String) -> Unit = {},
    onSongMenu: (YTItem) -> Unit,
    modifier: Modifier = Modifier,
    onChannelClick: (String) -> Unit = {},
    bottomContentPadding: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    val errorText = stringResource(Res.string.unable_to_load_podcast)
    val playbackState by remember(playbackController) {
        playbackController.listState
    }.collectAsState(playbackController.state.value.listState())
    var page by remember(podcastId) { mutableStateOf<PodcastPage?>(null) }
    var isLoading by remember(podcastId) { mutableStateOf(true) }
    var isSaving by remember(podcastId) { mutableStateOf(false) }
    var loadFailed by remember(podcastId) { mutableStateOf(false) }
    var reloadKey by remember(podcastId) { mutableIntStateOf(0) }

    val blockedVersion by BlockedContent.version.collectAsState()

    LaunchedEffect(podcastId, reloadKey) {
        isLoading = true
        loadFailed = false
        try {
            val loaded = GlobalYouTubeRepository.instance.getPodcast(podcastId, page?.takeIf { it.continuation != null })
            page = loaded
            loadFailed = loaded == null || loaded.continuation != null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w("MobilePodcastScreen", "Podcast load failed: ${t::class.simpleName}")
            loadFailed = true
        } finally {
            isLoading = false
        }
    }

    val currentPage =
        remember(page, blockedVersion) {
            val blocked = BlockedContent.snapshot()
            page?.let { loaded ->
                loaded.copy(episodes = loaded.episodes.filterNot { blocked.isBlocked(it.toSongItem(loaded.podcast.title)) })
            }
        }
    PodcastContent(
        page = currentPage,
        isLoading = isLoading,
        isSaving = isSaving,
        error = errorText.takeIf { loadFailed },
        onBackClick = onBack,
        onChannelClick = onChannelClick,
        onRetry = { reloadKey++ },
        currentEpisodeId = playbackState.currentSongId,
        isPlaying = playbackState.isPlaying,
        onPlay = {
            currentPage?.let { loaded ->
                val songs = loaded.episodes.map { it.toSongItem(loaded.podcast.title) }
                if (songs.isNotEmpty()) playbackController.play(songs.first(), songs)
            }
        },
        onToggleSaved = {
            currentPage?.let { loaded ->
                scope.launch {
                    val saved = !loaded.saved
                    isSaving = true
                    PodcastLibrary
                        .setPodcastSaved(database, loaded.podcast, saved)
                        .onSuccess {
                            page = page?.copy(saved = saved)
                        }
                    isSaving = false
                }
            }
        },
        onPodcastMenuClick = {
            currentPage?.podcast?.let(onSongMenu)
        },
        onEpisodeClick = { episode ->
            currentPage?.let { loaded ->
                val index = loaded.episodes.indexOf(episode)
                val songs = loaded.episodes.map { it.toSongItem(loaded.podcast.title) }
                if (index >= 0) playbackController.playQueue(songs, index, loaded.podcast.title)
            }
        },
        onToggleEpisodeSaved = { episode ->
            scope.launch {
                PodcastLibrary.setEpisodeSaved(database, episode, !episode.saved).onSuccess { updated ->
                    page =
                        page?.let { latest ->
                            latest.copy(episodes = latest.episodes.map { if (it.id == updated.id) updated else it })
                        }
                }
            }
        },
        onPlayNext = { episode ->
            playbackController.playNext(listOf(episode.toSongItem(currentPage?.podcast?.title)))
        },
        onAddToQueue = { episode ->
            playbackController.addToQueue(listOf(episode.toSongItem(currentPage?.podcast?.title)))
        },
        onDownload = null,
        onShare = { episode ->
            onShare(ShareUrls.song(episode.id))
        },
        contentPadding = PaddingValues(bottom = 88.dp + bottomContentPadding),
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
internal fun MobileAboutArtistScreen(
    route: MobileRoute.AboutArtist,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(route.artist.id) { AboutArtistViewModel(route.artist.id, route.artist.title, scope) }
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(viewModel, Settings.shared.lastResolvedContentLanguage) {
        viewModel.load(route.catalogTitles)
    }
    AboutArtistContent(
        artistName = route.artist.title,
        info = route.artist,
        uiState = uiState,
        onBackClick = onBack,
        bottomContentPadding = bottomContentPadding,
    )
}
