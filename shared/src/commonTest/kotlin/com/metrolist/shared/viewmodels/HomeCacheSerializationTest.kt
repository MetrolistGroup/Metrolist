package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeCacheSerializationTest {
    @Test
    fun startupSnapshotRetainsGenericShelvesAfterSpecializedSections() {
        val song = SongItem("song", "Song", null, emptyList(), null)
        val specialized = (0..7).map { HomeSection("Chart $it", listOf(song), browseId = "FEmusic_charts_$it") }
        val generic = HomeSection("Recommendation", listOf(song), browseId = "recommendation")
        for (savedSections in listOf(emptyList(), listOf(generic))) {
            val snapshot = HomeViewModel.HomeUiState(data = specialized + generic, sections = savedSections).startupSnapshot()
            val restored =
                homeCacheJson
                    .decodeFromString<HomeViewModel.HomeUiState>(
                        homeCacheJson.encodeToString(snapshot),
                    ).startupSnapshot()
            assertEquals(specialized, restored.data)
            assertEquals(listOf(generic), restored.sections)
        }
    }

    @Test
    fun startupSnapshotBoundsContinuedShelvesWithoutMutatingLiveContentOrPins() {
        val songs = (0 until 100).map { SongItem("song-$it", "Song $it", null, emptyList(), null) }
        val sections = (0 until 100).map { HomeSection(title = "Section $it", items = songs) }
        val selected = Chip(title = "Focus", params = "focus")
        val state =
            HomeViewModel.HomeUiState(
                data = sections,
                sections = sections,
                discoverySections = sections,
                quickPicks = songs,
                keepListening = songs,
                speedDialItems = songs,
                speedDialPinnedIds = listOf("song-99"),
                continuation = "page-101",
                selectedChip = selected,
                contentLocaleSignature = "fr:FR",
            )
        val snapshot = state.startupSnapshot()
        val restored = homeCacheJson.decodeFromString<HomeViewModel.HomeUiState>(homeCacheJson.encodeToString(snapshot))

        assertEquals(snapshot, restored)
        assertEquals(8, restored.data?.size)
        assertEquals(8, restored.sections.size)
        assertEquals(8, restored.discoverySections.size)
        assertTrue(restored.sections.all { it.items.size == 24 })
        assertEquals(24, restored.quickPicks.size)
        assertEquals(24, restored.keepListening.size)
        assertEquals(25, restored.speedDialItems.size)
        assertTrue(restored.speedDialItems.any { it.id == "song-99" })
        assertEquals(state.speedDialPinnedIds, restored.speedDialPinnedIds)
        assertEquals(selected, restored.selectedChip)
        assertEquals("fr:FR", restored.contentLocaleSignature)
        assertEquals(null, restored.continuation)
        assertEquals(100, state.data?.size)
        assertEquals("page-101", state.continuation)
        assertEquals(snapshot, snapshot.startupSnapshot())
    }

    @Test
    fun refreshedChipsKeepSelectedIdentityAndFilteredRequestAfterCacheRoundTrip() {
        val selected = Chip(title = "Podcasts", params = "podcasts", browseId = "FEmusic_home")
        val other = Chip(title = "Relax", params = "relax", browseId = "FEmusic_home")
        val responseChips = listOf(selected.copy(params = null), other)
        val state = HomeViewModel.HomeUiState(selectedChip = selected, chips = responseChips)
        val restored = homeCacheJson.decodeFromString<HomeViewModel.HomeUiState>(homeCacheJson.encodeToString(state))

        assertEquals(listOf(selected, other), homeChipsForSelection(responseChips, selected))
        assertEquals(listOf(selected, other), homeChipsForSelection(restored.chips, restored.selectedChip))
        assertEquals(responseChips, homeChipsForSelection(responseChips, null))
        assertEquals(null, homeContentForAllSelection(restored, null).selectedChip)
    }

    @Test
    fun restoringAllWithoutCachedSnapshotClearsRestoredChip() {
        val restoredSelected =
            HomeViewModel.HomeUiState(
                selectedChip = Chip(title = "Energy", params = "energy"),
                error = "selected",
            )

        val pendingAll = homeContentForAllSelection(restoredSelected, null)

        assertEquals(
            restoredSelected.copy(selectedChip = null, isAllContentPending = true),
            pendingAll,
        )
        assertFalse(isCacheableAllContent(pendingAll))
        assertTrue(isCacheableAllContent(pendingAll.copy(isAllContentPending = false)))
    }

    @Test
    fun uncachedChipStartsEmptyAndKeepsPreviousContentAvailableForRestore() {
        val chip = Chip("Energy", "energy")
        val track = SongItem("old", "Old", null, emptyList(), null)
        val previous =
            HomeViewModel.HomeUiState(
                chips = listOf(chip),
                quickPicks = listOf(track),
                speedDialItems = listOf(track),
                sections = listOf(HomeSection("Old shelf", listOf(track))),
                continuation = "old-next",
            )
        val pending = pendingHomeChipContent(previous, chip)
        assertEquals(chip, pending.selectedChip)
        assertEquals(previous.chips, pending.chips)
        assertTrue(pending.isContentLoading)
        assertTrue(pending.loadedHomeSongs().isEmpty())
        assertTrue(pending.sections.isEmpty())
        assertEquals(null, pending.continuation)
        assertTrue(pending.copy(isContentLoading = false, error = "offline").loadedHomeSongs().isEmpty())
        assertEquals(previous, homeContentForAllSelection(pending, previous))
    }

    @Test
    fun cachedHomeContentRequiresMatchingLocaleAndSession() {
        val state = HomeViewModel.HomeUiState(contentLocaleSignature = "fr:FR", sessionKey = "session-one")

        assertEquals(state, cachedHomeStateForSession(state, "fr:FR", "session-one"))
        assertEquals(null, cachedHomeStateForSession(state, "fr:FR", "session-two"))
        assertEquals(null, cachedHomeStateForSession(state.copy(sessionKey = null), "fr:FR", "session-one"))
        assertEquals(null, cachedHomeStateForSession(state, "de:DE", "session-one"))
        assertEquals(null, cachedHomeStateForSession(HomeViewModel.HomeUiState(), "fr:FR", "session-one"))
    }

    @Test
    fun roundTripsAlbumTypeThroughPolymorphicHomeSection() {
        val album =
            AlbumItem(
                id = "album-id",
                title = "Album",
                thumbnail = null,
                artists = emptyList(),
                type = "Album",
            )
        val state =
            HomeViewModel.HomeUiState(
                data = listOf(HomeSection(title = "Section", items = listOf(album))),
                newReleases = listOf(album),
            )

        assertEquals(
            state,
            homeCacheJson.decodeFromString<HomeViewModel.HomeUiState>(homeCacheJson.encodeToString(state)),
        )
    }
}
