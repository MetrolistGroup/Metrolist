/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.Cache
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.extensions.filterExplicit
import com.metrolist.music.extensions.filterVideoSongs
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CachePlaylistViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        @PlayerCache private val playerCache: Cache,
        @DownloadCache private val downloadCache: Cache,
    ) : ViewModel() {
        private data class Inputs(
            val flagged: List<Song>,
            val hideExplicit: Boolean,
            val hideVideoSongs: Boolean,
        )

        @OptIn(ExperimentalCoroutinesApi::class)
        val cachedSongs: StateFlow<List<Song>> =
            combine(
                database.cachePlaylistSongs(),
                context.dataStore.data.map { it[HideExplicitKey] ?: false }.distinctUntilChanged(),
                context.dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged(),
                ::Inputs,
            ).mapLatest { (flagged, hideExplicit, hideVideoSongs) ->
                val partition = partitionCachedSongs(flagged) { songId, contentLength ->
                    downloadCache.isCached(songId, 0, contentLength) ||
                        playerCache.isCached(songId, 0, contentLength)
                }

                // Clearing the flag removes these songs from cachePlaylistSongs(), so this
                // re-emits once and then settles rather than looping.
                partition.stale.forEach { song ->
                    database.query { update(song.song.copy(dateDownload = null)) }
                }

                partition.stillCached
                    .sortedByDescending { it.song.dateDownload }
                    .filterExplicit(hideExplicit)
                    .filterVideoSongs(hideVideoSongs)
            }.flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun removeSongFromCache(songId: String) {
            playerCache.removeResource(songId)

            // Dropping the bytes does not touch the database, so nothing would invalidate
            // cachePlaylistSongs() and the song would linger in the list until the screen is
            // re-entered. Re-check this one song against the same rules and clear its flag here.
            database.query {
                val song = getSongByIdBlocking(songId) ?: return@query
                val partition = partitionCachedSongs(listOf(song)) { id, contentLength ->
                    downloadCache.isCached(id, 0, contentLength) ||
                        playerCache.isCached(id, 0, contentLength)
                }
                partition.stale.forEach { update(it.song.copy(dateDownload = null)) }
            }
        }
    }

/**
 * Result of checking which Cache Playlist entries still have their bytes on disk.
 */
internal data class CachedSongPartition(
    val stillCached: List<Song>,
    val stale: List<Song>,
)

/**
 * Splits songs flagged as belonging to the Cache Playlist into those whose data is still
 * present and those whose flag must be cleared.
 *
 * A downloaded song is always considered present: its file is managed by the download service
 * rather than the player cache. A song whose content length is unknown cannot be checked, so it
 * is treated as stale rather than assumed present.
 *
 * [isCached] receives the song id and its content length, and reports whether the complete file
 * is available in either cache.
 */
internal fun partitionCachedSongs(
    flagged: List<Song>,
    isCached: (songId: String, contentLength: Long) -> Boolean,
): CachedSongPartition {
    val stillCached = mutableListOf<Song>()
    val stale = mutableListOf<Song>()

    for (song in flagged) {
        val contentLength = song.format?.contentLength
        val present = song.song.isDownloaded ||
            (contentLength != null && isCached(song.song.id, contentLength))
        if (present) stillCached += song else stale += song
    }

    return CachedSongPartition(stillCached, stale)
}
