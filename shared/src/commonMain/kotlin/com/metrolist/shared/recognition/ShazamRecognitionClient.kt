package com.metrolist.shared.recognition

import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.recognition_failed
import com.metrolist.shared.generated.resources.recognition_notification_no_match
import com.metrolist.shared.generated.resources.recognition_service_temporarily_unavailable
import com.metrolist.shared.generated.resources.recognition_too_many_requests
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.parseYouTubeUrl
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.getString
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
object ShazamRecognitionClient {
    private const val MIN_REQUEST_INTERVAL_MS = 1200L
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { createHttpClient() }
    private val mutex = Mutex()
    private var lastRequestAt: TimeMark? = null
    private val cache = RecognitionResultCache()

    private val userAgents =
        listOf(
            "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A)",
            "Dalvik/2.1.0 (Linux; U; Android 12; SM-G991B Build/SP1A)",
            "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A)",
        )
    private val timezones = listOf("Europe/London", "Europe/Paris", "America/New_York", "America/Los_Angeles", "Asia/Tokyo")

    suspend fun recognize(
        signature: String,
        sampleDurationMs: Long,
    ): Result<RecognitionResult> {
        cache.get(signature)?.let { return Result.success(it) }

        repeat(3) { attempt ->
            mutex.withLock {
                val remaining = lastRequestAt?.let { MIN_REQUEST_INTERVAL_MS.milliseconds - it.elapsedNow() } ?: Duration.ZERO
                if (remaining > Duration.ZERO) delay(remaining)
                lastRequestAt = TimeSource.Monotonic.markNow()
            }
            try {
                val result = performRecognition(signature, sampleDurationMs)
                cache.put(signature, result)
                return Result.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 2 || e is NoRecognitionMatchException) {
                    return Result.failure(e)
                }
                delay((1000L * (attempt + 1)).milliseconds)
            }
        }
        return Result.failure(IllegalStateException(getString(Res.string.recognition_failed)))
    }

    private suspend fun performRecognition(
        signature: String,
        sampleDurationMs: Long,
    ): RecognitionResult {
        val timestamp = Clock.System.now().epochSeconds
        val body =
            buildJsonObject {
                put(
                    "geolocation",
                    buildJsonObject {
                        put("altitude", Random.nextDouble(100.0, 500.0))
                        put("latitude", Random.nextDouble(-90.0, 90.0))
                        put("longitude", Random.nextDouble(-180.0, 180.0))
                    },
                )
                put(
                    "signature",
                    buildJsonObject {
                        put("samplems", sampleDurationMs)
                        put("timestamp", timestamp)
                        put("uri", signature)
                    },
                )
                put("timestamp", timestamp)
                put("timezone", timezones.random())
            }.toString()
        val locale = GlobalYouTube.contentLocale
        val resolvedHl = locale.hl.substringBefore('-')
        val resolvedGl = locale.gl
        val response =
            client.post(
                "https://amp.shazam.com/discovery/v5/$resolvedHl/$resolvedGl/android/-/tag/${Uuid.random().toString().uppercase()}/${Uuid.random()}?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3",
            ) {
                // Matches the old Android client: 35 s read, plus 15 s each for connect and write.
                timeout {
                    socketTimeoutMillis = 35_000
                    requestTimeoutMillis = 65_000
                }
                header("User-Agent", userAgents.random())
                header("Content-Language", "${resolvedHl}_$resolvedGl")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        val responseText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            when (response.status.value) {
                404 -> throw NoRecognitionMatchException(getString(Res.string.recognition_notification_no_match))
                429 -> throw RecognitionUserException(getString(Res.string.recognition_too_many_requests))
                in 500..599 -> throw RecognitionUserException(getString(Res.string.recognition_service_temporarily_unavailable))
                else -> throw IllegalStateException("Shazam request failed with HTTP ${response.status.value}")
            }
        }
        return parseResult(responseText)
            ?: throw NoRecognitionMatchException(getString(Res.string.recognition_notification_no_match))
    }

    internal fun parseResult(responseText: String): RecognitionResult? {
        val root = json.parseToJsonElement(responseText).jsonObject
        val track = root["track"]?.jsonObject ?: return null
        val sections = track["sections"]?.jsonArray.orEmpty().map { it.jsonObject }
        val metadata =
            sections
                .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "SONG" }
                ?.get("metadata")
                ?.jsonArray
                .orEmpty()
                .mapNotNull { item ->
                    val obj = item.jsonObject
                    (obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null) to obj["text"]?.jsonPrimitive?.contentOrNull
                }.toMap()
        val youtubeVideoId =
            track["hub"]
                ?.jsonObject
                ?.get("options")
                ?.jsonArray
                .orEmpty()
                .firstOrNull {
                    it.jsonObject["type"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.contains("video", true) == true
                }?.jsonObject
                ?.get("actions")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("uri")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let { uri ->
                    (parseYouTubeUrl(uri) as? YouTubeUrlParser.ParsedUrl.Video)?.id
                }
        val spotifyUrl =
            track["hub"]
                ?.jsonObject
                ?.get("providers")
                ?.jsonArray
                .orEmpty()
                .firstOrNull {
                    it.jsonObject["caption"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.contains("spotify", true) == true
                }?.jsonObject
                ?.get("actions")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("uri")
                ?.jsonPrimitive
                ?.contentOrNull
        val appleUrl =
            track["hub"]
                ?.jsonObject
                ?.get("options")
                ?.jsonArray
                .orEmpty()
                .firstOrNull {
                    it.jsonObject["providername"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.contains("apple", true) == true
                }?.jsonObject
                ?.get("actions")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("uri")
                ?.jsonPrimitive
                ?.contentOrNull
        return RecognitionResult(
            trackId = track["key"]?.jsonPrimitive?.contentOrNull ?: root["tagid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            title = track["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            artist = track["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            album = metadata["Album"],
            coverArtUrl =
                track["images"]
                    ?.jsonObject
                    ?.get("coverart")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            coverArtHqUrl =
                track["images"]
                    ?.jsonObject
                    ?.get("coverarthq")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            genre =
                track["genres"]
                    ?.jsonObject
                    ?.get("primary")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            releaseDate = metadata["Released"],
            label = metadata["Label"],
            shazamUrl = track["url"]?.jsonPrimitive?.contentOrNull,
            appleMusicUrl = appleUrl,
            spotifyUrl = spotifyUrl,
            isrc = track["isrc"]?.jsonPrimitive?.contentOrNull,
            youtubeVideoId = youtubeVideoId,
        ).takeIf { it.title.isNotBlank() }
    }
}

private class RecognitionResultCache {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Pair<TimeMark, RecognitionResult>>()

    suspend fun get(signature: String): RecognitionResult? =
        mutex.withLock {
            val entry = entries[signature] ?: return@withLock null
            if (entry.first.elapsedNow() >= 5.minutes) {
                entries.remove(signature)
                null
            } else {
                entry.second
            }
        }

    suspend fun put(
        signature: String,
        result: RecognitionResult,
    ) = mutex.withLock {
        entries.entries.removeAll { entry -> entry.value.first.elapsedNow() >= 5.minutes }
        entries[signature] = TimeSource.Monotonic.markNow() to result
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    private companion object {
        const val MAX_ENTRIES = 32
    }
}
