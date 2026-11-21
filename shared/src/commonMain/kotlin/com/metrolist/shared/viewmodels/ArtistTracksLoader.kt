package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.viewmodels.ArtistViewModel.ArtistUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class ArtistTracksLoader(
    private val artistId: String?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ArtistUiState>,
    private val repository: () -> YouTubeRepository = { GlobalYouTubeRepository.instance },
) {
    private var loadGeneration = 0L

    private enum class MetadataRequest {
        INITIAL,
        RETRY_AVAILABLE,
        RETRY,
        FINISHED,
    }

    private val requestedMetadata = mutableMapOf<String, MetadataRequest>()
    private val metadataRequests = Semaphore(2)
    private val sectionTokens = mutableMapOf<String, MutableSet<String>>()

    fun reset(): Set<String> {
        loadGeneration++
        sectionTokens.clear()
        return requestedMetadata.keys.toSet().also { requestedMetadata.clear() }
    }

    fun loadSection(title: String) {
        val state = uiState.value
        if (state.isLoading || title in state.loadingSections || title !in state.loadableSections) return
        val (browseId, params) = state.sectionBrowseIds[title] ?: return
        if (browseId == null) return
        val generation = loadGeneration
        val continuation = state.sectionContinuations[title]
        uiState.update { it.copy(loadingSections = it.loadingSections + title, failedSections = it.failedSections - title) }
        scope.launch {
            try {
                val (sections, next) =
                    withContext(Dispatchers.Default) {
                        if (continuation == null) {
                            repository()
                                .getBrowsePageData(
                                    browseId,
                                    params,
                                    title,
                                ).let { it.sections to it.continuation }
                        } else {
                            repository().getHomeSectionsContinuation(continuation, isPersonal = true)
                        }
                    }
                if (generation != loadGeneration) return@launch
                val seen = sectionTokens.getOrPut(title) { mutableSetOf() }
                if (continuation != null) seen += continuation
                val additions = sections.flatMap { it.items }.filterNot { it is BrowseItem }
                uiState.update { current ->
                    val unfiltered =
                        current.unfilteredSections +
                            (title to (current.unfilteredSections[title].orEmpty() + additions).distinctBy { it.id })
                    current.copy(
                        unfilteredSections = unfiltered,
                        sections = visibleArtistSections(unfiltered, artistId),
                        sectionContinuations = current.sectionContinuations + (title to next?.takeUnless { it in seen }),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == loadGeneration) uiState.update { it.copy(failedSections = it.failedSections + title) }
            } finally {
                if (generation == loadGeneration) uiState.update { it.copy(loadingSections = it.loadingSections - title) }
            }
        }
    }

    fun loadItemMetadata(items: List<YTItem>) {
        val pending =
            items.mapNotNull { item ->
                if (item !is SongItem || item.duration != null) return@mapNotNull null
                val request =
                    when (requestedMetadata[item.id]) {
                        null -> MetadataRequest.INITIAL
                        MetadataRequest.RETRY_AVAILABLE -> MetadataRequest.RETRY
                        else -> return@mapNotNull null
                    }
                requestedMetadata[item.id] = request
                item.id
            }
        if (pending.isEmpty()) return
        val generation = loadGeneration
        scope.launch {
            val updates =
                withContext(Dispatchers.Default) {
                    metadataRequests.withPermit {
                        val repository = repository()
                        repository
                            .getSongsMetadata(pending)
                            .mapNotNull { song -> song.duration?.let { song.id to it } }
                            .toMap()
                    }
                }
            if (generation != loadGeneration) return@launch
            pending.forEach { id ->
                // Let a later visibility callback retry missing metadata once, without polling or duplicate in-flight requests.
                requestedMetadata[id] =
                    if (id !in updates && requestedMetadata[id] == MetadataRequest.INITIAL) {
                        MetadataRequest.RETRY_AVAILABLE
                    } else {
                        MetadataRequest.FINISHED
                    }
            }
            uiState.update { state ->
                val sections = updateArtistSongDurations(state.unfilteredSections, updates)
                if (sections ===
                    state.unfilteredSections
                ) {
                    state
                } else {
                    state.copy(unfilteredSections = sections, sections = visibleArtistSections(sections, artistId))
                }
            }
        }
    }
}

internal fun updateArtistSongDurations(
    sections: Map<String, List<YTItem>>,
    durations: Map<String, Long>,
): Map<String, List<YTItem>> {
    if (durations.isEmpty()) return sections
    var updated: MutableMap<String, List<YTItem>>? = null
    sections.forEach { (title, entries) ->
        if (entries.none { it is SongItem && durations[it.id]?.let { duration -> duration != it.duration } == true }) return@forEach
        val songs =
            entries.map { item ->
                val duration = durations[item.id]
                if (item is SongItem && duration != null && duration != item.duration) item.copy(duration = duration) else item
            }
        val target = updated ?: sections.toMutableMap().also { updated = it }
        target[title] = songs
    }
    return updated ?: sections
}
