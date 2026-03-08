package com.metrolist.shared.model

fun HomeSection.isDesktopHomeDiscoverySection(userPlaylistIds: Set<String> = emptySet()): Boolean {
    if (!hasTitle || browseId?.startsWith("FEmusic_library") == true) return false
    if (isLibrarySection(userPlaylistIds)) return false
    return true
}

fun HomeSection.isLibrarySection(userPlaylistIds: Set<String> = emptySet()): Boolean {
    if (browseId == "VLSE") return true
    if (browseId?.contains("library", ignoreCase = true) == true) return true
    if (browseId?.startsWith("FEmusic_liked", ignoreCase = true) == true) return true
    if (browseId?.startsWith("FEmusic_history", ignoreCase = true) == true) return true
    if (isPersonal && items.isNotEmpty() && items.all { it is PlaylistItem }) return true
    if (items.isNotEmpty() && items.all { it is PlaylistItem }) {
        if (browseId?.startsWith("FEmusic_community") == true ||
            browseId?.startsWith("FEmusic_charts") == true ||
            browseId?.startsWith("FEmusic_new_releases") == true ||
            browseId?.startsWith("FEmusic_listening_review") == true ||
            browseId?.startsWith("FEmusic_immersive") == true
        ) {
            return false
        }
        if (items.any { (it as? PlaylistItem)?.isEditable == true }) return true
        if (userPlaylistIds.isNotEmpty()) {
            val canonicalPlaylistIds = userPlaylistIds.mapTo(mutableSetOf(), ::speedDialItemId)
            if (items.any { canonicalHomeItemId(it) in canonicalPlaylistIds }) return true
        }
    }
    return false
}

fun List<HomeSection>.withoutRegionalCharts(signedIn: Boolean): List<HomeSection> =
    if (signedIn) filterNot { it.browseId?.startsWith("FEmusic_charts") == true } else this

fun canonicalHomeItemId(item: YTItem): String {
    if (item is PlaylistItem) BuiltinPlaylist.fromId(item.id)?.let { return it.id }
    if (item is BrowseItem && !item.params.isNullOrBlank()) {
        return "${item.id}_${item.params}"
    }
    return item.id.removePrefix("VL").removePrefix("MPREb_")
}

fun mergeAndDeduplicateHomeSections(
    sections: List<HomeSection>,
    userPlaylistIds: Set<String> = emptySet(),
): List<HomeSection> {
    if (sections.isEmpty()) return emptyList()

    val result = mutableListOf<HomeSection>()
    var firstLibraryIndex = -1
    val seenBrowseIds = mutableMapOf<String, Int>()
    val seenTitles = mutableMapOf<String, Int>()

    for (section in sections) {
        val uniqueItems = section.items.distinctBy(::canonicalHomeItemId)
        if (uniqueItems.isEmpty()) continue

        val browseIdKey = section.browseId?.takeIf { it.isNotBlank() }
        val titleKey =
            section.title
                .trim()
                .lowercase()
                .takeIf { it.isNotBlank() }

        if (section.isLibrarySection(userPlaylistIds) && section.items.none { it.isPodcast }) {
            if (firstLibraryIndex == -1) {
                firstLibraryIndex = result.size
                result.add(section.copy(items = uniqueItems))
                browseIdKey?.let { seenBrowseIds[it] = firstLibraryIndex }
                titleKey?.let { seenTitles[it] = firstLibraryIndex }
            } else {
                val existing = result[firstLibraryIndex]
                val mergedItems = (existing.items + uniqueItems).distinctBy(::canonicalHomeItemId)
                result[firstLibraryIndex] = existing.copy(items = mergedItems)
                browseIdKey?.let { seenBrowseIds[it] = firstLibraryIndex }
                titleKey?.let { seenTitles[it] = firstLibraryIndex }
            }
            continue
        }

        val existingIndex = (browseIdKey?.let { seenBrowseIds[it] } ?: titleKey?.let { seenTitles[it] })
        if (existingIndex != null) {
            val existing = result[existingIndex]
            val mergedItems = (existing.items + uniqueItems).distinctBy(::canonicalHomeItemId)
            result[existingIndex] = existing.copy(items = mergedItems)
        } else {
            val index = result.size
            result.add(section.copy(items = uniqueItems))
            browseIdKey?.let { seenBrowseIds[it] = index }
            titleKey?.let { seenTitles[it] = index }
        }
    }

    return result
}
