/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ArtistNameAliases {
    const val BACKUP_FILENAME = "artist_name_aliases.json"
    private const val PREFERENCES_NAME = "artist_name_aliases"

    private val _aliases = MutableStateFlow<Map<String, String>>(emptyMap())
    val aliases = _aliases.asStateFlow()

    @Volatile
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        if (preferences != null) return

        synchronized(this) {
            if (preferences != null) return

            val sharedPreferences =
                context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            _aliases.value =
                sharedPreferences.all.mapNotNull { (artistId, value) ->
                    (value as? String)?.let { artistId to it }
                }.toMap()
            preferences = sharedPreferences
        }
    }

    fun set(
        context: Context,
        artistId: String,
        channelId: String?,
        originalName: String,
        name: String,
    ) {
        initialize(context)
        val currentAliases = _aliases.value
        val aliasKeys =
            buildSet {
                add(artistId)
                channelId?.let(::add)
                add(nameKey(originalName))
                currentAliases.filterValues { it == originalName }.keys.forEach(::add)
            }
        preferences?.edit()?.apply {
            aliasKeys.forEach { putString(it, name) }
            apply()
        }
        _aliases.update { aliases -> aliases + aliasKeys.associateWith { name } }
    }

    fun resolve(
        artistId: String?,
        fallback: String,
    ): String = resolve(_aliases.value, artistId, fallback)

    fun resolve(
        aliases: Map<String, String>,
        artistId: String?,
        fallback: String,
    ): String {
        var resolvedName = artistId?.let(aliases::get) ?: aliases[nameKey(fallback)] ?: fallback
        var nextName = aliases[nameKey(resolvedName)] ?: return resolvedName
        val visitedNames = mutableSetOf(resolvedName)
        while (visitedNames.add(nextName)) {
            resolvedName = nextName
            nextName = aliases[nameKey(resolvedName)] ?: return resolvedName
        }
        return resolvedName
    }

    fun serialize(): String = Json.encodeToString(_aliases.value)

    fun deserialize(serializedAliases: String): Map<String, String> = Json.decodeFromString(serializedAliases)

    fun restore(
        context: Context,
        restoredAliases: Map<String, String>,
    ) {
        initialize(context)
        preferences?.edit()?.apply {
            clear()
            restoredAliases.forEach(::putString)
            commit()
        }
        _aliases.value = restoredAliases
    }

    private fun nameKey(name: String) = "name:$name"
}
