package com.metrolist.shared.playlistimport

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.import_error_album_tracks_unavailable
import com.metrolist.shared.generated.resources.import_error_playlist_tracks_unavailable
import com.metrolist.shared.generated.resources.import_provider_amazon
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

object AmazonMusicPlaylistImporter {
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    val marketplaceHosts =
        setOf(
            "music.amazon.com",
            "music.amazon.co.uk",
            "music.amazon.de",
            "music.amazon.fr",
            "music.amazon.it",
            "music.amazon.es",
            "music.amazon.co.jp",
            "music.amazon.ca",
            "music.amazon.com.au",
            "music.amazon.com.br",
            "music.amazon.com.mx",
            "music.amazon.ie",
            "music.amazon.in",
            "music.amazon.nl",
            "music.amazon.se",
            "music.amazon.pl",
            "music.amazon.com.tr",
            "music.amazon.ae",
            "music.amazon.sa",
            "music.amazon.sg",
            "music.amazon.co.za",
            "music.amazon.com.be",
            "music.amazon.com.eg",
        )

    private val defaultHttpClient: HttpClient by lazy { createHttpClient() }
    internal var clientProvider: () -> HttpClient = { defaultHttpClient }
    private val httpClient: HttpClient get() = clientProvider()
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    internal fun resetClientForTests(client: HttpClient? = null) {
        clientProvider =
            if (client != null) {
                { client }
            } else {
                { defaultHttpClient }
            }
    }

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("music.amazon.") || lower.matches(Regex("b0[a-z0-9]{8}"))
    }

    suspend fun loadPlaylist(
        urlOrId: String,
        fallbackTitles: PlaylistImportFallbackTitles? = null,
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): Result<ImportedPlaylistData> {
        return try {
            val parsed = parseUrl(urlOrId) ?: return Result.failure(IllegalArgumentException("Invalid Amazon Music URL"))

            when (parsed.type) {
                Type.PLAYLIST -> loadPlaylistTracks(parsed.url, fallbackTitles, onProgress)
                Type.ALBUM -> loadAlbumTracks(parsed.url, fallbackTitles, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private enum class Type { PLAYLIST, ALBUM }

    private data class ParsedUrl(
        val type: Type,
        val url: String,
    )

    private fun parseUrl(input: String): ParsedUrl? {
        val trimmed = input.trim()
        if (trimmed.matches(Regex("""B0[A-Z0-9]{8}"""))) {
            return ParsedUrl(Type.ALBUM, "https://music.amazon.com/albums/$trimmed")
        }
        val absolute = if (trimmed.startsWith("music.amazon.", ignoreCase = true)) "https://$trimmed" else trimmed
        val url = runCatching { Url(absolute) }.getOrNull() ?: return null

        val host = url.host.lowercase()
        if (url.protocol.name != "https" || url.port != 443 || host !in marketplaceHosts) return null
        if (!url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.fragment.isNotEmpty()) return null

        val route =
            Regex("""/(user-playlists|userplaylists|playlists|albums)/([A-Z0-9]{10,}|[a-f0-9-]+)/?""", RegexOption.IGNORE_CASE)
                .matchEntire(url.encodedPath) ?: return null
        val routeName = route.groups[1]?.value ?: return null
        val id = route.groups[2]?.value ?: return null
        val type = if (routeName.equals("albums", ignoreCase = true)) Type.ALBUM else Type.PLAYLIST
        if (type == Type.ALBUM && !id.matches(Regex("""[A-Z0-9]{10,}""", RegexOption.IGNORE_CASE))) return null

        return ParsedUrl(type, "https://$host${url.encodedPath}")
    }

    private suspend fun loadPlaylistTracks(
        playlistUrl: String,
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val response =
            withTimeout(15_000.milliseconds) {
                httpClient.get(playlistUrl) {
                    header("User-Agent", UA)
                }
            }
        val html = response.bodyAsTextLimited(MAX_RESPONSE_BYTES)
        if (!response.status.isSuccess() || html.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Amazon Music playlist not found (HTTP ${response.status.value})"))
        }

        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()

        val jsonLdMatch =
            Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
        if (jsonLdMatch != null) {
            val root = json.parseToJsonElement(jsonLdMatch.groupValues[1]).jsonObject
            val trackList =
                root["trackList"]
                    ?.jsonArray
                    ?: root["tracks"]
                        ?.jsonArray
                    ?: root["itemListElement"]
                        ?.jsonArray
                    ?: JsonArray(emptyList())

            val title = root["name"]?.jsonPrimitive?.content ?: fallbackTitles?.playlist ?: getString(Res.string.playlist)
            val coverUrl =
                root["image"]?.let { image ->
                    when (image) {
                        is JsonArray -> image.firstOrNull()?.jsonPrimitive?.contentOrNull
                        is JsonObject -> image["url"]?.jsonPrimitive?.contentOrNull
                        else -> image.jsonPrimitive.contentOrNull
                    }
                }

            for (item in trackList) {
                val obj = item.jsonObject
                val trackTitle = obj["name"]?.jsonPrimitive?.content?.trim() ?: continue
                val artistName =
                    obj["byArtist"]
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim()
                        ?: obj["artist"]
                            ?.jsonPrimitive
                            ?.content
                            ?.trim()
                        ?: ""
                tracks += PlaylistImportManager.CsvTrack(trackTitle, artistName, null)
            }

            onProgress(tracks.size, tracks.size)

            if (tracks.isNotEmpty()) {
                return Result.success(
                    ImportedPlaylistData(
                        title = title,
                        tracks = tracks,
                        source = "Amazon Music",
                        coverUrl = coverUrl,
                    ),
                )
            }
        }

        return Result.failure(
            IllegalStateException(
                getString(Res.string.import_error_playlist_tracks_unavailable, getString(Res.string.import_provider_amazon)),
            ),
        )
    }

    private suspend fun loadAlbumTracks(
        albumUrl: String,
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val response =
            withTimeout(15_000.milliseconds) {
                httpClient.get(albumUrl) {
                    header("User-Agent", UA)
                }
            }
        val html = response.bodyAsTextLimited(MAX_RESPONSE_BYTES)
        if (!response.status.isSuccess() || html.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Amazon Music album not found (HTTP ${response.status.value})"))
        }

        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()

        val jsonLdMatch =
            Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
        if (jsonLdMatch != null) {
            val root = json.parseToJsonElement(jsonLdMatch.groupValues[1]).jsonObject
            val title = root["name"]?.jsonPrimitive?.content ?: fallbackTitles?.album ?: getString(Res.string.album)
            val coverUrl =
                root["image"]?.let { image ->
                    when (image) {
                        is JsonArray -> image.firstOrNull()?.jsonPrimitive?.contentOrNull
                        is JsonObject -> image["url"]?.jsonPrimitive?.contentOrNull
                        else -> image.jsonPrimitive.contentOrNull
                    }
                }
            val albumArtist =
                root["byArtist"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content
                    ?: ""

            val trackList =
                root["trackList"]
                    ?.jsonArray
                    ?: root["tracks"]
                        ?.jsonArray
                    ?: root["itemListElement"]
                        ?.jsonArray
                    ?: JsonArray(emptyList())

            for (item in trackList) {
                val obj = item.jsonObject
                val trackTitle = obj["name"]?.jsonPrimitive?.content?.trim() ?: continue
                val trackArtist =
                    obj["byArtist"]
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim()
                        ?: albumArtist
                tracks += PlaylistImportManager.CsvTrack(trackTitle, trackArtist, null)
            }

            onProgress(tracks.size, tracks.size)

            if (tracks.isNotEmpty()) {
                return Result.success(
                    ImportedPlaylistData(
                        title = title,
                        tracks = tracks,
                        source = "Amazon Music",
                        coverUrl = coverUrl,
                    ),
                )
            }
        }

        return Result.failure(
            IllegalStateException(
                getString(Res.string.import_error_album_tracks_unavailable, getString(Res.string.import_provider_amazon)),
            ),
        )
    }
}
