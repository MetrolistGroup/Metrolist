package com.metrolist.android.recognition

import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.recognition_failed
import com.metrolist.shared.generated.resources.recognition_notification_no_match
import com.metrolist.shared.generated.resources.recognition_service_temporarily_unavailable
import com.metrolist.shared.generated.resources.recognition_too_many_requests
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.recognition.RecognitionResult
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.parseYouTubeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jetbrains.compose.resources.getString
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

object ShazamRecognitionClient {
    private const val MIN_REQUEST_INTERVAL_MS = 1200L
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client =
        sharedOkHttpClient
            .newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    private val mutex = Mutex()
    private val cache = RecognitionResultCache()
    private var lastRequestAt = 0L

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
    ): Result<RecognitionResult> =
        withContext(Dispatchers.IO) {
            cache.get(signature, System.currentTimeMillis())?.let { return@withContext Result.success(it) }

            mutex.withLock {
                val elapsed = System.currentTimeMillis() - lastRequestAt
                if (elapsed < MIN_REQUEST_INTERVAL_MS) delay((MIN_REQUEST_INTERVAL_MS - elapsed).milliseconds)
                lastRequestAt = System.currentTimeMillis()
            }

            repeat(3) { attempt ->
                try {
                    val result = performRecognition(signature, sampleDurationMs)
                    cache.put(signature, result, System.currentTimeMillis())
                    return@withContext Result.success(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt == 2 || e is NoRecognitionMatchException) {
                        return@withContext Result.failure(e)
                    }
                    delay((1000L * (attempt + 1)).milliseconds)
                }
            }
            Result.failure(IllegalStateException(getString(Res.string.recognition_failed)))
        }

    private suspend fun performRecognition(
        signature: String,
        sampleDurationMs: Long,
    ): RecognitionResult {
        val timestamp = System.currentTimeMillis() / 1000
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
        val resolvedHl = GlobalYouTube.innerTube.locale.hl
        val resolvedGl = GlobalYouTube.innerTube.locale.gl
        val request =
            Request
                .Builder()
                .url(
                    "https://amp.shazam.com/discovery/v5/$resolvedHl/$resolvedGl/android/-/tag/${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3",
                ).header("User-Agent", userAgents.random())
                .header("Content-Language", "${resolvedHl}_$resolvedGl")
                .post(body.toRequestBody(mediaType))
                .build()
        val (code, responseText) = client.newCall(request).readCancellable()
        if (code !in 200..299) {
            when (code) {
                404 -> throw NoRecognitionMatchException(getString(Res.string.recognition_notification_no_match))
                429 -> throw RecognitionUserException(getString(Res.string.recognition_too_many_requests))
                in 500..599 -> throw RecognitionUserException(getString(Res.string.recognition_service_temporarily_unavailable))
                else -> throw IllegalStateException("Shazam request failed with HTTP $code")
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

internal class NoRecognitionMatchException(
    message: String,
) : IllegalStateException(message)

internal class RecognitionUserException(
    message: String,
) : IllegalStateException(message)

internal suspend fun Call.readCancellable(): Pair<Int, String> =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        try {
            val result = execute().use { it.code to it.body.string() }
            continuation.resume(result)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }
