package com.metrolist.shared.settings

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.metrolist.shared.constants.DesktopRenderBackendKey
import com.metrolist.shared.constants.LibrarySortingByFilterKey
import com.metrolist.shared.constants.PlaylistSongSortingsKey
import com.metrolist.shared.constants.SentryEnabledKey
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SortDirection
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import com.russhwolf.settings.Settings as KmpSettings

class SettingsTest {
    @Test
    fun desktopRenderBackendStaysOnTheInstallationAcrossBackupRestore() {
        val backing = MapSettings()
        val settings = Settings(backing)
        assertEquals("AUTO", settings.desktopRenderBackend)
        settings.desktopRenderBackend = "DIRECT3D"
        assertEquals("DIRECT3D", Settings(backing).desktopRenderBackend)
        val backup = settings.exportBackupSettings()
        assertFalse(backup.entries.any { it.key == DesktopRenderBackendKey })
        val restored = Settings(MapSettings())
        restored.importBackupSettings(backup)
        assertEquals("AUTO", restored.desktopRenderBackend)
        settings.importBackupSettings(restored.exportBackupSettings())
        assertEquals("DIRECT3D", settings.desktopRenderBackend)
    }

    @Test
    fun legacyShuffleDefaultsOnAndPreservesTheKmpChoiceAcrossRestore() {
        val backing = MapSettings()
        val settings = Settings(backing)
        assertEquals(true, settings.useLegacyShuffle)
        settings.useLegacyShuffle = false
        assertEquals(false, Settings(backing).useLegacyShuffle)
        val restored = Settings(MapSettings())
        restored.importBackupSettings(settings.exportBackupSettings())
        assertEquals(false, restored.useLegacyShuffle)
    }

    @Test
    fun artworkCroppingDefaultsOnAndPreservesSavedOptOuts() {
        val backing = MapSettings()
        val settings = Settings(backing)
        assertEquals(true, settings.cropAlbumArt)

        settings.cropAlbumArt = false
        assertEquals(false, Settings(backing).cropAlbumArt)
        val restored = Settings(MapSettings())
        restored.importBackupSettings(settings.exportBackupSettings())
        assertEquals(false, restored.cropAlbumArt)
    }

    @Test
    fun collectionCoversPersistIndependentlyAndRemovalRestoresDefaults() {
        val backing = MapSettings()
        val settings = Settings(backing)
        settings.setCollectionCover("liked", "/covers/liked.jpg")
        settings.setCollectionCover("downloaded", "/covers/downloaded.jpg")
        val restored = Settings(backing)
        assertEquals(settings.collectionCovers, restored.collectionCovers)
        val otherDevice = Settings(MapSettings())
        otherDevice.importBackupSettings(settings.exportBackupSettings())
        assertEquals(emptyMap(), otherDevice.collectionCovers)
        assertFailsWith<IllegalArgumentException> { restored.setCollectionCover("liked", " ") }
        restored.setCollectionCover("liked", null)
        assertEquals(mapOf("downloaded" to "/covers/downloaded.jpg"), Settings(backing).collectionCovers)
    }

