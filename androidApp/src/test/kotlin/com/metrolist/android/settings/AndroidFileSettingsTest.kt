package com.metrolist.android.settings

import android.content.Context
import com.metrolist.shared.settings.PortableSetting
import com.metrolist.shared.settings.PortableSettings
import com.metrolist.shared.settings.SettingType
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import com.russhwolf.settings.Settings as KmpSettings

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AndroidFileSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun migratesLegacyBlobsAndKeepsReadAfterWriteAndBackupRestoreCoherent() {
        val backing = MapSettings("persistentPlaybackState" to "old queue", "aiSlopBlocklistCache" to "blocklist")
        val store = fileSettings(backing)
        assertEquals("old queue", store.getStringOrNull("persistentPlaybackState"))
        assertFalse(backing.hasKey("persistentPlaybackState"))
        assertFalse(backing.hasKey("aiSlopBlocklistCache"))
        assertEquals("blocklist", File(context.cacheDir, "settings-blobs/aiSlopBlocklistCache").readText())
        store.putString("persistentPlaybackState", "new queue")
        assertEquals("new queue", store.getStringOrNull("persistentPlaybackState"))
        assertEquals("new queue", File(context.noBackupFilesDir, "settings-blobs/persistentPlaybackState").readText())
        assertFalse(backing.hasKey("persistentPlaybackState"))
        val settings = Settings(store)
        settings.importBackupSettings(
            PortableSettings(entries = listOf(PortableSetting("lastHomePageJson", SettingType.STRING, "restored"))),
        )
        assertEquals("restored", File(context.noBackupFilesDir, "settings-blobs/lastHomePageJson").readText())
        assertTrue(settings.exportBackupSettings().entries.any { it.key == "persistentPlaybackState" && it.value == "new queue" })
        store.remove("persistentPlaybackState")
        assertFalse(store.hasKey("persistentPlaybackState"))
        assertFalse(store.keys.contains("persistentPlaybackState"))
    }

    @Test
    fun failedMigrationKeepsLegacyValueForRetry() {
        val directory = File(context.noBackupFilesDir, "settings-blobs")
        directory.writeText("prevents creating a directory")
        val backing = MapSettings("lastHomePageJson" to "home")
        val store = fileSettings(backing)
        assertEquals("home", store.getStringOrNull("lastHomePageJson"))
        assertEquals("home", backing.getStringOrNull("lastHomePageJson"))
        assertTrue(directory.delete())
        val retried = fileSettings(backing)
        assertEquals("home", retried.getStringOrNull("lastHomePageJson"))
        assertFalse(backing.hasKey("lastHomePageJson"))
    }

    private fun fileSettings(backing: KmpSettings): KmpSettings =
        Class
            .forName("com.metrolist.shared.settings.AndroidFileSettings")
            .getDeclaredConstructor(Context::class.java, KmpSettings::class.java)
            .newInstance(context, backing) as KmpSettings
}
