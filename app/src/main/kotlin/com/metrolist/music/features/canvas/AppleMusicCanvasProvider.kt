package com.metrolist.music.features.canvas

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AppleMusicCanvasProvider {
    private const val AMP_BASE_URL = "https://amp-api.music.apple.com"
    private const val FALLBACK_TOKEN =
        "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ" +
            ".eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzgxMDMyODU1LCJleHAiOjE3ODQwNTY4NTUsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ" +
            ".fiMFcJWkfSlxKP9NVA0UW9CbItD1Rge0SISuepz203XcpU762OqdCpU9M-YkmtKkjRmaIWtjsfGgqZPrlMonpA"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 25_000
                socketTimeoutMillis = 25_000
            }
            expectSuccess = false
        }
    }
    private data class CacheEntry(val value: CanvasArtwork?, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryMs: Long = 0L

    suspend fun getBySongArtist(
        song: String,
        artist: String,
        album: String? = null,
        storefront: String = "us",
    ): CanvasArtwork? {
        val key = listOf("song", song, artist, album.orEmpty(), storefront).joinToString("|") { it.lowercase(Locale.ROOT).trim() }
        cache[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.value }
        val result = runCatching { search(song, artist, album, storefront) }.getOrNull()
        cache[key] = CacheEntry(result, System.currentTimeMillis() + CACHE_TTL_MS)
        return result
    }

    private suspend fun search(song: String, artist: String, album: String?, storefront: String): CanvasArtwork? {
        if (song.isBlank() || artist.isBlank()) return null
        val query = listOf(artist, song, album).filter { !it.isNullOrBlank() }.joinToString(" ")
        val response = client.get("$AMP_BASE_URL/v1/catalog/$storefront/search") {
            header("Authorization", "Bearer ${getToken()}")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", USER_AGENT)
            parameter("term", query)
            parameter("types", "songs")
            parameter("limit", "10")
            parameter("extend", "editorialVideo")
            parameter("include", "albums")
        }
        if (response.status != HttpStatusCode.OK) return null
        val root = response.body<JsonObject>()
        val results = root["results"]?.jsonObject?.get("songs")?.jsonObject?.get("data")?.jsonArray ?: return null
        val candidates = results.mapNotNull { item ->
            val obj = item.jsonObject
            val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
            val returnedName = attrs["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val returnedArtist = attrs["artistName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!artistMatches(artist, returnedArtist)) return@mapNotNull null
            val titleScore = when {
                returnedName.normalizeForCanvasComparison() == song.normalizeForCanvasComparison() -> 20
                returnedName.normalizeForCanvasComparison().contains(song.normalizeForCanvasComparison()) -> 8
                else -> -10
            }
            val albumName = attrs["albumName"]?.jsonPrimitive?.contentOrNull
                ?: attrs["collectionName"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val albumScore = if (!album.isNullOrBlank() && albumName.normalizeForCanvasComparison() == album.normalizeForCanvasComparison()) 20 else 0
            titleScore + albumScore to item
        }.sortedByDescending { it.first }

        for ((score, item) in candidates) {
            if (score < 10) continue
            val obj = item.jsonObject
            val attrs = obj["attributes"]?.jsonObject ?: continue
            val resultName = attrs["name"]?.jsonPrimitive?.contentOrNull
            val resultArtist = attrs["artistName"]?.jsonPrimitive?.contentOrNull
            val resultAlbum = attrs["albumName"]?.jsonPrimitive?.contentOrNull
                ?: attrs["collectionName"]?.jsonPrimitive?.contentOrNull

            // Some Apple catalog song results omit editorialVideo. Resolve the
            // song's album endpoint before giving up, as the web client does.
            val directVideo = attrs["editorialVideo"]?.jsonObject
            val normalUrl = directVideo?.let { extractVideo(it, preferTall = false) }
            if (!normalUrl.isNullOrBlank()) {
                return CanvasArtwork(
                    name = resultName,
                    artist = resultArtist,
                    albumName = resultAlbum,
                    animated = normalUrl,
                    animatedTall = directVideo?.let { extractVideo(it, preferTall = true) },
                )
            }

            val albumId = extractAlbumId(attrs)
            if (!albumId.isNullOrBlank()) {
                fetchAlbumArtwork(
                    albumId = albumId,
                    storefront = storefront,
                    songTitle = resultName,
                    songArtist = resultArtist,
                )?.let { return it }
            }
        }
        return null
    }

    private fun extractAlbumId(attrs: JsonObject): String? {
        attrs["url"]?.jsonPrimitive?.contentOrNull?.let { url ->
            Regex("/album/[^/]+/(\\d+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return attrs["collectionId"]?.jsonPrimitive?.contentOrNull
    }

    private suspend fun fetchAlbumArtwork(
        albumId: String,
        storefront: String,
        songTitle: String?,
        songArtist: String?,
    ): CanvasArtwork? = runCatching {
        val response = client.get("$AMP_BASE_URL/v1/catalog/$storefront/albums/$albumId") {
            header("Authorization", "Bearer ${getToken()}")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("User-Agent", USER_AGENT)
            parameter("extend", "editorialVideo")
            parameter("include", "tracks")
        }
        if (response.status != HttpStatusCode.OK) return@runCatching null
        val data = response.body<JsonObject>()["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
        val attrs = data["attributes"]?.jsonObject ?: return@runCatching null
        val video = attrs["editorialVideo"]?.jsonObject ?: return@runCatching null
        val normalUrl = extractVideo(video, preferTall = false) ?: return@runCatching null
        CanvasArtwork(
            name = songTitle ?: attrs["name"]?.jsonPrimitive?.contentOrNull,
            artist = songArtist ?: attrs["artistName"]?.jsonPrimitive?.contentOrNull,
            albumName = attrs["name"]?.jsonPrimitive?.contentOrNull,
            animated = normalUrl,
            animatedTall = extractVideo(video, preferTall = true),
        )
    }.getOrNull()

    private suspend fun getToken(): String {
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { now < tokenExpiryMs - 60_000 }?.let { return it }
        return runCatching {
            val html = client.get("https://music.apple.com/us/browse") {
                header("User-Agent", USER_AGENT)
            }.body<String>()
            val paths = Regex("/assets/index(?:-legacy)?[~-][a-zA-Z0-9_-]+\\.js")
                .findAll(html).map { it.value }.distinct().toList()
            for (path in paths) {
                val js = client.get("https://music.apple.com$path") {
                    header("User-Agent", USER_AGENT)
                }.body<String>()
                for (token in Regex("ey[a-zA-Z0-9_-]+\\.ey[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+").findAll(js).map { it.value }) {
                    val payload = token.split('.').getOrNull(1) ?: continue
                    val decoded = runCatching { String(Base64.getUrlDecoder().decode(payload)) }.getOrNull() ?: continue
                    val expiry = Regex("\\\"exp\\\":(\\d+)").find(decoded)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: continue
                    if (expiry * 1000 > now) {
                        cachedToken = token
                        tokenExpiryMs = expiry * 1000
                        return token
                    }
                }
            }
            FALLBACK_TOKEN
        }.getOrDefault(FALLBACK_TOKEN)
    }

    private fun extractVideo(video: JsonObject, preferTall: Boolean): String? {
        val keys = if (preferTall) {
            listOf("motionDetailTall", "motionDetailRaw", "motionDetailSquare", "motionDetailStatic")
        } else {
            listOf("motionDetailSquare", "motionDetailRaw", "motionDetailTall", "motionDetailStatic")
        }
        for (key in keys) {
            val asset = video[key]?.jsonObject ?: continue
            val url = listOf("video", "videoUrl", "hlsUrl", "url")
                .firstNotNullOfOrNull { asset[it]?.jsonPrimitive?.contentOrNull }
            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    private fun artistMatches(requested: String, returned: String): Boolean {
        val delimiters = Regex("(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)", RegexOption.IGNORE_CASE)
        val requestedArtists = requested.split(delimiters).map { it.normalizeForCanvasComparison() }.filter { it.isNotBlank() }
        val returnedArtists = returned.split(delimiters).map { it.normalizeForCanvasComparison() }.filter { it.isNotBlank() }
        return requestedArtists.isNotEmpty() && requestedArtists.all { req -> returnedArtists.any { it == req } }
    }

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
}
