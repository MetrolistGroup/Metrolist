package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlinePodcastViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val database: MusicDatabase,
    private val syncUtils: SyncUtils,
) : ViewModel() {
    private val podcastId = savedStateHandle.get<String>("podcastId")!!

    val podcast = MutableStateFlow<PodcastItem?>(null)
    val episodes = MutableStateFlow<List<EpisodeItem>>(emptyList())

    val libraryPodcast = podcast.flatMapLatest { p ->
        p?.let { database.podcast(it.id) } ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        Timber.d("ViewModel init with podcastId: $podcastId")
        fetchPodcastData()
    }

    private fun fetchPodcastData() {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("fetchPodcastData called for: $podcastId")
            _isLoading.value = true
            _error.value = null

            YouTube.podcast(podcastId)
                .onSuccess { podcastPage ->
                    Timber.d("Success! Podcast: ${podcastPage.podcast.title}, Episodes: ${podcastPage.episodes.size}")
                    podcast.value = podcastPage.podcast
                    episodes.value = podcastPage.episodes
                    _isLoading.value = false
                }.onFailure { throwable ->
                    Timber.e(throwable, "Failed to load podcast: ${throwable.message}")
                    _error.value = throwable.message ?: "Failed to load podcast"
                    _isLoading.value = false
                    reportException(throwable)
                }
        }
    }

    /**
     * Toggle saving podcast to library.
     */
    fun toggleSubscription() {
        val currentPodcast = podcast.value ?: run {
            Timber.d("[PODCAST_TOGGLE] No podcast loaded, returning")
            return
        }
        val existingEntity = libraryPodcast.value
        val isCurrentlySaved = existingEntity?.inLibrary == true
        val shouldBeSaved = !isCurrentlySaved

        val channelId = currentPodcast.channelId ?: currentPodcast.author?.id
        Timber.d("[PODCAST_TOGGLE] toggleSubscription called: podcastId=${currentPodcast.id}, channelId=$channelId, authorId=${currentPodcast.author?.id}, isCurrentlySaved=$isCurrentlySaved, shouldBeSaved=$shouldBeSaved")

        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("[PODCAST_TOGGLE] Inside coroutine, updating database...")

            if (existingEntity != null) {
                val updated = existingEntity.toggleBookmark()
                Timber.d("[PODCAST_TOGGLE] Updating existing entity: bookmarkedAt=${updated.bookmarkedAt}")
                database.update(updated)
            } else {
                val newEntity = PodcastEntity(
                    id = currentPodcast.id,
                    title = currentPodcast.title,
                    author = currentPodcast.author?.name,
                    thumbnailUrl = currentPodcast.thumbnail,
                    channelId = channelId,
                    bookmarkedAt = LocalDateTime.now(),
                )
                Timber.d("[PODCAST_TOGGLE] Inserting new entity: ${newEntity.id}")
                database.insert(newEntity)
            }

            Timber.d("[PODCAST_TOGGLE] Database updated, calling syncUtils.savePodcast(${currentPodcast.id}, $shouldBeSaved)")
            // Sync with YouTube (handles login check internally)
            syncUtils.savePodcast(currentPodcast.id, shouldBeSaved)
        }
    }

    /**
     * Legacy method - now calls toggleSubscription
     */
    fun toggleLibrary() = toggleSubscription()

    fun retry() {
        fetchPodcastData()
    }
}
