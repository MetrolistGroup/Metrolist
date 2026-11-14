package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.ui.screens.LibraryFilter
import com.metrolist.shared.ui.screens.LibrarySortType
import com.metrolist.shared.ui.screens.prepareLibraryItems
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LibraryPresentationInput(
    val items: List<YTItem>,
    val filter: LibraryFilter?,
    val hiddenItemKeys: Set<HiddenLibraryItemKey>,
    val sortType: LibrarySortType,
    val sortDirection: SortDirection,
    val manualOrder: String,
    val contentFilterVersion: Int,
    val searchQuery: String,
    val contentMatchedSongs: List<SongItem>,
)

class LibraryPresentation(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var input: LibraryPresentationInput? = null
    private var items: List<YTItem>? = null

    internal fun cached(input: LibraryPresentationInput): List<YTItem>? = items.takeIf { this.input == input }

    internal suspend fun prepare(input: LibraryPresentationInput): List<YTItem> {
        cached(input)?.let { return it }
        val prepared =
            withContext(dispatcher) {
                val blockedContent = BlockedContent.snapshot()
                prepareLibraryItems(
                    BlockedContent.filterItems(input.items, blockedContent),
                    input.filter,
                    input.hiddenItemKeys,
                    input.sortType,
                    input.manualOrder,
                    input.sortDirection,
                    input.searchQuery,
                    BlockedContent.filterSongs(input.contentMatchedSongs, blockedContent),
                )
            }
        this.input = input
        items = prepared
        return prepared
    }
}
