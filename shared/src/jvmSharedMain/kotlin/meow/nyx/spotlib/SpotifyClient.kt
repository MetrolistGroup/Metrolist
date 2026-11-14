package meow.nyx.spotlib

import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.network.TextResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private const val PAGE_SIZE = 50
private val OPEN_HOST = Url("https://open.spotify.com/")

class SpotifyClient internal constructor(
    private val config: SpotifyConfig,
    private val http: HttpClient,
    internal val jar: SpotifyCookieJar,
    private val auth: AuthHttp,
    internal val api: ApiHttp,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val authMutex = Mutex()
    private var currentSession = SpotifySession()

    companion object {
        fun create(config: SpotifyConfig): SpotifyClient {
            val http = SpotifyHttp.create(config)
            val jar = SpotifyCookieJar()
            val api = ApiHttp(http, jar)
            return SpotifyClient(config, http, jar, AuthHttp(api, config, jar), api)
        }
    }

    val session: SpotifySession get() = currentSession

    fun attachSession(session: SpotifySession): SpotifyClient {
        currentSession = session
        jar.clear()
        for ((name, value) in session.cookies) jar.store(OPEN_HOST, name, value)
        return this
    }

    suspend fun loginWithSession(spDc: String): SpotifySession = loginWithSession(mapOf("sp_dc" to spDc))

    suspend fun loginWithSession(cookies: Map<String, String>): SpotifySession {
        attachSession(SpotifySession(cookies = cookies))
        ensureAuth()
        return currentSession
    }

    suspend fun ensureAuth() {
        authMutex.withLock {
            if (!currentSession.accessTokenValid) {
                val token = auth.fetchAccessToken()
                currentSession =
                    currentSession.copy(
                        accessToken = token.accessToken,
                        accessTokenExpiresAtMs = token.accessTokenExpirationTimestampMs,
                    )
            }
            if (!currentSession.clientTokenValid) {
                currentSession =
                    currentSession.copy(
                        clientToken = auth.fetchClientToken(),
                        clientTokenFetchedAtMs = System.currentTimeMillis(),
                    )
            }
        }
    }

    suspend fun canvasQuery(
        operation: String,
        hash: String,
        variables: JsonObject,
    ): JsonObject =
        try {
            query(operation, hash, variables, 256 * 1024, rateLimitRetries = 0, suppressSessionExpired = true)
        } catch (error: SpotifySessionExpiredException) {
            if (!currentSession.hasLoginCookie && !config.allowAnonymous) throw error
            attachSession(currentSession.copy(accessToken = null, clientToken = null))
            query(operation, hash, variables, 256 * 1024, rateLimitRetries = 0)
        }

    suspend fun query(
        operation: String,
        hash: String,
        variables: JsonObject,
        maxResponseBytes: Int = 2 * 1024 * 1024,
        rateLimitRetries: Int = 2,
        suppressSessionExpired: Boolean = false,
    ): JsonObject {
        val body =
            buildJsonObject {
                put("variables", variables)
                put("operationName", operation)
                put(
                    "extensions",
                    buildJsonObject {
                        put(
                            "persistedQuery",
                            buildJsonObject {
                                put("version", 1)
                                put("sha256Hash", hash)
                            },
                        )
                    },
                )
            }

        suspend fun postQuery(
            suppressRateLimit: Boolean,
            suppressSessionExpired: Boolean,
        ): TextResponse {
            ensureAuth()
            val accessToken = currentSession.accessToken ?: throw SpotifyAuthException("not logged in (no access token)")
            val clientToken = currentSession.clientToken ?: throw SpotifyAuthException("not logged in (no client token)")
            return api.post(Queries.PATHFINDER_URL, maxResponseBytes) {
                val expectedStatusCodes =
                    buildSet {
                        if (suppressRateLimit) add(429)
                        if (suppressSessionExpired) add(401)
                    }
                if (expectedStatusCodes.isNotEmpty()) attributes.put(ExpectedHttpFailureStatusCodes, expectedStatusCodes)
                headers {
                    append(HttpHeaders.Accept, "application/json")
                    append(HttpHeaders.ContentType, "application/json;charset=UTF-8")
                    append(HttpHeaders.Origin, "https://open.spotify.com")
                    append(HttpHeaders.Referrer, "https://open.spotify.com/")
                    append("app-platform", "WebPlayer")
                    append("spotify-app-version", config.appVersion)
                    append(HttpHeaders.Authorization, "Bearer $accessToken")
                    append("client-token", clientToken)
                }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }
        }
        var retries = 0
        var response = postQuery(suppressRateLimit = rateLimitRetries > 0, suppressSessionExpired = suppressSessionExpired)
        while (response.status.value == 429 && retries++ < rateLimitRetries) {
            val retryAfterSeconds = response.headers["Retry-After"]?.toLongOrNull()?.takeIf { it >= 0 } ?: 2L
            delay(retryAfterSeconds.seconds)
            response = postQuery(suppressRateLimit = retries < rateLimitRetries, suppressSessionExpired = suppressSessionExpired)
        }
        if (response.status.value == 429) {
            throw SpotifyException("$operation: Spotify rate limited", httpFailureLogged = true)
        }
        if (response.status.value == 401) {
            throw SpotifySessionExpiredException(
                "$operation: Spotify session expired",
                httpFailureLogged = !suppressSessionExpired,
            )
        }
        if (response.status.value >= 400) {
            throw SpotifyException("$operation: HTTP ${response.status.value}", httpFailureLogged = true)
        }
        val data =
            json
                .parseToJsonElement(
                    response.body ?: throw SpotifyException("$operation: response too large"),
                ).jsonObject
        if (data["errors"] != null) throw SpotifyException("$operation: Spotify returned GraphQL errors")
        return data
    }

    suspend fun listPlaylists(includeLikedSongs: Boolean = true): List<PlaylistMeta> {
        val result = ArrayList<PlaylistMeta>()
        var likedSongs: PlaylistMeta? = null
        val visitedFolders = HashSet<String>()

        suspend fun collect(
            folderUri: String?,
            path: List<String>,
        ) {
            var offset = 0
            while (true) {
                val parsed =
                    parseLibraryPage(
                        query("libraryV3", Queries.LIBRARY_V3_HASH, libraryV3Variables(folderUri, offset, PAGE_SIZE)),
                        path,
                    )
                result.addAll(parsed.playlists)
                for ((uri, name) in parsed.folders) {
                    if (visitedFolders.add(uri)) collect(uri, path + name)
                }
                if (parsed.likedSongs != null) likedSongs = parsed.likedSongs
                offset += PAGE_SIZE
                if (parsed.totalCount <= 0 || offset >= parsed.totalCount) break
            }
        }

        collect(null, emptyList())
        if (includeLikedSongs) likedSongs?.let { result.add(0, it) }
        Log.info("Loaded ${result.size} Spotify playlists")
        return result
    }

    suspend fun playlistTracks(
        uri: String,
        maxTracks: Int = Int.MAX_VALUE,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
    ): List<TrackItem> {
        require(maxTracks >= 0) { "maxTracks must not be negative" }
        if (maxTracks == 0) return emptyList()
        val output = ArrayList<TrackItem>()
        var offset = 0
        while (true) {
            val page =
                query(
                    "fetchPlaylistContents",
                    Queries.PLAYLIST_CONTENTS_HASH,
                    buildJsonObject {
                        put("uri", uri)
                        put("offset", offset)
                        put("limit", PAGE_SIZE)
                        put("includeEpisodeContentRatingsV2", true)
                    },
                )
            val (items, total) = parsePlaylistContentsPage(page)
            output.addAll(items)
            onProgress?.invoke(output.size.coerceAtMost(maxTracks), total)
            offset += PAGE_SIZE
            if (items.isEmpty() || total <= 0 || output.size >= total || output.size >= maxTracks) break
        }
        return reindex(output.take(maxTracks))
    }

    suspend fun likedSongs(
        maxTracks: Int = Int.MAX_VALUE,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
    ): List<TrackItem> {
        require(maxTracks >= 0) { "maxTracks must not be negative" }
        if (maxTracks == 0) return emptyList()
        val output = ArrayList<TrackItem>()
        var offset = 0
        while (true) {
            val page =
                query(
                    "fetchLibraryTracks",
                    Queries.LIBRARY_TRACKS_HASH,
                    buildJsonObject {
                        put("offset", offset)
                        put("limit", PAGE_SIZE)
                    },
                )
            val (items, total) = parseLikedSongsPage(page)
            output.addAll(items)
            onProgress?.invoke(output.size.coerceAtMost(maxTracks), total)
            offset += PAGE_SIZE
            if (items.isEmpty() || total <= 0 || output.size >= total || output.size >= maxTracks) break
        }
        return reindex(output.take(maxTracks))
    }

    suspend fun profile(): AccountProfile = parseProfile(query("profileAttributes", Queries.PROFILE_ATTRIBUTES_HASH, buildJsonObject {}))

    suspend fun export(includeLikedSongs: Boolean = true): LibraryExport {
        ensureAuth()
        val exported =
            listPlaylists(includeLikedSongs).map { playlist ->
                val tracks =
                    when (playlist.source) {
                        PlaylistSource.LIKED_SONGS -> likedSongs()
                        PlaylistSource.PLAYLIST -> playlistTracks(playlist.uri)
                    }
                Log.info("Loaded ${tracks.size} tracks for an export playlist")
                ExportedPlaylist(
                    uri = playlist.uri,
                    name = playlist.name,
                    owner = playlist.owner,
                    description = playlist.description,
                    image = playlist.image,
                    folderPath = playlist.folderPath,
                    source = playlist.source,
                    tracks = tracks,
                )
            }
        return LibraryExport(
            generatedAt = Instant.now().toString(),
            account = runCatching { profile() }.getOrElse { AccountProfile() },
            totalPlaylists = exported.size,
            totalTracks = exported.sumOf { it.tracks.size },
            playlists = exported,
        )
    }

    override fun close() {
        http.close()
    }

    private fun libraryV3Variables(
        folderUri: String?,
        offset: Int,
        limit: Int,
    ): JsonObject =
        buildJsonObject {
            put("filters", buildJsonArray {})
            put("order", JsonNull)
            put("textFilter", "")
            put("features", buildJsonArray { Queries.LIBRARY_FEATURES.forEach(::add) })
            put("limit", limit)
            put("offset", offset)
            put("flatten", false)
            put("expandedFolders", buildJsonArray {})
            put("folderUri", folderUri?.let(::JsonPrimitive) ?: JsonNull)
            put("includeFoldersWhenFlattening", true)
        }

    private fun reindex(tracks: List<TrackItem>): List<TrackItem> = tracks.mapIndexed { index, track -> track.copy(position = index + 1) }
}
