/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sydforum.musicplay.constants.HideVideoSongsKey
import com.sydforum.musicplay.constants.MyTopFilter
import com.sydforum.musicplay.db.MusicDatabase
import com.sydforum.musicplay.utils.dataStore
import java.time.LocalDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TopPlaylistViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val top = savedStateHandle.get<String>("top")!!

    val topPeriod = MutableStateFlow(MyTopFilter.ALL_TIME)

    @OptIn(ExperimentalCoroutinesApi::class)
    val topSongs =
        combine(
            topPeriod,
            context.dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged()
        ) { period, hideVideoSongs -> period to hideVideoSongs }
            .flatMapLatest { (period, hideVideoSongs) ->
                val now = LocalDateTime.now()
                database.mostPlayedSongs(
                    fromTimeStamp = period.toLocalDateTime(),
                    limit = top.toInt(),
                    offset = 0,
                    toTimeStamp = now
                ).map { songs ->
                    if (hideVideoSongs) songs.filter { !it.song.isVideo } else songs
                }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
