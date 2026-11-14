/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.settings

import com.russhwolf.settings.Settings as KmpSettings

internal expect fun defaultKmpSettings(): KmpSettings

internal val largeSettingKeys =
    setOf(
        "aiSlopBlocklistCache",
        "lastHomePageJson",
        "persistentPlaybackState",
        "persistentPlaybackPosition",
        "playbackClientHealthJson",
        "likedSongsJson",
    )

internal interface FlushableSettings {
    fun flush()
}

internal interface DeferredSecretMigrationSettings {
    val secretRecoveryPending: Boolean
}

internal fun migrateLargeSetting(
    settings: KmpSettings,
    key: String,
    fileExists: Boolean,
    write: (String) -> Unit,
) {
    val legacy = settings.getStringOrNull(key) ?: return
    if (!fileExists) write(legacy)
    settings.remove(key)
}

internal interface BatchSettings {
    fun applyBatch(
        entries: List<PortableSetting>,
        removedKeys: Set<String>,
    )
}

internal fun KmpSettings.applyBatch(
    entries: List<PortableSetting>,
    removedKeys: Set<String>,
) {
    if (this is BatchSettings) {
        applyBatch(entries, removedKeys)
        return
    }
    removedKeys.forEach(::remove)
    entries.forEach { entry ->
        when (entry.type) {
            SettingType.STRING -> putString(entry.key, entry.value)
            SettingType.BOOLEAN -> putBoolean(entry.key, entry.value.toBooleanStrict())
            SettingType.INT -> putInt(entry.key, entry.value.toInt())
            SettingType.LONG -> putLong(entry.key, entry.value.toLong())
            SettingType.FLOAT -> putFloat(entry.key, entry.value.toFloat())
        }
    }
}

internal fun KmpSettings.removeExisting(vararg keys: String) {
    val existing = keys.filterTo(mutableSetOf(), ::hasKey)
    if (existing.isNotEmpty()) applyBatch(emptyList(), existing)
}
