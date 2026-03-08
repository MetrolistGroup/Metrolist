package com.metrolist.shared.api.musicvideo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class CounterpartSegment(
    val sourceStartMs: Long,
    val targetStartMs: Long,
    val durationMs: Long,
)

@Serializable
data class CounterpartPositionMap(
    val segments: List<CounterpartSegment>,
) {
    fun mappedPosition(positionMs: Long): Long? {
        val position = positionMs.coerceAtLeast(0L)
        for (segment in segments) {
            if (position < segment.sourceStartMs) return segment.targetStartMs
            val offset = position - segment.sourceStartMs
            if (offset < segment.durationMs) return segment.targetStartMs + offset
        }
        return segments.lastOrNull()?.let { it.targetStartMs + it.durationMs }
    }

    fun mapPosition(positionMs: Long): Long = mappedPosition(positionMs) ?: positionMs.coerceAtLeast(0L)

    fun reversed(): CounterpartPositionMap =
        CounterpartPositionMap(
            segments.map { CounterpartSegment(it.targetStartMs, it.sourceStartMs, it.durationMs) },
        )
}

data class MusicVideoCounterpart(
    val sourceVideoId: String,
    val targetVideoId: String,
    val targetMusicVideoType: String? = null,
    val positionMap: CounterpartPositionMap?,
)

enum class CounterpartResolutionSource(
    val displayName: String,
) {
    AUTHORITATIVE("Authoritative (/next)"),
    ALBUM_RELEASE("Album Release"),
    HEURISTIC("Heuristic Search"),
}

fun parseMusicVideoCounterpart(
    response: JsonObject,
    sourceVideoId: String,
): MusicVideoCounterpart? {
    for (wrapper in response.findObjects("playlistPanelVideoWrapperRenderer")) {
        val primaryId = wrapper.rendererVideoId("primaryRenderer") ?: continue
        val counterparts = wrapper["counterpart"] as? JsonArray ?: continue
        for (entry in counterparts) {
            val counterpart = entry as? JsonObject ?: continue
            val counterpartId = counterpart.rendererVideoId("counterpartRenderer") ?: continue
            val forward = sourceVideoId == primaryId
            if (!forward && sourceVideoId != counterpartId) continue
            val targetId = if (forward) counterpartId else primaryId
            if (targetId == sourceVideoId) continue
            val segments = counterpart.parseSegments(forward)
            return MusicVideoCounterpart(
                sourceVideoId = sourceVideoId,
                targetVideoId = targetId,
                targetMusicVideoType = if (forward) counterpart.findMusicVideoType() else null,
                positionMap = segments.takeIf { it.isNotEmpty() }?.let(::CounterpartPositionMap),
            )
        }
    }
    return null
}

private fun JsonObject.rendererVideoId(key: String): String? =
    (this[key] as? JsonObject)
        ?.get("playlistPanelVideoRenderer")
        ?.jsonObject
        ?.get("videoId")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.parseSegments(forward: Boolean): List<CounterpartSegment> =
    (this["segmentMap"] as? JsonObject)
        ?.get("segment")
        ?.let { it as? JsonArray }
        .orEmpty()
        .mapNotNull { element ->
            val segment = element as? JsonObject ?: return@mapNotNull null
            val primary = segment["primaryVideoStartTimeMilliseconds"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val counterpart = segment["counterpartVideoStartTimeMilliseconds"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val duration = segment["durationMilliseconds"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L } ?: return@mapNotNull null
            CounterpartSegment(
                sourceStartMs = if (forward) primary else counterpart,
                targetStartMs = if (forward) counterpart else primary,
                durationMs = duration,
            )
        }.sortedBy(CounterpartSegment::sourceStartMs)

private fun JsonElement.findObjects(key: String): Sequence<JsonObject> =
    sequence {
        when (this@findObjects) {
            is JsonObject -> {
                (this@findObjects[key] as? JsonObject)?.let { yield(it) }
                this@findObjects.values.forEach { yieldAll(it.findObjects(key)) }
            }

            is JsonArray -> {
                this@findObjects.forEach { yieldAll(it.findObjects(key)) }
            }

            else -> {
                return@sequence
            }
        }
    }

private fun JsonObject.findMusicVideoType(): String? = findStringValue(this, "musicVideoType")

private fun findStringValue(
    element: JsonElement,
    key: String,
): String? =
    when (element) {
        is JsonObject -> {
            (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: element.values.firstNotNullOfOrNull { findStringValue(it, key) }
        }

        is JsonArray -> {
            element.firstNotNullOfOrNull { findStringValue(it, key) }
        }

        else -> {
            null
        }
    }
