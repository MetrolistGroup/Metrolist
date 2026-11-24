package com.metrolist.shared.recognition

import kotlinx.serialization.Serializable

@Serializable
data class RecognitionWidgetSnapshot(
    val trackId: String,
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
    val recognizedAtEpochMillis: Long,
    val entryPoint: String,
    val historySaved: Boolean,
)

object RecognitionWidgetTimeline {
    fun snapshot(
        payload: RecognitionPayload,
        entryPoint: RecognitionEntryPoint,
        historySaved: Boolean,
    ): RecognitionWidgetSnapshot =
        RecognitionWidgetSnapshot(
            trackId = payload.trackId,
            title = payload.title,
            artist = payload.artist,
            coverArtUrl = payload.coverArtUrlHq ?: payload.coverArtUrl,
            recognizedAtEpochMillis = payload.recognizedAtEpochMillis,
            entryPoint = entryPoint.rawValue,
            historySaved = historySaved,
        )

    fun userInfo(
        payload: RecognitionPayload,
        entryPoint: RecognitionEntryPoint,
        historySaved: Boolean,
    ): Map<Any?, Any> {
        val snapshot = snapshot(payload, entryPoint, historySaved)
        return mapOf<Any?, Any>(
            "payload" to RecognitionPayloadCodec.encode(payload),
            "snapshot" to RecognitionPayloadCodec.encodeSnapshot(snapshot),
            "command" to RecognitionCommand.RECOGNIZE.rawValue,
            "entryPoint" to snapshot.entryPoint,
            "historySaved" to snapshot.historySaved,
        )
    }
}
