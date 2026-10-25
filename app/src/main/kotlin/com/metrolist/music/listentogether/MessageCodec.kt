package com.metrolist.music.listentogether

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.metrolist.music.listentogether.proto.Listentogether
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class MessageCodec(
    var compressionEnabled: Boolean = false,
) {
    companion object {
        private const val COMPRESSION_THRESHOLD = 100
    }

    fun encode(
        messageType: String,
        payload: MessageLite?,
    ): ByteArray {
        var payloadBytes = payload?.toByteArray() ?: byteArrayOf()
        var compressed = false
        if (compressionEnabled && payloadBytes.size > COMPRESSION_THRESHOLD) {
            val compressedBytes = compress(payloadBytes)
            if (compressedBytes.size < payloadBytes.size) {
                payloadBytes = compressedBytes
                compressed = true
            }
        }

        return Listentogether.Envelope
            .newBuilder()
            .setType(messageType)
            .setPayload(ByteString.copyFrom(payloadBytes))
            .setCompressed(compressed)
            .build()
            .toByteArray()
    }

    fun decode(data: ByteArray): Pair<String, ByteArray> {
        val envelope = Listentogether.Envelope.parseFrom(data)
        val payload = envelope.payload.toByteArray()
        return envelope.type to if (envelope.compressed) decompress(payload) ?: payload else payload
    }

    fun decodePayload(
        messageType: String,
        payload: ByteArray,
    ): MessageLite? {
        if (payload.isEmpty()) return null
        return when (messageType) {
            MessageTypes.ROOM_CREATED -> RoomCreatedPayload.parseFrom(payload)
            MessageTypes.JOIN_REQUEST -> JoinRequestPayload.parseFrom(payload)
            MessageTypes.JOIN_APPROVED -> JoinApprovedPayload.parseFrom(payload)
            MessageTypes.JOIN_REJECTED -> JoinRejectedPayload.parseFrom(payload)
            MessageTypes.USER_JOINED -> UserJoinedPayload.parseFrom(payload)
            MessageTypes.USER_LEFT -> UserLeftPayload.parseFrom(payload)
            MessageTypes.SYNC_PLAYBACK -> PlaybackActionPayload.parseFrom(payload)
            MessageTypes.BUFFER_WAIT -> BufferWaitPayload.parseFrom(payload)
            MessageTypes.BUFFER_COMPLETE -> BufferCompletePayload.parseFrom(payload)
            MessageTypes.ERROR -> ErrorPayload.parseFrom(payload)
            MessageTypes.HOST_CHANGED -> HostChangedPayload.parseFrom(payload)
            MessageTypes.KICKED -> KickedPayload.parseFrom(payload)
            MessageTypes.SYNC_STATE -> SyncStatePayload.parseFrom(payload)
            MessageTypes.PONG -> PongPayload.parseFrom(payload)
            MessageTypes.RECONNECTED -> ReconnectedPayload.parseFrom(payload)
            MessageTypes.USER_RECONNECTED -> UserReconnectedPayload.parseFrom(payload)
            MessageTypes.USER_DISCONNECTED -> UserDisconnectedPayload.parseFrom(payload)
            MessageTypes.SUGGESTION_RECEIVED -> SuggestionReceivedPayload.parseFrom(payload)
            MessageTypes.SUGGESTION_APPROVED -> SuggestionApprovedPayload.parseFrom(payload)
            MessageTypes.SUGGESTION_REJECTED -> SuggestionRejectedPayload.parseFrom(payload)
            else -> null
        }
    }

    private fun compress(data: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(data) }
            output.toByteArray()
        }

    private fun decompress(data: ByteArray): ByteArray? =
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        } catch (error: Exception) {
            Timber.e(error, "Failed to decompress Listen Together payload")
            null
        }
}
