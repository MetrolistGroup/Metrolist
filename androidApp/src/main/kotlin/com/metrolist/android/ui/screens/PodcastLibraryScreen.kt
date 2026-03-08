/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.ui.download.rememberDownloadPermissionGate
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PodcastEntity
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_download
import com.metrolist.shared.generated.resources.artist
import com.metrolist.shared.generated.resources.artists
import com.metrolist.shared.generated.resources.auto_playlist
import com.metrolist.shared.generated.resources.downloaded_episodes
import com.metrolist.shared.generated.resources.episodes_for_later
import com.metrolist.shared.generated.resources.filter_channels
import com.metrolist.shared.generated.resources.filter_downloaded
import com.metrolist.shared.generated.resources.filter_episodes
import com.metrolist.shared.generated.resources.library_empty
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.new_episodes
import com.metrolist.shared.generated.resources.no_downloaded_episodes
import com.metrolist.shared.generated.resources.no_subscribed_channels
import com.metrolist.shared.generated.resources.play_next
import com.metrolist.shared.generated.resources.playlist_direction_ascending
import com.metrolist.shared.generated.resources.playlist_direction_descending
import com.metrolist.shared.generated.resources.podcast
import com.metrolist.shared.generated.resources.podcasts
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.sort_by_create_date
import com.metrolist.shared.generated.resources.sort_by_name
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.EpisodePlayedIndicator
import com.metrolist.shared.ui.components.SongThumbnailPlayingIndicator
import com.metrolist.shared.ui.components.libraryCover
import com.metrolist.shared.ui.components.menuAnchorSemantics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.BuiltinPlaylistMenuButton
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.gridCellShape
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private enum class PodcastLibraryTab { EPISODES, CHANNELS, DOWNLOADED }

private enum class DownloadSort { CREATE_DATE, NAME, ARTIST }

