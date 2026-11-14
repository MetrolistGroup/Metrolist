package com.metrolist.shared.amazonmusicimport

import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.playlistimport.AmazonMusicPlaylistImporter
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

class AmazonMusicImportManager internal constructor(
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

    private data class Session(
        val cookies: String,
        val marketplaceHost: String,
    )

    private var session: Session? = null
    private var closed = false

    val isAuthenticated: Boolean get() = session != null

    fun authenticate(
        cookies: String,
        host: String,
    ) {
        require(host in AmazonMusicPlaylistImporter.marketplaceHosts) { "Unsupported Amazon Music marketplace" }
        require(cookies.isNotBlank()) { "Amazon Music login did not return session cookies" }
        check(!closed) { "Amazon Music import is closed" }
        session = Session(cookies, host)
    }

    suspend fun loadPlaylists(likedSongsName: String = "Liked Songs"): List<LoadedPlaylist> {
        val (cookies, marketplaceHost) = session ?: error("Sign in to Amazon Music first")
        val playlists = mutableListOf<LoadedPlaylist>()

        val response =
            withTimeout(15_000.milliseconds) {
                httpClient.get(
                    "https://$marketplaceHost/api/library/playlists?adjustedTracks=true&maxResults=500&sortColumn=default&sortDirection=ASC",
                ) {
                    header("User-Agent", UA)
                    header("Cookie", cookies)
                    header("Accept", "application/json")
                }
            }
        val body = response.importBody()

        val root = json.parseToJsonElement(body).jsonObject
        val playlistsArray = root["playlists"]?.jsonArray ?: throw ImportException(ImportErrorKey.Generic)
        for (item in playlistsArray) {
            val obj = item.jsonObject
            val id = obj["playlistId"]?.jsonPrimitive?.content ?: continue
            val name = obj["title"]?.jsonPrimitive?.content ?: continue
            val trackCount = obj["trackCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val imageUrl = obj["imageUrl"]?.jsonPrimitive?.content
            playlists +=
                LoadedPlaylist(
                    id = id,
                    name = name,
                    image = imageUrl,
                    trackCount = trackCount,
                    type = PlaylistType.PLAYLIST,
                )
        }

        playlists.add(
            0,
            LoadedPlaylist(
                id = LIKED_SONGS_ID,
                name = likedSongsName,
                image = null,
                trackCount = 0,
                type = PlaylistType.LIKED_SONGS,
            ),
        )

        return playlists
    }

    suspend fun loadPlaylist(
        playlist: LoadedPlaylist,
        likedSongsName: String = "Liked Songs",
        onProgress: (loaded: Int, total: Int?) -> Unit = { _, _ -> },
    ): ImportedPlaylistData {
        val (cookies, marketplaceHost) = session ?: error("Sign in to Amazon Music first")
        val likedSongs = playlist.type == PlaylistType.LIKED_SONGS
        val playlistName = if (likedSongs) likedSongsName else playlist.name
        val path =
            if (likedSongs) {
                "tracks?maxResults=500&sortColumn=default&sortDirection=ASC"
            } else {
                "playlists/${playlist.id}/tracks?maxResults=500"
            }
        val tracks = mutableListOf<PlaylistImportManager.CsvTrack>()

        val response =
            withTimeout(15_000.milliseconds) {
                httpClient.get("https://$marketplaceHost/api/library/$path") {
                    header("User-Agent", UA)
                    header("Cookie", cookies)
                    header("Accept", "application/json")
                }
            }
        val body = response.importBody()

        val root = json.parseToJsonElement(body).jsonObject
        val tracksArray = root["tracks"]?.jsonArray ?: throw ImportException(ImportErrorKey.Generic)
        for (item in tracksArray) {
            val obj = item.jsonObject
            val title = obj["title"]?.jsonPrimitive?.content?.trim() ?: continue
            val artistName = obj["artist"]?.jsonPrimitive?.content?.trim() ?: ""
            tracks += PlaylistImportManager.CsvTrack(title, artistName, null)
        }
        onProgress(tracks.size, tracks.size)

        if (playlist.trackCount > PlaylistImportManager.MAX_PLAYLIST_TRACKS) throw ImportException(ImportErrorKey.TooManyTracks)
        if (tracks.size < playlist.trackCount || (playlist.trackCount == 0 && tracksArray.size >= 500) ||
            !root["nextToken"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
        ) {
            throw ImportException(ImportErrorKey.IncompleteSource)
        }
        if (tracks.isEmpty()) throw ImportException(ImportErrorKey.NoTracksToImport)
        return ImportedPlaylistData(title = playlistName, tracks = tracks, source = "Amazon Music")
    }

    private suspend fun HttpResponse.importBody(): String {
        if (!status.isSuccess()) {
            throw ImportException(ImportErrorKey.Generic, IllegalStateException("Amazon Music request failed (${status.value})"))
        }
        return bodyAsTextLimited(MAX_JSON_RESPONSE_BYTES)
            ?: throw ImportException(ImportErrorKey.Generic, IllegalStateException("Amazon Music response exceeds size limit"))
    }

    override fun close() {
        closed = true
        session = null
        if (clientDelegate.isInitialized()) httpClient.close()
    }

    enum class PlaylistType { PLAYLIST, LIKED_SONGS }

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
        private const val LIKED_SONGS_ID = "liked"

        fun extractSessionCookie(cookieHeader: String?): String? {
            if (cookieHeader.isNullOrBlank()) return null
            val hasSessionToken =
                cookieHeader.contains("session-token=") ||
                    cookieHeader.contains("session-id=") ||
                    cookieHeader.contains("at-main=") ||
                    cookieHeader.contains("ubid-main=")
            return if (hasSessionToken) cookieHeader else null
        }
    }
}
