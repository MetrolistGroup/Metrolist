package com.metrolist.music.features.lyrics

import android.content.Context
import com.metrolist.music.lyrics.LyricsProvider
import com.metrolist.music.constants.EnableSimpMusicKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
private data class SimpMusicLyricsData(
    val id: String? = null,
    val videoId: String? = null,
    @SerialName("songTitle") val title: String? = null,
    @SerialName("artistName") val artist: String? = null,
    @SerialName("albumName") val album: String? = null,
    @SerialName("durationSeconds") val duration: Int? = null,
    val syncedLyrics: String? = null,
    @SerialName("plainLyric") val plainLyrics: String? = null,
    val richSyncLyrics: String? = null,
)

@Serializable
private data class SimpMusicResponse(
    val data: List<SimpMusicLyricsData> = emptyList(),
    val success: Boolean = false,
)

object SimpMusicLyricsProvider : LyricsProvider {
    override val name = "SimpMusic"
    private const val BASE_URL = "https://api-lyrics.simpmusic.org/v1/"
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableSimpMusicKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = runCatching {
        val tracks = fetch(id)
        if (tracks.isEmpty()) error("SimpMusic lyrics unavailable")
        val best = tracks.minByOrNull { abs((it.duration ?: 0) - duration) }
        best?.syncedLyrics ?: best?.richSyncLyrics ?: best?.plainLyrics
            ?: error("SimpMusic lyrics unavailable")
    }

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        fetch(id)
            .sortedBy { abs((it.duration ?: 0) - duration) }
            .take(5)
            .forEach { track ->
                if (abs((track.duration ?: 0) - duration) <= 5) {
                    track.syncedLyrics?.let(callback)
                    track.richSyncLyrics?.let(callback)
                    track.plainLyrics?.let(callback)
                }
            }
    }

    private suspend fun fetch(videoId: String): List<SimpMusicLyricsData> = runCatching {
        val response = client.get(BASE_URL + videoId) {
            header("Accept", "application/json")
            header("User-Agent", "Metrolist/lyrics")
        }
        if (response.status != HttpStatusCode.OK) return@runCatching emptyList()
        val payload = response.body<SimpMusicResponse>()
        if (payload.success) payload.data else emptyList()
    }.getOrDefault(emptyList())
}
