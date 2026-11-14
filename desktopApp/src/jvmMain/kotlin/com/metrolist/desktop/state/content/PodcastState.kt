package com.metrolist.desktop.state.content

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.unable_to_load_podcast
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastPage
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.PodcastLibrary
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.viewmodels.PodcastPager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext

object PodcastState {
    private var loadJob: Job? = null
    private var pager: PodcastPager? = null
    var selectedPodcastId by mutableStateOf<String?>(null)
    var page by mutableStateOf<PodcastPage?>(null)
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun fetch(
        podcastId: String,
        addToHistory: Boolean = true,
    ) {
        if (addToHistory) DesktopNavigation.navigate(Screen.Podcast(podcastId))
        PlayerState.exitExpanded()
        selectedPodcastId = podcastId
        ArtistState.selectedArtistId = null
        PlaylistAlbumState.selectedPlaylistId = null
        PlaylistAlbumState.selectedAlbumId = null
        BrowseState.clear()
        loadJob?.cancel()
        pager?.cancel()
        val loader = PodcastPager(AppState.scope, GlobalYouTubeRepository.instance.podcastPageLoader(podcastId)).also { pager = it }
        page = null
        isLoading = true
        error = null
        loadJob =
            AppState.scope.launch {
                launch {
                    try {
                        val database = GlobalContext.get().get<MusicDatabase>()
                        database.dao().savedPodcastEpisodesByCreateDateAsc().collect { songs ->
                            loader.updateSavedEpisodes(
                                songs.map {
                                    EpisodeItem(id = it.id, title = it.title, thumbnail = it.thumbnailUrl, saved = true)
                                },
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Logger.w("PodcastState", "Saved episode membership loading failed", failure)
                    }
                }
                loader.state.collect { state ->
                    page = state.page
                    isLoading = state.isLoading
                    error = state.error?.let { getString(Res.string.unable_to_load_podcast) }
                }
            }
        loader.loadMore()
    }

    fun loadMore() {
        pager?.loadMore()
    }

    fun retry() {
        pager?.retry()
    }

    fun playAll() {
        val current = page ?: return
        AppState.scope.launch {
            val complete =
                if (current.continuation == null) {
                    current
                } else {
                    withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.getPodcast(current.podcast.id) }
                }
            val songs = complete?.episodes?.map { it.toSongItem(current.podcast.title) }.orEmpty()
            songs.firstOrNull()?.let { PlayerState.playTrack(it, songs) }
        }
    }

    fun togglePodcastSaved() {
        val current = page ?: return
        AppState.scope.launch {
            val saved = !current.saved
            if (GlobalYouTubeRepository.instance.setPodcastSaved(current.podcast.id, saved)) {
                val latest = page?.takeIf { it.podcast.id == current.podcast.id } ?: return@launch
                pager?.updatePage { latest.copy(saved = saved) }
            }
        }
    }

    fun toggleEpisodeSaved(episode: EpisodeItem) {
        val current = page ?: return
        AppState.scope.launch {
            val database = GlobalContext.get().get<MusicDatabase>()
            PodcastLibrary.setEpisodeSaved(database, episode, !episode.saved).onSuccess { updated ->
                val latest = page?.takeIf { it.podcast.id == current.podcast.id } ?: return@onSuccess
                pager?.updatePage { latest.copy(episodes = latest.episodes.map { if (it.id == updated.id) updated else it }) }
            }
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
        pager?.cancel()
        pager = null
        isLoading = false
        selectedPodcastId = null
        page = null
        error = null
    }
}
