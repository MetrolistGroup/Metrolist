package com.metrolist.shared.api.lastfm

import com.metrolist.shared.api.ScrobbleApiException
import com.metrolist.shared.api.ScrobbleFailureDisposition
import com.metrolist.shared.api.parseRetryAfter
import com.metrolist.shared.api.scrobbleStatusDisposition
import com.metrolist.shared.network.readTextLimited
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.utils.md5Hex
import com.metrolist.shared.youtube.logging.YtLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class LastFmLoginFailure { INVALID_CREDENTIALS, CONNECTION, SERVICE }

internal fun Throwable.lastFmLoginFailure(): LastFmLoginFailure =
    when {
        this is ScrobbleApiException && apiErrorCode == 4 -> LastFmLoginFailure.INVALID_CREDENTIALS
        this is IOException || this is HttpRequestTimeoutException -> LastFmLoginFailure.CONNECTION
        else -> LastFmLoginFailure.SERVICE
    }

@Serializable
data class LastFmSession(
    val name: String = "",
    val key: String = "",
    val subscriber: Int = 0,
)

@Serializable
data class LastFmAuthResponse(
    val session: LastFmSession? = null,
    val error: Int? = null,
    val message: String? = null,
)

@Serializable
data class LastFmUserImage(
    @SerialName("#text") val url: String = "",
)

@Serializable
data class LastFmUserInfo(
    val name: String = "",
    val image: List<LastFmUserImage> = emptyList(),
)

@Serializable
private data class LastFmUserInfoResponse(
    val user: LastFmUserInfo = LastFmUserInfo(),
)

@Serializable
data class LastFmRankAttr(
    val rank: String = "",
)

@Serializable
data class LastFmTopTrack(
    val name: String = "",
    val playcount: String? = null,
    val listeners: String? = null,
    val url: String = "",
    val image: List<LastFmUserImage> = emptyList(),
    val artist: LastFmTrackArtist? = null,
    @SerialName("@attr")
    val attr: LastFmRankAttr? = null,
)

@Serializable
data class LastFmTrackArtist(
    val name: String = "",
)

@Serializable
data class LastFmFriend(
    val name: String = "",
    val realname: String = "",
    val url: String = "",
    val image: List<LastFmUserImage> = emptyList(),
)

@Serializable
data class LastFmRecentTrack(
    val name: String = "",
    val url: String = "",
    val artist: JsonElement? = null,
    val image: List<LastFmUserImage> = emptyList(),
    val album: JsonElement? = null,
    val date: LastFmTrackDate? = null,
    @SerialName("@attr")
    val attr: LastFmNowPlayingAttr? = null,
)

@Serializable
data class LastFmTrackDate(
    val uts: String = "",
)

@Serializable
data class LastFmNowPlayingAttr(
    val nowplaying: String = "",
)

fun LastFmRecentTrack.artistName(): String? =
    when (val artistJson = artist) {
        is JsonObject -> artistJson["#text"]?.jsonPrimitive?.contentOrNull
        is JsonPrimitive -> artistJson.contentOrNull
        else -> null
    }

fun List<LastFmUserImage>.largestAvatar(): String? =
    lastOrNull { !it.url.contains(LASTFM_PLACEHOLDER_HASH) }
        ?.url
        ?.takeIf { it.isNotBlank() }

fun List<LastFmUserImage>.largest(): String? =
    lastOrNull { !it.url.contains(LASTFM_PLACEHOLDER_HASH) }
        ?.url
        ?.takeIf { it.isNotBlank() }
        ?.let { toFullResolution(it) }

const val LASTFM_PLACEHOLDER_HASH = "2a96cbd8b46e442fc41c2b86b821562f"

fun toFullResolution(url: String): String = url.replace(Regex("i/u/[a-zA-Z0-9]+/"), "i/u/ar0/")

object LastFM {
    private const val MAX_SCRAPED_HTML_BYTES = 2 * 1_024 * 1_024
    private const val TOP_TITLES_LIMIT = 50
    private const val MIN_SHARED_TITLES = 2
    private const val MAX_SCRAPED_MATCHES = 100
    private const val TAG = "LastFM"
    private var apiKey: String = ""
    private var secret: String = ""
    var sessionKey: String? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

    fun initialize(
        apiKey: String,
        secret: String,
    ) {
        if (apiKey.isNotBlank()) this.apiKey = apiKey
        if (secret.isNotBlank()) this.secret = secret
    }

