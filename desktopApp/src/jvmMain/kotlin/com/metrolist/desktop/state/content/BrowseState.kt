/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.content

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.shared.api.loadBrowsePage
import com.metrolist.shared.model.BrowsePageData
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BrowseState {
    private var browseLoadJob: Job? = null
    var selectedBrowseId by mutableStateOf<String?>(null)
    var selectedBrowseParams by mutableStateOf<String?>(null)
    var selectedBrowseTitle by mutableStateOf<String?>(null)
    var browsePageData by mutableStateOf(BrowsePageData())
    private var unfilteredBrowsePageData = BrowsePageData()
    var isBrowseLoading by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var loadMoreFailed by mutableStateOf(false)
    private var generation = 0L
    private var pageCount = 1
    private val seenTokens = mutableSetOf<String>()
    private var setLogin = false
    val hasMore: Boolean get() =
        pageCount < 50 && unfilteredBrowsePageData.sections.size < 1_000 &&
            (unfilteredBrowsePageData.continuation != null || unfilteredBrowsePageData.sections.any { it.continuation != null })

    init {
        AppState.scope.launch {
            BlockedContent.version.drop(1).collect {
                browsePageData = unfilteredBrowsePageData.withoutBlockedContent()
            }
        }
    }

    fun fetchBrowseData(
        browseId: String,
        params: String? = null,
        title: String? = null,
        addToHistory: Boolean = true,
    ) {
        val requestGeneration = ++generation
        pageCount = 1
        seenTokens.clear()
        isLoadingMore = false
        loadMoreFailed = false
        setLogin = GlobalYouTube.innerTube.useLoginForBrowse || browseId.startsWith("FEmusic_library") ||
            browseId.startsWith("FEmusic_moods_and_genres") || browseId == "VLSE" || browseId == "VLRDPN"
        PodcastState.clear()
        selectedBrowseId = browseId
        selectedBrowseParams = params
        selectedBrowseTitle = title
        isBrowseLoading = true
        browsePageData = BrowsePageData(title = title)
        unfilteredBrowsePageData = BrowsePageData(title = title)

        PlaylistAlbumState.selectedLocalPlaylist = null
        PlaylistAlbumState.selectedPlaylistId = null
        PlaylistAlbumState.selectedAlbumId = null
        ArtistState.selectedArtistId = null

        if (addToHistory) {
            DesktopNavigation.navigate(Screen.Browse(browseId, params, title))
        }

        browseLoadJob?.cancel()
        browseLoadJob =
            AppState.scope.launch {
                try {
                    val pageData =
                        withContext(Dispatchers.IO) {
                            GlobalYouTubeRepository.instance.getBrowsePageData(browseId, params, title, loadAllPages = false)
                        }
                    if (generation == requestGeneration) {
                        unfilteredBrowsePageData = pageData
                        browsePageData = unfilteredBrowsePageData.withoutBlockedContent()
                        selectedBrowseTitle = browsePageData.title ?: title
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    YtLogger.e("BrowseState", "fetchBrowseData failed for $browseId: ${e.message}", e)
                } finally {
                    if (generation == requestGeneration) isBrowseLoading = false
                }
            }
    }

    fun loadMore() {
        if (isBrowseLoading || isLoadingMore || !hasMore) return
        val requestGeneration = generation
        val current = unfilteredBrowsePageData
        val requestLogin = setLogin
        val visited = seenTokens.toSet()
        val shelfIndex = current.sections.indexOfFirst { it.continuation != null }
        val token = if (shelfIndex >= 0) current.sections[shelfIndex].continuation!! else current.continuation ?: return
        isLoadingMore = true
        loadMoreFailed = false
        browseLoadJob =
            AppState.scope.launch {
                try {
                    val next =
                        withContext(Dispatchers.IO) {
                            loadBrowsePage(
                                current,
                                visited,
                                loadSections = {
                                    GlobalYouTubeRepository.instance.getHomeSectionsContinuation(
                                        it,
                                        isPersonal = requestLogin,
                                        loadAllPages = false,
                                    )
                                },
                                loadShelf = { GlobalYouTubeRepository.instance.getBrowseShelfContinuation(it, requestLogin) },
                            )
                        }
                    if (generation != requestGeneration) return@launch
                    seenTokens += token
                    pageCount++
                    unfilteredBrowsePageData = next
                    browsePageData = next.withoutBlockedContent()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation == requestGeneration) loadMoreFailed = true
                } finally {
                    if (generation == requestGeneration) isLoadingMore = false
                }
            }
    }

    fun clear() {
        generation++
        isLoadingMore = false
        loadMoreFailed = false
        browseLoadJob?.cancel()
        selectedBrowseId = null
        selectedBrowseParams = null
        selectedBrowseTitle = null
        browsePageData = BrowsePageData()
        unfilteredBrowsePageData = BrowsePageData()
        isBrowseLoading = false
    }

    private fun BrowsePageData.withoutBlockedContent(): BrowsePageData = copy(sections = BlockedContent.filterSections(sections))
}
