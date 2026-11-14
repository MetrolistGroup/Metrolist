package com.metrolist.shared.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.russhwolf.settings.Settings
import java.security.KeyStore
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface SecureSettingsKeyAccess {
    fun loadOrCreate(): SecretKey

    fun delete()
}

internal class AndroidSecureSettings(
    context: Context,
    private val delegate: Settings,
    private val keyAccess: SecureSettingsKeyAccess = AndroidKeystoreKeyAccess,
) : Settings by delegate,
    BatchSettings,
    FlushableSettings {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var key: SecretKey? = null

    init {
        migratePlaintextSecrets()
    }

    override fun flush() {
        (delegate as? FlushableSettings)?.flush()
    }

    override fun applyBatch(
        entries: List<PortableSetting>,
        removedKeys: Set<String>,
    ) {
        val (special, ordinary) = entries.partition { it.key in secureSettingsKeys }
        val secretsToRemove = removedKeys.filter { it in secureSettingsKeys }
        if (secretsToRemove.isNotEmpty() || special.isNotEmpty()) {
            val previous = (secretsToRemove + special.map { it.key }).associateWith { preferences.getString(it, null) }
            val editor = preferences.edit()
            secretsToRemove.forEach(editor::remove)
            if (special.isNotEmpty()) {
                withKey { secretKey ->
                    special.forEach { entry -> editor.putString(entry.key, encrypt(entry.key, entry.value, secretKey)) }
                }
            }
            if (!editor.commit()) {
                val rollback = preferences.edit()
                previous.forEach { (name, value) ->
                    if (value == null) rollback.remove(name) else rollback.putString(name, value)
                }
                rollback.commit()
                error("Could not persist encrypted settings")
            }
        }
        delegate.applyBatch(ordinary, removedKeys + special.map { it.key })
    }

    override val keys: Set<String>
        get() = delegate.keys + preferences.all.keys

    override val size: Int
        get() = keys.size

    override fun clear() {
        delegate.clear()
        preferences.edit().clear().commit()
    }

    override fun remove(key: String) {
        delegate.remove(key)
        if (key in secureSettingsKeys) preferences.edit().remove(key).commit()
    }

    override fun hasKey(key: String): Boolean = if (key in secureSettingsKeys) getStringOrNull(key) != null else delegate.hasKey(key)

    override fun putString(
        key: String,
        value: String,
    ) {
        if (key !in secureSettingsKeys) {
            delegate.putString(key, value)
            return
        }
        putSecret(key, value)
        delegate.remove(key)
    }

    override fun getString(
        key: String,
        defaultValue: String,
    ): String = getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? =
        if (key in secureSettingsKeys) {
            preferences.getString(key, null)?.let { decrypt(key, it) }
        } else {
            delegate.getStringOrNull(key)
        }

    private fun migratePlaintextSecrets() {
        for (secretKey in secureSettingsKeys) {
            if (preferences.contains(secretKey)) {
                if (delegate.hasKey(secretKey)) delegate.remove(secretKey)
                continue
            }
            val plaintext = delegate.getStringOrNull(secretKey) ?: continue
            runCatching { putSecret(secretKey, plaintext) }
                .onSuccess { delegate.remove(secretKey) }
        }
    }

    private fun putSecret(
        name: String,
        value: String,
    ) {
        val encrypted = withKey { encrypt(name, value, it) }
        val previous = preferences.getString(name, null)
        if (!preferences.edit().putString(name, encrypted).commit()) {
            val rollback = preferences.edit()
            if (previous == null) rollback.remove(name) else rollback.putString(name, previous)
            rollback.commit()
            error("Could not persist encrypted setting")
        }
    }

    private fun encrypt(
        name: String,
        value: String,
        secretKey: SecretKey,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(name.encodeToByteArray())
        val encrypted = cipher.doFinal(value.encodeToByteArray())
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(
        name: String,
        value: String,
    ): String? =
        runCatching {
            val payload = Base64.decode(value, Base64.NO_WRAP)
            require(payload.size > GCM_NONCE_BYTES)
            withKey { secretKey ->
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_NONCE_BYTES))
                cipher.updateAAD(name.encodeToByteArray())
                cipher.doFinal(payload, GCM_NONCE_BYTES, payload.size - GCM_NONCE_BYTES).decodeToString()
            }
        }.getOrElse { error ->
            if (error is IllegalArgumentException || error is BadPaddingException || error is IllegalBlockSizeException) {
                preferences.edit().remove(name).commit()
            }
            null
        }

    @Synchronized
    private fun <T> withKey(block: (SecretKey) -> T): T {
        val secretKey = key ?: keyAccess.loadOrCreate().also { key = it }
        return try {
            block(secretKey)
        } catch (e: KeyPermanentlyInvalidatedException) {
            keyAccess.delete()
            key = null
            val replacement = keyAccess.loadOrCreate()
            key = replacement
            block(replacement)
        }
    }

    private companion object {
        val AndroidKeystoreKeyAccess =
            object : SecureSettingsKeyAccess {
                override fun loadOrCreate(): SecretKey {
                    val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
                    return KeyGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                        .apply {
                            init(
                                KeyGenParameterSpec
                                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                    .setRandomizedEncryptionRequired(true)
                                    .build(),
                            )
                        }.generateKey()
                }

                override fun delete() {
                    KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(KEY_ALIAS)
                }
            }

        const val PREFERENCES_NAME = "secure_settings"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "metrolist.settings.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
