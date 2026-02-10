package com.metrolist.shazamkit

import com.metrolist.shazamkit.models.RecognitionResult
import com.metrolist.shazamkit.models.ShazamRequestJson
import com.metrolist.shazamkit.models.ShazamResponseJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.random.Random

object Shazam {
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
            expectSuccess = false
        }
    }

    private val userAgents = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)",
        "Dalvik/1.6.0 (Linux; U; Android 4.4.2; SM-T210 Build/KOT49H)",
        "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-P905V Build/LMY47X)",
        "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)",
        "Dalvik/2.1.0 (Linux; U; Android 5.0; SM-G900F Build/LRX21T)"
    )

    private val timezones = listOf(
        "Europe/Paris", "Europe/London", "America/New_York",
        "America/Los_Angeles", "Asia/Tokyo", "Asia/Dubai"
    )

    /**
     * Recognize music from an audio signature
     * The signature should be in Shazam's DejaVu format
     */
    suspend fun recognize(signature: String, sampleDurationMs: Long): Result<RecognitionResult> = runCatching {
        val timestamp = System.currentTimeMillis() / 1000
        val uuid1 = UUID.randomUUID().toString().uppercase()
        val uuid2 = UUID.randomUUID().toString()
        
        val request = ShazamRequestJson(
            geolocation = ShazamRequestJson.Geolocation(
                altitude = Random.nextDouble() * 400 + 100,
                latitude = Random.nextDouble() * 180 - 90,
                longitude = Random.nextDouble() * 360 - 180
            ),
            signature = ShazamRequestJson.Signature(
                samplems = sampleDurationMs,
                timestamp = timestamp,
                uri = signature
            ),
            timestamp = timestamp,
            timezone = timezones.random()
        )

        val response = client.post("https://amp.shazam.com/discovery/v5/en/US/android/-/tag/$uuid1/$uuid2") {
            parameter("sync", "true")
            parameter("webv3", "true")
            parameter("sampling", "true")
            parameter("connected", "")
            parameter("shazamapiversion", "v3")
            parameter("sharehub", "true")
            parameter("video", "v3")
            header("User-Agent", userAgents.random())
            header("Content-Language", "en_US")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        val shazamResponse = response.body<ShazamResponseJson>()
        shazamResponse.toRecognitionResult() 
            ?: throw Exception("No match found")
    }

    /**
     * Convert Shazam response to our internal model
     */
    fun ShazamResponseJson.toRecognitionResult(): RecognitionResult? {
        val track = this.track ?: return null
        
        // Extract metadata from sections
        val songSection = track.sections?.find { it?.type == "SONG" }
        val metadata = songSection?.metadata
        val album = metadata?.find { it?.title == "Album" }?.text
        val label = metadata?.find { it?.title == "Label" }?.text
        val releaseDate = metadata?.find { it?.title == "Released" }?.text
        
        // Extract lyrics
        val lyricsSection = track.sections?.find { it?.type == "LYRICS" }
        val lyrics = lyricsSection?.text
        
        // Extract streaming links
        val appleAction = track.hub?.options?.firstOrNull { 
            it?.providername?.contains("apple", ignoreCase = true) == true 
        }?.actions?.firstOrNull()
        val spotifyProvider = track.hub?.providers?.find { 
            it?.caption?.contains("spotify", ignoreCase = true) == true 
        }
        
        // Extract YouTube video ID if available
        val youtubeAction = track.hub?.options?.find { 
            it?.type?.contains("video", ignoreCase = true) == true 
        }?.actions?.firstOrNull()
        val youtubeVideoId = youtubeAction?.uri?.let { uri ->
            // Extract video ID from YouTube URL or URI
            uri.substringAfterLast("v=", "").takeIf { it.isNotEmpty() }
                ?: uri.substringAfterLast("/", "").takeIf { it.isNotEmpty() && it.length == 11 }
        }

        return RecognitionResult(
            trackId = track.key ?: tagid ?: "",
            title = track.title ?: "",
            artist = track.subtitle ?: "",
            album = album,
            coverArtUrl = track.images?.coverart,
            coverArtHqUrl = track.images?.coverarthq,
            genre = track.genres?.primary,
            releaseDate = releaseDate,
            label = label,
            lyrics = lyrics,
            shazamUrl = track.url,
            appleMusicUrl = appleAction?.uri,
            spotifyUrl = spotifyProvider?.actions?.firstOrNull()?.uri,
            isrc = track.isrc,
            youtubeVideoId = youtubeVideoId
        )
    }
}
