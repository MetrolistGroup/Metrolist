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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.Album
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.viewmodels.LibraryPlaylistsViewModel
import com.metrolist.music.viewmodels.LibrarySongsViewModel

/**
 * Spotify TV style Your Library Screen
 *
 * Horizontal shelves for:
 * - Playlists (Liked Songs, Custom Playlists)
 * - Albums
 * - Saved Songs
 */
@Composable
fun TvLibraryScreen(
    navController: NavController,
    songsViewModel: LibrarySongsViewModel = hiltViewModel(),
    playlistsViewModel: LibraryPlaylistsViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current
    val allSongs by songsViewModel.allSongs.collectAsState()
    val allPlaylists by playlistsViewModel.allPlaylists.collectAsState()

    var selectedFilterTab by remember { mutableIntStateOf(0) } // 0=Playlists, 1=Songs

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.BackgroundDark)
            .padding(top = 32.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        // Header
        item {
            Column(modifier = Modifier.padding(horizontal = 48.dp)) {
                Text(
                    text = "Your Library",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TvColors.TextPrimary
                )

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvFilterChip(
                        title = "Playlists",
                        isSelected = selectedFilterTab == 0,
                        onClick = { selectedFilterTab = 0 }
                    )
                    TvFilterChip(
                        title = "Songs",
                        isSelected = selectedFilterTab == 1,
                        onClick = { selectedFilterTab = 1 }
                    )
                }
            }
        }

        if (selectedFilterTab == 0) {
            // Playlists Shelf
            item {
                Text(
                    text = "Playlists",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TvColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 48.dp)
                )
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Liked Songs Card
                    item {
                        TvLikedSongsPlaylistCard(
                            songCount = allSongs.count { it.song.liked },
                            onClick = { navController.navigate("auto_playlist/liked") }
                        )
                    }

                    items(allPlaylists) { playlist ->
                        TvPlaylistCard(
                            playlist = playlist,
                            onClick = { navController.navigate("local_playlist/${playlist.id}") }
                        )
                    }
                }
            }
        } else {
            // Songs Shelf
            item {
                Text(
                    text = "Saved Songs (${allSongs.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TvColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 48.dp)
                )
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(allSongs) { songItem ->
                        TvSongItemCard(
                            song = songItem,
                            onClick = {
                                playerConnection?.playQueue(
                                    com.metrolist.music.playback.queues.YouTubeQueue(
                                        com.metrolist.innertube.models.WatchEndpoint(videoId = songItem.id),
                                        songItem.toMediaMetadata()
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvFilterChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    isFocused -> TvColors.SpotifyGreenBright
                    isSelected -> Color.White
                    else -> TvColors.CardBackgroundElevated
                }
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isSelected || isFocused) Color.Black else Color.White
        )
    }
}

@Composable
private fun TvLikedSongsPlaylistCard(
    songCount: Int,
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
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(Color(0xFF450AF5), Color(0xFFC4EFD9))
                    )
                )
        ) {
            Icon(
                painter = painterResource(R.drawable.favorite),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Liked Songs",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
            maxLines = 1
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = "$songCount songs",
            style = MaterialTheme.typography.bodySmall,
            color = TvColors.TextSecondary
        )
    }
}

@Composable
private fun TvPlaylistCard(
    playlist: Playlist,
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
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TvColors.CardBackgroundElevated)
        ) {
            Icon(
                painter = painterResource(R.drawable.queue_music),
                contentDescription = null,
                tint = TvColors.SpotifyGreenBright,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = playlist.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = "Playlist",
            style = MaterialTheme.typography.bodySmall,
            color = TvColors.TextSecondary
        )
    }
}

@Composable
private fun TvSongItemCard(
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
        ) {
            AsyncImage(
                model = song.song.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = song.song.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = song.artists.joinToString { it.name }.ifEmpty { song.song.albumName ?: "Song" },
            style = MaterialTheme.typography.bodySmall,
            color = TvColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
