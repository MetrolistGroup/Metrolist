package com.metrolist.shared.tidalimport

import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

class TidalImportManager internal constructor(
    createClient: () -> HttpClient,
) : AutoCloseable {
    constructor() : this(::createHttpClient)

    private val clientDelegate = lazy(createClient)
    private val httpClient: HttpClient by clientDelegate
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private var sessionId: String? = null
    private var userId: String? = null
    private var closed = false

    val isAuthenticated: Boolean get() = sessionId != null

    fun authenticate(
        sessionId: String,
        userId: String,
    ) {
        require(sessionId.isNotBlank()) { "Tidal login did not return a session ID" }
        check(!closed) { "Tidal import is closed" }
        this.sessionId = sessionId
        this.userId = userId
    }

    suspend fun loadPlaylists(likedSongsName: String = "Liked Songs"): List<LoadedPlaylist> {
        val sid = sessionId ?: error("Sign in to Tidal first")
        val uid = userId ?: error("Sign in to Tidal first")
        val playlists = mutableListOf<LoadedPlaylist>()

        var offset = 0
        val limit = 50
        while (playlists.size < 500) {
            val response =
                withTimeout(15_000.milliseconds) {
                    httpClient.get("https://api.tidal.com/v1/users/$uid/playlists?offset=$offset&limit=$limit") {
                        header("User-Agent", UA)
                        header("x-tidal-session-id", sid)
                    }
                }
            val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: throw ImportException(ImportErrorKey.Generic)
            if (!response.status.isSuccess()) throw ImportException(ImportErrorKey.Generic)

            val root = json.parseToJsonElement(body).jsonObject
            if (root.containsKey("errorCode")) throw ImportException(ImportErrorKey.Generic)
            val items = root["items"]?.jsonArray ?: throw ImportException(ImportErrorKey.Generic)
            if (items.isEmpty()) break
            for (item in items) {
                val obj = item.jsonObject
                val id = obj["uuid"]?.jsonPrimitive?.content ?: continue
                val title = obj["title"]?.jsonPrimitive?.content ?: continue
                val numberOfTracks = obj["numberOfTracks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val imageUrl =
                    obj["image"]?.jsonPrimitive?.content
                        ?: obj["squareImage"]?.jsonPrimitive?.content
                playlists +=
                    LoadedPlaylist(
                        id = id,
                        name = title,
                        image = imageUrl,
                        trackCount = numberOfTracks,
                        type = PlaylistType.PLAYLIST,
                    )
            }
            offset += limit
        }

        playlists.add(
            0,
            LoadedPlaylist(
                id = FAVORITES_ID,
                name = likedSongsName,
                image = null,
                trackCount = 0,
                type = PlaylistType.FAVORITES,
            ),
        )

        return playlists
    }

    suspend fun loadPlaylist(
        playlist: LoadedPlaylist,
        likedSongsName: String = "Liked Songs",
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): ImportedPlaylistData {
        val sid = sessionId ?: error("Sign in to Tidal first")
        val uid = userId ?: error("Sign in to Tidal first")
        val favorites = playlist.type == PlaylistType.FAVORITES
        val path = if (favorites) "users/$uid/favorites/tracks" else "playlists/${playlist.id}/tracks"
        val playlistName = if (favorites) likedSongsName else playlist.name
        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()
        var offset = 0
        val limit = 50

        while (true) {
            val response =
                withTimeout(15_000.milliseconds) {
                    httpClient.get("https://api.tidal.com/v1/$path?offset=$offset&limit=$limit") {
                        header("User-Agent", UA)
                        header("x-tidal-session-id", sid)
                    }
                }
            val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: throw ImportException(ImportErrorKey.Generic)
            if (!response.status.isSuccess()) throw ImportException(ImportErrorKey.Generic)

            val root = json.parseToJsonElement(body).jsonObject
            if (root.containsKey("errorCode")) throw ImportException(ImportErrorKey.Generic)
            val items = root["items"]?.jsonArray ?: throw ImportException(ImportErrorKey.Generic)
            if (items.isEmpty()) break
            if (tracks.size == PlaylistImportManager.MAX_PLAYLIST_TRACKS) throw ImportException(ImportErrorKey.TooManyTracks)
            for (item in items) {
                val obj = item.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content?.trim() ?: continue
                val artistName =
                    obj["artist"]
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim() ?: ""
                if (tracks.size == PlaylistImportManager.MAX_PLAYLIST_TRACKS) throw ImportException(ImportErrorKey.TooManyTracks)
                tracks += PlaylistImportManager.CsvTrack(title, artistName, null)
            }
            onProgress(tracks.size, null)
            offset += limit
        }

        if (tracks.isEmpty()) throw ImportException(ImportErrorKey.NoTracksToImport)
        return ImportedPlaylistData(title = playlistName, tracks = tracks, source = "Tidal")
    }

    override fun close() {
        closed = true
        sessionId = null
        userId = null
        if (clientDelegate.isInitialized()) httpClient.close()
    }

    enum class PlaylistType { PLAYLIST, FAVORITES }

    data class LoadedPlaylist(
        val id: String,
        val name: String,
        val image: String?,
        val trackCount: Int,
        val type: PlaylistType,
    )

    companion object {
        private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MAX_JSON_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val FAVORITES_ID = "favorites"

        fun extractSessionCredentials(cookieHeader: String?): Pair<String, String>? {
            if (cookieHeader.isNullOrBlank()) return null
            val sidMatch = Regex("""(?:^|;\s*)sid=([^;]+)""").find(cookieHeader)
            val sid =
                sidMatch
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return null
            val userIdMatch = Regex("""(?:^|;\s*)userId=([^;]+)""").find(cookieHeader)
            val uid =
                userIdMatch
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return null
            return sid to uid
        }
    }
}
