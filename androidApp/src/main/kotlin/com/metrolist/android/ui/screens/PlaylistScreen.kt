@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package com.metrolist.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.LocalPlaylistAdder
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.android.ui.component.SwipeGestureHandler
import com.metrolist.android.ui.components.SongMetadataRow
import com.metrolist.android.ui.download.rememberDownloadPermissionGate
import com.metrolist.android.ui.menu.CollectionExportDialog
import com.metrolist.android.ui.menu.PlaylistMenu
import com.metrolist.android.ui.menu.SongMenu
import com.metrolist.android.utils.readPlaylistCover
import com.metrolist.android.viewmodels.PlaylistViewModel
import com.metrolist.shared.api.PlaylistRemovalFailedException
import com.metrolist.shared.db.isHistoryPlaylistId
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_cannot_be_undone
import com.metrolist.shared.generated.resources.add
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.choose_from_library
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.delete_failed
import com.metrolist.shared.generated.resources.delete_playlist_question
import com.metrolist.shared.generated.resources.description
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.edit_playlist
import com.metrolist.shared.generated.resources.edit_playlist_cover
import com.metrolist.shared.generated.resources.edit_playlist_cover_note
import com.metrolist.shared.generated.resources.edit_playlist_cover_note_wait
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.more
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.generated.resources.playlist_cover_update_failed
import com.metrolist.shared.generated.resources.playlist_name
import com.metrolist.shared.generated.resources.playlist_update_failed
import com.metrolist.shared.generated.resources.playlist_update_partially_failed
import com.metrolist.shared.generated.resources.queued_songs
import com.metrolist.shared.generated.resources.remove_custom_image
import com.metrolist.shared.generated.resources.remove_from_library
import com.metrolist.shared.generated.resources.remove_from_playlist
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.share_playlist
import com.metrolist.shared.generated.resources.suggestions
import com.metrolist.shared.model.PlaylistPresentationOptions
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canEdit
import com.metrolist.shared.model.filterForPlaylistSearch
import com.metrolist.shared.model.indexOfPlaylistOccurrence
import com.metrolist.shared.model.playlistOccurrenceKey
import com.metrolist.shared.model.preparePlaylistPresentation
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.model.sortedForPlaylist
import com.metrolist.shared.playlist.PlaylistPartiallyUpdatedException
import com.metrolist.shared.settings.HiddenLibraryItems
import com.metrolist.shared.settings.SavedPlaylistSongSorting
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.AddSongSheetContent
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.CollectionLoadingPlaceholder
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.CollectionSongsLoadingPlaceholder
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.PlaylistCoverImage
import com.metrolist.shared.ui.components.PlaylistOrderControls
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.SongSelectionControls
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.screens.PlaylistContent
import com.metrolist.shared.ui.screens.playlistContentText
import com.metrolist.shared.ui.screens.playlistOrderControlsText
import com.metrolist.shared.ui.selection.rememberSelectionState
import com.metrolist.shared.ui.theme.uiEnhanced
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.utils.ArtworkPrefetchWindow
import com.metrolist.shared.utils.ShareUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlaylistScreen(
    playlistId: String,
    thumbnailHint: String? = null,
    viewModel: PlaylistViewModel =
        koinViewModel(
            key = playlistId,
            parameters = { parametersOf(playlistId, thumbnailHint ?: CollectionPreviews.playlist(playlistId)?.thumbnail) },
        ),
    onBackClick: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
) {
    val sharePlaylistText = stringResource(Res.string.share_playlist)

    val playlistInfo by viewModel.playlistInfo
    val preview = remember(playlistId) { CollectionPreviews.playlist(playlistId) }
    val songs by viewModel.songs
    val durationSongs by viewModel.durationSongs
    val playlistTrackCount by viewModel.playlistTrackCount
    val suggestions by viewModel.suggestions
    val downloadedSongIds by viewModel.downloadedSongIds
    val isFavorite by viewModel.isFavorite
    val isLoading by viewModel.isLoading
    val page by viewModel.page
    val isLoadingMore by viewModel.isLoadingMore
    val errorMessage by viewModel.errorMessage
    val operationState by viewModel.operationState
    val playerConnection = LocalPlayerConnection.current
    val coverUpdateFailedText = stringResource(Res.string.playlist_cover_update_failed)
    val partialUpdateText = stringResource(Res.string.playlist_update_partially_failed)
    val updateFailedText = stringResource(Res.string.playlist_update_failed)
    val deleteFailedText = stringResource(Res.string.delete_failed)
    val errorOccurredText = stringResource(Res.string.error_occurred)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val downloadManager: SongDownloadManager = koinInject()
    val imageLoader: ImageLoader = koinInject()
    val requireDownloadNotifications = rememberDownloadPermissionGate()
    val currentSong by playerConnection?.currentSong?.collectAsState(initial = null)
        ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) }

    var showMenu by remember { mutableStateOf(false) }
    val addToPlaylist = LocalPlaylistAdder.current
    var exportSongs by remember { mutableStateOf<List<SongItem>?>(null) }
    var selectedSongForMenu by remember { mutableStateOf<SongItem?>(null) }
    var showAddSongSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editName by remember(playlistInfo?.title) { mutableStateOf(playlistInfo?.title.orEmpty()) }
    var editDescription by remember(playlistInfo?.description) { mutableStateOf(playlistInfo?.description.orEmpty()) }
    var reorderMode by remember(playlistInfo?.id) { mutableStateOf(false) }
    var searchQuery by remember(playlistInfo?.id) { mutableStateOf("") }
    val sorting =
        Settings.shared.playlistSongSorting(
            "playlist:$playlistId",
            Settings.shared.playlistSongSort,
            Settings.shared.playlistSongSortDirection,
        )
    val sortType = sorting.sort
    val sortDirection = sorting.direction
    val listState = rememberLazyListState()
    val selection = rememberSelectionState(playlistInfo?.id)
    val isEditablePlaylist =
        playlistInfo?.canEdit == true &&
            playlistInfo?.id !in setOf("liked", "downloaded", "LP_LIKED", "LP_DOWNLOADED")
    val isLocalPlaylist = playlistInfo?.isLocal == true || playlistId.startsWith("LP")
    val canRemoveFromLibrary = !isLocalPlaylist && (isFavorite || isEditablePlaylist)
    val canEditSingle = playlistInfo?.canEdit == true
    var showCoverNoteDialog by remember { mutableStateOf(false) }
    var isCoverUploading by remember { mutableStateOf(false) }

    fun coverFiles(id: String): List<File> {
        val dir = File(context.filesDir, "playlist_covers")
        return dir
            .listFiles { _, name ->
                name == "$id.jpg" || (name.startsWith("${id}_") && name.endsWith(".jpg"))
            }?.toList()
            .orEmpty()
    }

    fun persistLocalCoverBytes(
        bytes: ByteArray,
        id: String,
    ): String? =
        runCatching {
            val coversDir = File(context.filesDir, "playlist_covers").apply { mkdirs() }
            val dest = File(coversDir, "${id}_${System.currentTimeMillis()}.jpg")
            dest.outputStream().use { output -> output.write(bytes) }
            coverFiles(id).filter { it.absolutePath != dest.absolutePath }.forEach { runCatching { it.delete() } }
            dest.absolutePath
        }.getOrNull()

    var stagedCoverUri by remember { mutableStateOf<Uri?>(null) }
    var stagedCoverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var stagedCoverRemove by remember { mutableStateOf(false) }

    fun handleRemoveCover() {
        val info = playlistInfo ?: return
        viewModel.removePlaylistCover { result ->
            result.onSuccess { coverFiles(info.id).forEach { runCatching { it.delete() } } }
            result.onFailure {
                Toast.makeText(context, coverUpdateFailedText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clearStagedCover() {
        stagedCoverUri = null
        stagedCoverBytes = null
        stagedCoverRemove = false
    }

    fun applyStagedCover() {
        val info = playlistInfo ?: return
        if (stagedCoverRemove) {
            handleRemoveCover()
            return
        }
        val bytes = stagedCoverBytes ?: return
        if (isCoverUploading) return
        isCoverUploading = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                if (info.isLocal || !info.isEditable) {
                    val path = persistLocalCoverBytes(bytes, info.id)
                    if (path != null) {
                        withContext(Dispatchers.Main) {
                            viewModel.setPlaylistCover(path) { result ->
                                result.onFailure {
                                    Toast.makeText(context, coverUpdateFailedText, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, coverUpdateFailedText, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        viewModel.uploadPlaylistCover(bytes) { result ->
                            result.onFailure {
                                Toast.makeText(context, coverUpdateFailedText, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } finally {
                isCoverUploading = false
            }
        }
    }

    fun handlePickedCover(uri: Uri) {
        if (isCoverUploading) return
        isCoverUploading = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use(::readPlaylistCover) }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (bytes == null) {
                        Toast.makeText(context, coverUpdateFailedText, Toast.LENGTH_SHORT).show()
                    } else {
                        stagedCoverUri = uri
                        stagedCoverBytes = bytes
                        stagedCoverRemove = false
                    }
                }
            } finally {
                isCoverUploading = false
            }
        }
    }
    val coverPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) handlePickedCover(uri)
        }

    fun launchCoverPicker() {
        val info = playlistInfo ?: return
        if (info.isLocal || !info.isEditable) {
            coverPicker.launch(arrayOf("image/*"))
        } else {
            showCoverNoteDialog = true
        }
    }

    fun openFullEdit() {
        editName = playlistInfo?.title.orEmpty()
        editDescription = playlistInfo?.description.orEmpty()
        clearStagedCover()
        showRenameDialog = true
    }

    fun saveFullEdit() {
        applyStagedCover()
        if (isEditablePlaylist && editName.isNotBlank()) {
            viewModel.updatePlaylist(editName, editDescription) { result ->
                result.onFailure {
                    Toast
                        .makeText(
                            context,
                            when (it) {
                                is PlaylistPartiallyUpdatedException -> partialUpdateText
                                is PlaylistRemovalFailedException -> errorOccurredText
                                else -> updateFailedText
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }
        clearStagedCover()
        showRenameDialog = false
    }

    fun cancelFullEdit() {
        clearStagedCover()
        showRenameDialog = false
    }
    // Observe options during composition so control changes also schedule this effect.
    val presentationOptions = PlaylistPresentationOptions(sortType, sortDirection, reorderMode, searchQuery)
    SideEffect {
        viewModel.presentationOptions.value = presentationOptions
    }
    // Scaffold subcomposition must use songs and keys from the same presentation.
    val presentation = viewModel.presentation.value
    val displayedSongs = presentation.songs
    val displayedIndexByKey = presentation.indexByKey

    fun occurrenceKey(song: SongItem): String = song.playlistOccurrenceKey(viewModel.sourceIndices[song] ?: 0)

    fun shuffleAll() {
        val options = presentationOptions
        val title = playlistInfo?.title
        viewModel.withAllSongs { all ->
            val queue = withContext(Dispatchers.Default) { preparePlaylistPresentation(all, options).songs }
            if (queue.isNotEmpty()) playerConnection?.shuffleQueue(queue, title, finite = true)
        }
    }

    LaunchedEffect(playlistId, searchQuery) {
        viewModel.cancelSearchLoad()
        if (searchQuery.isNotBlank()) {
            try {
                viewModel.loadForSearch()
                awaitCancellation()
            } finally {
                viewModel.cancelSearchLoad()
            }
        }
    }
    LaunchedEffect(playlistId, searchQuery, page, isLoadingMore, errorMessage) {
        if (searchQuery.isNotBlank() && page?.hasMore == true && !isLoadingMore && errorMessage == null) {
            viewModel.loadForSearch()
        }
    }
    LaunchedEffect(page, displayedIndexByKey, isLoading, searchQuery) {
        if (searchQuery.isNotBlank()) return@LaunchedEffect
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val isPaginationVisible = visible.any { it.key == "pagination" }
            val lastVisibleSong = visible.mapNotNull { displayedIndexByKey[it.key] }.maxOrNull() ?: -1
            if (isPaginationVisible) Int.MAX_VALUE else lastVisibleSong
        }.distinctUntilChanged().collect { lastVisible ->
            if (errorMessage == null) viewModel.loadMore(lastVisible)
        }
    }
    val selectedSongs =
        remember(displayedSongs, selection.selectedIds) {
            if (selection.selectedIds.isEmpty()) emptyList() else displayedSongs.filter { selection.isSelected(occurrenceKey(it)) }
        }
    val suggestionsTitle = stringResource(Res.string.suggestions)

    LaunchedEffect(playlistId, songs) {
        if (downloadManager.isTrackingPlaylist(playlistId)) downloadManager.downloadMissing(songs, playlistId)
    }

    val artworkLookahead = PLAYLIST_ARTWORK_PREFETCH_COUNT
    val currentPresentation by rememberUpdatedState(presentation)
    LaunchedEffect(playlistId, imageLoader, context, artworkLookahead) {
        val requests =
            ArtworkPrefetchWindow(
                enqueue = { url: String ->
                    imageLoader.enqueue(
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .size(128, 128)
                            .build(),
                    )
                },
                dispose = { it.dispose() },
            )
        try {
            snapshotFlow {
                val presentation = currentPresentation
                val lastVisibleSongIndex =
                    listState.layoutInfo.visibleItemsInfo
                        .mapNotNull { presentation.indexByKey[it.key] }
                        .maxOrNull()
                        ?: -1
                if (lastVisibleSongIndex < 0) return@snapshotFlow emptyList<String>()
                presentation.songs
                    .asSequence()
                    .drop((lastVisibleSongIndex + 1).coerceAtLeast(0))
                    .take(artworkLookahead)
                    .mapNotNull { it.previewThumbnailUrl }
                    .distinct()
                    .toList()
            }.distinctUntilChanged()
                .collect(requests::update)
        } finally {
            requests.clear()
        }
    }

    LaunchedEffect(operationState.message) {
        val message = operationState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeOperationMessage()
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

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val isTopBarCollapsed by remember(scrollBehavior) { derivedStateOf { scrollBehavior.state.overlappedFraction > 0.5f } }
    val miniPlayerInset = LocalMiniPlayerBottomInset.current

    val selectionBar: @Composable () -> Unit = {
        val displayedKeys = displayedSongs.map(::occurrenceKey)
        SongSelectionControls(
            selectedCount = selectedSongs.size,
            selectedSongs = selectedSongs,
            onPlay = { playerConnection?.playQueue(selectedSongs, 0, playlistInfo?.title, finite = true) },
            onShuffle = { playerConnection?.shuffleQueue(selectedSongs, playlistInfo?.title, finite = true) },
            allSelected = selection.allSelected(displayedKeys),
            onDone = selection::exit,
            onSelectAll = { selection.toggleAll(displayedKeys) },
            onPlayNext = { playerConnection?.addToQueue(selectedSongs, playNext = true) },
            onAddToQueue = { playerConnection?.addToQueue(selectedSongs) },
            onDownload = { downloadSongs(selectedSongs) },
            onAddToPlaylist = { addToPlaylist(selectedSongs) },
            onRemove =
                if (isEditablePlaylist) {
                    {
                        viewModel.removeSongs(selectedSongs)
                        selection.exit()
                    }
                } else {
                    null
                },
        )
    }

    Scaffold(
        modifier = Modifier.imePadding().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selection.active) {
                selectionBar()
            } else {
                TopAppBar(
                    title = {
                        if (isTopBarCollapsed) {
                            Text(
                                text = playlistInfo?.title ?: "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
                            Icon(MetrolistIcons.Default.MoreVert, contentDescription = stringResource(Res.string.more))
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
            if (playlistInfo == null && preview == null && (isLoading || errorMessage == null)) {
                CollectionLoadingPlaceholder(Modifier.padding(top = paddingValues.calculateTopPadding()))
            } else if (playlistInfo == null && !isLoading && errorMessage != null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(errorMessage ?: stringResource(Res.string.error_unknown))
                    TextButton(
                        onClick = { viewModel.loadPlaylist() },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.retry)) }
                }
            } else {
                PlaylistContent(
                    selectionActive = selection.active,
                    playlist = playlistInfo ?: preview,
                    loadingHeader =
                        if (playlistInfo == null && preview == null) {
                            { CollectionLoadingPlaceholder() }
                        } else {
                            null
                        },
                    emptyContent = if (playlistInfo == null) ({ CollectionSongsLoadingPlaceholder() }) else null,
                    artworkSongs = songs,
                    durationSongs =
                        durationSongs
                            .takeIf {
                                page?.let { !it.isOffline } ?: (playlistInfo?.isLocal == true || playlistInfo?.id == "liked")
                            }.orEmpty(),
                    hasMoreSongs = page?.hasMore == true,
                    hiddenSongCount = (playlistTrackCount - songs.size).coerceAtLeast(0),
                    onEdit = null,
                    previewThumbnail = thumbnailHint ?: preview?.thumbnail,
                    paginationContent =
                        if (page?.hasMore == true) {
                            {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (isLoadingMore) {
                                        ContainedLoadingIndicator()
                                    } else if (errorMessage != null) {
                                        TextButton(
                                            onClick = { viewModel.loadMore(Int.MAX_VALUE, retry = true) },
                                            shapes = ButtonDefaults.shapes(),
                                        ) {
                                            Text(stringResource(Res.string.retry))
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    songs = displayedSongs,
                    preparedSongKeys = presentation.keys,
                    text = playlistContentText(),
                    onPlayAll = {
                        if (displayedSongs.isNotEmpty()) {
                            playerConnection?.playQueue(
                                displayedSongs,
                                0,
                                playlistInfo?.title,
                                finite = true,
                                playlistPage = page.takeIf { searchQuery.isBlank() },
                            )
                        }
                    },
                    onShuffle = ::shuffleAll,
                    onTrackClick = { _, index ->
                        if (index >= displayedSongs.size) {
                            val suggestionIndex = index - displayedSongs.size
                            playerConnection?.playQueue(suggestions, suggestionIndex, suggestionsTitle)
                        } else if (selection.active) {
                            selection.toggle(occurrenceKey(displayedSongs[index]))
                        } else if (searchQuery.isNotBlank()) {
                            val song = displayedSongs[index]
                            val options = presentationOptions.copy(query = "")
                            val title = playlistInfo?.title
                            viewModel.withPlaybackSongs(options) { all, playbackPage ->
                                val queue = withContext(Dispatchers.Default) { preparePlaylistPresentation(all, options).songs }
                                val startIndex = queue.indexOfPlaylistOccurrence(song)
                                if (startIndex >= 0) {
                                    playerConnection?.playQueue(queue, startIndex, title, finite = true, playlistPage = playbackPage)
                                }
                            }
                        } else {
                            playerConnection?.playQueue(
                                displayedSongs,
                                index,
                                playlistInfo?.title,
                                finite = true,
                                playlistPage = page,
                            )
                        }
                    },
                    onTrackMenuClick = { song, _ -> if (!reorderMode) selectedSongForMenu = song },
                    onTrackLongClick = { song ->
                        if (!reorderMode) selection.begin(occurrenceKey(song))
                    },
                    listState = listState,
                    contentPadding =
                        PaddingValues(
                            top = paddingValues.calculateTopPadding(),
                            bottom =
                                paddingValues.calculateBottomPadding() +
                                    miniPlayerInset,
                        ),
                    currentSongId = currentSong?.id,
                    isPlaying = isPlaying,
                    suggestions = suggestions.takeIf { searchQuery.isBlank() }.orEmpty(),
                    downloadedSongIds = downloadedSongIds,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    reorderMode = reorderMode,
                    onArtistClick = onArtistClick,
                    onReorder = { from, to -> viewModel.moveDisplayedSong(from, to, selectedSongs) },
                    onReorderFinished = viewModel::commitPendingReorder,
                    isFavorite = isFavorite,
                    onFavorite =
                        if (!isLocalPlaylist && !isEditablePlaylist &&
                            playlistInfo?.id !in setOf("liked", "downloaded", "LP_LIKED", "LP_DOWNLOADED", "LM")
                        ) {
                            { viewModel.toggleFavorite { downloadManager.downloadAll(it) } }
                        } else {
                            null
                        },
                    onMoreOptions = { showMenu = true },
                    onAddSong = if (isEditablePlaylist) ({ showAddSongSheet = true }) else null,
                    orderControls = {
                        PlaylistOrderControls(
                            text = playlistOrderControlsText(),
                            sort = sortType,
                            sortDirection = sortDirection,
                            reorderMode = reorderMode,
                            canReorder = isEditablePlaylist,
                            onSortChange = {
                                Settings.shared.setPlaylistSongSorting(
                                    "playlist:$playlistId",
                                    SavedPlaylistSongSorting(it, sortDirection),
                                )
                            },
                            onSortDirectionChange = {
                                Settings.shared.setPlaylistSongSorting(
                                    "playlist:$playlistId",
                                    SavedPlaylistSongSorting(sortType, it),
                                )
                            },
                            onReorderModeChange = {
                                searchQuery = ""
                                val enabled = it
                                if (enabled && page?.hasMore == true) {
                                    viewModel.withAllSongs { reorderMode = true }
                                } else {
                                    reorderMode = enabled
                                }
                            },
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    },
                    trackTrailingContent =
                        if (selection.active) {
                            { song, _ ->
                                val key = occurrenceKey(song)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = selection.isSelected(key),
                                        onCheckedChange = { selection.toggle(key) },
                                    )
                                }
                            }
                        } else {
                            { song, _ ->
                                IconButton(
                                    modifier = Modifier.navigationPopupSource(),
                                    onClick = { if (!reorderMode) selectedSongForMenu = song },
                                    enabled = !reorderMode,
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        MetrolistIcons.Default.MoreVert,
                                        contentDescription = stringResource(Res.string.more),
                                    )
                                }
                            }
                        },
                    trackContainer = { song, isSuggestion, content ->
                        val canRemove =
                            !isSuggestion && isEditablePlaylist && !reorderMode && Settings.shared.swipeToRemoveSong
                        SwipeGestureHandler(
                            onSwipeLeft = {
                                if (canRemove) {
                                    viewModel.removeSong(song)
                                } else {
                                    playerConnection?.addToQueue(song)
                                }
                            },
                            onSwipeRight = { playerConnection?.addToQueue(song, playNext = true) },
                            enabled =
                                playerConnection != null && !reorderMode && !selection.active && (canRemove || Settings.shared.swipeToSong),
                            removeOnSwipeLeft = canRemove,
                            enableSwipeRight = Settings.shared.swipeToSong,
                            content = content,
                        )
                    },
                )
            }

            if ((isLoading || (searchQuery.isNotBlank() && isLoadingMore)) && playlistInfo != null) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = paddingValues.calculateTopPadding()),
                ) {
                    ContainedLoadingIndicator()
                }
            }
            if (operationState.inProgressSongIds.isNotEmpty()) {
                LinearWavyProgressIndicator(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(top = paddingValues.calculateTopPadding()),
                )
            }
        }

        playlistInfo?.let { playlist ->
            PopupVisibility(visible = showMenu) {
                PlaylistMenu(
                    playlist = playlist,
                    isFavorite = isFavorite,
                    onFavorite =
                        if (!isLocalPlaylist && !isEditablePlaylist &&
                            playlist.id !in setOf("liked", "downloaded", "LP_LIKED", "LP_DOWNLOADED", "LM")
                        ) {
                            { viewModel.toggleFavorite { downloadManager.downloadAll(it) } }
                        } else {
                            null
                        },
                    onDismiss = { showMenu = false },
                    onPlay = {
                        playerConnection?.playQueue(
                            displayedSongs,
                            0,
                            playlist.title,
                            finite = true,
                            playlistPage = page.takeIf { searchQuery.isBlank() },
                        )
                        showMenu = false
                    },
                    onShuffle = {
                        shuffleAll()
                        showMenu = false
                    },
                    onDownload = {
                        viewModel.withAllSongs {
                            downloadSongs(
                                it.sortedForPlaylist(sortType, sortDirection).filterForPlaylistSearch(searchQuery),
                            )
                        }
                    },
                    onShare = {
                        val intent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    ShareUrls.playlist(playlist.id),
                                )
                            }
                        context.startActivity(Intent.createChooser(intent, sharePlaylistText))
                    },
                    onPlayNext = {
                        viewModel.withAllSongs { all ->
                            all
                                .sortedForPlaylist(sortType, sortDirection)
                                .filterForPlaylistSearch(searchQuery)
                                .let { playerConnection?.addToQueue(it, playNext = true) }
                        }
                    },
                    onAddToQueue = {
                        viewModel.withAllSongs { all ->
                            all
                                .sortedForPlaylist(sortType, sortDirection)
                                .filterForPlaylistSearch(searchQuery)
                                .let { playerConnection?.addToQueue(it) }
                        }
                    },
                    onRename =
                        if (canEditSingle) {
                            { openFullEdit() }
                        } else {
                            null
                        },
                    onDelete =
                        if ((isEditablePlaylist && isLocalPlaylist) || canRemoveFromLibrary) {
                            { showDeleteDialog = true }
                        } else {
                            null
                        },
                    onHideFromLibrary = { HiddenLibraryItems.hide(playlist) }.takeUnless { isHistoryPlaylistId(playlist.id) },
                    onExport = { viewModel.withAllSongs { exportSongs = it } },
                    loadSongs = { songs },
                )
            }
        }

        PopupContent(exportSongs) { collection ->
            CollectionExportDialog(
                title = playlistInfo?.title.orEmpty(),
                onDismiss = { exportSongs = null },
                loadSongs = { collection },
            )
        }

        if (showRenameDialog) {
            val dialogPreview =
                when {
                    stagedCoverRemove -> null
                    stagedCoverUri != null -> stagedCoverUri.toString()
                    else -> playlistInfo?.customThumbnail ?: playlistInfo?.thumbnail
                }
            val hasStagedCustom = !playlistInfo?.customThumbnail.isNullOrBlank() && !stagedCoverRemove
            AlertDialog(
                onDismissRequest = { cancelFullEdit() },
                title = { Text(stringResource(Res.string.edit_playlist)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (dialogPreview != null) {
                                PlaylistCoverImage(
                                    url = dialogPreview,
                                    modifier =
                                        Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                )
                            } else {
                                Image(
                                    painterResource(Res.drawable.logo),
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { launchCoverPicker() },
                                    modifier = Modifier.tvFocusHighlight(CircleShape),
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text(stringResource(Res.string.choose_from_library))
                                }
                                if (stagedCoverUri != null || hasStagedCustom) {
                                    TextButton(
                                        onClick = {
                                            stagedCoverUri = null
                                            stagedCoverBytes = null
                                            stagedCoverRemove = true
                                        },
                                        modifier = Modifier.tvFocusHighlight(CircleShape),
                                        shapes = ButtonDefaults.shapes(),
                                    ) {
                                        Text(stringResource(Res.string.remove_custom_image))
                                    }
                                }
                            }
                        }
                        if (isEditablePlaylist) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                label = { Text(stringResource(Res.string.playlist_name)) },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = editDescription,
                                onValueChange = { editDescription = it },
                                label = { Text(stringResource(Res.string.description)) },
                                minLines = 3,
                                maxLines = 6,
                            )
                        }
                    }
                },
                confirmButton = {
                    if (isEditablePlaylist) {
                        TextButton(
                            enabled = editName.isNotBlank(),
                            onClick = { saveFullEdit() },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.save)) }
                    } else {
                        TextButton(onClick = { saveFullEdit() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.done)) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { cancelFullEdit() }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                },
            )
        }

        PopupVisibility(visible = showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = {
                    Text(
                        stringResource(
                            if (isLocalPlaylist ||
                                isEditablePlaylist
                            ) {
                                Res.string.delete_playlist_question
                            } else {
                                Res.string.remove_from_library
                            },
                        ),
                    )
                },
                text =
                    if (isLocalPlaylist || isEditablePlaylist) {
                        { Text(stringResource(Res.string.action_cannot_be_undone)) }
                    } else {
                        null
                    },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deletePlaylist { result ->
                                result.onSuccess {
                                    showDeleteDialog = false
                                    onBackClick()
                                }
                                result.onFailure {
                                    Toast
                                        .makeText(
                                            context,
                                            if (it is PlaylistRemovalFailedException) errorOccurredText else deleteFailedText,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(
                            stringResource(
                                if (isLocalPlaylist ||
                                    isEditablePlaylist
                                ) {
                                    Res.string.delete
                                } else {
                                    Res.string.remove_from_library
                                },
                            ),
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteDialog = false },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.cancel)) }
                },
            )
        }

        PopupVisibility(visible = showCoverNoteDialog) {
            AlertDialog(
                onDismissRequest = { showCoverNoteDialog = false },
                title = { Text(stringResource(Res.string.edit_playlist_cover)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.edit_playlist_cover_note))
                        Text(
                            stringResource(Res.string.edit_playlist_cover_note_wait),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCoverNoteDialog = false
                            coverPicker.launch(arrayOf("image/*"))
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.ok)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showCoverNoteDialog = false },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.cancel)) }
                },
            )
        }

        PopupVisibility(visible = showAddSongSheet) {
            AddSongSheet(
                existingSongIds = songs.mapTo(mutableSetOf()) { it.id },
                onDismiss = { showAddSongSheet = false },
                onAddSong = { song, allowDuplicate ->
                    viewModel.addSong(song, allowDuplicate)
                },
            )
        }

        PopupContent(selectedSongForMenu) { song ->
            SongMenu(
                song = song,
                isLiked = SongLikeState.isLiked(song.id),
                onDismiss = { selectedSongForMenu = null },
                onStartRadio = { playerConnection?.startRadio(song) },
                onPlayNext = { playerConnection?.addToQueue(song, playNext = true) },
                onAddToQueue = { playerConnection?.addToQueue(song) },
                onLike = { SongLikeState.toggleLike(song) },
                onDownload = { downloadSongs(listOf(song)) },
                onGoToAlbum = song.album?.id?.let { albumId -> { onAlbumClick(albumId) } },
                onGoToArtist = onArtistClick,
                onRemove = if (isEditablePlaylist) ({ viewModel.removeSong(song) }) else null,
                removeLabel = stringResource(Res.string.remove_from_playlist),
            )
        }
    }
}

@Composable
private fun AddSongSheet(
    existingSongIds: Set<String>,
    onDismiss: () -> Unit,
    onAddSong: (SongItem, Boolean) -> Unit,
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        AddSongSheetContent(existingSongIds, onDismiss, onAddSong, songContent = { song, onClick ->
            AddSongResultRow(song, onClick)
        })
    }
}

@Composable
private fun AddSongResultRow(
    song: SongItem,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        shape = RoundedCornerShape(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                url = song.thumbnail ?: "",
                contentDescription = null,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(uiEnhanced(4.dp, 8.dp))),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                SongMetadataRow(song)
            }
            Icon(MetrolistIcons.Default.Add, contentDescription = stringResource(Res.string.add))
        }
    }
}

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

private const val PLAYLIST_ARTWORK_PREFETCH_COUNT = 10
