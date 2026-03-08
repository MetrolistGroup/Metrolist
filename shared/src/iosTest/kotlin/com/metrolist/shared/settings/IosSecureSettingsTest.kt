package com.metrolist.shared.settings

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosSecureSettingsTest {
    private val key = "innerTubeCookie"

    @Test
    fun migrationPreservesExistingSecureValueAndMovesOtherPlaintextSecrets() {
        val plain =
            MapSettings().apply {
                putString(key, "old")
                putString("visitorData", "visitor")
                putString("ordinary", "value")
            }
        val secure = MapSettings().apply { putString(key, "new") }
        val settings = IosSecureSettings(plain, secure)
        assertEquals("new", settings.getStringOrNull(key))
        assertEquals("visitor", secure.getStringOrNull("visitorData"))
        assertFalse(plain.hasKey(key))
        assertFalse(plain.hasKey("visitorData"))
        settings.remove(key)
        assertFalse(secure.hasKey(key))
        settings.clear()
        assertTrue(secure.keys.isEmpty())
        assertTrue(plain.keys.isEmpty())
    }

    @Test
    fun failedMigrationRetainsPlaintextAndFailedLogoutIsReported() {
        val plain = MapSettings().apply { putString(key, "preserved") }
        val secure = MapSettings()
        val failing =
            object : Settings by secure {
                override fun putString(
                    key: String,
                    value: String,
                ) {
                    error("Keychain unavailable")
                }

                override fun remove(key: String) {
                    error("Keychain unavailable")
                }

                override fun clear() {
                    error("Keychain unavailable")
                }
            }
        assertFailsWith<IllegalStateException> { IosSecureSettings(plain, failing) }
        assertEquals("preserved", plain.getStringOrNull(key))
        secure.putString(key, "secure")
        val settings = IosSecureSettings(plain, failing)
        assertFailsWith<IllegalStateException> { settings.remove(key) }
        assertEquals("secure", secure.getStringOrNull(key))
        assertFailsWith<IllegalStateException> { settings.clear() }
        assertEquals("secure", secure.getStringOrNull(key))
    }
}
