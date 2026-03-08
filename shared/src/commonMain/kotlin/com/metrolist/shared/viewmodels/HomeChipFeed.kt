package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import kotlinx.coroutines.sync.Mutex
import kotlin.random.Random

data class HomeChipFeed(
    val chip: Chip,
    val sections: List<HomeSection>,
    val continuation: String? = null,
    val requestedContinuations: Set<String> = emptySet(),
)

data class HomePaginationKey(
    val mainContinuation: String?,
    val chipContinuation: String?,
    val chipsUsed: Int,
    val generation: Long = 0,
)

class HomePaginationTrigger {
    private var lastPage: HomePaginationKey? = null
    private var lastScrollRevision = -1L

    fun shouldLoad(
        state: HomeUiState,
        nearEnd: Boolean,
        scrollRevision: Long,
    ): Boolean {
        val ready =
            nearEnd && !state.isLoading && !state.isRefreshing && !state.isRestoringCache && !state.isRevalidating &&
                !state.isContentLoading && !state.isLoadingMore && !state.loadMoreError
        return shouldLoad(state.homePaginationKey.takeIf { ready }, scrollRevision, state.isLoadingMore)
    }

    fun shouldLoad(
        page: HomePaginationKey?,
        scrollRevision: Long,
        isLoading: Boolean,
    ): Boolean {
        if (isLoading) lastScrollRevision = scrollRevision
        if (isLoading || page == null || page == lastPage || (lastPage != null && scrollRevision <= lastScrollRevision)) return false
        lastPage = page
        lastScrollRevision = scrollRevision
        return true
    }
}

private fun Chip.endpoint() = (browseId ?: "FEmusic_home") to params

internal fun HomeUiState.remainingHomeChips(): List<Chip> {
    if (selectedChip != null || continuation != null) return emptyList()
    val used = usedHomeChips.mapTo(mutableSetOf()) { it.endpoint() }
    return chips.distinctBy { it.endpoint() }.filter {
        isFilteredHome(it.params, it.browseId ?: "FEmusic_home") && it.endpoint() !in used
    }
}

internal fun HomeUiState.nextHomeChip(random: Random = Random.Default): Chip? = remainingHomeChips().randomOrNull(random)

val HomeUiState.homePaginationKey: HomePaginationKey?
    get() {
        val chipContinuation = chipFeeds.lastOrNull()?.continuation.takeIf { selectedChip == null }
        return if (continuation != null || chipContinuation != null || remainingHomeChips().isNotEmpty()) {
            HomePaginationKey(continuation, chipContinuation, usedHomeChips.size, chipFeedGeneration)
        } else {
            null
        }
    }

internal fun HomeUiState.withoutChipFeeds(): HomeUiState =
    copy(chipFeeds = emptyList(), usedHomeChips = emptyList(), chipFeedGeneration = chipFeedGeneration + 1)

internal fun HomeUiState.withChipFeed(
    chip: Chip,
    sections: List<HomeSection>,
    nextContinuation: String? = null,
): HomeUiState {
    val existing = (data.orEmpty() + discoverySections + this.sections + chipFeeds.flatMap { it.sections }).toMutableList()
    val unique =
        sections.mapNotNull { section ->
            val items = section.items.distinctBy(::canonicalHomeItemId)
            if (items.isEmpty()) return@mapNotNull null
            val itemIds = items.mapTo(mutableSetOf(), ::canonicalHomeItemId)
            val duplicate = existing.any { it.items.mapTo(mutableSetOf(), ::canonicalHomeItemId) == itemIds }
            if (duplicate) null else section.copy(items = items).also(existing::add)
        }
    val index = chipFeeds.indexOfFirst { it.chip.endpoint() == chip.endpoint() }
    val previous = chipFeeds.getOrNull(index)
    val requested = previous?.requestedContinuations.orEmpty() + listOfNotNull(previous?.continuation)
    val feed =
        HomeChipFeed(
            chip,
            previous?.sections.orEmpty() + unique,
            nextContinuation?.takeUnless { it in requested },
            requested,
        )
    return copy(
        usedHomeChips = if (previous == null) usedHomeChips + chip else usedHomeChips,
        chipFeeds = if (previous == null) chipFeeds + feed else chipFeeds.toMutableList().also { it[index] = feed },
    )
}

internal class HomeChipFeedPager(
    private val loadFirst: suspend (Chip) -> HomePageData,
    private val loadContinuation: suspend (Chip, String) -> HomePageData,
) {
    private val requestLock = Mutex()
    private var pendingChip: Chip? = null

    fun reset() {
        pendingChip = null
    }

    suspend fun loadNext(state: HomeUiState): HomeUiState? {
        if (!requestLock.tryLock()) return null
        try {
            if (state.selectedChip != null || state.continuation != null) return null
            val active = state.chipFeeds.lastOrNull()?.takeIf { it.continuation != null }
            val chip =
                active?.chip ?: pendingChip?.takeIf { pending ->
                    state.remainingHomeChips().any { it.endpoint() == pending.endpoint() }
                } ?: state.nextHomeChip() ?: return null
            pendingChip = chip
            val page = active?.continuation?.let { loadContinuation(chip, it) } ?: loadFirst(chip)
            val result = state.withChipFeed(chip, page.sections, page.continuation)
            if (page.continuation != null && result.chipFeeds.lastOrNull()?.continuation == null) {
                Logger.w("HomeChipFeedPager", "Stopped a repeated Home chip continuation")
            }
            pendingChip = null
            return result
        } finally {
            requestLock.unlock()
        }
    }
}
