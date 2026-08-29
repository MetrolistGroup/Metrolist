/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.tv

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.viewmodels.HomeViewModel

/**
 * Spotify TV Inspired Home Screen
 *
 * Displays rich hero shelves:
 * - Quick Picks (recently played / top recommendations in Spotify TV cards)
 * - Daily Discover & Recommended Songs
 * - Speed Dial / Jump back in
 * - Community Playlists & Curated mixes
 */
@Composable
fun TvHomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val playerConnection = LocalPlayerConnection.current
    val quickPicks by viewModel.quickPicks.collectAsState()
    val speedDialItems by viewModel.speedDialItems.collectAsState()
    val dailyDiscover by viewModel.dailyDiscover.collectAsState()
    val forgottenFavorites by viewModel.forgottenFavorites.collectAsState()
    val homePage by viewModel.homePage.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadHomeData()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.BackgroundDark)
            .padding(top = 32.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        // ── Top Speed Dial (Spotify TV "Good Evening / Jump Back In" grid row) ──
        if (speedDialItems.isNotEmpty()) {
            item {
                TvShelfHeader(title = "Jump Back In")
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(speedDialItems.take(8)) { item ->
                        TvSpeedDialCard(
                            item = item,
                            onClick = {
                                when (item) {
                                    is SongItem -> playerConnection?.playQueue(
                                        com.metrolist.music.playback.queues.YouTubeQueue(
                                            com.metrolist.innertube.models.WatchEndpoint(videoId = item.id),
                                            item.toMediaMetadata()
                                        )
                                    )
                                    is AlbumItem -> navController.navigate("album/${item.id}")
                                    is PlaylistItem -> navController.navigate("online_playlist/${item.id}")
                                    is ArtistItem -> navController.navigate("artist/${item.id}")
                                    else -> {}
                                }
                            }
                        )
                    }
                }
            }
        }

        // ── Quick Picks Shelf ────────────────────────────────────────────────
        if (!quickPicks.isNullOrEmpty()) {
            item {
                TvShelfHeader(title = "Quick Picks for You")
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(quickPicks!!) { song ->
                        TvSongCard(
                            song = song,
                            onClick = {
                                playerConnection?.playQueue(
                                    com.metrolist.music.playback.queues.YouTubeQueue(
                                        com.metrolist.innertube.models.WatchEndpoint(videoId = song.id),
                                        song.toMediaMetadata()
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }

        // ── Daily Discover Shelf ─────────────────────────────────────────────
        if (!dailyDiscover.isNullOrEmpty()) {
            item {
                TvShelfHeader(title = "Daily Discover")
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(dailyDiscover!!) { item ->
                        val ytItem = item.recommendation
                        TvYtItemCard(
                            item = ytItem,
                            onClick = {
                                when (ytItem) {
                                    is SongItem -> playerConnection?.playQueue(
                                        com.metrolist.music.playback.queues.YouTubeQueue(
                                            com.metrolist.innertube.models.WatchEndpoint(videoId = ytItem.id),
                                            ytItem.toMediaMetadata()
                                        )
                                    )
                                    is AlbumItem -> navController.navigate("album/${ytItem.id}")
                                    is PlaylistItem -> navController.navigate("online_playlist/${ytItem.id}")
                                    else -> {}
                                }
                            }
                        )
                    }
                }
            }
        }

        // ── Forgotten Favorites ──────────────────────────────────────────────
        if (!forgottenFavorites.isNullOrEmpty()) {
            item {
                TvShelfHeader(title = "Forgotten Favorites")
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(forgottenFavorites!!) { song ->
                        TvSongCard(
                            song = song,
                            onClick = {
                                playerConnection?.playQueue(
                                    com.metrolist.music.playback.queues.YouTubeQueue(
                                        com.metrolist.innertube.models.WatchEndpoint(videoId = song.id),
                                        song.toMediaMetadata()
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }

        // ── Explore / Home Sections ──────────────────────────────────────────
        homePage?.sections?.forEach { section ->
            if (section.items.isNotEmpty()) {
                item {
                    TvShelfHeader(title = section.title)
                    Spacer(Modifier.height(16.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(section.items) { ytItem ->
                            TvYtItemCard(
                                item = ytItem,
                                onClick = {
                                    when (ytItem) {
                                        is SongItem -> playerConnection?.playQueue(
                                            com.metrolist.music.playback.queues.YouTubeQueue(
                                                com.metrolist.innertube.models.WatchEndpoint(videoId = ytItem.id),
                                                ytItem.toMediaMetadata()
                                            )
                                        )
                                        is AlbumItem -> navController.navigate("album/${ytItem.id}")
                                        is PlaylistItem -> navController.navigate("online_playlist/${ytItem.id}")
                                        is ArtistItem -> navController.navigate("artist/${ytItem.id}")
                                        else -> {}
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Section Title for horizontal shelves */
@Composable
private fun TvShelfHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = TvColors.TextPrimary,
        modifier = Modifier.padding(horizontal = 48.dp)
    )
}

/** Spotify TV Card for SpeedDial Items */
@Composable
private fun TvSpeedDialCard(
    item: YTItem,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(260.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) TvColors.CardBackgroundElevated else TvColors.CardBackground)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .focusable(interactionSource = interactionSource)
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = item.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .background(Color.DarkGray)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp)
        )
    }
}

/** Standard Spotify TV Music Item Card (Square Artwork + Info) */
@Composable
private fun TvSongCard(
    song: Song,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) TvColors.CardBackgroundElevated else Color.Transparent)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(10.dp)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(8.dp))
                .shadow(if (isFocused) 12.dp else 4.dp, RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = song.artists.joinToString { it.name },
            style = MaterialTheme.typography.bodySmall,
            color = TvColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** General YTItem Card */
@Composable
private fun TvYtItemCard(
    item: YTItem,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val isArtist = item is ArtistItem

    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) TvColors.CardBackgroundElevated else Color.Transparent)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(10.dp)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick()
                    true
                } else false
            }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(8.dp))
                .shadow(if (isFocused) 12.dp else 4.dp, if (isArtist) CircleShape else RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(2.dp))

        val subtitle = when (item) {
            is SongItem -> item.artists.joinToString { it.name }
            is AlbumItem -> item.artists?.joinToString { it.name } ?: "Album"
            is PlaylistItem -> item.author?.name ?: "Playlist"
            is ArtistItem -> "Artist"
            else -> ""
        }

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TvColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
