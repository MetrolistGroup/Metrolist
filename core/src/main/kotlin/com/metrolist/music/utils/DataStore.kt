/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.metrolist.music.extensions.toEnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.properties.ReadOnlyProperty
import timber.log.Timber
import java.io.File
import java.io.IOException

@Volatile private var dataStoreInstance: DataStore<Preferences>? = null
private val dataStoreLock = Any()

val Context.dataStore: DataStore<Preferences>
    get() {
        dataStoreInstance?.let { return it }
        synchronized(dataStoreLock) {
            dataStoreInstance?.let { return it }
            // Ensure the datastore parent directory exists before DataStore init.
            // On Samsung One UI 8.5+/Android 16 the directory can be missing after
            // deep sleep, causing IOException in FileStorageConnection.writeScope()
            // that crashes the process and triggers a soft reboot.
            File(filesDir, "datastore").mkdirs()
            return preferencesDataStore(name = "settings")
                .getValue(this, ::dataStore)
                .also { dataStoreInstance = it }
        }
    }

/**
 * Safe DataStore write that ensures the parent directory exists before every edit.
 * Catches and reports IOException instead of crashing the coroutine scope.
 * Returns true if the write succeeded, false if it failed.
 */
suspend fun Context.safeDataStoreEdit(
    transform: suspend (MutablePreferences) -> Unit,
): Boolean {
    return try {
        File(filesDir, "datastore").mkdirs()
        dataStore.edit(transform)
        true
    } catch (e: IOException) {
        Timber.e(e, "DataStore edit failed")
        reportException(e)
        false
    }
}

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? =
    runBlocking(Dispatchers.IO) {
        data.first()[key]
    }

fun <T> DataStore<Preferences>.get(
    key: Preferences.Key<T>,
    defaultValue: T,
): T =
    runBlocking(Dispatchers.IO) {
        data.first()[key] ?: defaultValue
    }

fun <T> preference(
    context: Context,
    key: Preferences.Key<T>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key] ?: defaultValue }

inline fun <reified T : Enum<T>> enumPreference(
    context: Context,
    key: Preferences.Key<String>,
    defaultValue: T,
) = ReadOnlyProperty<Any?, T> { _, _ -> context.dataStore[key].toEnum(defaultValue) }

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val state =
        remember {
            context.dataStore.data
                .map { it[key] ?: defaultValue }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.safeDataStoreEdit {
                            it[key] = value
                        }
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
    key: Preferences.Key<String>,
    defaultValue: T,
): MutableState<T> {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val state =
        remember {
            context.dataStore.data
                .map { it[key].toEnum(defaultValue = defaultValue) }
                .distinctUntilChanged()
        }.collectAsStateWithLifecycle(defaultValue)

    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    coroutineScope.launch {
                        context.safeDataStoreEdit {
                            it[key] = value.name
                        }
                    }
                }

            override fun component1() = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