    fun apiKeyConfigured(): Boolean = apiKey.isNotBlank()

    suspend fun getUserInfo(
        httpClient: HttpClient,
        username: String,
        apiKey: String,
    ): Result<LastFmUserInfo> =
        catchingApi {
            val response =
                httpClient.get("https://ws.audioscrobbler.com/2.0/") {
                    parameter("method", "user.getinfo")
                    parameter("user", username)
                    parameter("api_key", apiKey)
                    parameter("format", "json")
                }
            val payload = validateResponse(response.status.value, response.bodyAsText(), response.headers[HttpHeaders.RetryAfter])
            json.decodeFromJsonElement<LastFmUserInfoResponse>(payload).user
        }

    private fun generateSignature(params: Map<String, String>): String {
        val signature = params.entries.sortedBy { it.key }.joinToString("") { it.key + it.value } + secret
        return md5Hex(signature)
    }

    suspend fun getMobileSession(
        httpClient: HttpClient,
        username: String,
        password: String,
    ): Result<LastFmAuthResponse> =
        catchingApi {
            if (apiKey.isBlank() || secret.isBlank()) {
                throw ScrobbleApiException(
                    "Last.fm API key or secret is missing. Please configure LASTFM_API_KEY and LASTFM_SECRET in local.properties or gradle.properties.",
                    ScrobbleFailureDisposition.PERMANENT,
                )
            }
            val params =
                mutableMapOf(
                    "method" to "auth.getMobileSession",
                    "username" to username,
                    "password" to password,
                    "api_key" to apiKey,
                )
            val apiSig = generateSignature(params)

            val response =
                httpClient.submitForm(
                    url = "https://ws.audioscrobbler.com/2.0/",
                    formParameters =
                        Parameters.build {
                            params.forEach { (k, v) -> append(k, v) }
                            append("api_sig", apiSig)
                            append("format", "json")
                        },
                )

            val jsonElement = validateResponse(response.status.value, response.bodyAsText())

            try {
                json.decodeFromJsonElement<LastFmAuthResponse>(jsonElement).also {
                    check(
                        it.session != null && it.session.key.isNotBlank() && it.session.name.isNotBlank(),
                    ) { "Last.fm returned an invalid session" }
                }
            } catch (e: Exception) {
                throw Exception("Failed to parse Last.fm success response: ${e.message}")
            }
        }

    suspend fun updateNowPlaying(
        httpClient: HttpClient,
        artist: String,
        track: String,
        album: String? = null,
        albumArtist: String? = null,
        duration: Long? = null,
        session: String? = sessionKey,
    ): Result<Unit> =
        catchingApi {
            val sk = session ?: throw ScrobbleApiException("No Last.fm session", ScrobbleFailureDisposition.AUTHENTICATION)
            val params =
                mutableMapOf(
                    "method" to "track.updateNowPlaying",
                    "artist" to artist,
                    "track" to track,
                    "api_key" to apiKey,
                    "sk" to sk,
                )
            album?.let { params["album"] = it }
            albumArtist?.let { params["albumArtist"] = it }
            duration?.let { params["duration"] = it.toString() }
            val apiSig = generateSignature(params)

            signedPost(httpClient, params, apiSig)
        }

    suspend fun scrobble(
        httpClient: HttpClient,
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        albumArtist: String? = null,
        duration: Long? = null,
        session: String? = sessionKey,
    ): Result<Unit> =
        catchingApi {
            val sk = session ?: throw ScrobbleApiException("No Last.fm session", ScrobbleFailureDisposition.AUTHENTICATION)
            val params =
                mutableMapOf(
                    "method" to "track.scrobble",
                    "artist" to artist,
                    "track" to track,
                    "timestamp" to timestamp.toString(),
                    "api_key" to apiKey,
                    "sk" to sk,
                    "chosenByUser" to "1",
                )
            album?.let { params["album"] = it }
            albumArtist?.let { params["albumArtist"] = it }
            duration?.let { params["duration"] = it.toString() }
            val apiSig = generateSignature(params)

            val response = signedPost(httpClient, params, apiSig)
            val accepted =
                response["scrobbles"]
                    ?.jsonObject
                    ?.get("@attr")
                    ?.jsonObject
                    ?.get("accepted")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()
            if (accepted == null || accepted <= 0) {
                throw ScrobbleApiException("Last.fm did not accept the scrobble", retryable = false)
            }
        }

