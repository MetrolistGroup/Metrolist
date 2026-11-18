package com.metrolist.shared.api

import com.metrolist.shared.api.YouTubeResponseParser.asArray
import com.metrolist.shared.api.YouTubeResponseParser.asObject
import com.metrolist.shared.api.YouTubeResponseParser.findFirstObject
import com.metrolist.shared.api.YouTubeResponseParser.primitiveText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.isLivePlayerResponse(fallbackDurationSeconds: Long? = null): Boolean {
    val details = this["videoDetails"].asObject()
    val duration =
        details
            ?.get("lengthSeconds")
            .primitiveText()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: fallbackDurationSeconds?.takeIf { it > 0L }
    val liveFlags =
        listOf(
            details?.get("isLive"),
            details?.get("isLiveNow"),
            findFirstObject("liveBroadcastDetails")?.get("isLiveNow"),
        ).mapNotNull { it?.jsonPrimitive?.booleanOrNull }
    val streamingData = this["streamingData"].asObject()
    val hlsOnly =
        streamingData?.get("hlsManifestUrl").primitiveText()?.isNotBlank() == true &&
            streamingData?.get("formats").asArray().isNullOrEmpty() &&
            streamingData?.get("adaptiveFormats").asArray().isNullOrEmpty()
    // isLiveContent also marks recordings and premieres; only current stream evidence counts.
    return true in liveFlags || (false !in liveFlags && duration == null && hlsOnly)
}