    @Test
    fun ordinaryPlaylistsShareSortingWhileSpecialCollectionsPersistIndependently() {
        val backing = MapSettings()
        val settings = Settings(backing)
        val playlistA = "local:playlist-a"
        val playlistB = "playlist:remote-b"
        val liked = "special:liked"
        val downloaded = "special:downloaded"
        val cached = "special:cached"

        settings.setPlaylistSongSorting(playlistA, SavedPlaylistSongSorting(PlaylistSongSort.TITLE, SortDirection.ASCENDING))
        settings.setPlaylistSongSorting(playlistB, SavedPlaylistSongSorting(PlaylistSongSort.ARTIST, SortDirection.DESCENDING))
        settings.setPlaylistSongSorting(liked, SavedPlaylistSongSorting(PlaylistSongSort.DURATION, SortDirection.ASCENDING))
        settings.setPlaylistSongSorting(downloaded, SavedPlaylistSongSorting(PlaylistSongSort.ALBUM, SortDirection.DESCENDING))
        settings.setPlaylistCollectionSorting(downloaded, SavedPlaylistCollectionSorting(CollectionSort.SIZE, SortDirection.ASCENDING))
        settings.setPlaylistCollectionSorting(cached, SavedPlaylistCollectionSorting(CollectionSort.NEWEST, SortDirection.DESCENDING))

        val fromBackup = Settings(MapSettings())
        fromBackup.importBackupSettings(settings.exportBackupSettings())
        for (loaded in listOf(settings, Settings(backing), fromBackup)) {
            for (key in listOf(playlistA, playlistB, "playlist:unopened", "local:unopened")) {
                assertEquals(
                    SavedPlaylistSongSorting(PlaylistSongSort.ARTIST, SortDirection.DESCENDING),
                    loaded.playlistSongSorting(key, PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING),
                )
            }
            assertEquals(
                SavedPlaylistSongSorting(PlaylistSongSort.DURATION, SortDirection.ASCENDING),
                loaded.playlistSongSorting(liked, PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING),
            )
        }
        val restored = Settings(backing)
        assertEquals(
            SavedPlaylistSongSorting(PlaylistSongSort.ARTIST, SortDirection.DESCENDING),
            restored.playlistSongSorting(playlistB, PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING),
        )
        assertEquals(
            SavedPlaylistSongSorting(PlaylistSongSort.ALBUM, SortDirection.DESCENDING),
            restored.playlistSongSorting(downloaded, PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING),
        )
        assertEquals(
            SavedPlaylistCollectionSorting(CollectionSort.SIZE, SortDirection.ASCENDING),
            restored.playlistCollectionSorting(downloaded, CollectionSort.NEWEST, SortDirection.DESCENDING),
        )
        assertEquals(
            SavedPlaylistCollectionSorting(CollectionSort.NEWEST, SortDirection.DESCENDING),
            restored.playlistCollectionSorting(cached, CollectionSort.SIZE, SortDirection.ASCENDING),
        )
    }

    @Test
    fun ordinaryPlaylistOverridesAreIgnoredWithoutLosingSavedLikedSorting() {
        val backing =
            MapSettings(
                PlaylistSongSortingsKey to
                    """{"playlist:remote":{"sort":"TITLE","direction":"DESCENDING"},
                    "local:local":{"sort":"ARTIST","direction":"ASCENDING"},
                    "special:liked":{"sort":"DURATION","direction":"ASCENDING"}}""",
            )
        val settings = Settings(backing)
        val ordinaryKeys = listOf("playlist:remote", "local:local")
        val original = SavedPlaylistSongSorting(PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING)
        for (key in ordinaryKeys) {
            assertEquals(original, settings.playlistSongSorting(key, PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING))
        }
        settings.playlistSongSort = PlaylistSongSort.ALBUM
        settings.playlistSongSortDirection = SortDirection.DESCENDING
        val legacyGlobal = SavedPlaylistSongSorting(PlaylistSongSort.ALBUM, SortDirection.DESCENDING)
        for (key in ordinaryKeys) {
            assertEquals(legacyGlobal, settings.playlistSongSorting(key, PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING))
        }
        val shared = SavedPlaylistSongSorting(PlaylistSongSort.YEAR, SortDirection.ASCENDING)
        settings.setPlaylistSongSorting("local:local", shared)
        val restored = Settings(backing)
        for (key in ordinaryKeys) {
            assertEquals(shared, restored.playlistSongSorting(key, PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING))
        }
        assertEquals(
            SavedPlaylistSongSorting(PlaylistSongSort.DURATION, SortDirection.ASCENDING),
            restored.playlistSongSorting("special:liked", restored.likedSongSort, restored.likedSongSortDirection),
        )
    }

