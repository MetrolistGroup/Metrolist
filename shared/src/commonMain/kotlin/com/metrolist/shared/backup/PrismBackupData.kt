/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

private const val PRISM_LOCAL_LIBRARY = "files/library/prism.json"
private const val PRISM_REMOTE_LIBRARY = "files/offline/library.json"
private val PRISM_PAGE = Regex("files/offline/pages/[A-Za-z0-9_-]+\\.json")

internal fun isPrismBackupDataEntry(entry: String): Boolean =
    entry in setOf(PRISM_LOCAL_LIBRARY, PRISM_REMOTE_LIBRARY) || PRISM_PAGE.matches(entry)

internal fun ForeignLibrary.withPrismArtistPreferences(xml: String?): ForeignLibrary {
    if (family != ForeignLibraryFamily.PRISM || xml == null) return this
    if (xml.contains("<!DOCTYPE", ignoreCase = true)) throw UnsupportedForeignLibraryException()
    val favorites =
        runCatching {
            val value = findPrismFavoritesValue(xml) ?: return this
            val cdata = Regex("""^<!\[CDATA\[(.*)]]>$""", RegexOption.DOT_MATCHES_ALL).matchEntire(value.trim())?.groupValues?.get(1)
            val json = cdata ?: decodePrismXmlEntities(value)
            Json.parseToJsonElement(json).jsonArray.map { item ->
                val artist = item.jsonObject
                ForeignBookmark(
                    id = artist.getValue("id").jsonPrimitive.content,
                    name = artist.getValue("name").jsonPrimitive.content,
                    thumbnailUrl = (artist["thumbnail"] as? JsonPrimitive)?.contentOrNull,
                    bookmarkedAt = artist.long("at")?.takeIf { it > 0L } ?: Clock.System.now().toEpochMilliseconds(),
                )
            }
        }.getOrDefault(emptyList())
    return copy(artists = favorites.asReversed() + artists)
}

private fun findPrismFavoritesValue(xml: String): String? {
    val attributeName = Regex("""\bname\s*=\s*(['"])(.*?)\1""")
    var searchFrom = 0
    while (searchFrom < xml.length) {
        val open = xml.indexOf("<string", searchFrom)
        if (open < 0) return null
        val nameEnd = open + "<string".length
        if (nameEnd < xml.length && !xml[nameEnd].isWhitespace() && xml[nameEnd] != '>') {
            searchFrom = nameEnd
            continue
        }
        val tagEnd = findPrismXmlTagEnd(xml, nameEnd) ?: return null
        val attributes = xml.substring(nameEnd, tagEnd)
        if (attributeName.find(attributes)?.groupValues?.get(2) == "favorites") {
            val valueStart = tagEnd + 1
            val close = xml.indexOf("</string>", valueStart)
            return close.takeIf { it >= 0 }?.let { xml.substring(valueStart, it) }
        }
        searchFrom = tagEnd + 1
    }
    return null
}

private fun findPrismXmlTagEnd(
    xml: String,
    from: Int,
): Int? {
    var quote: Char? = null
    for (index in from until xml.length) {
        val char = xml[index]
        if (quote != null) {
            if (char == quote) quote = null
        } else if (char == '\'' || char == '"') {
            quote = char
        } else if (char == '>') {
            return index
        }
    }
    return null
}

