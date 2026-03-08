package com.metrolist.shared.playlistimport

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
object SpotifyUrlPlaylistImporter {
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MAX_EMBED_HTML_BYTES = 4 * 1024 * 1024
    private const val MAX_JSON_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val WEB_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"
    private const val APP_VERSION = "1.2.96.390.gd46a85db"
    private const val PAGE_SIZE = 50
    private const val PLAYLIST_CONTENTS_HASH = "e4b2953f160e58e38ac025d79b5a9b3aceee5c4c716598e9830bfceb69faff5f"

    private val defaultHttpClient: HttpClient by lazy { createHttpClient() }
    internal var clientProvider: () -> HttpClient = { defaultHttpClient }
    private val httpClient: HttpClient get() = clientProvider()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val tokenMutex = Mutex()
    private var cachedClientToken: String? = null
    private var clientTokenExpiresAtMs: Long = 0L

    internal fun resetClientForTests(client: HttpClient? = null) {
        clientProvider =
            if (client != null) {
                { client }
            } else {
                { defaultHttpClient }
            }
        cachedClientToken = null
        clientTokenExpiresAtMs = 0L
    }

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("spotify.com") || lower.startsWith("spotify:playlist:") || lower.startsWith("spotify:album:") ||
            lower.matches(Regex("[a-zA-Z0-9]{22}"))
    }

    suspend fun loadPlaylist(
        urlOrUri: String,
        fallbackTitles: PlaylistImportFallbackTitles? = null,
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): Result<ImportedPlaylistData> {
        return try {
            val (type, id) =
                parseUrl(urlOrUri)
                    ?: return Result.failure(IllegalArgumentException("Invalid Spotify URL or URI"))

            loadFromEmbed(type, id, fallbackTitles, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseUrl(input: String): Pair<String, String>? {
        val trimmed = input.trim()

        val playlistRegex = Regex("""spotify\.com/(?:[a-zA-Z0-9_-]+/)?playlist/([a-zA-Z0-9]+)""")
        playlistRegex.find(trimmed)?.let {
            return "playlist" to it.groupValues[1]
        }

        val albumRegex = Regex("""spotify\.com/(?:[a-zA-Z0-9_-]+/)?album/([a-zA-Z0-9]+)""")
        albumRegex.find(trimmed)?.let {
            return "album" to it.groupValues[1]
        }

        val uriRegex = Regex("""spotify:(playlist|album):([a-zA-Z0-9]+)""")
        uriRegex.find(trimmed)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }

        if (trimmed.matches(Regex("""^[a-zA-Z0-9]{22}$"""))) {
            return "playlist" to trimmed
        }

        return null
    }

    private suspend fun getClientToken(): String? {
        tokenMutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            if (cachedClientToken != null && now < clientTokenExpiresAtMs) {
                return cachedClientToken
            }
            return try {
                val payload =
                    buildJsonObject {
                        put(
                            "client_data",
                            buildJsonObject {
                                put("client_version", APP_VERSION)
                                put("client_id", WEB_CLIENT_ID)
                                put(
                                    "js_sdk_data",
                                    buildJsonObject {
                                        put("device_brand", "unknown")
                                        put("device_model", "unknown")
                                        put("os", "android")
                                        put("os_version", "unknown")
                                        put("device_id", Uuid.random().toString())
                                        put("device_type", "computer")
                                    },
                                )
                            },
                        )
                    }

                val response =
                    withTimeoutOrNull(10_000.milliseconds) {
                        httpClient.post(CLIENT_TOKEN_URL) {
                            header(HttpHeaders.UserAgent, UA)
                            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                            header(HttpHeaders.Origin, "https://open.spotify.com")
                            header(HttpHeaders.Referrer, "https://open.spotify.com/")
                            contentType(ContentType.Application.Json)
                            setBody(json.encodeToString(JsonObject.serializer(), payload))
                        }
                    } ?: return null

                if (!response.status.isSuccess()) return null
                val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: return null
                val root = json.parseToJsonElement(body).jsonObject
                val grantedToken = root["granted_token"]?.jsonObject ?: return null
                val token = grantedToken["token"]?.jsonPrimitive?.contentOrNull ?: return null
                val expiresAfterSec =
                    grantedToken["expires_after_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 1209600L
                cachedClientToken = token
                clientTokenExpiresAtMs = now + (expiresAfterSec * 1000L) - 60_000L
                token
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun loadFromEmbed(
        type: String,
        id: String,
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val embedUrl = "https://open.spotify.com/embed/$type/$id"
        val response =
            withTimeoutOrNull(15_000.milliseconds) {
                httpClient.get(embedUrl) {
                    header(HttpHeaders.UserAgent, UA)
                    header(HttpHeaders.Accept, "text/html")
                }
            } ?: throw ImportException(ImportErrorKey.IncompleteSource)
        val html = response.bodyAsTextLimited(MAX_EMBED_HTML_BYTES)
        if (!response.status.isSuccess() || html.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Spotify $type not found (HTTP ${response.status.value})"))
        }

        val scriptMatch =
            Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
                ?: return Result.failure(IllegalStateException("Could not parse Spotify embed payload"))

        val nextData = scriptMatch.groupValues[1]
        val root = json.parseToJsonElement(nextData).jsonObject

        val entity =
            root["props"]
                ?.jsonObject
                ?.get("pageProps")
                ?.jsonObject
                ?.get("state")
                ?.jsonObject
                ?.get("data")
                ?.jsonObject
                ?.get("entity")
                ?.jsonObject
                ?: return Result.failure(IllegalStateException("Spotify metadata not found in embed"))

        val title =
            entity["name"]?.jsonPrimitive?.content
                ?: entity["title"]?.jsonPrimitive?.content
                ?: (if (type == "playlist") fallbackTitles?.playlist else fallbackTitles?.album)
                ?: getString(if (type == "playlist") Res.string.playlist else Res.string.album)
        val coverUrl =
            entity["coverArt"]
                ?.jsonObject
                ?.get("sources")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.contentOrNull

        val trackList = entity["trackList"]?.jsonArray ?: JsonArray(emptyList())
        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()

        for (item in trackList) {
            val trackObj = item.jsonObject
            val trackTitle = trackObj["title"]?.jsonPrimitive?.content?.trim() ?: continue
            val rawArtist = trackObj["subtitle"]?.jsonPrimitive?.content?.trim() ?: ""
            val artistName = rawArtist.replace('\u00A0', ' ')
            tracks += PlaylistImportManager.CsvTrack(trackTitle, artistName, null)
        }

        if (type == "playlist" && trackList.size >= 100) {
            val accessToken =
                root["props"]
                    ?.jsonObject
                    ?.get("pageProps")
                    ?.jsonObject
                    ?.get("state")
                    ?.jsonObject
                    ?.get("settings")
                    ?.jsonObject
                    ?.get("session")
                    ?.jsonObject
                    ?.get("accessToken")
                    ?.jsonPrimitive
                    ?.contentOrNull

            if (accessToken.isNullOrBlank()) throw ImportException(ImportErrorKey.IncompleteSource)
            paginatePlaylistTracks(id, accessToken, trackList.size, tracks, onProgress)
        }

        onProgress(tracks.size, tracks.size)

        if (tracks.isEmpty()) {
            return Result.failure(IllegalStateException("No playable tracks found in this Spotify $type"))
        }

        return Result.success(
            ImportedPlaylistData(
                title = title,
                tracks = tracks,
                source = "Spotify",
                coverUrl = coverUrl,
            ),
        )
    }

    private suspend fun paginatePlaylistTracks(
        playlistId: String,
        accessToken: String,
        initialOffset: Int,
        tracks: MutableList<PlaylistImportManager.CsvTrack>,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ) {
        val clientToken = getClientToken() ?: throw ImportException(ImportErrorKey.IncompleteSource)
        var offset = initialOffset
        var rateLimitRetries = 0

        while (tracks.size < PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
            val variables =
                buildJsonObject {
                    put("uri", "spotify:playlist:$playlistId")
                    put("offset", offset)
                    put("limit", PAGE_SIZE)
                    put("includeEpisodeContentRatingsV2", true)
                }

            val requestBody =
                buildJsonObject {
                    put("operationName", "fetchPlaylistContents")
                    put("variables", variables)
                    put(
                        "extensions",
                        buildJsonObject {
                            put(
                                "persistedQuery",
                                buildJsonObject {
                                    put("version", 1)
                                    put("sha256Hash", PLAYLIST_CONTENTS_HASH)
                                },
                            )
                        },
                    )
                }

            val response =
                try {
                    withTimeoutOrNull(15_000.milliseconds) {
                        httpClient.post(PATHFINDER_URL) {
                            attributes.put(ExpectedHttpFailureStatusCodes, setOf(429))
                            header(HttpHeaders.Authorization, "Bearer $accessToken")
                            header("client-token", clientToken)
                            header(HttpHeaders.Accept, "application/json")
                            header(HttpHeaders.Origin, "https://open.spotify.com")
                            header(HttpHeaders.Referrer, "https://open.spotify.com/")
                            header("app-platform", "WebPlayer")
                            header("spotify-app-version", APP_VERSION)
                            header(HttpHeaders.UserAgent, UA)
                            contentType(ContentType.Application.Json)
                            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
                        }
                    } ?: throw ImportException(ImportErrorKey.IncompleteSource)
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Exception) {
                    throw ImportException(ImportErrorKey.IncompleteSource, error)
                }

            if (response.status.value == 429) {
                val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 2L
                if (retryAfter in 1..5 && rateLimitRetries++ < 2) {
                    delay((retryAfter * 1000L).milliseconds)
                    continue
                } else {
                    PlaybackEventLog.error(
                        PlaybackEventKind.IMPORT,
                        "SpotifyPlaylistImport",
                        "Spotify playlist request remained rate limited",
                        ImportException(ImportErrorKey.IncompleteSource),
                    )
                    throw ImportException(ImportErrorKey.IncompleteSource)
                }
            }
            rateLimitRetries = 0

            if (!response.status.isSuccess()) throw ImportException(ImportErrorKey.IncompleteSource)

            val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: throw ImportException(ImportErrorKey.IncompleteSource)
            val root =
                try {
                    json.parseToJsonElement(body).jsonObject
                } catch (error: Exception) {
                    throw ImportException(ImportErrorKey.IncompleteSource, error)
                }

            val content =
                root["data"]
                    ?.jsonObject
                    ?.get("playlistV2")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonObject
            val items = content?.get("items")?.jsonArray ?: throw ImportException(ImportErrorKey.IncompleteSource)
            val totalCount =
                content
                    .get("totalCount")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull() ?: throw ImportException(ImportErrorKey.IncompleteSource)

            if (offset >= totalCount) return
            if (items.isEmpty()) throw ImportException(ImportErrorKey.IncompleteSource)

            for (item in items) {
                val entry = item as? JsonObject ?: continue
                val wrapper = (entry["itemV2"] as? JsonObject) ?: (entry["track"] as? JsonObject) ?: entry
                val track = wrapper["data"] as? JsonObject ?: wrapper
                val trackTitle = (track["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val uri =
                    (track["uri"] as? JsonPrimitive)?.contentOrNull
                        ?: (wrapper["_uri"] as? JsonPrimitive)?.contentOrNull
                        ?: (wrapper["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (uri.startsWith("spotify:local:") || trackTitle.isBlank()) continue
                val artistName =
                    ((track["artists"] as? JsonObject)?.get("items") as? JsonArray)
                        .orEmpty()
                        .mapNotNull { artist ->
                            val profile = (artist as? JsonObject)?.get("profile") as? JsonObject
                            (profile?.get("name") as? JsonPrimitive)?.contentOrNull
                        }.joinToString(", ")
                        .replace('\u00A0', ' ')
                tracks += PlaylistImportManager.CsvTrack(trackTitle, artistName, null)
                if (tracks.size >= PlaylistImportManager.MAX_PLAYLIST_TRACKS) break
            }

            onProgress(tracks.size, totalCount)

            offset += items.size
            if (offset >= totalCount) return
        }
        throw ImportException(ImportErrorKey.IncompleteSource)
    }
}
