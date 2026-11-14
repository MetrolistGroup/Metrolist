@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.metrolist.android.playback.MusicService
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.android_auto_choose_pinned_playlists
import com.metrolist.shared.generated.resources.android_auto_local_playlist
import com.metrolist.shared.generated.resources.android_auto_no_playlists
import com.metrolist.shared.generated.resources.android_auto_pinned_playlist_mode
import com.metrolist.shared.generated.resources.android_auto_pinned_playlist_mode_desc
import com.metrolist.shared.generated.resources.android_auto_pinned_playlists
import com.metrolist.shared.generated.resources.android_auto_pinned_playlists_count
import com.metrolist.shared.generated.resources.android_auto_pinned_playlists_desc
import com.metrolist.shared.generated.resources.android_auto_play_in_order
import com.metrolist.shared.generated.resources.android_auto_playlists_load_failed
import com.metrolist.shared.generated.resources.android_auto_quick_add_playlist
import com.metrolist.shared.generated.resources.android_auto_title
import com.metrolist.shared.generated.resources.android_auto_youtube_playlist
import com.metrolist.shared.generated.resources.cached_playlist
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.downloaded_songs
import com.metrolist.shared.generated.resources.hidden_item_type_auto
import com.metrolist.shared.generated.resources.liked_songs
import com.metrolist.shared.generated.resources.my_top_100
import com.metrolist.shared.generated.resources.none
import com.metrolist.shared.generated.resources.podcasts
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.settings_quick_add_summary
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.uploaded_playlist_name
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.settings.AutomotiveAutoPlaylist
import com.metrolist.shared.settings.AutomotivePinnedPlaylist
import com.metrolist.shared.settings.AutomotivePinnedPlaylistPlaybackMode
import com.metrolist.shared.settings.AutomotivePlaylistSource
import com.metrolist.shared.settings.MAX_AUTOMOTIVE_PINNED_PLAYLISTS
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.getAutomotivePinnedPlaylists
import com.metrolist.shared.settings.setAutomotivePinnedPlaylists
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.libraryArtwork
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
fun AndroidAutoScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val database = koinInject<MusicDatabase>()
    val localPlaylists by database.dao().playlistsByNameAsc().collectAsState(initial = emptyList())
    val editablePlaylists = localPlaylists.filter { it.playlist.isEditable }
    val targetPlaylistId = Settings.shared.androidAutoTargetPlaylist
    val storedPinsJson = Settings.shared.androidAutoPinnedPlaylistsJson
    var pinnedPlaylists by remember(storedPinsJson) { mutableStateOf(Settings.shared.getAutomotivePinnedPlaylists()) }
    var youtubePlaylists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var loadingYoutubePlaylists by remember { mutableStateOf(true) }
    var youtubeLoadFailed by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var draftPins by remember { mutableStateOf(pinnedPlaylists) }

    LaunchedEffect(refreshKey) {
        loadingYoutubePlaylists = true
        val result = GlobalYouTubeRepository.instance.getUserPlaylistsResult()
        youtubePlaylists = result.items.filterIsInstance<PlaylistItem>()
        youtubeLoadFailed = !result.complete
        loadingYoutubePlaylists = false
    }

    val localChoices =
        localPlaylists.map { playlist ->
            AutomotivePinnedPlaylist(
                source = AutomotivePlaylistSource.LOCAL,
                id = playlist.id,
                title = playlist.title,
                thumbnail = libraryArtwork(playlist.id, playlist.thumbnailUrl, playlist.playlist.customThumbnailUrl),
            )
        }
    val localBrowseIds = localPlaylists.mapNotNullTo(mutableSetOf()) { it.playlist.browseId }
    val youtubeChoices =
        youtubePlaylists
            .filterNot { it.id in localBrowseIds }
            .map { playlist ->
                AutomotivePinnedPlaylist(
                    source = AutomotivePlaylistSource.YOUTUBE,
                    id = playlist.id,
                    title = playlist.title,
                    thumbnail = libraryArtwork(playlist.id, playlist.thumbnail, playlist.customThumbnail),
                )
            }
    val automaticChoices =
        listOf(
            AutomotivePinnedPlaylist(
                AutomotivePlaylistSource.AUTO,
                AutomotiveAutoPlaylist.LIKED.id,
                stringResource(Res.string.liked_songs),
            ),
            AutomotivePinnedPlaylist(
                AutomotivePlaylistSource.AUTO,
                AutomotiveAutoPlaylist.DOWNLOADED.id,
                stringResource(Res.string.downloaded_songs),
            ),
            AutomotivePinnedPlaylist(AutomotivePlaylistSource.AUTO, AutomotiveAutoPlaylist.TOP.id, stringResource(Res.string.my_top_100)),
            AutomotivePinnedPlaylist(
                AutomotivePlaylistSource.AUTO,
                AutomotiveAutoPlaylist.CACHED.id,
                stringResource(Res.string.cached_playlist),
            ),
            AutomotivePinnedPlaylist(
                AutomotivePlaylistSource.AUTO,
                AutomotiveAutoPlaylist.UPLOADED.id,
                stringResource(Res.string.uploaded_playlist_name),
            ),
            AutomotivePinnedPlaylist(
                AutomotivePlaylistSource.AUTO,
                AutomotiveAutoPlaylist.PODCASTS.id,
                stringResource(Res.string.podcasts),
            ),
        )
    val availablePlaylists = (automaticChoices + localChoices + youtubeChoices).distinctBy { it.source to it.id }
    val pinnedDescription =
        "${stringResource(Res.string.android_auto_pinned_playlists_desc)} ${
            stringResource(
                Res.string.android_auto_pinned_playlists_count,
                pinnedPlaylists.size,
                MAX_AUTOMOTIVE_PINNED_PLAYLISTS,
            )
        }"

    PopupVisibility(visible = showPlaylistDialog) {
        PinnedPlaylistDialog(
            playlists = (draftPins + availablePlaylists).distinctBy { it.source to it.id },
            selected = draftPins,
            loading = loadingYoutubePlaylists,
            loadFailed = youtubeLoadFailed,
            onRetry = { refreshKey++ },
            onToggle = { playlist ->
                val selected = draftPins.any { it.source == playlist.source && it.id == playlist.id }
                draftPins =
                    if (selected) {
                        draftPins.filterNot { it.source == playlist.source && it.id == playlist.id }
                    } else if (draftPins.size < MAX_AUTOMOTIVE_PINNED_PLAYLISTS) {
                        draftPins + playlist
                    } else {
                        draftPins
                    }
            },
            onDismiss = { showPlaylistDialog = false },
            onSave = {
                pinnedPlaylists = draftPins
                Settings.shared.setAutomotivePinnedPlaylists(draftPins)
                notifyAutomotiveLibraryChanged(context)
                showPlaylistDialog = false
            },
        )
    }

    SettingsSubScreen(
        title = stringResource(Res.string.android_auto_title),
        onNavigateBack = onNavigateBack,
    ) {
        SettingsSubScreenGroup(
            items =
                listOf(
                    listChooserSettingsItem(
                        title = stringResource(Res.string.android_auto_quick_add_playlist),
                        options = listOf("") + editablePlaylists.map { it.id },
                        selectedOption = targetPlaylistId.takeIf { id -> editablePlaylists.any { it.id == id } }.orEmpty(),
                        optionToString = { id ->
                            editablePlaylists.firstOrNull { it.id == id }?.title ?: stringResource(Res.string.none)
                        },
                        onOptionSelected = { Settings.shared.androidAutoTargetPlaylist = it },
                        description = stringResource(Res.string.settings_quick_add_summary),
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.android_auto_pinned_playlists),
                        description = pinnedDescription,
                        onClick = {
                            draftPins = pinnedPlaylists
                            showPlaylistDialog = true
                        },
                    ),
                    listChooserSettingsItem(
                        title = stringResource(Res.string.android_auto_pinned_playlist_mode),
                        description = stringResource(Res.string.android_auto_pinned_playlist_mode_desc),
                        options = AutomotivePinnedPlaylistPlaybackMode.entries,
                        selectedOption = Settings.shared.androidAutoPinnedPlaylistPlaybackMode,
                        optionToString = { mode ->
                            when (mode) {
                                AutomotivePinnedPlaylistPlaybackMode.IN_ORDER -> stringResource(Res.string.android_auto_play_in_order)
                                AutomotivePinnedPlaylistPlaybackMode.SHUFFLE -> stringResource(Res.string.shuffle)
                            }
                        },
                        onOptionSelected = { mode ->
                            Settings.shared.androidAutoPinnedPlaylistPlaybackMode = mode
                            notifyAutomotiveLibraryChanged(context)
                        },
                    ),
                ),
        )
    }
}

