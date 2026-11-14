package com.metrolist.shared.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyV13AccountSettingsTest {
    @Test
    fun migratesLegacyLastFmAccountKeys() {
        val backing =
            MapSettings(
                "lastfmUsername" to "listener",
                "lastfmSession" to "session-key",
            )
        val settings = Settings(backing)

        settings.migrateLegacyV13AccountSettings()

        assertEquals("listener", settings.lastFmUsername)
        assertEquals("session-key", settings.lastFmSession)
    }

    @Test
    fun keepsExistingKmpAccountValues() {
        val backing =
            MapSettings(
                "lastfmUsername" to "legacy",
                "lastFmUsername" to "current",
            )
        val settings = Settings(backing)

        settings.migrateLegacyV13AccountSettings()

        assertEquals("current", settings.lastFmUsername)
    }
}
