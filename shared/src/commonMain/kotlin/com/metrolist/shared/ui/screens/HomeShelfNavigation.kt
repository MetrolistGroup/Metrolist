package com.metrolist.shared.ui.screens

import com.metrolist.shared.model.HomeSection

val HomeShelf.pageKey: String
    get() =
        listOf(feedChip?.browseId ?: "FEmusic_home", feedChip?.params, key, browseId, params)
            .joinToString("") { value -> value?.let { "${it.length}:$it" } ?: "-1:" }

internal fun HomeShelf.headerNavigationAction(hasSectionPage: Boolean): HomeShelfHeaderAction =
    if (hasSectionPage && items.isNotEmpty() && style != HomeShelfStyle.MOOD_AND_GENRES) {
        HomeShelfHeaderAction.LOCAL
    } else {
        headerAction
    }

fun HomeShelf.toHomeSection(): HomeSection {
    val filteredFeed = feedChip?.let { it.params != null || (it.browseId != null && it.browseId != "FEmusic_home") } == true
    // Chip parameters belong to the feed and cannot be reused with an unrelated shelf endpoint.
    val pageBrowseId = browseId?.takeUnless { it.startsWith("UC") || (filteredFeed && params.isNullOrBlank() && continuation == null) }
    return HomeSection(
        title = title,
        items = items,
        browseId = pageBrowseId,
        params = params,
        continuation = continuation,
        isPersonal = isPersonal,
    )
}
