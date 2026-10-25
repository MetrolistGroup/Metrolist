/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.AddToPlaylistPosition
import com.metrolist.music.constants.AddToPlaylistPositionKey
import com.metrolist.music.constants.AddToPlaylistSortDescendingKey
import com.metrolist.music.constants.AddToPlaylistSortTypeKey
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.PlaylistListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.reportException
import com.metrolist.music.viewmodels.PlaylistsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults

@Composable
fun AddToPlaylistDialogOnline(
    isVisible: Boolean,
    allowSyncing: Boolean = true,
    initialTextFieldValue: String? = null,
    songs: SnapshotStateList<Song>,
    onDismiss: () -> Unit,
    onProgressStart: (Boolean) -> Unit,
    onPercentageChange: (Int) -> Unit,
    onSongChange: (String) -> Unit = {},
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val viewStateMap = remember { mutableStateMapOf<String, ItemsPage?>() }
    val (addToPlaylistPosition) = rememberEnumPreference(
        AddToPlaylistPositionKey,
        AddToPlaylistPosition.BEGINNING,
    )
    val (sortType, onSortTypeChange) = rememberEnumPreference(
        AddToPlaylistSortTypeKey,
        PlaylistSortType.NAME
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        AddToPlaylistSortDescendingKey,
        false
    )
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()

    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showDuplicateDialog by remember {
        mutableStateOf(false)
    }
    var selectedPlaylist by remember {
        mutableStateOf<Playlist?>(null)
    }
    val songIds by remember {
        mutableStateOf<List<String>?>(null)
    }
    val duplicates by remember {
        mutableStateOf(emptyList<String>())
    }
    var playlistsContainingSong by remember {
        mutableStateOf<Set<String>>(emptySet())
    }

    LaunchedEffect(isVisible, playlists) {
        if (!isVisible) {
            playlistsContainingSong = emptySet()
            return@LaunchedEffect
            }
                playlistsContainingSong = emptySet()
                if (playlists.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val ids = songs.map { it.id }
                playlistsContainingSong = playlists
                    .filter { playlist ->
                        database.playlistDuplicatesBatched(playlist.id, ids).isNotEmpty()
                    }
                    .map { it.id }
                    .toSet()
            }
        }
    }

    if (isVisible) {
        ListDialog(
            onDismiss = onDismiss
        ) {
            item {
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.94f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "buttonScale"
                )
                FilledTonalButton(
                    onClick = { showCreatePlaylistDialog = true },
                    shape = RoundedCornerShape(50),
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_playlist),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (playlists.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            PlaylistSortType.entries.forEach { type ->
                                val selected = sortType == type
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSortTypeChange(type) },
                                    shape = RoundedCornerShape(50),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderWidth = 0.dp,
                                        selectedBorderWidth = 0.dp,
                                    ),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    label = {
                                        Text(
                                            text = stringResource(when (type) {
                                                PlaylistSortType.CREATE_DATE  -> R.string.sort_by_create_date
                                                PlaylistSortType.NAME         -> R.string.sort_by_name
                                                PlaylistSortType.SONG_COUNT   -> R.string.sort_by_song_count
                                                PlaylistSortType.LAST_UPDATED -> R.string.sort_by_last_updated
                                            }),
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                )
                            }
                        }
                        val arrowBg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowBg"
                        )
                        val arrowFg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowFg"
                        )
                        IconToggleButton(
                            checked = sortDescending,
                            onCheckedChange = { onSortDescendingChange(it) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(arrowBg)
                                .size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (sortDescending) R.drawable.arrow_downward else R.drawable.arrow_upward
                                ),
                                contentDescription = stringResource(
                                    if (sortDescending) R.string.sort_descending else R.string.sort_ascending
                                ),
                                tint = arrowFg,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            items(playlists) { playlist ->
                val containsSong = playlist.id in playlistsContainingSong
                val rowBg by animateColorAsState(
                    targetValue = if (containsSong)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "playlistBg"
                )
                PlaylistListItem(
                    playlist = playlist,
                    modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(rowBg)
                    .clickable {
                        selectedPlaylist = playlist
                        coroutineScope.launch(Dispatchers.IO) {
                            onDismiss()
                            val songsTot = songs.count()
                            if (songsTot == 0) return@launch
                            
                            val songsIdx = AtomicInteger(0)
                            val semaphore = kotlinx.coroutines.sync.Semaphore(15)
                            val resolvedSongIds = arrayOfNulls<String>(songsTot)
                            onProgressStart(true)
                            try {
                                val jobs = songs.mapIndexed { index, song ->
                                    coroutineScope.launch {
                                        semaphore.withPermit {
                                            try {
                                                var allArtists = ""
                                                song.artists.forEach { artist ->
                                                    allArtists += " ${URLDecoder.decode(artist.name, StandardCharsets.UTF_8.toString())}"
                                                }
                                                val query = "${song.title} - $allArtists"

                                                YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                                                    .onSuccess { result ->
                                                        val items = result.items.distinctBy { it.id }
                                                        if (items.isNotEmpty()) {
                                                            val firstSong = items.firstOrNull() as? SongItem
                                                            if (firstSong != null) {
                                                                val firstSongMedia = firstSong.toMediaMetadata()
                                                                withContext(Dispatchers.IO) {
                                                                    try {
                                                                        database.insert(firstSongMedia)
                                                                    } catch (e: Exception) {
                                                                        Timber.tag("Exception").e(e.toString())
                                                                    }
                                                                    resolvedSongIds[index] = firstSong.id
                                                                }
                                                            }
                                                        }
                                                    }
                                                    .onFailure { reportException(it) }
                                            } catch (e: Exception) {
                                                Timber.tag("ERROR").v(e.toString())
                                            } finally {
                                                val completed = songsIdx.incrementAndGet()
                                                onSongChange(song.title)
                                                onPercentageChange(((completed.toDouble() / songsTot) * 100).toInt())
                                            }
                                        }
                                    }
                                }
                                jobs.forEach { it.join() }
                                database.addSongsToPlaylist(
                                    playlist,
                                    resolvedSongIds.filterNotNull().map { it to null },
                                    prepend = addToPlaylistPosition.prepend,
                                )
                            } finally {
                                withContext(Dispatchers.Main) {
                                    onProgressStart(false)
                                }
                            }
                        }
                    }
                )
            }

            item {
                ListItem(
                    modifier = Modifier.clickable {
                        coroutineScope.launch(Dispatchers.IO) {
                            onDismiss()
                            val songsTot = songs.count()
                            if (songsTot == 0) return@launch

                            val songsIdx = AtomicInteger(0)
                            val semaphore = kotlinx.coroutines.sync.Semaphore(15)
                            onProgressStart(true)
                            try {
                                val jobs = songs.reversed().map { song ->
                                    coroutineScope.launch {
                                        semaphore.withPermit {
                                            try {
                                                var allArtists = ""
                                                song.artists.forEach { artist ->
                                                    allArtists += " ${URLDecoder.decode(artist.name, StandardCharsets.UTF_8.toString())}"
                                                }
                                                val query = "${song.title} - $allArtists"

                                                YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                                                    .onSuccess { result ->
                                                        val items = result.items.distinctBy { it.id }
                                                        if (items.isNotEmpty()) {
                                                            val firstSong = items.firstOrNull() as? SongItem
                                                            if (firstSong != null) {
                                                                val firstSongMedia = firstSong.toMediaMetadata()
                                                                val firstSongEnt = firstSong.toMediaMetadata().toSongEntity()
                                                                withContext(Dispatchers.IO) {
                                                                    try {
                                                                        database.insert(firstSongMedia)
                                                                        database.query {
                                                                            update(firstSongEnt.toggleLike())
                                                                        }
                                                                    } catch (e: Exception) {
                                                                        Timber.tag("Exception").e(e.toString())
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    .onFailure { reportException(it) }
                                            } catch (e: Exception) {
                                                Timber.tag("ERROR").v(e.toString())
                                            } finally {
                                                val completed = songsIdx.incrementAndGet()
                                                onSongChange(song.title)
                                                onPercentageChange(((completed.toDouble() / songsTot) * 100).toInt())
                                            }
                                        }
                                    }
                                }
                                jobs.forEach { it.join() }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    onProgressStart(false)
                                }
                            }
                        }
                    },
                    title = stringResource(R.string.liked_songs),
                    thumbnailContent = {
                        Image(
                            painter = painterResource(id = R.drawable.favorite),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
                        )
                    },
                    trailingContent = {}
                )
            }

            item {
                Text(
                    text = stringResource(R.string.playlist_add_local_to_synced_note),
                    fontSize = TextUnit(12F, TextUnitType.Sp),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing
        )
    }

    // duplicate songs warning
    if (showDuplicateDialog) {
        DefaultDialog(
            title = { Text(stringResource(R.string.duplicates)) },
            buttons = {
                TextButton(
                    onClick = {
                        showDuplicateDialog = false
                        coroutineScope.launch {
                            database.addSongsToPlaylist(
                                selectedPlaylist!!,
                                songIds!!.filter {
                                    !duplicates.contains(it)
                                }.map { it to null },
                                prepend = addToPlaylistPosition.prepend,
                            )
                            onDismiss()
                        }
                    }
                ) {
                    Text(stringResource(R.string.skip_duplicates))
                }

                TextButton(
                    onClick = {
                        showDuplicateDialog = false
                        coroutineScope.launch {
                            database.addSongsToPlaylist(
                                selectedPlaylist!!,
                                songIds!!.map { it to null },
                                prepend = addToPlaylistPosition.prepend,
                            )
                            onDismiss()
                        }
                    }
                ) {
                    Text(stringResource(R.string.add_anyway))
                }

                TextButton(
                    onClick = {
                        showDuplicateDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            onDismiss = {
                showDuplicateDialog = false
            }
        ) {
            Text(
                text = if (duplicates.size == 1) {
                    stringResource(R.string.duplicates_description_single)
                } else {
                    stringResource(R.string.duplicates_description_multiple, duplicates.size)
                },
                textAlign = TextAlign.Start,
                modifier = Modifier.align(Alignment.Start)
            )
        }
    }
}
