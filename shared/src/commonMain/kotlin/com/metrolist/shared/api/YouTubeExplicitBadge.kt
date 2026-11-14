package com.metrolist.shared.api

import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_LIVE
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.MusicVideoType
import com.metrolist.shared.model.isVideoType
import com.metrolist.shared.model.parseMusicVideoType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val officialVideoTitleRegex =
    Regex("""\bofficial\s+(?:music\s+)?(?:video|mv)\b""", RegexOption.IGNORE_CASE)

private val liveVideoTitleRegex =
    Regex(
        """[(\[][^()\[\]]*\blive\b[^()\[\]]*[)\]]|\blive[ -]?(performance|session|video)\b|\blive\s+(at|from|in|on)\b|[-–—|:~]\s*live\s*$""",
        RegexOption.IGNORE_CASE,
    )

internal fun String.inferredMusicVideoType(hasVideoEvidence: Boolean = false): String? =
    when {
        officialVideoTitleRegex.containsMatchIn(this) -> MUSIC_VIDEO_TYPE_OMV
        hasVideoEvidence && liveVideoTitleRegex.containsMatchIn(this) -> MUSIC_VIDEO_TYPE_LIVE
        else -> null
    }

fun isLiveLyricsVideo(
    musicVideoType: String?,
    title: String,
): Boolean =
    parseMusicVideoType(musicVideoType) == MusicVideoType.LIVE ||
        title.inferredMusicVideoType(isVideoType(musicVideoType)) == MUSIC_VIDEO_TYPE_LIVE

internal fun JsonObject.hasExplicitBadge(): Boolean =
    hasExplicitBadgeArray(this["badges"]?.jsonArray) ||
        hasExplicitBadgeArray(this["subtitleBadges"]?.jsonArray)

internal fun JsonObject.musicVideoType(): String? = findStringValue("musicVideoType")

private fun JsonElement.findStringValue(key: String): String? =
    when (this) {
        is JsonObject -> {
            (this[key] as? JsonPrimitive)?.content
                ?: values.firstNotNullOfOrNull { it.findStringValue(key) }
        }

        is JsonArray -> {
            firstNotNullOfOrNull { it.findStringValue(key) }
        }

        else -> {
            null
        }
    }

private fun hasExplicitBadgeArray(array: JsonArray?): Boolean =
    array?.any { badge ->
        badge.jsonObject["musicInlineBadgeRenderer"]
            ?.jsonObject
            ?.get("icon")
            ?.jsonObject
            ?.get("iconType")
            ?.jsonPrimitive
            ?.content == "MUSIC_EXPLICIT_BADGE"
    } == true

internal fun JsonObject.hasVideoThumbnail(): Boolean =
    entries.any { (key, value) ->
        when {
            key == "thumbnails" && value is JsonArray -> {
                value.any { thumbnail ->
                    val dimensions = thumbnail as? JsonObject
                    val width = (dimensions?.get("width") as? JsonPrimitive)?.content?.toLongOrNull()
                    val height = (dimensions?.get("height") as? JsonPrimitive)?.content?.toLongOrNull()
                    width != null && height != null && height > 0 && width > height
                }
            }

            value is JsonObject -> {
                value.hasVideoThumbnail()
            }

            else -> {
                false
            }
        }
    }
