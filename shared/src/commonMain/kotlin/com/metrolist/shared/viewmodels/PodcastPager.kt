package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PodcastPagingState(
    val page: PodcastPage? = null,
    val isLoading: Boolean = false,
    val error: Throwable? = null,
)

class PodcastPager(
    private val scope: CoroutineScope,
    private val pages: (PodcastPage?) -> Flow<PodcastPage>,
) {
    private val mutableState = MutableStateFlow(PodcastPagingState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var cancelled = false
    private val consumedTokens = mutableSetOf<String>()
    private var savedEpisodes: Map<String, EpisodeItem>? = null

    fun loadMore() {
        if (cancelled || state.value.isLoading || state.value.error != null) return
        load()
    }

    fun retry() {
        if (cancelled || state.value.isLoading) return
        load()
    }

    private fun load() {
        val initial = state.value.page
        if (initial != null && initial.continuation == null) return
        mutableState.update { it.copy(isLoading = true, error = null) }
        job =
            scope.launch {
                try {
                    val next = pages(initial).drop(if (initial == null) 0 else 1).firstOrNull()
                    initial?.continuation?.let(consumedTokens::add)
                    mutableState.update { current ->
                        val page =
                            next?.copy(
                                episodes = (current.page?.episodes.orEmpty() + next.episodes).distinctBy { it.id },
                                saved = current.page?.saved ?: next.saved,
                                continuations = next.continuations.filter { it.isNotBlank() && it !in consumedTokens },
                            ) ?: current.page?.copy(continuations = emptyList())
                        check(page != null) { "Podcast returned no first page" }
                        current.copy(page = applySaved(page), isLoading = false)
                    }
                } catch (failure: CancellationException) {
                    cancelled = true
                    throw failure
                } catch (failure: Exception) {
                    mutableState.update { it.copy(isLoading = false, error = failure) }
                } finally {
                    mutableState.update { it.copy(isLoading = false) }
                }
            }
    }

    fun updatePage(transform: (PodcastPage) -> PodcastPage) {
        mutableState.update { it.copy(page = it.page?.let(transform)) }
    }

    fun updateSavedEpisodes(episodes: List<EpisodeItem>) {
        savedEpisodes = episodes.associateBy { it.id }
        updatePage(::applySaved)
    }

    private fun applySaved(page: PodcastPage): PodcastPage {
        val saved = savedEpisodes ?: return page
        return page.copy(
            episodes =
                page.episodes.map { episode ->
                    episode.copy(
                        saved = episode.id in saved,
                        playlistSetVideoId =
                            saved[episode.id]?.playlistSetVideoId ?: episode.playlistSetVideoId,
                    )
                },
        )
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
    }
}
