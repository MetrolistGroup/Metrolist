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
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Clock

private const val PRISM_ARCHIVE_DIRECTORY = ".prism"
private const val PRISM_LOCAL_LIBRARY = "files/library/prism.json"
private const val PRISM_REMOTE_LIBRARY = "files/offline/library.json"
private const val PRISM_ARTIST_PREFERENCES = "shared_prefs/artist_prefs.xml"
private val PRISM_PAGE = Regex("files/offline/pages/[A-Za-z0-9_-]+\\.json")

internal fun prismArchiveFile(
    directory: File,
    entry: String,
): File? =
    if (entry in setOf(PRISM_LOCAL_LIBRARY, PRISM_REMOTE_LIBRARY, PRISM_ARTIST_PREFERENCES) || PRISM_PAGE.matches(entry)) {
        directory.resolve(PRISM_ARCHIVE_DIRECTORY).resolve(entry)
    } else {
        null
    }

internal fun ForeignLibrary.withPrismArchive(directory: File): ForeignLibrary {
    if (family != ForeignLibraryFamily.PRISM) return this
    val now = Clock.System.now().toEpochMilliseconds()
    val songs = songs.associateBy(ForeignSong::id).toMutableMap()
    val playlists = playlists.toMutableList()
    val artists = artists.toMutableList()
    val albums = albums.toMutableList()

    fun file(entry: String) = checkNotNull(prismArchiveFile(directory, entry))

    fun read(entry: String) = file(entry).takeIf(File::isFile)?.readText()?.let { Json.parseToJsonElement(it).jsonObject }

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
        library.objects("albums").forEach { album ->
            albums += album.bookmark("title", album.long("savedAt") ?: now)
        }
    }
    read(PRISM_REMOTE_LIBRARY)?.let { library ->
        library.objects("playlists").filter { it.text("kind") == "playlist" && !it.boolean("isMix") }.forEach { item ->
            val id =
                item
                    .getValue("id")
                    .jsonPrimitive.content
                    .removePrefix("VL")
            if (id.isBlank() || id in setOf("LM", "SE")) return@forEach
            val pageName =
                item
                    .getValue("id")
                    .jsonPrimitive.content
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
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
    file(PRISM_ARTIST_PREFERENCES).takeIf(File::isFile)?.let { preferences ->
        val favorites = mutableListOf<ForeignBookmark>()
        val xml = preferences.readText()
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) throw UnsupportedForeignLibraryException()
        try {
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }
            val builder =
                factory.newDocumentBuilder().apply {
                    setEntityResolver { _, _ -> throw UnsupportedForeignLibraryException() }
                }
            val document = builder.parse(InputSource(StringReader(xml)))
            val strings = document.getElementsByTagName("string")
            for (index in 0 until strings.length) {
                val element = strings.item(index) as? Element ?: continue
                if (element.getAttribute("name") != "favorites") continue
                Json.parseToJsonElement(element.textContent).jsonArray.forEach { value ->
                    val artist = value.jsonObject
                    favorites.add(0, artist.bookmark("name", artist.long("at")?.takeIf { it > 0 } ?: now))
                }
            }
        } catch (_: Exception) {
            favorites.clear()
        }
        artists.addAll(0, favorites)
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
