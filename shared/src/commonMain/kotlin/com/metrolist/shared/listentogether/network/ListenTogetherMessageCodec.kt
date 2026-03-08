package com.metrolist.shared.listentogether.network

class ListenTogetherEnvelope(
    val type: String,
    val payload: ByteArray,
    val requestId: Long = 0L,
)

interface ListenTogetherMessageCodec {
    var compressionEnabled: Boolean

    fun encode(
        msgType: String,
        payload: Any?,
        requestId: Long = 0L,
    ): ByteArray

    fun decodeEnvelope(data: ByteArray): ListenTogetherEnvelope

    fun decode(data: ByteArray): Pair<String, ByteArray> = decodeEnvelope(data).let { it.type to it.payload }

    fun decodePayload(
        msgType: String,
        payloadBytes: ByteArray,
    ): Any?
}

internal expect fun createListenTogetherMessageCodec(): ListenTogetherMessageCodec

internal expect fun compressListenTogetherPayload(data: ByteArray): ByteArray

internal expect fun decompressListenTogetherPayload(
    data: ByteArray,
    limit: Int,
): ByteArray
