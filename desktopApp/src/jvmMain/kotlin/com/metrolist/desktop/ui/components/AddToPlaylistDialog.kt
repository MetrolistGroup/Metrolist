/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.desktop.state.content.LibraryContentState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.state.playback.PlaylistAdditionStopped
import com.metrolist.desktop.ui.screens.PlaylistNameDialog
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.added_songs_to_playlist
import com.metrolist.shared.generated.resources.could_not_add_to_playlist
import com.metrolist.shared.generated.resources.create_playlist_dialog_title
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.no_editable_playlists
import com.metrolist.shared.generated.resources.playlist_add_progress
import com.metrolist.shared.generated.resources.playlist_add_stopped
import com.metrolist.shared.generated.resources.playlist_add_uncertain
import com.metrolist.shared.generated.resources.playlist_create_action
import com.metrolist.shared.generated.resources.sign_in
import com.metrolist.shared.generated.resources.sign_in_to_add_playlists
import com.metrolist.shared.model.sortedForPlaylistPicker
import com.metrolist.shared.settings.HiddenLibraryItem
import com.metrolist.shared.settings.HiddenLibraryItems
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.Dialog
import com.metrolist.shared.ui.components.PlaylistChoice
import com.metrolist.shared.ui.components.PlaylistChooserContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.libraryArtwork
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.MdSpacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddToPlaylistDialog() {
    val songs = PlayerState.showAddToPlaylistSongs
    if (songs.isEmpty()) return
    val hiddenVersion by HiddenLibraryItems.version.collectAsState()
    val playlists =
        remember(PlayerState.userPlaylists, hiddenVersion, Settings.shared.hiddenLibraryPlaylistsJson) {
            val hiddenKeys = HiddenLibraryItems.keys()
            PlayerState.userPlaylists.filterNot { HiddenLibraryItem.from(it).key in hiddenKeys }
        }
    val sort = Settings.shared.playlistPickerSort
    val direction = Settings.shared.playlistPickerSortDirection
    val librarySorting = Settings.shared.librarySorting(LibraryFilterType.PLAYLISTS)
    val manualOrder = Settings.shared.libraryManualOrder
    val sortedPlaylists =
        remember(playlists, sort, direction, librarySorting, manualOrder) {
            playlists.sortedForPlaylistPicker(
                sort,
                direction,
                name = { it.title },
                addedAt = { it.libraryAddedAt },
                lastUpdated = { it.libraryUpdatedAt },
                songCount = { it.songCount?.toIntOrNull() },
                id = { it.id },
                librarySorting = librarySorting,
                manualOrder = manualOrder,
                playtime = { it.playtime },
            )
        }
    var selectedIds by remember(songs) { mutableStateOf(emptySet<String>()) }
    var completedIds by remember(songs) { mutableStateOf(emptySet<String>()) }
    var failedIds by remember(songs) { mutableStateOf(emptySet<String>()) }
    var addErrorPlaylist by remember(songs) { mutableStateOf<String?>(null) }
    var additionStopped by remember(songs) { mutableStateOf<PlaylistAdditionStopped?>(null) }
    var addInProgress by remember(songs) { mutableStateOf(false) }
    var creating by remember(songs) { mutableStateOf(false) }
    var name by remember(songs) { mutableStateOf("") }
    var createdPlaylistId by remember(songs) { mutableStateOf<String?>(null) }

    LaunchedEffect(songs, AuthState.isSignedIn) {
        PlayerState.fetchUserPlaylists(songs, AuthState.isSignedIn)
    }

    fun dismiss() {
        if (addInProgress) return
        PlayerState.dismissAddToPlaylist()
    }

    fun notifyAdded(playlistName: String) {
        AppState.scope.launch {
            AppState.snackbarHostState.showSnackbar(
                getPluralString(Res.plurals.added_songs_to_playlist, songs.size, songs.size, playlistName),
                duration = SnackbarDuration.Short,
            )
        }
    }

    fun onAdded(playlistName: String) {
        PlayerState.dismissAddToPlaylist()
        notifyAdded(playlistName)
    }

    val membership = playlists.filter { it.id.removePrefix("VL") in PlayerState.userPlaylistMembership }.mapTo(mutableSetOf()) { it.id }
    Dialog(onDismissRequest = ::dismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            PlaylistChooserContent(
                playlists =
                    sortedPlaylists.takeUnless { PlayerState.isUserPlaylistsLoading }.orEmpty().map { playlist ->
                        PlaylistChoice(
                            id = playlist.id,
                            title = playlist.title,
                            artwork = libraryArtwork(playlist.id, playlist.thumbnail, playlist.customThumbnail),
                            songCount = playlist.songCount?.toIntOrNull(),
                            author = playlist.author,
                        )
                    },
                membership = membership,
                disabledIds = membership,
                onDismiss = ::dismiss,
                onChoosePlaylist = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    failedIds = failedIds - id
                },
                selectedIds = selectedIds,
                completedIds = completedIds,
                failedIds = failedIds,
                busy = addInProgress,
                onAdd = {
                    addErrorPlaylist = null
                    additionStopped = null
                    failedIds = emptySet()
                    addInProgress = true
                    PlayerState.addToPlaylists(
                        playlistIds =
                            sortedPlaylists
                                .filter {
                                    it.id in selectedIds && it.id !in completedIds && it.id !in membership
                                }.map { it.id },
                        songs = songs,
                        onPlaylistAdded = { id ->
                            completedIds = completedIds + id
                            playlists.firstOrNull { it.id == id }?.let { notifyAdded(it.title) }
                        },
                        onResult = { id, result ->
                            addInProgress = false
                            result.fold(
                                onSuccess = { dismiss() },
                                onFailure = {
                                    additionStopped = it as? PlaylistAdditionStopped
                                    addErrorPlaylist = playlists.firstOrNull { playlist -> playlist.id == id }?.title
                                    if (id != null) failedIds = failedIds + id
                                },
                            )
                        },
                    )
                },
                onCreatePlaylist = { creating = true },
                onCancel = PlayerState::cancelPlaylistAddition,
                modifier = Modifier.widthIn(max = 560.dp).heightIn(max = 600.dp).padding(vertical = 16.dp),
                statusContent = {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        if (addInProgress && PlayerState.playlistAddTotal > 0) {
                            Text(
                                stringResource(
                                    Res.string.playlist_add_progress,
                                    PlayerState.playlistAddCompleted,
                                    PlayerState.playlistAddTotal,
                                ),
                            )
                        }
                        additionStopped?.let {
                            Text(stringResource(Res.string.playlist_add_stopped, it.completed, it.total - it.completed))
                            if (it.uncertain) Text(stringResource(Res.string.playlist_add_uncertain))
                        }
                        addErrorPlaylist?.let {
                            Text(
                                stringResource(Res.string.could_not_add_to_playlist, it, stringResource(Res.string.error_unknown)),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        when {
                            !AuthState.isSignedIn && !PlayerState.isUserPlaylistsLoading && playlists.isEmpty() -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = MdSpacing.sm),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(MdSpacing.sm),
                                ) {
                                    Icon(
                                        MetrolistIcons.Outlined.AccountCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                    Text(
                                        stringResource(Res.string.sign_in_to_add_playlists),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = {
                                            dismiss()
                                            UiState.showSignIn = true
                                            UiState.showSwitchChannel = false
                                        },
                                        shapes = ButtonDefaults.shapes(),
                                    ) {
                                        Text(stringResource(Res.string.sign_in))
                                    }
                                }
                            }

                            PlayerState.isUserPlaylistsLoading -> {
                                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                    ContainedLoadingIndicator()
                                }
                            }

                            playlists.isEmpty() -> {
                                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(Res.string.no_editable_playlists))
                                }
                            }
                        }
                    }
                },
            )
        }
    }
    PopupVisibility(visible = creating) {
        PlaylistNameDialog(
            title = stringResource(Res.string.create_playlist_dialog_title),
            confirmActionLabel = stringResource(Res.string.playlist_create_action),
            value = name,
            onValueChange = { if (!addInProgress && createdPlaylistId == null) name = it },
            onDismiss = { if (!addInProgress) creating = false },
        ) {
            if (!addInProgress) {
                addInProgress = true

                fun save(id: String) {
                    PlayerState.addToPlaylist(id, songs) { result ->
                        addInProgress = false
                        result
                            .onSuccess { onAdded(name) }
                            .onFailure {
                                creating = false
                                additionStopped = it as? PlaylistAdditionStopped
                                addErrorPlaylist = name
                            }
                    }
                }
                val existing = createdPlaylistId
                if (existing != null) {
                    save(existing)
                } else {
                    LibraryContentState.createPlaylist(name) { result ->
                        result
                            .onSuccess { id ->
                                createdPlaylistId = id
                                save(id)
                            }.onFailure {
                                addInProgress = false
                                creating = false
                                additionStopped = it as? PlaylistAdditionStopped
                                addErrorPlaylist = name
                            }
                    }
                }
            }
        }
    }
}
