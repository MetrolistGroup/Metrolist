package com.metrolist.desktop.state.content

import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.UserPlaylist
import com.metrolist.shared.viewmodels.HomeChipFeed
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import com.metrolist.shared.viewmodels.homePaginationKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeDiscoveryTest {
    @Test
    fun appendedPagesUseTheDesktopDiscoveryFilterAndKeepTheirCursors() {
        val saved = PlaylistItem("saved", "Saved playlist", null)
        val recommendation = HomeSection("Recommendations", listOf(song("recommended")), browseId = "mixes", params = "more")
        val sources =
            listOf(
                recommendation,
                recommendation.copy(browseId = "FEmusic_library_albums"),
                recommendation.copy(browseId = "FEmusic_history"),
                HomeSection("Saved playlists", listOf(saved)),
                HomeSection("Local playlists", listOf(saved.copy(id = "local", isLocal = true))),
                HomeSection("Local songs", listOf(song("local-song").copy(isLocal = true))),
                HomeSection("Library links", listOf(BrowseItem("FEmusic_library_songs", "Library"))),
                recommendation.copy(hasTitle = false),
            )
        val chip = Chip("Focus", params = "focus")
        val feed = HomeChipFeed(chip, sources, continuation = "next", requestedContinuations = setOf("previous"))
        val state =
            HomeUiState(
                data = sources,
                chips = listOf(chip),
                userPlaylists = listOf(UserPlaylist(saved.id, saved.title, thumbnailUrl = null, songCount = null)),
                chipFeeds = listOf(feed),
                usedHomeChips = listOf(chip),
            )
        val desktop = state.forDesktopDiscovery()
        assertEquals(listOf(recommendation), desktop.data)
        assertEquals(listOf(feed.copy(sections = listOf(recommendation))), desktop.chipFeeds)
        assertEquals(state.homePaginationKey, desktop.homePaginationKey)
        assertEquals(listOf(chip), desktop.usedHomeChips)
    }

    @Test
    fun distinctAppendedShelvesSurviveAndLibraryItemsDoNotReachTheirHeaders() {
        val first = HomeSection("Mixes", listOf(song("first")), browseId = "mixes")
        val second = first.copy(items = listOf(song("second"), BrowseItem("FEmusic_library_playlists", "Library")))
        val third = second.copy(items = listOf(song("third")))
        val chip = Chip("Focus", params = "focus")
        val libraryChip = Chip("Library", browseId = "FEmusic_library")
        val visible = HomeChipFeed(chip, listOf(first, second, third))
        val library = HomeChipFeed(libraryChip, listOf(first), continuation = "library-next")
        val state =
            HomeUiState(
                chips = listOf(chip, libraryChip),
                chipFeeds = listOf(visible, library),
                usedHomeChips = listOf(chip, libraryChip),
            )
        val desktop = state.forDesktopDiscovery()
        assertEquals(listOf(chip), desktop.chips)
        assertEquals(listOf(first, second.copy(items = listOf(song("second"))), third), desktop.chipFeeds.first().sections)
        assertTrue(
            desktop.chipFeeds
                .last()
                .sections
                .isEmpty(),
        )
        assertEquals("library-next", desktop.chipFeeds.last().continuation)
        assertEquals(state.homePaginationKey, desktop.homePaginationKey)
    }
}

private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
