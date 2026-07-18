/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.R
import com.metrolist.music.constants.CONTENT_TYPE_HEADER
import com.metrolist.music.constants.CONTENT_TYPE_SONG
import com.metrolist.music.constants.PodcastFilter
import com.metrolist.music.constants.PodcastFilterKey
import com.metrolist.music.constants.SongSortDescendingKey
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.constants.SongSortTypeKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.HideOnScrollFAB
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.LibraryPodcastsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryPodcastsScreen(
    navController: NavController,
    onDeselect: () -> Unit,
    viewModel: LibraryPodcastsViewModel = hiltViewModel(),
) {
    val downloadedEpisodesStr = stringResource(R.string.downloaded_episodes)
    val database = LocalDatabase.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    var podcastFilter by rememberEnumPreference(PodcastFilterKey, PodcastFilter.EPISODES)

    val (sortType, onSortTypeChange) =
        rememberEnumPreference(
            SongSortTypeKey,
            SongSortType.CREATE_DATE,
        )
    val (sortDescending, onSortDescendingChange) = rememberPreference(SongSortDescendingKey, true)

    val subscribedChannels by viewModel.subscribedChannels.collectAsStateWithLifecycle()
    val downloadedEpisodes by viewModel.downloadedEpisodes.collectAsStateWithLifecycle()
    val savedEpisodes by viewModel.savedEpisodes.collectAsStateWithLifecycle()
    val sePlaylist by viewModel.sePlaylist.collectAsStateWithLifecycle()
    val podcastChannels by viewModel.podcastChannels.collectAsStateWithLifecycle()
    val rdpnPlaylist by viewModel.rdpnPlaylist.collectAsStateWithLifecycle()

    // Refresh channels when screen becomes visible (ON_RESUME)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshChannels()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val lazyListState = rememberLazyListState()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsStateWithLifecycle()

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazyListState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val pullToRefreshState = rememberPullToRefreshState()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pullToRefresh(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        if (!isRefreshing) {
                            isRefreshing = true
                            coroutineScope.launch {
                                viewModel.refreshAll()
                                isRefreshing = false
                            }
                        }
                    },
                ),
    ) {
        // Chip row header — same pattern as LibrarySongsScreen
        val chipsHeader = @Composable {
            Row {
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    label = { Text(stringResource(R.string.filter_podcasts)) },
                    selected = true,
                    colors =
                        FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    onClick = onDeselect,
                    shape = RoundedCornerShape(16.dp),
                    border = null,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.close_chip),
                        )
                    },
                )
                ChipsRow(
                    chips =
                        listOf(
                            PodcastFilter.EPISODES to stringResource(R.string.filter_episodes),
                            PodcastFilter.CHANNELS to stringResource(R.string.filter_channels),
                            PodcastFilter.DOWNLOADED to stringResource(R.string.filter_downloaded),
                        ),
                    currentValue = podcastFilter,
                    onValueUpdate = { podcastFilter = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        when (podcastFilter) {
            // ── EPISODES FOR LATER tab ────────────────────────────────────
            PodcastFilter.EPISODES -> {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                ) {
                    item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                        chipsHeader()
                    }

                    // RDPN "New Episodes" auto-playlist card
                    item(key = "rdpn_playlist", contentType = CONTENT_TYPE_HEADER) {
                        AutoPlaylistCard(
                            title = stringResource(R.string.new_episodes),
                            thumbnailUrl = rdpnPlaylist?.thumbnail,
                            episodeCount = rdpnPlaylist?.songCountText,
                            onClick = { navController.navigate("online_playlist/RDPN") },
                        )
                    }

                    // Episodes for Later - card/folder (works both logged in and out)
                    item(key = "episodes_for_later", contentType = CONTENT_TYPE_HEADER) {
                        AutoPlaylistCard(
                            title = stringResource(R.string.episodes_for_later),
                            thumbnailUrl = sePlaylist?.thumbnail ?: savedEpisodes.firstOrNull()?.song?.thumbnailUrl,
                            episodeCount =
                                sePlaylist?.songCountText ?: if (savedEpisodes.isNotEmpty()) {
                                    pluralStringResource(R.plurals.n_episode, savedEpisodes.size, savedEpisodes.size)
                                } else {
                                    null
                                },
                            onClick = { navController.navigate("online_playlist/SE") },
                        )
                    }

                    // Saved podcast shows (episode playlists) from YT Music library
                    itemsIndexed(
                        items = subscribedChannels,
                        key = { _, item -> item.id },
                        contentType = { _, _ -> CONTENT_TYPE_SONG },
                    ) { _, podcast ->
                        PodcastEpisodePlaylistItem(
                            podcast = podcast,
                            onClick = { navController.navigate("online_podcast/${podcast.id}") },
                            onMenuClick = {
                                menuState.show {
                                    PodcastEpisodePlaylistMenu(
                                        podcast = podcast,
                                        database = database,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .animateItem(),
                        )
                    }
                }
            }

            // ── CHANNELS tab — podcast host artist pages from YT Music ───
            PodcastFilter.CHANNELS -> {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                ) {
                    item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                        chipsHeader()
                    }

                    item(key = "channels_count", contentType = CONTENT_TYPE_HEADER) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text =
                                    pluralStringResource(
                                        R.plurals.n_channel,
                                        podcastChannels.size,
                                        podcastChannels.size,
                                    ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }

                    itemsIndexed(
                        items = podcastChannels,
                        key = { _, item -> item.id },
                        contentType = { _, _ -> CONTENT_TYPE_SONG },
                    ) { _, channel ->
                        PodcastArtistChannelItem(
                            thumbnailUrl = channel.thumbnail,
                            channelName = channel.title,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        navController.navigate("artist/${channel.id}")
                                    }.animateItem(),
                        )
                    }

                    if (podcastChannels.isEmpty()) {
                        item(key = "empty") {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.no_subscribed_channels),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ── DOWNLOADED tab ────────────────────────────────────────────
            PodcastFilter.DOWNLOADED -> {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                ) {
                    item(key = "filter", contentType = CONTENT_TYPE_HEADER) {
                        chipsHeader()
                    }

                    item(key = "sort_header", contentType = CONTENT_TYPE_HEADER) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            SortHeader(
                                sortType = sortType,
                                sortDescending = sortDescending,
                                onSortTypeChange = onSortTypeChange,
                                onSortDescendingChange = onSortDescendingChange,
                                sortTypeText = { st ->
                                    when (st) {
                                        SongSortType.CREATE_DATE -> R.string.sort_by_create_date
                                        SongSortType.NAME -> R.string.sort_by_name
                                        SongSortType.ARTIST -> R.string.sort_by_artist
                                        SongSortType.PLAY_TIME -> R.string.sort_by_play_time
                                    }
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text =
                                    pluralStringResource(
                                        R.plurals.n_episode,
                                        downloadedEpisodes.size,
                                        downloadedEpisodes.size,
                                    ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }

                    itemsIndexed(
                        items = downloadedEpisodes,
                        key = { index, item -> "${item.song.id}_$index" },
                        contentType = { _, _ -> CONTENT_TYPE_SONG },
                    ) { index, episode ->
                        // Always show channel name: use artists if available,
                        // else fall back to song.albumName (podcast show title stored during sync)
                        val channelName =
                            episode.artists
                                .joinToString { it.name }
                                .ifEmpty { episode.song.albumName ?: "" }
                        val subtitle =
                            joinByBullet(
                                channelName,
                                makeTimeString(episode.song.duration.toLong() * 1000L),
                            )
                        SongListItem(
                            song = episode,
                            showInLibraryIcon = false,
                            isActive = episode.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            showLikedIcon = false,
                            showDownloadIcon = true,
                            subtitleOverride = subtitle.ifEmpty { null },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            SongMenu(
                                                originalSong = episode,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert),
                                    contentDescription = stringResource(R.string.more_options),
                                )
                                }
                            },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (episode.id == mediaMetadata?.id) {
                                            playerConnection.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = downloadedEpisodesStr,
                                                    items = downloadedEpisodes.map { it.toMediaItem() },
                                                    startIndex = index,
                                                ),
                                            )
                                        }
                                    }.animateItem(),
                        )
                    }

                    if (downloadedEpisodes.isEmpty()) {
                        item(key = "empty") {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.no_downloaded_episodes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                HideOnScrollFAB(
                    visible = downloadedEpisodes.isNotEmpty(),
                    lazyListState = lazyListState,
                    icon = R.drawable.shuffle,
                    onClick = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = downloadedEpisodesStr,
                                items = downloadedEpisodes.shuffled().map { it.toMediaItem() },
                            ),
                        )
                    },
                )
            }
        }

        Indicator(
            isRefreshing = isRefreshing,
            state = pullToRefreshState,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
        )
    }
}

