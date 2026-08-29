/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.metrolist.music.constants.SongSortType
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.Album
import javax.inject.Inject

@HiltViewModel
class OnlinePlaylistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val database: MusicDatabase
) : ViewModel() {
    private val playlistId = savedStateHandle.get<String>("playlistId")!!

    // Check if this is a special podcast playlist (with or without VL prefix)
    private val normalizedPlaylistId = playlistId.removePrefix("VL")
    val isPodcastPlaylist = normalizedPlaylistId == "RDPN" || normalizedPlaylistId == "SE"

    val playlist = MutableStateFlow<PlaylistItem?>(null)
    val playlistSongs = MutableStateFlow<List<SongItem>>(emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore = _isLoadingMore.asStateFlow()

    val dbPlaylist = database.playlistByBrowseId(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    var continuation: String? = null
        private set

    private var proactiveLoadJob: Job? = null

    init {
        fetchInitialPlaylistData()
    }

    private fun fetchInitialPlaylistData() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            continuation = null
            proactiveLoadJob?.cancel() // Cancel any ongoing proactive load

            if (isPodcastPlaylist) {
                // Use special podcast playlist APIs
                fetchPodcastPlaylist()
            } else {
                // Use regular playlist API
                fetchRegularPlaylist()
            }
        }
    }

    private suspend fun fetchPodcastPlaylist() {
        when (normalizedPlaylistId) {
            "RDPN" -> {
                YouTube.newEpisodes()
                    .onSuccess { episodes ->
                        playlist.value = PlaylistItem(
                            id = playlistId,
                            title = "New Episodes",
                            author = null,
                            songCountText = "${episodes.size} episodes",
                            thumbnail = episodes.firstOrNull()?.thumbnail ?: "",
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        )
                        playlistSongs.value = applySongFilters(episodes)
                        _isLoading.value = false
                    }.onFailure { throwable ->
                        _error.value = throwable.message ?: "Failed to load new episodes"
                        _isLoading.value = false
                        reportException(throwable)
                    }
            }
            "SE" -> {
                timber.log.Timber.d("[SE_LOCAL] Fetching SE playlist...")
                val result = YouTube.episodesForLater()
                val episodes = result.getOrNull() ?: emptyList()
                timber.log.Timber.d("[SE_LOCAL] YouTube API result: ${if (result.isSuccess) "success" else "failed"}, ${episodes.size} episodes")

                if (result.isSuccess && episodes.isNotEmpty()) {
                    // Use YouTube episodes
                    playlist.value = PlaylistItem(
                        id = playlistId,
                        title = "Episodes for Later",
                        author = null,
                        songCountText = "${episodes.size} episodes",
                        thumbnail = episodes.firstOrNull()?.thumbnail ?: "",
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    )
                    playlistSongs.value = applySongFilters(episodes)
                    _isLoading.value = false
                } else {
                    // Fall back to local saved episodes when API fails or returns empty
                    timber.log.Timber.d("[SE_LOCAL] Falling back to local saved episodes")
                    loadLocalSavedEpisodes()
                }
            }
            else -> {
                _error.value = "Unknown podcast playlist"
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchRegularPlaylist() {
        YouTube.playlist(playlistId)
            .onSuccess { playlistPage ->
                playlist.value = playlistPage.playlist
                playlistSongs.value = applySongFilters(playlistPage.songs)
                continuation = playlistPage.songsContinuation
                _isLoading.value = false
                if (continuation != null) {
                    startProactiveBackgroundLoading()
                }
            }.onFailure { throwable ->
                _error.value = throwable.message ?: "Failed to load playlist"
                _isLoading.value = false
                reportException(throwable)
            }
    }

    private suspend fun loadLocalSavedEpisodes() {
        timber.log.Timber.d("[SE_LOCAL] loadLocalSavedEpisodes called")
        val savedEpisodes = database.savedPodcastEpisodes(SongSortType.CREATE_DATE, true).firstOrNull() ?: emptyList()
        timber.log.Timber.d("[SE_LOCAL] Found ${savedEpisodes.size} saved episodes")
        savedEpisodes.forEachIndexed { index, ep ->
            timber.log.Timber.d("[SE_LOCAL] Episode $index: id=${ep.song.id}, title=${ep.song.title}, isEpisode=${ep.song.isEpisode}, inLibrary=${ep.song.inLibrary}")
        }
        if (savedEpisodes.isNotEmpty()) {
            // Convert local Song entities to SongItem format
            val songItems = savedEpisodes.map { song ->
                SongItem(
                    id = song.song.id,
                    title = song.song.title,
                    artists = song.artists.map { Artist(it.id, it.name) },
                    album = song.album?.let { com.metrolist.innertube.models.Album(it.id, it.title) },
                    duration = song.song.duration,
                    thumbnail = song.song.thumbnailUrl ?: "",
                    explicit = song.song.explicit,
                    endpoint = null,
                )
            }
            timber.log.Timber.d("[SE_LOCAL] Converted to ${songItems.size} SongItems")
            playlist.value = PlaylistItem(
                id = playlistId,
                title = "Episodes for Later",
                author = null,
                songCountText = "${songItems.size} episodes",
                thumbnail = songItems.firstOrNull()?.thumbnail ?: "",
                playEndpoint = null,
                shuffleEndpoint = null,
                radioEndpoint = null,
            )
            val filtered = applySongFilters(songItems)
            timber.log.Timber.d("[SE_LOCAL] After filter: ${filtered.size} episodes, setting playlistSongs")
            playlistSongs.value = filtered
            _isLoading.value = false
            timber.log.Timber.d("[SE_LOCAL] Done, isLoading=false")
        } else {
            timber.log.Timber.d("[SE_LOCAL] No saved episodes found")
            _error.value = "No saved episodes"
            _isLoading.value = false
        }
    }

    private fun startProactiveBackgroundLoading() {
        proactiveLoadJob?.cancel() // Cancel previous job if any
        proactiveLoadJob = viewModelScope.launch(Dispatchers.IO) {
            var currentProactiveToken = continuation
            while (currentProactiveToken != null && isActive) {
                // If a manual loadMore is happening, pause proactive loading
                if (_isLoadingMore.value) {
                    // Wait until manual load is finished, then re-evaluate
                    // This simple break and restart strategy from loadMoreSongs is preferred
                    break 
                }

                YouTube.playlistContinuation(currentProactiveToken)
                    .onSuccess { playlistContinuationPage ->
                        val currentSongs = playlistSongs.value.toMutableList()
                        currentSongs.addAll(playlistContinuationPage.songs)
                        playlistSongs.value = applySongFilters(currentSongs)
                        currentProactiveToken = playlistContinuationPage.continuation
                        // Update the class-level continuation for manual loadMore if needed
                        this@OnlinePlaylistViewModel.continuation = currentProactiveToken 
                    }.onFailure { throwable ->
                        reportException(throwable)
                        currentProactiveToken = null // Stop proactive loading on error
                    }
            }
            // If loop finishes because currentProactiveToken is null, all songs are loaded proactively.
        }
    }

    fun loadMoreSongs() {
        if (_isLoadingMore.value) return // Already loading more (manually)
        
        val tokenForManualLoad = continuation ?: return // No more songs to load

        proactiveLoadJob?.cancel() // Cancel proactive loading to prioritize manual scroll
        _isLoadingMore.value = true

        viewModelScope.launch(Dispatchers.IO) {
            YouTube.playlistContinuation(tokenForManualLoad)
                .onSuccess { playlistContinuationPage ->
                    val currentSongs = playlistSongs.value.toMutableList()
                    currentSongs.addAll(playlistContinuationPage.songs)
                    playlistSongs.value = applySongFilters(currentSongs)
                    continuation = playlistContinuationPage.continuation
                }.onFailure { throwable ->
                    reportException(throwable)
                }.also {
                    _isLoadingMore.value = false
                    // Resume proactive loading if there's still a continuation
                    if (continuation != null && isActive) {
                        startProactiveBackgroundLoading()
                    }
                }
        }
    }

    fun retry() {
        proactiveLoadJob?.cancel()
        fetchInitialPlaylistData() // This will also restart proactive loading if applicable
    }

    private fun applySongFilters(songs: List<SongItem>): List<SongItem> {
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        return songs
            .distinctBy { it.id }
            .filterVideoSongs(hideVideoSongs)
    }

    override fun onCleared() {
        super.onCleared()
        proactiveLoadJob?.cancel()
    }
}