@Composable
fun PodcastLibraryScreen(
    onNavigateToPodcast: (String) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAutoPlaylist: (String) -> Unit = {},
) {
    val database: MusicDatabase = koinInject()
    val playerConnection = LocalPlayerConnection.current
    val currentSong by playerConnection?.currentSong?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val isPlaying by playerConnection?.isPlaying?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val bottomInset = LocalMiniPlayerBottomInset.current

    val subscribedPodcasts by database.dao().subscribedPodcasts().collectAsStateWithLifecycle(initialValue = emptyList())
    val savedEpisodes by database.dao().savedPodcastEpisodesByCreateDateAsc().collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadedEpisodes by database.dao().downloadedPodcastEpisodesByCreateDateAsc().collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val bookmarkedChannels by database.dao().bookmarkedPodcastChannels().collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedTab by remember { mutableStateOf<PodcastLibraryTab?>(null) }
    var downloadSort by remember { mutableStateOf(DownloadSort.CREATE_DATE) }
    var sortDescending by remember { mutableStateOf(true) }
    val podcastQueueTitle = stringResource(Res.string.downloaded_episodes)

    val podcastChannels =
        remember(subscribedPodcasts, bookmarkedChannels) {
            val bookmarked =
                bookmarkedChannels.map {
                    ArtistItem(
                        id = it.artist.id,
                        title = it.artist.name,
                        thumbnail = it.artist.thumbnailUrl,
                        isSubscribed = true,
                    )
                }
            val subscribed =
                subscribedPodcasts.map { podcast ->
                    ArtistItem(
                        // Older saved shows may lack channelId; use the show route so they remain reachable.
                        id = podcast.channelId ?: podcast.id,
                        title = podcast.author ?: podcast.title,
                        thumbnail = podcast.thumbnailUrl,
                        isSubscribed = true,
                    )
                }
            (bookmarked + subscribed).distinctBy { it.id }
        }

    val sortedDownloaded =
        remember(downloadedEpisodes, downloadSort, sortDescending) {
            val sorted =
                when (downloadSort) {
                    DownloadSort.CREATE_DATE -> {
                        downloadedEpisodes
                    }

                    DownloadSort.NAME -> {
                        downloadedEpisodes.sortedBy { it.song.title.lowercase() }
                    }

                    DownloadSort.ARTIST -> {
                        downloadedEpisodes.sortedBy {
                            it.orderedArtists
                                .joinToString { artist -> artist.name }
                                .ifBlank { it.song.albumName.orEmpty() }
                                .lowercase()
                        }
                    }
                }
            if (sortDescending) sorted.asReversed() else sorted
        }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(bottom = bottomInset + 96.dp),
        ) {
            item(key = "podcast_filters") {
                PodcastFilterChips(
                    selectedTab = selectedTab,
                    onSelected = { selectedTab = it },
                )
            }

            val showEpisodes = selectedTab == null || selectedTab == PodcastLibraryTab.EPISODES
            val showChannels = selectedTab == null || selectedTab == PodcastLibraryTab.CHANNELS
            val showDownloaded = selectedTab == null || selectedTab == PodcastLibraryTab.DOWNLOADED

            if (showEpisodes) {
                item(key = "rdpn_playlist") {
                    AutoPlaylistCard(
                        type = "podcasts",
                        title = stringResource(Res.string.new_episodes),
                        subtitle = stringResource(Res.string.auto_playlist),
                        icon = MetrolistIcons.Outlined.Podcasts,
                        onClick = { onNavigateToAutoPlaylist("podcasts") },
                    )
                }
                item(key = "se_playlist") {
                    AutoPlaylistCard(
                        type = "SE",
                        title = stringResource(Res.string.episodes_for_later),
                        subtitle = stringResource(Res.string.auto_playlist),
                        icon = MetrolistIcons.AutoMirrored.Outlined.QueueMusic,
                        onClick = { onNavigateToAutoPlaylist("SE") },
                    )
                }
                if (subscribedPodcasts.isNotEmpty()) {
                    item(key = "saved_shows_header") {
                        SectionLabel(text = stringResource(Res.string.podcasts))
                    }
                    items(subscribedPodcasts, key = { "podcast_${it.id}" }) { podcast ->
                        PodcastShowRow(
                            podcast = podcast,
                            onClick = { onNavigateToPodcast(podcast.id) },
                        )
                    }
                } else if (savedEpisodes.isEmpty() && selectedTab != null) {
                    item(key = "episodes_empty") {
                        EmptyPodcastLibrary(stringResource(Res.string.library_empty))
                    }
                }
            }

            if (showChannels && (selectedTab != null || podcastChannels.isNotEmpty())) {
                item(key = "channels_header") {
                    SectionLabel(text = stringResource(Res.string.artists))
                }
                if (podcastChannels.isEmpty()) {
                    item(key = "channels_empty") {
                        EmptyPodcastLibrary(stringResource(Res.string.no_subscribed_channels))
                    }
                } else {
                    items(podcastChannels, key = { "channel_${it.id}" }) { channel ->
                        PodcastLibraryChannelRow(
                            channel = channel,
                            onClick = {
                                if (channel.id.startsWith("MPSP")) {
                                    onNavigateToPodcast(channel.id)
                                } else {
                                    CollectionPreviews.record(channel)
                                    onNavigateToArtist(channel.id)
                                }
                            },
                        )
                    }
                }
            }

            if (showDownloaded && (selectedTab != null || sortedDownloaded.isNotEmpty())) {
                item(key = "downloaded_sort") {
                    DownloadSortHeader(
                        sort = downloadSort,
                        descending = sortDescending,
                        onSortSelected = { downloadSort = it },
                        onToggleDirection = { sortDescending = !sortDescending },
                        count = sortedDownloaded.size,
                    )
                }
                if (sortedDownloaded.isEmpty()) {
                    item(key = "downloaded_empty") {
                        EmptyPodcastLibrary(stringResource(Res.string.no_downloaded_episodes))
                    }
                } else {
                    items(sortedDownloaded, key = { "downloaded_${it.song.id}" }) { song ->
                        PodcastLibraryEpisodeRow(
                            song = song,
                            isActive = currentSong?.id == song.song.id,
                            isPlaying = isPlaying,
                            onClick = {
                                playerConnection?.playQueue(
                                    sortedDownloaded.map { it.toSongItem() },
                                    sortedDownloaded.indexOf(song).coerceAtLeast(0),
                                    podcastQueueTitle,
                                    finite = true,
                                )
                            },
                            onPlayNext = { playerConnection?.addToQueue(song.toSongItem(), playNext = true) },
                            onDownload = null,
                        )
                    }
                }
            }
        }

        if (selectedTab == PodcastLibraryTab.DOWNLOADED && sortedDownloaded.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = {
                    playerConnection?.shuffleQueue(
                        sortedDownloaded.map { it.toSongItem() },
                        podcastQueueTitle,
                        finite = true,
                    )
                },
                icon = { Icon(MetrolistIcons.Rounded.Shuffle, contentDescription = null) },
                text = { Text(stringResource(Res.string.shuffle)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomInset + 16.dp),
            )
        }
    }
}

