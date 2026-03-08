/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    com.russhwolf.settings.ExperimentalSettingsApi::class,
    com.russhwolf.settings.ExperimentalSettingsImplementation::class,
)

package com.metrolist.shared.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.shared.youtube.playback.AndroidContextProvider
import com.russhwolf.settings.Settings as KmpSettings

private val Context.metrolistPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

internal actual fun defaultKmpSettings(): KmpSettings {
    val appContext =
        try {
            AndroidContextProvider.applicationContext
        } catch (_: UninitializedPropertyAccessException) {
            null
        } ?: error("AndroidContextProvider.applicationContext must be assigned in Application.onCreate before Settings.shared is used")
    val store = appContext.metrolistPreferencesDataStore
    val plaintextSettings = AndroidBatchSettings(store)
    return AndroidFileSettings(appContext, AndroidSecureSettings(appContext, plaintextSettings))
}
