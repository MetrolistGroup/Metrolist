package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeStartupTest {
    @Test
    fun cacheReadAndInitialRefreshKeepSkeletonVisibleWithoutFeedContent() {
        val chipsOnly = HomeViewModel.HomeUiState(chips = listOf(Chip("Focus", params = "focus")))
        val states =
            listOf(
                chipsOnly.copy(isRestoringCache = true),
                chipsOnly.copy(isLoading = true),
                chipsOnly.copy(isRefreshing = true),
                chipsOnly.copy(isContentLoading = true),
            )
        states.forEach { state ->
            assertFalse(state.hasHomeFeedContent())
            assertTrue(state.showLoadingPlaceholder)
        }
    }

    @Test
    fun cachedContentRemainsVisibleDuringRefresh() {
        val song = SongItem("song", "Song", null, emptyList(), null)
        val states =
            listOf(
                HomeViewModel.HomeUiState(speedDialItems = listOf(song)),
                HomeViewModel.HomeUiState(sections = listOf(HomeSection("Shelf", listOf(song)))),
                HomeViewModel.HomeUiState(keepListening = listOf(song)),
            )
        states.forEach { state ->
            assertTrue(state.hasHomeFeedContent())
            assertFalse(state.copy(isRefreshing = true).showLoadingPlaceholder)
            assertFalse(state.copy(isLoading = true).showLoadingPlaceholder)
        }
    }

    @Test
    fun emptyShelvesDoNotReplaceSkeletonAndFailuresCanShowRetry() {
        val state = HomeViewModel.HomeUiState(sections = listOf(HomeSection("Empty", emptyList())), isRefreshing = true)
        assertTrue(state.showLoadingPlaceholder)
        assertFalse(state.copy(isRefreshing = false, error = "offline").showLoadingPlaceholder)
    }

    @Test
    fun cacheReadFlagIsNeverRestoredFromDisk() {
        val state = HomeViewModel.HomeUiState(isRestoringCache = true, isLoading = true)
        val restored = homeCacheJson.decodeFromString<HomeViewModel.HomeUiState>(homeCacheJson.encodeToString(state))
        assertFalse(restored.isRestoringCache)
        assertEquals(state.copy(isRestoringCache = false), restored)
    }
}
