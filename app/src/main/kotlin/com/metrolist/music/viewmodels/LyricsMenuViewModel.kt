/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.lyrics.LyricsResult
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.component.LyricsSelectionSignals
import com.metrolist.music.utils.NetworkConnectivityObserver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LyricsMenuViewModel
@Inject
constructor(
    private val lyricsHelper: LyricsHelper,
    val database: MusicDatabase,
    private val networkConnectivity: NetworkConnectivityObserver,
) : ViewModel() {
    private var job: Job? = null
    val results = MutableStateFlow(emptyList<LyricsResult>())
    val isLoading = MutableStateFlow(false)

    private val _isNetworkAvailable = MutableStateFlow(false)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            networkConnectivity.networkStatus.collect { isConnected ->
                _isNetworkAvailable.value = isConnected
            }
        }

        _isNetworkAvailable.value = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            true // Assume connected as fallback
        }
    }

    fun search(
        mediaId: String,
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ) {
        isLoading.value = true
        results.value = emptyList()
        job?.cancel()
        job =
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    lyricsHelper.getAllLyrics(mediaId, title, artist, duration, album) { result ->
                        results.update {
                            it + result
                        }
                    }
                } finally {
                    isLoading.value = false
                }
            }
    }

    fun cancelSearch() {
        job?.cancel()
        job = null
    }

    fun refetchLyrics(
        mediaMetadata: MediaMetadata,
        lyricsEntity: LyricsEntity?,
    ) {
        // Fetch first, write only on success: deleting upfront would destroy good lyrics
        // when the fetch fails or finds nothing (failures surface as NOT_FOUND).
        // Bypass the single-result cache: refetch means force-refresh from the
        // network. Write-through stays so playback and prefetch benefit.
        viewModelScope.launch(Dispatchers.IO) {
            val fetched =
                try {
                    lyricsHelper.getLyrics(mediaMetadata, bypassCache = true)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@launch
                }
            if (fetched.lyrics == LyricsEntity.LYRICS_NOT_FOUND || fetched.lyrics.isBlank()) return@launch
            database.query {
                lyricsEntity?.let(::delete)
                upsert(LyricsEntity(mediaMetadata.id, fetched.lyrics, fetched.provider))
                // Same-text rewrites change no observable state, so the reader's
                // selection is cleared explicitly instead of via state keys.
                LyricsSelectionSignals.requestClearSelection(mediaMetadata.id)
            }
        }
    }
}
