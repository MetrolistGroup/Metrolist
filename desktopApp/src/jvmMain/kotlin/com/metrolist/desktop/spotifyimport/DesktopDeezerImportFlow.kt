package com.metrolist.desktop.spotifyimport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.deezerimport.DeezerImportManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_deezer_picker_description
import com.metrolist.shared.generated.resources.import_deezer_picker_title
import com.metrolist.shared.generated.resources.import_deezer_selected_count
import com.metrolist.shared.generated.resources.import_provider_deezer
import com.metrolist.shared.generated.resources.import_status_fetching_metadata
import com.metrolist.shared.generated.resources.import_status_fetching_tracks
import com.metrolist.shared.generated.resources.liked_songs
import com.metrolist.shared.generated.resources.spotify_import_error_no_playlists
import com.metrolist.shared.generated.resources.spotify_import_error_sign_in_failed
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.util.logging.Logger

@Composable
@Suppress("FunctionName")
internal fun DesktopDeezerImportFlow(
    database: MusicDatabase,
    onDismissRequest: () -> Unit,
    onStatus: (String) -> Unit,
) {
    val providerName = stringResource(Res.string.import_provider_deezer)
    val fetchingMetadata = stringResource(Res.string.import_status_fetching_metadata)
    val fetchingTracks = stringResource(Res.string.import_status_fetching_tracks)
    val noPlaylists = stringResource(Res.string.spotify_import_error_no_playlists)
    val signInFailed = stringResource(Res.string.spotify_import_error_sign_in_failed)
    val logger = remember { Logger.getLogger("DeezerImport") }
    val manager = remember { DeezerImportManager() }
    var deezerPlaylists by remember { mutableStateOf(emptyList<DeezerImportManager.LoadedPlaylist>()) }

    DesktopAccountImportFlow(
        database = database,
        providerName = providerName,
        provider = ImportSourceType.DEEZER,
        logger = logger,
        authenticationTitle = fetchingMetadata,
        playlistLoadingTitle = fetchingTracks,
        noPlaylists = noPlaylists,
        signInFailed = signInFailed,
        authenticationFailureLog = "Deezer authentication failed",
        playlistFailureLog = "Failed to load selected Deezer playlists",
        onDismissRequest = onDismissRequest,
        onStatus = onStatus,
        onClose = manager::close,
        loginContent = { onCredentials ->
            DeezerLoginDialog(onDismissRequest) { arl ->
                onCredentials(
                    { manager.authenticate(arl) },
                    {
                        deezerPlaylists = manager.loadPlaylists(likedSongsName = getString(Res.string.liked_songs))
                        deezerPlaylists.map { DesktopImportPlaylist(it.id, it.name, it.image, it.trackCount) }
                    },
                )
            }
        },
        playlistPicker = { playlists, selectedIds, onSelectionChange, onReview, dismiss ->
            DesktopAccountPlaylistPickerDialog(
                title = Res.string.import_deezer_picker_title,
                description = Res.string.import_deezer_picker_description,
                selectedCount = Res.string.import_deezer_selected_count,
                playlists = playlists,
                selectedIds = selectedIds,
                onSelectionChange = onSelectionChange,
                onReview = onReview,
                onDismissRequest = dismiss,
            )
        },
        loadSelectedPlaylists = { selected, onProgress ->
            buildList {
                for ((index, option) in selected.withIndex()) {
                    val playlist = deezerPlaylists.firstOrNull { it.id == option.id } ?: continue
                    onProgress(index + 1, selected.size)
                    val importedData =
                        manager.loadPlaylist(playlist, likedSongsName = getString(Res.string.liked_songs)) { loaded, total ->
                            onProgress(loaded, total ?: 0)
                        }
                    add(
                        SpotifyImportManager.LoadedPlaylist(
                            uri = playlist.id,
                            name = playlist.name,
                            image = playlist.image,
                            tracks =
                                importedData.tracks.map {
                                    SpotifyImportManager.ImportTrack(
                                        it.title,
                                        it.artist,
                                        it.album,
                                        it.durationSeconds,
                                    )
                                },
                            reachedTrackLimit = false,
                        ),
                    )
                }
            }
        },
    )
}
