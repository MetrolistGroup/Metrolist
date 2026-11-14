package com.metrolist.shared.deezerimport

import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

class DeezerImportManager internal constructor(
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

    private var accessToken: String? = null
    private var closed = false

    val isAuthenticated: Boolean get() = accessToken != null

    fun authenticate(arl: String) {
        require(arl.isNotBlank()) { "Deezer login did not return a session token" }
        check(!closed) { "Deezer import is closed" }
        accessToken = arl
    }

    suspend fun loadPlaylists(likedSongsName: String = "Liked Songs"): List<LoadedPlaylist> {
        val token = accessToken ?: error("Sign in to Deezer first")
        val playlists = mutableListOf<LoadedPlaylist>()

        var nextUrl: String? = "https://api.deezer.com/user/me/playlists"
        while (nextUrl != null && playlists.size < 500) {
            val requestUrl = deezerApiUrl(nextUrl)
            val response =
                withTimeout(15_000.milliseconds) {
                    httpClient.get(requestUrl) {
                        header("User-Agent", UA)
                        parameter("access_token", token)
                    }
                }
            val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: throw ImportException(ImportErrorKey.Generic)
            if (!response.status.isSuccess()) throw ImportException(ImportErrorKey.Generic)

            val root = json.parseToJsonElement(body).jsonObject
            if (root.containsKey("error")) throw ImportException(ImportErrorKey.Generic)
            val dataArray = root["data"]?.jsonArray ?: throw ImportException(ImportErrorKey.Generic)
            for (item in dataArray) {
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: continue
                val title = obj["title"]?.jsonPrimitive?.content ?: continue
                val trackCount = obj["nb_tracks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val picture =
                    obj["picture_medium"]?.jsonPrimitive?.content
                        ?: obj["picture"]?.jsonPrimitive?.content
                playlists +=
                    LoadedPlaylist(
                        id = id,
                        name = title,
                        image = picture,
                        trackCount = trackCount,
                    )
            }
            nextUrl = root["next"]?.jsonPrimitive?.contentOrNull
        }

        playlists.add(
            0,
            LoadedPlaylist(
                id = LIKED_SONGS_ID,
                name = likedSongsName,
                image = null,
                trackCount = 0,
            ),
        )

        return playlists
    }

    suspend fun loadPlaylist(
        playlist: LoadedPlaylist,
        likedSongsName: String = "Liked Songs",
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): ImportedPlaylistData {
        val token = accessToken ?: error("Sign in to Deezer first")
        return if (playlist.id == LIKED_SONGS_ID) {
            loadLikedSongs(token, onProgress, likedSongsName)
        } else {
            loadPlaylistTracks(playlist.id, playlist.name, token, onProgress)
        }
    }

    private suspend fun loadLikedSongs(
        token: String,
        onProgress: (loaded: Int, total: Int?) -> Unit,
        likedSongsName: String,
    ): ImportedPlaylistData {
        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()
        var nextUrl: String? = "https://api.deezer.com/user/me/tracks"

        while (nextUrl != null && tracks.size < PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
            val requestUrl = deezerApiUrl(nextUrl)
            val response =
                withTimeout(15_000.milliseconds) {
                    httpClient.get(requestUrl) {
                        header("User-Agent", UA)
                        parameter("access_token", token)
                    }
                }
            val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: throw ImportException(ImportErrorKey.Generic)
            if (!response.status.isSuccess()) throw ImportException(ImportErrorKey.Generic)

            val root = json.parseToJsonElement(body).jsonObject
            if (root.containsKey("error")) throw ImportException(ImportErrorKey.Generic)
            val dataArray = root["data"]?.jsonArray ?: throw ImportException(ImportErrorKey.Generic)
            for (item in dataArray) {
                val obj = item.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content?.trim() ?: continue
                val artistName =
                    obj["artist"]
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim() ?: ""
                tracks += PlaylistImportManager.CsvTrack(title, artistName, null)
            }
            onProgress(tracks.size, null)
            nextUrl = root["next"]?.jsonPrimitive?.contentOrNull
        }

        if (nextUrl != null || tracks.size > PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
            throw ImportException(ImportErrorKey.TooManyTracks)
        }
        if (tracks.isEmpty()) throw ImportException(ImportErrorKey.NoTracksToImport)
        return ImportedPlaylistData(title = likedSongsName, tracks = tracks, source = "Deezer")
    }

    private suspend fun loadPlaylistTracks(
        playlistId: String,
        playlistName: String,
        token: String,
        onProgress: (loaded: Int, total: Int?) -> Unit,
    ): ImportedPlaylistData {
        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()
        var nextUrl: String? = "https://api.deezer.com/playlist/$playlistId/tracks"

        while (nextUrl != null && tracks.size < PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
            val requestUrl = deezerApiUrl(nextUrl)
            val response =
                withTimeout(15_000.milliseconds) {
                    httpClient.get(requestUrl) {
                        header("User-Agent", UA)
                        parameter("access_token", token)
                    }
                }
            val body = response.bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES) ?: throw ImportException(ImportErrorKey.Generic)
            if (!response.status.isSuccess()) throw ImportException(ImportErrorKey.Generic)

            val root = json.parseToJsonElement(body).jsonObject
            if (root.containsKey("error")) throw ImportException(ImportErrorKey.Generic)
            val dataArray = root["data"]?.jsonArray ?: throw ImportException(ImportErrorKey.Generic)
            for (item in dataArray) {
                val obj = item.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content?.trim() ?: continue
                val artistName =
                    obj["artist"]
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content
                        ?.trim() ?: ""
                tracks += PlaylistImportManager.CsvTrack(title, artistName, null)
            }
            onProgress(tracks.size, null)
            nextUrl = root["next"]?.jsonPrimitive?.contentOrNull
        }

        if (nextUrl != null || tracks.size > PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
            throw ImportException(ImportErrorKey.TooManyTracks)
        }
        if (tracks.isEmpty()) throw ImportException(ImportErrorKey.NoTracksToImport)
        return ImportedPlaylistData(title = playlistName, tracks = tracks, source = "Deezer")
    }

    override fun close() {
        closed = true
        accessToken = null
        if (clientDelegate.isInitialized()) httpClient.close()
    }

    data class LoadedPlaylist(
        val id: String,
        val name: String,
        val image: String?,
        val trackCount: Int,
    )

    companion object {
        private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val MAX_JSON_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val LIKED_SONGS_ID = "liked"

        fun extractArl(cookieHeader: String?): String? {
            if (cookieHeader.isNullOrBlank()) return null
            val match = Regex("""(?:^|;\s*)arl=([^;]+)""").find(cookieHeader)
            return match
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }
}

private fun deezerApiUrl(value: String): String {
    val url = Url(if (value.startsWith('/')) "https://api.deezer.com$value" else value)
    require(url.protocol.name == "https" && url.host == "api.deezer.com" && url.user?.isNotEmpty() != true)
    return url.toString()
}
