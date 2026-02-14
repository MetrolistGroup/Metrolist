package com.metrolist.innertube

import com.metrolist.innertube.models.response.PlayerResponse

actual object NewPipeExtractor {
    actual fun init() {
        // No-op for now on iOS
    }

    actual fun getSignatureTimestamp(videoId: String): Result<Int> {
        return Result.failure(Exception("Not implemented on iOS"))
    }

    actual fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): String? {
        return null
    }

    actual fun newPipePlayer(videoId: String): List<Pair<Int, String>> {
        return emptyList()
    }
}
