package com.metrolist.shared.playback.sponsorblock

import com.metrolist.shared.deviceconnect.DeviceCrypto
import com.metrolist.shared.model.MusicVideoType
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.parseMusicVideoType
import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.network.createHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal val sponsorBlockCategories = setOf("sponsor", "selfpromo", "interaction")

internal fun canSkipPodcastSegments(
    song: SongItem?,
    enabled: Boolean,
): Boolean =
    enabled && song != null && !song.isLocal && !song.isUploaded && !song.id.startsWith("local_") &&
        parseMusicVideoType(song.musicVideoType) == MusicVideoType.PODCAST_EPISODE

internal data class SponsorBlockSegment(
    val startMs: Long,
    val endMs: Long,
)

internal fun parseSponsorBlockSegments(
    body: String,
    videoId: String,
): List<SponsorBlockSegment> {
    val videos = Json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
    val video = videos.filterIsInstance<JsonObject>().firstOrNull { (it["videoID"] as? JsonPrimitive)?.contentOrNull == videoId }
    val segments = video?.get("segments") as? JsonArray ?: return emptyList()
    val valid =
        segments
            .mapNotNull { element ->
                val segment = element as? JsonObject ?: return@mapNotNull null
                if ((segment["category"] as? JsonPrimitive)?.contentOrNull !in sponsorBlockCategories ||
                    (segment["actionType"] as? JsonPrimitive)?.contentOrNull != "skip"
                ) {
                    return@mapNotNull null
                }
                val range = segment["segment"] as? JsonArray ?: return@mapNotNull null
                if (range.size != 2) return@mapNotNull null
                val start = (range[0] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val end = (range[1] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end * 1000 >= Long.MAX_VALUE.toDouble()) {
                    return@mapNotNull null
                }
                SponsorBlockSegment((start * 1000).toLong(), (end * 1000).toLong()).takeIf { it.endMs > it.startMs }
            }.sortedBy { it.startMs }
    val merged = mutableListOf<SponsorBlockSegment>()
    for (segment in valid) {
        val previous = merged.lastOrNull()
        if (previous != null && segment.startMs <= previous.endMs) {
            merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, segment.endMs))
        } else {
            merged += segment
        }
    }
    return merged
}

internal fun List<SponsorBlockSegment>.skipTarget(
    positionMs: Long,
    durationMs: Long,
): Long? = firstOrNull { positionMs >= it.startMs && positionMs < it.endMs && (durationMs <= 0 || it.endMs <= durationMs) }?.endMs

class SponsorBlockClient internal constructor(
    private val httpClient: HttpClient,
) {
    private data class CachedSegments(
        val segments: List<SponsorBlockSegment>,
        val fetchedAt: TimeMark,
    )

    private val cache = mutableMapOf<String, CachedSegments>()
    private val mutex = Mutex()

    internal suspend fun segments(
        song: SongItem?,
        enabled: Boolean,
    ): List<SponsorBlockSegment> {
        if (!canSkipPodcastSegments(song, enabled)) return emptyList()
        val videoId = requireNotNull(song).id
        return mutex.withLock {
            cache[videoId]?.let {
                val lifetime = if (it.segments.isEmpty()) 5.minutes else 1.hours
                if (it.fetchedAt.elapsedNow() < lifetime) return@withLock it.segments
                cache.remove(videoId)
            }
            val segments =
                try {
                    val prefix =
                        DeviceCrypto.sha256(videoId.encodeToByteArray()).take(2).joinToString("") {
                            (it.toInt() and 0xff).toString(16).padStart(2, '0')
                        }
                    val response =
                        httpClient.get("https://sponsor.ajay.app/api/skipSegments/$prefix") {
                            attributes.put(ExpectedHttpFailureStatusCodes, setOf(HttpStatusCode.NotFound.value))
                            sponsorBlockCategories.forEach { parameter("category", it) }
                            parameter("actionType", "skip")
                            timeout { requestTimeoutMillis = 5_000 }
                        }
                    if (response.status == HttpStatusCode.OK) parseSponsorBlockSegments(response.bodyAsText(), videoId) else emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            if (cache.size >= 64) cache.remove(cache.keys.first())
            cache[videoId] = CachedSegments(segments, TimeSource.Monotonic.markNow())
            segments
        }
    }

    companion object {
        val shared: SponsorBlockClient by lazy { SponsorBlockClient(createHttpClient()) }
    }
}
