/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.shared.db.entities.Playlist
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add
import com.metrolist.shared.generated.resources.add_to_playlist
import com.metrolist.shared.generated.resources.already_in_playlist
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.create_playlist
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.playlist_update_failed
import com.metrolist.shared.generated.resources.song_count
import com.metrolist.shared.model.sortedForPlaylistPicker
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.utils.DragScrollableColumn
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaylistChooser(
    playlists: List<Playlist>,
    playtimes: Map<String, Long?>,
    membership: Set<String>,
    onDismiss: () -> Unit,
    onChoosePlaylist: (Playlist) -> Unit,
    selectedIds: Set<String>,
    completedIds: Set<String>,
    failedIds: Set<String>,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    onCreatePlaylist: (() -> Unit)? = null,
    busy: Boolean = false,
    failed: Boolean = false,
    sheet: Boolean = false,
    readOnlyPlaylists: List<Playlist> = emptyList(),
) {
    val sort = Settings.shared.playlistPickerSort
    val direction = Settings.shared.playlistPickerSortDirection
    val librarySorting = Settings.shared.librarySorting(LibraryFilterType.PLAYLISTS)
    val manualOrder = Settings.shared.libraryManualOrder
    val inPickerOrder: (List<Playlist>) -> List<Playlist> = { list ->
        list.sortedForPlaylistPicker(
            sort,
            direction,
            name = { it.title },
            addedAt = { it.playlist.bookmarkedAt ?: it.playlist.createdAt },
            lastUpdated = { it.playlist.lastUpdateTime },
            songCount = { it.songCount },
            id = { it.playlist.browseId ?: it.id },
            librarySorting = librarySorting,
            manualOrder = manualOrder,
            playtime = { playtimes[it.id] },
        )
    }
    // Read-only rows follow every selectable playlist, each group in the picker's order.
    val sortedPlaylists =
        remember(playlists, readOnlyPlaylists, sort, direction, librarySorting, manualOrder, playtimes) {
            inPickerOrder(playlists) + inPickerOrder(readOnlyPlaylists)
        }
    PlaylistChooserContent(
        playlists =
            sortedPlaylists.map { playlist ->
                PlaylistChoice(
                    id = playlist.id,
                    title = playlist.title,
                    artwork = libraryArtwork(playlist.id, playlist.thumbnailUrl, playlist.playlist.customThumbnailUrl),
                    songCount = playlist.songCount,
                )
            },
        membership = membership,
        onDismiss = onDismiss,
        onChoosePlaylist = { id -> playlists.firstOrNull { it.id == id }?.let(onChoosePlaylist) },
        selectedIds = selectedIds,
        completedIds = completedIds,
        failedIds = failedIds,
        onAdd = onAdd,
        modifier = modifier,
        onCreatePlaylist = onCreatePlaylist,
        busy = busy,
        failed = failed,
        sheet = sheet,
        readOnlyIds = remember(readOnlyPlaylists) { readOnlyPlaylists.mapTo(mutableSetOf()) { it.id } },
    )
}

data class PlaylistChoice(
    val id: String,
    val title: String,
    val artwork: String?,
    val songCount: Int?,
    val author: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistChooserContent(
    playlists: List<PlaylistChoice>,
    membership: Set<String>,
    onDismiss: () -> Unit,
    onChoosePlaylist: (String) -> Unit,
    selectedIds: Set<String>,
    completedIds: Set<String>,
    failedIds: Set<String>,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    onCreatePlaylist: (() -> Unit)? = null,
    busy: Boolean = false,
    failed: Boolean = false,
    disabledIds: Set<String> = emptySet(),
    // Shown for membership only: greyed out and never selectable.
    readOnlyIds: Set<String> = emptySet(),
    onCancel: (() -> Unit)? = null,
    statusContent: @Composable () -> Unit = {},
    // A bottom sheet dismisses by drag, back or scrim, so it drops the close button and fills its full height.
    sheet: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = if (sheet) 4.dp else 8.dp, bottom = if (sheet) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.add_to_playlist),
                style = if (sheet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { heading() },
            )
            if (!sheet) {
                FilledTonalIconButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Close,
                        contentDescription = stringResource(Res.string.close),
                    )
                }
            }
        }

        if (busy) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }

        if (failed) {
            Text(
                text = stringResource(Res.string.playlist_update_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(ComponentShapes.CardShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        statusContent()

        PlaylistPickerOrderControls(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        DragScrollableColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = sheet),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (onCreatePlaylist != null) {
                item {
                    Material3MenuGroup(
                        items =
                            listOf(
                                Material3MenuItemData(
                                    title = { Text(stringResource(Res.string.create_playlist)) },
                                    icon = {
                                        Icon(
                                            imageVector = MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = if (busy) null else onCreatePlaylist,
                                    cardColors =
                                        CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                ),
                            ),
                    )
                }
            }

            itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                val containsSongs = playlist.id in membership
                val readOnly = playlist.id in readOnlyIds
                val contentModifier = if (readOnly) Modifier.alpha(READ_ONLY_CONTENT_ALPHA) else Modifier
                Material3MenuGroupItem(
                    item =
                        Material3MenuItemData(
                            icon = {
                                PlaylistCoverImage(
                                    url = playlist.artwork,
                                    modifier =
                                        contentModifier
                                            .size(40.dp)
                                            .clip(ComponentShapes.SmallThumbnailShape),
                                )
                            },
                            title = { Text(playlist.title, modifier = contentModifier) },
                            description = {
                                val description =
                                    when (playlist.id) {
                                        in failedIds -> {
                                            stringResource(Res.string.playlist_update_failed)
                                        }

                                        in completedIds -> {
                                            stringResource(Res.string.done)
                                        }

                                        else -> {
                                            playlist.songCount?.let { pluralStringResource(Res.plurals.song_count, it, it) }
                                                ?: playlist.author
                                        }
                                    }
                                Column(contentModifier) {
                                    if (description != null) Text(description)
                                    if (containsSongs) {
                                        Text(stringResource(Res.string.already_in_playlist))
                                    }
                                }
                            },
                            cardColors =
                                if (containsSongs) {
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                } else {
                                    null
                                },
                            onClick =
                                if (busy || readOnly || playlist.id in completedIds || playlist.id in disabledIds) {
                                    null
                                } else {
                                    { onChoosePlaylist(playlist.id) }
                                },
                            trailingContent = {
                                Checkbox(
                                    checked = playlist.id in selectedIds,
                                    enabled = !busy && !readOnly && playlist.id !in completedIds && playlist.id !in disabledIds,
                                    onCheckedChange = { onChoosePlaylist(playlist.id) },
                                    modifier = Modifier.semantics { contentDescription = playlist.title },
                                )
                            },
                        ),
                    index = index,
                    count = playlists.size,
                )
            }
        }
        Button(
            onClick = onAdd,
            enabled = !busy && selectedIds.any { it !in completedIds },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(Res.string.add))
        }
        if (busy && onCancel != null) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.cancel))
            }
        }
    }
}

// Material's opacity for disabled content.
private const val READ_ONLY_CONTENT_ALPHA = 0.38f
