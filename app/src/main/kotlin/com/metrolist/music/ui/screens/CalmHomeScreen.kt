/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.YouTubeGridItem
import com.metrolist.music.ui.component.shimmer.GridItemPlaceHolder
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.menu.YouTubePlaylistMenu
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.viewmodels.CalmHomeViewModel
import com.metrolist.music.viewmodels.CalmMood
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalmHomeScreen(viewModel: CalmHomeViewModel = hiltViewModel()) {
    val navController = LocalNavController.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val listenTogetherManager = LocalListenTogetherManager.current
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val scope = rememberCoroutineScope()

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val pullRefreshState = rememberPullToRefreshState()

    val playMood: (CalmMood) -> Unit = { mood ->
        val playlist = playlists[mood]?.randomOrNull()
        when {
            isListenTogetherGuest -> Unit
            playlist != null -> playerConnection.playQueue(YouTubeQueue(playlist.shufflePlayEndpoint()))
            else -> navController.navigate(SearchRoutes.resultRoute(mood.queries.first()))
        }
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
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "greeting") {
                Greeting()
            }

            CalmMood.entries.forEach { mood ->
                item(key = "banner_${mood.name}") {
                    MoodBanner(mood = mood, onClick = { playMood(mood) })
                }

                item(key = "playlists_${mood.name}") {
                    val moodPlaylists = playlists[mood]
                    if (moodPlaylists == null) {
                        ShimmerHost(horizontalAlignment = Alignment.Start) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                userScrollEnabled = false,
                            ) {
                                items(4) { GridItemPlaceHolder(thumbnailShape = RoundedCornerShape(16.dp)) }
                            }
                        }
                    } else {
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                            items(moodPlaylists, key = { "${mood.name}_${it.id}" }) { playlist ->
                                YouTubeGridItem(
                                    item = playlist,
                                    coroutineScope = scope,
                                    thumbnailRatio = 1f,
                                    modifier =
                                        Modifier.combinedClickable(
                                            onClick = { navController.navigate("online_playlist/${playlist.id}") },
                                            onLongClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                menuState.show {
                                                    YouTubePlaylistMenu(
                                                        playlist = playlist,
                                                        coroutineScope = scope,
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

            item(key = "footer") {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Greeting() {
    val greetingRes =
        remember {
            when (LocalTime.now().hour) {
                in 5..11 -> R.string.calm_greeting_morning
                in 12..16 -> R.string.calm_greeting_afternoon
                else -> R.string.calm_greeting_evening
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp),
    ) {
        Text(
            text = stringResource(greetingRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.calm_greeting_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MoodBanner(
    mood: CalmMood,
    onClick: () -> Unit,
) {
    val title = stringResource(mood.titleRes)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .height(132.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(listOf(Color(mood.gradientStart), Color(mood.gradientEnd))),
                ).clickable(onClick = onClick),
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp, end = 96.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(mood.subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f)),
        ) {
            Icon(
                painter = painterResource(R.drawable.play),
                contentDescription = stringResource(R.string.calm_play_mood, title),
                tint = Color(mood.gradientStart),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

private fun PlaylistItem.shufflePlayEndpoint(): WatchEndpoint =
    shuffleEndpoint ?: playEndpoint ?: WatchEndpoint(playlistId = id.removePrefix("VL"))
