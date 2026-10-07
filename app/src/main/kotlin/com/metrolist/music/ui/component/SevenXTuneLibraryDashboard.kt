package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.LibraryFilter

@Composable
fun SevenXTuneLibraryDashboard(
    modifier: Modifier = Modifier,
    onCategorySelected: (LibraryFilter) -> Unit,
    onLiked: () -> Unit,
    onDownloaded: () -> Unit,
    onCached: () -> Unit,
    onTop: () -> Unit,
    onUploaded: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    )
                    .padding(22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.library_music_filled),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Your Library",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = "Everything you saved, created and downloaded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f),
                        )
                    }
                }
            }
        }

        Text(
            text = "Explore your library",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LibraryDashboardCard(
                modifier = Modifier.weight(1f),
                icon = R.drawable.playlist_play,
                title = stringResource(R.string.filter_playlists),
                subtitle = "Playlists",
                onClick = { onCategorySelected(LibraryFilter.PLAYLISTS) },
            )
            LibraryDashboardCard(
                modifier = Modifier.weight(1f),
                icon = R.drawable.music_note,
                title = stringResource(R.string.filter_songs),
                subtitle = "Songs",
                onClick = { onCategorySelected(LibraryFilter.SONGS) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LibraryDashboardCard(
                modifier = Modifier.weight(1f),
                icon = R.drawable.album,
                title = stringResource(R.string.filter_albums),
                subtitle = "Albums",
                onClick = { onCategorySelected(LibraryFilter.ALBUMS) },
            )
            LibraryDashboardCard(
                modifier = Modifier.weight(1f),
                icon = R.drawable.artist,
                title = stringResource(R.string.filter_artists),
                subtitle = "Artists",
                onClick = { onCategorySelected(LibraryFilter.ARTISTS) },
            )
        }

        LibraryDashboardCard(
            modifier = Modifier.fillMaxWidth(),
            icon = R.drawable.podcast,
            title = stringResource(R.string.filter_podcasts),
            subtitle = "Shows and episodes",
            onClick = { onCategorySelected(LibraryFilter.PODCASTS) },
        )

        Text(
            text = "Your collections",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LibraryDashboardCard(
                modifier = Modifier.weight(1f),
                icon = R.drawable.favorite,
                title = stringResource(R.string.liked),
                subtitle = "Your favourites",
                accent = MaterialTheme.colorScheme.tertiary,
                onClick = onLiked,
            )
            LibraryDashboardCard(
                modifier = Modifier.weight(1f),
                icon = R.drawable.download,
                title = stringResource(R.string.offline),
                subtitle = "Saved offline",
                accent = MaterialTheme.colorScheme.secondary,
                onClick = onDownloaded,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LibraryDashboardCard(
                modifier = Modifier.weight(1f),
                icon = R.drawable.cached,
                title = stringResource(R.string.cached_playlist),
                subtitle = "Available cache",
                accent = MaterialTheme.colorScheme.primary,
                onClick = onCached,
            )
            LibraryDashboardCard(
                modifier = Modifier.weight(1f),
                icon = R.drawable.trending_up,
                title = stringResource(R.string.my_top),
                subtitle = "Most played",
                accent = MaterialTheme.colorScheme.tertiary,
                onClick = onTop,
            )
        }

        LibraryDashboardCard(
            modifier = Modifier.fillMaxWidth(),
            icon = R.drawable.backup,
            title = stringResource(R.string.uploaded_playlist),
            subtitle = "Your uploads",
            accent = MaterialTheme.colorScheme.secondary,
            onClick = onUploaded,
        )
    }
}

@Composable
private fun LibraryDashboardCard(
    icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier = modifier
            .height(104.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        accent.copy(alpha = 0.15f),
                        RoundedCornerShape(16.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
