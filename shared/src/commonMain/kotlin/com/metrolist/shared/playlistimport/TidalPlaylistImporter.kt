package com.metrolist.shared.playlistimport

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.import_error_album_tracks_unavailable
import com.metrolist.shared.generated.resources.import_error_playlist_tracks_unavailable
import com.metrolist.shared.generated.resources.import_provider_tidal
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.generated.resources.track
import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.getString
import kotlin.time.Duration.Companion.milliseconds

object TidalPlaylistImporter {
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    private val httpClient: HttpClient by lazy { createHttpClient() }
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
                Type.TRACK -> loadSingleTrack(id, fallbackTitles, onProgress)
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
            withTimeout(15_000.milliseconds) {
                httpClient.get(embedUrl) {
                    header("User-Agent", UA)
                }
            }
        val html = response.bodyAsTextLimited(MAX_RESPONSE_BYTES)
        if (!response.status.isSuccess() || html.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Tidal playlist not found (HTTP ${response.status.value})"))
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
            withTimeout(15_000.milliseconds) {
                httpClient.get(embedUrl) {
                    header("User-Agent", UA)
                }
            }
        val html = response.bodyAsTextLimited(MAX_RESPONSE_BYTES)
        if (!response.status.isSuccess() || html.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Tidal album not found (HTTP ${response.status.value})"))
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
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val embedUrl = "https://embed.tidal.com/tracks/$trackId"
        val response =
            withTimeout(15_000.milliseconds) {
                httpClient.get(embedUrl) {
                    header("User-Agent", UA)
                }
            }
        val html = response.bodyAsTextLimited(MAX_RESPONSE_BYTES)
        if (!response.status.isSuccess() || html.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Tidal track not found (HTTP ${response.status.value})"))
        }

        val title =
            Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: fallbackTitles?.track ?: getString(Res.string.track)

        val artistName =
            Regex("""<meta\s+property="og:description"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: ""

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
}
