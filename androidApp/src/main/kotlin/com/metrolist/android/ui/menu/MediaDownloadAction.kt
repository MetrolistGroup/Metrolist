@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.menu

import android.widget.Toast
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.android.ui.download.rememberDownloadPermissionGate
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.loadAndCachePlaylist
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.home_load_item_failed
import com.metrolist.shared.generated.resources.queued_downloads
import com.metrolist.shared.generated.resources.remove
import com.metrolist.shared.generated.resources.removed_downloads
import com.metrolist.shared.generated.resources.stored_remove_selected_message
import com.metrolist.shared.generated.resources.stored_remove_selected_title
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.menu.loadMediaItemSongs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

internal class MediaDownloadAction(
    val isDownloaded: Boolean,
    val invoke: () -> Unit,
    val isConfirming: () -> Boolean,
    val confirmationDialog: @Composable (onConfirmed: () -> Unit) -> Unit,
)

@Composable
internal fun rememberMediaDownloadAction(
    item: YTItem,
    onDismiss: () -> Unit,
    onDownload: (() -> Unit)? = null,
    loadSongs: suspend () -> List<SongItem> = { loadMediaItemSongs(item) },
): MediaDownloadAction {
    val context = LocalContext.current
    val scope = LocalLifecycleOwner.current.lifecycleScope
    val database: MusicDatabase = koinInject()
    val downloadManager: SongDownloadManager = koinInject()
    val audioCacheManager: AudioCacheManager = koinInject()
    val requireDownloadNotifications = rememberDownloadPermissionGate()
    var refreshKey by remember { mutableIntStateOf(0) }

    val allDownloaded by remember(item.id, refreshKey) {
        when (item) {
            is SongItem -> {
                database.dao().isSongDownloaded(item.id)
            }

            is PlaylistItem -> {
                if (item.id.startsWith("auto:") && item.id != "auto:uploaded") {
                    combine(
                        flow { emit(loadSongs().mapTo(mutableSetOf()) { it.id }) },
                        database.dao().cachedAudioIdsByKind(KIND_DOWNLOAD),
                    ) { ids, downloaded ->
                        areAllSongsDownloaded(ids, downloaded.toSet())
                    }
                } else {
                    database.dao().isPlaylistDownloaded(item.id.removePrefix("VL"))
                }
            }

            is AlbumItem -> {
                database.dao().isAlbumDownloaded(item.id)
            }

            else -> {
                flowOf(false)
            }
        }
    }.collectAsState(initial = item is PlaylistItem && item.isDownloaded)
    var preparingRemoval by remember(item.id) { mutableStateOf(false) }
    var awaitingPermission by remember(item.id) { mutableStateOf(false) }
    var pendingRemovalIds by remember(item.id) { mutableStateOf<Set<String>?>(null) }

    fun remove(ids: Set<String>) {
        if (item is PlaylistItem) downloadManager.stopTrackingPlaylist(item.id)
        scope.launch {
            val removed =
                removeMediaDownloads(ids) { id ->
                    if (item is SongItem) downloadManager.removeDownload(item) else audioCacheManager.removeDownload(id)
                }
            Toast
                .makeText(
                    context,
                    getPluralString(Res.plurals.removed_downloads, removed, removed),
                    Toast.LENGTH_SHORT,
                ).show()
            refreshKey++
        }
    }

    return MediaDownloadAction(
        isDownloaded = allDownloaded,
        invoke = {
            if (allDownloaded) {
                if (item is SongItem) {
                    remove(setOf(item.id))
                } else {
                    preparingRemoval = true
                    scope.launch {
                        try {
                            pendingRemovalIds =
                                when {
                                    item is PlaylistItem && !item.id.startsWith("auto:") -> {
                                        database.dao().playlistDownloadIds(item.id.removePrefix("VL")).toSet()
                                    }

                                    item is AlbumItem -> {
                                        database.dao().albumDownloadIds(item.id).toSet()
                                    }

                                    else -> {
                                        loadSongs().mapTo(mutableSetOf()) { it.id } intersect
                                            database
                                                .dao()
                                                .cachedAudioIdsByKind(KIND_DOWNLOAD)
                                                .first()
                                                .toSet()
                                    }
                                }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            Toast.makeText(context, getString(Res.string.home_load_item_failed, item.title), Toast.LENGTH_SHORT).show()
                        } finally {
                            preparingRemoval = false
                        }
                    }
                }
            } else {
                awaitingPermission = true
                var requestingPermission = true
                requireDownloadNotifications {
                    if (item is PlaylistItem) downloadManager.trackPlaylist(item.id)
                    if (onDownload != null) {
                        onDownload()
                    } else {
                        scope.launch {
                            try {
                                val songs =
                                    if (item is PlaylistItem && !item.isLocal && !item.id.startsWith("auto:")) {
                                        database.loadAndCachePlaylist(GlobalYouTubeRepository.instance, item.id).playlistSongs()
                                    } else {
                                        loadSongs()
                                    }
                                val count = downloadManager.downloadAll(songs)
                                Toast
                                    .makeText(
                                        context,
                                        getPluralString(Res.plurals.queued_downloads, count, count),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                refreshKey++
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                Toast.makeText(context, getString(Res.string.home_load_item_failed, item.title), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    awaitingPermission = false
                    if (!requestingPermission) onDismiss()
                }
                requestingPermission = false
            }
        },
        isConfirming = { preparingRemoval || awaitingPermission || pendingRemovalIds != null },
        confirmationDialog = { onConfirmed ->
            PopupContent(pendingRemovalIds) { ids ->
                AlertDialog(
                    onDismissRequest = { pendingRemovalIds = null },
                    title = { Text(pluralStringResource(Res.plurals.stored_remove_selected_title, ids.size, ids.size)) },
                    text = { Text(stringResource(Res.string.stored_remove_selected_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingRemovalIds = null
                                remove(ids)
                                onConfirmed()
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.remove)) }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { pendingRemovalIds = null },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.cancel)) }
                    },
                )
            }
        },
    )
}

internal fun areAllSongsDownloaded(
    songIds: Set<String>,
    downloadedIds: Set<String>,
): Boolean = songIds.isNotEmpty() && downloadedIds.containsAll(songIds)

internal suspend fun removeMediaDownloads(
    songIds: Set<String>,
    removeDownload: suspend (String) -> Boolean,
): Int = songIds.count { removeDownload(it) }
