package com.metrolist.shared.ui.screens

import androidx.compose.ui.unit.Dp
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.model.isLibrarySection
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState

object HomeShelfBuilder {
    private const val MIN_KEEP_LISTENING_ITEMS = 5

    fun build(
        state: HomeUiState,
        text: HomeContentText,
        pageWidth: Dp,
        includeHidden: Boolean = false,
    ): List<HomeShelf> {
        fun isVisible(
            key: String,
            title: String,
        ) = includeHidden || Settings.shared.shouldShowHomeSection(key, title)
        val sourceSections = state.data.orEmpty().ifEmpty { state.sections }

        fun sourceFor(shelf: HomeShelf): HomeSection? {
            state.sections
                .singleOrNull { section ->
                    section.hasTitle && section.title == shelf.title && section.browseId == shelf.browseId &&
                        section.params == shelf.params && section.subtitle == shelf.label && section.items.containsAll(shelf.items)
                }?.let { return it }
            val itemIds = shelf.items.mapTo(mutableSetOf(), ::canonicalHomeItemId)
            val candidates =
                sourceSections.filter { section ->
                    section.hasTitle && section.items.any { canonicalHomeItemId(it) in itemIds }
                }
            return candidates.singleOrNull { it.browseId == shelf.browseId && (shelf.params == null || it.params == shelf.params) }
                ?: candidates.singleOrNull {
                    it.title == shelf.title && (shelf.label == null || it.subtitle == shelf.label) &&
                        (shelf.params == null || it.params == shelf.params)
                }
        }
        return buildList {
            val seenItemKeys = mutableSetOf<String>()
            val seenShelfTitles = mutableSetOf<String>()

            val userPlaylistIds = state.userPlaylists.map { it.id.removePrefix("VL") }.toSet()
            if (isVisible("speed_dial", text.speedDial)) {
                val uniqueSpeedDial =
                    state.speedDialItems
                        .distinctBy(::canonicalHomeItemId)
                        .filter { item ->
                            val isPinned = item.id in state.speedDialPinnedIds
                            isPinned || !(item is PlaylistItem && (item.isEditable || canonicalHomeItemId(item) in userPlaylistIds))
                        }
                if (uniqueSpeedDial.isNotEmpty()) {
                    text.speedDial
                        .trim()
                        .lowercase()
                        .takeIf { it.isNotBlank() }
                        ?.let { seenShelfTitles.add(it) }
                    add(
                        HomeShelf(
                            key = "speed_dial",
                            title = text.speedDial,
                            items = uniqueSpeedDial,
                            style = HomeShelfStyle.SPEED_DIAL,
                            rows = 3,
                            speedDialPinnedIds = state.speedDialPinnedIds,
                            speedDialSurprisePinned = state.speedDialSurprisePinned,
                        ),
                    )
                }
            }

            if (isVisible("keep_listening", text.keepListening)) {
                val listenAgainSection =
                    state.data?.firstOrNull { section ->
                        section.browseId?.startsWith("FEmusic_listen_again") == true && section.items.any { it is SongItem }
                    }
                val uniqueKeepListening =
                    (
                        state.keepListening +
                            state.sections.filter { it.browseId?.startsWith("FEmusic_listen_again") == true }.flatMap { it.items }
                    ).distinctBy(::canonicalHomeItemId)
                        .filterNot { canonicalHomeItemId(it) in seenItemKeys }
                if (uniqueKeepListening.size >= MIN_KEEP_LISTENING_ITEMS && seenShelfTitles.add(text.keepListening.trim().lowercase())) {
                    seenItemKeys += uniqueKeepListening.map(::canonicalHomeItemId)
                    add(
                        HomeShelf(
                            key = "keep_listening",
                            title = text.keepListening,
                            items = uniqueKeepListening,
                            style = HomeShelfStyle.CAROUSEL,
                            headerAction =
                                if (listenAgainSection != null) HomeShelfHeaderAction.BROWSE else HomeShelfHeaderAction.LOCAL,
                            browseId = listenAgainSection?.browseId,
                            params = listenAgainSection?.params,
                        ),
                    )
                }
            }

            if (isVisible("quick_picks", text.quickPicks)) {
                val uniqueQuickPicks =
                    state.quickPicks
                        .distinctBy(::canonicalHomeItemId)
                        .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                if (uniqueQuickPicks.isNotEmpty() && seenShelfTitles.add(text.quickPicks.trim().lowercase())) {
                    add(
                        HomeShelf(
                            key = "quick_picks",
                            title = text.quickPicks,
                            items = uniqueQuickPicks,
                            style = HomeShelfStyle.SONG_GRID,
                            headerAction = HomeShelfHeaderAction.PLAY_ALL,
                            rows = 4,
                            browseId = "FEmusic_quickpicks",
                        ),
                    )
                }
            }

            if (isVisible("forgotten_favorites", text.forgottenFavorites)) {
                val uniqueForgottenFavorites =
                    state.forgottenFavorites
                        .distinctBy(::canonicalHomeItemId)
                        .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                if (uniqueForgottenFavorites.isNotEmpty() && seenShelfTitles.add(text.forgottenFavorites.trim().lowercase())) {
                    add(
                        HomeShelf(
                            key = "forgotten_favorites",
                            title = text.forgottenFavorites,
                            items = uniqueForgottenFavorites,
                            style = HomeShelfStyle.CAROUSEL,
                            headerAction = HomeShelfHeaderAction.SHUFFLE,
                            browseId = "FEmusic_forgotten_favorites",
                        ),
                    )
                }
            }

            if (isVisible("new_releases", text.newReleases)) {
                val uniqueNewReleases =
                    state.newReleases
                        .distinctBy(::canonicalHomeItemId)
                        .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                if (uniqueNewReleases.isNotEmpty() && seenShelfTitles.add(text.newReleases.trim().lowercase())) {
                    add(
                        HomeShelf(
                            key = "new_releases",
                            title = text.newReleases,
                            items = uniqueNewReleases,
                            style = HomeShelfStyle.CAROUSEL,
                            headerAction = HomeShelfHeaderAction.BROWSE,
                            browseId = "FEmusic_new_releases",
                        ),
                    )
                }
            }

            if (isVisible("trending", text.charts)) {
                val uniqueTrending =
                    state.trending
                        .distinctBy(::canonicalHomeItemId)
                        .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                if (uniqueTrending.isNotEmpty() && seenShelfTitles.add(text.charts.trim().lowercase())) {
                    add(
                        HomeShelf(
                            key = "trending",
                            title = text.charts,
                            items = uniqueTrending,
                            style = HomeShelfStyle.CAROUSEL,
                            browseId = "FEmusic_charts",
                        ),
                    )
                }
            }

            if (isVisible("album_charts", text.albumCharts)) {
                val uniqueAlbumCharts =
                    state.albumCharts
                        .distinctBy(::canonicalHomeItemId)
                        .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                if (uniqueAlbumCharts.isNotEmpty() && seenShelfTitles.add(text.albumCharts.trim().lowercase())) {
                    add(
                        HomeShelf(
                            key = "album_charts",
                            title = text.albumCharts,
                            items = uniqueAlbumCharts,
                            style = HomeShelfStyle.ALBUM_CAROUSEL,
                            browseId = "FEmusic_charts",
                        ),
                    )
                }
            }

            state.similarRecommendations.forEachIndexed { index, recommendation ->
                if (!isVisible(
                        "similar_${recommendation.artist.id}",
                        "${text.similarTo} ${recommendation.artist.title}",
                    )
                ) {
                    return@forEachIndexed
                }
                val uniqueSimilar =
                    recommendation.items
                        .distinctBy(::canonicalHomeItemId)
                        .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                val title = "${text.similarTo} ${recommendation.artist.title}"
                if (uniqueSimilar.isNotEmpty() && seenShelfTitles.add(title.trim().lowercase())) {
                    add(
                        HomeShelf(
                            key = "similar_${recommendation.artist.id}_$index",
                            visibilityKey = "similar_${recommendation.artist.id}",
                            title = title,
                            items = uniqueSimilar,
                            style = HomeShelfStyle.SIMILAR_TO,
                            headerAction = HomeShelfHeaderAction.BROWSE,
                            browseId = recommendation.browseId,
                            params = recommendation.params,
                            label = recommendation.label ?: text.similarTo,
                            headerTitle = recommendation.artist.title,
                            thumbnail = recommendation.artist.thumbnail,
                            headerArtistId = recommendation.artist.id.takeIf { it.startsWith("UC") },
                        ),
                    )
                }
            }

            if (isVisible("community_playlists", text.editorialPlaylists)) {
                val uniqueCommunity =
                    state.communityPlaylist
                        .distinctBy(::canonicalHomeItemId)
                        .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                if (uniqueCommunity.isNotEmpty() && seenShelfTitles.add(text.editorialPlaylists.trim().lowercase())) {
                    add(
                        HomeShelf(
                            key = "community_playlists",
                            title = text.editorialPlaylists,
                            items = uniqueCommunity,
                            style = HomeShelfStyle.CAROUSEL,
                            headerAction = HomeShelfHeaderAction.BROWSE,
                            browseId = "FEmusic_community",
                        ),
                    )
                }
            }

            if (isVisible("recap_playlists", text.recapPlaylists)) {
                val uniqueRecap =
                    state.recapPlaylists
                        .distinctBy(::canonicalHomeItemId)
                        .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                if (uniqueRecap.isNotEmpty() && seenShelfTitles.add(text.recapPlaylists.trim().lowercase())) {
                    add(
                        HomeShelf(
                            key = "recap_playlists",
                            title = text.recapPlaylists,
                            items = uniqueRecap,
                            style = HomeShelfStyle.CAROUSEL,
                            browseId = "FEmusic_listening_review",
                        ),
                    )
                }
            }

            if (isVisible("podcasts", text.podcastsForYou)) {
                val uniquePodcasts =
                    state.podcasts
                        .distinctBy(::canonicalHomeItemId)
                        .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                if (uniquePodcasts.isNotEmpty() && seenShelfTitles.add(text.podcastsForYou.trim().lowercase())) {
                    add(
                        HomeShelf(
                            key = "podcasts",
                            title = text.podcastsForYou,
                            items = uniquePodcasts,
                            style = HomeShelfStyle.SONG_GRID,
                            headerAction = HomeShelfHeaderAction.BROWSE,
                            rows = 2,
                            browseId = "FEmusic_immersive",
                        ),
                    )
                }
            }

            state.sections
                .filter { it.hasTitle && it.browseId?.startsWith("FEmusic_listen_again") != true && isVisible(it.visibilityKey, it.title) }
                .forEachIndexed { index, section ->
                    val sectionTitleKey = section.title.trim().lowercase()
                    run {
                        val uniqueItems =
                            section.items
                                .distinctBy(::canonicalHomeItemId)
                                .filter { seenItemKeys.add(canonicalHomeItemId(it)) }
                        if (uniqueItems.isNotEmpty()) {
                            val existingIndex =
                                indexOfFirst {
                                    it.title.trim().lowercase() == sectionTitleKey && it.browseId == section.browseId &&
                                        it.params == section.params
                                }
                            if (existingIndex >= 0) {
                                val existing = get(existingIndex)
                                set(existingIndex, existing.copy(items = existing.items + uniqueItems))
                                return@run
                            }
                            seenShelfTitles.add(sectionTitleKey)
                            add(
                                section.toHomeShelf(
                                    key = "section_${section.browseId ?: section.title}_$index",
                                    text = text,
                                    userPlaylistIds = userPlaylistIds,
                                    items = uniqueItems,
                                ),
                            )
                        }
                    }
                }

            if (state.moodsAndGenres.isNotEmpty()) {
                val moodsTitleKey = text.moodsAndGenres.trim().lowercase()
                if (seenShelfTitles.add(moodsTitleKey)) {
                    val moodItems =
                        state.moodsAndGenres
                            .distinctBy { it.id to it.params }
                            .map { genre ->
                                BrowseItem(
                                    id = genre.id,
                                    title = genre.name,
                                    thumbnail = genre.thumbnail,
                                    params = genre.params,
                                )
                            }
                    if (moodItems.isNotEmpty()) {
                        add(
                            HomeShelf(
                                key = "moods",
                                title = text.moodsAndGenres,
                                items = moodItems,
                                style = HomeShelfStyle.MOOD_AND_GENRES,
                                headerAction = HomeShelfHeaderAction.BROWSE,
                                browseId = "FEmusic_moods_and_genres",
                            ),
                        )
                    }
                }
            }
        }.map { shelf ->
            val source = sourceFor(shelf)
            if (source == null) {
                shelf.copy(feedChip = state.selectedChip)
            } else {
                shelf.copy(
                    feedChip = state.selectedChip,
                    title = source.title,
                    rows = source.numItemsPerColumn ?: shelf.rows,
                    compactRows = source.isResponsiveList,
                    label = source.subtitle ?: shelf.label,
                    thumbnail = source.thumbnail ?: shelf.thumbnail,
                    headerArtistId = source.headerBrowseId?.takeIf { it.startsWith("UC") } ?: shelf.headerArtistId,
                    headerAction =
                        if (shelf.headerAction == HomeShelfHeaderAction.NONE && !source.browseId.isNullOrBlank()) {
                            HomeShelfHeaderAction.BROWSE
                        } else {
                            shelf.headerAction
                        },
                    browseId = source.browseId ?: shelf.browseId,
                    params = source.params ?: shelf.params,
                    continuation = source.continuation,
                    isPersonal = source.isPersonal,
                )
            }
        }.sortedBy { shelf ->
            if (shelf.key == "speed_dial") {
                -1
            } else if (shelf.style == HomeShelfStyle.MOOD_AND_GENRES) {
                sourceSections.size + 1
            } else {
                sourceFor(shelf)?.let { source ->
                    sourceSections
                        .indexOfFirst {
                            it.title == source.title && it.browseId == source.browseId && it.params == source.params &&
                                it.subtitle == source.subtitle
                        }.takeIf { it >= 0 }
                } ?: sourceSections.size
            }
        }.filterRenderable(pageWidth)
            .filter { isVisible(it.visibilityKey, it.title) } +
            state.chipFeeds.flatMapIndexed { feedIndex, feed ->
                feed.sections
                    .filter { it.items.isNotEmpty() && isVisible(it.visibilityKey, it.title) }
                    .mapIndexed { index, section ->
                        val shelf =
                            section.toHomeShelf(
                                key = "chip_${feedIndex}_$index",
                                text = text,
                                userPlaylistIds = state.userPlaylists.mapTo(mutableSetOf()) { it.id.removePrefix("VL") },
                            )
                        shelf.copy(
                            chipTitle = feed.chip.title.takeIf { index == 0 },
                            feedChip = feed.chip,
                            headerAction =
                                if (shelf.headerAction == HomeShelfHeaderAction.NONE && !shelf.browseId.isNullOrBlank()) {
                                    HomeShelfHeaderAction.BROWSE
                                } else {
                                    shelf.headerAction
                                },
                        )
                    }
            }
    }
}

