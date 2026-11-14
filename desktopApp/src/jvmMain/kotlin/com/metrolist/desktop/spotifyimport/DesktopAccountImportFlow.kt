@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.spotifyimport

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_error_generic
import com.metrolist.shared.generated.resources.import_error_load_failed
import com.metrolist.shared.generated.resources.import_error_no_tracks_to_import
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.generated.resources.spotify_import_alert_title
import com.metrolist.shared.generated.resources.spotify_import_all_already_in_destination
import com.metrolist.shared.generated.resources.spotify_import_canceled
import com.metrolist.shared.generated.resources.spotify_import_error_import_failed
import com.metrolist.shared.generated.resources.spotify_import_error_no_matched_songs
import com.metrolist.shared.generated.resources.spotify_import_imported_all
import com.metrolist.shared.generated.resources.spotify_import_imported_none
import com.metrolist.shared.generated.resources.spotify_import_imported_partial
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.PlaylistImportManager.Destination
import com.metrolist.shared.playlistimport.PlaylistImportStatusManager
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.util.logging.Logger

@Composable
@Suppress("FunctionName")
internal fun DesktopAccountImportFlow(
    database: MusicDatabase,
    providerName: String,
    provider: ImportSourceType,
    logger: Logger,
    authenticationTitle: String,
    playlistLoadingTitle: String,
    noPlaylists: String,
    signInFailed: String,
    authenticationFailureLog: String,
    playlistFailureLog: String,
    onDismissRequest: () -> Unit,
    onStatus: (String) -> Unit,
    onClose: () -> Unit = {},
    loginContent: @Composable (
        onCredentials: (suspend () -> Unit, suspend () -> List<DesktopImportPlaylist>) -> Unit,
    ) -> Unit,
    playlistPicker: @Composable (
        playlists: List<DesktopImportPlaylist>,
        selectedIds: Set<String>,
        onSelectionChange: (Set<String>) -> Unit,
        onReview: () -> Unit,
        onDismissRequest: () -> Unit,
    ) -> Unit,
    loadSelectedPlaylists: suspend (
        List<DesktopImportPlaylist>,
        (current: Int, total: Int) -> Unit,
    ) -> List<SpotifyImportManager.LoadedPlaylist>,
) {
    val importFailed = stringResource(Res.string.spotify_import_error_import_failed)
    val noMatchedSongs = stringResource(Res.string.spotify_import_error_no_matched_songs)
    val importedAll = stringResource(Res.string.spotify_import_imported_all)
    val importedPartial = stringResource(Res.string.spotify_import_imported_partial)
    val importedNone = stringResource(Res.string.spotify_import_imported_none)
    val canceled = stringResource(Res.string.spotify_import_canceled)
    val allAlreadyInDestination = stringResource(Res.string.spotify_import_all_already_in_destination)

    val scope = rememberCoroutineScope()
    val playlistManager = remember(database) { PlaylistImportManager(database, GlobalYouTubeRepository.instance) }
    val playlists by database.dao().allPlaylists().collectAsState(emptyList())
    val editableSyncedPlaylists =
        playlists.filter { playlist ->
            playlist.isEditable && !playlist.isLocal && !playlist.browseId.isNullOrBlank()
        }

    var stage by remember { mutableStateOf(DesktopAccountImportStage.LOGIN) }
    var providerPlaylists by remember { mutableStateOf<List<DesktopImportPlaylist>>(emptyList()) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var previewPlaylists by remember { mutableStateOf<List<SpotifyImportManager.LoadedPlaylist>>(emptyList()) }
    var previewedId by remember { mutableStateOf("") }
    var destinationName by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf<Destination>(Destination.NewSynced) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            operationJob?.cancel()
            onClose()
        }
    }

    fun authenticate(
        authenticate: suspend () -> Unit,
        loadPlaylists: suspend () -> List<DesktopImportPlaylist>,
    ) {
        operationJob =
            scope.launch {
                val statusId =
                    PlaylistImportStatusManager.startImport(
                        playlistTitle = authenticationTitle,
                        source = providerName,
                        job = currentCoroutineContext()[Job],
                    )
                try {
                    authenticate()
                    PlaylistImportStatusManager.updateProgress(
                        statusId,
                        ImportProgressState.LoadingTracks(providerName, 0, 0),
                    )
                    providerPlaylists = loadPlaylists()
                    check(providerPlaylists.isNotEmpty()) { noPlaylists }
                    PlaylistImportStatusManager.complete(
                        statusId,
                        ImportProgressState.Completed(
                            playlistTitle = "",
                            totalSourceTracks = 0,
                            resolvedTracks = 0,
                            importedTracks = 0,
                            destinationName = "",
                            unresolvedTracks = emptyList(),
                        ),
                    )
                    selectedIds = emptySet()
                    stage = DesktopAccountImportStage.PICKER
                } catch (_: CancellationException) {
                    PlaylistImportStatusManager.cancel(statusId)
                    stage = DesktopAccountImportStage.LOGIN
                } catch (error: Exception) {
                    logger.warning("$authenticationFailureLog: ${error.message}")
                    PlaylistImportStatusManager.fail(
                        statusId,
                        ImportProgressState.Failed(
                            if (error is ImportException) {
                                resolveAccountImportError(error)
                            } else {
                                error.message
                                    ?: signInFailed
                            },
                        ),
                    )
                    stage = DesktopAccountImportStage.LOGIN
                    errorMessage = if (error is ImportException) resolveAccountImportError(error) else error.message ?: signInFailed
                } finally {
                    operationJob = null
                }
            }
    }

    fun loadSelected() {
        val selected = selectedIds.mapNotNull { id -> providerPlaylists.firstOrNull { it.id == id } }
        if (selected.isEmpty()) return
        operationJob =
            scope.launch {
                val statusId =
                    PlaylistImportStatusManager.startImport(
                        playlistTitle = playlistLoadingTitle,
                        source = providerName,
                        job = currentCoroutineContext()[Job],
                    )
                try {
                    previewPlaylists =
                        loadSelectedPlaylists(selected) { current, total ->
                            PlaylistImportStatusManager.updateProgress(
                                statusId,
                                ImportProgressState.LoadingTracks(providerName, current, total),
                            )
                        }
                    previewedId = previewPlaylists.first().uri
                    PlaylistImportStatusManager.complete(
                        statusId,
                        ImportProgressState.Completed(
                            playlistTitle = "",
                            totalSourceTracks = 0,
                            resolvedTracks = 0,
                            importedTracks = 0,
                            destinationName = "",
                            unresolvedTracks = emptyList(),
                        ),
                    )
                    stage = DesktopAccountImportStage.PREVIEW
                } catch (_: CancellationException) {
                    PlaylistImportStatusManager.cancel(statusId)
                    stage = DesktopAccountImportStage.PICKER
                } catch (error: Exception) {
                    logger.warning("$playlistFailureLog: ${error.message}")
                    PlaylistImportStatusManager.fail(
                        statusId,
                        ImportProgressState.Failed(resolveAccountImportError(error)),
                    )
                    stage = DesktopAccountImportStage.PICKER
                    errorMessage = resolveAccountImportError(error)
                } finally {
                    operationJob = null
                }
            }
    }

    val importExecutor =
        DesktopPlaylistImportExecutor(
            playlistManager = playlistManager,
            providerName = providerName,
            provider = provider,
            logger = logger,
            onDismissRequest = onDismissRequest,
            onStatus = onStatus,
            noMatchedSongs = noMatchedSongs,
            importFailed = importFailed,
            importedAll = importedAll,
            importedPartial = importedPartial,
            importedNone = importedNone,
            canceled = canceled,
            allAlreadyInDestination = allAlreadyInDestination,
        )

    when (stage) {
        DesktopAccountImportStage.LOGIN -> {
            loginContent(::authenticate)
        }

        DesktopAccountImportStage.PICKER -> {
            playlistPicker(
                providerPlaylists,
                selectedIds,
                { selectedIds = it },
                ::loadSelected,
                onDismissRequest,
            )
        }

        DesktopAccountImportStage.PREVIEW -> {
            SpotifyPlaylistPreviewDialog(
                playlists = previewPlaylists,
                selectedUri = previewedId,
                onPlaylistSelected = { previewedId = it },
                onBack = { stage = DesktopAccountImportStage.PICKER },
                onContinue = {
                    if (previewPlaylists.size == 1) {
                        destinationName = previewPlaylists.single().name
                        destination = Destination.NewSynced
                        stage = DesktopAccountImportStage.DESTINATION
                    } else {
                        importExecutor.importBatch(previewPlaylists)
                    }
                },
                onDismissRequest = onDismissRequest,
            )
        }

        DesktopAccountImportStage.DESTINATION -> {
            SpotifyDestinationDialog(
                playlistName = destinationName,
                existingPlaylists = editableSyncedPlaylists,
                selectedDestination = destination,
                onNameChange = { destinationName = it },
                onDestinationSelected = { destination = it },
                onImport = { importExecutor.importSingle(previewPlaylists, destination, destinationName) },
                onBack = { stage = DesktopAccountImportStage.PREVIEW },
                onDismissRequest = onDismissRequest,
            )
        }
    }

    PopupContent(errorMessage) { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(Res.string.spotify_import_alert_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = { errorMessage = null },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.ok)) }
            },
        )
    }
}

private enum class DesktopAccountImportStage {
    LOGIN,
    PICKER,
    PREVIEW,
    DESTINATION,
}

private suspend fun resolveAccountImportError(error: Exception): String =
    when ((error as? ImportException)?.errorKey) {
        ImportErrorKey.NoTracksToImport -> getString(Res.string.import_error_no_tracks_to_import)
        null -> error.message ?: getString(Res.string.import_error_load_failed, "")
        else -> getString(Res.string.import_error_generic)
    }
