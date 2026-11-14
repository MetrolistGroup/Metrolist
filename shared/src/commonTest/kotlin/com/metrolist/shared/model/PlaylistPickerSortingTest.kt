package com.metrolist.shared.model

import com.metrolist.shared.constants.PlaylistPickerSortKey
import com.metrolist.shared.constants.PlaylistSortType
import com.metrolist.shared.settings.LibraryContentSort
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.settings.LibrarySorting
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistPickerSortingTest {
    private val first = PlaylistItem("first", "Beta", null, songCount = "2", libraryAddedAt = 20, playtime = 500)
    private val second = PlaylistItem("VLsecond", "Alpha", null, songCount = "5", libraryAddedAt = 10, playtime = 100)
    private val unknown = PlaylistItem("new", "Gamma", null)
    private val playlists = listOf(first, second, unknown)

    @Test
    fun librarySortAndDirectionApplyWithoutOverwritingExplicitPickerChoices() {
        val settings = Settings(MapSettings())
        assertEquals(PlaylistPickerSort.LIBRARY, settings.playlistPickerSort)
        settings.setLibrarySorting(LibraryFilterType.PLAYLISTS, LibrarySorting(LibraryContentSort.NAME, SortDirection.DESCENDING))
        assertEquals(listOf(unknown, first, second), sorted(settings))
        settings.setLibrarySorting(LibraryFilterType.PLAYLISTS, LibrarySorting(LibraryContentSort.RECENTLY_ADDED, SortDirection.ASCENDING))
        assertEquals(listOf(second, first, unknown), sorted(settings))
        settings.setLibrarySorting(LibraryFilterType.PLAYLISTS, LibrarySorting(LibraryContentSort.PLAYTIME, SortDirection.DESCENDING))
        assertEquals(listOf(first, second, unknown), sorted(settings))
        settings.playlistPickerSort = PlaylistPickerSort.NAME
        settings.playlistPickerSortDirection = SortDirection.ASCENDING
        assertEquals(listOf(second, first, unknown), sorted(settings))
    }

    @Test
    fun manualOrderUsesLibraryIdentitiesAndKeepsNewItems() {
        val settings = Settings(MapSettings())
        settings.libraryManualOrder = "PLAYLIST:second\nARTIST:first\nPLAYLIST:missing\nPLAYLIST:first\nPLAYLIST:second"
        settings.setLibrarySorting(LibraryFilterType.PLAYLISTS, LibrarySorting(LibraryContentSort.MANUAL, SortDirection.DESCENDING))
        assertEquals(listOf(second, first, unknown), sorted(settings))
        settings.playlistPickerSort = PlaylistPickerSort.MANUAL
        settings.setLibrarySorting(LibraryFilterType.PLAYLISTS, LibrarySorting(LibraryContentSort.NAME, SortDirection.ASCENDING))
        assertEquals(listOf(second, first, unknown), sorted(settings))
    }

    @Test
    fun existingPickerOrdinalsAndBackupsKeepTheirChoices() {
        for (old in PlaylistSortType.entries) {
            val settings = Settings(MapSettings(PlaylistPickerSortKey to old.ordinal))
            assertEquals(old.name, settings.playlistPickerSort.name)
            val restored = Settings(MapSettings())
            restored.importBackupSettings(settings.exportBackupSettings())
            assertEquals(settings.playlistPickerSort, restored.playlistPickerSort)
        }
    }

    private fun sorted(settings: Settings): List<PlaylistItem> =
        playlists.sortedForPlaylistPicker(
            sort = settings.playlistPickerSort,
            direction = settings.playlistPickerSortDirection,
            name = { it.title },
            addedAt = { it.libraryAddedAt },
            lastUpdated = { it.libraryUpdatedAt },
            songCount = { it.songCount?.toIntOrNull() },
            id = { it.id },
            librarySorting = settings.librarySorting(LibraryFilterType.PLAYLISTS),
            manualOrder = settings.libraryManualOrder,
            playtime = { it.playtime },
        )
}
