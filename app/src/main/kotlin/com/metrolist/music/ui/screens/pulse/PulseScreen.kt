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
import androidx.compose.runtime.rememberSaveable
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

    LaunchedEffect(Unit) {
        viewModel.loadInitial()
    }

    when {
        isLoading && tracks.isEmpty() -> PulseLoading()
        tracks.isEmpty() -> PulseEmpty(onRetry = viewModel::refresh)
        else -> {
            val pagerState = rememberPagerState(pageCount = { tracks.size })
            var hasSwiped by rememberSaveable { mutableStateOf(false) }

            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage > 0) {
                    hasSwiped = true
                }
            }
            val latestTracks = rememberUpdatedState(tracks)

            LaunchedEffect(pagerState, playerConnection) {
                if (playerConnection == null) return@LaunchedEffect
                snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
                    .filter { !it.first }
                    .collect { (_, page) ->
                        val currentTracks = latestTracks.value
                        val track = currentTracks.getOrNull(page) ?: return@collect
                        playerConnection.playQueue(
                            ListQueue(
                                title = track.song.title,
                                items = listOf(track.song.toMediaMetadata().toMediaItem()),
                            ),
                        )
                        if (page >= currentTracks.size - 4) {
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
                    track = tracks[page],
                    isLiked = currentSong?.id == tracks[page].song.id && currentSong?.song?.liked == true,
                    onLike = {
                        playerConnection?.toggleLike()
                    },
                    onAddToQueue = {
                        val item = tracks[page].song.toMediaMetadata().toMediaItem()
                        playerConnection?.addToQueue(item)
                    },
                )
            }

            if (!hasSwiped) {
                Text(
                    text = "↑  Swipe",
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
                            ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
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

@Composable
private fun PulsePage(
    track: PulseTrack,
    modifier: Modifier = Modifier,
    isLiked: Boolean,
    onLike: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        AsyncImage(
            model = track.song.thumbnail,
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