private val PRISM_XML_ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|quot|apos|amp);""")

private fun decodePrismXmlEntities(value: String): String =
    PRISM_XML_ENTITY.replace(value) { match ->
        when (val entity = match.groupValues[1]) {
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            else -> {
                val codePoint = if (entity.startsWith("#x")) entity.substring(2).toIntOrNull(16) else entity.substring(1).toIntOrNull()
                if (codePoint == null || codePoint !in 0..0x10ffff || codePoint in 0xd800..0xdfff) {
                    match.value
                } else if (codePoint <= 0xffff) {
                    codePoint.toChar().toString()
                } else {
                    val supplementary = codePoint - 0x10000
                    buildString {
                        append((0xd800 + (supplementary shr 10)).toChar())
                        append((0xdc00 + (supplementary and 0x3ff)).toChar())
                    }
                }
            }
        }
    }

internal fun ForeignLibrary.withPrismBackupData(readEntry: (String) -> String?): ForeignLibrary {
    if (family != ForeignLibraryFamily.PRISM) return this
    val now = Clock.System.now().toEpochMilliseconds()
    val songs = songs.associateBy(ForeignSong::id).toMutableMap()
    val playlists = playlists.toMutableList()
    val artists = artists.toMutableList()
    val albums = albums.toMutableList()

    fun read(entry: String) = readEntry(entry)?.let { Json.parseToJsonElement(it).jsonObject }

    fun members(collection: JsonObject): List<String> =
        collection.objects("songs").map { item ->
            val song = item.prismSong()
            val stored = songs[song.id]
            songs[song.id] =
                if (stored == null) {
                    song
                } else {
                    stored.copy(
                        title = stored.title.takeUnless { it.isBlank() || it == stored.id } ?: song.title,
                        artists = stored.artists.ifEmpty { song.artists },
                        albumId = stored.albumId?.takeIf(String::isNotBlank) ?: song.albumId,
                        albumName = stored.albumName?.takeIf(String::isNotBlank) ?: song.albumName,
                        durationSeconds = stored.durationSeconds?.takeIf { it > 0 } ?: song.durationSeconds,
                        thumbnailUrl = stored.thumbnailUrl?.takeIf(String::isNotBlank) ?: song.thumbnailUrl,
                        explicit = stored.explicit || song.explicit,
                        isLocal = stored.isLocal || song.isLocal,
                        isLive = stored.isLive || song.isLive,
                        isVideo = stored.isVideo || song.isVideo,
                        isUploaded = stored.isUploaded || song.isUploaded,
                        isEpisode = stored.isEpisode || song.isEpisode,
                    )
                }
            song.id
        }

    read(PRISM_LOCAL_LIBRARY)?.let { library ->
        library.objects("lists").forEach { list ->
            playlists +=
                ForeignPlaylist(
                    name = list.getValue("title").jsonPrimitive.content,
                    browseId = null,
                    songIds = members(list),
                    isEditable = true,
                    description = list.text("description"),
                    createdAt = list.long("createdAt"),
                    updatedAt = list.long("updatedAt"),
                )
        }
        library.objects("albums").forEach { album -> albums += album.bookmark("title", album.long("savedAt") ?: now) }
    }
    read(PRISM_REMOTE_LIBRARY)?.let { library ->
        library.objects("playlists").filter { it.text("kind") == "playlist" && !it.boolean("isMix") }.forEach { item ->
            val sourceId = item.getValue("id").jsonPrimitive.content
            val id = sourceId.removePrefix("VL")
            if (id.isBlank() || id in setOf("LM", "SE")) return@forEach
            val pageName = sourceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val page = read("files/offline/pages/$pageName.json")?.takeIf { !it.boolean("album") }
            playlists +=
                ForeignPlaylist(
                    name = item.getValue("title").jsonPrimitive.content,
                    browseId = id,
                    songIds = page?.let(::members).orEmpty(),
                    isEditable = page?.boolean("owned") == true,
                    description = page?.text("description"),
                    thumbnailUrl = item.text("thumbnail"),
                )
        }
        library.objects("artists").filter { it.text("kind") == "artist" }.forEach { artists += it.bookmark("title", now) }
        library.objects("albums").filter { it.text("kind") == "album" }.forEach { albums += it.bookmark("title", now) }
    }
    return copy(songs = songs.values.toList(), playlists = playlists, artists = artists, albums = albums)
}

private fun JsonObject.prismSong(): ForeignSong {
    val id = getValue("id").jsonPrimitive.content
    val album = get("album") as? JsonObject
    return ForeignSong(
        id = id,
        title = getValue("title").jsonPrimitive.content,
        artists = get("artists")?.let { prismArtists(it.toString()) }.orEmpty(),
        albumId = album?.text("id"),
        albumName = album?.text("title"),
        durationSeconds = (get("durationSec") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
        thumbnailUrl = text("thumbnail"),
        explicit = boolean("explicit"),
        isLocal = id.startsWith("local:"),
        isLive = boolean("isLive"),
        isVideo = boolean("isVideo"),
        isUploaded = boolean("isUpload"),
        isEpisode = boolean("isEpisode"),
    )
}

private fun JsonObject.bookmark(
    name: String,
    timestamp: Long,
) = ForeignBookmark(getValue("id").jsonPrimitive.content, getValue(name).jsonPrimitive.content, text("thumbnail"), timestamp)

private fun JsonObject.objects(key: String): List<JsonObject> = get(key)?.jsonArray.orEmpty().map { it.jsonObject }

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

private fun JsonObject.boolean(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
