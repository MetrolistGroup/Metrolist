package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.AlbumLikeState
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.viewmodels.AlbumViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class AlbumViewModel(
    val albumId: String? = null,
    private val database: MusicDatabase,
    private val downloadManager: SongDownloadManager,
    private val applicationScope: CoroutineScope,
    repository: YouTubeRepository = GlobalYouTubeRepository.instance,
) : ViewModel() {
    private val sharedViewModel =
        AlbumViewModel(albumId, viewModelScope, repository)

    val albumPage = sharedViewModel.albumPage
    val albumInfo = sharedViewModel.albumInfo
    val songs = sharedViewModel.songs
    val isLoading = sharedViewModel.isLoading
    val isLoadingMore = sharedViewModel.isLoadingMore
    val errorMessage = sharedViewModel.errorMessage
    val selectedAlbumId = sharedViewModel.selectedAlbumId
    private val _isFavorite = MutableStateFlow(false)
    val isFavorite = _isFavorite.asStateFlow()
    private var favoriteJob: Job? = null

    init {
        albumId?.let(::refreshFavorite)
    }

    fun loadAlbum() = sharedViewModel.loadAlbum()

    fun loadAlbum(albumId: String) {
        sharedViewModel.loadAlbum(albumId)
        refreshFavorite(albumId)
    }

    fun refresh() = sharedViewModel.refresh()

    fun loadMore(lastVisibleSongIndex: Int) = sharedViewModel.loadMore(lastVisibleSongIndex)

    suspend fun allSongs(): List<SongItem> = sharedViewModel.allSongs()

    fun toggleFavorite(
        album: AlbumItem,
        songs: List<SongItem>,
    ) {
        if (savingFavorite) return
        savingFavorite = true
        val cursor = albumPage.value?.takeIf { it.album.id == album.id }?.playlistPage
        AlbumLikeState.toggleAlbumLike(album, songs) { result ->
            savingFavorite = false
            result
                .onSuccess { liked ->
                    if (selectedAlbumId.value == album.id) _isFavorite.value = liked
                    if (shouldAutoDownloadLikedAlbum(liked, Settings.shared.autoDownloadOnLike, songs.size)) {
                        applicationScope.launch(Dispatchers.IO) {
                            try {
                                val allSongs = cursor?.let { GlobalYouTubeRepository.instance.completePlaylistPage(it).songs } ?: songs
                                downloadManager.downloadAll(allSongs)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w("Unable to load album for automatic download type=%s", e::class.simpleName)
                            }
                        }
                    }
                }.onFailure {
                    _favoriteFailed.value = true
                }
        }
    }

    private var savingFavorite = false
    private val _favoriteFailed = MutableStateFlow(false)
    val favoriteFailed = _favoriteFailed.asStateFlow()

    fun consumeFavoriteFailure() {
        _favoriteFailed.value = false
    }

    private fun refreshFavorite(albumId: String) {
        favoriteJob?.cancel()
        favoriteJob =
            viewModelScope.launch(Dispatchers.IO) {
                database.dao().album(albumId).collect { album ->
                    _isFavorite.value = album?.album?.bookmarkedAt != null
                }
            }
    }
}

internal fun shouldAutoDownloadLikedAlbum(
    isLiked: Boolean,
    autoDownloadOnLike: Boolean,
    songCount: Int,
): Boolean = isLiked && autoDownloadOnLike && songCount > 0
