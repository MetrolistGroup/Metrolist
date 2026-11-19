package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.SearchSuggestions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchSuggestionQueryChangeTest {
    private val suggestions = SearchSuggestions(queries = listOf("jazz albums"), recommendedItems = emptyList())
    private val state = SearchViewModel.SearchUiState(query = "jazz", searchSuggestions = suggestions)

    @Test
    fun editingQueryDoesNotExposeSuggestionsForPreviousQuery() {
        val edited = queryChangeState(state, "metal")

        assertEquals("metal", edited.query)
        assertNull(edited.searchSuggestions)
    }

    @Test
    fun repeatedQueryChangeKeepsSuggestionsForSameQuery() {
        assertEquals(suggestions, queryChangeState(state, "jazz").searchSuggestions)
    }

    @Test
    fun queriesBelowSuggestionThresholdDoNotShowSuggestions() {
        assertNull(queryChangeState(state, "j").searchSuggestions)
        assertNull(queryChangeState(state, "").searchSuggestions)
    }
}
