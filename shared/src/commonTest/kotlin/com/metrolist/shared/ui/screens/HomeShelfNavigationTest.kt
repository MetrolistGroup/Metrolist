package com.metrolist.shared.ui.screens

import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.PlaylistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class HomeShelfNavigationTest {
    @Test
    fun collectionShelfHeadersOpenTheSectionPageIncludingLibraryClassifiedRecommendations() {
        HomeShelfHeaderAction.entries.forEach { action ->
            val shelf = shelf.copy(headerAction = action)
            assertEquals(HomeShelfHeaderAction.LOCAL, shelf.headerNavigationAction(hasSectionPage = true))
            assertEquals(action, shelf.headerNavigationAction(hasSectionPage = false))
        }
        val moods = shelf.copy(style = HomeShelfStyle.MOOD_AND_GENRES, headerAction = HomeShelfHeaderAction.BROWSE)
        assertEquals(HomeShelfHeaderAction.BROWSE, moods.headerNavigationAction(hasSectionPage = true))
        val empty = shelf.copy(items = emptyList(), headerAction = HomeShelfHeaderAction.NONE)
        assertEquals(HomeShelfHeaderAction.NONE, empty.headerNavigationAction(hasSectionPage = true))
    }

    @Test
    fun pageKeysKeepFeedChipShelfAndEndpointIdentitySeparate() {
        val shelves =
            listOf(
                shelf,
                shelf.copy(feedChip = Chip("Relax", "relax")),
                shelf.copy(feedChip = Chip("Focus", "focus")),
                shelf.copy(feedChip = Chip("Explore", browseId = "FEmusic_explore")),
                shelf.copy(key = "another-shelf"),
                shelf.copy(browseId = "another-endpoint"),
                shelf.copy(params = "another-filter"),
            )
        assertEquals(shelves.size, shelves.map { it.pageKey }.toSet().size)
        assertEquals(shelf.pageKey, shelf.copy(feedChip = Chip("All", browseId = "FEmusic_home")).pageKey)
        assertEquals(
            shelf.copy(feedChip = Chip("Relax", "relax")).pageKey,
            shelf.copy(feedChip = Chip("Localized title", "relax")).pageKey,
        )
    }

    @Test
    fun lengthPrefixedPageKeysAvoidDelimiterAndNullCollisions() {
        assertNotEquals(
            shelf.copy(key = "a:b", browseId = "c").pageKey,
            shelf.copy(key = "a", browseId = "b:c").pageKey,
        )
        assertNotEquals(
            shelf.copy(feedChip = Chip("Feed", "b:c", "a")).pageKey,
            shelf.copy(feedChip = Chip("Feed", "c", "a:b")).pageKey,
        )
        assertNotEquals(shelf.copy(params = null).pageKey, shelf.copy(params = "").pageKey)
    }

    @Test
    fun filteredShelfWithoutItsOwnParametersKeepsTheVisibleSnapshot() {
        listOf(Chip("Relax", "relax-feed"), Chip("Explore", browseId = "FEmusic_explore")).forEach { feed ->
            val page = shelf.copy(feedChip = feed).toHomeSection()
            assertNull(page.browseId)
            assertNull(page.params)
            assertEquals(shelf.items, page.items)
        }
        assertEquals(shelf.browseId, shelf.toHomeSection().browseId)
    }

    @Test
    fun filteredShelfKeepsItsOwnMoreParametersAndAuthenticatedCursor() {
        val filtered = shelf.copy(feedChip = Chip("Relax", "feed-parameters"), isPersonal = true)
        val endpoint = filtered.copy(params = "shelf-parameters").toHomeSection()
        assertEquals(shelf.browseId, endpoint.browseId)
        assertEquals("shelf-parameters", endpoint.params)
        val cursor = filtered.copy(continuation = "next-shelf-page").toHomeSection()
        assertEquals(shelf.browseId, cursor.browseId)
        assertNull(cursor.params)
        assertEquals("next-shelf-page", cursor.continuation)
        assertEquals(true, cursor.isPersonal)
        assertEquals(shelf.items, cursor.items)
    }

    @Test
    fun artistHeaderSourceCannotReplaceASectionPageWithAnArtistRoot() {
        val page =
            shelf
                .copy(
                    browseId = "UCfemtanyl",
                    params = "artist-parameters",
                    continuation = "next",
                    isPersonal = true,
                ).toHomeSection()
        assertNull(page.browseId)
        assertEquals(shelf.items, page.items)
        assertEquals("artist-parameters", page.params)
        assertEquals("next", page.continuation)
        assertEquals(true, page.isPersonal)
    }

    private val shelf =
        HomeShelf(
            key = "fresh-finds",
            title = "Fresh finds",
            items = listOf(PlaylistItem("VLRDpersonal", "Personal mix", null)),
            style = HomeShelfStyle.CAROUSEL,
            headerAction = HomeShelfHeaderAction.LIBRARY,
            browseId = "FEmusic_mixes",
        )
}
