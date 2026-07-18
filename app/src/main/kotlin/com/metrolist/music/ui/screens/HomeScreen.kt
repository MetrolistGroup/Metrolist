/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.utils.completed
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.constants.GridItemSize
import com.metrolist.music.constants.GridItemsSizeKey
import com.metrolist.music.constants.GridThumbnailHeight
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.RandomizeHomeOrderKey
import com.metrolist.music.constants.SmallGridThumbnailHeight
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Artist
import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.LocalAlbumRadio
import com.metrolist.music.playback.queues.YouTubeAlbumRadio
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.AlbumGridItem
import com.metrolist.music.ui.component.ArtistGridItem
import com.metrolist.music.ui.component.ChipsRow
import com.metrolist.music.ui.component.HideOnScrollFAB
import com.metrolist.music.ui.component.LocalBottomSheetPageState
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.NavigationTitle
import com.metrolist.music.ui.component.RandomizeGridItem
import com.metrolist.music.ui.component.SongGridItem
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.component.SpeedDialGridItem
import com.metrolist.music.ui.component.YouTubeGridItem
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.ui.component.shimmer.GridItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import com.metrolist.music.ui.menu.AlbumMenu
import com.metrolist.music.ui.menu.ArtistMenu
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.menu.YouTubeAlbumMenu
import com.metrolist.music.ui.menu.YouTubeArtistMenu
import com.metrolist.music.ui.menu.YouTubePlaylistMenu
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.ui.utils.SnapLayoutInfoProvider
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.CommunityPlaylistItem
import com.metrolist.music.viewmodels.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.random.Random

sealed class HomeSection(
    val id: String,
    val baseWeight: Int,
) {
    data object SpeedDial : HomeSection("speed_dial", 100)

    data object QuickPicks : HomeSection("quick_picks", 90)

    data object DailyDiscover : HomeSection("daily_discover", 80)

    data object KeepListening : HomeSection("keep_listening", 50)

    data object AccountPlaylists : HomeSection("account_playlists", 40)

    data object ForgottenFavorites : HomeSection("forgotten_favorites", 30)

    data object FromTheCommunity : HomeSection("from_the_community", 20)

    data class SimilarRecommendation(
        val index: Int,
    ) : HomeSection("similar_recommendation_$index", 10)

    data class HomePageSection(
        val index: Int,
    ) : HomeSection("home_page_section_$index", 10)

    data object MoodAndGenres : HomeSection("mood_and_genres", 5)
}

