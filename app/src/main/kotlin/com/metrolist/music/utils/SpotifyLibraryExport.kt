package com.metrolist.music.utils

import kotlinx.serialization.json.*
import java.util.Locale

data class SpotifyLibraryExport(val tracks: List<SpotifyLikedTrack>, val skipped: Int)

object SpotifyLibraryExportParser {
    fun parse(text: String): SpotifyLibraryExport {
        require(text.length <= 20 * 1024 * 1024) { "File too large" }
        val content = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val rows = if (content.startsWith("{")) parseJson(content) else parseCsv(content)
        val valid = rows.filterNotNull().filter {
            it.title.isNotBlank() && it.artists.any(String::isNotBlank) &&
                it.title.length <= 1000 && it.artists.size <= 30 && it.artists.all { artist -> artist.length <= 1000 }
        }
        val unique = valid.distinctBy { it.id.ifBlank { "${it.title.lowercase(Locale.ROOT)}|${it.artists.joinToString().lowercase(Locale.ROOT)}" } }
        return SpotifyLibraryExport(unique, rows.size - unique.size)
    }

    private fun parseJson(text: String): List<SpotifyLikedTrack?> {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (escaped) { escaped = false; continue }
            if (quoted && char == '\\') { escaped = true; continue }
            if (char == '"') { quoted = !quoted; continue }
            if (!quoted) {
                if (char == '{' || char == '[') depth++
                if (char == '}' || char == ']') depth--
                require(depth in 0..32) { "Excessively nested JSON" }
            }
        }
        val root = Json.parseToJsonElement(text).jsonObject
        val tracks = root["tracks"] as? JsonArray ?: throw IllegalArgumentException("Select the Spotify library export")
        require(tracks.size <= 50_000) { "Too many songs" }
        return tracks.map { item ->
            val row = item as? JsonObject ?: return@map null
            fun string(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val title = string("track").ifBlank { string("name") }.ifBlank { string("title") }
            val artists = (row["artists"] as? JsonArray)?.mapNotNull {
                when (it) {
                    is JsonPrimitive -> it.contentOrNull
                    is JsonObject -> (it["name"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            } ?: listOf(string("artist"))
            val uri = string("uri")
            if (uri.isNotBlank() && !uri.startsWith("spotify:track:") && !uri.startsWith("spotify:local:")) return@map null
            SpotifyLikedTrack(uri, title, artists, (row["duration_ms"] as? JsonPrimitive)?.intOrNull?.div(1000) ?: 0,
                (row["explicit"] as? JsonPrimitive)?.booleanOrNull)
        }
    }

    private fun parseCsv(text: String): List<SpotifyLikedTrack?> {
        val comma = csvRows(text, ',')
        val rows = if (comma.firstOrNull()?.size == 1) csvRows(text, ';') else comma
        val header = rows.firstOrNull()?.map { it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "") }
            ?: throw IllegalArgumentException("Empty CSV")
        fun column(vararg names: String) = header.indexOfFirst { it in names }
        val title = column("trackname", "title", "name", "song", "track")
        val artist = column("artistnames", "artistname", "artists", "artist", "artistss")
        require(title >= 0 && artist >= 0) { "CSV needs title and artist headers" }
        val uri = column("trackuri", "spotifyuri", "uri", "trackurl", "url")
        val duration = column("durationms", "trackdurationms")
        val explicit = column("explicit")
        return rows.drop(1).filter { row -> row.any(String::isNotBlank) }.map { row ->
            fun value(index: Int) = row.getOrNull(index)?.trim().orEmpty()
            val explicitValue = when (value(explicit).lowercase(Locale.ROOT)) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            SpotifyLikedTrack(value(uri), value(title), value(artist).split(';').map(String::trim).filter(String::isNotBlank),
                value(duration).toIntOrNull()?.div(1000) ?: 0, explicitValue)
        }
    }

    private fun csvRows(text: String, separator: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && text.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == separator && !quoted -> { row.add(field.toString()); field.clear() }
                (char == '\n' || char == '\r') && !quoted -> {
                    row.add(field.toString()); field.clear(); rows.add(row.toList()); row.clear()
                    require(rows.size <= 50_001) { "Too many rows" }
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                }
                else -> field.append(char)
            }
            index++
            require(row.size <= 100 && field.length <= 10_000) { "Oversized CSV field" }
        }
        require(!quoted) { "Unclosed CSV quote" }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row.toList()) }
        require(rows.size <= 50_001) { "Too many rows" }
        return rows
    }
}
