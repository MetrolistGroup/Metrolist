package com.metrolist.innertube

import com.metrolist.innertube.models.response.PlayerResponse

expect object NewPipeExtractor {
    fun init()
    fun getSignatureTimestamp(videoId: String): Result<Int>
    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): String?
    fun newPipePlayer(videoId: String): List<Pair<Int, String>>
}
