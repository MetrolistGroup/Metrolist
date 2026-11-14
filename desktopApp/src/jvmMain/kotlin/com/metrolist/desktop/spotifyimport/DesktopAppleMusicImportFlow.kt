package com.metrolist.desktop.spotifyimport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.apple_music_login_failed
import com.metrolist.shared.generated.resources.import_error_empty_playlist
import com.metrolist.shared.generated.resources.import_provider_apple_music
import com.metrolist.shared.generated.resources.import_status_fetching_metadata
import com.metrolist.shared.playlistimport.AppleMusicPlaylistImporter
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import meow.nyx.spotlib.PlaylistMeta
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.util.logging.Logger

@Composable
@Suppress("FunctionName")
internal fun DesktopAppleMusicImportFlow(
    database: MusicDatabase,
    onDismissRequest: () -> Unit,
    onStatus: (String) -> Unit,
) {
    val providerName = stringResource(Res.string.import_provider_apple_music)
    val fetchingMetadata = stringResource(Res.string.import_status_fetching_metadata)
    val noPlaylists = stringResource(Res.string.import_error_empty_playlist)
    val signInFailed = stringResource(Res.string.apple_music_login_failed)
    val logger = remember { Logger.getLogger("AppleMusicImport") }
    var userToken by remember { mutableStateOf<String?>(null) }
    var appleMusicPlaylists by remember { mutableStateOf(emptyList<PlaylistMeta>()) }

    DesktopAccountImportFlow(
        database = database,
        providerName = providerName,
        provider = ImportSourceType.APPLE_MUSIC,
        logger = logger,
        authenticationTitle = fetchingMetadata,
        playlistLoadingTitle = fetchingMetadata,
        noPlaylists = noPlaylists,
        signInFailed = signInFailed,
        authenticationFailureLog = "Apple Music authentication/load failed",
        playlistFailureLog = "Failed to load selected Apple Music playlists",
        onDismissRequest = onDismissRequest,
        onStatus = onStatus,
        loginContent = { onCredentials ->
            AppleMusicLoginDialog(onDismissRequest) { token ->
                userToken = token
                onCredentials(
                    {},
                    {
                        appleMusicPlaylists = AppleMusicPlaylistImporter.loadUserPlaylists(token).getOrThrow()
                        appleMusicPlaylists.map { DesktopImportPlaylist(it.uri, it.name, it.image, it.trackCount) }
                    },
                )
            }
        },
        playlistPicker = { _, selectedIds, onSelectionChange, onReview, dismiss ->
            SpotifyPlaylistPickerDialog(
                title = providerName,
                playlists = appleMusicPlaylists,
                selectedUris = selectedIds,
                onSelectionChange = onSelectionChange,
                onReview = onReview,
                onDismissRequest = dismiss,
            )
        },
        loadSelectedPlaylists = { selected, onProgress ->
            val token = requireNotNull(userToken)
            buildList {
                selected.forEachIndexed { index, option ->
                    val meta = appleMusicPlaylists.firstOrNull { it.uri == option.id } ?: return@forEachIndexed
                    onProgress(index + 1, selected.size)
                    val importedData =
                        AppleMusicPlaylistImporter
                            .loadUserPlaylistTracks(
                                userToken = token,
                                playlistUriOrId = meta.uri,
                                playlistName = meta.name,
                                onProgress = { loaded, total -> onProgress(loaded, total ?: 0) },
                            ).getOrThrow()
                    add(
                        SpotifyImportManager.LoadedPlaylist(
                            uri = meta.uri,
                            name = meta.name,
                            image = meta.image,
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