@Composable
fun CommunityPlaylistCard(
    item: CommunityPlaylistItem,
    onClick: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val listenTogetherManager = LocalListenTogetherManager.current
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    val containerColor =
        if (isDark) {
            MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }

    val dbPlaylist by database.playlistByBrowseId(item.playlist.id).collectAsStateWithLifecycle(initialValue = null)
    val isBookmarked = dbPlaylist?.playlist?.bookmarkedAt != null

    Card(
        modifier =
            modifier
                .width(320.dp)
                .height(420.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
            ),
        shape = RoundedCornerShape(28.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 2x2 Grid of thumbnails
                Box(
                    modifier =
                        Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(12.dp)),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1f)) {
                            AsyncImage(
                                model =
                                    item.songs
                                        .getOrNull(0)
                                        ?.thumbnail
                                        ?.resize(200, 200),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize(),
                            )
                            AsyncImage(
                                model =
                                    item.songs
                                        .getOrNull(1)
                                        ?.thumbnail
                                        ?.resize(200, 200),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize(),
                            )
                        }
                        Row(modifier = Modifier.weight(1f)) {
                            AsyncImage(
                                model =
                                    item.songs
                                        .getOrNull(2)
                                        ?.thumbnail
                                        ?.resize(200, 200),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize(),
                            )
                            AsyncImage(
                                model =
                                    item.songs
                                        .getOrNull(3)
                                        ?.thumbnail
                                        ?.resize(200, 200),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize(),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = item.playlist.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.playlist.author?.name ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
            ) {
                item.songs.take(3).forEach { song ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(onClick = { onSongClick(song) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(
                            model = song.thumbnail.resize(200, 200),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artists.joinToArtistString(" ${stringResource(R.string.and)} ") { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                IconButton(
                    onClick = {
                        if (!isListenTogetherGuest) {
                            item.playlist.playEndpoint?.let {
                                playerConnection?.playQueue(YouTubeQueue(it))
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_widget_play),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }

                IconButton(
                    onClick = {
                        if (!isListenTogetherGuest) {
                            item.playlist.radioEndpoint?.let {
                                playerConnection?.playQueue(YouTubeQueue(it))
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.radio),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }

                IconButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            if (dbPlaylist?.playlist == null) {
                                val playlistEntity =
                                    PlaylistEntity(
                                        name = item.playlist.title,
                                        browseId = item.playlist.id,
                                        thumbnailUrl = item.playlist.thumbnail,
                                        remoteSongCount =
                                            item.playlist.songCountText
                                                ?.split(" ")
                                                ?.firstOrNull()
                                                ?.toIntOrNull(),
                                        playEndpointParams = item.playlist.playEndpoint?.params,
                                        shuffleEndpointParams = item.playlist.shuffleEndpoint?.params,
                                        radioEndpointParams = item.playlist.radioEndpoint?.params,
                                    ).toggleLike()
                                val songMetadata =
                                    item.songs
                                        .ifEmpty {
                                            YouTube
                                                .playlist(item.playlist.id)
                                                .completed()
                                                .getOrNull()
                                                ?.songs
                                                .orEmpty()
                                        }.map { it.toMediaMetadata() }
                                if (songMetadata.isNotEmpty()) {
                                    database.withTransaction {
                                        insert(playlistEntity)
                                        songMetadata.onEach { insert(it) }
                                        val songIds = songMetadata.map { it.id to it.setVideoId }
                                        val createdPlaylist = database.playlistBlocking(playlistEntity.id)
                                        if (createdPlaylist != null) {
                                            addSongsToPlaylist(createdPlaylist, songIds)
                                        }
                                    }
                                }
                            } else {
                                database.transaction {
                                    val currentPlaylist = dbPlaylist!!.playlist
                                    update(currentPlaylist.toggleLike())
                                }
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(
                        painter = painterResource(if (isBookmarked) R.drawable.library_add_check else R.drawable.library_add),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DailyDiscoverCard(
    dailyDiscover: com.metrolist.music.viewmodels.DailyDiscoverItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = LocalNavController.current
    val database = LocalDatabase.current
    val playCount by database.getLifetimePlayCount(dailyDiscover.recommendation.id).collectAsStateWithLifecycle(initialValue = 0)
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current

    val song = dailyDiscover.recommendation as? SongItem
    val playsString = stringResource(R.string.plays)

    Card(
        modifier =
            modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(28.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (song != null) {
                            menuState.show {
                                YouTubeSongMenu(
                                    song = song,
                                    onDismiss = { menuState.dismiss() },
                                )
                            }
                        }
                    },
                ),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        shape = RoundedCornerShape(28.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(dailyDiscover.recommendation.thumbnail?.resize(1080, 1080))
                        .crossfade(true)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize(),
            )

            if (maxWidth > 200.dp) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                brush =
                                    Brush.verticalGradient(
                                        colors =
                                            listOf(
                                                Color.Black.copy(alpha = 0.3f),
                                                Color.Transparent,
                                                Color.Black.copy(alpha = 0.6f),
                                                Color.Black.copy(alpha = 0.9f),
                                            ),
                                    ),
                            ),
                )

                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = dailyDiscover.recommendation.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Text(
                            text =
                                buildString {
                                    append((dailyDiscover.recommendation as? SongItem)?.artists?.joinToArtistString(" ${stringResource(R.string.and)} ") { it.name } ?: "")
                                    if (playCount > 0) {
                                        append(" | $playCount $playsString")
                                    }
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }

                    val messages =
                        listOf(
                            R.string.daily_discover_sounds_like,
                            R.string.daily_discover_because_you_listen_to,
                            R.string.daily_discover_similar_to,
                            R.string.daily_discover_based_on,
                            R.string.daily_discover_for_fans_of,
                        )
                    val messageRes =
                        remember(dailyDiscover.seed.id) {
                            messages[kotlin.math.abs(dailyDiscover.seed.id.hashCode()) % messages.size]
                        }

                    Text(
                        text =
                            stringResource(
                                messageRes,
                                "${dailyDiscover.seed.title} • ${dailyDiscover.seed.artists.joinToArtistString(" ${stringResource(R.string.and)} ") { it.name }}",
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val listenTogetherManager = LocalListenTogetherManager.current
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val quickPicks by viewModel.quickPicks.collectAsStateWithLifecycle()
    val forgottenFavorites by viewModel.forgottenFavorites.collectAsStateWithLifecycle()
    val keepListening by viewModel.keepListening.collectAsStateWithLifecycle()
    val similarRecommendations by viewModel.similarRecommendations.collectAsStateWithLifecycle()
    val accountPlaylists by viewModel.accountPlaylists.collectAsStateWithLifecycle()
    val homePage by viewModel.homePage.collectAsStateWithLifecycle()
    val explorePage by viewModel.explorePage.collectAsStateWithLifecycle()
    val dailyDiscover by viewModel.dailyDiscover.collectAsStateWithLifecycle()
    val communityPlaylists by viewModel.communityPlaylists.collectAsStateWithLifecycle()

    val allLocalItems by viewModel.allLocalItems.collectAsStateWithLifecycle()
    val allYtItems by viewModel.allYtItems.collectAsStateWithLifecycle()
    val speedDialItems by viewModel.speedDialItems.collectAsStateWithLifecycle()
    val pinnedSpeedDialItems by viewModel.pinnedSpeedDialItems.collectAsStateWithLifecycle()
    val selectedChip by viewModel.selectedChip.collectAsStateWithLifecycle()

    // Official podcast API data
    val savedPodcastShows by viewModel.savedPodcastShows.collectAsStateWithLifecycle()
    val episodesForLater by viewModel.episodesForLater.collectAsStateWithLifecycle()

    val isLoading: Boolean by viewModel.isLoading.collectAsStateWithLifecycle()
    val isMoodAndGenresLoading = isLoading && explorePage?.moodAndGenres == null
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isRandomizing by viewModel.isRandomizing.collectAsStateWithLifecycle()
    val pullRefreshState = rememberPullToRefreshState()

    val quickPicksLazyGridState = rememberLazyGridState()
    val forgottenFavoritesLazyGridState = rememberLazyGridState()

    val accountName by viewModel.accountName.collectAsStateWithLifecycle()
    val accountImageUrl by viewModel.accountImageUrl.collectAsStateWithLifecycle()
    val innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
    val (randomizeHomeOrder) = rememberPreference(RandomizeHomeOrderKey, true)
    val autoRadioQueue by rememberPreference(AutoRadioQueueKey, defaultValue = true)

    LaunchedEffect(Unit) { viewModel.loadHomeData() }

    val shouldShowWrappedCard by viewModel.showWrappedCard.collectAsStateWithLifecycle()
    val wrappedState by viewModel.wrappedManager.state.collectAsStateWithLifecycle()
    val isWrappedDataReady = wrappedState.isDataReady

    val isLoggedIn =
        remember(innerTubeCookie) {
            "SAPISID" in parseCookieString(innerTubeCookie)
        }
    val url = if (isLoggedIn) accountImageUrl else null

    // Extract unique podcasts from episodes for "Podcast Channels" row
    // Cache the podcasts to prevent them from disappearing during refresh
    var cachedPodcasts by remember { mutableStateOf<List<PodcastItem>>(emptyList()) }

    val featuredPodcasts =
        remember(homePage, selectedChip) {
            if (selectedChip == null) {
                cachedPodcasts = emptyList()
                emptyList()
            } else {
                val newPodcasts =
                    homePage
                        ?.sections
                        ?.flatMap { it.items }
                        ?.filterIsInstance<EpisodeItem>()
                        ?.mapNotNull { episode ->
                            episode.podcast?.let { podcast ->
                                PodcastItem(
                                    id = podcast.id,
                                    title = podcast.name,
                                    author = episode.author,
                                    episodeCountText = null,
                                    thumbnail = episode.thumbnail,
                                    playEndpoint = null,
                                    shuffleEndpoint = null,
                                )
                            }
                        }?.distinctBy { it.id }
                        ?.shuffled()
                        ?.take(10)
                        ?: emptyList()

                // Only update cache if we got valid data; keep old data during refresh
                if (newPodcasts.isNotEmpty()) {
                    cachedPodcasts = newPodcasts
                }
                cachedPodcasts
            }
        }

    val scope = rememberCoroutineScope()
    // Track randomization job
    var randomizeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val lazylistState = rememberLazyListState()
    val gridItemSize by rememberEnumPreference(GridItemsSizeKey, GridItemSize.BIG)
    val currentGridHeight = if (gridItemSize == GridItemSize.BIG) GridThumbnailHeight else SmallGridThumbnailHeight
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsStateWithLifecycle()

    val wrappedDismissed by backStackEntry
        ?.savedStateHandle
        ?.getStateFlow("wrapped_seen", false)
        ?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }

    var randomSeed by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            randomSeed = System.currentTimeMillis()
        }
    }

    val foundInSettings = stringResource(R.string.found_in_settings_content)
    LaunchedEffect(wrappedDismissed) {
        if (wrappedDismissed) {
            viewModel.markWrappedAsSeen()
            scope.launch {
                snackbarHostState.showSnackbar(foundInSettings)
            }
            backStackEntry?.savedStateHandle?.set("wrapped_seen", false) // Reset the value
        }
    }

    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazylistState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow {
            lazylistState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
        }.collect { lastVisibleIndex ->
            val len = lazylistState.layoutInfo.totalItemsCount
            if (lastVisibleIndex != null && lastVisibleIndex >= len - 3) {
                viewModel.loadMoreYouTubeItems(homePage?.continuation)
            }
        }
    }

    if (selectedChip != null) {
        BackHandler {
            // if a chip is selected, go back to the normal homepage first
            viewModel.toggleChip(selectedChip)
        }
    }

    val localGridItem: @Composable (LocalItem) -> Unit = {
        when (it) {
            is Song -> {
                SongGridItem(
                    song = it,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (!isListenTogetherGuest) {
                                        if (it.id == mediaMetadata?.id) {
                                            playerConnection.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                if (autoRadioQueue) {
                                                    YouTubeQueue.radio(it.toMediaMetadata())
                                                } else {
                                                    ListQueue(
                                                        title = it.title,
                                                        items = listOf(it.toMediaItem())
                                                    )
                                                }
                                            )
                                        }
                                    }
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(
                                        HapticFeedbackType.LongPress,
                                    )
                                    menuState.show {
                                        SongMenu(
                                            originalSong = it,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ),
                    isActive = it.id == mediaMetadata?.id,
                    isPlaying = isPlaying,
                )
            }

            is Album -> {
                AlbumGridItem(
                    album = it,
                    isActive = it.id == mediaMetadata?.album?.id,
                    isPlaying = isPlaying,
                    coroutineScope = scope,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    navController.navigate("album/${it.id}")
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        AlbumMenu(
                                            originalAlbum = it,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ),
                )
            }

            is Artist -> {
                ArtistGridItem(
                    artist = it,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    navController.navigate("artist/${it.id}")
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(
                                        HapticFeedbackType.LongPress,
                                    )
                                    menuState.show {
                                        ArtistMenu(
                                            originalArtist = it,
                                            coroutineScope = scope,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                },
                            ),
                )
            }

            is Playlist -> {}
        }
    }

    val ytGridItem: @Composable (YTItem) -> Unit = { item ->
        YouTubeGridItem(
            item = item,
            isActive = item.id in listOf(mediaMetadata?.album?.id, mediaMetadata?.id),
            isPlaying = isPlaying,
            coroutineScope = scope,
            thumbnailRatio = 1f,
            modifier =
                Modifier
                    .combinedClickable(
                        onClick = {
                            when (item) {
                                is SongItem -> {
                                    if (!isListenTogetherGuest) {
                                        playerConnection.playQueue(
                                            if (autoRadioQueue) {
                                                YouTubeQueue(
                                                    item.endpoint ?: WatchEndpoint(
                                                        videoId = item.id,
                                                    ),
                                                    item.toMediaMetadata(),
                                                )
                                            } else {
                                                ListQueue(
                                                    title = item.title,
                                                    items = listOf(item.toMediaItem())
                                                )
                                            }
                                        )
                                    }
                                }

                                is AlbumItem -> {
                                    navController.navigate("album/${item.id}")
                                }

                                is ArtistItem -> {
                                    navController.navigate("artist/${item.id}")
                                }

                                is PlaylistItem -> {
                                    navController.navigate("online_playlist/${item.id}")
                                }

                                is PodcastItem -> {
                                    navController.navigate("online_podcast/${item.id}")
                                }

                                is EpisodeItem -> {
                                    if (!isListenTogetherGuest) {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = item.title,
                                                items = listOf(item.toMediaMetadata().toMediaItem()),
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuState.show {
                                when (item) {
                                    is SongItem -> {
                                        YouTubeSongMenu(
                                            song = item,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }

                                    is AlbumItem -> {
                                        YouTubeAlbumMenu(
                                            albumItem = item,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }

                                    is ArtistItem -> {
                                        YouTubeArtistMenu(
                                            artist = item,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }

                                    is PlaylistItem -> {
                                        YouTubePlaylistMenu(
                                            playlist = item,
                                            coroutineScope = scope,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }

                                    is PodcastItem -> {
                                        YouTubePlaylistMenu(
                                            playlist = item.asPlaylistItem(),
                                            coroutineScope = scope,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }

                                    is EpisodeItem -> {
                                        YouTubeSongMenu(
                                            song = item.asSongItem(),
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                }
                            }
                        },
                    ),
        )
    }

    val homeSections =
        remember(
            randomizeHomeOrder,
            randomSeed,
            selectedChip,
            speedDialItems,
            quickPicks,
            dailyDiscover,
            keepListening,
            accountPlaylists,
            forgottenFavorites,
            communityPlaylists,
            similarRecommendations,
            homePage?.sections,
            explorePage?.moodAndGenres,
        ) {
            val list = mutableListOf<HomeSection>()
            val chipActive = selectedChip != null

            if (!chipActive && speedDialItems.isNotEmpty()) list.add(HomeSection.SpeedDial)
            if (!chipActive && quickPicks?.isNotEmpty() == true) list.add(HomeSection.QuickPicks)
            if (!chipActive && communityPlaylists?.isNotEmpty() == true) list.add(HomeSection.FromTheCommunity)
            if (!chipActive && dailyDiscover?.isNotEmpty() == true) list.add(HomeSection.DailyDiscover)
            if (!chipActive && keepListening?.isNotEmpty() == true) list.add(HomeSection.KeepListening)
            if (!chipActive && accountPlaylists?.isNotEmpty() == true) list.add(HomeSection.AccountPlaylists)
            if (!chipActive && forgottenFavorites?.isNotEmpty() == true) list.add(HomeSection.ForgottenFavorites)

            if (!chipActive) {
                similarRecommendations?.indices?.forEach { i ->
                    list.add(HomeSection.SimilarRecommendation(i))
                }
            }

            homePage?.sections?.indices?.forEach { i ->
                list.add(HomeSection.HomePageSection(i))
            }

            if (explorePage?.moodAndGenres != null) list.add(HomeSection.MoodAndGenres)

            if (randomizeHomeOrder) {
                list.sortedByDescending { section ->
                    // Use a stable seed for each section based on the session seed + section ID hash
                    // This ensures the weight for a specific section remains constant during a session (until refresh)
                    // even if other sections appear/disappear, preventing jumping.
                    val sectionRandom = Random(randomSeed + section.id.hashCode())

                    // Flatten the base values to allow for more overlap and variation
                    // All "main" sections start closer together
                    val base =
                        when (section) {
                            HomeSection.SpeedDial,
                            HomeSection.QuickPicks,
                            HomeSection.DailyDiscover,
                            -> 500

                            // Top tier starts equal

                            HomeSection.KeepListening,
                            HomeSection.AccountPlaylists,
                            HomeSection.ForgottenFavorites,
                            HomeSection.FromTheCommunity,
                            -> 300

                            // Middle tier starts equal

                            else -> 100 // Bottom tier
                        }

                    val modifier =
                        when (section) {
                            // Top tier: High variance to allow shuffling among themselves
                            // Range: [500-200, 500+400] = [300, 900]
                            HomeSection.SpeedDial,
                            HomeSection.QuickPicks,
                            HomeSection.DailyDiscover,
                            -> sectionRandom.nextInt(-200, 400)

                            // Middle tier: Can jump up to challenge top tier, or drop lower
                            // Range: [300-100, 300+400] = [200, 700]
                            // This allows them to occasionally appear above a "bad roll" top tier item
                            HomeSection.KeepListening,
                            HomeSection.AccountPlaylists,
                            HomeSection.ForgottenFavorites,
                            HomeSection.FromTheCommunity,
                            -> sectionRandom.nextInt(-100, 400)

                            // Bottom tier: Standard variance
                            else -> sectionRandom.nextInt(-50, 50)
                        }
                    base + modifier
                }
            } else {
                val defaultOrder =
                    mapOf(
                        HomeSection.SpeedDial to 100,
                        HomeSection.QuickPicks to 90,
                        HomeSection.FromTheCommunity to 80,
                        HomeSection.DailyDiscover to 70,
                        HomeSection.KeepListening to 60,
                        HomeSection.AccountPlaylists to 50,
                        HomeSection.ForgottenFavorites to 40,
                        HomeSection.MoodAndGenres to 10,
                    )

                list.sortedByDescending { section ->
                    when (section) {
                        is HomeSection.SimilarRecommendation -> 30 - section.index
                        is HomeSection.HomePageSection -> 20 - section.index
                        else -> defaultOrder[section] ?: 0
                    }
                }
            }
        }

    LaunchedEffect(quickPicks) {
        quickPicksLazyGridState.scrollToItem(0)
    }

    LaunchedEffect(forgottenFavorites) {
        forgottenFavoritesLazyGridState.scrollToItem(0)
    }

    PullToRefreshBox(
        state = pullRefreshState,
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        indicator = {
            Indicator(
                isRefreshing = isRefreshing,
                state = pullRefreshState,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
            )
        },
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart,
        ) {
            val horizontalLazyGridItemWidthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
            val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor
            val quickPicksSnapLayoutInfoProvider =
                remember(quickPicksLazyGridState) {
                    SnapLayoutInfoProvider(
                        lazyGridState = quickPicksLazyGridState,
                        positionInLayout = { layoutSize, itemSize ->
                            (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                        },
                    )
                }
            val forgottenFavoritesSnapLayoutInfoProvider =
                remember(forgottenFavoritesLazyGridState) {
                    SnapLayoutInfoProvider(
                        lazyGridState = forgottenFavoritesLazyGridState,
                        positionInLayout = { layoutSize, itemSize ->
                            (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                        },
                    )
                }

            LazyColumn(
                state = lazylistState,
                contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            ) {
                item {
                    ChipsRow(
                        chips = homePage?.chips?.map { it to it.title } ?: emptyList(),
                        currentValue = selectedChip,
                        onValueUpdate = {
                            viewModel.toggleChip(it)
                        },
                    )
                }

                if (isLoading && homePage?.chips.isNullOrEmpty()) {
                    item(key = "chips_shimmer") {
                        ShimmerHost(showGradient = false) {
                            LazyRow(
                                contentPadding =
                                    WindowInsets.systemBars
                                        .only(WindowInsetsSides.Horizontal)
                                        .asPaddingValues(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                items(5) {
                                    TextPlaceholder(
                                        height = 30.dp,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.width(72.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Show podcast sections FIRST when podcast chip is selected (fixed at top)
                if (selectedChip?.title?.contains("Podcast", ignoreCase = true) == true) {
                    // Show "Your Shows" section from official API
                    if (savedPodcastShows.isNotEmpty()) {
                        item(key = "00_your_shows_title") {
                            NavigationTitle(
                                title = stringResource(R.string.your_shows),
                                onClick = {
                                    navController.navigate("youtube_browse/FEmusic_library_non_music_audio_list")
                                },
                            )
                        }

                        item(key = "00_your_shows_list") {
                            LazyRow(
                                contentPadding =
                                    WindowInsets.systemBars
                                        .only(WindowInsetsSides.Horizontal)
                                        .asPaddingValues(),
                            ) {
                                items(savedPodcastShows.distinctBy { it.id }, key = { "home_saved_podcast_${it.id}" }) { podcast ->
                                    ytGridItem(podcast)
                                }
                            }
                        }
                    }

                    // Show "Episodes for Later" section from official API
                    if (episodesForLater.isNotEmpty()) {
                        item(key = "00_episodes_for_later_title") {
                            NavigationTitle(
                                title = stringResource(R.string.episodes_for_later),
                                onClick = {
                                    navController.navigate("online_playlist/SE")
                                },
                            )
                        }

                        item(key = "00_episodes_for_later_list") {
                            LazyRow(
                                contentPadding =
                                    WindowInsets.systemBars
                                        .only(WindowInsetsSides.Horizontal)
                                        .asPaddingValues(),
                            ) {
                                items(episodesForLater.distinctBy { it.id }, key = { "home_episode_later_${it.id}" }) { episode ->
                                    ytGridItem(episode)
                                }
                            }
                        }
                    }

                    // Show Podcast Channels row if we have any (extracted from episodes)
                    // Only show if "Your Shows" from official API is empty (to avoid duplicates)
                    if (featuredPodcasts.isNotEmpty() && savedPodcastShows.isEmpty()) {
                        item(key = "0_podcast_channels_title") {
                            NavigationTitle(
                                title = stringResource(R.string.podcast_channels),
                            )
                        }

                        item(key = "0_podcast_channels_list") {
                            LazyRow(
                                contentPadding =
                                    WindowInsets.systemBars
                                        .only(WindowInsetsSides.Horizontal)
                                        .asPaddingValues(),
                            ) {
                                items(featuredPodcasts.distinctBy { it.id }, key = { "home_featured_podcast_${it.id}" }) { podcast ->
                                    ytGridItem(podcast)
                                }
                            }
                        }
                    }

                    // Add "Latest Episodes" header before episode sections (if we have any sections)
                    if (homeSections.filterIsInstance<HomeSection.HomePageSection>().isNotEmpty()) {
                        item(key = "0_latest_episodes_title") {
                            NavigationTitle(
                                title = stringResource(R.string.latest_episodes),
                            )
                        }
                    }

                    // Render the regular sections from the chip (episodes grouped by category)
                    // Use key prefix "1_" to ensure episodes sort after channels "0_"
                    // Skip sections that duplicate official API sections (Your Shows, Episodes for Later)
                    homeSections.filterIsInstance<HomeSection.HomePageSection>().forEach { section ->
                        val sectionData = homePage?.sections?.getOrNull(section.index)
                        // Skip if this section duplicates an official API section
                        val skipTitles = listOf("your shows", "episodes for later", "podcast channels", "new episodes")
                        if (sectionData?.title?.lowercase()?.let { title -> skipTitles.any { title.contains(it) } } == true) {
                            return@forEach
                        }
                        sectionData?.let {
                            item(key = "1_chip_section_title_${section.index}") {
                                NavigationTitle(
                                    title = sectionData.title,
                                    label = sectionData.label,
                                    thumbnail =
                                        sectionData.thumbnail?.let { thumbnailUrl ->
                                            {
                                                val shape =
                                                    if (sectionData.endpoint?.isArtistEndpoint == true) {
                                                        CircleShape
                                                    } else {
                                                        RoundedCornerShape(
                                                            ThumbnailCornerRadius,
                                                        )
                                                    }
                                                AsyncImage(
                                                    model = thumbnailUrl,
                                                    contentDescription = null,
                                                    modifier =
                                                        Modifier
                                                            .size(ListThumbnailSize)
                                                            .clip(shape),
                                                )
                                            }
                                        },
                                    onClick =
                                        sectionData.endpoint?.let { endpoint ->
                                            {
                                                when {
                                                    endpoint.browseId == "FEmusic_moods_and_genres" -> {
                                                        navController.navigate("mood_and_genres")
                                                    }

                                                    endpoint.params != null -> {
                                                        navController.navigate(
                                                            "youtube_browse/${endpoint.browseId}?params=${endpoint.params}",
                                                        )
                                                    }

                                                    else -> {
                                                        navController.navigate("browse/${endpoint.browseId}")
                                                    }
                                                }
                                            }
                                        },
                                )
                            }

                            item(key = "1_chip_section_list_${section.index}") {
                                LazyRow(
                                    contentPadding =
                                        WindowInsets.systemBars
                                            .only(WindowInsetsSides.Horizontal)
                                            .asPaddingValues(),
                                ) {
                                    items(sectionData.items.distinctBy { it.id }, key = { "home_chip_section_${it.id}" }) { item ->
                                        ytGridItem(item)
                                    }
                                }
                            }
                        }
                    }
                }

                if (selectedChip == null) {
                    item(key = "wrapped_card") {
                        AnimatedVisibility(visible = shouldShowWrappedCard) {
                            Card(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                colors =
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isWrappedDataReady) {
                                        val bbhFont =
                                            try {
                                                FontFamily(Font(R.font.bbh_bartle_regular))
                                            } catch (e: Exception) {
                                                FontFamily.Default
                                            }
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                        ) {
                                            Text(
                                                text = stringResource(R.string.wrapped_ready_title),
                                                style =
                                                    MaterialTheme.typography.headlineLarge.copy(
                                                        fontFamily = bbhFont,
                                                        textAlign = TextAlign.Center,
                                                    ),
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = stringResource(R.string.wrapped_ready_subtitle),
                                                style =
                                                    MaterialTheme.typography.bodyLarge.copy(
                                                        textAlign = TextAlign.Center,
                                                    ),
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Button(onClick = {
                                                navController.navigate("wrapped")
                                            }) {
                                                Text(stringResource(R.string.open))
                                            }
                                        }
                                    } else {
                                        ContainedLoadingIndicator()
                                    }
                                }
                            }
                        }
                    }
                }

                homeSections.forEach { section ->
                    when (section) {
                        HomeSection.SpeedDial -> {
                            speedDialItems.takeIf { it.isNotEmpty() }?.let { items ->
                                item(key = "speed_dial_title") {
                                    NavigationTitle(
                                        title = stringResource(R.string.speed_dial),
                                    )
                                }

                                item(key = "speed_dial_list") {
                                    val targetItemSize = 160.dp
                                    val availableWidth = maxWidth - 32.dp
                                    val columns = (availableWidth / targetItemSize).toInt().coerceAtLeast(3)
                                    val rows =
                                        if (columns >= 6) {
                                            1
                                        } else if (columns >= 4) {
                                            2
                                        } else {
                                            3
                                        }
                                    val itemsPerPage = columns * rows
                                    val itemWidth = availableWidth / columns

                                    val pagerState = rememberPagerState(pageCount = { (items.size + itemsPerPage - 1) / itemsPerPage })

                                    Column(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth(),
                                    ) {
                                        HorizontalPager(
                                            state = pagerState,
                                            contentPadding = PaddingValues(horizontal = 16.dp),
                                            pageSpacing = 16.dp,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(itemWidth * rows),
                                        ) { page ->
                                            val pageStartIndex = page * itemsPerPage
                                            val pageItems = items.drop(pageStartIndex).take(itemsPerPage)

                                            Column(modifier = Modifier.fillMaxSize()) {
                                                for (row in 0 until rows) {
                                                    Row(modifier = Modifier.fillMaxWidth()) {
                                                        for (col in 0 until columns) {
                                                            val itemIndex = row * columns + col

                                                            val isRandomizeSlot = (page == 0 && itemIndex == itemsPerPage - 1)

                                                            if (isRandomizeSlot) {
                                                                Box(
                                                                    modifier =
                                                                        Modifier
                                                                            .width(itemWidth)
                                                                            .height(itemWidth)
                                                                            .padding(4.dp),
                                                                ) {
                                                                    RandomizeGridItem(
                                                                        isLoading = isRandomizing,
                                                                        onClick = {
                                                                            if (isRandomizing) {
                                                                                randomizeJob?.cancel()
                                                                            } else if (!isListenTogetherGuest) {
                                                                                randomizeJob =
                                                                                    scope.launch {
                                                                                        val randomItem = viewModel.getRandomItem()
                                                                                        if (randomItem != null) {
                                                                                            when (randomItem) {
                                                                                                is SongItem -> {
                                                                                                    playerConnection.playQueue(
                                                                                                        if (autoRadioQueue) {
                                                                                                            YouTubeQueue(
                                                                                                                randomItem.endpoint
                                                                                                                    ?: WatchEndpoint(
                                                                                                                        videoId = randomItem.id,
                                                                                                                    ),
                                                                                                                randomItem.toMediaMetadata(),
                                                                                                            )
                                                                                                        } else {
                                                                                                            ListQueue(
                                                                                                                title = randomItem.title,
                                                                                                                items = listOf(randomItem.toMediaItem())
                                                                                                            )
                                                                                                        }
                                                                                                    )
                                                                                                }

                                                                                                is AlbumItem -> {
                                                                                                    navController.navigate(
                                                                                                        "album/${randomItem.id}",
                                                                                                    )
                                                                                                }

                                                                                                is ArtistItem -> {
                                                                                                    navController.navigate(
                                                                                                        "artist/${randomItem.id}",
                                                                                                    )
                                                                                                }

                                                                                                is PlaylistItem -> {
                                                                                                    navController.navigate(
                                                                                                        "online_playlist/${randomItem.id}",
                                                                                                    )
                                                                                                }

                                                                                                is PodcastItem -> {
                                                                                                    navController.navigate(
                                                                                                        "online_podcast/${randomItem.id}",
                                                                                                    )
                                                                                                }

                                                                                                is EpisodeItem -> {
                                                                                                    playerConnection.playQueue(
                                                                                                        ListQueue(
                                                                                                            title = randomItem.title,
                                                                                                            items =
                                                                                                                listOf(
                                                                                                                    randomItem
                                                                                                                        .toMediaMetadata()
                                                                                                                        .toMediaItem(),
                                                                                                                ),
                                                                                                        ),
                                                                                                    )
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    }
                                                                            }
                                                                        },
                                                                    )
                                                                }
                                                            } else if (itemIndex < pageItems.size) {
                                                                val item = pageItems[itemIndex]
                                                                val isPinned by database.speedDialDao
                                                                    .isPinned(
                                                                        item.id,
                                                                    ).collectAsStateWithLifecycle(initialValue = false)

                                                                Box(
                                                                    modifier =
                                                                        Modifier
                                                                            .width(itemWidth)
                                                                            .height(itemWidth)
                                                                            .padding(4.dp),
                                                                ) {
                                                                    SpeedDialGridItem(
                                                                        item = item,
                                                                        isPinned = isPinned,
                                                                        isActive =
                                                                            item.id in listOf(mediaMetadata?.album?.id, mediaMetadata?.id),
                                                                        isPlaying = isPlaying,
                                                                        modifier =
                                                                            Modifier
                                                                                .fillMaxSize()
                                                                                .combinedClickable(
                                                                                    onClick = {
                                                                                        when (item) {
                                                                                            is SongItem -> {
                                                                                                if (!isListenTogetherGuest) {
                                                                                                    playerConnection.playQueue(
                                                                                                        if (autoRadioQueue) {
                                                                                                            YouTubeQueue(
                                                                                                                item.endpoint
                                                                                                                    ?: WatchEndpoint(
                                                                                                                        videoId = item.id,
                                                                                                                    ),
                                                                                                                item.toMediaMetadata(),
                                                                                                            )
                                                                                                        } else {
                                                                                                            ListQueue(
                                                                                                                title = item.title,
                                                                                                                items = listOf(item.toMediaItem())
                                                                                                            )
                                                                                                        }
                                                                                                    )
                                                                                                }
                                                                                            }

                                                                                            is AlbumItem -> {
                                                                                                navController.navigate("album/${item.id}")
                                                                                            }

                                                                                            is ArtistItem -> {
                                                                                                navController.navigate("artist/${item.id}")
                                                                                            }

                                                                                            is PlaylistItem -> {
                                                                                                val rawType =
                                                                                                    pinnedSpeedDialItems
                                                                                                        .find {
                                                                                                            it.id ==
                                                                                                                item.id
                                                                                                        }?.type
                                                                                                if (rawType == "LOCAL_PLAYLIST") {
                                                                                                    navController.navigate(
                                                                                                        "local_playlist/${item.id}",
                                                                                                    )
                                                                                                } else {
                                                                                                    navController.navigate(
                                                                                                        "online_playlist/${item.id}",
                                                                                                    )
                                                                                                }
                                                                                            }

                                                                                            is PodcastItem -> {
                                                                                                navController.navigate(
                                                                                                    "online_podcast/${item.id}",
                                                                                                )
                                                                                            }

                                                                                            is EpisodeItem -> {
                                                                                                if (!isListenTogetherGuest) {
                                                                                                    playerConnection.playQueue(
                                                                                                        ListQueue(
                                                                                                            title = item.title,
                                                                                                            items =
                                                                                                                listOf(
                                                                                                                    item
                                                                                                                        .toMediaMetadata()
                                                                                                                        .toMediaItem(),
                                                                                                                ),
                                                                                                        ),
                                                                                                    )
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    },
                                                                                    onLongClick = {
                                                                                        haptic.performHapticFeedback(
                                                                                            HapticFeedbackType.LongPress,
                                                                                        )
                                                                                        menuState.show {
                                                                                            when (item) {
                                                                                                is SongItem -> {
                                                                                                    YouTubeSongMenu(
                                                                                                        song = item,
                                                                                                        onDismiss = menuState::dismiss,
                                                                                                    )
                                                                                                }

                                                                                                is AlbumItem -> {
                                                                                                    YouTubeAlbumMenu(
                                                                                                        albumItem = item,
                                                                                                        onDismiss = menuState::dismiss,
                                                                                                    )
                                                                                                }

                                                                                                is ArtistItem -> {
                                                                                                    YouTubeArtistMenu(
                                                                                                        artist = item,
                                                                                                        onDismiss = menuState::dismiss,
                                                                                                    )
                                                                                                }

                                                                                                is PlaylistItem -> {
                                                                                                    YouTubePlaylistMenu(
                                                                                                        playlist = item,
                                                                                                        coroutineScope = scope,
                                                                                                        onDismiss = menuState::dismiss,
                                                                                                    )
                                                                                                }

                                                                                                is PodcastItem -> {
                                                                                                    YouTubePlaylistMenu(
                                                                                                        playlist = item.asPlaylistItem(),
                                                                                                        coroutineScope = scope,
                                                                                                        onDismiss = menuState::dismiss,
                                                                                                    )
                                                                                                }

                                                                                                is EpisodeItem -> {
                                                                                                    YouTubeSongMenu(
                                                                                                        song = item.asSongItem(),
                                                                                                        onDismiss = menuState::dismiss,
                                                                                                    )
                                                                                                }
                                                                                            }
                                                                                        }
                                                                                    },
                                                                                ),
                                                                    )
                                                                }
                                                            } else {
                                                                Spacer(modifier = Modifier.width(itemWidth))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (pagerState.pageCount > 1) {
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .height(24.dp)
                                                        .fillMaxWidth(),
                                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                repeat(pagerState.pageCount) { iteration ->
                                                    val color =
                                                        if (pagerState.currentPage == iteration) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                        }
                                                    Box(
                                                        modifier =
                                                            Modifier
                                                                .padding(4.dp)
                                                                .clip(CircleShape)
                                                                .background(color)
                                                                .size(8.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HomeSection.QuickPicks -> {
                            quickPicks?.takeIf { it.isNotEmpty() }?.let { quickPicks ->
                                item(key = "quick_picks_title") {
                                    val quickPicksTitle = stringResource(R.string.quick_picks)
                                    NavigationTitle(
                                        title = quickPicksTitle,
                                        onPlayAllClick =
                                            if (!isListenTogetherGuest) {
                                                {
                                                    playerConnection.playQueue(
                                                        ListQueue(
                                                            title = quickPicksTitle,
                                                            items = quickPicks.distinctBy { it.id }.map { it.toMediaItem() },
                                                        ),
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                    )
                                }

                                item(key = "quick_picks_list") {
                                    LazyHorizontalGrid(
                                        state = quickPicksLazyGridState,
                                        rows = GridCells.Fixed(4),
                                        flingBehavior = rememberSnapFlingBehavior(quickPicksSnapLayoutInfoProvider),
                                        contentPadding =
                                            WindowInsets.systemBars
                                                .only(WindowInsetsSides.Horizontal)
                                                .asPaddingValues(),
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .height(ListItemHeight * 4),
                                        ) {
                                            items(
                                                items = quickPicks.distinctBy { it.id },
                                                key = { "home_quickpick_${it.id}" },
                                            ) { originalSong ->
                                            // fetch song from database to keep updated
                                            val song by database
                                                .song(originalSong.id)
                                                .collectAsStateWithLifecycle(initialValue = originalSong)

                                            SongListItem(
                                                song = song!!,
                                                showInLibraryIcon = true,
                                                isActive = song!!.id == mediaMetadata?.id,
                                                isPlaying = isPlaying,
                                                isSwipeable = false,
                                                trailingContent = {
                                                    IconButton(
                                                        onClick = {
                                                            menuState.show {
                                                                SongMenu(
                                                                    originalSong = song!!,
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
                                                },
                                                modifier =
                                                    Modifier
                                                        .width(horizontalLazyGridItemWidth)
                                                        .combinedClickable(
                                                            onClick = {
                                                                if (!isListenTogetherGuest) {
                                                                    if (song!!.id == mediaMetadata?.id) {
                                                                        playerConnection.togglePlayPause()
                                                                    } else {
                                                                        playerConnection.playQueue(
                                                                            if (autoRadioQueue) {
                                                                                YouTubeQueue.radio(
                                                                                    song!!.toMediaMetadata(),
                                                                                )
                                                                            } else {
                                                                                ListQueue(
                                                                                    title = song!!.title,
                                                                                    items = listOf(song!!.toMediaItem())
                                                                                )
                                                                            }
                                                                        )
                                                                    }
                                                                }
                                                            },
                                                            onLongClick = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                menuState.show {
                                                                    SongMenu(
                                                                        originalSong = song!!,
                                                                        onDismiss = menuState::dismiss,
                                                                    )
                                                                }
                                                            },
                                                        ),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HomeSection.FromTheCommunity -> {
                            communityPlaylists?.takeIf { it.isNotEmpty() }?.let { playlists ->
                                item(key = "community_playlists_title") {
                                    NavigationTitle(
                                        title = stringResource(R.string.from_the_community),
                                    )
                                }

                                item(key = "community_playlists_content") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        items(playlists) { item ->
                                            CommunityPlaylistCard(
                                                item = item,
                                                onClick = {
                                                    navController.navigate("online_playlist/${item.playlist.id.removePrefix("VL")}")
                                                },
                                                onSongClick = { song ->
                                                    if (!isListenTogetherGuest) {
                                                        playerConnection.playQueue(
                                                            YouTubeQueue(
                                                                song.endpoint ?: WatchEndpoint(videoId = song.id),
                                                                song.toMediaMetadata(),
                                                            ),
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HomeSection.DailyDiscover -> {
                            dailyDiscover?.takeIf { it.isNotEmpty() }?.let { discoverList ->
                                item(key = "daily_discover_title") {
                                    val title = stringResource(R.string.your_daily_discover)
                                    NavigationTitle(
                                        title = title,
                                        onPlayAllClick = {
                                            val queueItems =
                                                discoverList.mapNotNull {
                                                    (it.recommendation as? SongItem)?.toMediaMetadata()
                                                }

                                            if (queueItems.isNotEmpty()) {
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = title,
                                                        items = queueItems.map { it.toMediaItem() },
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                }

                                item(key = "daily_discover_content") {
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .height(340.dp)
                                                .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val carouselState = rememberCarouselState { discoverList.size }
                                        HorizontalMultiBrowseCarousel(
                                            state = carouselState,
                                            preferredItemWidth = 320.dp,
                                            itemSpacing = 16.dp,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(320.dp),
                                        ) { i ->
                                            val item = discoverList[i]
                                            DailyDiscoverCard(
                                                dailyDiscover = item,
                                                onClick = {
                                                    if (!isListenTogetherGuest) {
                                                        val song = item.recommendation as? SongItem
                                                        val mediaMetadata = song?.toMediaMetadata()
                                                        if (mediaMetadata != null) {
                                                            playerConnection.playQueue(
                                                                if (autoRadioQueue) {
                                                                    YouTubeQueue(
                                                                        song.endpoint ?: WatchEndpoint(videoId = song.id),
                                                                        mediaMetadata,
                                                                    )
                                                                } else {
                                                                    ListQueue(
                                                                        title = song.title,
                                                                        items = listOf(song.toMediaItem())
                                                                    )
                                                                }
                                                            )
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.maskClip(MaterialTheme.shapes.extraLarge),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HomeSection.KeepListening -> {
                            keepListening?.takeIf { it.isNotEmpty() }?.let { keepListening ->
                                item(key = "keep_listening_title") {
                                    NavigationTitle(
                                        title = stringResource(R.string.keep_listening),
                                    )
                                }

                                item(key = "keep_listening_list") {
                                    val rows = if (keepListening.size > 6) 2 else 1
                                    LazyHorizontalGrid(
                                        state = remember("keep_listening_grid") { LazyGridState() },
                                        rows = GridCells.Fixed(rows),
                                        contentPadding =
                                            WindowInsets.systemBars
                                                .only(WindowInsetsSides.Horizontal)
                                                .asPaddingValues(),
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .height(
                                                    (
                                                        currentGridHeight +
                                                            with(LocalDensity.current) {
                                                                MaterialTheme.typography.bodyLarge.lineHeight
                                                                    .toDp() * 2 +
                                                                    MaterialTheme.typography.bodyMedium.lineHeight
                                                                        .toDp() * 2
                                                            }
                                                    ) * rows,
                                                ),
                                    ) {
                                        items(keepListening.distinctBy { it.id }, key = { "home_keep_listening_${it.id}" }) {
                                            localGridItem(it)
                                        }
                                    }
                                }
                            }
                        }

                        HomeSection.AccountPlaylists -> {
                            accountPlaylists?.takeIf { it.isNotEmpty() }?.let { accountPlaylists ->
                                item(key = "account_playlists_title") {
                                    NavigationTitle(
                                        label = stringResource(R.string.mixes),
                                        title = accountName,
                                        thumbnail = {
                                            if (url != null) {
                                                AsyncImage(
                                                    model =
                                                        ImageRequest
                                                            .Builder(LocalContext.current)
                                                            .data(url)
                                                            .diskCachePolicy(CachePolicy.ENABLED)
                                                            .diskCacheKey(url)
                                                            .crossfade(false)
                                                            .build(),
                                                    placeholder = painterResource(id = R.drawable.person),
                                                    error = painterResource(id = R.drawable.person),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier =
                                                        Modifier
                                                            .size(ListThumbnailSize)
                                                            .clip(CircleShape),
                                                )
                                            } else {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.person),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(ListThumbnailSize),
                                                )
                                            }
                                        },
                                        onClick = {
                                            navController.navigate("account")
                                        },
                                    )
                                }

                                item(key = "account_playlists_list") {
                                    LazyRow(
                                        contentPadding =
                                            WindowInsets.systemBars
                                                .only(WindowInsetsSides.Horizontal)
                                                .asPaddingValues(),
                                    ) {
                                        items(
                                            items = accountPlaylists.distinctBy { it.id },
                                            key = { "home_account_playlist_${it.id}" },
                                        ) { item ->
                                            ytGridItem(item)
                                        }
                                    }
                                }
                            }
                        }

                        HomeSection.ForgottenFavorites -> {
                            forgottenFavorites?.takeIf { it.isNotEmpty() }?.let { forgottenFavorites ->
                                item(key = "forgotten_favorites_title") {
                                    val forgottenFavoritesTitle = stringResource(R.string.forgotten_favorites)
                                    NavigationTitle(
                                        title = forgottenFavoritesTitle,
                                        onPlayAllClick =
                                            if (!isListenTogetherGuest) {
                                                {
                                                    playerConnection.playQueue(
                                                        ListQueue(
                                                            title = forgottenFavoritesTitle,
                                                            items = forgottenFavorites.distinctBy { it.id }.map { it.toMediaItem() },
                                                        ),
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                    )
                                }

                                item(key = "forgotten_favorites_list") {
                                    // take min in case list size is less than 4
                                    val rows = min(4, forgottenFavorites.size)
                                    LazyHorizontalGrid(
                                        state = forgottenFavoritesLazyGridState,
                                        rows = GridCells.Fixed(rows),
                                        contentPadding =
                                            WindowInsets.systemBars
                                                .only(WindowInsetsSides.Horizontal)
                                                .asPaddingValues(),
                                        flingBehavior =
                                            rememberSnapFlingBehavior(
                                                forgottenFavoritesSnapLayoutInfoProvider,
                                            ),
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .height(ListItemHeight * rows),
                                        ) {
                                            items(
                                                items = forgottenFavorites.distinctBy { it.id },
                                                key = { "home_forgotten_${it.id}" },
                                            ) { originalSong ->
                                            val song by database
                                                .song(originalSong.id)
                                                .collectAsStateWithLifecycle(initialValue = originalSong)

                                            SongListItem(
                                                song = song!!,
                                                showInLibraryIcon = true,
                                                isActive = song!!.id == mediaMetadata?.id,
                                                isPlaying = isPlaying,
                                                isSwipeable = false,
                                                trailingContent = {
                                                    IconButton(
                                                        onClick = {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            menuState.show {
                                                                SongMenu(
                                                                    originalSong = song!!,
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
                                                },
                                                modifier =
                                                    Modifier
                                                        .width(horizontalLazyGridItemWidth)
                                                        .combinedClickable(
                                                            onClick = {
                                                                if (!isListenTogetherGuest) {
                                                                    if (song!!.id == mediaMetadata?.id) {
                                                                        playerConnection.togglePlayPause()
                                                                    } else {
                                                                        playerConnection.playQueue(
                                                                            if (autoRadioQueue) {
                                                                                YouTubeQueue.radio(
                                                                                    song!!.toMediaMetadata(),
                                                                                )
                                                                            } else {
                                                                                ListQueue(
                                                                                    title = song!!.title,
                                                                                    items = listOf(song!!.toMediaItem())
                                                                                )
                                                                            }
                                                                        )
                                                                    }
                                                                }
                                                            },
                                                            onLongClick = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                menuState.show {
                                                                    SongMenu(
                                                                        originalSong = song!!,
                                                                        onDismiss = menuState::dismiss,
                                                                    )
                                                                }
                                                            },
                                                        ),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is HomeSection.SimilarRecommendation -> {
                            val recommendation = similarRecommendations?.getOrNull(section.index)
                            recommendation?.let {
                                item(key = "similar_to_title_${section.index}") {
                                    NavigationTitle(
                                        label = stringResource(R.string.similar_to),
                                        title = recommendation.title.title,
                                        thumbnail =
                                            recommendation.title.thumbnailUrl?.let { thumbnailUrl ->
                                                {
                                                    val shape =
                                                        if (recommendation.title is Artist) {
                                                            CircleShape
                                                        } else {
                                                            RoundedCornerShape(
                                                                ThumbnailCornerRadius,
                                                            )
                                                        }
                                                    AsyncImage(
                                                        model = thumbnailUrl,
                                                        contentDescription = null,
                                                        modifier =
                                                            Modifier
                                                                .size(ListThumbnailSize)
                                                                .clip(shape),
                                                    )
                                                }
                                            },
                                        onClick = {
                                            when (recommendation.title) {
                                                is Song -> {
                                                    navController.navigate("album/${recommendation.title.album!!.id}")
                                                }

                                                is Album -> {
                                                    navController.navigate("album/${recommendation.title.id}")
                                                }

                                                is Artist -> {
                                                    navController.navigate("artist/${recommendation.title.id}")
                                                }

                                                is Playlist -> {}
                                            }
                                        },
                                    )
                                }

                                item(key = "similar_to_list_${section.index}") {
                                    LazyRow(
                                        contentPadding =
                                            WindowInsets.systemBars
                                                .only(WindowInsetsSides.Horizontal)
                                                .asPaddingValues(),
                                    ) {
                                        items(recommendation.items.distinctBy { it.id }, key = { "home_similar_${it.id}" }) { item ->
                                            ytGridItem(item)
                                        }
                                    }
                                }
                            }
                        }

                        is HomeSection.HomePageSection -> {
                            // Skip HomePageSection rendering when podcast chip is selected
                            // Podcast sections are handled separately with special UI
                            if (selectedChip?.title?.contains("Podcast", ignoreCase = true) == true) {
                                return@forEach
                            }
                            val sectionData = homePage?.sections?.getOrNull(section.index)
                            sectionData?.let {
                                // Check if section contains songs for Play All functionality
                                val sectionSongs = sectionData.items.filterIsInstance<SongItem>()
                                val hasPlayableSongs = sectionSongs.isNotEmpty()
                                // Check if this section contains ONLY songs (like Quick picks, Trending songs)
                                val isSongsOnlySection =
                                    sectionData.items.isNotEmpty() &&
                                        sectionData.items.all { it is SongItem }

                                item(key = "home_section_title_${section.index}") {
                                    NavigationTitle(
                                        title = sectionData.title,
                                        label = sectionData.label,
                                        thumbnail =
                                            sectionData.thumbnail?.let { thumbnailUrl ->
                                                {
                                                    val shape =
                                                        if (sectionData.endpoint?.isArtistEndpoint == true) {
                                                            CircleShape
                                                        } else {
                                                            RoundedCornerShape(
                                                                ThumbnailCornerRadius,
                                                            )
                                                        }
                                                    AsyncImage(
                                                        model = thumbnailUrl,
                                                        contentDescription = null,
                                                        modifier =
                                                            Modifier
                                                                .size(ListThumbnailSize)
                                                                .clip(shape),
                                                    )
                                                }
                                            },
                                        onClick =
                                            sectionData.endpoint?.let { endpoint ->
                                                {
                                                    when {
                                                        endpoint.browseId == "FEmusic_moods_and_genres" -> {
                                                            navController.navigate("mood_and_genres")
                                                        }

                                                        // Handle podcast-related browse endpoints
                                                        endpoint.browseId.startsWith("FEmusic_library_non_music_audio") ||
                                                            endpoint.browseId.startsWith("FEmusic_non_music_audio") -> {
                                                            navController.navigate("youtube_browse/${endpoint.browseId}")
                                                        }

                                                        endpoint.params != null -> {
                                                            navController.navigate(
                                                                "youtube_browse/${endpoint.browseId}?params=${endpoint.params}",
                                                            )
                                                        }

                                                        else -> {
                                                            navController.navigate("browse/${endpoint.browseId}")
                                                        }
                                                    }
                                                }
                                            },
                                        onPlayAllClick =
                                            if (hasPlayableSongs && !isListenTogetherGuest) {
                                                {
                                                    playerConnection.playQueue(
                                                        ListQueue(
                                                            title = sectionData.title,
                                                            items = sectionSongs.map { it.toMediaMetadata().toMediaItem() },
                                                        ),
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                    )
                                }

                                if (isSongsOnlySection) {
                                    // Render songs as a horizontal scrollable list (like Quick picks in YouTube Music)
                                    item(key = "home_section_list_${section.index}") {
                                        LazyHorizontalGrid(
                                            state = remember("section_${section.index}_grid") { LazyGridState() },
                                            rows = GridCells.Fixed(4),
                                            contentPadding =
                                                WindowInsets.systemBars
                                                    .only(WindowInsetsSides.Horizontal)
                                                    .asPaddingValues(),
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(ListItemHeight * 4),
                                        ) {
                                            items(
                                                items = sectionSongs.distinctBy { it.id },
                                                key = { "home_section_${section.index}_song_${it.id}" },
                                            ) { song ->
                                                YouTubeListItem(
                                                    item = song,
                                                    isActive = song.id == mediaMetadata?.id,
                                                    isPlaying = isPlaying,
                                                    isSwipeable = false,
                                                    trailingContent = {
                                                        IconButton(
                                                            onClick = {
                                                                menuState.show {
                                                                    YouTubeSongMenu(
                                                                        song = song,
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
                                                    },
                                                    modifier =
                                                        Modifier
                                                            .width(horizontalLazyGridItemWidth)
                                                            .combinedClickable(
                                                                onClick = {
                                                                    if (!isListenTogetherGuest) {
                                                                        playerConnection.playQueue(
                                                                            if (autoRadioQueue) {
                                                                                YouTubeQueue(
                                                                                    song.endpoint ?: WatchEndpoint(videoId = song.id),
                                                                                    song.toMediaMetadata(),
                                                                                )
                                                                            } else {
                                                                                ListQueue(
                                                                                    title = song.title,
                                                                                    items = listOf(song.toMediaItem())
                                                                                )
                                                                            }
                                                                        )
                                                                    }
                                                                },
                                                                onLongClick = {
                                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                    menuState.show {
                                                                        YouTubeSongMenu(
                                                                            song = song,
                                                                            onDismiss = menuState::dismiss,
                                                                        )
                                                                    }
                                                                },
                                                            ),
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // Render mixed content as horizontal grid items (albums, playlists, artists, etc.)
                                    item(key = "home_section_list_${section.index}") {
                                        LazyRow(
                                            contentPadding =
                                                WindowInsets.systemBars
                                                    .only(WindowInsetsSides.Horizontal)
                                                    .asPaddingValues(),
                                        ) {
                                            items(
                                                items = sectionData.items.distinctBy { it.id },
                                                key = { "home_section_${section.index}_item_${it.id}" },
                                            ) { item ->
                                                ytGridItem(item)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HomeSection.MoodAndGenres -> {
                            // Skip MoodAndGenres when podcast chip is selected
                            if (selectedChip?.title?.contains("Podcast", ignoreCase = true) == true) {
                                return@forEach
                            }
                            explorePage?.moodAndGenres?.let { moodAndGenres ->
                                item(key = "mood_and_genres_title") {
                                    NavigationTitle(
                                        title = stringResource(R.string.mood_and_genres),
                                        onClick = {
                                            navController.navigate("mood_and_genres")
                                        },
                                    )
                                }
                                item(key = "mood_and_genres_list") {
                                    LazyHorizontalGrid(
                                        rows = GridCells.Fixed(4),
                                        contentPadding = PaddingValues(6.dp),
                                        modifier =
                                            Modifier
                                                .height((MoodAndGenresButtonHeight + 12.dp) * 4 + 12.dp),
                                    ) {
                                        items(moodAndGenres.distinctBy { "${it.title}_${it.endpoint.browseId}_${it.endpoint.params}" }, key = { "${it.title}_${it.endpoint.browseId}_${it.endpoint.params}" }) {
                                            MoodAndGenresButton(
                                                title = it.title,
                                                onClick = {
                                                    navController.navigate(
                                                        "youtube_browse/${it.endpoint.browseId}?params=${it.endpoint.params}",
                                                    )
                                                },
                                                modifier =
                                                    Modifier
                                                        .padding(6.dp)
                                                        .width(180.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Only show shimmer during initial loading, not for pagination
                if (isLoading && homePage?.sections.isNullOrEmpty()) {
                    item(key = "loading_shimmer") {
                        ShimmerHost(
                        ) {
                            repeat(2) {
                                TextPlaceholder(
                                    height = 36.dp,
                                    modifier =
                                        Modifier
                                            .padding(12.dp)
                                            .width(250.dp),
                                )
                                LazyRow(
                                    contentPadding =
                                        WindowInsets.systemBars
                                            .only(WindowInsetsSides.Horizontal)
                                            .asPaddingValues(),
                                ) {
                                    items(4) {
                                        GridItemPlaceHolder()
                                    }
                                }
                            }

                            TextPlaceholder(
                                height = 36.dp,
                                modifier =
                                    Modifier
                                        .padding(vertical = 12.dp, horizontal = 12.dp)
                                        .width(250.dp),
                            )
                            repeat(4) {
                                Row {
                                    repeat(2) {
                                        TextPlaceholder(
                                            height = MoodAndGenresButtonHeight,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier =
                                                Modifier
                                                    .padding(horizontal = 12.dp)
                                                    .width(200.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HideOnScrollFAB(
                visible = allLocalItems.isNotEmpty() || allYtItems.isNotEmpty(),
                lazyListState = lazylistState,
                icon = R.drawable.shuffle,
                onClick = {
                    if (!isListenTogetherGuest) {
                        val local =
                            when {
                                allLocalItems.isNotEmpty() && allYtItems.isNotEmpty() -> Random.nextFloat() < 0.5
                                allLocalItems.isNotEmpty() -> true
                                else -> false
                            }
                        scope.launch(Dispatchers.Main) {
                            if (local) {
                                when (val luckyItem = allLocalItems.random()) {
                                    is Song -> {
                                        playerConnection.playQueue(YouTubeQueue.radio(luckyItem.toMediaMetadata()))
                                    }

                                    is Album -> {
                                        val albumWithSongs =
                                            withContext(Dispatchers.IO) {
                                                database.albumWithSongs(luckyItem.id).first()
                                            }
                                        albumWithSongs?.let {
                                            playerConnection.playQueue(LocalAlbumRadio(it))
                                        }
                                    }

                                    is Artist -> {}

                                    is Playlist -> {}
                                }
                            } else {
                                when (val luckyItem = allYtItems.random()) {
                                    is SongItem -> {
                                        playerConnection.playQueue(YouTubeQueue.radio(luckyItem.toMediaMetadata()))
                                    }

                                    is AlbumItem -> {
                                        playerConnection.playQueue(YouTubeAlbumRadio(luckyItem.playlistId))
                                    }

                                    is ArtistItem -> {
                                        luckyItem.radioEndpoint?.let {
                                            playerConnection.playQueue(YouTubeQueue(it))
                                        }
                                    }

                                    is PlaylistItem -> {
                                        luckyItem.playEndpoint?.let {
                                            playerConnection.playQueue(YouTubeQueue(it))
                                        }
                                    }

                                    is PodcastItem -> {
                                        luckyItem.playEndpoint?.let {
                                            playerConnection.playQueue(YouTubeQueue(it))
                                        }
                                    }

                                    is EpisodeItem -> {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = luckyItem.title,
                                                items = listOf(luckyItem.toMediaMetadata().toMediaItem()),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                onRecognitionClick = {
                    navController.navigate("recognition")
                },
            )
        }
    }
}
