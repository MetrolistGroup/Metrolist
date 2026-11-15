@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.import_action_choose_file
import com.metrolist.shared.generated.resources.import_action_start
import com.metrolist.shared.generated.resources.import_cancel
import com.metrolist.shared.generated.resources.import_cancelled_partial
import com.metrolist.shared.generated.resources.import_default_imported_playlist_title
import com.metrolist.shared.generated.resources.import_destination_favorites
import com.metrolist.shared.generated.resources.import_duplicate_playlist_import_anyway
import com.metrolist.shared.generated.resources.import_duplicate_playlist_message
import com.metrolist.shared.generated.resources.import_duplicate_playlist_skip
import com.metrolist.shared.generated.resources.import_duplicate_playlist_title
import com.metrolist.shared.generated.resources.import_error_empty_input
import com.metrolist.shared.generated.resources.import_error_file_read_failed
import com.metrolist.shared.generated.resources.import_error_generic
import com.metrolist.shared.generated.resources.import_error_incomplete_source
import com.metrolist.shared.generated.resources.import_error_invalid_youtube_url
import com.metrolist.shared.generated.resources.import_error_local_files_require_local_playlist
import com.metrolist.shared.generated.resources.import_error_no_library_matches
import com.metrolist.shared.generated.resources.import_error_no_tracks_recognized
import com.metrolist.shared.generated.resources.import_error_no_tracks_to_import
import com.metrolist.shared.generated.resources.import_error_resolution_failed
import com.metrolist.shared.generated.resources.import_error_track_limit
import com.metrolist.shared.generated.resources.import_error_unsupported_playlist_entries
import com.metrolist.shared.generated.resources.import_error_youtube_dedicated_flow
import com.metrolist.shared.generated.resources.import_source_text_file
import com.metrolist.shared.generated.resources.import_source_youtube_music
import com.metrolist.shared.generated.resources.import_status_failed
import com.metrolist.shared.generated.resources.import_status_fetching_metadata
import com.metrolist.shared.generated.resources.import_status_fetching_tracks
import com.metrolist.shared.generated.resources.import_status_matching_tracks_short
import com.metrolist.shared.generated.resources.import_status_saving_tracks
import com.metrolist.shared.generated.resources.import_tracks_found
import com.metrolist.shared.generated.resources.load_playlist
import com.metrolist.shared.generated.resources.local_playlist
import com.metrolist.shared.generated.resources.or_choose_existing_playlist
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.generated.resources.playlist_import_success
import com.metrolist.shared.generated.resources.playlist_name
import com.metrolist.shared.generated.resources.playlist_url
import com.metrolist.shared.generated.resources.spotify_import_create_new_playlist
import com.metrolist.shared.generated.resources.track
import com.metrolist.shared.playlistimport.CsvPlaylist
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportFallbackTitles
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.PlaylistImportStatusManager
import com.metrolist.shared.playlistimport.UnifiedPlaylistImporter
import com.metrolist.shared.playlistimport.UniversalPlaylistParser
import com.metrolist.shared.playlistimport.decodePlaylistText
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.menu.CsvColumnMappingDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

class PlaylistImportFile(
    val name: String,
    val bytes: ByteArray,
)

