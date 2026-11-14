package com.metrolist.shared.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.loadBrowsePage
import com.metrolist.shared.model.BrowsePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BrowseViewModel(
    private val browseId: String?,
    private val params: String?,
    private val title: String?,
    private val scope: CoroutineScope,
    private val repository: YouTubeRepository = GlobalYouTubeRepository.instance,
    private val initialSection: HomeSection? = null,
) {
    var page by mutableStateOf(BrowsePageData(title = title, sections = listOfNotNull(initialSection)))
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var loadFailed by mutableStateOf(false)
        private set
    var loadMoreFailed by mutableStateOf(false)
        private set
    private var loadJob: Job? = null
    private var generation = 0L
    private var pageCount = 1
    private val seenTokens = mutableSetOf<String>()
    private val updatedSongs = mutableMapOf<String, SongItem>()
    private var setLogin = false
    val hasMore: Boolean get() =
        pageCount < 50 && page.sections.size < 1_000 &&
            (page.continuation != null || page.sections.any { it.continuation != null })

    init {
        load()
    }

    fun load() {
        val requestGeneration = ++generation
        loadJob?.cancel()
        pageCount = 1
        seenTokens.clear()
        setLogin = initialSection?.isPersonal == true || repository.browseUsesLogin(browseId)
        if (initialSection?.continuation != null || browseId == null) {
            page = applySongMetadata(BrowsePageData(title = title, sections = listOfNotNull(initialSection)))
            isLoading = false
            isLoadingMore = false
            loadFailed = false
            loadMoreFailed = false
            loadMore()
            return
        }
        isLoading = true
        isLoadingMore = false
        loadFailed = false
        loadMoreFailed = false
        loadJob =
            scope.launch {
                try {
                    val loaded = repository.getBrowsePageData(browseId, params, title, loadAllPages = false)
                    if (generation == requestGeneration) page = applySongMetadata(loaded)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (generation == requestGeneration) loadFailed = true
                } finally {
                    if (generation == requestGeneration) isLoading = false
                }
            }
    }

    fun updateSongMetadata(song: SongItem) {
        updatedSongs[song.id] = song
        page = applySongMetadata(page)
    }

    fun loadMore() {
        if (isLoading || isLoadingMore || !hasMore) return
        val requestGeneration = generation
        val current = page
        val token = current.sections.firstOrNull { it.continuation != null }?.continuation ?: current.continuation ?: return
        val requestLogin = setLogin
        val visited = seenTokens.toSet()
        isLoadingMore = true
        loadMoreFailed = false
        loadJob =
            scope.launch {
                try {
                    val next =
                        loadBrowsePage(
                            current,
                            visited,
                            loadSections = { repository.getHomeSectionsContinuation(it, isPersonal = requestLogin, loadAllPages = false) },
                            loadShelf = { repository.getBrowseShelfContinuation(it, requestLogin) },
                        )
                    if (generation != requestGeneration) return@launch
                    seenTokens += token
                    pageCount++
                    page = applySongMetadata(next)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (generation == requestGeneration) loadMoreFailed = true
                } finally {
                    if (generation == requestGeneration) isLoadingMore = false
                }
            }
    }

    private fun applySongMetadata(page: BrowsePageData): BrowsePageData =
        if (updatedSongs.isEmpty()) {
            page
        } else {
            page.copy(
                sections =
                    page.sections.map { section ->
                        section.copy(
                            items =
                                section.items.map { item ->
                                    if (item is SongItem) updatedSongs[item.id]?.let(item::withUpdatedMetadata) ?: item else item
                                },
                        )
                    },
            )
        }
}
