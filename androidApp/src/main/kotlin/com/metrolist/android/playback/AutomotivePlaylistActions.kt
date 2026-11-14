package com.metrolist.android.playback

import android.content.Context
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.metrolist.android.R
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.addSongToStoredPlaylist
import kotlinx.coroutines.CancellationException
import timber.log.Timber

internal suspend fun addAutomotiveSongToPlaylist(
    context: Context,
    database: MusicDatabase,
    repository: YouTubeRepository,
    playlistId: String,
    song: SongItem?,
): SessionResult {
    if (song == null) return SessionResult(SessionError(SessionError.ERROR_BAD_VALUE, context.getString(R.string.no_song_selected)))
    return try {
        val playlist = database.dao().findPlaylistEntity(playlistId)
        if (playlistId.isBlank() || playlist == null || !playlist.isEditable) {
            return SessionResult(
                SessionError(SessionError.ERROR_BAD_VALUE, context.getString(R.string.android_auto_quick_add_playlist_not_set)),
            )
        }
        addSongToStoredPlaylist(database, repository, playlistId, song).getOrThrow()
        SessionResult(SessionResult.RESULT_SUCCESS)
    } catch (error: CancellationException) {
        throw error
    } catch (error: PlaylistSongAlreadyPresentException) {
        SessionResult(SessionError(SessionError.ERROR_BAD_VALUE, context.getString(R.string.song_already_in_playlist, song.title)))
    } catch (error: Exception) {
        Timber.e(error, "Automotive playlist addition failed")
        SessionResult(SessionError(SessionError.ERROR_IO, context.getString(R.string.playlist_update_failed)))
    }
}
