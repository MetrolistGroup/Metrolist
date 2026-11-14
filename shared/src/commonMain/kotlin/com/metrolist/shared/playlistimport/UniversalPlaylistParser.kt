package com.metrolist.shared.playlistimport

import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object UniversalPlaylistParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val numberingRegex = Regex("""^\s*(?:\[\d+]|\d+[\.\-\):\s]+)\s*""")
    private val bareYoutubeIdRegex = Regex("[A-Za-z0-9_-]{11}")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")

    fun parseImportable(text: String): List<PlaylistImportManager.CsvTrack> = importableTracks(parse(text))

    internal fun importableTracks(tracks: List<PlaylistImportManager.CsvTrack>): List<PlaylistImportManager.CsvTrack> =
        tracks.also {
            if (
                tracks.any { track ->
                    val reference = track.directReference
                    reference != null && PlaylistImportManager.isLocalReference(reference) && !track.isResolvedLocalReference
                }
            ) {
                throw ImportException(ImportErrorKey.UnsupportedPlaylistEntries)
            }
        }

    fun parse(
        text: String,
        resolveLocalReference: (String) -> String? = { null },
    ): List<PlaylistImportManager.CsvTrack> {
        if (text.length > PlaylistImportManager.MAX_CSV_CHARACTERS) throw ImportException(ImportErrorKey.FileReadFailed)
        val trimmed = text.removePrefix("\uFEFF").trim()
        if (trimmed.isBlank()) return emptyList()

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            val fromJson = parseJson(trimmed, resolveLocalReference)
            if (fromJson.isNotEmpty()) return fromJson
        }

        if (trimmed.startsWith("#EXTM3U", ignoreCase = true) || trimmed.contains("#EXTINF:", ignoreCase = true)) {
            val fromM3u = parseM3u(trimmed, resolveLocalReference)
            if (fromM3u.isNotEmpty()) return fromM3u
        }

        if (trimmed.contains('\t') || trimmed.contains(",") || trimmed.contains(";")) {
            val fromDelimited = parseDelimited(text.removePrefix("\uFEFF"), resolveLocalReference)
            if (fromDelimited.isNotEmpty()) return fromDelimited
        }

        return parsePlainTextLines(trimmed, resolveLocalReference)
    }

    fun prepareFile(
        text: String,
        fileName: String,
        title: String,
        source: String,
        resolveLocalReference: (String) -> String? = { null },
    ): ImportedPlaylistData {
        val trimmed = text.removePrefix("\uFEFF").trimStart()
        val explicitCsv = fileName.substringAfterLast('.').lowercase() in setOf("csv", "tsv")
        val structured =
            trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("#EXTM3U", true) ||
                trimmed.contains("#EXTINF:", true)
        val csv = if (explicitCsv || (!structured && text.any { it in ",;\t" })) CsvPlaylist.parse(text) else null
        val needsMapping = explicitCsv || (csv?.columnCount ?: 0) > 1
        if (needsMapping && csv?.rows.orEmpty().none { row -> row.any(String::isNotBlank) }) {
            throw ImportException(ImportErrorKey.NoTracksRecognized)
        }
        return ImportedPlaylistData(
            title = title,
            source = source,
            tracks = if (needsMapping) emptyList() else parse(text, resolveLocalReference),
            csv = csv.takeIf { needsMapping },
        )
    }

    private fun parseJson(
        text: String,
        resolveLocalReference: (String) -> String?,
    ): List<PlaylistImportManager.CsvTrack> =
        try {
            val root = json.parseToJsonElement(text)
            val items =
                when (root) {
                    is JsonArray -> root
                    is JsonObject -> root["tracks"] ?: root["songs"] ?: root["items"] ?: root["data"]
                    else -> null
                } as? JsonArray ?: return emptyList()

            items
                .mapNotNull { element ->
                    (element as? JsonObject)?.let { extractTrackFromJsonObject(it, resolveLocalReference) }
                }.take(PlaylistImportManager.MAX_PLAYLIST_TRACKS)
        } catch (_: Exception) {
            emptyList()
        }

    private fun extractTrackFromJsonObject(
        obj: JsonObject,
        resolveLocalReference: (String) -> String?,
    ): PlaylistImportManager.CsvTrack? {
        val title = firstString(obj, listOf("title", "name", "trackName", "track_name", "song", "songTitle"))
        val artistKeys = listOf("artist", "artistName", "artist_name", "artists", "creator", "author")
        val artist =
            artistKeys
                .firstNotNullOfOrNull { key ->
                    when (val value = obj[key]) {
                        is JsonArray -> {
                            value
                                .mapNotNull { item ->
                                    if (item is JsonObject) {
                                        item["name"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                                    } else {
                                        runCatching { item.jsonPrimitive.content }.getOrNull()
                                    }
                                }.takeIf(List<String>::isNotEmpty)
                                ?.joinToString(", ")
                        }

                        null -> {
                            null
                        }

                        else -> {
                            runCatching { value.jsonPrimitive.content.trim() }.getOrNull()?.takeIf(String::isNotBlank)
                        }
                    }
                }.orEmpty()
        val rawReference =
            firstString(obj, listOf("videoId", "video_id", "youtubeId", "youtube_id", "url", "uri", "link"))
        val videoId = rawReference?.let(::extractVideoId)
        val directReference =
            rawReference?.takeIf { videoId == null && looksLikeDirectReference(it) }?.let {
                resolveDirectReference(it, resolveLocalReference)
            }
        if (title.isNullOrBlank() && videoId == null && directReference == null) return null
        return PlaylistImportManager.CsvTrack(
            title.orEmpty(),
            artist,
            videoId,
            directReference?.first,
            directReference?.second == true,
            customTitle = title,
        )
    }

    private fun firstString(
        obj: JsonObject,
        keys: List<String>,
    ): String? =
        keys.firstNotNullOfOrNull { key ->
            obj[key]
                ?.let { runCatching { it.jsonPrimitive.content.trim() }.getOrNull() }
                ?.takeIf(String::isNotBlank)
        }

    private fun parseM3u(
        text: String,
        resolveLocalReference: (String) -> String?,
    ): List<PlaylistImportManager.CsvTrack> {
        val results = mutableListOf<PlaylistImportManager.CsvTrack>()
        var pendingInfo: String? = null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                pendingInfo = trimmed.substringAfter(',').trim().takeIf { ',' in trimmed && it.isNotBlank() }
                continue
            }
            if (trimmed.startsWith("#")) continue

            val videoId = extractVideoId(trimmed)
            val directReference = if (videoId == null) resolveDirectReference(trimmed, resolveLocalReference) else null
            val fallbackTitle = referenceTitle(trimmed)
            val suppliedInfo = pendingInfo?.let(::splitArtistAndTitle)
            val (artist, title) = suppliedInfo ?: ("" to fallbackTitle)
            pendingInfo = null
            if (title.isNotBlank() || videoId != null || directReference != null) {
                results +=
                    PlaylistImportManager.CsvTrack(
                        title,
                        artist,
                        videoId,
                        directReference?.first,
                        directReference?.second == true,
                        customTitle = suppliedInfo?.second?.takeIf(String::isNotBlank),
                    )
            }
            if (results.size >= PlaylistImportManager.MAX_PLAYLIST_TRACKS) break
        }
        return results
    }

    private fun parseDelimited(
        text: String,
        resolveLocalReference: (String) -> String?,
    ): List<PlaylistImportManager.CsvTrack> = PlaylistImportManager.parseCsv(text, resolveLocalReference)

    private fun parsePlainTextLines(
        text: String,
        resolveLocalReference: (String) -> String?,
    ): List<PlaylistImportManager.CsvTrack> =
        text
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
            .take(PlaylistImportManager.MAX_PLAYLIST_TRACKS)
            .mapNotNull { line ->
                val cleaned = numberingRegex.replace(line, "").trim()
                if (cleaned.isBlank()) return@mapNotNull null
                val videoId = extractVideoId(cleaned)
                val directReference =
                    cleaned.takeIf { videoId == null && looksLikeDirectReference(it) }?.let {
                        resolveDirectReference(it, resolveLocalReference)
                    }
                val (artist, title) =
                    if (directReference == null) splitArtistAndTitle(cleaned) else "" to referenceTitle(cleaned)
                if (title.isBlank() && videoId == null && directReference == null) return@mapNotNull null
                PlaylistImportManager.CsvTrack(
                    title.ifBlank { cleaned },
                    artist,
                    videoId,
                    directReference?.first,
                    directReference?.second == true,
                    customTitle = title.takeIf { videoId == null && directReference == null && it.isNotBlank() },
                )
            }.toList()

    private fun resolveDirectReference(
        value: String,
        resolveLocalReference: (String) -> String?,
    ): Pair<String, Boolean> {
        val scheme = uriScheme(value)
        if (scheme?.equals("http", true) == true || scheme?.equals("https", true) == true) return value to false
        val local = resolveLocalReference(value)
        return (local ?: value) to (local != null)
    }

    internal fun looksLikeDirectReference(value: String): Boolean {
        if (value.isBlank()) return false
        return uriScheme(value) != null || value.startsWith(".") || value.startsWith("/") || value.startsWith("\\") ||
            value.contains('/') || value.contains('\\')
    }

    private fun referenceTitle(value: String): String {
        val path = value.substringBefore('?').substringBefore('#')
        return path
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.')
            .ifBlank { value }
    }

    internal fun splitArtistAndTitle(line: String): Pair<String, String> {
        val trimmed = line.trim()
        val byMatch = Regex("""^(?:["'“](.+?)["'”]|(.+?))\s+by\s+(.+)$""", RegexOption.IGNORE_CASE).find(trimmed)
        if (byMatch != null) {
            val title = (byMatch.groups[1]?.value ?: byMatch.groups[2]?.value).orEmpty().trim()
            val artist =
                byMatch.groups[3]
                    ?.value
                    .orEmpty()
                    .trim()
            if (title.isNotBlank() && artist.isNotBlank()) return artist to title
        }
        for (separator in listOf(" - ", " – ", " — ", " : ", " | ")) {
            val index = trimmed.indexOf(separator)
            if (index > 0 && index + separator.length < trimmed.length) {
                return trimmed.substring(0, index).trim().trim('"', '\'', '“', '”') to
                    trimmed.substring(index + separator.length).trim().trim('"', '\'', '“', '”')
            }
        }
        return "" to trimmed.trim('"', '\'', '“', '”')
    }

    internal fun extractVideoId(input: String): String? {
        if (input.isBlank()) return null
        return extractYouTubeUrlVideoId(input) ?: input.takeIf { it.matches(bareYoutubeIdRegex) }
    }

    private fun extractYouTubeUrlVideoId(input: String): String? {
        val url = runCatching { Url(input.trim()) }.getOrNull() ?: return null
        if (url.protocol.name.lowercase() !in setOf("http", "https") || url.user?.isNotEmpty() == true ||
            url.password?.isNotEmpty() == true
        ) {
            return null
        }
        if (url.port !in setOf(80, 443)) return null
        val host = url.host.lowercase()
        if (host !in youtubeHosts) return null
        if (host == "youtu.be") {
            return url.encodedPath
                .trim('/')
                .substringBefore('/')
                .takeIf { it.matches(bareYoutubeIdRegex) }
        }
        if (url.encodedPath != "/watch") return null
        return url.parameters["v"]?.takeIf { it.matches(bareYoutubeIdRegex) }
    }
}

