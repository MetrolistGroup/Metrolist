/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalNavController
import com.metrolist.music.R
import com.metrolist.music.constants.AlbumViewTypeKey
import com.metrolist.music.constants.ChipSortTypeKey
import com.metrolist.music.constants.LibraryFilter
import com.metrolist.music.constants.LibraryViewType
import com.metrolist.music.constants.PlaylistViewTypeKey
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.ui.theme.SevenXTunePalette

@Composable
fun LibraryScreen() {
    val navController = LocalNavController.current
    var filterType by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)
    var libraryViewType by rememberEnumPreference(AlbumViewTypeKey, LibraryViewType.GRID)
    var playlistViewType by rememberEnumPreference(PlaylistViewTypeKey, LibraryViewType.GRID)

    val filterContent = @Composable {
        LibraryFilterBar(
            currentValue = filterType,
            onValueUpdate = { selected ->
                filterType =
                    if (selected == filterType && selected != LibraryFilter.LIBRARY) {
                        LibraryFilter.LIBRARY
                    } else {
                        selected
                    }
            },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SevenXTunePalette.Midnight),
    ) {
        // Compact, opaque collection header: the library content starts immediately
        // below it, without the large dead zone or content bleeding behind the header.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SevenXTunePalette.Midnight)
                .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "YOUR SPACE",
                    style = MaterialTheme.typography.labelSmall,
                    color = SevenXTunePalette.ElectricBlue,
                )
                Text(
                    text = "Your library",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SevenXTunePalette.TextPrimary,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LibraryUtilityCard(
                title = stringResource(R.string.history),
                subtitle = "Your listening history",
                icon = R.drawable.history,
                modifier = Modifier.weight(1f),
                onClick = { navController.navigate("history") },
            )
            LibraryUtilityCard(
                title = stringResource(R.string.stats),
                subtitle = "Your listening stats",
                icon = R.drawable.stats,
                modifier = Modifier.weight(1f),
                onClick = { navController.navigate("stats") },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(SevenXTunePalette.Midnight),
        ) {
            when (filterType) {
                LibraryFilter.LIBRARY ->
                    LibraryMixScreen(
                        navController = navController,
                        filterContent = filterContent,
                        viewType = libraryViewType,
                        onViewTypeChange = { libraryViewType = it },
                    )

                LibraryFilter.PLAYLISTS ->
                    LibraryPlaylistsScreen(
                        navController = navController,
                        filterContent = filterContent,
                        viewType = playlistViewType,
                        onViewTypeChange = { playlistViewType = it },
                    )

                LibraryFilter.SONGS ->
                    LibrarySongsScreen(
                        navController,
                        { filterType = LibraryFilter.LIBRARY },
                    )

                LibraryFilter.ALBUMS ->
                    LibraryAlbumsScreen(
                        navController,
                        { filterType = LibraryFilter.LIBRARY },
                    )

                LibraryFilter.ARTISTS ->
                    LibraryArtistsScreen(
                        navController,
                        { filterType = LibraryFilter.LIBRARY },
                    )

                LibraryFilter.PODCASTS ->
                    LibraryPodcastsScreen(
                        navController,
                        { filterType = LibraryFilter.LIBRARY },
                    )
            }
        }
    }
}

@Composable
private fun LibraryFilterBar(
    currentValue: LibraryFilter,
    onValueUpdate: (LibraryFilter) -> Unit,
) {
    val items =
        listOf(
            LibraryFilter.LIBRARY to (R.string.filter_library to R.drawable.library_music),
            LibraryFilter.PLAYLISTS to (R.string.filter_playlists to R.drawable.queue_music),
            LibraryFilter.SONGS to (R.string.filter_songs to R.drawable.music_note),
            LibraryFilter.ALBUMS to (R.string.filter_albums to R.drawable.album),
            LibraryFilter.ARTISTS to (R.string.filter_artists to R.drawable.artist),
            LibraryFilter.PODCASTS to (R.string.filter_podcasts to R.drawable.radio),
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(SevenXTunePalette.Surface.copy(alpha = 0.78f))
                .border(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        ),
                    ),
                    RoundedCornerShape(22.dp),
                )
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (filter, resourcePair) ->
            val (titleRes, iconRes) = resourcePair
            val selected = currentValue == filter
            val tabShape = RoundedCornerShape(16.dp)

            Box(
                modifier =
                    Modifier
                        .clip(tabShape)
                        .clickable { onValueUpdate(filter) }
                        .background(
                            if (selected) {
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary,
                                    ),
                                )
                            } else {
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color.Transparent),
                                )
                            },
                        )
                        .border(
                            1.dp,
                            if (selected) {
                                Color.White.copy(alpha = 0.08f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
                            },
                            tabShape,
                        )
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = stringResource(titleRes),
                        tint =
                            if (selected) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color =
                            if (selected) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}


@Composable
private fun LibraryUtilityCard(
    title: String,
    subtitle: String,
    @androidx.annotation.DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.50f),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
                    ),
                ),
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(
            modifier = Modifier.padding(start = 10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
