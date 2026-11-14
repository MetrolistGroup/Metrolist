package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.settings.HiddenLibraryItemType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.screens.LibraryFilter
import com.metrolist.shared.ui.screens.LibrarySortType
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LibraryPresentationTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    private val input =
        LibraryPresentationInput(
            items = List(300) { PlaylistItem("playlist-$it", "Playlist $it", null) },
            filter = LibraryFilter.PLAYLISTS,
            hiddenItemKeys = emptySet(),
            sortType = LibrarySortType.NAME,
            sortDirection = SortDirection.ASCENDING,
            manualOrder = "",
            contentFilterVersion = 0,
            searchQuery = "",
            contentMatchedSongs = emptyList(),
        )

    @Test
    fun returningConsumerImmediatelyReceivesTheCompletePreparedList() =
        runTest {
            val presentation = LibraryPresentation(StandardTestDispatcher(testScheduler))
            assertNull(presentation.cached(input))
            val prepared = presentation.prepare(input)
            assertEquals(300, prepared.size)
            assertSame(prepared, presentation.cached(input.copy(items = input.items.toList())))
            assertSame(prepared, presentation.prepare(input))
        }

    @Test
    fun playlistsIncludeEditableLocalAndSavedPlaylists() =
        runTest {
            val saved = PlaylistItem("saved", "Saved", null, isEditable = false)
            val editable = saved.copy(id = "editable", title = "Editable", isEditable = true)
            val local = saved.copy(id = "local", title = "Local", isLocal = true)
            val song = SongItem("song", "Song", null, emptyList(), null)
            val presentation = LibraryPresentation(StandardTestDispatcher(testScheduler))
            assertEquals(
                listOf(editable, local, saved),
                presentation.prepare(input.copy(items = listOf(saved, song, local, editable))),
            )
        }

    @Test
    fun changedInputsCannotRestoreAStalePresentation() =
        runTest {
            val presentation = LibraryPresentation(StandardTestDispatcher(testScheduler))
            presentation.prepare(input)
            val changes =
                listOf(
                    input.copy(items = input.items.drop(1)),
                    input.copy(filter = LibraryFilter.ALBUMS),
                    input.copy(hiddenItemKeys = setOf(HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, "playlist-1"))),
                    input.copy(sortType = LibrarySortType.RECENTLY_ADDED),
                    input.copy(sortDirection = SortDirection.DESCENDING),
                    input.copy(manualOrder = "changed"),
                    input.copy(contentFilterVersion = 1),
                    input.copy(searchQuery = "missing"),
                    input.copy(contentMatchedSongs = listOf(SongItem("song", "Song", null, emptyList(), null))),
                )
            changes.forEach { assertNull(presentation.cached(it)) }
            val emptySearch = input.copy(searchQuery = "missing")
            assertEquals(emptyList(), presentation.prepare(emptySearch))
            assertEquals(emptyList(), presentation.cached(emptySearch))
            assertNull(presentation.cached(input))
        }

    @Test
    fun searchExcludesHiddenBlockedAndFilteredSongs() =
        runTest {
            val visible = SongItem("visible", "Match", null, emptyList(), null)
            val blocked = visible.copy(id = "blocked")
            val hidden = visible.copy(id = "hidden")
            val explicit = visible.copy(id = "explicit", isExplicit = true)
            Settings.shared.setSongBlocked(blocked.id, true)
            Settings.shared.hideExplicit = true
            val presentation = LibraryPresentation(StandardTestDispatcher(testScheduler))
            val search =
                input.copy(
                    items = emptyList(),
                    filter = null,
                    searchQuery = "Match",
                    hiddenItemKeys = setOf(HiddenLibraryItemKey(HiddenLibraryItemType.OTHER, hidden.id)),
                    contentMatchedSongs = listOf(visible, blocked, hidden, explicit),
                )
            val tracks = search.copy(items = search.contentMatchedSongs, filter = LibraryFilter.TRACKS, searchQuery = "")
            assertEquals(listOf(visible), presentation.prepare(tracks))
            assertEquals(listOf(visible), presentation.prepare(search))
            Settings.shared.setSongBlocked(blocked.id, false)
            assertEquals(listOf(visible, blocked), presentation.prepare(search.copy(contentFilterVersion = 1)))
        }

    @Test
    fun cancelledPreparationKeepsTheLastCompleteSnapshot() =
        runTest {
            val presentation = LibraryPresentation(StandardTestDispatcher(testScheduler))
            val prepared = presentation.prepare(input)
            val changed = input.copy(sortDirection = SortDirection.DESCENDING)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { presentation.prepare(changed) }
            job.cancel()
            job.join()
            assertNull(presentation.cached(changed))
            assertSame(prepared, presentation.cached(input))
            assertEquals(prepared.reversed(), presentation.prepare(changed))
        }
}
