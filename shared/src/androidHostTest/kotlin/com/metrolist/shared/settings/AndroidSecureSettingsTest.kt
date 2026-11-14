package com.metrolist.shared.settings

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.russhwolf.settings.MapSettings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.InvalidKeyException
import java.security.SecureRandom
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AndroidSecureSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences("secure_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun ordinarySettingsDoNotLoadTheKeyButSecretsStillMigrateAndDecrypt() {
        val keyAccess = TestKeyAccess()
        val backing = MapSettings("appLanguage" to "SYSTEM")
        val settings = AndroidSecureSettings(context, backing, keyAccess)
        assertEquals("SYSTEM", settings.getStringOrNull("appLanguage"))
        assertNull(settings.getStringOrNull("innerTubeCookie"))
        assertEquals(0, keyAccess.loads)
        settings.putString("innerTubeCookie", "saved cookie")
        assertEquals(1, keyAccess.loads)
        val reopened = AndroidSecureSettings(context, backing, keyAccess)
        assertEquals(1, keyAccess.loads)
        assertEquals("saved cookie", reopened.getStringOrNull("innerTubeCookie"))
        assertEquals(2, keyAccess.loads)
        backing.putString("visitorData", "legacy visitor")
        val migrated = AndroidSecureSettings(context, backing, keyAccess)
        assertEquals("legacy visitor", migrated.getStringOrNull("visitorData"))
        assertFalse(backing.hasKey("visitorData"))
        assertEquals(3, keyAccess.loads)
    }

    @Test
    fun customServerCredentialsMigrateToEncryptedSettings() {
        val url = "wss://user:secret@host.example/ws?access_token=private"
        val backing =
            MapSettings().apply {
                putString("listenTogetherServerUrl", url)
                putString("listenTogetherCustomServers", url)
            }
        val settings = AndroidSecureSettings(context, backing, TestKeyAccess())
        for (name in listOf("listenTogetherServerUrl", "listenTogetherCustomServers")) {
            assertFalse(backing.hasKey(name))
            assertEquals(url, settings.getStringOrNull(name))
            val stored = context.getSharedPreferences("secure_settings", Context.MODE_PRIVATE).getString(name, null).orEmpty()
            assertFalse("private" in stored)
            assertFalse("secret" in stored)
        }
    }

    @Test
    fun failedMigrationAndWriteRetainPlaintextUntilEncryptionSucceeds() {
        val keyAccess = TestKeyAccess()
        keyAccess.current.unavailable = true
        val delegate = MapSettings().apply { putString("innerTubeCookie", "original cookie") }
        val settings = AndroidSecureSettings(context, delegate, keyAccess)

        assertFails { settings.putString("innerTubeCookie", "replacement cookie") }
        assertEquals("original cookie", delegate.getStringOrNull("innerTubeCookie"))
        keyAccess.current.unavailable = false
        settings.putString("innerTubeCookie", "replacement cookie")
        assertNull(delegate.getStringOrNull("innerTubeCookie"))
        assertEquals("replacement cookie", settings.getStringOrNull("innerTubeCookie"))
    }

    @Test
    fun invalidatedKeyOnWriteIsReplacedAndOnlyUnreadableSecretsAreRemoved() {
        val keyAccess = TestKeyAccess()
        val settings = AndroidSecureSettings(context, MapSettings(), keyAccess)
        settings.putString("innerTubeCookie", "old cookie")
        settings.putString("dataSyncId", "old sync")
        settings.putString("ordinary", "retained")

        keyAccess.invalidate()
        settings.putString("visitorData", "new visitor")

        assertEquals(1, keyAccess.deletions)
        assertFalse(settings.hasKey("innerTubeCookie"))
        assertNull(settings.getStringOrNull("innerTubeCookie"))
        assertNull(settings.getStringOrNull("dataSyncId"))
        assertEquals("new visitor", settings.getStringOrNull("visitorData"))
        assertEquals("retained", settings.getStringOrNull("ordinary"))
    }

    @Test
    fun invalidatedKeyOnReadIsReplacedBeforeLaterSecureWrites() {
        val keyAccess = TestKeyAccess()
        val settings = AndroidSecureSettings(context, MapSettings(), keyAccess)
        settings.putString("innerTubeCookie", "old cookie")

        keyAccess.invalidate()
        assertNull(settings.getStringOrNull("innerTubeCookie"))
        assertFalse(settings.hasKey("innerTubeCookie"))
        assertEquals(1, keyAccess.deletions)

        settings.applyBatch(listOf(PortableSetting("visitorData", SettingType.STRING, "fresh visitor")), emptySet())
        assertEquals("fresh visitor", settings.getStringOrNull("visitorData"))
    }

    @Test
    fun temporaryKeyFailureDoesNotDeleteDecryptableSecret() {
        val keyAccess = TestKeyAccess()
        val settings = AndroidSecureSettings(context, MapSettings(), keyAccess)
        settings.putString("innerTubeCookie", "retained cookie")

        keyAccess.current.unavailable = true
        assertFalse(settings.hasKey("innerTubeCookie"))
        assertNull(settings.getStringOrNull("innerTubeCookie"))
        assertEquals(0, keyAccess.deletions)

        keyAccess.current.unavailable = false
        assertEquals("retained cookie", settings.getStringOrNull("innerTubeCookie"))
    }

    private class TestKeyAccess : SecureSettingsKeyAccess {
        var current = TestKey()
            private set
        var deletions = 0
            private set

        var loads = 0
            private set

        override fun loadOrCreate(): SecretKey {
            loads++
            return current
        }

        override fun delete() {
            deletions++
            current = TestKey()
        }

        fun invalidate() {
            current.invalidated = true
        }
    }

    private class TestKey : SecretKey {
        private val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        var invalidated = false
        var unavailable = false

        override fun getAlgorithm(): String = "AES"

        override fun getFormat(): String = "RAW"

        override fun getEncoded(): ByteArray {
            if (invalidated) throw KeyPermanentlyInvalidatedException()
            if (unavailable) throw InvalidKeyException("temporarily unavailable")
            return bytes
        }
    }
}
