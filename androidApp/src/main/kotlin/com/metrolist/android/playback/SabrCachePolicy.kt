package com.metrolist.android.playback

import androidx.media3.common.Player
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.youtube.playback.PlaybackResource

internal fun activeSabrCacheTargets(
    current: PlaybackResource?,
    currentSongId: String?,
    playbackState: Int,
    preloaded: PlaybackResource?,
    preloadedSongId: String?,
): Set<String> =
    buildSet {
        if (current?.isSabr == true && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE) {
            add(current.videoId)
            current.musicVideoId?.let(::add)
            currentSongId?.let(::add)
        }
        if (preloaded?.isSabr == true) {
            add(preloaded.videoId)
            preloaded.musicVideoId?.let(::add)
            preloadedSongId?.let(::add)
        }
    }

private const val MAX_DEFERRED_CACHE_SONGS = 32

internal suspend fun cacheWhenSabrReleased(
    song: SongItem,
    deferred: MutableMap<String, SongItem>,
    activeIds: () -> Set<String>,
    resolve: suspend () -> PlaybackResource?,
    store: suspend (PlaybackResource) -> Boolean,
) {
    fun defer() {
        deferred[song.id] = song
        while (deferred.size > MAX_DEFERRED_CACHE_SONGS) deferred.remove(deferred.keys.first())
    }

    if (song.id in activeIds()) {
        defer()
        return
    }
    val resource = resolve() ?: return
    val active = activeIds()
    if (resource.isSabr) {
        if (song.id in active || resource.videoId in active || resource.musicVideoId in active) {
            defer()
            return
        }
        defer()
    }
    if (store(resource)) deferred.remove(song.id)
}
