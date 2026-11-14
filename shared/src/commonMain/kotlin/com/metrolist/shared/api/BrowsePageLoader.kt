package com.metrolist.shared.api

import com.metrolist.innertubex.models.YouTubeDataPage
import com.metrolist.shared.model.BrowsePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.YTItem

suspend fun loadBrowsePage(
    current: BrowsePageData,
    seenTokens: Set<String>,
    loadSections: suspend (String) -> Pair<List<HomeSection>, String?>,
    loadShelf: suspend (String) -> YouTubeDataPage<YTItem>,
): BrowsePageData {
    val shelfIndex = current.sections.indexOfFirst { it.continuation != null }
    val token = if (shelfIndex >= 0) current.sections[shelfIndex].continuation!! else current.continuation ?: return current

    fun next(token: String?) =
        token?.takeUnless {
            it in seenTokens ||
                it == (if (shelfIndex >= 0) current.sections[shelfIndex].continuation else current.continuation)
        }
    return if (shelfIndex >= 0) {
        val page = loadShelf(token)
        current.copy(
            sections =
                current.sections.mapIndexed { index, section ->
                    if (index ==
                        shelfIndex
                    ) {
                        section.copy(items = section.items + page.items, continuation = next(page.continuation))
                    } else {
                        section
                    }
                },
        )
    } else {
        val (sections, nextToken) = loadSections(token)
        current.copy(sections = (current.sections + sections).take(1_000), continuation = next(nextToken))
    }
}
