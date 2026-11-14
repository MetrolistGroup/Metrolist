package com.metrolist.shared.discord

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.secureSettingsKeys
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordAuthSessionStoreTest {
    @Test
    fun pendingSessionSurvivesRecreationExpiresAndIsExcludedFromBackups() {
        val ordinary = MapSettings()
        val settings = Settings(ordinary)
        val session = DiscordAuthSession("https://discord.com/oauth2/authorize", "metrolist-auth://discord", "verifier", "state")
        var now = 100L
        DiscordAuthSessionStore(settings) { now }.save(session)
        val restoredSettings = Settings(ordinary)
        val restored = DiscordAuthSessionStore(restoredSettings) { now }
        assertEquals(session, restored.pending())
        assertFalse(settings.exportBackupSettings().entries.any { it.key == "discordPendingAuthSession" })
        assertTrue("discordPendingAuthSession" in secureSettingsKeys)
        now += 120_000L
        assertNull(restored.pending())
        assertEquals("", restoredSettings.discordPendingAuthSession)
    }

    @Test
    fun clearingOrCorruptingSessionPreventsReuse() {
        val settings = Settings(MapSettings())
        val store = DiscordAuthSessionStore(settings) { 0L }
        store.save(DiscordAuthSession("authorize", "redirect", "verifier", "state"))
        store.clear()
        assertNull(store.pending())
        settings.discordPendingAuthSession = "invalid"
        assertNull(store.pending())
        assertEquals("", settings.discordPendingAuthSession)
    }
}
