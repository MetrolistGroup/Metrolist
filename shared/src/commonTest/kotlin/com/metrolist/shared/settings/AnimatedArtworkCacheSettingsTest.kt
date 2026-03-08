package com.metrolist.shared.settings

import com.metrolist.shared.constants.AnimatedArtworkCacheSizeKey
import com.metrolist.shared.constants.AnimatedCoverCacheSizeKey
import com.metrolist.shared.constants.CanvasCacheSizeKey
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AnimatedArtworkCacheSettingsTest {
    @Test
    fun combinesOldBudgetsOnceAndKeepsDisabledAndCustomValues() {
        for ((canvas, covers, expected) in listOf(Triple(128, 256, 384), Triple(0, 0, 0), Triple(0, 1024, 1024))) {
            val backing = MapSettings(CanvasCacheSizeKey to canvas, AnimatedCoverCacheSizeKey to covers)
            val settings = Settings(backing)
            assertEquals(expected, settings.animatedArtworkCacheSize)
            assertFalse(backing.hasKey(CanvasCacheSizeKey))
            assertFalse(backing.hasKey(AnimatedCoverCacheSizeKey))
            settings.animatedArtworkCacheSize = 512
            assertEquals(512, Settings(backing).animatedArtworkCacheSize)
        }
        assertEquals(2048, Settings(MapSettings()).animatedArtworkCacheSize)
        assertEquals(1152, Settings(MapSettings(CanvasCacheSizeKey to 128)).animatedArtworkCacheSize)
        assertEquals(1024, Settings(MapSettings(AnimatedCoverCacheSizeKey to 0)).animatedArtworkCacheSize)
        assertEquals(Int.MAX_VALUE, combinedAnimatedArtworkCacheLimit(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun oldPortableAndLegacyBackupsRestoreTheCombinedBudget() {
        val settings = Settings(MapSettings())
        val old =
            PortableSettings(
                entries =
                    listOf(
                        PortableSetting(CanvasCacheSizeKey, SettingType.INT, "256"),
                        PortableSetting(AnimatedCoverCacheSizeKey, SettingType.INT, "512"),
                    ),
            )
        settings.importBackupSettings(old)
        assertEquals(768, settings.animatedArtworkCacheSize)
        val exported = settings.exportBackupSettings()
        assertFalse(exported.entries.any { it.key == CanvasCacheSizeKey || it.key == AnimatedCoverCacheSizeKey })
        val restored = Settings(MapSettings())
        restored.importBackupSettings(exported)
        assertEquals(768, restored.animatedArtworkCacheSize)
        settings.importBackupSettings(
            settings.portableSettingsFromLegacy(
                mapOf(
                    CanvasCacheSizeKey to "128",
                    AnimatedCoverCacheSizeKey to 256,
                ),
            ),
        )
        assertEquals(384, settings.animatedArtworkCacheSize)
    }

    @Test
    fun unifiedValueWinsWhenOldAndNewSettingsCoexist() {
        val settings =
            Settings(
                MapSettings(
                    CanvasCacheSizeKey to 128,
                    AnimatedCoverCacheSizeKey to 256,
                    AnimatedArtworkCacheSizeKey to 4096,
                ),
            )
        assertEquals(4096, settings.animatedArtworkCacheSize)
        settings.importBackupSettings(
            PortableSettings(
                entries =
                    listOf(
                        PortableSetting(CanvasCacheSizeKey, SettingType.INT, "128"),
                        PortableSetting(AnimatedCoverCacheSizeKey, SettingType.INT, "256"),
                        PortableSetting(AnimatedArtworkCacheSizeKey, SettingType.INT, "1024"),
                    ),
            ),
        )
        assertEquals(1024, settings.animatedArtworkCacheSize)
    }
}
