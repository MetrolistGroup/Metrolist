@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.spotifyimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.metrolist.desktop.ui.DesktopDensityProvider
import com.metrolist.desktop.window.ClearWindowContentOnDispose
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_import
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear_selection
import com.metrolist.shared.generated.resources.favorites
import com.metrolist.shared.generated.resources.import_playlists
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.import_review_playlists
import com.metrolist.shared.generated.resources.playlist_name
import com.metrolist.shared.generated.resources.select_all
import com.metrolist.shared.generated.resources.spotify_import_choose_destination
import com.metrolist.shared.generated.resources.spotify_import_create_new_playlist
import com.metrolist.shared.generated.resources.spotify_import_destination_description
import com.metrolist.shared.generated.resources.spotify_import_existing_playlists
import com.metrolist.shared.generated.resources.spotify_import_limit_warning
import com.metrolist.shared.generated.resources.spotify_import_picker_description
import com.metrolist.shared.generated.resources.spotify_import_picker_title
import com.metrolist.shared.generated.resources.spotify_import_playlist_label
import com.metrolist.shared.generated.resources.spotify_import_review
import com.metrolist.shared.generated.resources.spotify_import_selected_count
import com.metrolist.shared.generated.resources.spotify_playlist_count
import com.metrolist.shared.generated.resources.spotify_playlist_track_count
import com.metrolist.shared.generated.resources.spotify_preview_summary
import com.metrolist.shared.playlistimport.PlaylistImportManager.Destination
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.utils.DragScrollableColumn
import meow.nyx.spotlib.PlaylistMeta
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SpotifyPlaylistPickerDialog(
    title: String = stringResource(Res.string.spotify_import_picker_title),
    playlists: List<PlaylistMeta>,
    selectedUris: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onReview: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismissRequest,
        title = title,
        state = rememberDialogState(width = 760.dp, height = 720.dp),
        visible = true,
    ) {
        ClearWindowContentOnDispose(window)
        DesktopDensityProvider {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(Res.string.spotify_import_picker_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(Res.string.spotify_import_selected_count, selectedUris.size),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(
                                onClick = {
                                    onSelectionChange(
                                        if (selectedUris.size ==
                                            playlists.size
                                        ) {
                                            emptySet()
                                        } else {
                                            playlists.mapTo(linkedSetOf()) { it.uri }
                                        },
                                    )
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(
                                    stringResource(
                                        if (selectedUris.size ==
                                            playlists.size
                                        ) {
                                            Res.string.clear_selection
                                        } else {
                                            Res.string.select_all
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    DragScrollableColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(playlists, key = PlaylistMeta::uri) { playlist ->
                            val selected = playlist.uri in selectedUris
                            val shape = RoundedCornerShape(14.dp)
                            Surface(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(shape)
                                        .toggleable(
                                            value = selected,
                                            role = Role.Checkbox,
                                            onValueChange = {
                                                onSelectionChange(
                                                    if (selected) selectedUris - playlist.uri else selectedUris + playlist.uri,
                                                )
                                            },
                                        ),
                                shape = shape,
                                color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    SpotifyArtwork(playlist.image, playlist.name)
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            playlist.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            listOfNotNull(
                                                playlist.owner?.takeIf(String::isNotBlank),
                                                playlist.trackCount.takeIf { it > 0 }?.let {
                                                    pluralStringResource(Res.plurals.spotify_playlist_track_count, it, it)
                                                },
                                            ).joinToString(" /\u00a0").ifBlank { stringResource(Res.string.spotify_import_playlist_label) },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Checkbox(checked = selected, onCheckedChange = null)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                        Spacer(Modifier.width(12.dp))
                        Button(enabled = selectedUris.isNotEmpty(), onClick = onReview, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(Res.string.spotify_import_review, selectedUris.size))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SpotifyPlaylistPreviewDialog(
    playlists: List<SpotifyImportManager.LoadedPlaylist>,
    selectedUri: String,
    onPlaylistSelected: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val selectedPlaylist = playlists.firstOrNull { it.uri == selectedUri } ?: playlists.first()
    DialogWindow(
        onCloseRequest = onDismissRequest,
        title =
            pluralStringResource(
                Res.plurals.import_review_playlists,
                playlists.size,
                stringResource(Res.string.import_provider_spotify),
            ),
        state = rememberDialogState(width = 980.dp, height = 760.dp),
        visible = true,
    ) {
        ClearWindowContentOnDispose(window)
        DesktopDensityProvider {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            pluralStringResource(
                                Res.plurals.import_review_playlists,
                                playlists.size,
                                stringResource(Res.string.import_provider_spotify),
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(
                                Res.string.spotify_preview_summary,
                                pluralStringResource(Res.plurals.spotify_playlist_count, playlists.size, playlists.size),
                                pluralStringResource(
                                    Res.plurals.spotify_playlist_track_count,
                                    playlists.sumOf { it.tracks.size },
                                    playlists.sumOf { it.tracks.size },
                                ),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        DragScrollableColumn(
                            modifier = Modifier.width(290.dp).fillMaxHeight(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(playlists, key = SpotifyImportManager.LoadedPlaylist::uri) { playlist ->
                                val selected = playlist.uri == selectedPlaylist.uri
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .selectable(
                                                selected = selected,
                                                role = Role.RadioButton,
                                                onClick = { onPlaylistSelected(playlist.uri) },
                                            ).padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SpotifyArtwork(playlist.image, playlist.name, 44)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            playlist.name,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            pluralStringResource(
                                                Res.plurals.spotify_playlist_track_count,
                                                playlist.tracks.size,
                                                playlist.tracks.size,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                SpotifyArtwork(selectedPlaylist.image, selectedPlaylist.name)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        selectedPlaylist.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        pluralStringResource(
                                            Res.plurals.spotify_playlist_track_count,
                                            selectedPlaylist.tracks.size,
                                            selectedPlaylist.tracks.size,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (selectedPlaylist.reachedTrackLimit) {
                                Text(
                                    stringResource(Res.string.spotify_import_limit_warning),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            HorizontalDivider()
                            key(selectedPlaylist.uri) {
                                DragScrollableColumn(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                                ) {
                                    itemsIndexed(
                                        items = selectedPlaylist.tracks,
                                        key = { index, _ -> "${selectedPlaylist.uri}:$index" },
                                    ) { index, track ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                            Text(
                                                (index + 1).toString(),
                                                modifier = Modifier.width(38.dp),
                                                textAlign = TextAlign.End,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (track.artist.isNotBlank()) {
                                                    Text(
                                                        track.artist,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onBack, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.back)) }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = onContinue, shapes = ButtonDefaults.shapes()) {
                            Text(
                                if (playlists.size == 1) {
                                    stringResource(Res.string.spotify_import_choose_destination)
                                } else {
                                    pluralStringResource(Res.plurals.import_playlists, playlists.size)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SpotifyDestinationDialog(
    playlistName: String,
    existingPlaylists: List<PlaylistEntity>,
    selectedDestination: Destination,
    includeFavorites: Boolean = false,
    onNameChange: (String) -> Unit,
    onDestinationSelected: (Destination) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismissRequest,
        title = stringResource(Res.string.spotify_import_choose_destination),
        state = rememberDialogState(width = 620.dp, height = 680.dp),
        visible = true,
    ) {
        ClearWindowContentOnDispose(window)
        DesktopDensityProvider {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(Res.string.spotify_import_choose_destination),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(Res.string.spotify_import_destination_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .selectable(
                                        selected = selectedDestination == Destination.NewSynced,
                                        role = Role.RadioButton,
                                        onClick = { onDestinationSelected(Destination.NewSynced) },
                                    ).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedDestination == Destination.NewSynced, onClick = null)
                            Text(stringResource(Res.string.spotify_import_create_new_playlist), modifier = Modifier.padding(start = 10.dp))
                        }
                        if (selectedDestination == Destination.NewSynced) {
                            OutlinedTextField(
                                value = playlistName,
                                onValueChange = onNameChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(Res.string.playlist_name)) },
                            )
                        }
                        if (includeFavorites) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .selectable(
                                            selected = selectedDestination == Destination.Favorites,
                                            role = Role.RadioButton,
                                            onClick = { onDestinationSelected(Destination.Favorites) },
                                        ).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selectedDestination == Destination.Favorites, onClick = null)
                                Text(stringResource(Res.string.favorites), modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                        if (existingPlaylists.isNotEmpty()) {
                            Text(
                                stringResource(Res.string.spotify_import_existing_playlists),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    DragScrollableColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(existingPlaylists, key = PlaylistEntity::id) { playlist ->
                            val selected = (selectedDestination as? Destination.EXISTING)?.playlist?.id == playlist.id
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .selectable(
                                            selected = selected,
                                            role = Role.RadioButton,
                                            onClick = { onDestinationSelected(Destination.EXISTING(playlist)) },
                                        ).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(
                                    playlist.name,
                                    modifier = Modifier.padding(start = 10.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onBack, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.back)) }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            enabled = selectedDestination != Destination.NewSynced || playlistName.isNotBlank(),
                            onClick = onImport,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(Res.string.action_import))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyArtwork(
    imageUrl: String?,
    name: String,
    size: Int = 52,
) {
    Surface(
        modifier = Modifier.size(size.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name
                    .trim()
                    .firstOrNull()
                    ?.uppercase()
                    .orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    url = imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
