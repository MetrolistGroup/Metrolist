/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.listentogether

import com.google.protobuf.MessageLite
import com.metrolist.music.listentogether.proto.Listentogether
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Codec for encoding and decoding messages using Protocol Buffers
 */
class MessageCodec(
    var compressionEnabled: Boolean = false
) {
    companion object {
        private const val TAG = "MessageCodec"
        private const val COMPRESSION_THRESHOLD = 100 // Only compress if > 100 bytes
    }
    
    /**
     * Encode a message using Protocol Buffers
     */
    fun encode(msgType: String, payload: Any?): ByteArray {
        return encodeProtobuf(msgType, payload)
    }
    
    /**
     * Decode a protobuf message
     */
    fun decode(data: ByteArray): Pair<String, ByteArray> {
        return decodeProtobuf(data)
    }
    
    /**
     * Encode message using Protocol Buffers
     */
    private fun encodeProtobuf(msgType: String, payload: Any?): ByteArray {
        var payloadBytes = byteArrayOf()
        var compressed = false
        
        if (payload != null) {
            val protoMsg = toProtoMessage(payload)
            payloadBytes = protoMsg.toByteArray()
            
            // Compress if enabled and payload is large enough
            if (compressionEnabled && payloadBytes.size > COMPRESSION_THRESHOLD) {
                val compressedBytes = compressData(payloadBytes)
                if (compressedBytes.size < payloadBytes.size) {
                    payloadBytes = compressedBytes
                    compressed = true
                }
            }
        }
        
        val envelope = Listentogether.Envelope.newBuilder()
            .setType(msgType)
            .setPayload(com.google.protobuf.ByteString.copyFrom(payloadBytes))
            .setCompressed(compressed)
            .build()
        
        return envelope.toByteArray()
    }
    
    /**
     * Decode protobuf message
     */
    private fun decodeProtobuf(data: ByteArray): Pair<String, ByteArray> {
        val envelope = Listentogether.Envelope.parseFrom(data)
        
        var payloadBytes = envelope.payload.toByteArray()
        
        if (envelope.compressed) {
            payloadBytes = decompressData(payloadBytes) ?: payloadBytes
        }
        
        return Pair(envelope.type, payloadBytes)
    }
    
    /**
     * Compress data using GZIP
     */
    private fun compressData(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(data)
        }
        return outputStream.toByteArray()
    }
    
    /**
     * Decompress GZIP data
     */
    private fun decompressData(data: ByteArray): ByteArray? {
        return try {
            val inputStream = ByteArrayInputStream(data)
            GZIPInputStream(inputStream).use { gzip ->
                gzip.readBytes()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to decompress data")
            null
        }
    }
    
    /**
     * Convert Kotlin objects to protobuf messages
     */
    private fun toProtoMessage(payload: Any): MessageLite {
        return when (payload) {
            is CreateRoomPayload -> Listentogether.CreateRoomPayload.newBuilder()
                .setUsername(payload.username)
                .build()
            is JoinRoomPayload -> Listentogether.JoinRoomPayload.newBuilder()
                .setRoomCode(payload.roomCode)
                .setUsername(payload.username)
                .build()
            is ApproveJoinPayload -> Listentogether.ApproveJoinPayload.newBuilder()
                .setUserId(payload.userId)
                .build()
            is RejectJoinPayload -> Listentogether.RejectJoinPayload.newBuilder()
                .setUserId(payload.userId)
                .setReason(payload.reason ?: "")
                .build()
            is PlaybackActionPayload -> {
                val builder = Listentogether.PlaybackActionPayload.newBuilder()
                    .setAction(payload.action)
                    .setPosition(payload.position ?: 0)
                    .setInsertNext(payload.insertNext ?: false)
                    .setVolume(payload.volume ?: 1f)
                    .setServerTime(payload.serverTime ?: 0)
                    .setRevision(payload.revision)
                    .setCapturedAtServerTime(payload.capturedAtServerTime ?: 0)
                
                payload.trackId?.let { builder.setTrackId(it) }
                payload.trackInfo?.let { builder.setTrackInfo(trackInfoToProto(it)) }
                payload.queueTitle?.let { builder.setQueueTitle(it) }
                payload.queue?.forEach { track ->
                    builder.addQueue(trackInfoToProto(track))
                }
                
                builder.build()
            }
            is PingPayload -> Listentogether.PingPayload.newBuilder()
                .setClientTime(payload.clientTime)
                .setSequence(payload.sequence)
                .build()
            is BufferReadyPayload -> Listentogether.BufferReadyPayload.newBuilder()
                .setTrackId(payload.trackId)
                .build()
            is KickUserPayload -> Listentogether.KickUserPayload.newBuilder()
                .setUserId(payload.userId)
                .setReason(payload.reason ?: "")
                .build()
            is SuggestTrackPayload -> {
                val builder = Listentogether.SuggestTrackPayload.newBuilder()
                payload.trackInfo.let { builder.setTrackInfo(trackInfoToProto(it)) }
                builder.build()
            }
            is ApproveSuggestionPayload -> Listentogether.ApproveSuggestionPayload.newBuilder()
                .setSuggestionId(payload.suggestionId)
                .build()
            is RejectSuggestionPayload -> Listentogether.RejectSuggestionPayload.newBuilder()
                .setSuggestionId(payload.suggestionId)
                .setReason(payload.reason ?: "")
                .build()
            is ReconnectPayload -> Listentogether.ReconnectPayload.newBuilder()
                .setSessionToken(payload.sessionToken)
                .build()
            is TransferHostPayload -> Listentogether.TransferHostPayload.newBuilder()
                .setNewHostId(payload.newHostId)
                .build()
            else -> throw IllegalArgumentException("Unsupported payload type: ${payload::class.simpleName}")
        }
    }
    
    /**
     * Decode protobuf payload to Kotlin objects
     */
    fun decodePayload(msgType: String, payloadBytes: ByteArray): Any? {
        if (payloadBytes.isEmpty()) return null
        
        return decodeProtobufPayload(msgType, payloadBytes)
    }
    
    /**
     * Decode protobuf payload
     */
    private fun decodeProtobufPayload(msgType: String, payloadBytes: ByteArray): Any? {
        return when (msgType) {
            MessageTypes.ROOM_CREATED -> {
                val pb = Listentogether.RoomCreatedPayload.parseFrom(payloadBytes)
                RoomCreatedPayload(pb.roomCode, pb.userId, pb.sessionToken)
            }
            MessageTypes.JOIN_REQUEST -> {
                val pb = Listentogether.JoinRequestPayload.parseFrom(payloadBytes)
                JoinRequestPayload(pb.userId, pb.username)
            }
            MessageTypes.JOIN_APPROVED -> {
                val pb = Listentogether.JoinApprovedPayload.parseFrom(payloadBytes)
                JoinApprovedPayload(
                    pb.roomCode,
                    pb.userId,
                    pb.sessionToken,
                    protoToRoomState(pb.state)
                )
            }
            MessageTypes.JOIN_REJECTED -> {
                val pb = Listentogether.JoinRejectedPayload.parseFrom(payloadBytes)
                JoinRejectedPayload(pb.reason)
            }
            MessageTypes.USER_JOINED -> {
                val pb = Listentogether.UserJoinedPayload.parseFrom(payloadBytes)
                UserJoinedPayload(pb.userId, pb.username)
            }
            MessageTypes.USER_LEFT -> {
                val pb = Listentogether.UserLeftPayload.parseFrom(payloadBytes)
                UserLeftPayload(pb.userId, pb.username)
            }
            MessageTypes.SYNC_PLAYBACK -> {
                val pb = Listentogether.PlaybackActionPayload.parseFrom(payloadBytes)
                val positionForAction =
                    pb.position.takeIf {
                        it != 0L ||
                            pb.action == PlaybackActions.PLAY ||
                            pb.action == PlaybackActions.PAUSE ||
                            pb.action == PlaybackActions.SEEK
                    }
                PlaybackActionPayload(
                    action = pb.action,
                    trackId = pb.trackId.takeIf { it.isNotEmpty() },
                    position = positionForAction,
                    trackInfo = pb.takeIf { it.hasTrackInfo() }?.trackInfo?.let { protoToTrackInfo(it) },
                    insertNext = pb.insertNext.takeIf { it },
                    queue = pb.queueList.map { protoToTrackInfo(it) },
                    queueTitle = pb.queueTitle.takeIf { it.isNotEmpty() },
                    volume = pb.volume.takeIf { pb.action == PlaybackActions.SET_VOLUME },
                    serverTime = pb.serverTime.takeIf { it > 0 },
                    revision = pb.revision,
                    capturedAtServerTime = pb.capturedAtServerTime.takeIf { it > 0 },
                )
            }
            MessageTypes.BUFFER_WAIT -> {
                val pb = Listentogether.BufferWaitPayload.parseFrom(payloadBytes)
                BufferWaitPayload(pb.trackId, pb.waitingForList)
            }
            MessageTypes.BUFFER_COMPLETE -> {
                val pb = Listentogether.BufferCompletePayload.parseFrom(payloadBytes)
                BufferCompletePayload(pb.trackId)
            }
            MessageTypes.ERROR -> {
                val pb = Listentogether.ErrorPayload.parseFrom(payloadBytes)
                ErrorPayload(pb.code, pb.message)
            }
            MessageTypes.HOST_CHANGED -> {
                val pb = Listentogether.HostChangedPayload.parseFrom(payloadBytes)
                HostChangedPayload(pb.newHostId, pb.newHostName)
            }
            MessageTypes.KICKED -> {
                val pb = Listentogether.KickedPayload.parseFrom(payloadBytes)
                KickedPayload(pb.reason)
            }
            MessageTypes.SYNC_STATE -> {
                val pb = Listentogether.SyncStatePayload.parseFrom(payloadBytes)
                SyncStatePayload(
                    currentTrack = pb.takeIf { it.hasCurrentTrack() }?.currentTrack?.let { protoToTrackInfo(it) },
                    isPlaying = pb.isPlaying,
                    position = pb.position,
                    lastUpdate = pb.lastUpdate,
                    queue = pb.queueList.map { protoToTrackInfo(it) },
                    volume = pb.volume,
                    revision = pb.revision,
                )
            }
            MessageTypes.PONG -> {
                val pb = Listentogether.PongPayload.parseFrom(payloadBytes)
                PongPayload(pb.clientTime, pb.serverReceiveTime, pb.serverSendTime, pb.sequence)
            }
            MessageTypes.RECONNECTED -> {
                val pb = Listentogether.ReconnectedPayload.parseFrom(payloadBytes)
                ReconnectedPayload(
                    pb.roomCode,
                    pb.userId,
                    protoToRoomState(pb.state),
                    pb.isHost
                )
            }
            MessageTypes.USER_RECONNECTED -> {
                val pb = Listentogether.UserReconnectedPayload.parseFrom(payloadBytes)
                UserReconnectedPayload(pb.userId, pb.username)
            }
            MessageTypes.USER_DISCONNECTED -> {
                val pb = Listentogether.UserDisconnectedPayload.parseFrom(payloadBytes)
                UserDisconnectedPayload(pb.userId, pb.username)
            }
            MessageTypes.SUGGESTION_RECEIVED -> {
                val pb = Listentogether.SuggestionReceivedPayload.parseFrom(payloadBytes)
                SuggestionReceivedPayload(
                    pb.suggestionId,
                    pb.fromUserId,
                    pb.fromUsername,
                    protoToTrackInfo(pb.trackInfo)
                )
            }
            MessageTypes.SUGGESTION_APPROVED -> {
                val pb = Listentogether.SuggestionApprovedPayload.parseFrom(payloadBytes)
                SuggestionApprovedPayload(
                    pb.suggestionId,
                    protoToTrackInfo(pb.trackInfo)
                )
            }
            MessageTypes.SUGGESTION_REJECTED -> {
                val pb = Listentogether.SuggestionRejectedPayload.parseFrom(payloadBytes)
                SuggestionRejectedPayload(pb.suggestionId, pb.reason.takeIf { it.isNotEmpty() })
            }
            else -> null
        }
    }
    
    // Helper conversion functions
    
    private fun trackInfoToProto(track: TrackInfo): Listentogether.TrackInfo {
        return Listentogether.TrackInfo.newBuilder()
            .setId(track.id)
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbum(track.album ?: "")
            .setDuration(track.duration)
            .setThumbnail(track.thumbnail ?: "")
            .setSuggestedBy(track.suggestedBy ?: "")
            .build()
    }
    
    private fun protoToTrackInfo(proto: Listentogether.TrackInfo): TrackInfo {
        return TrackInfo(
            id = proto.id,
            title = proto.title,
            artist = proto.artist,
            album = proto.album.takeIf { it.isNotEmpty() },
            duration = proto.duration,
            thumbnail = proto.thumbnail.takeIf { it.isNotEmpty() },
            suggestedBy = proto.suggestedBy.takeIf { it.isNotEmpty() }
        )
    }
    
    private fun protoToUserInfo(proto: Listentogether.UserInfo): UserInfo {
        return UserInfo(
            userId = proto.userId,
            username = proto.username,
            isHost = proto.isHost,
            isConnected = proto.isConnected
        )
    }
    
    private fun protoToRoomState(proto: Listentogether.RoomState): RoomState {
        return RoomState(
            roomCode = proto.roomCode,
            hostId = proto.hostId,
            users = proto.usersList.map { protoToUserInfo(it) },
            currentTrack = proto.takeIf { it.hasCurrentTrack() }?.currentTrack?.let { protoToTrackInfo(it) },
            isPlaying = proto.isPlaying,
            position = proto.position,
            lastUpdate = proto.lastUpdate,
            volume = proto.volume,
            queue = proto.queueList.map { protoToTrackInfo(it) },
            revision = proto.revision,
        )
    }
}
