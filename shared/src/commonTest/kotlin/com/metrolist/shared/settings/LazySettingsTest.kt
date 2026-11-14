package com.metrolist.shared.settings

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class LazySettingsTest {
    @Test
    fun untouchedKeysRoundTripWithoutInitializingDelegates() {
        val original =
            Settings(
                MapSettings(
                    "dynamicTheme" to false,
                    "savedWidth" to 1440,
                    "selectedThemeColor" to 42L,
                    "volume" to 0.75f,
                    "blockedSongIds" to "one,two",
                    "themeMode" to ThemeMode.DARK.ordinal,
                ),
            ).exportBackupSettings()
        val store = MapSettings()
        val settings = Settings(store)
        settings.importBackupSettings(original)
        assertEquals(original, settings.exportBackupSettings())
        assertFalse(settings.dynamicTheme)
        assertEquals(1440, settings.savedWidth)
        assertEquals(42L, settings.selectedThemeColor)
        assertEquals(0.75f, settings.volume)
        assertEquals("one,two", settings.blockedSongIds)
        assertEquals(ThemeMode.DARK, settings.themeMode)
    }

    @Test
    fun snapshotFlowObservesFirstReadWritesRestoreAndRemoval() =
        runTest {
            val store = MapSettings()
            val settings = Settings(store)
            val values = mutableListOf<Boolean>()
            val observer = backgroundScope.launch { snapshotFlow { settings.dynamicTheme }.collect(values::add) }
            runCurrent()
            assertEquals(listOf(true), values)
            settings.dynamicTheme = false
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertEquals(listOf(true, false), values)
            settings.savedWidth = 900
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertEquals(listOf(true, false), values)
            settings.importBackupSettings(PortableSettings(entries = listOf(PortableSetting("dynamicTheme", SettingType.BOOLEAN, "true"))))
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertEquals(listOf(true, false, true), values)
            settings.dynamicTheme = false
            Snapshot.sendApplyNotifications()
            runCurrent()
            settings.restoreBackupSettingsSnapshot(PortableSettings(entries = emptyList()))
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertEquals(listOf(true, false, true, false, true), values)
            observer.cancel()
        }

    @Test
    fun batchRefreshUpdatesCachedAndUnreadKeys() {
        val store = MapSettings()
        val settings = Settings(store)
        assertEquals("", settings.blockedSongIds)
        settings.applyStringBatch(mapOf("blockedSongIds" to "song", "blockedArtistIds" to "artist"))
        assertEquals("song", settings.blockedSongIds)
        assertEquals("artist", settings.blockedArtistIds)
    }
}
