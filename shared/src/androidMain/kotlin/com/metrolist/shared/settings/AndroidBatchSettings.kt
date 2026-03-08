@file:OptIn(ExperimentalSettingsApi::class, ExperimentalSettingsImplementation::class)

package com.metrolist.shared.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

internal class AndroidBatchSettings(
    private val store: DataStore<Preferences>,
) : Settings,
    BatchSettings,
    FlushableSettings {
    @Volatile
    private var snapshot = runBlocking { store.data.first() }
    private val writeLock = Any()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "settings-preferences").apply { isDaemon = true } }
    private var pendingSnapshot: Preferences? = null
    private var writeScheduled = false

    override val keys: Set<String> get() = snapshot.asMap().keys.mapTo(mutableSetOf()) { it.name }
    override val size: Int get() = snapshot.asMap().size

    override fun hasKey(key: String): Boolean = snapshot.contains(stringPreferencesKey(key))

    private fun edit(change: (MutablePreferences) -> Unit) {
        synchronized(writeLock) {
            val updated = snapshot.toMutablePreferences()
            change(updated)
            snapshot = updated.toPreferences()
            pendingSnapshot = snapshot
            if (!writeScheduled) {
                writeScheduled = true
                worker.submit { drainPending() }
            }
        }
    }

    private fun persistPending() {
        val pending = synchronized(writeLock) { pendingSnapshot } ?: return
        try {
            runBlocking { store.updateData { pending } }
            synchronized(writeLock) {
                if (pendingSnapshot === pending) pendingSnapshot = null
            }
        } catch (error: Exception) {
            Log.e("Settings", "Could not persist preferences", error)
            throw error
        }
    }

    private fun drainPending() {
        try {
            persistPending()
        } catch (error: Exception) {
            synchronized(writeLock) { writeScheduled = false }
            throw error
        }
        synchronized(writeLock) {
            if (pendingSnapshot == null) {
                writeScheduled = false
            } else {
                worker.submit { drainPending() }
            }
        }
    }

    override fun flush() {
        val write = synchronized(writeLock) { worker.submit { persistPending() } }
        write.get()
    }

    override fun clear() = edit { it.clear() }

    override fun remove(key: String) = edit { it.remove(stringPreferencesKey(key)) }

    override fun getIntOrNull(key: String): Int? = snapshot[intPreferencesKey(key)]

    override fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = getIntOrNull(key) ?: defaultValue

    override fun putInt(
        key: String,
        value: Int,
    ) = edit { it[intPreferencesKey(key)] = value }

    override fun getLongOrNull(key: String): Long? = snapshot[longPreferencesKey(key)]

    override fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = getLongOrNull(key) ?: defaultValue

    override fun putLong(
        key: String,
        value: Long,
    ) = edit { it[longPreferencesKey(key)] = value }

    override fun getStringOrNull(key: String): String? = snapshot[stringPreferencesKey(key)]

    override fun getString(
        key: String,
        defaultValue: String,
    ): String = getStringOrNull(key) ?: defaultValue

    override fun putString(
        key: String,
        value: String,
    ) = edit { it[stringPreferencesKey(key)] = value }

    override fun getFloatOrNull(key: String): Float? = snapshot[floatPreferencesKey(key)]

    override fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float = getFloatOrNull(key) ?: defaultValue

    override fun putFloat(
        key: String,
        value: Float,
    ) = edit { it[floatPreferencesKey(key)] = value }

    override fun getDoubleOrNull(key: String): Double? = snapshot[doublePreferencesKey(key)]

    override fun getDouble(
        key: String,
        defaultValue: Double,
    ): Double = getDoubleOrNull(key) ?: defaultValue

    override fun putDouble(
        key: String,
        value: Double,
    ) = edit { it[doublePreferencesKey(key)] = value }

    override fun getBooleanOrNull(key: String): Boolean? = snapshot[booleanPreferencesKey(key)]

    override fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = getBooleanOrNull(key) ?: defaultValue

    override fun putBoolean(
        key: String,
        value: Boolean,
    ) = edit { it[booleanPreferencesKey(key)] = value }

    override fun applyBatch(
        entries: List<PortableSetting>,
        removedKeys: Set<String>,
    ) {
        val batchEntries = entries.toList()
        val batchRemovedKeys = removedKeys.toSet()
        edit { preferences ->
            preferences
                .asMap()
                .keys
                .filter { it.name in batchRemovedKeys }
                .forEach { preferences.remove(it) }
            batchEntries.forEach { entry ->
                when (entry.type) {
                    SettingType.STRING -> preferences[stringPreferencesKey(entry.key)] = entry.value
                    SettingType.BOOLEAN -> preferences[booleanPreferencesKey(entry.key)] = entry.value.toBooleanStrict()
                    SettingType.INT -> preferences[intPreferencesKey(entry.key)] = entry.value.toInt()
                    SettingType.LONG -> preferences[longPreferencesKey(entry.key)] = entry.value.toLong()
                    SettingType.FLOAT -> preferences[floatPreferencesKey(entry.key)] = entry.value.toFloat()
                }
            }
        }
    }
}