internal fun List<HomeShelf>.filterRenderable(pageWidth: Dp): List<HomeShelf> =
    filter { shelf ->
        shelf.items.isNotEmpty()
    }

class HomeShelfCache {
    private var state: HomeUiState? = null
    private var text: HomeContentText? = null
    private var visibility: String? = null
    private var shelves: List<HomeShelf> = emptyList()

    fun build(
        state: HomeUiState,
        text: HomeContentText,
        pageWidth: Dp,
    ): List<HomeShelf> {
        val content =
            state.copy(
                isLoading = false,
                isRefreshing = false,
                isRevalidating = false,
                isContentLoading = false,
                isAllContentPending = false,
                isLoadingMore = false,
                loadMoreError = false,
                error = null,
            )
        val visibility = Settings.shared.homeSectionVisibility
        if (this.state != content || this.text != text || this.visibility != visibility) {
            shelves = HomeShelfBuilder.build(content, text, pageWidth)
            this.state = content
            this.text = text
            this.visibility = visibility
        }
        return shelves
    }

    fun clear() {
        state = null
        text = null
        shelves = emptyList()
    }
}

private val HomeSection.visibilityKey: String
    get() = "section:${browseId ?: headerBrowseId ?: title}:${params.orEmpty()}"

private fun HomeSection.toHomeShelf(
    key: String,
    text: HomeContentText,
    userPlaylistIds: Set<String>,
    items: List<YTItem> = this.items,
): HomeShelf {
    val section = this
    val isShuffleShelf =
        section.title.equals(text.quickPicks, true) || section.title.equals(text.forgottenFavorites, true)
    val isSimilar =
        section.browseId?.startsWith("FEmusic_similar") == true ||
            section.title.startsWith("${text.similarTo} ", true)
    return HomeShelf(
        key = key,
        visibilityKey = section.visibilityKey,
        title = section.title,
        items = items,
        style =
            when {
                section.browseId?.startsWith("FEmusic_forgotten_favorites") == true ||
                    section.title.equals(text.forgottenFavorites, true) -> HomeShelfStyle.CAROUSEL

                isShuffleShelf || section.isMultiRow || section.isResponsiveList -> HomeShelfStyle.SONG_GRID

                isSimilar -> HomeShelfStyle.SIMILAR_TO

                section.isListStyle -> HomeShelfStyle.SONG_LIST

                else -> HomeShelfStyle.CAROUSEL
            },
        headerAction =
            when {
                section.isLibrarySection(userPlaylistIds) && !section.browseId.isNullOrBlank() &&
                    section.items.any { it.isPodcast } -> HomeShelfHeaderAction.BROWSE

                section.isLibrarySection(userPlaylistIds) -> HomeShelfHeaderAction.LIBRARY

                section.browseId?.startsWith("FEmusic_quickpicks") == true ||
                    section.title.equals(text.quickPicks, ignoreCase = true) -> HomeShelfHeaderAction.PLAY_ALL

                section.title.equals(
                    text.forgottenFavorites,
                    ignoreCase = true,
                ) -> HomeShelfHeaderAction.SHUFFLE

                listOf(
                    text.keepListening,
                    text.newReleases,
                    text.podcastsForYou,
                    text.moodsAndGenres,
                    text.editorialPlaylists,
                ).any { section.title.equals(it, ignoreCase = true) } ||
                    section.title.startsWith("${text.similarTo} ", ignoreCase = true) ||
                    // shortcut: Match the moment shelf by title until the home API exposes a stable browse ID.
                    section.title.endsWith(
                        " tube of the moment",
                        ignoreCase = true,
                    ) -> HomeShelfHeaderAction.BROWSE

                else -> HomeShelfHeaderAction.NONE
            },
        browseId = section.browseId,
        params = section.params,
        continuation = section.continuation,
        isPersonal = section.isPersonal,
        compactRows = section.isResponsiveList,
        rows =
            section.numItemsPerColumn ?: if (isShuffleShelf) {
                4
            } else if (section.isMultiRow) {
                2
            } else {
                1
            },
        label = section.subtitle ?: text.similarTo.takeIf { isSimilar },
        headerTitle = if (isSimilar) section.title.removePrefix("${text.similarTo} ") else null,
        thumbnail = section.thumbnail,
        headerArtistId = section.headerBrowseId?.takeIf { it.startsWith("UC") },
    )
}