@Composable
private fun PinnedPlaylistDialog(
    playlists: List<AutomotivePinnedPlaylist>,
    selected: List<AutomotivePinnedPlaylist>,
    loading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    onToggle: (AutomotivePinnedPlaylist) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.android_auto_choose_pinned_playlists)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text =
                        stringResource(
                            Res.string.android_auto_pinned_playlists_count,
                            selected.size,
                            MAX_AUTOMOTIVE_PINNED_PLAYLISTS,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loadFailed) {
                    Text(stringResource(Res.string.android_auto_playlists_load_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onClick = onRetry,
                        enabled = !loading,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.retry)) }
                }
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ContainedLoadingIndicator()
                    }
                } else if (playlists.isEmpty()) {
                    Text(stringResource(Res.string.android_auto_no_playlists))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(playlists, key = { "${it.source}:${it.id}" }) { playlist ->
                            val checked = selected.any { it.source == playlist.source && it.id == playlist.id }
                            val enabled = checked || selected.size < MAX_AUTOMOTIVE_PINNED_PLAYLISTS
                            Surface(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .tvFocusHighlight(RoundedCornerShape(12.dp))
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = enabled) { onToggle(playlist) },
                                shape = RoundedCornerShape(12.dp),
                                color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = null,
                                        enabled = enabled,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(playlist.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            text =
                                                when (playlist.source) {
                                                    AutomotivePlaylistSource.LOCAL -> {
                                                        stringResource(Res.string.android_auto_local_playlist)
                                                    }

                                                    AutomotivePlaylistSource.YOUTUBE -> {
                                                        stringResource(
                                                            Res.string.android_auto_youtube_playlist,
                                                        )
                                                    }

                                                    AutomotivePlaylistSource.AUTO -> {
                                                        stringResource(
                                                            Res.string.hidden_item_type_auto,
                                                        )
                                                    }
                                                },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                modifier = Modifier.tvFocusHighlight(RoundedCornerShape(100.dp)),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.tvFocusHighlight(RoundedCornerShape(100.dp)),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

private fun notifyAutomotiveLibraryChanged(context: Context) {
    if (!MusicService.isRunning) return
    context.startService(
        Intent(context, MusicService::class.java).setAction(MusicService.ACTION_REFRESH_AUTOMOTIVE_LIBRARY),
    )
}
