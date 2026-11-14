package com.metrolist.shared.settings

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings

@OptIn(ExperimentalSettingsImplementation::class)
internal class IosSecureSettings(
    private val delegate: Settings,
    private val secure: Settings = KeychainSettings("com.metrolist.MetrolistApp.settings"),
) : Settings by delegate {
    init {
        migratePlaintextSecrets()
    }

    override val keys: Set<String>
        get() = delegate.keys + runCatching { secure.keys }.getOrDefault(emptySet())

    override val size: Int
        get() = keys.size

    override fun clear() {
        secure.clear()
        delegate.clear()
    }

    override fun remove(key: String) {
        if (key in secureSettingsKeys) secure.remove(key)
        delegate.remove(key)
    }

    override fun hasKey(key: String): Boolean =
        if (key in secureSettingsKeys) runCatching { secure.hasKey(key) }.getOrDefault(false) else delegate.hasKey(key)

    override fun putString(
        key: String,
        value: String,
    ) {
        if (key !in secureSettingsKeys) {
            delegate.putString(key, value)
            return
        }
        secure.putString(key, value)
        delegate.remove(key)
    }

    override fun getString(
        key: String,
        defaultValue: String,
    ): String = getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? =
        if (key in secureSettingsKeys) {
            secure.getStringOrNull(key)
        } else {
            delegate.getStringOrNull(key)
        }

    private fun migratePlaintextSecrets() {
        secureSettingsKeys.forEach { key ->
            val plaintext = delegate.getStringOrNull(key) ?: return@forEach
            if (!secure.hasKey(key)) secure.putString(key, plaintext)
            delegate.remove(key)
        }
    }
}
