package com.metrolist.android.playlistimport

import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun preparePlaylistBatch(
    playlists: List<SpotifyImportManager.LoadedPlaylist>,
    source: String,
    provider: ImportSourceType,
): List<ImportedPlaylistData> =
    playlists.map { playlist ->
        ImportedPlaylistData(
            title = playlist.name,
            tracks =
                playlist.tracks.map {
                    PlaylistImportManager.CsvTrack(it.title, it.artist, null, album = it.album, durationSeconds = it.durationSeconds)
                },
            source = source,
            provider = provider,
            coverUrl = playlist.image,
        )
    }

internal fun preparePlaylistPreview(
    playlist: SpotifyImportManager.LoadedPlaylist?,
    fallbackTitle: String,
    source: String,
    provider: ImportSourceType,
): ImportedPlaylistData =
    if (playlist == null) {
        ImportedPlaylistData(fallbackTitle, emptyList(), source = source, provider = provider, sourcePlaylistCount = 0)
    } else {
        preparePlaylistBatch(listOf(playlist), source, provider).single()
    }

internal data class PlaylistImportBatchResult(
    val completed: List<ImportProgressState.Completed>,
    val failures: List<Pair<String, Throwable>>,
    val importedTracks: Int,
)

internal suspend fun executePlaylistBatch(
    playlists: List<ImportedPlaylistData>,
    title: String,
    onStateUpdate: (ImportProgressState) -> Unit,
    importPlaylist: suspend (ImportedPlaylistData, (ImportProgressState) -> Unit) -> Result<ImportProgressState.Completed>,
): PlaylistImportBatchResult {
    val completed = mutableListOf<ImportProgressState.Completed>()
    val failures = mutableListOf<Pair<String, Throwable>>()
    var importedTracks = 0
    for (playlist in playlists) {
        try {
            currentCoroutineContext().ensureActive()
            val result =
                importPlaylist(playlist) { state ->
                    // Keep the service request running until every playlist has been attempted.
                    if (state !is ImportProgressState.Completed && state !is ImportProgressState.Failed &&
                        state !is ImportProgressState.Cancelled
                    ) {
                        onStateUpdate(state)
                    }
                }.getOrThrow()
            completed += result
            importedTracks += result.importedTracks
        } catch (error: CancellationException) {
            val saved = importedTracks + ((error as? PlaylistImportManager.CancelledImportException)?.importedSongCount ?: 0)
            if (saved > 0) onStateUpdate(ImportProgressState.Cancelled(saved, title))
            throw error
        } catch (error: Exception) {
            importedTracks += (error as? PlaylistImportManager.IncompleteImportException)?.importedSongCount ?: 0
            failures += playlist.title to error
        }
    }
    return PlaylistImportBatchResult(completed, failures, importedTracks)
}
