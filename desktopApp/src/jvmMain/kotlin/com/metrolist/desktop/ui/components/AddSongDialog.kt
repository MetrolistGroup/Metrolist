/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add
import com.metrolist.shared.generated.resources.add_a_song
import com.metrolist.shared.generated.resources.add_anyway
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear_search
import com.metrolist.shared.generated.resources.duplicate_song
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.search_songs
import com.metrolist.shared.generated.resources.song_already_in_playlist
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.theme.MdSpacing
import com.metrolist.shared.ui.utils.DragScrollableColumn
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddSongDialog(
    existingSongIds: Set<String>,
    onDismiss: () -> Unit,
    onAddSong: (SongItem, Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var duplicateSong by remember { mutableStateOf<SongItem?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_a_song)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MdSpacing.sm)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MdSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(Res.string.search_songs)) },
                        leadingIcon = {
                            Icon(
                                imageVector = MetrolistIcons.Default.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(
                                        imageVector = MetrolistIcons.Default.Clear,
                                        contentDescription = stringResource(Res.string.clear_search),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            if (query.isBlank()) return@Button
                            val submittedQuery = query
                            searchJob?.cancel()
                            searchJob =
                                scope.launch {
                                    isSearching = true
                                    val found =
                                        GlobalYouTubeRepository.instance
                                            .searchFiltered(
                                                submittedQuery,
                                                SearchFilter.SONG,
                                                recordSearchHistory = !Settings.shared.pauseSearchHistory,
                                            ).items
                                            .filterIsInstance<SongItem>()
                                    ensureActive()
                                    results = found
                                    isSearching = false
                                }
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.search))
                    }
                }
                if (isSearching) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(MdSpacing.md),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContainedLoadingIndicator()
                    }
                } else if (results.isNotEmpty()) {
                    DragScrollableColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(MdSpacing.xxs),
                    ) {
                        itemsIndexed(results, key = { index, song -> "add_${song.id}_$index" }) { _, song ->
                            AddSongResultRow(
                                song = song,
                                isDuplicate = song.id in existingSongIds,
                                onClick = {
                                    if (song.id in existingSongIds) {
                                        duplicateSong = song
                                    } else {
                                        onAddSong(song, false)
                                        onDismiss()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )

    PopupContent(duplicateSong) { song ->
        AlertDialog(
            onDismissRequest = { duplicateSong = null },
            title = { Text(stringResource(Res.string.duplicate_song)) },
            text = { Text(stringResource(Res.string.song_already_in_playlist, song.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddSong(song, true)
                        duplicateSong = null
                        onDismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.add_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { duplicateSong = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AddSongResultRow(
    song: SongItem,
    isDuplicate: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color =
            if (isDuplicate) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MdSpacing.sm, vertical = MdSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                url = song.thumbnail ?: "",
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(ComponentShapes.SmallThumbnailShape),
            )
            Spacer(Modifier.width(MdSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (song.artists.isNotEmpty()) {
                    Text(
                        song.artists.joinToString(", ") { it.displayName },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(MetrolistIcons.Default.Add, contentDescription = stringResource(Res.string.add))
        }
    }
}
