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
import com.metrolist.shared.generated.resources.import_error_track_limit
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.generated.resources.spotify_import_alert_title
import com.metrolist.shared.generated.resources.spotify_import_all_already_in_destination
import com.metrolist.shared.generated.resources.spotify_import_canceled
import com.metrolist.shared.generated.resources.spotify_import_error_import_failed
import com.metrolist.shared.generated.resources.spotify_import_error_load_playlists
import com.metrolist.shared.generated.resources.spotify_import_error_no_matched_songs
import com.metrolist.shared.generated.resources.spotify_import_error_no_playlists
import com.metrolist.shared.generated.resources.spotify_import_error_sign_in_failed
import com.metrolist.shared.generated.resources.spotify_import_imported_all
import com.metrolist.shared.generated.resources.spotify_import_imported_none
import com.metrolist.shared.generated.resources.spotify_import_imported_partial
import com.metrolist.shared.generated.resources.spotify_import_loading_library
import com.metrolist.shared.generated.resources.spotify_import_loading_playlists
import com.metrolist.shared.generated.resources.spotify_import_skipped_playlists
import com.metrolist.shared.network.sharedHttpEngine
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.PlaylistImportManager.Destination
import com.metrolist.shared.playlistimport.PlaylistImportStatusManager
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import meow.nyx.spotlib.LogLevel
import meow.nyx.spotlib.PlaylistMeta
import meow.nyx.spotlib.SpotifyClient
import meow.nyx.spotlib.SpotifyConfig
import meow.nyx.spotlib.SpotifyLogger
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.util.logging.Logger