@Composable
@Suppress("FunctionName")
fun IosPlaylistImportSettings(
    database: MusicDatabase,
    modifier: Modifier = Modifier,
    onPickPlaylistFile: (suspend () -> PlaylistImportFile?)? = null,
    scrollable: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val repository = remember { GlobalYouTubeRepository.instance }
    val manager = remember(database, repository) { PlaylistImportManager(database, repository) }
    val playlists by database.dao().allPlaylists().collectAsState(emptyList())
    val editablePlaylists = playlists.filter { it.isEditable }

    var input by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<PlaylistImportManager.CsvTrack>>(emptyList()) }
    var pendingCsv by remember { mutableStateOf<CsvPlaylist?>(null) }
    var sourceYouTubePlaylistId by remember { mutableStateOf<String?>(null) }
    var coverUrl by remember { mutableStateOf<String?>(null) }
    var provider by remember { mutableStateOf<ImportSourceType?>(null) }
    var destination by remember { mutableStateOf<PlaylistImportManager.Destination>(PlaylistImportManager.Destination.NewLocal) }
    var progress by remember { mutableStateOf<ImportProgressState>(ImportProgressState.Idle) }
    var operation by remember { mutableStateOf<Job?>(null) }
    IosKeepScreenOnEffect(operation != null)
    var duplicate by remember { mutableStateOf<ImportProgressState.DuplicateDetected?>(null) }
    val defaultImportedPlaylistTitle = stringResource(Res.string.import_default_imported_playlist_title)
    val textFileSource = stringResource(Res.string.import_source_text_file)
    val youtubeMusicSource = stringResource(Res.string.import_source_youtube_music)
    val fallbackTitles =
        PlaylistImportFallbackTitles(
            playlist = stringResource(Res.string.playlist),
            importedPlaylist = defaultImportedPlaylistTitle,
            album = stringResource(Res.string.album),
            track = stringResource(Res.string.track),
        )

    DisposableEffect(Unit) {
        onDispose { operation?.cancel() }
    }

    fun loadPlaylist(file: PlaylistImportFile? = null) {
        operation?.cancel()
        operation =
            scope.launch {
                val currentJob = currentCoroutineContext()[Job]
                tracks = emptyList()
                pendingCsv = null
                provider = null
                sourceYouTubePlaylistId = null
                coverUrl = null
                title = ""
                progress = ImportProgressState.FetchingMetadata(file?.name ?: input)
                try {
                    val data =
                        if (file != null) {
                            val data =
                                UniversalPlaylistParser.prepareFile(
                                    decodePlaylistText(file.bytes),
                                    file.name,
                                    file.name.substringBeforeLast('.', file.name).ifBlank { defaultImportedPlaylistTitle },
                                    textFileSource,
                                )
                            data.copy(
                                tracks =
                                    UniversalPlaylistParser.importableTracks(data.tracks).also {
                                        if (it.isEmpty() && data.csv == null) throw ImportException(ImportErrorKey.NoTracksRecognized)
                                    },
                            )
                        } else {
                            val value = input.trim()
                            val playlistId = PlaylistImportManager.parsePlaylistId(value)
                            if (playlistId != null && UnifiedPlaylistImporter.isYouTubePlaylistInput(value)) {
                                val loaded = manager.loadYouTubePlaylist(playlistId, fallbackTitles.importedPlaylist).getOrThrow()
                                val (title, songs, coverUrl) = loaded
                                ImportedPlaylistData(
                                    title = title,
                                    tracks =
                                        songs.map { song ->
                                            PlaylistImportManager.CsvTrack(
                                                song.title,
                                                song.artists.joinToString(", ") { it.name },
                                                song.id,
                                            )
                                        },
                                    source = youtubeMusicSource,
                                    sourceYouTubePlaylistId = playlistId,
                                    coverUrl = coverUrl,
                                )
                            } else {
                                UnifiedPlaylistImporter
                                    .parseFromInput(value, fallbackTitles) { loaded, total ->
                                        progress = ImportProgressState.LoadingTracks("", loaded, total)
                                    }.getOrThrow()
                            }
                        }
                    tracks = data.tracks
                    pendingCsv = data.csv
                    provider = data.provider
                    sourceYouTubePlaylistId = data.sourceYouTubePlaylistId
                    coverUrl = data.coverUrl
                    title = data.title
                    progress =
                        if (data.csv == null) {
                            ImportProgressState.LoadingTracks(data.source, data.tracks.size, data.tracks.size)
                        } else {
                            ImportProgressState.Idle
                        }
                } catch (error: CancellationException) {
                    if (operation === currentJob) progress = ImportProgressState.Idle
                    throw error
                } catch (error: Exception) {
                    if (operation ===
                        currentJob
                    ) {
                        progress =
                            ImportProgressState.Failed(
                                error.message.orEmpty(),
                                error,
                                (error as? ImportException)?.errorKey ?: ImportErrorKey.Generic,
                            )
                    }
                } finally {
                    if (operation === currentJob) operation = null
                }
            }
    }

    fun startImport(
        target: PlaylistImportManager.Destination,
        skipDuplicateCheck: Boolean = false,
    ) {
        duplicate = null
        val statusId = PlaylistImportStatusManager.startImport(title, provider?.name ?: title)
        operation =
            scope.launch {
                val currentJob = checkNotNull(currentCoroutineContext()[Job])
                PlaylistImportStatusManager.attachJob(statusId, currentJob)
                try {
                    if (!skipDuplicateCheck) {
                        val existing = UnifiedPlaylistImporter.checkForDuplicate(database, title, target)
                        if (existing != null) {
                            duplicate = existing
                            PlaylistImportStatusManager.clear(statusId)
                            return@launch
                        }
                    }
                    UnifiedPlaylistImporter
                        .executeImport(
                            manager,
                            title,
                            tracks,
                            target,
                            provider,
                            sourceYouTubePlaylistId,
                            coverUrl,
                        ) {
                            progress = it
                            PlaylistImportStatusManager.updateProgress(statusId, it)
                        }.getOrThrow()
                } catch (error: CancellationException) {
                    if (operation === currentJob && progress !is ImportProgressState.Cancelled) progress = ImportProgressState.Idle
                    if (progress !is ImportProgressState.Cancelled) PlaylistImportStatusManager.clear(statusId)
                } catch (error: Exception) {
                    if (operation ===
                        currentJob
                    ) {
                        val failed =
                            ImportProgressState.Failed(
                                error.message.orEmpty(),
                                error,
                                (error as? ImportException)?.errorKey ?: ImportErrorKey.Generic,
                            )
                        progress = failed
                        PlaylistImportStatusManager.fail(
                            statusId,
                            failed,
                        )
                    }
                } finally {
                    if (operation === currentJob) operation = null
                }
            }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.playlist_url)) },
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { loadPlaylist() }, enabled = operation == null && input.isNotBlank(), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.load_playlist))
            }
            if (onPickPlaylistFile != null) {
                TextButton(
                    onClick = {
                        operation =
                            scope.launch {
                                val currentJob = currentCoroutineContext()[Job]
                                try {
                                    val file = onPickPlaylistFile()
                                    if (operation === currentJob) operation = null
                                    if (file != null) loadPlaylist(file)
                                } catch (error: CancellationException) {
                                    if (operation === currentJob) progress = ImportProgressState.Idle
                                    throw error
                                } catch (error: Exception) {
                                    if (operation ===
                                        currentJob
                                    ) {
                                        progress =
                                            ImportProgressState.Failed(error.message.orEmpty(), error, ImportErrorKey.FileReadFailed)
                                    }
                                } finally {
                                    if (operation === currentJob) operation = null
                                }
                            }
                    },
                    enabled = operation == null,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.import_action_choose_file))
                }
            }
        }

        if (tracks.isNotEmpty()) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.playlist_name)) },
                singleLine = true,
            )
            Text(stringResource(Res.string.import_tracks_found, tracks.size), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.or_choose_existing_playlist), style = MaterialTheme.typography.titleSmall)
            DestinationChoice(
                selected = destination == PlaylistImportManager.Destination.NewLocal,
                label = stringResource(Res.string.local_playlist),
                onSelect = { destination = PlaylistImportManager.Destination.NewLocal },
            )
            DestinationChoice(
                selected = destination == PlaylistImportManager.Destination.NewSynced,
                label = stringResource(Res.string.spotify_import_create_new_playlist),
                onSelect = { destination = PlaylistImportManager.Destination.NewSynced },
            )
            DestinationChoice(
                selected = destination == PlaylistImportManager.Destination.Favorites,
                label = stringResource(Res.string.import_destination_favorites),
                onSelect = { destination = PlaylistImportManager.Destination.Favorites },
            )
            editablePlaylists.forEach { playlist ->
                DestinationChoice(
                    selected = (destination as? PlaylistImportManager.Destination.EXISTING)?.playlist?.id == playlist.id,
                    label = playlist.name,
                    onSelect = { destination = PlaylistImportManager.Destination.EXISTING(playlist) },
                )
            }
            Button(
                onClick = { startImport(destination) },
                enabled = operation == null && title.isNotBlank() && tracks.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(Res.string.import_action_start))
            }
        }

        if (operation != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoadingIndicator()
                TextButton(onClick = { operation?.cancel() }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.import_cancel))
                }
            }
        }
        ImportProgressMessage(progress)
    }

    pendingCsv?.let { csv ->
        CsvColumnMappingDialog(
            csv = csv,
            onDismiss = { pendingCsv = null },
            onConfirm = { mapping ->
                pendingCsv = null
                try {
                    tracks = UniversalPlaylistParser.importableTracks(csv.tracks(mapping))
                    if (tracks.isEmpty()) throw ImportException(ImportErrorKey.NoTracksRecognized)
                    progress = ImportProgressState.LoadingTracks(textFileSource, tracks.size, tracks.size)
                } catch (error: Exception) {
                    progress =
                        ImportProgressState.Failed(
                            error.message.orEmpty(),
                            error,
                            (error as? ImportException)?.errorKey ?: ImportErrorKey.Generic,
                        )
                }
            },
        )
    }

    duplicate?.let { detected ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text(stringResource(Res.string.import_duplicate_playlist_title)) },
            text = {
                Text(stringResource(Res.string.import_duplicate_playlist_message, detected.existingPlaylistName, detected.trackCount))
            },
            confirmButton = {
                TextButton(onClick = { startImport(destination, skipDuplicateCheck = true) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.import_duplicate_playlist_import_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicate = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.import_duplicate_playlist_skip))
                }
            },
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun DestinationChoice(
    selected: Boolean,
    label: String,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
@Suppress("FunctionName")
private fun ImportProgressMessage(state: ImportProgressState) {
    val message =
        when (state) {
            ImportProgressState.Idle -> {
                null
            }

            is ImportProgressState.FetchingMetadata -> {
                stringResource(Res.string.import_status_fetching_metadata)
            }

            is ImportProgressState.LoadingTracks -> {
                stringResource(
                    Res.string.import_status_fetching_tracks,
                    state.loaded,
                    state.total?.toString() ?: "…",
                )
            }

            is ImportProgressState.ResolvingTracks -> {
                stringResource(
                    Res.string.import_status_matching_tracks_short,
                    state.resolved,
                    state.total,
                )
            }

            is ImportProgressState.SavingToLibrary -> {
                stringResource(Res.string.import_status_saving_tracks, state.saved, state.total)
            }

            is ImportProgressState.Cancelled -> {
                stringResource(Res.string.import_cancelled_partial, state.saved, state.destinationName)
            }

            is ImportProgressState.Completed -> {
                pluralStringResource(Res.plurals.playlist_import_success, state.importedTracks, state.importedTracks)
            }

            is ImportProgressState.Failed -> {
                when (state.errorKey) {
                    ImportErrorKey.EmptyInput -> {
                        stringResource(Res.string.import_error_empty_input)
                    }

                    ImportErrorKey.InvalidYouTubeUrl -> {
                        stringResource(Res.string.import_error_invalid_youtube_url)
                    }

                    ImportErrorKey.UnsupportedPlaylistEntries -> {
                        stringResource(Res.string.import_error_unsupported_playlist_entries)
                    }

                    ImportErrorKey.LocalFilesRequireLocalPlaylist -> {
                        stringResource(Res.string.import_error_local_files_require_local_playlist)
                    }

                    ImportErrorKey.TooManyTracks -> {
                        stringResource(Res.string.import_error_track_limit, PlaylistImportManager.MAX_PLAYLIST_TRACKS)
                    }

                    ImportErrorKey.FileReadFailed -> {
                        stringResource(Res.string.import_error_file_read_failed)
                    }

                    ImportErrorKey.NoTracksRecognized -> {
                        stringResource(Res.string.import_error_no_tracks_recognized)
                    }

                    ImportErrorKey.NoTracksToImport -> {
                        stringResource(Res.string.import_error_no_tracks_to_import)
                    }

                    ImportErrorKey.ResolutionFailed -> {
                        stringResource(Res.string.import_error_resolution_failed)
                    }

                    ImportErrorKey.IncompleteSource -> {
                        stringResource(Res.string.import_error_incomplete_source)
                    }

                    ImportErrorKey.NoLibraryMatches -> {
                        stringResource(Res.string.import_error_no_library_matches)
                    }

                    ImportErrorKey.YouTubeDedicatedFlow -> {
                        stringResource(Res.string.import_error_youtube_dedicated_flow)
                    }

                    ImportErrorKey.Generic -> {
                        stringResource(Res.string.import_error_generic)
                    }

                    null -> {
                        stringResource(Res.string.import_status_failed)
                    }
                }
            }

            is ImportProgressState.DuplicateDetected -> {
                stringResource(
                    Res.string.import_duplicate_playlist_message,
                    state.existingPlaylistName,
                    state.trackCount,
                )
            }
        }
    if (message != null) {
        Text(
            message,
            color =
                if (state is ImportProgressState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}