/** Auto-playlist card — mirrors YT Music design. Used for both SE and RDPN playlists. */
@Composable
private fun AutoPlaylistCard(
    title: String,
    thumbnailUrl: String?,
    episodeCount: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.queue_music),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    buildString {
                        append(stringResource(R.string.auto_playlist))
                        if (!episodeCount.isNullOrBlank()) {
                            append(" • ")
                            append(episodeCount)
                        }
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Episode playlist row shown in the Episodes tab — represents a saved podcast show */
@Composable
private fun PodcastEpisodePlaylistItem(
    podcast: PodcastEntity,
    onClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (podcast.thumbnailUrl != null) {
                AsyncImage(
                    model = podcast.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.queue_music),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = podcast.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!podcast.author.isNullOrBlank()) {
                Text(
                    text = podcast.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onMenuClick) {
            Icon(
                painter = painterResource(R.drawable.more_vert),
                contentDescription = stringResource(R.string.more_options),
            )
        }
    }
}

/** Menu shown when tapping the three-dot icon on an episode playlist */
@Composable
private fun PodcastEpisodePlaylistMenu(
    podcast: PodcastEntity,
    database: MusicDatabase,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val isPinned by database.speedDialDao.isPinned(podcast.id).collectAsStateWithLifecycle(initialValue = false)

    val playlistId = podcast.id.removePrefix("MPSP")
    val shareUrl = "https://music.youtube.com/playlist?list=$playlistId"

    Spacer(Modifier.height(12.dp))
    Material3MenuGroup(
        items =
            listOf(
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.remove_from_library)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.delete),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            // Update local database
                            database.query {
                                update(podcast.copy(bookmarkedAt = null))
                            }
                            // Sync with YouTube (unsave podcast only, don't unsubscribe channel)
                            syncUtils.savePodcast(podcast.id, false)
                        }
                        onDismiss()
                    },
                ),
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.share)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        val intent =
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareUrl)
                            }
                        context.startActivity(Intent.createChooser(intent, null))
                        onDismiss()
                    },
                ),
                Material3MenuItemData(
                    title = {
                        Text(
                            text =
                                stringResource(
                                    if (isPinned) {
                                        R.string.unpin_from_speed_dial
                                    } else {
                                        R.string.pin_to_speed_dial
                                    },
                                ),
                        )
                    },
                    icon = {
                        Icon(
                            painter =
                                painterResource(
                                    if (isPinned) R.drawable.remove else R.drawable.add,
                                ),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            if (isPinned) {
                                database.speedDialDao.delete(podcast.id)
                            } else {
                                database.speedDialDao.insert(
                                    SpeedDialItem(
                                        id = podcast.id,
                                        title = podcast.title,
                                        subtitle = podcast.author,
                                        thumbnailUrl = podcast.thumbnailUrl,
                                        type = "PLAYLIST",
                                    ),
                                )
                            }
                        }
                        onDismiss()
                    },
                ),
            ),
    )
    Spacer(Modifier.height(12.dp))
}

/** Artist/channel page item shown in the Channels tab */
@Composable
private fun PodcastArtistChannelItem(
    thumbnailUrl: String?,
    channelName: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(CircleShape),
        )

        Spacer(Modifier.width(12.dp))

        Text(
            text = channelName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
