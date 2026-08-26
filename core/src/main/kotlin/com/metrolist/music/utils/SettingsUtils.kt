/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.models.AndroidAutoSection

fun serializeSections(sections: List<Pair<AndroidAutoSection, Boolean>>): String =
    sections.joinToString(",") { (section, enabled) -> "${section.id}:$enabled" }

fun deserializeSections(raw: String): List<Pair<AndroidAutoSection, Boolean>> {
    if (raw.isBlank()) return AndroidAutoSection.entries.map { it to true }
    return raw.split(",").mapNotNull { token ->
        val parts = token.split(":")
        if (parts.size != 2) return@mapNotNull null
        val section = AndroidAutoSection.entries.find { it.id == parts[0] } ?: return@mapNotNull null
        val enabled = parts[1].toBooleanStrictOrNull() ?: true
        section to enabled
    }
}
