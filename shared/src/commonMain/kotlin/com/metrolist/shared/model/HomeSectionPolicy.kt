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

fun HomeSection.isHeardInShortsSection(): Boolean {
    if (browseId?.startsWith("FEmusic_similar") == true || headerBrowseId?.startsWith("UC") == true) {
        return false
    }
    val normalizedBrowseId = browseId?.trim()?.removePrefix("VL")
    if (normalizedBrowseId != null &&
        (normalizedBrowseId.equals("SS", ignoreCase = true) || normalizedBrowseId.contains("shorts", ignoreCase = true))
    ) {
        return true
    }
    if (!hasTitle || items.none { it is SongItem }) return false
    // shortcut: Match the "Heard in Shorts" shelf by title because YouTube Music omits a browseId on it; prefer browseId if YouTube adds one.
    return title.containsHeardInShortsToken()
}

fun HomeSection.isQuickPicksSection(): Boolean = browseId?.startsWith("FEmusic_quickpicks") == true && !isHeardInShortsSection()

fun List<HomeSection>.heardInShortsItemIds(): Set<String> =
    filter(HomeSection::isHeardInShortsSection)
        .flatMap(HomeSection::items)
        .mapTo(mutableSetOf(), ::canonicalHomeItemId)

private fun Char.isLatinLetterOrDigit(): Boolean = code < 0x0250 && isLetterOrDigit()

private fun String.containsHeardInShortsToken(): Boolean {
    val lower = lowercase()
    var start = lower.indexOf("shorts")
    while (start >= 0) {
        val beforeOk = start == 0 || !lower[start - 1].isLatinLetterOrDigit()
        val end = start + "shorts".length
        val afterOk = end == lower.length || !lower[end].isLatinLetterOrDigit()
        if (beforeOk && afterOk) return true
        start = lower.indexOf("shorts", start + 1)
    }
    return "ショート" in this || "쇼츠" in this || "शॉर्ट्स" in this
}

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
    val seenBrowseDestinations = mutableMapOf<Pair<String, String?>, Int>()
    val seenUnparameterizedTitles = mutableMapOf<String, Int>()

    for (section in sections) {
        val uniqueItems = section.items.distinctBy(::canonicalHomeItemId)
        if (uniqueItems.isEmpty()) continue

        val destination = section.browseDestination()
        val titleKey =
            section.title
                .trim()
                .lowercase()
                .takeIf { it.isNotBlank() }

        if (section.isLibrarySection(userPlaylistIds) && section.items.none { it.isPodcast }) {
            if (firstLibraryIndex == -1) {
                firstLibraryIndex = result.size
                result.add(section.copy(items = uniqueItems))
                destination?.let { seenBrowseDestinations[it] = firstLibraryIndex }
                if (destination?.second == null) titleKey?.let { seenUnparameterizedTitles[it] = firstLibraryIndex }
            } else {
                val existing = result[firstLibraryIndex]
                val mergedItems = (existing.items + uniqueItems).distinctBy(::canonicalHomeItemId)
                result[firstLibraryIndex] = existing.copy(items = mergedItems)
                destination?.let { seenBrowseDestinations[it] = firstLibraryIndex }
                if (destination?.second == null) titleKey?.let { seenUnparameterizedTitles[it] = firstLibraryIndex }
            }
            continue
        }

        val existingIndex =
            destination?.let { seenBrowseDestinations[it] }
                ?: titleKey?.takeIf { destination?.second == null }?.let { seenUnparameterizedTitles[it] }?.takeIf { index ->
                    // Identical labels can refer to different browse destinations.
                    val existingDestination = result[index].browseDestination()
                    destination == null || existingDestination == null || destination == existingDestination
                }
        if (existingIndex != null) {
            val existing = result[existingIndex]
            val mergedItems = (existing.items + uniqueItems).distinctBy(::canonicalHomeItemId)
            result[existingIndex] = existing.copy(items = mergedItems)
        } else {
            val index = result.size
            result.add(section.copy(items = uniqueItems))
            destination?.let { seenBrowseDestinations[it] = index }
            if (destination?.second == null) titleKey?.let { seenUnparameterizedTitles.getOrPut(it) { index } }
        }
    }

    return result
}

private fun HomeSection.browseDestination(): Pair<String, String?>? =
    browseId?.takeIf(String::isNotBlank)?.let { id -> id to params?.takeIf(String::isNotBlank) }
