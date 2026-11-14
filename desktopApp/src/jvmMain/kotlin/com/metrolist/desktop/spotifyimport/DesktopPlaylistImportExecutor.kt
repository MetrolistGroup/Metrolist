package com.metrolist.desktop.spotifyimport

import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.content.LibraryContentState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.favorites
import com.metrolist.shared.generated.resources.import_error_resolution_failed
import com.metrolist.shared.generated.resources.playlist_import_success
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.PlaylistImportManager.Destination
import com.metrolist.shared.playlistimport.PlaylistImportStatusManager
import com.metrolist.shared.playlistimport.UnifiedPlaylistImporter
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import com.metrolist.shared.spotifyimport.SpotifyImportOperationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.util.Locale
import java.util.logging.Logger

internal class DesktopPlaylistImportExecutor(
    private val playlistManager: PlaylistImportManager,
    private val providerName: String,
    private val provider: ImportSourceType,
    private val logger: Logger,
    private val onDismissRequest: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val noMatchedSongs: String,
    private val importFailed: String,
    private val importedAll: String,
    private val importedPartial: String,
    private val importedNone: String,
    private val canceled: String,
    private val allAlreadyInDestination: String,
) {
    fun importBatch(sourcePlaylists: List<SpotifyImportManager.LoadedPlaylist>) {
        onDismissRequest()
        AppState.scope.launch {
            if (!SpotifyImportOperationGate.tryStart()) return@launch
            var importedPlaylists = 0
            var importedSongs = 0
            var failedPlaylists = 0
            try {
                sourcePlaylists.forEachIndexed { index, playlist ->
                    val statusId =
                        PlaylistImportStatusManager.startImport(
                            playlistTitle = playlist.name,
                            source = "$providerName (${index + 1}/${sourcePlaylists.size})",
                            job = currentCoroutineContext()[Job],
                        )

                    try {
                        val tracks =
                            playlist.tracks.map {
                                PlaylistImportManager.CsvTrack(
                                    it.title,
                                    it.artist,
                                    null,
                                    album = it.album,
                                    durationSeconds = it.durationSeconds,
                                )
                            }
                        val matches =
                            playlistManager.resolveImportTracks(
                                tracks,
                                PlaylistImportManager.Destination.NewSynced,
                                provider,
                            ) { resolved, total, _ ->
                                PlaylistImportStatusManager.updateProgress(
                                    statusId,
                                    ImportProgressState.ResolvingTracks(
                                        resolved = resolved,
                                        total = total,
                                        currentTrackTitle = tracks.getOrNull(resolved)?.title.orEmpty(),
                                        currentTrackArtist = tracks.getOrNull(resolved)?.artist.orEmpty(),
                                    ),
                                )
                            }
                        check(matches.isNotEmpty()) { noMatchedSongs }

                        PlaylistImportStatusManager.updateProgress(
                            statusId,
                            ImportProgressState.SavingToLibrary(0, matches.size),
                        )
                        val result =
                            playlistManager
                                .importResolvedTracks(
                                    playlist.name,
                                    matches,
                                    PlaylistImportManager.Destination.NewSynced,
                                    provider,
                                    coverUrl = playlist.image,
                                ) { imported, total, _ ->
                                    PlaylistImportStatusManager.updateProgress(
                                        statusId,
                                        ImportProgressState.SavingToLibrary(imported, total),
                                    )
                                }.getOrThrow()
                        importedPlaylists++
                        importedSongs += result.importedSongCount
                        PlaylistImportStatusManager.complete(
                            statusId,
                            ImportProgressState.Completed(
                                playlistTitle = playlist.name,
                                totalSourceTracks = playlist.tracks.size,
                                resolvedTracks = matches.size,
                                importedTracks = result.importedSongCount,
                                destinationName = playlist.name,
                                unresolvedTracks = UnifiedPlaylistImporter.unresolvedTracks(tracks, matches),
                            ),
                        )
                    } catch (error: CancellationException) {
                        PlaylistImportStatusManager.finishCancellation(statusId, error)
                        throw error
                    } catch (error: Exception) {
                        logger.warning(
                            "Failed to import playlist '${playlist.name}' (${index + 1}/${sourcePlaylists.size}): ${error.message}",
                        )
                        (error as? PlaylistImportManager.IncompleteImportException)?.let {
                            importedSongs += it.importedSongCount
                        }
                        PlaylistImportStatusManager.fail(
                            statusId,
                            ImportProgressState.Failed(importFailureMessage(error)),
                        )
                        failedPlaylists++
                    }
                }
                if (importedPlaylists > 0 || importedSongs > 0) LibraryContentState.fetchLibraryData(force = true)
                onStatus(
                    when {
                        importedPlaylists == sourcePlaylists.size -> {
                            formatResource(importedAll, importedPlaylists, importedSongs)
                        }

                        importedPlaylists > 0 -> {
                            formatResource(importedPartial, importedPlaylists, sourcePlaylists.size, importedSongs, failedPlaylists)
                        }

                        else -> {
                            importedNone
                        }
                    },
                )
            } catch (_: CancellationException) {
                if (importedPlaylists > 0) {
                    LibraryContentState.fetchLibraryData(force = true)
                    onStatus(formatResource(canceled, importedPlaylists, sourcePlaylists.size))
                }
            } finally {
                SpotifyImportOperationGate.finish()
            }
        }
    }

    fun importSingle(
        sourcePlaylists: List<SpotifyImportManager.LoadedPlaylist>,
        destination: Destination,
        name: String,
    ) {
        val source = sourcePlaylists.singleOrNull() ?: return
        onDismissRequest()
        AppState.scope.launch {
            if (!SpotifyImportOperationGate.tryStart()) return@launch
            val statusId =
                PlaylistImportStatusManager.startImport(
                    playlistTitle = source.name,
                    source = providerName,
                    job = currentCoroutineContext()[Job],
                )
            try {
                val tracks =
                    source.tracks.map {
                        PlaylistImportManager.CsvTrack(it.title, it.artist, null, album = it.album, durationSeconds = it.durationSeconds)
                    }
                val destinationName =
                    when (destination) {
                        Destination.Favorites -> getString(Res.string.favorites)
                        is Destination.EXISTING -> destination.playlist.name
                        else -> name
                    }
                val matches =
                    playlistManager.resolveImportTracks(tracks, destination, provider) { resolved, total, _ ->
                        PlaylistImportStatusManager.updateProgress(
                            statusId,
                            ImportProgressState.ResolvingTracks(
                                resolved = resolved,
                                total = total,
                                currentTrackTitle = tracks.getOrNull(resolved)?.title.orEmpty(),
                                currentTrackArtist = tracks.getOrNull(resolved)?.artist.orEmpty(),
                            ),
                        )
                    }
                check(matches.isNotEmpty()) { noMatchedSongs }
                PlaylistImportStatusManager.updateProgress(
                    statusId,
                    ImportProgressState.SavingToLibrary(0, matches.size),
                )
                val result =
                    playlistManager
                        .importResolvedTracks(destinationName, matches, destination, provider) { imported, total, _ ->
                            PlaylistImportStatusManager.updateProgress(
                                statusId,
                                ImportProgressState.SavingToLibrary(imported, total),
                            )
                        }.getOrThrow()
                LibraryContentState.fetchLibraryData(force = true)
                PlaylistImportStatusManager.complete(
                    statusId,
                    ImportProgressState.Completed(
                        playlistTitle = source.name,
                        totalSourceTracks = source.tracks.size,
                        resolvedTracks = matches.size,
                        importedTracks = result.importedSongCount,
                        destinationName = destinationName,
                        unresolvedTracks = UnifiedPlaylistImporter.unresolvedTracks(tracks, matches),
                    ),
                )
                onStatus(
                    if (result.importedSongCount == 0) {
                        allAlreadyInDestination
                    } else {
                        getPluralString(
                            Res.plurals.playlist_import_success,
                            result.importedSongCount,
                            result.importedSongCount,
                        )
                    },
                )
            } catch (error: CancellationException) {
                PlaylistImportStatusManager.finishCancellation(statusId, error)
            } catch (error: Exception) {
                logger.warning("Failed to import playlist '$name': ${error.message}")
                PlaylistImportStatusManager.fail(
                    statusId,
                    ImportProgressState.Failed(importFailureMessage(error)),
                )
                (error as? PlaylistImportManager.IncompleteImportException)?.let {
                    if (it.importedSongCount > 0) LibraryContentState.fetchLibraryData(force = true)
                }
                onStatus(importFailureMessage(error))
            } finally {
                SpotifyImportOperationGate.finish()
            }
        }
    }

    private suspend fun importFailureMessage(error: Exception): String =
        if (error is ImportException && error.errorKey == ImportErrorKey.ResolutionFailed) {
            getString(Res.string.import_error_resolution_failed)
        } else {
            error.message ?: importFailed
        }

    private fun formatResource(
        template: String,
        vararg values: Any,
    ) = String.format(Locale.getDefault(), template, *values)
}