@Composable
internal fun DesktopSpotifyImportFlow(
    database: MusicDatabase,
    onDismissRequest: () -> Unit,
    onStatus: (String) -> Unit,
) {
    val loadingLibrary = stringResource(Res.string.spotify_import_loading_library)
    val loadingPlaylists = stringResource(Res.string.spotify_import_loading_playlists)
    val noPlaylists = stringResource(Res.string.spotify_import_error_no_playlists)
    val signInFailed = stringResource(Res.string.spotify_import_error_sign_in_failed)
    val providerName = stringResource(Res.string.import_provider_spotify)
    val loadPlaylistsFailed = stringResource(Res.string.spotify_import_error_load_playlists)
    val noMatchedSongs = stringResource(Res.string.spotify_import_error_no_matched_songs)
    val importFailed = stringResource(Res.string.spotify_import_error_import_failed)
    val importedAll = stringResource(Res.string.spotify_import_imported_all)
    val importedPartial = stringResource(Res.string.spotify_import_imported_partial)
    val importedNone = stringResource(Res.string.spotify_import_imported_none)
    val canceled = stringResource(Res.string.spotify_import_canceled)
    val allAlreadyInDestination = stringResource(Res.string.spotify_import_all_already_in_destination)
    val scope = rememberCoroutineScope()
    val logger = remember { Logger.getLogger("SpotifyImport") }
    val spotifyManager =
        remember {
            SpotifyImportManager(clientFactory = {
                SpotifyClient.create(
                    SpotifyConfig(
                        engine = sharedHttpEngine,
                        logLevel = LogLevel.SUMMARY,
                        logger = SpotifyLogger { _, message -> logger.fine(message) },
                    ),
                )
            }, loadStoredSession = { Settings.shared.spotifySession }, saveStoredSession = { Settings.shared.spotifySession = it })
        }
    val playlistManager =
        remember(database) { PlaylistImportManager(database, GlobalYouTubeRepository.instance) }
    val playlists by database.dao().allPlaylists().collectAsState(emptyList())
    val editableSyncedPlaylists =
        playlists.filter { playlist ->
            playlist.isEditable && !playlist.isLocal && !playlist.browseId.isNullOrBlank()
        }
    var stage by remember { mutableStateOf(SpotifyImportStage.LOGIN) }
    var spotifyPlaylists by remember { mutableStateOf<List<PlaylistMeta>>(emptyList()) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var previewPlaylists by remember { mutableStateOf<List<SpotifyImportManager.LoadedPlaylist>>(emptyList()) }
    var previewedUri by remember { mutableStateOf("") }
    var destinationName by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf<Destination>(Destination.NewSynced) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var authenticationFailed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(spotifyManager) {
        onDispose {
            operationJob?.cancel()
            spotifyManager.close()
        }
    }

    fun authenticate(cookie: String) {
        if (operationJob?.isActive == true) return
        authenticationFailed = false
        operationJob =
            scope.launch {
                val statusId =
                    PlaylistImportStatusManager.startImport(
                        playlistTitle = loadingLibrary,
                        source = providerName,
                        job = currentCoroutineContext()[Job],
                    )
                try {
                    spotifyManager.authenticate(cookie)
                    PlaylistImportStatusManager.updateProgress(
                        statusId,
                        ImportProgressState.LoadingTracks(providerName, 0, 0),
                    )
                    spotifyPlaylists = spotifyManager.loadPlaylists()
                    check(spotifyPlaylists.isNotEmpty()) { noPlaylists }
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
                    selectedUris = emptySet()
                    stage = SpotifyImportStage.PICKER
                } catch (_: CancellationException) {
                    PlaylistImportStatusManager.cancel(statusId)
                    authenticationFailed = true
                    stage = SpotifyImportStage.LOGIN
                } catch (error: Exception) {
                    logger.warning("Spotify authentication failed: ${error.message}")
                    PlaylistImportStatusManager.fail(
                        statusId,
                        ImportProgressState.Failed(error.message ?: signInFailed),
                    )
                    stage = SpotifyImportStage.LOGIN
                    authenticationFailed = true
                    errorMessage = error.message ?: signInFailed
                } finally {
                    operationJob = null
                }
            }
    }

    fun loadSelectedPlaylists() {
        val selected = selectedUris.mapNotNull { uri -> spotifyPlaylists.firstOrNull { it.uri == uri } }
        if (selected.isEmpty()) return
        operationJob =
            scope.launch {
                val statusId =
                    PlaylistImportStatusManager.startImport(
                        playlistTitle = loadingPlaylists,
                        source = providerName,
                        job = currentCoroutineContext()[Job],
                    )
                try {
                    val result =
                        spotifyManager.loadPlaylistsForImport(
                            selected,
                            onProgress = { current, total ->
                                PlaylistImportStatusManager.updateProgress(
                                    statusId,
                                    ImportProgressState.LoadingTracks(providerName, current, total),
                                )
                            },
                            onError = { playlist, error ->
                                logger.warning("Failed to load Spotify playlist '${playlist.name}': ${error.message}")
                            },
                        )
                    previewPlaylists = result.playlists
                    previewedUri = result.playlists.first().uri
                    if (result.failedCount > 0) {
                        val details = result.failures.joinToString("\n") { "• ${it.playlistName}: ${it.reason}" }
                        logger.warning("Skipped $result.failedCount playlists: $details")
                        val summary =
                            getPluralString(
                                Res.plurals.spotify_import_skipped_playlists,
                                result.failedCount,
                                result.failedCount,
                            )
                        errorMessage = "$summary\n\n$details"
                    }
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
                    stage = SpotifyImportStage.PREVIEW
                } catch (_: CancellationException) {
                    PlaylistImportStatusManager.cancel(statusId)
                    stage = SpotifyImportStage.PICKER
                } catch (error: Exception) {
                    logger.warning("Failed to load selected playlists: ${error.message}")
                    val message =
                        if ((error as? ImportException)?.errorKey == ImportErrorKey.TooManyTracks) {
                            getString(Res.string.import_error_track_limit, PlaylistImportManager.MAX_PLAYLIST_TRACKS)
                        } else {
                            error.message ?: loadPlaylistsFailed
                        }
                    PlaylistImportStatusManager.fail(
                        statusId,
                        ImportProgressState.Failed(message),
                    )
                    stage = if (spotifyManager.isAuthenticated) SpotifyImportStage.PICKER else SpotifyImportStage.LOGIN
                    errorMessage = message
                } finally {
                    operationJob = null
                }
            }
    }

    val importExecutor =
        DesktopPlaylistImportExecutor(
            playlistManager = playlistManager,
            providerName = providerName,
            provider = ImportSourceType.SPOTIFY,
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
        SpotifyImportStage.LOGIN -> {
            SpotifyLoginDialog(
                isAuthenticating = operationJob != null,
                authenticationFailed = authenticationFailed,
                onDismissRequest = onDismissRequest,
                onSessionCookie = ::authenticate,
            )
        }

        SpotifyImportStage.PICKER -> {
            SpotifyPlaylistPickerDialog(
                playlists = spotifyPlaylists,
                selectedUris = selectedUris,
                onSelectionChange = { selectedUris = it },
                onReview = ::loadSelectedPlaylists,
                onDismissRequest = onDismissRequest,
            )
        }

        SpotifyImportStage.PREVIEW -> {
            SpotifyPlaylistPreviewDialog(
                playlists = previewPlaylists,
                selectedUri = previewedUri,
                onPlaylistSelected = { previewedUri = it },
                onBack = { stage = SpotifyImportStage.PICKER },
                onContinue = {
                    if (previewPlaylists.size == 1) {
                        destinationName = previewPlaylists.single().name
                        destination = Destination.NewSynced
                        stage = SpotifyImportStage.DESTINATION
                    } else {
                        importExecutor.importBatch(previewPlaylists)
                    }
                },
                onDismissRequest = onDismissRequest,
            )
        }

        SpotifyImportStage.DESTINATION -> {
            SpotifyDestinationDialog(
                playlistName = destinationName,
                existingPlaylists = editableSyncedPlaylists,
                selectedDestination = destination,
                includeFavorites = true,
                onNameChange = { destinationName = it },
                onDestinationSelected = { destination = it },
                onImport = { importExecutor.importSingle(previewPlaylists, destination, destinationName) },
                onBack = { stage = SpotifyImportStage.PREVIEW },
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

private enum class SpotifyImportStage {
    LOGIN,
    PICKER,
    PREVIEW,
    DESTINATION,
}
