/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEachReversed
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.*
import com.metrolist.music.R
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.YouTubePlaylistQueue
import com.metrolist.music.ui.component.*
import com.metrolist.music.ui.menu.*
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.viewmodels.OnlinePlaylistViewModel
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnlinePlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: OnlinePlaylistViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val playlist by viewModel.playlist.collectAsState()
    val songs by viewModel.playlistSongs.collectAsState()
    val dbPlaylist by viewModel.dbPlaylist.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val error by viewModel.error.collectAsState()

    val hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)

    val lazyListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }

    val filteredSongs = remember(songs, query) {
        if (query.text.isEmpty()) songs.mapIndexed { i, s -> i to s }
        else songs.mapIndexed { i, s -> i to s }.filter {
            it.second.title.contains(query.text, true) ||
                it.second.artists.fastAny { a -> a.name.contains(query.text, true) }
        }
    }

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) { if (isSearching) focusRequester.requestFocus() }

    LaunchedEffect(filteredSongs) {
        selection.fastForEachReversed { songId ->
            if (filteredSongs.find { it.second.id == songId } == null) {
                selection.remove(songId)
            }
        }
    }

    if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    } else if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues(),
        ) {
            if (playlist == null || songs.isEmpty()) {
                if (isLoading) {
                    item(key = "loading_placeholder") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ContainedLoadingIndicator()
                        }
                    }
                }
            } else {
                playlist?.let { playlist ->
                    if (!isSearching) {
                        item(key = "playlist_header") {
                            OnlinePlaylistHeader(
                                playlist = playlist,
                                songs = songs,
                                dbPlaylist = dbPlaylist,
                                navController = navController,
                                coroutineScope = coroutineScope,
                                continuation = viewModel.continuation,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }

                    itemsIndexed(filteredSongs) { index, (_, songItem) ->
                        val onCheckedChange: (Boolean) -> Unit = {
                            if (it) {
                                selection.add(songItem.id)
                            } else {
                                selection.remove(songItem.id)
                            }
                        }

                        YouTubeListItem(
                            item = songItem,
                            isActive = mediaMetadata?.id == songItem.id,
                            isPlaying = isPlaying,
                            isSelected = inSelectMode && songItem.id in selection,
                            modifier = Modifier
                                .combinedClickable(
                                    enabled = !hideExplicit || !songItem.explicit,
                                    onClick = {
                                        if (inSelectMode) {
                                            onCheckedChange(songItem.id !in selection)
                                        } else if (songItem.id == mediaMetadata?.id) {
                                            playerConnection.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                YouTubePlaylistQueue(
                                                    playlistId = playlist.id,
                                                    playlistTitle = playlist.title,
                                                    initialSongs = filteredSongs.map { it.second },
                                                    initialContinuation = viewModel.continuation,
                                                    startIndex = index
                                                )
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        if (!inSelectMode) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            inSelectMode = true
                                            onCheckedChange(true)
                                        }
                                    }
                                )
                                .animateItem(),
                            trailingContent = {
                                if (inSelectMode) {
                                    Checkbox(
                                        checked = songItem.id in selection,
                                        onCheckedChange = onCheckedChange
                                    )
                                } else {
                                    IconButton(onClick = {
                                        menuState.show {
                                            YouTubeSongMenu(songItem, navController, menuState::dismiss)
                                        }
                                    }) {
                                        Icon(painterResource(R.drawable.more_vert), null)
                                    }
                                }
                            }
                        )
                    }

                    if (isLoadingMore) {
                        item(key = "loading_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                ContainedLoadingIndicator()
                            }
                        }
                    }
                }
            }
        }

        TopAppBar(
            title = {
                if (inSelectMode) {
                    Text(
                        text = pluralStringResource(R.plurals.n_song, selection.size, selection.size),
                        style = MaterialTheme.typography.titleLarge
                    )
                } else if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                } else if (lazyListState.firstVisibleItemIndex > 0) {
                    Text(playlist?.title ?: "")
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (isSearching) {
                            isSearching = false
                            query = TextFieldValue()
                        } else if (inSelectMode) {
                            onExitSelectionMode()
                        } else {
                            navController.navigateUp()
                        }
                    },
                    onLongClick = {
                        if (!isSearching && !inSelectMode) {
                            navController.backToMain()
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(
                            if (inSelectMode) R.drawable.close else R.drawable.arrow_back
                        ),
                        contentDescription = null
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
                                selection.addAll(filteredSongs.map { it.second.id })
                            }
                        }
                    )
                    IconButton(
                        enabled = selection.isNotEmpty(),
                        onClick = {
                            menuState.show {
                                YouTubeSelectionSongMenu(
                                    songSelection = filteredSongs.filter { it.second.id in selection }
                                        .map { it.second },
                                    onDismiss = menuState::dismiss,
                                    clearAction = onExitSelectionMode
                                )
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert),
                            contentDescription = null
                        )
                    }
                } else if (!isSearching) {
                    IconButton(
                        onClick = { isSearching = true }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.search),
                            contentDescription = null
                        )
                    }
                }
            }
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun OnlinePlaylistHeader(
    playlist: PlaylistItem,
    songs: List<SongItem>,
    dbPlaylist: Playlist?,
    navController: NavController,
    coroutineScope: CoroutineScope,
    continuation: String?,
    modifier: Modifier = Modifier
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val syncUtils = LocalSyncUtils.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .size(240.dp)
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(3.dp),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ),
            shape = RoundedCornerShape(3.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(playlist.thumbnail).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = playlist.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Metadata - Song Count • Duration
        val totalDuration = songs.sumOf { it.duration ?: 0 }
        Text(
            text = buildString {
                append(pluralStringResource(R.plurals.n_song, songs.size, songs.size))
                if (totalDuration > 0) {
                    append(" • ")
                    append(makeTimeString(totalDuration * 1000L))
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Like Button - Smaller secondary button
            Surface(
                onClick = {
                    if (dbPlaylist != null) {
                        database.transaction {
                            val currentPlaylist = dbPlaylist.playlist
                            update(currentPlaylist, playlist)
                            update(currentPlaylist.toggleLike())
                        }
                    } else {
                        database.transaction {
                            val playlistEntity = PlaylistEntity(
                                name = playlist.title,
                                browseId = playlist.id,
                                thumbnailUrl = playlist.thumbnail,
                                isEditable = playlist.isEditable,
                                remoteSongCount = playlist.songCountText?.let {
                                    Regex("""\d+""").find(it)?.value?.toIntOrNull()
                                },
                                playEndpointParams = playlist.playEndpoint?.params,
                                shuffleEndpointParams = playlist.shuffleEndpoint?.params,
                                radioEndpointParams = playlist.radioEndpoint?.params
                            ).toggleLike()
                            insert(playlistEntity)
                            coroutineScope.launch(Dispatchers.IO) {
                                songs.map { it.toMediaMetadata() }
                                    .onEach(::insert)
                                    .mapIndexed { index, song ->
                                        PlaylistSongMap(
                                            songId = song.id,
                                            playlistId = playlistEntity.id,
                                            position = index,
                                            setVideoId = song.setVideoId
                                        )
                                    }
                                    .forEach(::insert)
                            }
                        }
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (dbPlaylist?.playlist?.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border
                        ),
                        contentDescription = null,
                        tint = if (dbPlaylist?.playlist?.bookmarkedAt != null)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Play Button - Larger primary circular button
            Surface(
                onClick = {
                    if (songs.isNotEmpty()) {
                        playerConnection.playQueue(
                            YouTubePlaylistQueue(
                                playlistId = playlist.id,
                                playlistTitle = playlist.title,
                                initialSongs = songs,
                                initialContinuation = continuation
                            )
                        )
                    }
                },
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(72.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.play),
                        contentDescription = stringResource(R.string.play),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Menu Button - Smaller secondary button
            Surface(
                onClick = {
                    menuState.show {
                        YouTubePlaylistMenu(
                            playlist = playlist,
                            songs = songs,
                            coroutineScope = coroutineScope,
                            onDismiss = menuState::dismiss
                        )
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
