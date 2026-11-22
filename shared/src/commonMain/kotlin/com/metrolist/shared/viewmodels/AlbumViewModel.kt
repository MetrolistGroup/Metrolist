package com.metrolist.shared.viewmodels

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.shouldLoadPlaylistPage
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumViewModel(
    albumId: String? = null,
    private val scope: CoroutineScope,
    private val repository: YouTubeRepository = GlobalYouTubeRepository.instance,
) {
    private val _selectedAlbumId = mutableStateOf(albumId)
    val selectedAlbumId: State<String?> = _selectedAlbumId

    private val _albumPage = mutableStateOf<AlbumPage?>(null)
    val albumPage: State<AlbumPage?> = _albumPage

    private var unfilteredAlbumPage: AlbumPage? = null

    private val _albumInfo = mutableStateOf<AlbumItem?>(null)
    val albumInfo: State<AlbumItem?> = _albumInfo

    private val _songs = mutableStateOf<List<SongItem>>(emptyList())
    val songs: State<List<SongItem>> = _songs

    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: State<String?> = _errorMessage
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private val _isLoadingMore = mutableStateOf(false)
    val isLoadingMore: State<Boolean> = _isLoadingMore
    private var loadGeneration = 0L
    private var incompleteCache = false

    init {
        scope.launch {
            BlockedContent.version.drop(1).collect {
                applyBlockedContentFilter()
            }
        }
        loadAlbum()
    }

    fun loadAlbum(
        albumId: String? = _selectedAlbumId.value,
        forceRefresh: Boolean = false,
    ) {
        if (albumId == null) return
        val generation = ++loadGeneration
        val switchingAlbum = _selectedAlbumId.value != albumId
        val retainedPage = unfilteredAlbumPage.takeIf { !switchingAlbum }
        if (switchingAlbum) {
            incompleteCache = false
            setAlbumPage(null)
        }
        _selectedAlbumId.value = albumId
        loadJob?.cancel()
        pageJob?.cancel()
        loadJob =
            scope.launch {
                _errorMessage.value = null
                val cached =
                    if (forceRefresh) {
                        null
                    } else {
                        withContext(Dispatchers.Default) {
                            repository.getCachedAlbum(albumId, applyContentFilters = false)
                        }
                    }
                if (generation != loadGeneration) return@launch
                if (cached != null) {
                    incompleteCache = !cached.isComplete
                    setAlbumPage(cached.page)
                    if (cached.canReuseWithoutRefresh) {
                        _isLoading.value = false
                        return@launch
                    }
                }
                val hasDisplayedPage = cached != null || retainedPage != null
                val reportFailure = !hasDisplayedPage || incompleteCache
                _isLoading.value = cached == null || !(cached.isComplete && cached.isOfflineReady)
                try {
                    val albumPage =
                        withContext(Dispatchers.Default) {
                            repository.getAlbum(
                                albumId,
                                loadAllPages = cached?.isComplete == true,
                                applyContentFilters = false,
                                forceRefresh = true,
                                allowOfflineFallback = !hasDisplayedPage,
                            )
                        }
                    if (generation == loadGeneration) {
                        if (albumPage != null) {
                            incompleteCache = false
                            setAlbumPage(albumPage)
                        } else if (reportFailure) {
                            if (!hasDisplayedPage) setAlbumPage(null)
                            _errorMessage.value = ""
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (generation == loadGeneration && reportFailure) {
                        _errorMessage.value = t.message.orEmpty()
                    }
                } finally {
                    if (generation == loadGeneration) _isLoading.value = false
                }
            }
    }

    fun loadMore(lastVisibleSongIndex: Int) {
        val initial = unfilteredAlbumPage ?: return
        val cursor = initial.playlistPage ?: return
        if (!cursor.hasMore || loadJob?.isActive == true || pageJob?.isActive == true ||
            (_songs.value.isNotEmpty() && !shouldLoadPlaylistPage(lastVisibleSongIndex, _songs.value.size))
        ) {
            return
        }
        val generation = loadGeneration
        pageJob =
            scope.launch {
                _isLoadingMore.value = true
                _errorMessage.value = null
                try {
                    val next =
                        withContext(Dispatchers.Default) {
                            repository.continuePlaylistPage(cursor, applyContentFilters = false)
                        }
                    if (generation == loadGeneration) setAlbumPage(initial.copy(songs = next.songs, playlistPage = next))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == loadGeneration) _errorMessage.value = e.message.orEmpty()
                } finally {
                    _isLoadingMore.value = false
                }
            }
    }

    suspend fun allSongs(): List<SongItem> {
        val initial = unfilteredAlbumPage ?: return emptyList()
        val cursor = initial.playlistPage
        if (cursor != null) {
            return withContext(Dispatchers.Default) {
                BlockedContent.filterSongs(repository.completePlaylistPage(cursor, applyContentFilters = false).songs)
            }
        }
        if (incompleteCache) {
            val albumId = _selectedAlbumId.value ?: return BlockedContent.filterSongs(initial.songs)
            val generation = loadGeneration
            val refreshed =
                withContext(Dispatchers.Default) {
                    repository.getAlbum(
                        albumId,
                        loadAllPages = true,
                        applyContentFilters = false,
                        forceRefresh = true,
                        allowOfflineFallback = false,
                    )
                } ?: error("Album load failed")
            if (generation == loadGeneration && _selectedAlbumId.value == albumId) {
                incompleteCache = false
                _errorMessage.value = null
                setAlbumPage(refreshed)
            }
            return BlockedContent.filterSongs(refreshed.songs)
        }
        return BlockedContent.filterSongs(initial.songs)
    }

    fun refresh() {
        loadAlbum(forceRefresh = true)
    }

    private fun setAlbumPage(albumPage: AlbumPage?) {
        unfilteredAlbumPage = albumPage
        val filteredPage = albumPage?.let(BlockedContent::filterAlbumPage)
        _albumPage.value = filteredPage
        _albumInfo.value = filteredPage?.album
        _songs.value = filteredPage?.songs ?: emptyList()
    }

    private fun applyBlockedContentFilter() {
        val filteredPage = unfilteredAlbumPage?.let(BlockedContent::filterAlbumPage)
        _albumPage.value = filteredPage
        _albumInfo.value = filteredPage?.album
        _songs.value = filteredPage?.songs ?: emptyList()
    }
}
