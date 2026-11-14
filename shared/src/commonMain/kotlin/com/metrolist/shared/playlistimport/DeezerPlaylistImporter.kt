package com.metrolist.shared.playlistimport

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.getString
import kotlin.time.Duration.Companion.milliseconds

object DeezerPlaylistImporter {
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MAX_JSON_RESPONSE_BYTES = 8 * 1024 * 1024

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
        return lower.contains("deezer.com") || lower.contains("deezer.page.link")
    }

    suspend fun loadPlaylist(
        urlOrId: String,
        fallbackTitles: PlaylistImportFallbackTitles? = null,
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): Result<ImportedPlaylistData> {
        return try {
            val (type, id) =
                parseUrl(urlOrId)
                    ?: return Result.failure(IllegalArgumentException("Invalid Deezer URL or identifier"))

            when (type) {
                Type.PLAYLIST -> loadPlaylistTracks(id, fallbackTitles, onProgress)
                Type.ALBUM -> loadAlbumTracks(id, fallbackTitles, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private enum class Type { PLAYLIST, ALBUM }

    private suspend fun parseUrl(input: String): Pair<Type, String>? {
        var trimmed = input.trim()

        if (trimmed.contains("deezer.page.link")) {
            val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
            if (url.protocol.name != "https" || url.host != "deezer.page.link" || url.user?.isNotEmpty() == true) return null
            val response =
                httpClient.get(trimmed) {
                    header("User-Agent", UA)
                }
            val redirected = response.call.request.url
            if (redirected.protocol.name != "https" ||
                (redirected.host != "deezer.com" && !redirected.host.endsWith(".deezer.com"))
            ) {
                return null
            }
            trimmed = redirected.toString()
        }

        val playlistRegex = Regex("""deezer\.com(?:/[a-z]{2})?/playlist/(\d+)""", RegexOption.IGNORE_CASE)
        playlistRegex.find(trimmed)?.let {
            return Type.PLAYLIST to it.groupValues[1]
        }

        val albumRegex = Regex("""deezer\.com(?:/[a-z]{2})?/album/(\d+)""", RegexOption.IGNORE_CASE)
        albumRegex.find(trimmed)?.let {
            return Type.ALBUM to it.groupValues[1]
        }

        if (trimmed.matches(Regex("""^\d+$"""))) {
            return Type.PLAYLIST to trimmed
        }

        return null
    }

    private suspend fun loadPlaylistTracks(
        playlistId: String,
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val url = "https://api.deezer.com/playlist/$playlistId"
        val response =
            withTimeout(15_000.milliseconds) {
                httpClient.get(url) {
                    header("User-Agent", UA)
                }
            }
        val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES)
        if (!response.status.isSuccess() || body.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Deezer playlist not found (HTTP ${response.status.value})"))
        }

        val root = json.parseToJsonElement(body).jsonObject
        if (root.containsKey("error")) {
            val errMsg =
                root["error"]
                    ?.jsonObject
                    ?.get("message")
                    ?.jsonPrimitive
                    ?.content ?: "Deezer API error"
            return Result.failure(IllegalStateException(errMsg))
        }

        val title = root["title"]?.jsonPrimitive?.content ?: fallbackTitles?.playlist ?: getString(Res.string.playlist)
        val coverUrl = root["picture_xl"]?.jsonPrimitive?.contentOrNull
        val totalExpected = root["nb_tracks"]?.jsonPrimitive?.content?.toIntOrNull()

        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()
        val initialTracks = root["tracks"]?.jsonObject?.get("data")?.jsonArray
        if (initialTracks != null) {
            for (item in initialTracks) {
                val obj = item.jsonObject
                val trackTitle = obj["title"]?.jsonPrimitive?.content?.trim() ?: continue
                val artistName =
                    obj["artist"]
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim() ?: ""
                tracks += PlaylistImportManager.CsvTrack(trackTitle, artistName, null)
            }
        }

        onProgress(tracks.size, totalExpected ?: tracks.size)

        var nextUrl =
            root["tracks"]
                ?.jsonObject
                ?.get("next")
                ?.jsonPrimitive
                ?.content
        while (nextUrl != null && tracks.size < PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
            val urlToFetch = deezerApiUrl(nextUrl)
            val pageResponse =
                withTimeout(15_000.milliseconds) {
                    httpClient.get(urlToFetch) {
                        header("User-Agent", UA)
                    }
                }
            if (!pageResponse.status.isSuccess()) throw ImportException(ImportErrorKey.IncompleteSource)
            val pageBody =
                pageResponse.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES)
                    ?: throw ImportException(ImportErrorKey.IncompleteSource)
            val pageRoot = json.parseToJsonElement(pageBody).jsonObject
            if (pageRoot.containsKey("error")) throw ImportException(ImportErrorKey.IncompleteSource)
            val pageData = pageRoot["data"] as? JsonArray ?: throw ImportException(ImportErrorKey.IncompleteSource)
            for (item in pageData) {
                val obj = item.jsonObject
                val trackTitle = obj["title"]?.jsonPrimitive?.content?.trim() ?: continue
                val artistName =
                    obj["artist"]
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim() ?: ""
                tracks += PlaylistImportManager.CsvTrack(trackTitle, artistName, null)
            }

            onProgress(tracks.size, totalExpected ?: tracks.size)
            nextUrl = pageRoot["next"]?.jsonPrimitive?.content
        }

        if (tracks.isEmpty()) {
            return Result.failure(ImportException(ImportErrorKey.NoTracksToImport))
        }

        return Result.success(
            ImportedPlaylistData(
                title = title,
                tracks = tracks,
                source = "Deezer",
                coverUrl = coverUrl,
            ),
        )
    }

    private suspend fun loadAlbumTracks(
        albumId: String,
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val url = "https://api.deezer.com/album/$albumId"
        val response =
            withTimeout(15_000.milliseconds) {
                httpClient.get(url) {
                    header("User-Agent", UA)
                }
            }
        val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES)
        if (!response.status.isSuccess() || body.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Deezer album not found (HTTP ${response.status.value})"))
        }

        val root = json.parseToJsonElement(body).jsonObject
        if (root.containsKey("error")) {
            val errMsg =
                root["error"]
                    ?.jsonObject
                    ?.get("message")
                    ?.jsonPrimitive
                    ?.content ?: "Deezer API error"
            return Result.failure(IllegalStateException(errMsg))
        }

        val title = root["title"]?.jsonPrimitive?.content ?: fallbackTitles?.album ?: getString(Res.string.album)
        val albumArtist =
            root["artist"]
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.content ?: ""

        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()
        val initialTracks = root["tracks"]?.jsonObject?.get("data")?.jsonArray
        if (initialTracks != null) {
            for (item in initialTracks) {
                val obj = item.jsonObject
                val trackTitle = obj["title"]?.jsonPrimitive?.content?.trim() ?: continue
                val trackArtist =
                    obj["artist"]
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim() ?: albumArtist
                tracks += PlaylistImportManager.CsvTrack(trackTitle, trackArtist, null)
            }
        }

        onProgress(tracks.size, tracks.size)

        if (tracks.isEmpty()) {
            return Result.failure(IllegalStateException("No tracks found in this Deezer album"))
        }

        return Result.success(
            ImportedPlaylistData(
                title = title,
                tracks = tracks,
                source = "Deezer",
            ),
        )
    }
}

private fun deezerApiUrl(value: String): String {
    val url = Url(if (value.startsWith('/')) "https://api.deezer.com$value" else value)
    require(url.protocol.name == "https" && url.host == "api.deezer.com" && url.user?.isNotEmpty() != true)
    return url.toString()
}
