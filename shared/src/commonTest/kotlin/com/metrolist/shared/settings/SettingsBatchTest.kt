package com.metrolist.shared.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import com.russhwolf.settings.Settings as KmpSettings

class SettingsBatchTest {
    @Test
    fun migratedStartupPreservesPhotoCacheAndBatchesObsoleteKeyRemoval() {
        val backing = MapSettings()
        Settings(backing)
        var batches = 0
        var removals = 0
        val store =
            object : KmpSettings by backing, BatchSettings {
                override fun remove(key: String) {
                    removals++
                    backing.remove(key)
                }

                override fun applyBatch(
                    entries: List<PortableSetting>,
                    removedKeys: Set<String>,
                ) {
                    batches++
                    backing.applyBatch(entries, removedKeys)
                }
            }
        Settings(store)
        assertEquals(0, batches)
        assertEquals(0, removals)
        backing.putString("lastFmPhotoCacheJson", "{}")
        backing.putBoolean("keepScreenOn", true)
        backing.putString("listenTogetherPairedRoomCode", "ROOM1234")
        backing.putBoolean("deviceConnectRequireApproval", false)
        val migrated = Settings(store)
        assertEquals(KeepScreenOnMode.PLAYER_AND_LYRICS, migrated.keepScreenOnMode)
        assertEquals(1, batches)
        assertEquals(0, removals)
        assertEquals("{}", backing.getStringOrNull("lastFmPhotoCacheJson"))
        assertFalse(backing.hasKey("keepScreenOn"))
        assertFalse(backing.hasKey("listenTogetherPairedRoomCode"))
        assertFalse(backing.hasKey("deviceConnectRequireApproval"))
        Settings(store)
        assertEquals(1, batches)
    }

    @Test
    fun importAndRollbackUseOneBatchIncludingDeletionsAndMigrations() {
        val backing = MapSettings()
        var batches = 0
        val batchStore =
            object : KmpSettings by backing, BatchSettings {
                override fun applyBatch(
                    entries: List<PortableSetting>,
                    removedKeys: Set<String>,
                ) {
                    batches++
                    backing.applyBatch(entries, removedKeys)
                }
            }
        val settings = Settings(batchStore)
        settings.lastHomePageJson = "old"
        val snapshot = settings.exportBackupSettings()
        settings.importBackupSettings(
            PortableSettings(
                entries =
                    listOf(
                        PortableSetting("lastHomePageJson", SettingType.STRING, "new"),
                        PortableSetting("pureBlack", SettingType.BOOLEAN, "true"),
                        PortableSetting("playerCanvasEnabled", SettingType.BOOLEAN, "true"),
                        PortableSetting("enableAnimatedCovers", SettingType.BOOLEAN, "true"),
                    ),
            ),
        )
        assertEquals(1, batches)
        assertEquals("new", settings.lastHomePageJson)
        assertEquals(ThemeMode.PURE_BLACK, settings.themeMode)
        assertEquals(true, settings.enableAnimatedCovers)
        settings.restoreBackupSettingsSnapshot(snapshot)
        assertEquals(2, batches)
        assertEquals("old", settings.lastHomePageJson)
        assertFalse(backing.hasKey("enableAnimatedCovers"))
    }

    @Test
    fun invalidBackupIsRejectedBeforeAnyBatchMutation() {
        val backing = MapSettings()
        var batches = 0
        val settings =
            Settings(
                object : KmpSettings by backing, BatchSettings {
                    override fun applyBatch(
                        entries: List<PortableSetting>,
                        removedKeys: Set<String>,
                    ) {
                        batches++
                    }
                },
            )
        assertFailsWith<IllegalArgumentException> {
            settings.importBackupSettings(
                PortableSettings(entries = listOf(PortableSetting("enableAnimatedCovers", SettingType.BOOLEAN, "invalid"))),
            )
        }
        assertEquals(0, batches)
    }
}
