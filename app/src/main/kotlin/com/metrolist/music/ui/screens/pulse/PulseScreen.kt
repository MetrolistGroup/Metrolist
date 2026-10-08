package com.metrolist.music.ui.screens.pulse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.viewmodels.PulseTrack
import com.metrolist.music.viewmodels.PulseViewModel
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PulseScreen() {
    val viewModel: PulseViewModel = hiltViewModel()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val playerConnection = LocalPlayerConnection.current
    val currentSongState = playerConnection?.currentSong?.collectAsStateWithLifecycle()
    val currentSong = currentSongState?.value
    val effectivelyPlayingState = playerConnection?.isEffectivelyPlaying?.collectAsStateWithLifecycle()
    val isEffectivelyPlaying = effectivelyPlayingState?.value == true
    val queueWindowsState =
        playerConnection?.queueWindows?.collectAsStateWithLifecycle(initialValue = emptyList())
    val queueWindows = queueWindowsState?.value.orEmpty()
    val currentWindowIndexState =
        playerConnection?.currentWindowIndex?.collectAsStateWithLifecycle(initialValue = -1)
    val currentWindowIndex = currentWindowIndexState?.value ?: -1
    val currentPlaybackId =
        queueWindows.getOrNull(currentWindowIndex)?.mediaItem?.mediaId ?: currentSong?.id

    LaunchedEffect(currentSong?.id) {
        viewModel.loadInitial(currentSong?.id)

        val shouldSeedRadio =
            currentSong != null &&
                runCatching {
                    val player = playerConnection?.player ?: return@runCatching false
                    player.currentMediaItemIndex < 0 ||
                        player.currentMediaItemIndex + 1 >= player.mediaItemCount
                }.getOrDefault(true)

        if (shouldSeedRadio) {
            playerConnection?.startRadioSeamlessly()
        }
    }

    when {
        isLoading && tracks.isEmpty() && queueWindows.isEmpty() -> PulseLoading()
        tracks.isEmpty() && queueWindows.isEmpty() -> PulseEmpty(onRetry = {
            viewModel.refresh(currentSong?.id)
            playerConnection?.startRadioSeamlessly()
        })
        else -> {
            val mixTracks =
                remember(queueWindows, currentPlaybackId) {
                    queueWindows
                        .mapNotNull { window ->
                            window.mediaItem.metadata?.let { metadata ->
                                PulseTrack(
                                    song = metadata.toYTItem(),
                                    reason =
                                        if (metadata.id == currentPlaybackId) {
                                            "Now playing"
                                        } else {
                                            "From your current mix"
                                        },
                                )
                            }
                        }
                        .distinctBy { it.song.id }
                }

            val displayTracks =
                if (mixTracks.isNotEmpty()) {
                    mixTracks
                } else {
                    tracks
                }

            if (displayTracks.isEmpty()) {
                PulseLoading()
            } else {
                val initialPage =
                    remember(displayTracks, currentPlaybackId) {
                        displayTracks.indexOfFirst { it.song.id == currentPlaybackId }.coerceAtLeast(0)
                    }
                val pagerState =
                    rememberPagerState(
                        initialPage = initialPage,
                        pageCount = { displayTracks.size },
                    )
                var hasSwiped by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(pagerState.currentPage) {
                    if (pagerState.currentPage > 0) {
                        hasSwiped = true
                    }
                }

                LaunchedEffect(pagerState, currentPlaybackId, displayTracks) {
                    val currentId = currentPlaybackId ?: return@LaunchedEffect
                    val targetPage = displayTracks.indexOfFirst { it.song.id == currentId }
                    if (targetPage >= 0 && targetPage != pagerState.currentPage) {
                        pagerState.animateScrollToPage(targetPage)
                    }
                }

                val latestTracks = rememberUpdatedState(displayTracks)
                val latestCurrentSong = rememberUpdatedState(currentSong)

                LaunchedEffect(pagerState, playerConnection, mixTracks) {
                    if (playerConnection == null) return@LaunchedEffect
                    snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
                        .filter { !it.first }
                        .collect { (_, page) ->
                            val currentTracks = latestTracks.value
                            val track = currentTracks.getOrNull(page) ?: return@collect
                            if (track.song.id != latestCurrentSong.value?.id) {
                                if (mixTracks.size >= 2) {
                                    playerConnection.playMediaItemById(track.song.id)
                                } else {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = track.song.title,
                                            items = listOf(track.song.toMediaMetadata().toMediaItem()),
                                        ),
                                    )
                                }
                            }

                            if (mixTracks.size < 2 && page >= currentTracks.size - 4) {
                                viewModel.loadMore(track.song.id)
                            }
                        }
                }

            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageOffset =
                    ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                        .coerceIn(-1f, 1f)
                val pageScale = 1f - (abs(pageOffset) * 0.045f)
                val pageAlpha = 1f - (abs(pageOffset) * 0.12f)

                PulsePage(
                    modifier =
                        Modifier.graphicsLayer {
                            scaleX = pageScale
                            scaleY = pageScale
                            alpha = pageAlpha
                        },
                    track = displayTracks[page],
                    isCurrentPage = page == pagerState.currentPage,
                    isPlaying = isEffectivelyPlaying && currentPlaybackId == displayTracks[page].song.id,
                    isLiked = currentSong?.id == displayTracks[page].song.id && currentSong?.song?.liked == true,
                    onLike = {
                        playerConnection?.toggleLike()
                    },
                    onAddToQueue = {
                        val item = displayTracks[page].song.toMediaMetadata().toMediaItem()
                        playerConnection?.addToQueue(item)
                    },
                    onTogglePlayPause = {
                        playerConnection?.togglePlayPause()
                    },
                )
            }

                if (!hasSwiped) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Text(
                            text = "↑  Swipe",
                            modifier =
                                Modifier.padding(
                                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                }

                if (isLoadingMore) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .padding(WindowInsets.statusBars.asPaddingValues())
                                .size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PulsePage(
    track: PulseTrack,
    modifier: Modifier = Modifier,
    isCurrentPage: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    onLike: () -> Unit,
    onAddToQueue: () -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        val highQualityThumbnail =
            remember(track.song.thumbnail) {
                track.song.toMediaMetadata().thumbnailUrl?.takeIf { it.isNotBlank() }
                    ?: track.song.thumbnail
            }

        AsyncImage(
            model = highQualityThumbnail,
            contentDescription = track.song.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            0.72f to Color.Black.copy(alpha = 0.15f),
                            1f to Color.Black.copy(alpha = 0.92f),
                        ),
                    ),
        )

        if (isCurrentPage) {
            IconButton(
                onClick = onTogglePlayPause,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.28f))
                        .graphicsLayer {
                            alpha = if (isPlaying) 0f else 1f
                        },
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                    contentDescription =
                        stringResource(
                            if (isPlaying) R.string.player_pause else R.string.play,
                        ),
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.pulse).uppercase(),
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(start = 18.dp, top = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.88f),
        )

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = 18.dp,
                        end = 82.dp,
                        top = 20.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 82.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = track.reason,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.72f),
            )
            Text(
                text = track.song.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = track.song.artists.joinToString(", ") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
            )
            Text(
                text = stringResource(R.string.pulse_swipe_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 12.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PulseAction(
                icon = if (isLiked) R.drawable.favorite else R.drawable.favorite_border,
                contentDescription = stringResource(if (isLiked) R.string.action_remove_like else R.string.action_like),
                onClick = onLike,
            )
            PulseAction(
                icon = R.drawable.add,
                contentDescription = stringResource(R.string.add_to_queue),
                onClick = onAddToQueue,
            )
        }
    }
}

@Composable
private fun PulseAction(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.34f)),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Color.White,
        )
    }
}

@Composable
private fun PulseLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PulseEmpty(onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.pulse_filled),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = stringResource(R.string.pulse_empty_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.pulse_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}
