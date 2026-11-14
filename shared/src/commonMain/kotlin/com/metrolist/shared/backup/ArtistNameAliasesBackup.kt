package com.metrolist.shared.backup

import com.metrolist.shared.constants.ArtistNameAliasesJsonKey
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.PortableSetting
import com.metrolist.shared.settings.PortableSettings
import com.metrolist.shared.settings.SettingType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun PortableSettings?.withLegacyArtistNameAliases(serialized: String): PortableSettings {
    val aliases = ArtistNameAliases.deserialize(serialized)
    val settings = this ?: PortableSettings(entries = emptyList())
    return settings.copy(
        entries =
            settings.entries.filterNot { it.key == ArtistNameAliasesJsonKey } +
                PortableSetting(ArtistNameAliasesJsonKey, SettingType.STRING, Json.encodeToString(aliases)),
    )
}