    suspend fun love(
        httpClient: HttpClient,
        artist: String,
        track: String,
        love: Boolean = true,
        session: String? = sessionKey,
    ): Result<Unit> =
        catchingApi {
            val sk = session ?: throw ScrobbleApiException("No Last.fm session", ScrobbleFailureDisposition.AUTHENTICATION)
            val params =
                mutableMapOf(
                    "method" to if (love) "track.love" else "track.unlove",
                    "artist" to artist,
                    "track" to track,
                    "api_key" to apiKey,
                    "sk" to sk,
                )
            val apiSig = generateSignature(params)

            signedPost(httpClient, params, apiSig)
        }

    private suspend fun signedPost(
        httpClient: HttpClient,
        params: Map<String, String>,
        apiSig: String,
    ): JsonObject {
        if (apiKey.isBlank() || secret.isBlank()) {
            throw ScrobbleApiException(
                "Last.fm API key or secret is missing. Please configure LASTFM_API_KEY and LASTFM_SECRET in local.properties or gradle.properties.",
                ScrobbleFailureDisposition.PERMANENT,
            )
        }
        val response =
            httpClient.submitForm(
                url = "https://ws.audioscrobbler.com/2.0/",
                formParameters =
                    Parameters.build {
                        params.forEach { (key, value) -> append(key, value) }
                        append("api_sig", apiSig)
                        append("format", "json")
                    },
            )
        return validateResponse(
            response.status.value,
            response.bodyAsText(),
            response.headers[io.ktor.http.HttpHeaders.RetryAfter],
        ).jsonObject
    }

