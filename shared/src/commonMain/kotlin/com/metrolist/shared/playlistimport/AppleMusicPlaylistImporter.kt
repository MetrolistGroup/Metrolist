package com.metrolist.shared.playlistimport

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.systemCountryCode
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import meow.nyx.spotlib.PlaylistMeta
import org.jetbrains.compose.resources.getString
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

object AppleMusicPlaylistImporter {
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MAX_HTML_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_SCRIPT_RESPONSE_BYTES = 16 * 1024 * 1024
    private const val MAX_JSON_RESPONSE_BYTES = 8 * 1024 * 1024

    private val defaultHttpClient: HttpClient by lazy { createHttpClient() }
    internal var clientProvider: () -> HttpClient = { defaultHttpClient }
    private val httpClient: HttpClient get() = clientProvider()
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L
    private val tokenMutex = Mutex()

    internal fun resetClientForTests(client: HttpClient? = null) {
        clientProvider =
            if (client != null) {
                { client }
            } else {
                { defaultHttpClient }
            }
        cachedToken = null
        tokenExpiry = 0L
    }

    @Serializable
    private data class TokenPayload(
        val exp: Long? = null,
    )

    fun extractAppleMusicUserToken(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        val match = Regex("""(?:^|;\s*)media-user-token=([^;]+)""").find(cookieHeader)
        return match
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    suspend fun loadUserPlaylists(userToken: String): Result<List<PlaylistMeta>> {
        return try {
            val devToken = getDevToken() ?: return Result.failure(IllegalStateException("Failed to obtain Apple Music developer token"))
            val playlists = mutableListOf<PlaylistMeta>()
            var nextUrl: String? = "https://api.music.apple.com/v1/me/library/playlists?limit=100"

            while (nextUrl != null && playlists.size < 500) {
                val fullUrl = appleApiUrl(nextUrl)
                val response =
                    withTimeout(15_000.milliseconds) {
                        httpClient.get(fullUrl) {
                            header("Authorization", "Bearer $devToken")
                            header("Music-User-Token", userToken)
                            header("Origin", "https://music.apple.com")
                            header("User-Agent", UA)
                        }
                    }
                val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: break
                if (!response.status.isSuccess()) break

                val root = json.parseToJsonElement(body).jsonObject
                val dataArray = root["data"]?.jsonArray ?: break
                for (item in dataArray) {
                    val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: continue
                    val attrs = item.jsonObject["attributes"]?.jsonObject ?: continue
                    val name = attrs["name"]?.jsonPrimitive?.content ?: getString(Res.string.playlist)
                    val trackCount = attrs["trackCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val artworkObj = attrs["artwork"]?.jsonObject
                    val artworkRaw = artworkObj?.get("url")?.jsonPrimitive?.content
                    val artworkUrl = artworkRaw?.replace("{w}", "300")?.replace("{h}", "300")
                    playlists +=
                        PlaylistMeta(
                            uri = "apple:playlist:$id",
                            name = name,
                            owner = "Apple Music",
                            trackCount = trackCount,
                            image = artworkUrl,
                        )
                }
                nextUrl = root["next"]?.jsonPrimitive?.content
            }
            Result.success(playlists)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadUserPlaylistTracks(
        userToken: String,
        playlistUriOrId: String,
        playlistName: String = "",
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): Result<ImportedPlaylistData> {
        return try {
            val devToken = getDevToken() ?: return Result.failure(IllegalStateException("Failed to obtain Apple Music developer token"))
            val cleanId = playlistUriOrId.removePrefix("apple:playlist:")
            val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()
            var nextUrl: String? = "https://api.music.apple.com/v1/me/library/playlists/$cleanId/tracks?limit=100"

            while (nextUrl != null && tracks.size < PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
                val fullUrl = appleApiUrl(nextUrl)
                val response =
                    withTimeout(15_000.milliseconds) {
                        httpClient.get(fullUrl) {
                            header("Authorization", "Bearer $devToken")
                            header("Music-User-Token", userToken)
                            header("Origin", "https://music.apple.com")
                            header("User-Agent", UA)
                        }
                    }
                val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: break
                if (!response.status.isSuccess()) break

                val root = json.parseToJsonElement(body).jsonObject
                val dataArray = root["data"]?.jsonArray ?: break
                for (trackItem in dataArray) {
                    val attrs = trackItem.jsonObject["attributes"]?.jsonObject ?: continue
                    val trackName = attrs["name"]?.jsonPrimitive?.content?.trim() ?: continue
                    val artistName = attrs["artistName"]?.jsonPrimitive?.content?.trim() ?: ""
                    tracks +=
                        PlaylistImportManager.CsvTrack(
                            title = trackName,
                            artist = artistName,
                            videoId = null,
                        )
                }
                onProgress(tracks.size, null)
                nextUrl = root["next"]?.jsonPrimitive?.content
            }

            if (tracks.isEmpty()) {
                return Result.failure(IllegalStateException("No tracks found in this playlist"))
            }

            Result.success(
                ImportedPlaylistData(
                    title = playlistName.ifBlank { getString(Res.string.playlist) },
                    tracks = tracks,
                    source = "Apple Music",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun canHandle(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("music.apple.com") || lower.startsWith("pl.")
    }

    suspend fun loadPlaylist(
        urlOrId: String,
        defaultStorefront: String? = null,
        fallbackTitles: PlaylistImportFallbackTitles? = null,
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): Result<ImportedPlaylistData> {
        return try {
            val (explicitStorefront, id, isAlbum) =
                parseUrl(urlOrId)
                    ?: return Result.failure(IllegalArgumentException("Invalid Apple Music URL or identifier"))
            val storefront =
                (
                    explicitStorefront
                        ?: defaultStorefront
                        ?: Settings.shared.contentCountry.takeUnless { it == GlobalYouTube.SYSTEM }
                        ?: systemCountryCode()
                ).takeIf { it.isNotBlank() }
                    ?.lowercase()
                    ?: return Result.failure(ImportException(ImportErrorKey.Generic))

            val token =
                getDevToken()
                    ?: return Result.failure(IllegalStateException("Could not authenticate with Apple Music"))

            if (isAlbum) {
                loadAlbum(token, storefront, id, fallbackTitles, onProgress)
            } else {
                loadPlaylistTracks(token, storefront, id, fallbackTitles, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseUrl(input: String): Triple<String?, String, Boolean>? {
        val trimmed = input.trim()
        if (trimmed.startsWith("pl.")) {
            return Triple(null, trimmed, false)
        }

        val playlistRegex = Regex("""music\.apple\.com(?:/([a-z]{2}))?/playlist/(?:[^/]+/)?(pl\.[a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
        val albumRegex = Regex("""music\.apple\.com(?:/([a-z]{2}))?/album/(?:[^/]+/)?(\d+)""", RegexOption.IGNORE_CASE)

        playlistRegex.find(trimmed)?.let { match ->
            val storefront = match.groups[1]?.value?.lowercase()
            val id = match.groups[2]?.value ?: return null
            return Triple(storefront, id, false)
        }

        albumRegex.find(trimmed)?.let { match ->
            val storefront = match.groups[1]?.value?.lowercase()
            val id = match.groups[2]?.value ?: return null
            return Triple(storefront, id, true)
        }

        val directPl = Regex("""(pl\.[a-zA-Z0-9_-]+)""").find(trimmed)?.groupValues?.get(1)
        if (directPl != null) {
            return Triple(null, directPl, false)
        }

        return null
    }

    private suspend fun loadPlaylistTracks(
        token: String,
        storefront: String,
        playlistId: String,
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val metaUrl = "https://api.music.apple.com/v1/catalog/$storefront/playlists/$playlistId"
        val metaResponse =
            withTimeout(15_000.milliseconds) {
                httpClient.get(metaUrl) {
                    header("Authorization", "Bearer $token")
                    header("Origin", "https://music.apple.com")
                    header("User-Agent", UA)
                }
            }
        val metaBody = metaResponse.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES)
        if (!metaResponse.status.isSuccess() || metaBody.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Apple Music playlist not found (HTTP ${metaResponse.status.value})"))
        }

        val metaRoot = json.parseToJsonElement(metaBody).jsonObject
        val playlistItem =
            metaRoot["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return Result.failure(IllegalStateException("Apple Music playlist data empty"))

        val title =
            playlistItem["attributes"]
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.content ?: fallbackTitles?.playlist ?: getString(Res.string.playlist)
        val totalReported =
            playlistItem["attributes"]
                ?.jsonObject
                ?.get("trackCount")
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull()
        val artwork =
            playlistItem["attributes"]
                ?.jsonObject
                ?.get("artwork")
                ?.jsonObject
        val coverUrl =
            artwork
                ?.get("url")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.replace("{w}", "1000")
                ?.replace("{h}", "1000")

        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()
        var nextUrl: String? = "https://api.music.apple.com/v1/catalog/$storefront/playlists/$playlistId/tracks?limit=100"

        while (nextUrl != null && tracks.size < PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
            val fullUrl = appleApiUrl(nextUrl)
            val response =
                withTimeout(15_000.milliseconds) {
                    httpClient.get(fullUrl) {
                        header("Authorization", "Bearer $token")
                        header("Origin", "https://music.apple.com")
                        header("User-Agent", UA)
                    }
                }
            val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: break
            if (!response.status.isSuccess()) break

            val root = json.parseToJsonElement(body).jsonObject
            val dataArray = root["data"]?.jsonArray ?: break
            for (trackItem in dataArray) {
                val attrs = trackItem.jsonObject["attributes"]?.jsonObject ?: continue
                val trackName = attrs["name"]?.jsonPrimitive?.content?.trim() ?: continue
                val artistName = attrs["artistName"]?.jsonPrimitive?.content?.trim() ?: ""
                tracks +=
                    PlaylistImportManager.CsvTrack(
                        title = trackName,
                        artist = artistName,
                        videoId = null,
                    )
            }

            onProgress(tracks.size, totalReported ?: tracks.size)
            nextUrl = root["next"]?.jsonPrimitive?.content
        }

        if (tracks.isEmpty()) {
            return Result.failure(IllegalStateException("No tracks could be retrieved from this Apple Music playlist"))
        }

        return Result.success(
            ImportedPlaylistData(
                title = title,
                tracks = tracks,
                source = "Apple Music",
                coverUrl = coverUrl,
            ),
        )
    }

    private suspend fun loadAlbum(
        token: String,
        storefront: String,
        albumId: String,
        fallbackTitles: PlaylistImportFallbackTitles?,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): Result<ImportedPlaylistData> {
        val metaUrl = "https://api.music.apple.com/v1/catalog/$storefront/albums/$albumId"
        val response =
            withTimeout(15_000.milliseconds) {
                httpClient.get(metaUrl) {
                    header("Authorization", "Bearer $token")
                    header("Origin", "https://music.apple.com")
                    header("User-Agent", UA)
                }
            }
        val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES)
        if (!response.status.isSuccess() || body.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Apple Music album not found (HTTP ${response.status.value})"))
        }

        val root = json.parseToJsonElement(body).jsonObject
        val albumItem =
            root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return Result.failure(IllegalStateException("Apple Music album data empty"))

        val attrs = albumItem["attributes"]?.jsonObject
        val title = attrs?.get("name")?.jsonPrimitive?.content ?: fallbackTitles?.album ?: getString(Res.string.album)
        val albumArtist = attrs?.get("artistName")?.jsonPrimitive?.content ?: ""

        val tracksArray =
            albumItem["relationships"]
                ?.jsonObject
                ?.get("tracks")
                ?.jsonObject
                ?.get("data")
                ?.jsonArray ?: JsonArray(emptyList())

        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()
        for (trackItem in tracksArray) {
            val trackAttrs = trackItem.jsonObject["attributes"]?.jsonObject ?: continue
            val trackName = trackAttrs["name"]?.jsonPrimitive?.content?.trim() ?: continue
            val trackArtist = trackAttrs["artistName"]?.jsonPrimitive?.content?.trim() ?: albumArtist
            tracks +=
                PlaylistImportManager.CsvTrack(
                    title = trackName,
                    artist = trackArtist,
                    videoId = null,
                )
        }

        onProgress(tracks.size, tracks.size)

        if (tracks.isEmpty()) {
            return Result.failure(IllegalStateException("No tracks found in this Apple Music album"))
        }

        return Result.success(
            ImportedPlaylistData(
                title = title,
                tracks = tracks,
                source = "Apple Music",
            ),
        )
    }

    private suspend fun getDevToken(): String? {
        val now = Clock.System.now().epochSeconds
        tokenMutex.withLock {
            cachedToken?.let { token ->
                if (tokenExpiry - now > 300) return token
            }
        }

        return try {
            val browseHtml =
                withTimeout(15_000.milliseconds) {
                    val response =
                        httpClient.get("https://music.apple.com/browse") {
                            header("User-Agent", UA)
                            header("Accept", "text/html")
                        }
                    response.takeIf { it.status.isSuccess() }?.bodyAsTextLimited(MAX_HTML_RESPONSE_BYTES)
                } ?: return null

            val bundlePath = Regex("""/assets/index[^"'\s]*\.js""").find(browseHtml)?.value ?: return null
            val jsUrl = "https://music.apple.com$bundlePath"

            val js =
                withTimeout(15_000.milliseconds) {
                    val response =
                        httpClient.get(jsUrl) {
                            header("User-Agent", UA)
                        }
                    response.takeIf { it.status.isSuccess() }?.bodyAsTextLimited(MAX_SCRIPT_RESPONSE_BYTES)
                } ?: return null

            val jwt = Regex("""eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""").find(js)?.value ?: return null
            var exp = now + 86400
            runCatching {
                val parts = jwt.split('.')
                if (parts.size >= 2) {
                    val payload =
                        json.decodeFromString<TokenPayload>(
                            Base64.UrlSafe
                                .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                                .decode(parts[1])
                                .decodeToString(),
                        )
                    exp = payload.exp ?: exp
                }
            }

            tokenMutex.withLock {
                cachedToken = jwt
                tokenExpiry = exp
            }
            jwt
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun appleApiUrl(path: String): String {
        if (path.startsWith('/')) return "https://api.music.apple.com$path"
        val url = Url(path)
        require(url.protocol.name == "https" && url.host == "api.music.apple.com")
        return path
    }
}
