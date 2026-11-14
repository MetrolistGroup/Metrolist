package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.SearchResultPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchPresentationTest {
    @Test
    fun onlyAnEmptyUnsubmittedQueryShowsTheStartPage() {
        assertEquals(SearchPresentation.IDLE, searchPresentation("", false))
        for (query in listOf("s", "search", "search longer")) {
            assertEquals(SearchPresentation.SUGGESTIONS, searchPresentation(query, false))
            assertEquals(SearchPresentation.RESULTS, searchPresentation(query, true))
        }
    }

    @Test
    fun historyRoundTripsDelimiterCharactersAsJson() {
        val history = listOf("earth, wind & fire", "simon | garfunkel")

        assertEquals(history, SearchHistoryCodec.decode(SearchHistoryCodec.encode(history)))
    }

    @Test
    fun historyMigratesCommaAndPipeFormats() {
        assertEquals(listOf("one", "two"), SearchHistoryCodec.decode("one,two"))
        assertEquals(listOf("one", "two"), SearchHistoryCodec.decode("one|two"))
    }

    @Test
    fun continuationPagesAppendWithoutDuplicates() {
        val current =
            SearchResultPage(
                items = listOf(BrowseItem("one", "One")),
                continuation = "requested",
            )
        val next =
            SearchResultPage(
                items = listOf(BrowseItem("one", "One again"), BrowseItem("two", "Two")),
                continuation = "next",
            )

        val merged = appendSearchResultPage(current, next, "requested")

        assertEquals(listOf("one", "two"), merged.items.map { it.id })
        assertEquals("next", merged.continuation)
    }

    @Test
    fun repeatedContinuationTokenStopsPagination() {
        val merged =
            appendSearchResultPage(
                current = SearchResultPage(emptyList(), "same-token"),
                next = SearchResultPage(emptyList(), "same-token"),
                requestedContinuation = "same-token",
            )

        assertEquals(null, merged.continuation)
    }

    @Test
    fun routedYouTubeUrlsAreNotSearchHistoryEntries() {
        assertFalse(shouldAddSearchHistory("https://youtu.be/dQw4w9WgXcQ", paused = false))
        assertTrue(shouldAddSearchHistory("never gonna give you up", paused = false))
        assertFalse(shouldAddSearchHistory("never gonna give you up", paused = true))
    }
}