    private fun validateResponse(
        statusCode: Int,
        body: String,
        retryAfter: String? = null,
    ) = runCatching { json.parseToJsonElement(body) }
        .getOrElse {
            throw ScrobbleApiException(
                "Last.fm returned an invalid response (HTTP $statusCode)",
                disposition = scrobbleStatusDisposition(statusCode),
                statusCode = statusCode,
                retryAfterMs = parseRetryAfter(retryAfter),
            )
        }.also { element ->
            if (element is JsonObject && element.containsKey("error")) {
                val message = element["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown Last.fm error"
                val code = element["error"]?.jsonPrimitive?.intOrNull ?: 0
                val disposition =
                    when (code) {
                        4, 9, 14 -> ScrobbleFailureDisposition.AUTHENTICATION
                        10 -> ScrobbleFailureDisposition.PERMANENT
                        11, 16, 29 -> ScrobbleFailureDisposition.RETRY
                        else -> ScrobbleFailureDisposition.PERMANENT
                    }
                throw ScrobbleApiException(
                    "Last.fm error ($code): $message",
                    disposition,
                    statusCode,
                    parseRetryAfter(retryAfter),
                    apiErrorCode = code,
                )
            }
            if (statusCode !in 200..299) {
                throw ScrobbleApiException(
                    "Last.fm request failed (HTTP $statusCode)",
                    disposition = scrobbleStatusDisposition(statusCode),
                    statusCode = statusCode,
                    retryAfterMs = parseRetryAfter(retryAfter),
                )
            }
        }

    private suspend inline fun <T> catchingApi(crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

    suspend fun getRecentTracks(
        httpClient: HttpClient,
        user: String,
        limit: Int = 50,
    ): Result<JsonObject> =
        runCatching {
            val response =
                httpClient.get("https://ws.audioscrobbler.com/2.0/") {
                    parameter("method", "user.getRecentTracks")
                    parameter("user", user)
                    parameter("api_key", apiKey)
                    parameter("limit", limit)
                    parameter("format", "json")
                    parameter("extended", 1)
                }
            val body = response.bodyAsText()
            json.parseToJsonElement(body).jsonObject["recenttracks"]?.jsonObject
                ?: throw Exception("No recent tracks found")
        }

    suspend fun getRecentTrack(
        httpClient: HttpClient,
        user: String,
    ): Result<LastFmRecentTrack?> =
        catchingApi {
            val response =
                httpClient.get("https://ws.audioscrobbler.com/2.0/") {
                    parameter("method", "user.getRecentTracks")
                    parameter("user", user)
                    parameter("api_key", apiKey)
                    parameter("limit", 1)
                    parameter("format", "json")
                }
            val payload = validateResponse(response.status.value, response.bodyAsText())
            val trackJson =
                (payload.jsonObject["recenttracks"] as? JsonObject)?.get("track")
                    ?: return@catchingApi null
            val element =
                when (trackJson) {
                    is JsonArray -> trackJson.firstOrNull()
                    is JsonObject -> trackJson
                    else -> null
                } ?: return@catchingApi null
            json.decodeFromJsonElement<LastFmRecentTrack>(element)
        }

    suspend fun getFriends(
        httpClient: HttpClient,
        user: String,
        limit: Int = 50,
    ): Result<List<LastFmFriend>> =
        catchingApi {
            if (apiKey.isBlank()) {
                throw ScrobbleApiException(
                    "Last.fm API key is missing. Please configure LASTFM_API_KEY.",
                    ScrobbleFailureDisposition.PERMANENT,
                )
            }
            val response =
                httpClient.get("https://ws.audioscrobbler.com/2.0/") {
                    parameter("method", "user.getFriends")
                    parameter("user", user)
                    parameter("api_key", apiKey)
                    parameter("limit", limit)
                    parameter("format", "json")
                }
            val payload = validateResponse(response.status.value, response.bodyAsText())
            val friendsJson =
                payload.jsonObject["friends"]?.jsonObject
                    ?: throw ScrobbleApiException("Last.fm returned no friends data", retryable = false)
            val usersJson = friendsJson["user"] ?: return@catchingApi emptyList()
            val userElements =
                when (usersJson) {
                    is JsonArray -> usersJson.toList()
                    is JsonObject -> listOf(usersJson)
                    else -> emptyList()
                }
            userElements.mapNotNull { userJson ->
                runCatching { json.decodeFromJsonElement<LastFmFriend>(userJson) }.getOrNull()
            }
        }

    var renderedPageLoader: (suspend (String) -> String?)? = null

    /** The content language as Last.fm's ISO 639 alpha-2 code. */
    fun contentLanguage(): String = primaryLanguage(GlobalInnerTube.instance.locale.hl)

    internal fun primaryLanguage(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()

    suspend fun getArtistInfo(
        httpClient: HttpClient,
        artist: String,
        language: String = contentLanguage(),
    ): JsonObject? =
        try {
            val response =
                httpClient.prepareGet("https://ws.audioscrobbler.com/2.0/") {
                    headers.append(HttpHeaders.Accept, "application/json")
                    headers.append(HttpHeaders.AcceptLanguage, language)
                    parameter("method", "artist.getInfo")
                    parameter("autocorrect", 1)
                    parameter("artist", artist)
                    parameter("api_key", apiKey)
                    parameter("lang", language)
                    parameter("format", "json")
                }
            val limited = response.readTextLimited(MAX_SCRAPED_HTML_BYTES)
            if (limited.status.value !in 200..299) return null
            val body = limited.body ?: return null
            val parsed = json.parseToJsonElement(body).jsonObject
            if (parsed.containsKey("error")) {
                return null
            }
            parsed["artist"]?.jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    fun artistPageUrl(artist: String): String = "https://www.last.fm/music/${artist.trim().encodeURLParameter(spaceToPlus = true)}"

    suspend fun getArtistTopTitles(
        httpClient: HttpClient,
        artist: String,
        language: String = contentLanguage(),
    ): List<String>? {
        if (apiKey.isBlank()) return null
        val titles =
            listOf(
                Triple("artist.getTopTracks", "toptracks", "track"),
                Triple("artist.getTopAlbums", "topalbums", "album"),
            ).map { (method, root, item) ->
                try {
                    val response =
                        httpClient.prepareGet("https://ws.audioscrobbler.com/2.0/") {
                            headers.append(HttpHeaders.Accept, "application/json")
                            headers.append(HttpHeaders.AcceptLanguage, language)
                            parameter("method", method)
                            parameter("artist", artist)
                            parameter("limit", TOP_TITLES_LIMIT)
                            parameter("api_key", apiKey)
                            parameter("format", "json")
                        }
                    val limited = response.readTextLimited(MAX_SCRAPED_HTML_BYTES)
                    val body = limited.body?.takeIf { limited.status.value in 200..299 } ?: return@map null
                    json
                        .parseToJsonElement(body)
                        .jsonObject[root]
                        ?.jsonObject
                        ?.get(item)
                        ?.jsonArray
                        ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        return if (titles.all { it == null }) null else titles.flatMap { it.orEmpty() }
    }

    internal fun extractCatalogTitles(
        html: String,
        artist: String,
    ): List<String> =
        Regex("""href="/music/([^"/]+)/([^"/?#]+)(?:/([^"/?#]+))?"""")
            .findAll(html)
            .mapNotNull { match ->
                val (linkArtist, first, second) = match.destructured
                when {
                    normalizeCatalogTitle(linkArtist.decodeURLQueryComponent(plusIsSpace = true)) != normalizeCatalogTitle(artist) -> null
                    first == "_" && second.isNotEmpty() -> second
                    second.isEmpty() && !first.startsWith("+") && first != "_" -> first
                    else -> null
                }
            }.map { it.decodeURLQueryComponent(plusIsSpace = true) }
            .distinct()
            .take(MAX_SCRAPED_MATCHES)
            .toList()

    internal fun normalizeCatalogTitle(title: String): String =
        title
            .lowercase()
            .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
            .replace(Regex("""\s+-\s+.*$"""), "")
            .filter(Char::isLetterOrDigit)

    /** Last.fm merges same-name artists; require shared chart titles to verify the YouTube Music match. */
    fun catalogsMatch(
        youTubeTitles: Collection<String>,
        lastFmTitles: Collection<String>,
    ): Boolean {
        val youTube = youTubeTitles.mapTo(mutableSetOf(), ::normalizeCatalogTitle).apply { remove("") }
        val lastFm = lastFmTitles.mapTo(mutableSetOf(), ::normalizeCatalogTitle).apply { remove("") }
        val required = minOf(MIN_SHARED_TITLES, youTube.size, lastFm.size)
        return required > 0 && youTube.count { it in lastFm } >= required
    }

    data class MatchedArtist(
        val info: JsonObject?,
        val page: ScrapedArtistPage?,
    )

    suspend fun getMatchedArtist(
        httpClient: HttpClient,
        artist: String,
        youTubeTitles: Collection<String>,
        language: String = contentLanguage(),
    ): MatchedArtist? {
        val info = getArtistInfo(httpClient, artist, language)
        val apiTitles = if (info != null) getArtistTopTitles(httpClient, artist, language) else null
        val page = if (apiTitles == null) scrapeArtistPage(httpClient, artist, language) else null
        val lastFmTitles = apiTitles ?: page?.topTitles.orEmpty()
        return MatchedArtist(info, page).takeIf { catalogsMatch(youTubeTitles, lastFmTitles) }
    }

    suspend fun getArtistPhotos(
        httpClient: HttpClient,
        artist: String,
        language: String = contentLanguage(),
        renderPage: (suspend (String) -> String?)? = renderedPageLoader,
    ): List<String> =
        try {
            val html = fetchArtistHtml(httpClient, "${artistPageUrl(artist)}/+images", language, renderPage).orEmpty()
            extractGalleryPhotos(html).ifEmpty {
                extractGalleryPhotos(fetchArtistHtml(httpClient, artistPageUrl(artist), language, renderPage).orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    fun photoPageUrl(
        artist: String,
        imageUrl: String,
    ): String? =
        Regex("""/i/u/[^/]+/([0-9a-f]{32})""")
            .find(imageUrl)
            ?.let { "${artistPageUrl(artist)}/+images/${it.groupValues[1]}" }

    suspend fun getPhotoDetails(
        httpClient: HttpClient,
        pageUrl: String,
        renderPage: (suspend (String) -> String?)? = renderedPageLoader,
    ): LastFmPhotoDetails? {
        if (!Regex("""https://www\.last\.fm/music/[^/?#]+/\+images/[0-9a-f]{32}""").matches(pageUrl)) return null
        return try {
            val response = httpClient.prepareGet(pageUrl).readTextLimited(MAX_SCRAPED_HTML_BYTES)
            if (response.status.value == 429 || response.status.value == 503) {
                throw LastFmPhotoBackoff(parseRetryAfter(response.headers[HttpHeaders.RetryAfter]) ?: 60_000)
            }
            val fetched = response.body.orEmpty()
            if (response.status.value !in 200..299 && !isClientChallenge(fetched)) return null
            val html = if (isClientChallenge(fetched)) renderPage?.invoke(pageUrl).orEmpty() else fetched
            val details = parsePhotoDetails(html) ?: return null
            val username = details.authorProfileUrl?.substringAfterLast('/')?.decodeURLQueryComponent() ?: return details
            val avatar =
                if (apiKey.isNotBlank()) {
                    getUserInfo(httpClient, username, apiKey)
                        .onFailure { error ->
                            if (error is ScrobbleApiException &&
                                (error.statusCode == 429 || error.statusCode == 503 || error.apiErrorCode == 29)
                            ) {
                                throw LastFmPhotoBackoff(error.retryAfterMs ?: 60_000, details)
                            }
                        }.getOrNull()
                        ?.image
                        ?.lastOrNull { it.url.isNotBlank() && !it.url.contains(LASTFM_PLACEHOLDER_HASH) }
                        ?.url
                } else {
                    null
                }
            details.copy(authorAvatarUrl = avatar)
        } catch (e: LastFmPhotoBackoff) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun isClientChallenge(html: String): Boolean =
        html.contains("Client Challenge", ignoreCase = true) ||
            html.contains("cf-chl-", ignoreCase = true) ||
            html.contains("Rate Limited", ignoreCase = true)

    private suspend fun fetchArtistHtml(
        httpClient: HttpClient,
        url: String,
        language: String,
        renderPage: (suspend (String) -> String?)? = null,
    ): String? {
        val response =
            httpClient
                .prepareGet(url) {
                    headers.append(HttpHeaders.UserAgent, "Mozilla/5.0 (X11; Linux x86_64; rv:109.0) Gecko/20100101 Firefox/115.0")
                    headers.append(HttpHeaders.Accept, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    headers.append(HttpHeaders.AcceptLanguage, language)
                }.readTextLimited(MAX_SCRAPED_HTML_BYTES)
        val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
        if (type != null && type != "text/html" && type != "application/xhtml+xml") return null
        val html = response.body ?: return null
        if (isClientChallenge(html)) {
            return renderPage
                ?.invoke(
                    url,
                )?.takeIf { it.encodeToByteArray().size <= MAX_SCRAPED_HTML_BYTES }
                ?.takeUnless(::isClientChallenge)
        }
        if (response.status.value !in 200..299) return null
        return html
    }

    internal fun extractGalleryPhotos(html: String): List<String> {
        val gallery =
            Regex(
                """<li[^>]*class="[^"]*image-list-item[^"]*"[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*>""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(html)
                .map { it.groupValues[1] }
                .toList()
        return gallery
            .ifEmpty {
                Regex("""https://lastfm(?:-img)?\.freetls\.fastly\.net/i/u/[a-zA-Z0-9]+/[a-zA-Z0-9]+\.(?:jpg|png|webp|jpeg)""")
                    .findAll(html)
                    .take(5)
                    .map { it.value }
                    .toList()
            }.asSequence()
            .filter { url ->
                Regex(
                    """https://lastfm(?:-img)?\.freetls\.fastly\.net/i/u/[a-zA-Z0-9]+/[a-zA-Z0-9]+(?:\.(?:jpg|png|webp|jpeg))?""",
                ).matches(url) &&
                    !url.contains(LASTFM_PLACEHOLDER_HASH)
            }.map(::toFullResolution)
            .distinct()
            .take(20)
            .toList()
    }

    data class ScrapedArtistPage(
        val bio: String? = null,
        val tags: List<String> = emptyList(),
        val listeners: String? = null,
        val scrobbles: String? = null,
        val avatarUrl: String? = null,
        val topTitles: List<String> = emptyList(),
        val language: String? = null,
    )

    data class ArtistAudience(
        val genres: List<String> = emptyList(),
        val listeners: String? = null,
        val scrobbles: String? = null,
        val similarArtists: List<String> = emptyList(),
    )

    fun parseArtistAudience(
        info: JsonObject?,
        scraped: ScrapedArtistPage? = null,
    ): ArtistAudience {
        fun names(
            group: String,
            key: String,
        ): List<String> {
            val entries = (info?.get(group) as? JsonObject)?.get(key)
            val objects =
                when (entries) {
                    is JsonArray -> entries
                    is JsonObject -> listOf(entries)
                    else -> emptyList()
                }
            return objects
                .mapNotNull {
                    ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                }.distinct()
        }
        val stats = info?.get("stats") as? JsonObject

        fun count(key: String) = (stats?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { (it.toLongOrNull() ?: -1) >= 0 }
        return ArtistAudience(
            genres = names("tags", "tag").ifEmpty { scraped?.tags.orEmpty() },
            listeners = count("listeners") ?: scraped?.listeners,
            scrobbles = count("playcount") ?: scraped?.scrobbles,
            similarArtists = names("similar", "artist"),
        )
    }

    fun parseArtistImage(info: JsonObject?): String? =
        info
            ?.get("image")
            ?.jsonArray
            ?.mapNotNull { it.jsonObject["#text"]?.jsonPrimitive?.contentOrNull }
            ?.lastOrNull { it.isNotBlank() && !it.contains(LASTFM_PLACEHOLDER_HASH) }
            ?.let(::toFullResolution)

    fun parseArtistBio(
        info: JsonObject?,
        scraped: ScrapedArtistPage? = null,
        language: String = contentLanguage(),
    ): String? =
        ((info?.get("bio") as? JsonObject)?.get("summary") as? JsonPrimitive)
            ?.contentOrNull
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace(Regex("\\s*Read more on Last\\.fm\\s*$"), "")
            ?.trim()
            ?.ifBlank { null }
            ?: scraped?.takeIf { it.language == language }?.bio

    suspend fun scrapeArtistPage(
        httpClient: HttpClient,
        artist: String,
        language: String = contentLanguage(),
    ): ScrapedArtistPage =
        try {
            val url = artistPageUrl(artist)
            val html = fetchArtistHtml(httpClient, url, language, renderedPageLoader) ?: return ScrapedArtistPage()

            val bio =
                html
                    .let { page ->
                        Regex("""truncate-4-lines[^>]*>(.*?)</(?:p|div)""", RegexOption.DOT_MATCHES_ALL)
                            .find(page)
                            ?.groupValues
                            ?.get(1)
                    }?.let { raw ->
                        raw
                            .replace(Regex("<[^>]+>"), "")
                            .replace(Regex("\\s*read more\\s*$", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("\\s*…\\s*$"), "")
                            .replace("&amp;", "&")
                            .replace("&#x27;", "'")
                            .replace("&quot;", "\"")
                            .trim()
                            .ifBlank { null }
                    }
            val metadataItems =
                Regex(
                    """header-metadata-tnew-title[^>]*>\s*(\w+)\s*</h4.*?intabbr[^"]*"\s*title="([^"]+)""",
                    RegexOption.DOT_MATCHES_ALL,
                ).findAll(html).take(MAX_SCRAPED_MATCHES).associate { match ->
                    match.groupValues[1].lowercase() to match.groupValues[2]
                }
            val listeners = metadataItems["listeners"]
            val scrobbles = metadataItems["scrobbles"]

            val tags =
                Regex("""href="/tag/([^"]+)"""")
                    .findAll(html)
                    .map { it.groupValues[1].replace("+", " ") }
                    .distinct()
                    .take(MAX_SCRAPED_MATCHES)
                    .toList()

            val avatar =
                Regex("""<img[^>]*src="([^"]*avatar[^"]+)" """)
                    .find(html)
                    ?.groupValues
                    ?.get(1)
                    ?.replace(Regex("""\d+s/"""), "300x300/")

            ScrapedArtistPage(
                language =
                    Regex("""<html\b[^>]*\blang=["']([a-zA-Z_-]+)""")
                        .find(html)
                        ?.groupValues
                        ?.get(1)
                        ?.let(::primaryLanguage),
                bio = bio,
                tags = tags,
                listeners = listeners,
                scrobbles = scrobbles,
                avatarUrl = avatar,
                topTitles = extractCatalogTitles(html, artist),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ScrapedArtistPage()
        }

    suspend fun getTopTracks(
        httpClient: HttpClient,
        user: String,
        period: String = "7day",
        limit: Int = 10,
    ): Result<List<LastFmTopTrack>> =
        runCatching {
            val response =
                httpClient.get("https://ws.audioscrobbler.com/2.0/") {
                    parameter("method", "user.getTopTracks")
                    parameter("user", user)
                    parameter("api_key", apiKey)
                    parameter("period", period)
                    parameter("limit", limit)
                    parameter("format", "json")
                }
            val payload = validateResponse(response.status.value, response.bodyAsText())
            json.decodeFromJsonElement<List<LastFmTopTrack>>(
                payload.jsonObject["toptracks"]!!.jsonObject["track"]!!,
            )
        }
}
