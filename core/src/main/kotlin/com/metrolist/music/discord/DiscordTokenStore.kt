package com.metrolist.music.discord

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DiscordTokenStore {
    private const val PREFS_NAME = "discord_token"
    private const val TOKEN_KEY = "access_token"
    private const val REFRESH_TOKEN_KEY = "refresh_token"
    private const val EXPIRES_AT_KEY = "expires_at"
    private const val DEVICE_VENDOR_ID_KEY = "device_vendor_id"
    private const val CLIENT_UUID_KEY = "client_uuid"
    private const val TAG = "DiscordSvc"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val initDeferred = CompletableDeferred<Unit>()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            try {
                AesKeystore.getOrCreateKey()
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                initDeferred.complete(Unit)
            } catch (e: Exception) {
                Timber.e(e, "DiscordTokenStore init failed")
                initDeferred.completeExceptionally(e)
            }
        }
    }

    private fun encrypt(value: String?): String? {
        if (value == null) return null
        return try {
            AesKeystore.encrypt(value)
        } catch (e: Exception) {
            Timber.e(e, "DiscordTokenStore: encrypt failed")
            null
        }
    }

    private fun decrypt(value: String?): String? {
        if (value == null) return null
        return try {
            AesKeystore.decrypt(value)
        } catch (e: Exception) {
            Timber.e(e, "DiscordTokenStore: decrypt failed")
            null
        }
    }

    suspend fun retrieveSuspend(): String? {
        try {
            initDeferred.await()
        } catch (_: Exception) {
            return null
        }
        return retrieve()
    }

    fun store(token: String) {
        storeFull(token, refreshToken = "", expiresInSec = 0L)
    }

    fun storeFull(accessToken: String, refreshToken: String, expiresInSec: Long) {
        val encryptedToken = encrypt(accessToken) ?: run {
            Timber.tag(TAG).w("tokenStore: encryption failed, not persisting access token")
            return
        }
        prefs?.edit {
            putString(TOKEN_KEY, encryptedToken)
            if (refreshToken.isNotEmpty()) {
                val encryptedRefresh = encrypt(refreshToken)
                if (encryptedRefresh != null) {
                    putString(REFRESH_TOKEN_KEY, encryptedRefresh)
                } else {
                    Timber.tag(TAG).w("tokenStore: refresh token encryption failed, skipping")
                }
            }
            if (expiresInSec > 0L) {
                val expiresAt = (System.currentTimeMillis() / 1000L) + expiresInSec
                putLong(EXPIRES_AT_KEY, expiresAt)
            }
        }
        Timber.tag(TAG).d(
            "tokenStore: stored (accessToken length=%d, refreshToken present=%s, expiresIn=%d)",
            accessToken.length,
            refreshToken.isNotEmpty(),
            expiresInSec,
        )
    }

    fun storeAccessToken(accessToken: String) {
        val encryptedToken = encrypt(accessToken) ?: run {
            Timber.tag(TAG).w("tokenStore: encryption failed, not persisting access token")
            return
        }
        prefs?.edit {
            putString(TOKEN_KEY, encryptedToken)
        }
        Timber.tag(TAG).d("tokenStore: access token updated (length=%d)", accessToken.length)
    }

    fun retrieve(): String? {
        val encrypted = prefs?.getString(TOKEN_KEY, null)
        val token = decrypt(encrypted)
        Timber.tag(TAG).d("tokenStore: retrieve (found=%s)", !token.isNullOrEmpty())
        return token
    }

    fun getRefreshToken(): String? {
        val encrypted = prefs?.getString(REFRESH_TOKEN_KEY, null)
        val token = decrypt(encrypted)
        Timber.tag(TAG).d("tokenStore: getRefreshToken (found=%s)", !token.isNullOrEmpty())
        return token
    }

    fun getExpiresAt(): Long {
        val expiresAt = prefs?.getLong(EXPIRES_AT_KEY, 0L) ?: 0L
        Timber.tag(TAG).d("tokenStore: getExpiresAt (value=%d)", expiresAt)
        return expiresAt
    }

    fun getDeviceVendorId(): String? = getOrCreateId(DEVICE_VENDOR_ID_KEY, "device_vendor_id")

    fun getClientUuid(): String? = getOrCreateId(CLIENT_UUID_KEY, "client_uuid")

    private fun getOrCreateId(key: String, logName: String): String? = synchronized(this) {
        val p = prefs ?: return null
        val existing = p.getString(key, null)
        if (existing != null) return@synchronized existing
        val newId = UUID.randomUUID().toString()
        p.edit { putString(key, newId) }
        Timber.tag(TAG).d("tokenStore: generated new %s", logName)
        newId
    }

    fun clear() {
        prefs?.edit {
            remove(TOKEN_KEY)
            remove(REFRESH_TOKEN_KEY)
            remove(EXPIRES_AT_KEY)
            remove(DEVICE_VENDOR_ID_KEY)
            remove(CLIENT_UUID_KEY)
        }
        Timber.tag(TAG).d("tokenStore: cleared")
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

        @Volatile
        private var testKey: SecretKey? = null

        fun setTestKey(key: SecretKey?) {
            testKey = key
        }

        fun getOrCreateKey(): SecretKey {
            testKey?.let { return it }
            getKeyStore().getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
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
