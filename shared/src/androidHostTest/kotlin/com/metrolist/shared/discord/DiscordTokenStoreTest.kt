package com.metrolist.shared.discord

import android.content.Context
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import com.russhwolf.settings.MapSettings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiscordTokenStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val legacy get() = context.getSharedPreferences("discord_token", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        AndroidContextProvider.applicationContext = context
        Settings.replaceSharedForTest(Settings(MapSettings()))
        legacy.edit().clear().commit()
    }

    @After
    fun tearDown() {
        legacy.edit().clear().commit()
        Settings.replaceSharedForTest(null)
    }

    private fun legacyLogin() {
        legacy
            .edit()
            .putString("access_token", "encrypted-A")
            .putString("refresh_token", "encrypted-refresh-A")
            .putLong("expires_at", 123L)
            .putString("device_vendor_id", "device-A")
            .putString("client_uuid", "client-A")
            .commit()
    }

    @Test
    fun migrationEntersBackupAndRestoredCredentialsReplaceTheActiveAccount() {
        legacyLogin()
        DiscordTokenStore.migrateLegacyCredentials(legacy) { it.removePrefix("encrypted-") }
        val accountA = Settings.shared.exportBackupSettings()
        assertTrue(legacy.all.isEmpty())
        assertEquals("A", DiscordTokenStore.retrieve())
        assertEquals(123L, DiscordTokenStore.getExpiresAt())
        assertEquals("device-A", DiscordTokenStore.getDeviceVendorId())
        DiscordTokenStore.storeFull("B", "refresh-B", 0)
        val accountB = Settings.shared.exportBackupSettings()
        Settings.shared.importBackupSettings(accountA)
        assertEquals("A", DiscordTokenStore.retrieve())
        Settings.shared.importBackupSettings(accountB)
        assertEquals("B", DiscordTokenStore.retrieve())
        assertEquals("refresh-B", DiscordTokenStore.getRefreshToken())
        Settings.shared.restoreBackupSettingsSnapshot(accountA)
        assertEquals("A", DiscordTokenStore.retrieve())
        val fresh = Settings(MapSettings())
        fresh.importBackupSettings(accountA)
        Settings.replaceSharedForTest(fresh)
        assertEquals("A", DiscordTokenStore.retrieve())
        DiscordTokenStore.clear()
        DiscordTokenStore.migrateLegacyCredentials(legacy) { error("Legacy login must not return") }
        assertNull(DiscordTokenStore.retrieve())
    }

    @Test
    fun explicitRestoreWinsAndEmptyRestoreCannotResurrectLegacyLogin() {
        val empty = Settings.shared.exportBackupSettings()
        legacyLogin()
        DiscordTokenStore.storeFull("restored-B", "refresh-B", 0)
        DiscordTokenStore.migrateLegacyCredentials(legacy) { error("Restored credentials take precedence") }
        assertEquals("restored-B", DiscordTokenStore.retrieve())
        Settings.shared.importBackupSettings(empty)
        DiscordTokenStore.migrateLegacyCredentials(legacy) { error("Migration already consumed the old login") }
        assertNull(DiscordTokenStore.retrieve())
    }

    @Test
    fun logoutBeforeInitializationAlsoConsumesLegacyCredentials() {
        legacyLogin()
        DiscordTokenStore.clear()
        DiscordTokenStore.migrateLegacyCredentials(legacy) { error("Logged-out credentials must not return") }
        assertTrue(legacy.all.isEmpty())
        assertNull(DiscordTokenStore.retrieve())
    }

    @Test
    fun undecryptableLegacyLoginIsDropped() {
        legacyLogin()
        DiscordTokenStore.migrateLegacyCredentials(legacy) { throw AEADBadTagException() }
        assertTrue(legacy.all.isEmpty())
        assertNull(DiscordTokenStore.retrieve())
    }

    @Test
    fun failedDecryptionKeepsLegacyLoginForRetry() {
        legacyLogin()
        assertFails { DiscordTokenStore.migrateLegacyCredentials(legacy) { error("Keystore unavailable") } }
        assertEquals("encrypted-A", legacy.getString("access_token", null))
        assertNull(DiscordTokenStore.retrieve())
    }
}
