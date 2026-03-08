package com.metrolist.shared.api.artwork

import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.network.readTextLimited
import com.metrolist.shared.network.sharedHttpTransportHandlesFailures
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.SystemLocale
import com.metrolist.shared.youtube.logging.YtLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import meow.nyx.spotlib.LogLevel
import meow.nyx.spotlib.SpotifyClient
import meow.nyx.spotlib.SpotifyConfig
import meow.nyx.spotlib.SpotifySession
import java.net.URI
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

class DirectCanvasProvider(
    client: HttpClient,
    private val session: () -> String = { Settings.shared.spotifySession },
    private val country: () -> String = {
        Settings.shared.contentCountry.takeUnless { it == "SYSTEM" } ?: SystemLocale.current.country
    },
    private val language: () -> String = {
        Settings.shared.contentLanguage.takeUnless { it == "SYSTEM" } ?: SystemLocale.current.toLanguageTag()
    },
    private val now: () -> Long = System::currentTimeMillis,
    private val saveSession: (String) -> Unit = { Settings.shared.spotifySession = it },
) {
    private val http =
        HttpClient(client.engine) {
            install(HttpFailureLog) { transportHandledByEngine = sharedHttpTransportHandlesFailures }
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 8_000
            }
        }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var token: Pair<String, Long>? = null
    private var anonymousSpotifySession = SpotifySession()
    private val cache = LinkedHashMap<List<Any?>, Pair<DirectCanvasUrls, Long>>(32, 0.75f, true)

    suspend fun getSpotifyCanvas(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        primaryArtist: String? = null,
    ): String? = getCanvasesInternal(title, artist, duration, album, primaryArtist, includeAppleMusic = false).spotify

    suspend fun getCanvases(
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        primaryArtist: String? = null,
    ): DirectCanvasUrls = getCanvasesInternal(title, artist, duration, album, primaryArtist, includeAppleMusic = true)

    private suspend fun getCanvasesInternal(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        primaryArtist: String?,
        includeAppleMusic: Boolean,
    ): DirectCanvasUrls {
        if (title.isBlank() || artist.isBlank()) return DirectCanvasUrls()
        val login = session()
        val storefront = country().lowercase(Locale.ROOT)
        val lang = language()
        val key = listOf(title, artist, primaryArtist, duration, album, storefront, lang, login.isNotBlank(), includeAppleMusic)
        // shortcut: One lock serializes cold lookups; use per-key jobs if artwork throughput becomes a problem.
        return mutex.withLock {
            cache[key]?.takeIf { it.second > now() }?.let { return@withLock it.first }
            val result =
                coroutineScope {
                    val spotify = async { attempt { spotify(title, artist, primaryArtist, album, duration, login) } }
                    val apple =
                        async {
                            if (includeAppleMusic) {
                                attempt { apple(title, artist, primaryArtist, album, duration, storefront, lang) }
                            } else {
                                null
                            }
                        }
                    DirectCanvasUrls(spotify = spotify.await(), appleMusic = apple.await())
                }
            cache[key] = result to (now() + if (result.spotify == null && result.appleMusic == null) 60_000 else 300_000)
            while (cache.size > 32) cache.remove(cache.keys.first())
            result
        }
    }

    private suspend fun attempt(block: suspend () -> String?): String? =
        try {
            withTimeoutOrNull(8_000) { block() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            YtLogger.w("Canvas", "Artwork lookup failed (${error::class.simpleName})")
            null
        }

    private suspend fun spotify(
        title: String,
        artist: String,
        primaryArtist: String?,
        album: String?,
        duration: Long?,
        login: String,
    ): String? {
        val stored = if (login.isBlank()) anonymousSpotifySession else json.decodeFromString<SpotifySession>(login)
        SpotifyClient
            .create(SpotifyConfig(engine = http.engine, logLevel = LogLevel.NONE, allowAnonymous = !stored.hasLoginCookie))
            .use { client ->
                client.attachSession(stored)
                try {
                    return spotifyCanvas(client, title, artist, primaryArtist, album, duration)
                } finally {
                    if (!stored.hasLoginCookie) {
                        anonymousSpotifySession = client.session
                    } else if (client.session != stored && session() == login) {
                        saveSession(json.encodeToString(client.session))
                    }
                }
            }
    }

    private suspend fun spotifyCanvas(
        client: SpotifyClient,
        title: String,
        artist: String,
        primaryArtist: String?,
        album: String?,
        duration: Long?,
    ): String? {
        val variables =
            buildJsonObject {
                put("query", "$title $artist")
                put("limit", 10)
                put("offset", 0)
                put("numberOfTopResults", 10)
                put("includeArtistHasConcertsField", false)
                put("includeAudiobooks", true)
                put("includeAuthors", false)
                put("includePreReleases", true)
                put("includeAlbumPreReleases", false)
                put("includeEpisodeContentRatingsV2", true)
                put(
                    "sectionFilters",
                    buildJsonArray {
                        add("GENERIC")
                        add("VIDEO_CONTENT")
                    },
                )
            }
        val search = client.canvasQuery("searchTopResultsList", SPOTIFY_SEARCH_HASH, variables)
        val items =
            search
                .obj("data")
                ?.obj("searchV2")
                ?.obj("topResultsV2")
                ?.array("itemsV2")
                .orEmpty()
        for (item in items.take(10)) {
            val track = (item as? JsonObject)?.obj("item")?.obj("data") ?: continue
            if (track.text("__typename") != "Track") continue
            val id = track.text("id") ?: continue
            val artistNames =
                track
                    .obj("artists")
                    ?.array("items")
                    .orEmpty()
                    .mapNotNull {
                        (it as? JsonObject)?.obj("profile")?.text("name")
                    }
            val artists = artistNames.joinToString(", ")
            if (!matches(
                    title,
                    artist,
                    album,
                    duration,
                    track.text("name"),
                    artists,
                    track.obj("albumOfTrack")?.text("name"),
                    track.obj("duration")?.text("totalMilliseconds")?.toLongOrNull(),
                    primaryArtist,
                    artistNames.firstOrNull(),
                )
            ) {
                continue
            }
            val canvas =
                if (client.session.hasLoginCookie) {
                    client
                        .canvasQuery("canvas", SPOTIFY_CANVAS_HASH, buildJsonObject { put("trackUri", "spotify:track:$id") })
                        .obj("data")
                        ?.obj("trackUnion")
                        ?.obj("canvas")
                        ?.text("url")
                } else {
                    publicSpotifyCanvas(id)
                }
            if (trustedMedia(canvas, false)) {
                YtLogger.d("Canvas", "Resolved Spotify Canvas")
                return canvas
            }
        }
        return null
    }

    private suspend fun publicSpotifyCanvas(trackId: String): String? {
        if (!trackId.matches(Regex("[A-Za-z0-9]{22}"))) return null
        val response =
            http.prepareGet("https://spotify-canva.vercel.app/api/canvas") { parameter("trackId", trackId) }.readTextLimited(256 * 1024)
        if (!response.status.isSuccess()) return null
        val canvases = (response.body?.let { json.parseToJsonElement(it) } as? JsonObject)?.array("canvasesList") ?: return null
        return canvases
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.text("trackUri") == "spotify:track:$trackId" && trustedMedia(it.text("canvasUrl"), false) }
            ?.text("canvasUrl")
    }

    private suspend fun apple(
        title: String,
        artist: String,
        primaryArtist: String?,
        album: String?,
        duration: Long?,
        storefront: String,
        lang: String,
    ): String? {
        if (!storefront.matches(Regex("[a-z]{2}")) || lang.isBlank()) return null
        val auth = developerToken(storefront) ?: return null
        val search =
            appleJson("https://amp-api.music.apple.com/v1/catalog/$storefront/search", auth, storefront) {
                parameter("types", "songs")
                parameter("term", "$title $artist")
                parameter("limit", 10)
                parameter("l", lang)
            } ?: return null
        val songs =
            search
                .obj("results")
                ?.obj("songs")
                ?.array("data")
                .orEmpty()
        for (song in songs.take(10)) {
            val item = song as? JsonObject ?: continue
            val id = item.text("id")?.takeIf { it.matches(Regex("[0-9]+")) } ?: continue
            val attrs = item.obj("attributes") ?: continue
            if (!matches(
                    title,
                    artist,
                    album,
                    duration,
                    attrs.text("name"),
                    attrs.text("artistName"),
                    attrs.text("albumName"),
                    attrs.text("durationInMillis")?.toLongOrNull(),
                    primaryArtist,
                    attrs.text("artistName"),
                )
            ) {
                continue
            }
            val detail =
                appleJson("https://amp-api.music.apple.com/v1/catalog/$storefront/songs/$id", token?.first ?: auth, storefront) {
                    parameter("include", "albums")
                    parameter("extend", "editorialVideo")
                    parameter("l", lang)
                } ?: continue
            for (record in detail.array("data").orEmpty()) {
                val albums =
                    (record as? JsonObject)
                        ?.obj("relationships")
                        ?.obj("albums")
                        ?.array("data")
                        .orEmpty()
                for (entry in albums) {
                    val videos = (entry as? JsonObject)?.obj("attributes")?.obj("editorialVideo") ?: continue
                    for (kind in listOf("motionSquareVideo1x1", "motionDetailSquare", "motionTallVideo3x4", "motionDetailTall")) {
                        val url = videos.obj(kind)?.text("video")
                        if (trustedMedia(url, true)) return url
                    }
                }
            }
        }
        return null
    }

    private suspend fun appleJson(
        url: String,
        auth: String,
        storefront: String,
        params: HttpRequestBuilder.() -> Unit,
    ): JsonObject? {
        var authorization = auth
        repeat(2) { attempt ->
            val response =
                http
                    .prepareGet(url) {
                        if (attempt == 0) {
                            attributes.put(ExpectedHttpFailureStatusCodes, setOf(HttpStatusCode.Unauthorized.value))
                        }
                        header("Authorization", "Bearer $authorization")
                        header("Origin", "https://music.apple.com")
                        header("User-Agent", USER_AGENT)
                        params()
                    }.readTextLimited(256 * 1024)
            if (response.status.value == 401 && attempt == 0) {
                token = null
                authorization = developerToken(storefront) ?: return null
            } else {
                if (!response.status.isSuccess()) return null
                return response.body?.let { json.parseToJsonElement(it) as? JsonObject }
            }
        }
        return null
    }

    private suspend fun developerToken(storefront: String): String? {
        token?.takeIf { it.second > now() + 300_000 }?.let { return it.first }
        var pageUrl = "https://music.apple.com/$storefront/browse"
        var home = http.prepareGet(pageUrl) { header("User-Agent", USER_AGENT) }.readTextLimited(4 * 1024 * 1024)
        var redirects = 0
        while (home.status.value in 300..399 && redirects++ < 2) {
            val location = home.headers[HttpHeaders.Location] ?: return null
            val next = runCatching { URI(pageUrl).resolve(location).toString() }.getOrNull() ?: return null
            if (!trustedHost(next, setOf("music.apple.com")) || Url(next).host != "music.apple.com") return null
            pageUrl = next
            home = http.prepareGet(pageUrl) { header("User-Agent", USER_AGENT) }.readTextLimited(4 * 1024 * 1024)
        }
        if (!home.status.isSuccess()) return null
        val html = home.body ?: return null
        val script =
            Regex("""<script type="module" crossorigin src="([^"]+\.js)"""").find(html)?.groupValues?.get(1)
                ?: Regex("""/assets/index[^"'\s]*\.js""").find(html)?.value ?: return null
        val scriptUrl = if (script.startsWith('/')) "https://music.apple.com$script" else script
        if (!trustedHost(scriptUrl, setOf("music.apple.com"))) return null
        val bundle = http.prepareGet(scriptUrl) { header("User-Agent", USER_AGENT) }.readTextLimited(16 * 1024 * 1024)
        if (!bundle.status.isSuccess()) return null
        val js = bundle.body ?: return null
        val candidate =
            Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}""")
                .findAll(js)
                .mapNotNull { match ->
                    val payload =
                        runCatching {
                            val part = match.value.split('.')[1]
                            json.parseToJsonElement(String(Base64.getUrlDecoder().decode(part))).jsonObject
                        }.getOrNull() ?: return@mapNotNull null
                    val expiry = payload.text("exp")?.toLongOrNull()?.times(1_000) ?: return@mapNotNull null
                    match.value.takeIf { payload.text("iss") == "AMPWebPlay" && expiry > now() + 300_000 }?.let { it to expiry }
                }.maxByOrNull { it.second }
        token = candidate
        return candidate?.first
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val SPOTIFY_SEARCH_HASH = "12d940b3f7381506a82ff5382eb59b0ed65118656e6bcdfbe4cebc528f75d29d"
        private const val SPOTIFY_CANVAS_HASH = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"

        internal fun trustedMedia(
            url: String?,
            apple: Boolean,
        ): Boolean =
            url != null &&
                trustedHost(
                    url,
                    if (apple) setOf("itunes.apple.com", "apple.com", "mzstatic.com", "akamaized.net") else setOf("scdn.co"),
                )

        private fun trustedHost(
            url: String,
            domains: Set<String>,
        ): Boolean {
            val parsed = runCatching { Url(url) }.getOrNull() ?: return false
            return parsed.protocol == URLProtocol.HTTPS && parsed.user == null && parsed.password == null && parsed.port == 443 &&
                domains.any { parsed.host == it || parsed.host.endsWith(".$it") }
        }

        internal fun matches(
            title: String,
            artist: String,
            album: String?,
            duration: Long?,
            foundTitle: String?,
            foundArtist: String?,
            foundAlbum: String?,
            foundDuration: Long?,
            primaryArtist: String? = null,
            foundPrimaryArtist: String? = null,
        ): Boolean {
            fun words(value: String?): Set<String> =
                Normalizer
                    .normalize(value.orEmpty(), Normalizer.Form.NFKD)
                    .replace(Regex("\\p{M}+"), "")
                    .lowercase(Locale.ROOT)
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.isNotBlank() && it !in setOf("feat", "ft", "featuring", "official", "video", "audio") }
                    .toSet()

            fun similarity(
                left: String?,
                right: String?,
            ): Double {
                val a = words(left)
                val b = words(right)
                return if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / maxOf(a.size, b.size)
            }
            val requestedArtist = words(artist)
            val actualArtist = words(foundArtist)
            val requestedPrimary = words(primaryArtist)
            val actualPrimary = words(foundPrimaryArtist)
            val completeMatch = requestedArtist == actualArtist
            val primaryMatch = requestedPrimary.isNotEmpty() && requestedPrimary == actualPrimary
            if (requestedArtist.isEmpty() || !(completeMatch || primaryMatch)) return false
            return similarity(title, foundTitle) >= 0.75 &&
                (duration == null || duration <= 0 || foundDuration == null || kotlin.math.abs(duration - foundDuration) <= 10_000) &&
                (album.isNullOrBlank() || foundAlbum.isNullOrBlank() || similarity(album, foundAlbum) >= 0.5)
        }
    }
}

data class DirectCanvasUrls(
    val spotify: String? = null,
    val appleMusic: String? = null,
)

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content
