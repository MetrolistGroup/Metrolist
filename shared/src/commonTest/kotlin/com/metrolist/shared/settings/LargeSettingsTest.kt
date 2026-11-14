package com.metrolist.shared.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.russhwolf.settings.Settings as KmpSettings

class LargeSettingsTest {
    @Test
    fun migrationCommitsBeforeRemovingLegacyAndCanRetryFailure() {
        val backing = MapSettings("persistentPlaybackState" to "queue")
        assertFailsWith<IllegalStateException> {
            migrateLargeSetting(backing, "persistentPlaybackState", false) { error("disk full") }
        }
        assertEquals("queue", backing.getStringOrNull("persistentPlaybackState"))
        var stored: String? = null
        migrateLargeSetting(backing, "persistentPlaybackState", false) {
            assertTrue(backing.hasKey("persistentPlaybackState"))
            stored = it
        }
        assertEquals("queue", stored)
        assertFalse(backing.hasKey("persistentPlaybackState"))
        migrateLargeSetting(backing, "persistentPlaybackState", true) { error("Already migrated") }
    }

    @Test
    fun committedFileWinsAfterInterruptedLegacyRemoval() {
        val backing = MapSettings("lastHomePageJson" to "old")
        migrateLargeSetting(backing, "lastHomePageJson", true) { error("Must preserve committed file") }
        assertFalse(backing.hasKey("lastHomePageJson"))
    }

    @Test
    fun blobsAreReadOnDemandAndKeepExistingBackupExclusions() {
        val backing = MapSettings()
        val source = Settings(backing)
        source.persistentPlaybackState = "queue"
        source.lastHomePageJson = "home"
        source.aiSlopBlocklistCache = "cache"
        source.playbackClientHealthJson = "health"
        backing.putString("lastHomePageJson", "updated home")
        assertEquals("updated home", source.lastHomePageJson)
        val backup = source.exportBackupSettings()
        assertFalse(backup.entries.any { it.key == "aiSlopBlocklistCache" || it.key == "playbackClientHealthJson" })
        val restored = Settings(MapSettings())
        restored.importBackupSettings(backup)
        assertEquals("queue", restored.persistentPlaybackState)
        assertEquals("updated home", restored.lastHomePageJson)
        assertEquals("", restored.aiSlopBlocklistCache)
        assertEquals("", restored.playbackClientHealthJson)
        restored.restoreBackupSettingsSnapshot(PortableSettings(entries = emptyList()))
        assertEquals("", restored.persistentPlaybackState)
        assertEquals("", restored.lastHomePageJson)
    }

    @Test
    fun restoreWaitsForBlobCommitAndPropagatesFailure() {
        val backing = MapSettings()
        var flushed = false
        val store =
            object : KmpSettings by backing, FlushableSettings {
                override fun flush() {
                    flushed = true
                    error("disk full")
                }
            }
        val settings = Settings(store)
        assertFailsWith<IllegalStateException> {
            settings.importBackupSettings(
                PortableSettings(entries = listOf(PortableSetting("lastHomePageJson", SettingType.STRING, "home"))),
            )
        }
        assertTrue(flushed)
    }
}
