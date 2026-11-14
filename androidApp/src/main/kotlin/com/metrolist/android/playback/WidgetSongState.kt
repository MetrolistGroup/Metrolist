package com.metrolist.android.playback

import com.metrolist.android.widget.WidgetState
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.displayName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun widgetSongState(
    currentSong: Flow<SongItem?>,
    likedSongs: Flow<List<SongItem>>,
): Flow<Pair<SongItem?, Boolean>> =
    combine(currentSong, likedSongs) { song, likes ->
        song to (song != null && likes.any { it.id == song.id })
    }.distinctUntilChanged()

internal fun widgetPlaybackRequested(
    wantsPlayback: Flow<Boolean>,
    castState: Flow<CastState>,
): Flow<Boolean> =
    combine(wantsPlayback, castState) { local, remote ->
        remote.takeIf { it.isConnected }?.isPlaying ?: local
    }.distinctUntilChanged()

internal fun playbackWidgetState(
    song: SongItem?,
    isPlaying: Boolean,
    isLiked: Boolean,
    duration: Long,
    currentPosition: Long,
    idleTitle: String,
    idleArtist: String,
): WidgetState =
    WidgetState(
        title = song?.title ?: idleTitle,
        artist = song?.artists?.joinToString { it.displayName } ?: idleArtist,
        artworkUri = song?.thumbnail,
        isPlaying = song != null && isPlaying,
        isLiked = song != null && isLiked,
        duration = if (song != null) duration else 0,
        currentPosition = if (song != null) currentPosition else 0,
    )
