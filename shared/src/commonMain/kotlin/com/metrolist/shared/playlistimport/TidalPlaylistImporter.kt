package com.metrolist.shared.playlistimport

import com.metrolist.shared.api.lyrics.parseXml
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.import_error_album_tracks_unavailable
import com.metrolist.shared.generated.resources.import_error_playlist_tracks_unavailable
import com.metrolist.shared.generated.resources.import_provider_tidal
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.getString

object TidalPlaylistImporter {
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    // The public embed server caps collections at 50 without exposing the original total.
    private const val EMBED_TRACK_LIMIT = 50

    private val defaultHttpClient: HttpClient by lazy { createHttpClient() }
    internal var clientProvider: () -> HttpClient = { defaultHttpClient }
    private val httpClient: HttpClient get() = clientProvider()
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("tidal.com")
    }

    suspend fun loadPlaylist(
        urlOrId: String,
        fallbackTitles: PlaylistImportFallbackTitles? = null,
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): Result<ImportedPlaylistData> {
        return try {
            val (type, id) =
                parseUrl(urlOrId)
                    ?: return Result.failure(IllegalArgumentException("Invalid Tidal URL"))

            when (type) {
                Type.PLAYLIST -> loadPlaylistTracks(id, fallbackTitles, onProgress)
                Type.ALBUM -> loadAlbumTracks(id, fallbackTitles, onProgress)
                Type.TRACK -> loadSingleTrack(id, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private enum class Type { PLAYLIST, ALBUM, TRACK }

    private fun parseUrl(input: String): Pair<Type, String>? {
        val trimmed = input.trim()

        val playlistRegex = Regex("""tidal\.com(?:/browse)?/playlist/([a-f0-9-]+)""", RegexOption.IGNORE_CASE)
        playlistRegex.find(trimmed)?.let {
            return Type.PLAYLIST to it.groupValues[1]
        }

        val albumRegex = Regex("""tidal\.com(?:/browse)?/album/(\d+)""", RegexOption.IGNORE_CASE)
        albumRegex.find(trimmed)?.let {
            return Type.ALBUM to it.groupValues[1]
        }

        val trackRegex = Regex("""tidal\.com(?:/browse)?/track/(\d+)""", RegexOption.IGNORE_CASE)
        trackRegex.find(trimmed)?.let {
            return Type.TRACK to it.groupValues[1]
        }

        if (trimmed.matches(Regex("""^\d{5,}$"""))) {
            return Type.TRACK to trimmed
        }

        return null
    }

    private suspend fun loadPlaylistTracks(
        playlistId: String,
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val embedUrl = "https://embed.tidal.com/playlists/$playlistId"
        val response =
            withImportRequestTimeout {
                httpClient.get(embedUrl) {
                    header("User-Agent", UA)
                }
            }
        val html = response.bodyAsTextLimited(MAX_RESPONSE_BYTES)
        if (!response.status.isSuccess() || html.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Tidal playlist not found (HTTP ${response.status.value})"))
        }

        currentCollection(html)?.let { collection ->
            onProgress(collection.tracks.size, collection.tracks.size)
            return Result.success(collection)
        }

        val title =
            Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                    .find(html)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.replace(" | TIDAL", "")
                    ?.trim()
                ?: fallbackTitles?.playlist ?: getString(Res.string.playlist)
        val coverUrl =
            Regex("""<meta\s+property="og:image"\s+content="([^"]+)""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.get(1)

        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()

        val nextDataMatch =
            Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
        if (nextDataMatch != null) {
            val root = json.parseToJsonElement(nextDataMatch.groupValues[1]).jsonObject
            val trackList =
                root["props"]
                    ?.jsonObject
                    ?.get("pageProps")
                    ?.jsonObject
                    ?.get("playlist")
                    ?.jsonObject
                    ?.get("tracks")
                    ?.jsonArray
                    ?: root["props"]
                        ?.jsonObject
                        ?.get("pageProps")
                        ?.jsonObject
                        ?.get("tracks")
                        ?.jsonArray
                    ?: JsonArray(emptyList())

            for (item in trackList) {
                val obj = item.jsonObject
                val trackTitle = obj["title"]?.jsonPrimitive?.content?.trim() ?: continue
                val artistName =
                    obj["artists"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim()
                        ?: obj["artist"]
                            ?.jsonObject
                            ?.get("name")
                            ?.jsonPrimitive
                            ?.content
                            ?.trim()
                        ?: ""
                tracks += PlaylistImportManager.CsvTrack(trackTitle, artistName, null)
            }
        }

        onProgress(tracks.size, tracks.size)

        if (tracks.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    getString(Res.string.import_error_playlist_tracks_unavailable, getString(Res.string.import_provider_tidal)),
                ),
            )
        }

        return Result.success(
            ImportedPlaylistData(
                title = title,
                tracks = tracks,
                source = "Tidal",
                coverUrl = coverUrl,
            ),
        )
    }

    private suspend fun loadAlbumTracks(
        albumId: String,
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val embedUrl = "https://embed.tidal.com/albums/$albumId"
        val response =
            withImportRequestTimeout {
                httpClient.get(embedUrl) {
                    header("User-Agent", UA)
                }
            }
        val html = response.bodyAsTextLimited(MAX_RESPONSE_BYTES)
        if (!response.status.isSuccess() || html.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Tidal album not found (HTTP ${response.status.value})"))
        }

        currentCollection(html)?.let { collection ->
            onProgress(collection.tracks.size, collection.tracks.size)
            return Result.success(collection)
        }

        val title =
            Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: fallbackTitles?.album ?: getString(Res.string.album)

        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()

        val nextDataMatch =
            Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
        if (nextDataMatch != null) {
            val root = json.parseToJsonElement(nextDataMatch.groupValues[1]).jsonObject
            val trackList =
                root["props"]
                    ?.jsonObject
                    ?.get("pageProps")
                    ?.jsonObject
                    ?.get("album")
                    ?.jsonObject
                    ?.get("tracks")
                    ?.jsonArray
                    ?: root["props"]
                        ?.jsonObject
                        ?.get("pageProps")
                        ?.jsonObject
                        ?.get("tracks")
                        ?.jsonArray
                    ?: JsonArray(emptyList())

            for (item in trackList) {
                val obj = item.jsonObject
                val trackTitle = obj["title"]?.jsonPrimitive?.content?.trim() ?: continue
                val artistName =
                    obj["artists"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim()
                        ?: ""
                tracks += PlaylistImportManager.CsvTrack(trackTitle, artistName, null)
            }
        }

        onProgress(tracks.size, tracks.size)

        if (tracks.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    getString(Res.string.import_error_album_tracks_unavailable, getString(Res.string.import_provider_tidal)),
                ),
            )
        }

        return Result.success(
            ImportedPlaylistData(
                title = title,
                tracks = tracks,
                source = "Tidal",
            ),
        )
    }

    private suspend fun loadSingleTrack(
        trackId: String,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val embedUrl = "https://embed.tidal.com/tracks/$trackId"
        val response =
            withImportRequestTimeout {
                httpClient.get(embedUrl) {
                    header("User-Agent", UA)
                }
            }
        val html = response.bodyAsTextLimited(MAX_RESPONSE_BYTES)
        if (!response.status.isSuccess() || html.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Tidal track not found (HTTP ${response.status.value})"))
        }

        val metadata = trackMetadata(html, trackId)
        val title =
            if (metadata != null) {
                (metadata["title"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            } else {
                Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(html)
                    ?.groupValues
                    ?.get(1)
            }?.trim()?.takeIf(String::isNotEmpty)
                ?: throw ImportException(ImportErrorKey.NoTracksRecognized)
        val artistName =
            if (metadata != null) {
                (metadata["artist"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            } else {
                Regex("""<meta\s+property="og:description"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(html)
                    ?.groupValues
                    ?.get(1)
            }.orEmpty().trim()
        val tracks = listOf(PlaylistImportManager.CsvTrack(title, artistName, null))
        onProgress(1, 1)

        return Result.success(
            ImportedPlaylistData(
                title = title,
                tracks = tracks,
                source = "Tidal",
            ),
        )
    }

    private fun trackMetadata(
        html: String,
        trackId: String,
    ): JsonObject? {
        val metadata = embedMetadata(html) ?: return null
        return metadata[trackId] as? JsonObject ?: throw ImportException(ImportErrorKey.NoTracksRecognized)
    }

    private fun currentCollection(html: String): ImportedPlaylistData? {
        val metadata = embedMetadata(html) ?: return null
        val list =
            Regex("""<media-item-list\b[^>]*>(.*?)</media-item-list\s*>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html)
                .singleOrNull()
                ?.groupValues
                ?.get(1)
                ?: throw ImportException(ImportErrorKey.NoTracksRecognized)
        val entries = Regex("""<list-item\b([^>]*)>""").findAll(list).toList()
        if (entries.size >= EMBED_TRACK_LIMIT) throw ImportException(ImportErrorKey.IncompleteSource)
        if (entries.isEmpty() || Regex("""</list-item\s*>""").findAll(list).count() != entries.size) {
            throw ImportException(ImportErrorKey.IncompleteSource)
        }
        val ids =
            entries.map { entry ->
                val attributes = parseXml("<entry ${entry.groupValues[1]}/>")
                if (attributes.getAttribute("product-type") != "track") {
                    throw ImportException(ImportErrorKey.UnsupportedPlaylistEntries)
                }
                attributes.getAttribute("product-id").takeIf { id -> id.isNotEmpty() && id.all(Char::isDigit) }
                    ?: throw ImportException(ImportErrorKey.IncompleteSource)
            }
        if (metadata.keys != ids.toSet()) throw ImportException(ImportErrorKey.IncompleteSource)
        val tracks =
            ids.map { id ->
                val item = metadata[id] as? JsonObject ?: throw ImportException(ImportErrorKey.IncompleteSource)
                val title =
                    (item["title"] as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: throw ImportException(ImportErrorKey.IncompleteSource)
                val artist =
                    (item["artist"] as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.contentOrNull
                        .orEmpty()
                        .trim()
                PlaylistImportManager.CsvTrack(title, artist, null)
            }
        val heading =
            Regex("""<h1\b[^>]*>.*?</h1\s*>""", RegexOption.DOT_MATCHES_ALL).findAll(html).singleOrNull()?.value
                ?: throw ImportException(ImportErrorKey.NoTracksRecognized)
        val title =
            parseXml(heading).textContent.trim().takeIf(String::isNotEmpty)
                ?: throw ImportException(ImportErrorKey.NoTracksRecognized)
        return ImportedPlaylistData(title = title, tracks = tracks, source = "Tidal")
    }

    private fun embedMetadata(html: String): JsonObject? {
        val assignment = Regex("""window\.tidalMediaSessionMetadata\s*=\s*""").find(html) ?: return null
        val start = assignment.range.last + 1
        if (html.getOrNull(start) != '{') throw ImportException(ImportErrorKey.NoTracksRecognized)
        var depth = 0
        var quoted = false
        var escaped = false
        // Read JSON only, never execute the embed's JavaScript. Braces inside titles are ordinary text.
        for (index in start until html.length) {
            val char = html[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> {
                        quoted = true
                    }

                    '{' -> {
                        depth++
                    }

                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return json.parseToJsonElement(html.substring(start, index + 1)).jsonObject
                        }
                    }
                }
            }
        }
        throw ImportException(ImportErrorKey.NoTracksRecognized)
    }
}
