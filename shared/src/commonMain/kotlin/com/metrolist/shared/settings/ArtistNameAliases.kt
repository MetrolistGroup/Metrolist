package com.metrolist.shared.settings

import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.YTItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ArtistNameAliases {
    const val BACKUP_FILENAME = "artist_name_aliases.json"
    const val LEGACY_PREFERENCES_NAME = "artist_name_aliases"

    val aliases: Map<String, String> get() = Settings.shared.artistNameAliases

    fun deserialize(serialized: String): Map<String, String> = Json.decodeFromString(serialized)

    fun fromLegacyPreferences(values: Map<String, *>): Map<String, String> =
        values.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()

    fun resolve(
        artistId: String?,
        fallback: String,
    ): String = resolve(aliases, artistId, fallback)

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

    fun renamed(
        aliases: Map<String, String>,
        artistId: String,
        channelId: String?,
        originalName: String,
        name: String,
    ): Map<String, String> {
        val keys =
            buildSet {
                add(artistId)
                channelId?.let(::add)
                add(nameKey(originalName))
                aliases.filterValues { it == originalName }.keys.forEach(::add)
            }
        return aliases + keys.associateWith { name }
    }

    fun cleared(
        aliases: Map<String, String>,
        artistId: String,
        channelId: String?,
        originalName: String,
    ): Map<String, String> {
        val keys = mutableSetOf(artistId, nameKey(originalName))
        channelId?.let(keys::add)
        val names = mutableSetOf(originalName)
        do {
            val previousSize = keys.size + names.size
            aliases.forEach { (key, value) ->
                if (key in keys || (key.startsWith("name:") && key.removePrefix("name:") in names) || value in names) {
                    keys.add(key)
                    names.add(value)
                    if (key.startsWith("name:")) names.add(key.removePrefix("name:"))
                }
            }
        } while (keys.size + names.size != previousSize)
        return aliases - keys
    }

    fun set(
        artistId: String,
        channelId: String?,
        originalName: String,
        name: String,
    ) {
        restore(renamed(aliases, artistId, channelId, originalName, name))
    }

    fun clear(
        artistId: String,
        channelId: String?,
        originalName: String,
    ) {
        restore(cleared(aliases, artistId, channelId, originalName))
    }

    fun restore(aliases: Map<String, String>) {
        Settings.shared.artistNameAliasesJson = Json.encodeToString(aliases)
    }

    private fun nameKey(name: String) = "name:$name"
}

val ArtistTiny.displayName: String get() = ArtistNameAliases.resolve(id, name)
val ArtistEntity.displayName: String get() = ArtistNameAliases.resolve(id, name)
val YTItem.displayTitle: String get() = if (this is ArtistItem) ArtistNameAliases.resolve(id, title) else title
