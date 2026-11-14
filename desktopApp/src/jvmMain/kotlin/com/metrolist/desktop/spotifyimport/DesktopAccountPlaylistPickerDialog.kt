@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.spotifyimport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.metrolist.desktop.ui.DesktopDensityProvider
import com.metrolist.desktop.window.ClearWindowContentOnDispose
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear_selection
import com.metrolist.shared.generated.resources.import_playlists
import com.metrolist.shared.generated.resources.select_all
import com.metrolist.shared.generated.resources.spotify_playlist_track_count
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.utils.DragScrollableColumn
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal data class DesktopImportPlaylist(
    val id: String,
    val name: String,
    val image: String?,
    val trackCount: Int,
)

@Composable
@Suppress("FunctionName")
internal fun DesktopAccountPlaylistPickerDialog(
    title: StringResource,
    description: StringResource,
    selectedCount: StringResource,
    playlists: List<DesktopImportPlaylist>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onReview: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismissRequest,
        title = stringResource(title),
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
                            stringResource(title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(selectedCount, selectedIds.size),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(
                                onClick = {
                                    onSelectionChange(
                                        if (selectedIds.size == playlists.size) {
                                            emptySet()
                                        } else {
                                            playlists.mapTo(linkedSetOf()) { it.id }
                                        },
                                    )
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(
                                    stringResource(
                                        if (selectedIds.size == playlists.size) {
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
                        items(playlists, key = DesktopImportPlaylist::id) { playlist ->
                            val selected = playlist.id in selectedIds
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
                                                    if (selected) selectedIds - playlist.id else selectedIds + playlist.id,
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
                                    AsyncImage(
                                        url = playlist.image,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            playlist.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (playlist.trackCount > 0) {
                                                pluralStringResource(
                                                    Res.plurals.spotify_playlist_track_count,
                                                    playlist.trackCount,
                                                    playlist.trackCount,
                                                )
                                            } else {
                                                ""
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        TextButton(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(Res.string.cancel))
                        }
                        TextButton(
                            onClick = onReview,
                            enabled = selectedIds.isNotEmpty(),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(pluralStringResource(Res.plurals.import_playlists, selectedIds.size))
                        }
                    }
                }
            }
        }
    }
}
