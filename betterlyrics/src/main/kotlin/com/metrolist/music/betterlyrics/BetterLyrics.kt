package com.metrolist.music.betterlyrics

import com.metrolist.music.betterlyrics.models.TTMLResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import timber.log.Timber

object BetterLyrics {
    private const val TAG = "BetterLyrics"
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }

            defaultRequest {
                url("https://lyrics-api.boidu.dev")
                headers {
                    append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    append("Accept", "application/json")
                }
            }

            expectSuccess = false
        }
    }

    private suspend fun fetchTTML(
        artist: String,
        title: String,
        duration: Int = -1,
        album: String? = null,
    ): String? = runCatching {
        Timber.tag(TAG).d("Fetching TTML for: $title by $artist (dur=$duration, album=$album)")
        val response = client.get("/getLyrics") {
            parameter("s", title)
            parameter("a", artist)
            if (duration > 0) {
                parameter("d", duration)
            }
            if (!album.isNullOrBlank()) {
                parameter("al", album)
            }
        }
        if (response.status == HttpStatusCode.OK) {
            val ttml = response.body<TTMLResponse>().ttml?.trim()?.takeIf { it.isNotEmpty() }
            ttml
        } else {
            Timber.tag(TAG).w("API returned status: ${response.status}")
            null
        }
    }.getOrElse { e ->
        Timber.tag(TAG).e(e, "Exception during fetchTTML")
        null
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ) = runCatching {
        // Use exact title and artist - no normalization to ensure correct sync
        // Normalizing can return wrong lyrics (e.g., radio edit vs original)
        val ttml =
            fetchTTML(artist, title, duration, album)
                ?: throw IllegalStateException("Lyrics unavailable")

        val parsedLines = TTMLParser.parseTTML(ttml)
        if (parsedLines.isEmpty()) {
            throw IllegalStateException("Failed to parse lyrics")
        }
        
        TTMLParser.toLRC(parsedLines)
    }
}
