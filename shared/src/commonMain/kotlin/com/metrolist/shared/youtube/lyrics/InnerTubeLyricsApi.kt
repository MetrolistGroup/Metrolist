package com.metrolist.shared.youtube.lyrics

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.bodyAsJsonObjectLimited
import com.metrolist.innertubex.fetchCaptionText
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.transcriptCues
import com.metrolist.shared.api.lyrics.YouTubeLyricsApi
import com.metrolist.shared.api.lyrics.YouTubeTranscriptCue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class InnerTubeLyricsApi(
    private val innerTube: InnerTube,
) : YouTubeLyricsApi {
    override suspend fun watchNext(videoId: String): JsonObject =
        innerTube.next(YouTubeClient.WEB_REMIX, videoId = videoId).bodyAsJsonObjectLimited(MAX_JSON_RESPONSE_BYTES)

    override suspend fun browse(browseId: String): JsonObject =
        innerTube.browse(YouTubeClient.WEB_REMIX, browseId = browseId).bodyAsJsonObjectLimited(MAX_JSON_RESPONSE_BYTES)

    override suspend fun browse(
        client: YouTubeClient,
        browseId: String,
    ): JsonObject = innerTube.browse(client, browseId = browseId).bodyAsJsonObjectLimited(MAX_JSON_RESPONSE_BYTES)

    override suspend fun player(videoId: String): JsonObject {
        val web = innerTube.player(YouTubeClient.WEB, videoId).bodyAsJsonObjectLimited(MAX_JSON_RESPONSE_BYTES)
        if (web.hasCaptionTracks()) return web
        // Signed out, WEB answers UNPLAYABLE without captions; IOS still lists the caption tracks.
        val ios = innerTube.player(YouTubeClient.IOS, videoId).bodyAsJsonObjectLimited(MAX_JSON_RESPONSE_BYTES)
        return if (ios.hasCaptionTracks()) ios else web
    }

    override suspend fun transcript(videoId: String): List<YouTubeTranscriptCue> =
        innerTube
            .transcriptCues(YouTubeClient.WEB, videoId)
            .map { cue ->
                YouTubeTranscriptCue(
                    text = cue.text,
                    startMs = cue.startMs,
                    durationMs = cue.durationMs,
                )
            }

    override suspend fun getText(url: String): String = innerTube.fetchCaptionText(url)

    private companion object {
        const val MAX_JSON_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}

internal fun JsonObject.hasCaptionTracks(): Boolean =
    ((this["captions"] as? JsonObject)?.get("playerCaptionsTracklistRenderer") as? JsonObject)
        ?.get("captionTracks")
        ?.let { it as? JsonArray }
        ?.isNotEmpty() == true