fun decodePlaylistText(
    bytes: ByteArray,
    maxCharacters: Int = PlaylistImportManager.MAX_CSV_CHARACTERS,
): String {
    require(maxCharacters >= 0)
    if (bytes.size.toLong() > maxCharacters * 4L + 4L) throw ImportException(ImportErrorKey.FileReadFailed)
    val start =
        when {
            bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> 3
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> 2
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> 2
            else -> 0
        }
    val littleEndian =
        (start == 2 && bytes[0] == 0xFF.toByte()) ||
            (start == 0 && bytes.size > 1 && bytes[0] != 0.toByte() && bytes[1] == 0.toByte())
    val bigEndian =
        (start == 2 && bytes[0] == 0xFE.toByte()) ||
            (start == 0 && bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1] != 0.toByte())
    val text =
        when {
            littleEndian -> {
                decodeUtf16(bytes, start, littleEndian = true)
            }

            bigEndian -> {
                decodeUtf16(bytes, start, littleEndian = false)
            }

            else -> {
                try {
                    bytes.decodeToString(start, bytes.size, throwOnInvalidSequence = true)
                } catch (_: Exception) {
                    throw ImportException(ImportErrorKey.FileReadFailed)
                }
            }
        }
    if (text.length > maxCharacters) throw ImportException(ImportErrorKey.FileReadFailed)
    return text
}

private fun decodeUtf16(
    bytes: ByteArray,
    start: Int,
    littleEndian: Boolean,
): String {
    if ((bytes.size - start) % 2 != 0) throw ImportException(ImportErrorKey.FileReadFailed)
    val output = StringBuilder((bytes.size - start) / 2)
    var index = start
    while (index < bytes.size) {
        val first = codeUnit(bytes, index, littleEndian)
        index += 2
        if (first in 0xD800..0xDBFF) {
            if (index >= bytes.size || codeUnit(bytes, index, littleEndian) !in 0xDC00..0xDFFF) {
                throw ImportException(ImportErrorKey.FileReadFailed)
            }
            output.append(first.toChar())
            output.append(codeUnit(bytes, index, littleEndian).toChar())
            index += 2
        } else {
            if (first in 0xDC00..0xDFFF) throw ImportException(ImportErrorKey.FileReadFailed)
            output.append(first.toChar())
        }
    }
    return output.toString()
}

private fun codeUnit(
    bytes: ByteArray,
    index: Int,
    littleEndian: Boolean,
): Int =
    if (littleEndian) {
        (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
    } else {
        ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
    }

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun uriScheme(value: String): String? = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(value)?.groupValues?.get(1)
