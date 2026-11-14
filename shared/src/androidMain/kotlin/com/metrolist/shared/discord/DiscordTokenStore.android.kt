package com.metrolist.shared.discord

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual object DiscordTokenStore {
    private const val PREFS_NAME = "discord_token"
    private const val TOKEN_KEY = "access_token"
    private const val REFRESH_TOKEN_KEY = "refresh_token"
    private const val EXPIRES_AT_KEY = "expires_at"
    private const val DEVICE_VENDOR_ID_KEY = "device_vendor_id"
    private const val CLIENT_UUID_KEY = "client_uuid"
    private const val TAG = "DiscordSvc"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun legacyPreferences(context: Context): SharedPreferences =
        prefs ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }

    fun init(context: Context) {
        try {
            migrateLegacyCredentials(context)
        } catch (error: Exception) {
            Logger.e(TAG, "Discord credential migration failed", error)
        }
    }

    fun migrateLegacyCredentials(context: Context) {
        synchronized(this) { migrateLegacyCredentials(legacyPreferences(context)) }
    }

    internal fun migrateLegacyCredentials(
        legacy: SharedPreferences,
        decrypt: (String) -> String = AesKeystore::decrypt,
    ) {
        val settings = Settings.shared
        val access =
            legacy.getString(TOKEN_KEY, null)?.takeIf { settings.discordAccessToken.isEmpty() }?.let { encrypted ->
                try {
                    decrypt(encrypted)
                } catch (error: AEADBadTagException) {
                    // The keystore key no longer matches this ciphertext, so the old login can never be read again.
                    Logger.e(TAG, "Dropping undecryptable legacy Discord credentials", error)
                    null
                }
            }
        if (access != null) {
            val refresh = legacy.getString(REFRESH_TOKEN_KEY, null)?.let(decrypt).orEmpty()
            settings.discordRefreshToken = refresh
            settings.discordExpiresAt = legacy.getLong(EXPIRES_AT_KEY, 0L)
            settings.discordDeviceVendorId = legacy.getString(DEVICE_VENDOR_ID_KEY, null).orEmpty()
            settings.discordClientUuid = legacy.getString(CLIENT_UUID_KEY, null).orEmpty()
            settings.discordAccessToken = access
            settings.flush()
        }
        check(legacy.edit().clear().commit()) { "Could not remove migrated Discord credentials" }
    }

    actual fun storeFull(
        accessToken: String,
        refreshToken: String,
        expiresInSec: Long,
    ) {
        val settings = Settings.shared
        settings.discordRefreshToken = refreshToken
        settings.discordExpiresAt = if (expiresInSec > 0) System.currentTimeMillis() / 1000L + expiresInSec else 0L
        settings.discordAccessToken = accessToken
    }

    actual fun storeAccessToken(accessToken: String) {
        Settings.shared.discordAccessToken = accessToken
    }

    actual fun retrieve(): String? = Settings.shared.discordAccessToken.takeIf(String::isNotEmpty)

    actual fun getRefreshToken(): String? = Settings.shared.discordRefreshToken.takeIf(String::isNotEmpty)

    actual fun getExpiresAt(): Long = Settings.shared.discordExpiresAt

    actual fun getDeviceVendorId(): String? =
        synchronized(this) {
            Settings.shared.discordDeviceVendorId.ifEmpty {
                UUID.randomUUID().toString().also { Settings.shared.discordDeviceVendorId = it }
            }
        }

    actual fun getClientUuid(): String? =
        synchronized(this) {
            Settings.shared.discordClientUuid.ifEmpty { UUID.randomUUID().toString().also { Settings.shared.discordClientUuid = it } }
        }

    actual fun clear() {
        check(legacyPreferences(AndroidContextProvider.applicationContext).edit().clear().commit()) {
            "Could not remove legacy Discord credentials"
        }
        Settings.shared.discordAccessToken = ""
        Settings.shared.discordRefreshToken = ""
        Settings.shared.discordExpiresAt = 0L
        Settings.shared.discordDeviceVendorId = ""
        Settings.shared.discordClientUuid = ""
        Logger.d(TAG, "tokenStore: cleared")
    }

    internal object AesKeystore {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "metrolist_discord_token_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_SIZE = 128

        private var keyStore: KeyStore? = null

        @Synchronized
        private fun getKeyStore(): KeyStore {
            keyStore?.let { return it }
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore = ks
            return ks
        }

        fun getOrCreateKey(): SecretKey {
            getKeyStore().getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
            val spec =
                KeyGenParameterSpec
                    .Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
        }

        fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            if (iv.size != GCM_IV_SIZE) {
                throw IllegalStateException("Unexpected IV size: ${iv.size}")
            }
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(GCM_IV_SIZE + ciphertext.size)
            iv.copyInto(combined, destinationOffset = 0, startIndex = 0, endIndex = GCM_IV_SIZE)
            ciphertext.copyInto(combined, destinationOffset = GCM_IV_SIZE)
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        }

        fun decrypt(encrypted: String): String {
            val combined = Base64.decode(encrypted, Base64.NO_WRAP)
            if (combined.size < GCM_IV_SIZE) {
                throw IllegalArgumentException("Encrypted data too short")
            }
            val iv = combined.copyOfRange(0, GCM_IV_SIZE)
            val ciphertext = combined.copyOfRange(GCM_IV_SIZE, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_SIZE, iv))
            return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }
    }
}
