/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sydforum.musicplay.innertube.YouTube
import com.sydforum.musicplay.innertube.models.PlaylistItem
import com.sydforum.musicplay.constants.HideVideoSongsKey
import com.sydforum.musicplay.constants.PlaylistSongSortDescendingKey
import com.sydforum.musicplay.constants.PlaylistSongSortType
import com.sydforum.musicplay.constants.PlaylistSongSortTypeKey
import com.sydforum.musicplay.db.MusicDatabase
import com.sydforum.musicplay.db.entities.PlaylistSong
import com.sydforum.musicplay.extensions.reversed
import com.sydforum.musicplay.extensions.toEnum
import com.sydforum.musicplay.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!
    val playlist =
        database
            .playlist(playlistId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _onlinePlaylist = MutableStateFlow<PlaylistItem?>(null)
    val onlinePlaylist: StateFlow<PlaylistItem?> = _onlinePlaylist
    val playlistSongs: StateFlow<List<PlaylistSong>> =
        combine(
            database.playlistSongs(playlistId),
            context.dataStore.data
                .map {
                    Triple(
                        it[PlaylistSongSortTypeKey].toEnum(PlaylistSongSortType.CUSTOM),
                        it[PlaylistSongSortDescendingKey] ?: true,
                        it[HideVideoSongsKey] ?: false
                    )
                }.distinctUntilChanged(),
        ) { songs, (sortType, sortDescending, hideVideoSongs) ->
            val filteredSongs = if (hideVideoSongs) {
                songs.filter { !it.song.song.isVideo }
            } else {
                songs
            }
            when (sortType) {
                PlaylistSongSortType.CUSTOM -> filteredSongs
                PlaylistSongSortType.CREATE_DATE -> filteredSongs.sortedBy { it.map.id }
                PlaylistSongSortType.NAME -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs.sortedWith(compareBy(collator) { it.song.song.title })
                }
                PlaylistSongSortType.ARTIST -> {
                    val collator = Collator.getInstance(Locale.getDefault())
                    collator.strength = Collator.PRIMARY
                    filteredSongs.sortedWith(
                        compareBy<PlaylistSong, String>(collator) { song -> song.song.artists.joinToString("") { it.name } }
                            .thenBy(collator) { it.song.album?.title.orEmpty() },
                    )
                }

                PlaylistSongSortType.PLAY_TIME -> filteredSongs.sortedBy { it.song.song.totalPlayTime }
            }.reversed(sortDescending && sortType != PlaylistSongSortType.CUSTOM)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            val sortedSongs =
                playlistSongs.first().sortedWith(compareBy({ it.map.position }, { it.map.id }))
            database.transaction {
                sortedSongs.forEachIndexed { index, playlistSong ->
                    if (playlistSong.map.position != index) {
                        update(playlistSong.map.copy(position = index))
                    }
                }
            }
        }

        viewModelScope.launch {
            val localPlaylist = playlist.first { it != null }
            val browseId = localPlaylist?.playlist?.browseId
            if (browseId != null) {
                val page = withContext(Dispatchers.IO) {
                    YouTube.playlist(browseId).getOrNull()
                }
                val online = page?.playlist
                _onlinePlaylist.value = online
            }
        }
    }
}
