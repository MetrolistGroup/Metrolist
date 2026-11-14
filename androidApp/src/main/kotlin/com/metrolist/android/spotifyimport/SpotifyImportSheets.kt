@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.spotifyimport

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.android.R
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.import_review_playlists
import com.metrolist.shared.generated.resources.select_all
import com.metrolist.shared.generated.resources.spotify_choose_playlists_supporting
import com.metrolist.shared.generated.resources.spotify_import_picker_title
import com.metrolist.shared.generated.resources.spotify_import_review
import com.metrolist.shared.generated.resources.spotify_playlist_count
import com.metrolist.shared.generated.resources.spotify_playlist_track_count
import com.metrolist.shared.generated.resources.spotify_preview_summary
import com.metrolist.shared.generated.resources.spotify_selected_playlist_count
import com.metrolist.shared.generated.resources.spotify_track_limit_warning
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import com.metrolist.shared.ui.components.ExpressiveFilterChip
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import meow.nyx.spotlib.PlaylistMeta
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.res.stringResource as androidStringResource
import com.metrolist.shared.ui.components.rememberModalSheetState as rememberBottomSheetState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpotifyPlaylistPickerSheet(
    title: String = stringResource(Res.string.spotify_import_picker_title),
    playlists: List<PlaylistMeta>,
    selectedUris: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    val allSelected = playlists.isNotEmpty() && selectedUris.size == playlists.size
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = null,
        gesturesEnabled = false,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(Res.string.spotify_choose_playlists_supporting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        pluralStringResource(
                            Res.plurals.spotify_selected_playlist_count,
                            selectedUris.size,
                            selectedUris.size,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                        onClick = {
                            onSelectionChange(
                                if (allSelected) emptySet() else playlists.mapTo(linkedSetOf(), PlaylistMeta::uri),
                            )
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(if (allSelected) Res.string.clear else Res.string.select_all))
                    }
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(playlists, key = PlaylistMeta::uri) { playlist ->
                    val selected = playlist.uri in selectedUris
                    val rowShape = MaterialTheme.shapes.large
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(rowShape)
                                .toggleable(
                                    value = selected,
                                    role = Role.Checkbox,
                                ) {
                                    onSelectionChange(
                                        if (selected) selectedUris - playlist.uri else selectedUris + playlist.uri,
                                    )
                                },
                        shape = rowShape,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SpotifyPlaylistArtwork(playlist)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val details =
                                    listOfNotNull(
                                        playlist.trackCount.takeIf { it > 0 }?.let {
                                            pluralStringResource(Res.plurals.spotify_playlist_track_count, it, it)
                                        },
                                        playlist.owner?.takeIf(String::isNotBlank),
                                        playlist.folderPath.takeIf(List<String>::isNotEmpty)?.joinToString(" /\u00a0"),
                                    ).joinToString(" /\u00a0")
                                if (details.isNotBlank()) {
                                    Text(
                                        details,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Checkbox(checked = selected, onCheckedChange = null)
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(androidStringResource(android.R.string.cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onReview, enabled = selectedUris.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.spotify_import_review, selectedUris.size))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpotifyPlaylistPreviewSheet(
    title: String? = null,
    playlists: List<SpotifyImportManager.LoadedPlaylist>,
    selectedPlaylistUri: String?,
    onPlaylistSelected: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val resources = LocalResources.current
    val selectedPlaylist = playlists.firstOrNull { it.uri == selectedPlaylistUri } ?: playlists.first()
    val totalTracks = playlists.sumOf { it.tracks.size }
    AdaptiveModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = null,
        gesturesEnabled = false,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title
                        ?: pluralStringResource(
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
                        pluralStringResource(Res.plurals.spotify_playlist_track_count, totalTracks, totalTracks),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (playlists.size > 1) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(playlists, key = SpotifyImportManager.LoadedPlaylist::uri) { playlist ->
                        ExpressiveFilterChip(
                            selected = playlist.uri == selectedPlaylist.uri,
                            onClick = { onPlaylistSelected(playlist.uri) },
                            label = {
                                Text(
                                    playlist.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
            key(selectedPlaylist.uri) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    if (playlists.any { it.reachedTrackLimit }) {
                        item(key = "track-limit-warning") {
                            Text(
                                stringResource(Res.string.spotify_track_limit_warning),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    item(key = "playlist-summary") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SpotifyPlaylistArtwork(selectedPlaylist.image)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    selectedPlaylist.name,
                                    style = MaterialTheme.typography.titleMedium,
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
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item(key = "playlist-divider") { HorizontalDivider() }
                    itemsIndexed(
                        items = selectedPlaylist.tracks,
                        key = { index, _ -> "${selectedPlaylist.uri}:$index" },
                    ) { index, track ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                (index + 1).toString(),
                                modifier = Modifier.width(32.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (track.artist.isNotBlank()) {
                                    Text(
                                        track.artist,
                                        style = MaterialTheme.typography.bodyMedium,
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
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onBack, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.back))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onContinue, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.continue_action))
                }
            }
        }
    }
}

@Composable
private fun SpotifyPlaylistArtwork(playlist: PlaylistMeta) {
    SpotifyPlaylistArtwork(playlist.image)
}

@Composable
private fun SpotifyPlaylistArtwork(imageUrl: String?) {
    Box(
        modifier =
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MetrolistIcons.Outlined.LibraryMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                url = imageUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
            )
        }
    }
}
