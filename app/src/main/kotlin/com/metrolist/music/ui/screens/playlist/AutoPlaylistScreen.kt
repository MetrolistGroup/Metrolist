/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachReversed
import androidx.compose.ui.util.fastSumBy
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import timber.log.Timber
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.music.LocalDownloadUtil
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.SongSortDescendingKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.constants.SongSortTypeKey
import com.metrolist.music.constants.YtmSyncKey
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.ExoDownloadService
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.DraggableScrollbar
import com.metrolist.music.ui.component.EmptyPlaceholder
import com.metrolist.music.ui.component.HideOnScrollFAB
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.menu.AutoPlaylistMenu
import com.metrolist.music.ui.menu.SelectionSongMenu
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.ui.utils.isScrollingUp
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.AutoPlaylistViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AutoPlaylistScreen(
    navController: NavController,
    viewModel: AutoPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val uploadUnsupportedFormatStr = stringResource(R.string.upload_unsupported_format)
    val uploadFileTooLargeStr = stringResource(R.string.upload_file_too_large)
    val uploadFailedStr = stringResource(R.string.upload_failed)
    val uploadCompleteStr = stringResource(R.string.upload_complete)
    val focusManager = LocalFocusManager.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val playlist =
        when (viewModel.playlist) {
            "liked" -> stringResource(R.string.liked)
            "uploaded" -> stringResource(R.string.uploaded_playlist)
            else -> stringResource(R.string.offline)
        }

    val songs by viewModel.likedSongs.collectAsStateWithLifecycle(null)
    val mutableSongs =
        remember {
            mutableStateListOf<Song>()
        }

    var isSearching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)

    val (ytmSync) = rememberPreference(YtmSyncKey, true)

    val likeLength =
        remember(songs) {
            songs?.fastSumBy { it.song.duration } ?: 0
        }

    val playlistId = viewModel.playlist
    val playlistType =
        when (playlistId) {
            "liked" -> PlaylistType.LIKE
            "downloaded" -> PlaylistType.DOWNLOAD
            "uploaded" -> PlaylistType.UPLOADED
            else -> PlaylistType.OTHER
        }

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection =
        rememberSaveable(
            saver =
                listSaver<MutableList<String>, String>(
                    save = { it.toList() },
                    restore = { it.toMutableStateList() },
                ),
        ) { mutableStateListOf() }
    var selectionAnchorSongId by rememberSaveable { mutableStateOf<String?>(null) }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
        selectionAnchorSongId = null
    }

    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    } else if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    val (sortType, onSortTypeChange) =
        rememberEnumPreference(
            SongSortTypeKey,
            SongSortType.CREATE_DATE,
        )
    val (sortDescending, onSortDescendingChange) = rememberPreference(SongSortDescendingKey, true)

    val downloadUtil = LocalDownloadUtil.current
    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    val scope = rememberCoroutineScope()

    // Upload state
    var showUploadDialog by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var currentUploadIndex by remember { mutableIntStateOf(0) }
    var totalUploads by remember { mutableIntStateOf(0) }
    var currentFileName by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var uploadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    try {
                        val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (e: SecurityException) {
                        Timber.w(e, "Could not take persistable permission")
                    }
                }
                uploadJob =
                    scope.launch {
                        isUploading = true
                        showUploadDialog = true
                        totalUploads = uris.size
                        var successCount = 0

                        uris.forEachIndexed { index, uri ->
                            currentUploadIndex = index + 1
                            uploadProgress = 0f

                            try {
                                // Get actual display name from content resolver
                                var fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "unknown"
                                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                        if (displayNameIndex >= 0) {
                                            val name = cursor.getString(displayNameIndex)
                                            if (!name.isNullOrBlank()) {
                                                fileName = name
                                            }
                                        }
                                    }
                                }
                                currentFileName = fileName
                                val extension = fileName.substringAfterLast('.', "").lowercase()

                                if (extension !in YouTube.SUPPORTED_UPLOAD_TYPES) {
                                    withContext(Dispatchers.Main) {
                                        Toast
                                            .makeText(
                                                context,
                                                uploadUnsupportedFormatStr,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                    return@forEachIndexed
                                }

                                val inputStream = context.contentResolver.openInputStream(uri)
                                val data = inputStream?.readBytes()
                                inputStream?.close()

                                if (data == null) return@forEachIndexed

                                if (data.size > YouTube.MAX_UPLOAD_SIZE) {
                                    withContext(Dispatchers.Main) {
                                        Toast
                                            .makeText(
                                                context,
                                                uploadFileTooLargeStr,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                    return@forEachIndexed
                                }

                                val result =
                                    YouTube.uploadSong(
                                        filename = fileName,
                                        data = data,
                                        onProgress = { progress ->
                                            uploadProgress = progress
                                        },
                                    )

                                if (result.isSuccess && result.getOrDefault(false)) {
                                    successCount++
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast
                                        .makeText(
                                            context,
                                            uploadFailedStr + ": ${e.message}",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                        }

                        isUploading = false

                        if (successCount > 0) {
                            // Show completion briefly
                            uploadProgress = 1f
                            currentFileName = uploadCompleteStr
                            kotlinx.coroutines.delay(1000)

                            // Show toast on main thread
                            withContext(Dispatchers.Main) {
                                Toast
                                    .makeText(
                                        context,
                                        uploadCompleteStr,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }

                            showUploadDialog = false

                            // Refresh uploaded songs
                            viewModel.syncUploadedSongs()
                        } else {
                            showUploadDialog = false
                        }
                    }
            }
        }

    LaunchedEffect(Unit) {
        println("[UPLOAD_DEBUG] AutoPlaylistScreen LaunchedEffect: playlistId=$playlistId, playlistType=$playlistType, ytmSync=$ytmSync")
        if (ytmSync) {
            withContext(Dispatchers.IO) {
                if (playlistType == PlaylistType.LIKE) {
                    println("[UPLOAD_DEBUG] AutoPlaylistScreen: Calling syncLikedSongs()")
                    viewModel.syncLikedSongs()
                }
                if (playlistType == PlaylistType.UPLOADED) {
                    println("[UPLOAD_DEBUG] AutoPlaylistScreen: Calling syncUploadedSongs()")
                    viewModel.syncUploadedSongs()
                }
            }
        } else {
            println("[UPLOAD_DEBUG] AutoPlaylistScreen: ytmSync is false, not syncing")
        }
    }

    LaunchedEffect(songs) {
        mutableSongs.apply {
            clear()
            songs?.let { addAll(it) }
        }
        if (songs?.isEmpty() == true) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState =
                if (songs?.all { downloads[it.song.id]?.state == Download.STATE_COMPLETED } == true) {
                    Download.STATE_COMPLETED
                } else if (songs?.all {
                        downloads[it.song.id]?.state == Download.STATE_QUEUED ||
                            downloads[it.song.id]?.state == Download.STATE_DOWNLOADING ||
                            downloads[it.song.id]?.state == Download.STATE_COMPLETED
                    } == true
                ) {
                    Download.STATE_DOWNLOADING
                } else {
                    Download.STATE_STOPPED
                }
        }
    }

    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, playlist),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
            },
            buttons = {
                TextButton(
                    onClick = { showRemoveDownloadDialog = false },
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        songs!!.forEach { song ->
                            DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                song.song.id,
                                false,
                            )
                        }
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
        )
    }

    // Upload progress dialog
    if (showUploadDialog) {
        DefaultDialog(
            onDismiss = {
                if (isUploading) {
                    uploadJob?.cancel()
                    isUploading = false
                }
                showUploadDialog = false
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.upload),
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.uploading)) },
            buttons = {
                TextButton(
                    onClick = {
                        if (isUploading) {
                            uploadJob?.cancel()
                            isUploading = false
                        }
                        showUploadDialog = false
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            Text(
                text = stringResource(R.string.upload_progress, currentUploadIndex, totalUploads),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = currentFileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val filteredSongs =
        remember(songs, query) {
            if (query.text.isEmpty()) {
                songs ?: emptyList()
            } else {
                songs?.filter { song ->
                    song.song.title.contains(query.text, true) ||
                        song.artists.any { it.name.contains(query.text, true) }
                } ?: emptyList()
            }
        }

    LaunchedEffect(filteredSongs) {
        selection.fastForEachReversed { songId ->
            if (filteredSongs.find { it.id == songId } == null) {
                selection.remove(songId)
            }
        }

        if (selectionAnchorSongId != null && filteredSongs.none { it.id == selectionAnchorSongId }) {
            selectionAnchorSongId = filteredSongs.firstOrNull { it.id in selection }?.id
        }
    }

    val state = rememberLazyListState()

    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val pullRefreshState = rememberPullToRefreshState()
    val canRefresh = playlistType == PlaylistType.LIKE || playlistType == PlaylistType.UPLOADED

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (canRefresh) {
                        Modifier.pullToRefresh(
                            state = pullRefreshState,
                            isRefreshing = isRefreshing,
                            onRefresh = viewModel::refresh,
                        )
                    } else {
                        Modifier
                    },
                ),
    ) {
        LazyColumn(
            state = state,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        ) {
            if (songs != null) {
                if (songs!!.isEmpty()) {
                    item(key = "empty_placeholder") {
                        EmptyPlaceholder(
                            icon = R.drawable.music_note,
                            text = stringResource(R.string.playlist_is_empty),
                        )
                    }
                } else {
                    if (!isSearching) {
                        item(key = "playlist_header") {
                            AutoPlaylistHeader(
                                name = playlist,
                                songs = songs!!,
                                likeLength = likeLength,
                                downloadState = downloadState,
                                onShowRemoveDownloadDialog = { showRemoveDownloadDialog = true },
                                menuState = menuState,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }

                    item(key = "songs_header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp),
                        ) {
                            SortHeader(
                                sortType = sortType,
                                sortDescending = sortDescending,
                                onSortTypeChange = onSortTypeChange,
                                onSortDescendingChange = onSortDescendingChange,
                                sortTypeText = { sortType ->
                                    when (sortType) {
                                        SongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                        SongSortType.NAME -> R.string.sort_by_name
                                        SongSortType.ARTIST -> R.string.sort_by_artist
                                        SongSortType.PLAY_TIME -> R.string.sort_by_play_time
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                if (filteredSongs.isNotEmpty()) {
                    itemsIndexed(
                        items = filteredSongs,
                        key = { index, song -> "${song.id}_$index" },
                    ) { index, song ->
                        val onCheckedChange: (Boolean) -> Unit = {
                            if (it) {
                                selection.add(song.id)
                            } else {
                                selection.remove(song.id)
                            }
                        }

                        SongListItem(
                            song = song,
                            isActive = song.song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            showInLibraryIcon = true,
                            trailingContent = {
                                if (inSelectMode) {
                                    Checkbox(
                                        checked = song.id in selection,
                                        onCheckedChange = onCheckedChange,
                                    )
                                } else {
                                    IconButton(
                                        onClick = {
                                            menuState.show {
                                                SongMenu(
                                                    originalSong = song,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert),
                                            contentDescription = null,
                                        )
                                    }
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (inSelectMode) {
                                                onCheckedChange(song.id !in selection)
                                            } else if (song.song.id == mediaMetadata?.id) {
                                                playerConnection.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = playlist,
                                                        items = songs!!.map { it.toMediaItem() },
                                                        startIndex = songs!!.indexOfFirst { it.id == song.id },
                                                    ),
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            if (!inSelectMode) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                inSelectMode = true
                                                onCheckedChange(true)
                                                selectionAnchorSongId = song.id
                                            } else {
                                                val anchorIndex =
                                                    selectionAnchorSongId?.let { anchorSongId ->
                                                        filteredSongs.indexOfFirst { it.id == anchorSongId }
                                                    } ?: -1

                                                if (anchorIndex == -1) {
                                                    onCheckedChange(true)
                                                    selectionAnchorSongId = song.id
                                                } else {
                                                    val range = if (anchorIndex <= index) anchorIndex..index else index..anchorIndex
                                                    for (rangeIndex in range) {
                                                        val rangeSongId = filteredSongs[rangeIndex].id
                                                        if (rangeSongId !in selection) {
                                                            selection.add(rangeSongId)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    ).animateItem(),
                        )
                    }
                }
            }
        }

        DraggableScrollbar(
            modifier =
                Modifier
                    .padding(
                        LocalPlayerAwareWindowInsets.current
                            .union(WindowInsets.ime)
                            .asPaddingValues(),
                    ).align(Alignment.CenterEnd),
            scrollState = state,
            headerItems = 2,
        )

        if (canRefresh) {
            Indicator(
                isRefreshing = isRefreshing,
                state = pullRefreshState,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
            )
        }

        // Upload FAB for uploaded playlist - positioned above mini player
        if (playlistType == PlaylistType.UPLOADED) {
            androidx.compose.animation.AnimatedVisibility(
                visible = state.isScrollingUp(),
                enter = androidx.compose.animation.slideInVertically { it },
                exit = androidx.compose.animation.slideOutVertically { it },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(
                            LocalPlayerAwareWindowInsets.current
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        ).padding(16.dp),
            ) {
                FloatingActionButton(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "audio/mpeg",
                                "audio/mp4",
                                "audio/x-m4a",
                                "audio/flac",
                                "audio/ogg",
                                "audio/x-ms-wma",
                            ),
                        )
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.upload),
                        contentDescription = stringResource(R.string.upload_songs),
                    )
                }
            }
        }

        TopAppBar(
            title = {
                when {
                    inSelectMode -> {
                        Text(
                            text = pluralStringResource(R.plurals.n_song, selection.size, selection.size),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }

                    isSearching -> {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.search),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                        )
                    }

                    else -> {
                        Text(
                            text = playlist,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        when {
                            isSearching -> {
                                isSearching = false
                                query = TextFieldValue()
                                focusManager.clearFocus()
                            }

                            inSelectMode -> {
                                onExitSelectionMode()
                            }

                            else -> {
                                navController.navigateUp()
                            }
                        }
                    },
                    onLongClick = {
                        if (!isSearching && !inSelectMode) {
                            navController.backToMain()
                        }
                    },
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (inSelectMode) R.drawable.close else R.drawable.arrow_back,
                            ),
                        contentDescription = null,
                    )
                }
            },
            actions = {
                if (inSelectMode) {
                    Checkbox(
                        checked = selection.size == filteredSongs.size && selection.isNotEmpty(),
                        onCheckedChange = {
                            if (selection.size == filteredSongs.size) {
                                selection.clear()
                            } else {
                                selection.clear()
                                selection.addAll(filteredSongs.map { it.id })
                            }
                        },
                    )
                    IconButton(
                        enabled = selection.isNotEmpty(),
                        onClick = {
                            menuState.show {
                                SelectionSongMenu(
                                    songSelection = filteredSongs.filter { it.id in selection },
                                    onDismiss = menuState::dismiss,
                                    clearAction = onExitSelectionMode,
                                    isUploadedPlaylist = playlistType == PlaylistType.UPLOADED,
                                )
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null,
                        )
                    }
                } else if (!isSearching) {
                    IconButton(
                        onClick = { isSearching = true },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun AutoPlaylistHeader(
    name: String,
    songs: List<Song>,
    likeLength: Int,
    downloadState: Int,
    onShowRemoveDownloadDialog: () -> Unit,
    menuState: com.metrolist.music.ui.component.MenuState,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Playlist Thumbnail - Large centered with shadow
        Box(
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        ) {
            androidx.compose.material3.Surface(
                modifier =
                    Modifier
                        .size(240.dp)
                        .shadow(
                            elevation = 24.dp,
                            shape = RoundedCornerShape(3.dp),
                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        ),
                shape = RoundedCornerShape(3.dp),
            ) {
                AsyncImage(
                    model = songs[0].song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Playlist Name
        Text(
            text = name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Metadata - Song Count • Duration
        Text(
            text =
                buildString {
                    append(pluralStringResource(R.plurals.n_song, songs.size, songs.size))
                    if (likeLength > 0) {
                        append(" ")
                        append(makeTimeString(likeLength * 1000L))
                    }
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons Row
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Shuffle Button - Smaller secondary button
            androidx.compose.material3.Surface(
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = name,
                            items = songs.shuffled().map { it.toMediaItem() },
                        ),
                    )
                },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.shuffle),
                        contentDescription = stringResource(R.string.shuffle),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Play Button - Larger primary circular button
            Surface(
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = name,
                            items = songs.map { it.toMediaItem() },
                        ),
                    )
                },
                color = MaterialTheme.colorScheme.primary,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.size(72.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = stringResource(R.string.play),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Menu Button - Smaller secondary button
            Surface(
                onClick = {
                    menuState.show {
                        AutoPlaylistMenu(
                            downloadState = downloadState,
                            onQueue = {
                                playerConnection.addToQueue(
                                    songs.map { it.toMediaItem() },
                                )
                            },
                            onDownload = {
                                when (downloadState) {
                                    Download.STATE_COMPLETED -> {
                                        onShowRemoveDownloadDialog()
                                    }

                                    Download.STATE_DOWNLOADING -> {
                                        songs.forEach { song ->
                                            DownloadService.sendRemoveDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                song.song.id,
                                                false,
                                            )
                                        }
                                    }

                                    else -> {
                                        songs.forEach { song ->
                                            val downloadRequest =
                                                DownloadRequest
                                                    .Builder(song.song.id, song.song.id.toUri())
                                                    .setCustomCacheKey(song.song.id)
                                                    .setData(song.song.title.toByteArray())
                                                    .build()
                                            DownloadService.sendAddDownload(
                                                context,
                                                ExoDownloadService::class.java,
                                                downloadRequest,
                                                false,
                                            )
                                        }
                                    }
                                }
                            },
                            onDismiss = { menuState.dismiss() },
                            songs = songs,
                            playlistName = name,
                        )
                    }
                },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

enum class PlaylistType {
    LIKE,
    DOWNLOAD,
    UPLOADED,
    OTHER,
}
