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
import com.metrolist.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
        private val _cachedSongs = MutableStateFlow<List<Song>>(emptyList())
        val cachedSongs: StateFlow<List<Song>> = _cachedSongs

        init {
            viewModelScope.launch {
                while (true) {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)

                    // Candidate set: anything currently present in either cache. We no longer
                    // SET dateDownload here — that decision belongs solely to MusicService's
                    // markCachedIfFullyDownloaded(), which only fires once a track actually
                    // finishes playing naturally (MEDIA_ITEM_TRANSITION_REASON_AUTO). This loop
                    // only displays already-flagged songs and self-heals: if a song's dateDownload
                    // is set but its backing cache data is gone now (evicted, or manually removed
                    // via removeSongFromCache), the flag gets cleared so the list — and the DB —
                    // stay honest.
                    val candidateIds = playerCache.keys.toSet() + downloadCache.keys.toSet()
                    val songs =
                        if (candidateIds.isNotEmpty()) {
                            database.getSongsByIds(candidateIds.toList())
                        } else {
                            emptyList()
                        }

                    val flagged = songs.filter { it.song.dateDownload != null }
                    val stillValid = mutableListOf<Song>()

                    for (song in flagged) {
                        val contentLength = song.format?.contentLength
                        val stillCached =
                            song.song.isDownloaded ||
                                (
                                    contentLength != null &&
                                        (
                                            downloadCache.isCached(song.song.id, 0, contentLength) ||
                                                playerCache.isCached(song.song.id, 0, contentLength)
                                        )
                                )
                        if (stillCached) {
                            stillValid += song
                        } else {
                            database.query { update(song.song.copy(dateDownload = null)) }
                        }
                    }

                    _cachedSongs.value =
                        stillValid
                            .sortedByDescending { it.song.dateDownload }
                            .filterExplicit(hideExplicit)
                            .filterVideoSongs(hideVideoSongs)

                    delay(1000)
                }
            }
        }

        fun removeSongFromCache(songId: String) {
            playerCache.removeResource(songId)
        }
    }