@Composable
private fun PodcastFilterChips(
    selectedTab: PodcastLibraryTab?,
    onSelected: (PodcastLibraryTab?) -> Unit,
) {
    ChipShelf(
        options = PodcastLibraryTab.entries,
        selectedOption = selectedTab,
        onSelectionChange = onSelected,
        label = { tab ->
            stringResource(
                when (tab) {
                    PodcastLibraryTab.EPISODES -> Res.string.filter_episodes
                    PodcastLibraryTab.CHANNELS -> Res.string.filter_channels
                    PodcastLibraryTab.DOWNLOADED -> Res.string.filter_downloaded
                },
            )
        },
        modifier = Modifier.padding(top = 1.dp),
    )
}

@Composable
private fun AutoPlaylistCard(
    type: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(
                            56.dp,
                        ).clip(gridCellShape())
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                val cover = libraryCover(type)
                if (cover != null) {
                    Image(
                        painterResource(cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BuiltinPlaylist.fromId("auto:$type")?.let { BuiltinPlaylistMenuButton(it, koinInject<MusicDatabase>()) }
            Icon(MetrolistIcons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PodcastShowRow(
    podcast: PodcastEntity,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = podcast.thumbnailUrl,
                contentDescription = podcast.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(gridCellShape()),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    podcast.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    podcast.author ?: stringResource(Res.string.podcast),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(MetrolistIcons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun PodcastLibraryChannelRow(
    channel: ArtistItem,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(
                model = channel.thumbnail,
                contentDescription = channel.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(gridCellShape()),
            )
            Text(
                channel.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(MetrolistIcons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun DownloadSortHeader(
    sort: DownloadSort,
    descending: Boolean,
    onSortSelected: (DownloadSort) -> Unit,
    onToggleDirection: () -> Unit,
    count: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Text(
                text =
                    when (sort) {
                        DownloadSort.CREATE_DATE -> stringResource(Res.string.sort_by_create_date)
                        DownloadSort.NAME -> stringResource(Res.string.sort_by_name)
                        DownloadSort.ARTIST -> stringResource(Res.string.artist)
                    },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.menuAnchorSemantics(expanded).clickable { expanded = true }.padding(8.dp),
            )
            DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                    DownloadSort.entries.forEach { candidate ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when (candidate) {
                                        DownloadSort.CREATE_DATE -> stringResource(Res.string.sort_by_create_date)
                                        DownloadSort.NAME -> stringResource(Res.string.sort_by_name)
                                        DownloadSort.ARTIST -> stringResource(Res.string.artist)
                                    },
                                )
                            },
                            onClick = {
                                onSortSelected(candidate)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        IconButton(onClick = onToggleDirection, shapes = IconButtonDefaults.shapes()) {
            Icon(
                if (descending) MetrolistIcons.Outlined.KeyboardArrowDown else MetrolistIcons.Outlined.KeyboardArrowUp,
                contentDescription =
                    stringResource(if (descending) Res.string.playlist_direction_descending else Res.string.playlist_direction_ascending),
            )
        }
        Spacer(Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun PodcastLibraryEpisodeRow(
    song: Song,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onDownload: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(72.dp).clip(gridCellShape())) {
                AsyncImage(
                    model = song.song.thumbnailUrl,
                    contentDescription = song.song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                SongThumbnailPlayingIndicator(
                    isActive = isActive && !Settings.shared.uiEnhancements,
                    isPlaying = isPlaying,
                    shape = gridCellShape(),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    song.song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val author = song.orderedArtists.joinToString { it.displayName }.ifBlank { song.song.albumName.orEmpty() }
                Text(
                    listOfNotNull(
                        author.takeIf { it.isNotBlank() },
                        song.song.duration
                            .takeIf { it > 0 }
                            ?.let { formatDuration(it * 1000L) },
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                EpisodePlayedIndicator(song.id, song.song.duration.toLong())
            }
            Box {
                IconButton(
                    modifier = Modifier.navigationPopupSource(),
                    onClick = { expanded = true },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(MetrolistIcons.Default.MoreVert, contentDescription = stringResource(Res.string.more_options))
                }
                DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.play_next)) },
                            onClick = {
                                expanded = false
                                onPlayNext()
                            },
                            leadingIcon = { Icon(MetrolistIcons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                        )
                        onDownload?.let { download ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.action_download)) },
                                onClick = {
                                    expanded = false
                                    download()
                                },
                                leadingIcon = { Icon(MetrolistIcons.Outlined.Download, contentDescription = null) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPodcastLibrary(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