    @Test
    fun changingAnOrdinaryPlaylistInvalidatesOtherPlaylistObserversButKeepsLikedDefaults() {
        val settings = Settings(MapSettings())
        val observer = SnapshotStateObserver { it() }
        var invalidations = 0
        observer.start()
        try {
            Snapshot.sendApplyNotifications()
            observer.observeReads(settings, { invalidations++ }) {
                settings.playlistSongSorting("playlist:observed", PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING)
            }
            val sorting = SavedPlaylistSongSorting(PlaylistSongSort.TITLE, SortDirection.DESCENDING)
            settings.setPlaylistSongSorting("local:changed", sorting)
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
            assertEquals(sorting, settings.playlistSongSorting("playlist:observed", PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING))
            assertEquals(
                SavedPlaylistSongSorting(PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING),
                settings.playlistSongSorting("special:liked", settings.likedSongSort, settings.likedSongSortDirection),
            )
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun librarySortPairsMigrateIndependentlyAndSurviveRestartAndBackup() {
        val backing = MapSettings("libraryContentSort" to LibraryContentSort.NAME.name)
        val settings = Settings(backing)
        settings.librarySortDirection = SortDirection.DESCENDING
        val legacy = LibrarySorting(LibraryContentSort.NAME, SortDirection.DESCENDING)
        LibraryFilterType.entries.forEach { assertEquals(legacy, settings.librarySorting(it)) }
        val playlists = LibrarySorting(LibraryContentSort.NAME, SortDirection.ASCENDING)
        val albums = LibrarySorting(LibraryContentSort.RECENTLY_ADDED, SortDirection.DESCENDING)
        settings.setLibrarySorting(LibraryFilterType.PLAYLISTS, playlists)
        settings.setLibrarySorting(LibraryFilterType.ALBUMS, albums)
        settings.setLibrarySorting(LibraryFilterType.DEFAULT, albums)
        val restored = Settings(MapSettings())
        restored.importBackupSettings(settings.exportBackupSettings())
        for (loaded in listOf(settings, Settings(backing), restored)) {
            assertEquals(playlists, loaded.librarySorting(LibraryFilterType.PLAYLISTS))
            assertEquals(albums, loaded.librarySorting(LibraryFilterType.ALBUMS))
            assertEquals(albums, loaded.librarySorting(LibraryFilterType.ALL))
            assertEquals(legacy, loaded.librarySorting(LibraryFilterType.ARTISTS))
            assertEquals(legacy, loaded.librarySorting(LibraryFilterType.PODCASTS))
        }
    }

    @Test
    fun removedLibraryFilterDoesNotDiscardOtherSavedSorts() {
        val backing =
            MapSettings(
                LibrarySortingByFilterKey to """{
                "OWNED":{"sort":"LAST_UPDATED","direction":"ASCENDING"},
                "PLAYLISTS":{"sort":"NAME","direction":"DESCENDING"},
                "ALBUMS":{"sort":"YEAR","direction":"ASCENDING"}
            }""",
            )
        val settings = Settings(backing)
        val restored = Settings(MapSettings())
        restored.importBackupSettings(settings.exportBackupSettings())
        for (loaded in listOf(settings, Settings(backing), restored)) {
            assertEquals(
                LibrarySorting(LibraryContentSort.NAME, SortDirection.DESCENDING),
                loaded.librarySorting(LibraryFilterType.PLAYLISTS),
            )
            loaded.setLibrarySorting(LibraryFilterType.ARTISTS, LibrarySorting(LibraryContentSort.PLAYTIME, SortDirection.ASCENDING))
            assertEquals(
                LibrarySorting(LibraryContentSort.YEAR, SortDirection.ASCENDING),
                loaded.librarySorting(LibraryFilterType.ALBUMS),
            )
        }
    }

    @Test
    fun savedLibraryFilterIdsSurviveRestartAndBackupRestore() {
        val filters =
            listOf(
                LibraryFilterType.DEFAULT,
                LibraryFilterType.ALL,
                LibraryFilterType.TRACKS,
                LibraryFilterType.PLAYLISTS,
                LibraryFilterType.PLAYLISTS,
                LibraryFilterType.ARTISTS,
                LibraryFilterType.ALBUMS,
                LibraryFilterType.PODCASTS,
            )
        filters.forEachIndexed { stored, expected ->
            val backing = MapSettings("libraryFilter" to stored, "settingsMigrationVersion" to 14)
            val settings = Settings(backing)
            val backup = settings.exportBackupSettings()
            assertEquals(expected, settings.libraryFilter)
            assertEquals(expected.storedValue, backing.getInt("libraryFilter", -1))
            assertEquals(expected, Settings(backing).libraryFilter)
            val restored = Settings(MapSettings())
            restored.libraryFilter = LibraryFilterType.ALBUMS
            restored.importBackupSettings(backup)
            assertEquals(expected, restored.libraryFilter)
            restored.libraryFilter = LibraryFilterType.TRACKS
            restored.restoreBackupSettingsSnapshot(backup)
            assertEquals(expected, restored.libraryFilter)
            restored.libraryFilter = expected
            assertEquals(
                expected.storedValue.toString(),
                restored
                    .exportBackupSettings()
                    .entries
                    .single { it.key == "libraryFilter" }
                    .value,
            )
        }
        for (invalid in listOf(-1, 8, Int.MAX_VALUE)) {
            assertEquals(LibraryFilterType.DEFAULT, Settings(MapSettings("libraryFilter" to invalid)).libraryFilter)
        }
    }

    @Test
    fun unchangedValuesAreNotWrittenAgain() {
        val backing = CountingSettings()
        val preferences = Settings(backing)

        preferences.dynamicTheme = true
        preferences.dynamicTheme = false
        preferences.dynamicTheme = false

        assertEquals(1, backing.booleanWrites)
    }

    @Test
    fun failedWritesDoNotUpdateTheCachedValue() {
        val backing = CountingSettings(failWrites = true)
        val preferences = Settings(backing)

        assertFailsWith<IllegalStateException> { preferences.dynamicTheme = false }
        assertEquals(true, preferences.dynamicTheme)
    }

    @Test
    fun writesRemainObservableAndPersistAcrossInstances() {
        val backing = MapSettings()
        val settings = Settings(backing)
        val observer = SnapshotStateObserver { it() }
        var invalidations = 0
        observer.start()
        try {
            Snapshot.sendApplyNotifications()
            observer.observeReads(settings, { invalidations++ }) {
                assertEquals(true, settings.dynamicTheme)
            }

            settings.dynamicTheme = false
            Snapshot.sendApplyNotifications()

            assertEquals(1, invalidations)
            assertEquals(false, settings.dynamicTheme)
            assertEquals(false, Settings(backing).dynamicTheme)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test
    fun importingAndRestoringBackupSettingsRefreshesCachedValues() {
        val settings = Settings(MapSettings())
        val disabled = PortableSettings(entries = listOf(PortableSetting("dynamicTheme", SettingType.BOOLEAN, "false")))
        val enabled = PortableSettings(entries = listOf(PortableSetting("dynamicTheme", SettingType.BOOLEAN, "true")))

        settings.importBackupSettings(disabled)
        assertEquals(false, settings.dynamicTheme)
        settings.restoreBackupSettingsSnapshot(enabled)
        assertEquals(true, settings.dynamicTheme)
    }

    @Test
    fun backupRestoreDoesNotTransferSentryConsent() {
        val backing = MapSettings()
        val settings = Settings(backing)
        settings.sentryEnabled = true
        val backup = settings.exportBackupSettings()

        assertEquals(true, backing.getBooleanOrNull(SentryEnabledKey))
        assertFalse(backup.entries.any { it.key == SentryEnabledKey })

        settings.sentryEnabled = false
        settings.importBackupSettings(
            PortableSettings(entries = listOf(PortableSetting(SentryEnabledKey, SettingType.BOOLEAN, "true"))),
        )
        assertEquals(false, settings.sentryEnabled)
    }

    @Test
    fun invalidEnumOrdinalsUseTheDefaultWithoutOverwritingStorage() {
        for (ordinal in listOf(-1, Int.MAX_VALUE)) {
            val backing = MapSettings("themeMode" to ordinal, "musicVideoQuality" to ordinal)
            val settings = Settings(backing)

            assertEquals(ThemeMode.AUTO, settings.themeMode)
            assertEquals(MusicVideoQuality.AUTO, settings.musicVideoQuality)
            assertEquals(ordinal, backing.getIntOrNull("themeMode"))
            assertEquals(ordinal, backing.getIntOrNull("musicVideoQuality"))
        }
    }

    private class CountingSettings(
        private val delegate: KmpSettings = MapSettings(),
        private val failWrites: Boolean = false,
    ) : KmpSettings by delegate {
        var booleanWrites = 0

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) {
            booleanWrites++
            check(!failWrites) { "write failed" }
            delegate.putBoolean(key, value)
        }
    }
}
