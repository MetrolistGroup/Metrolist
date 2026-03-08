/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.android.data.PlayHistoryRepository
import com.metrolist.shared.model.ArtistStat
import com.metrolist.shared.model.SongStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AccountViewModel(
    private val repository: PlayHistoryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            loadStats()
        }
    }

    private suspend fun loadStats() {
        repository.syncFromYouTubeHistory()
        val overview = repository.getAccountOverview()
        val artists = repository.getArtistStats(limit = 5)
        val songs = repository.getMostPlayed(limit = 5)

        val topSongs =
            songs.map { song ->
                SongStat(
                    title = song.title.ifBlank { song.songId },
                    artist = song.artists,
                    playCount = song.playCount,
                )
            }

        _uiState.update { current ->
            current.copy(
                totalSongs = overview.totalSongs,
                totalPlaylists = overview.totalPlaylists,
                totalHours = (overview.totalPlayTimeMs / 3_600_000L).toInt(),
                topArtists = artists.map { ArtistStat(it.name, it.playCount) },
                topSongs = topSongs,
            )
        }
    }

    data class AccountUiState(
        val totalSongs: Int = 0,
        val totalPlaylists: Int = 0,
        val totalHours: Int = 0,
        val topArtists: List<ArtistStat> = emptyList(),
        val topSongs: List<SongStat> = emptyList(),
    )
}
