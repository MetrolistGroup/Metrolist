package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.mergeAndDeduplicateHomeSections

internal fun List<HomeSection>.needsHomeMoodsFallback(
    signedIn: Boolean,
    isFiltered: Boolean,
    continuation: String?,
): Boolean = signedIn && !isFiltered && continuation == null && none(HomeSection::isMoodAndGenresSection)

internal suspend fun List<HomeSection>.appendMissingHomeMoods(
    signedIn: Boolean,
    isFiltered: Boolean,
    continuation: String?,
    loadExplore: suspend () -> List<HomeSection>,
): List<HomeSection> {
    if (!needsHomeMoodsFallback(signedIn, isFiltered, continuation)) return this
    val moods =
        homeRequestResult(loadExplore)
            .getOrDefault(emptyList())
            .filter(HomeSection::isMoodAndGenresSection)
            .map { section -> section.copy(items = section.items.filterIsInstance<BrowseItem>()) }
    return if (moods.isEmpty()) this else this + mergeAndDeduplicateHomeSections(moods)
}
