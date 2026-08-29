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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spotify TV style Search & Browse Screen
 *
 * Direct TV search input with visual search results split into:
 * Top Result, Songs, Albums, Artists, Playlists
 */
@Composable
fun TvSearchScreen(navController: NavController) {
    val playerConnection = LocalPlayerConnection.current
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<YTItem>>(emptyList()) }

    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocus()
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            isLoading = true
            withContext(Dispatchers.IO) {
                YouTube.searchSummary(searchQuery).onSuccess { summaryPage ->
                    searchResults = summaryPage.summaries.flatMap { it.items }.distinctBy { it.id }
                }
                isLoading = false
            }
        } else if (searchQuery.isEmpty()) {
            searchResults = emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.BackgroundDark)
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        // Search Input Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "What do you want to play?",
                        color = TvColors.TextSecondary
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = "Search",
                        tint = TvColors.SpotifyGreenBright,
                        modifier = Modifier.size(24.dp)
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TvColors.SpotifyGreenBright,
                    unfocusedBorderColor = TvColors.OverlayFocused,
                    focusedContainerColor = TvColors.CardBackgroundElevated,
                    unfocusedContainerColor = TvColors.CardBackground,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester)
            )

            if (isLoading) {
                Spacer(Modifier.width(16.dp))
                CircularProgressIndicator(
                    color = TvColors.SpotifyGreenBright,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        if (searchResults.isEmpty()) {
            // Spotify TV Browse Categories Placeholder
            Text(
                text = "Browse All",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TvColors.TextPrimary
            )
            Spacer(Modifier.height(16.dp))

            val genres = listOf(
                "Pop" to Color(0xFF8D67AB),
                "Hip-Hop" to Color(0xFFBA5D07),
                "Rock" to Color(0xFFE91429),
                "Indie" to Color(0xFF608108),
                "Dance / Electronic" to Color(0xFF0D73EC),
                "Chill" to Color(0xFFD84000),
                "Workout" to Color(0xFF777777),
                "Party" to Color(0xFF503750)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(genres) { (title, color) ->
                    TvGenreCard(
                        title = title,
                        color = color,
                        onClick = { searchQuery = title }
                    )
                }
            }
        } else {
            // Search Results Grid
            Text(
                text = "Results for \"$searchQuery\"",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TvColors.TextPrimary
            )
            Spacer(Modifier.height(16.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(searchResults) { item ->
                    TvSearchResultCard(
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
}

@Composable
private fun TvGenreCard(
    title: String,
    color: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
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
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun TvSearchResultCard(
    item: YTItem,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isArtist = item is ArtistItem

    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                .fillMaxWidth()
                .height(140.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) TvColors.SpotifyGreenBright else TvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

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
