package com.metrolist.shared.listentogether.network

import okio.Buffer

class PortableMessageCodec(
    override var compressionEnabled: Boolean = false,
) : ListenTogetherMessageCodec {
    private companion object {
        const val MAX_ENVELOPE_BYTES = 256 * 1024
        const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    }

    override fun encode(
        msgType: String,
        payload: Any?,
        requestId: Long,
    ): ByteArray {
        require(msgType in knownMessageTypes) { "Unsupported Listen Together message type: $msgType" }
        require(payloadMatchesMessageType(msgType, payload)) { "Invalid Listen Together payload: $msgType" }
        var bytes = payload?.let(::encodePayload) ?: byteArrayOf()
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Listen Together payload exceeds the size limit" }
        var compressed = false
        if (compressionEnabled && bytes.size > 100) {
            val gzip = compressListenTogetherPayload(bytes)
            if (gzip.size < bytes.size) {
                bytes = gzip
                compressed = true
            }
        }
        return message {
            string(1, msgType)
            bytes(2, bytes)
            bool(3, compressed)
            long(4, requestId)
        }.also { require(it.size <= MAX_ENVELOPE_BYTES) { "Listen Together envelope exceeds the size limit" } }
    }

    override fun decodeEnvelope(data: ByteArray): ListenTogetherEnvelope {
        require(data.size <= MAX_ENVELOPE_BYTES) { "Listen Together envelope exceeds the size limit" }
        val envelope = ProtoFields(data)
        val type = envelope.string(1)
        require(type in knownMessageTypes) { "Unsupported Listen Together message type: $type" }
        val encoded = envelope.bytes(2)
        val payload = if (envelope.bool(3)) decompressListenTogetherPayload(encoded, MAX_PAYLOAD_BYTES) else encoded
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Listen Together payload exceeds the size limit" }
        return ListenTogetherEnvelope(type, payload, envelope.long(4))
    }

    override fun decodePayload(
        msgType: String,
        payloadBytes: ByteArray,
    ): Any? {
        require(msgType in knownMessageTypes) { "Unsupported Listen Together message type: $msgType" }
        require(payloadBytes.size <= MAX_PAYLOAD_BYTES) { "Listen Together payload exceeds the size limit" }
        if (payloadBytes.isEmpty() && msgType in payloadlessMessageTypes) return null
        val f = ProtoFields(payloadBytes)
        return when (msgType) {
            MessageTypes.CREATE_ROOM -> {
                CreateRoomPayload(f.string(1))
            }

            MessageTypes.JOIN_ROOM -> {
                JoinRoomPayload(f.string(1), f.string(2))
            }

            MessageTypes.APPROVE_JOIN -> {
                ApproveJoinPayload(f.string(1))
            }

            MessageTypes.REJECT_JOIN -> {
                RejectJoinPayload(f.string(1), f.optionalString(2))
            }

            MessageTypes.PLAYBACK_ACTION, MessageTypes.SYNC_PLAYBACK -> {
                PlaybackActionPayload(
                    action = f.string(1),
                    trackId = f.optionalString(2),
                    position = f.long(3),
                    trackInfo = f.nested(4)?.track(),
                    insertNext = f.bool(5).takeIf { it },
                    queue = f.repeated(6).map { it.track() },
                    queueTitle = f.optionalString(7),
                    volume = f.float(8),
                    serverTime = f.long(9).takeIf { it > 0 },
                    revision = f.long(10),
                    capturedAtServerTime = f.long(11).takeIf { it > 0 },
                    replaceQueue = f.bool(12),
                    originUserId = f.optionalString(13),
                    hostSequence = f.long(14),
                )
            }

            MessageTypes.BUFFER_READY -> {
                BufferReadyPayload(f.string(1), f.long(2))
            }

            MessageTypes.KICK_USER -> {
                KickUserPayload(f.string(1), f.optionalString(2))
            }

            MessageTypes.TRANSFER_HOST -> {
                TransferHostPayload(f.string(1))
            }

            MessageTypes.PING -> {
                PingPayload(f.long(1), f.long(2), f.bool(3), f.optionalString(4), f.long(5), f.long(6), f.bool(7), f.long(8))
            }

            MessageTypes.RECONNECT -> {
                ReconnectPayload(f.string(1))
            }

            MessageTypes.SUGGEST_TRACK -> {
                SuggestTrackPayload(f.nested(1).orEmpty().track())
            }

            MessageTypes.APPROVE_SUGGESTION -> {
                ApproveSuggestionPayload(f.string(1))
            }

            MessageTypes.REJECT_SUGGESTION -> {
                RejectSuggestionPayload(f.string(1), f.optionalString(2))
            }

            MessageTypes.ROOM_CREATED -> {
                RoomCreatedPayload(f.string(1), f.string(2), f.string(3)).also {
                    require(
                        it.roomCode.isNotBlank() && it.userId.isNotBlank() && it.sessionToken.isNotBlank(),
                    ) { "Missing room identifiers" }
                }
            }

            MessageTypes.JOIN_REQUEST -> {
                JoinRequestPayload(f.string(1), f.string(2))
            }

            MessageTypes.JOIN_APPROVED -> {
                JoinApprovedPayload(f.string(1), f.string(2), f.string(3), requireNotNull(f.nested(4)).room()).also {
                    require(it.sessionToken.isNotBlank()) { "Missing room session token" }
                    validateRoomResponse(it.roomCode, it.userId, it.state)
                }
            }

            MessageTypes.JOIN_REJECTED -> {
                JoinRejectedPayload(f.string(1))
            }

            MessageTypes.USER_JOINED -> {
                UserJoinedPayload(f.string(1), f.string(2))
            }

            MessageTypes.USER_LEFT -> {
                UserLeftPayload(f.string(1), f.string(2))
            }

            MessageTypes.BUFFER_WAIT -> {
                BufferWaitPayload(f.string(1), f.strings(2))
            }

            MessageTypes.BUFFER_COMPLETE -> {
                BufferCompletePayload(f.string(1))
            }

            MessageTypes.ERROR -> {
                ErrorPayload(f.string(1), f.string(2))
            }

            MessageTypes.PONG -> {
                PongPayload(f.long(1), f.long(2), f.long(3), f.long(4), f.bool(5), f.long(6), f.bool(7))
            }

            MessageTypes.HOST_CHANGED -> {
                HostChangedPayload(f.string(1), f.string(2))
            }

            MessageTypes.KICKED -> {
                KickedPayload(f.string(1))
            }

            MessageTypes.SYNC_STATE -> {
                SyncStatePayload(
                    f.nested(1)?.track(),
                    f.bool(2),
                    f.long(3),
                    f.long(4),
                    f.repeated(5).map { it.track() },
                    f.float(6),
                    f.long(7),
                    f.optionalString(8),
                )
            }

            MessageTypes.RECONNECTED -> {
                ReconnectedPayload(f.string(1), f.string(2), requireNotNull(f.nested(3)).room(), f.bool(4)).also {
                    validateRoomResponse(it.roomCode, it.userId, it.state)
                }
            }

            MessageTypes.USER_RECONNECTED -> {
                UserReconnectedPayload(f.string(1), f.string(2))
            }

            MessageTypes.USER_DISCONNECTED -> {
                UserDisconnectedPayload(f.string(1), f.string(2))
            }

            MessageTypes.SUGGESTION_RECEIVED -> {
                SuggestionReceivedPayload(f.string(1), f.string(2), f.string(3), f.nested(4).orEmpty().track())
            }

            MessageTypes.SUGGESTION_APPROVED -> {
                SuggestionApprovedPayload(f.string(1), f.nested(2).orEmpty().track())
            }

            MessageTypes.SUGGESTION_REJECTED -> {
                SuggestionRejectedPayload(f.string(1), f.optionalString(2))
            }

            MessageTypes.CLIENT_CAPABILITIES -> {
                ClientCapabilities(f.bool(1), f.bool(2), f.string(3), f.strings(4))
            }

            MessageTypes.SERVER_CAPABILITIES -> {
                ServerCapabilities(f.bool(1), f.bool(2), f.string(3), f.strings(4), f.bool(5))
            }

            MessageTypes.SERVER_RESTARTING -> {
                ServerRestartingPayload(f.long(1), f.optionalString(2))
            }

            MessageTypes.SUGGESTION_SUBMITTED -> {
                SuggestionSubmittedPayload(f.string(1), f.nested(2).orEmpty().track())
            }

            else -> {
                error("Payload not supported: $msgType")
            }
        }
    }

    private fun encodePayload(payload: Any): ByteArray =
        message {
            when (payload) {
                is CreateRoomPayload -> {
                    string(1, payload.username)
                }

                is JoinRoomPayload -> {
                    string(1, payload.roomCode)
                    string(2, payload.username)
                }

                is ApproveJoinPayload -> {
                    string(1, payload.userId)
                }

                is RejectJoinPayload -> {
                    string(1, payload.userId)
                    string(2, payload.reason)
                }

                is PlaybackActionPayload -> {
                    string(1, payload.action)
                    string(2, payload.trackId)
                    long(3, payload.position ?: 0)
                    payload.trackInfo?.let { nested(4, encodeTrack(it)) }
                    bool(5, payload.insertNext == true)
                    payload.queue?.forEach { nested(6, encodeTrack(it)) }
                    string(7, payload.queueTitle)
                    float(8, payload.volume ?: 1f)
                    long(9, payload.serverTime ?: 0)
                    long(10, payload.revision)
                    long(11, payload.capturedAtServerTime ?: 0)
                    bool(12, payload.replaceQueue)
                    string(13, payload.originUserId)
                    long(14, payload.hostSequence)
                }

                is PingPayload -> {
                    long(1, payload.clientTime)
                    long(2, payload.sequence)
                    bool(3, payload.hasPosition)
                    string(4, payload.trackId)
                    long(5, payload.position)
                    long(6, payload.positionCapturedAtServerTime)
                    bool(7, payload.hasStateHash)
                    long(8, payload.stateHash)
                }

                is BufferReadyPayload -> {
                    string(1, payload.trackId)
                    long(2, payload.trackRevision)
                }

                is KickUserPayload -> {
                    string(1, payload.userId)
                    string(2, payload.reason)
                }

                is TransferHostPayload -> {
                    string(1, payload.newHostId)
                }

                is SuggestTrackPayload -> {
                    nested(1, encodeTrack(payload.trackInfo))
                }

                is ApproveSuggestionPayload -> {
                    string(1, payload.suggestionId)
                }

                is RejectSuggestionPayload -> {
                    string(1, payload.suggestionId)
                    string(2, payload.reason)
                }

                is ReconnectPayload -> {
                    string(1, payload.sessionToken)
                }

                is ClientCapabilities -> {
                    bool(1, payload.supportsProtobuf)
                    bool(2, payload.supportsCompression)
                    string(3, payload.clientVersion)
                    payload.features.forEach { string(4, it) }
                }

                is ServerCapabilities -> {
                    bool(1, payload.supportsProtobuf)
                    bool(2, payload.supportsCompression)
                    string(3, payload.serverVersion)
                    payload.features.forEach { string(4, it) }
                    bool(5, payload.canHost)
                }

                is ServerRestartingPayload -> {
                    long(1, payload.etaMs)
                    string(2, payload.reason)
                }

                is SuggestionSubmittedPayload -> {
                    string(1, payload.suggestionId)
                    nested(2, encodeTrack(payload.trackInfo))
                }

                is RoomCreatedPayload -> {
                    string(1, payload.roomCode)
                    string(2, payload.userId)
                    string(3, payload.sessionToken)
                }

                is JoinRequestPayload -> {
                    string(1, payload.userId)
                    string(2, payload.username)
                }

                is JoinApprovedPayload -> {
                    string(1, payload.roomCode)
                    string(2, payload.userId)
                    string(3, payload.sessionToken)
                    nested(4, encodeRoom(payload.state))
                }

                is JoinRejectedPayload -> {
                    string(1, payload.reason)
                }

                is UserJoinedPayload -> {
                    string(1, payload.userId)
                    string(2, payload.username)
                }

                is UserLeftPayload -> {
                    string(1, payload.userId)
                    string(2, payload.username)
                }

                is BufferWaitPayload -> {
                    string(1, payload.trackId)
                    payload.waitingFor.forEach { nested(2, it.encodeToByteArray()) }
                }

                is BufferCompletePayload -> {
                    string(1, payload.trackId)
                }

                is ErrorPayload -> {
                    string(1, payload.code)
                    string(2, payload.message)
                }

                is PongPayload -> {
                    long(1, payload.clientTime)
                    long(2, payload.serverReceiveTime)
                    long(3, payload.serverSendTime)
                    long(4, payload.sequence)
                    bool(5, payload.driftValid)
                    long(6, payload.driftMs)
                    bool(7, payload.stateMismatch)
                }

                is HostChangedPayload -> {
                    string(1, payload.newHostId)
                    string(2, payload.newHostName)
                }

                is KickedPayload -> {
                    string(1, payload.reason)
                }

                is SyncStatePayload -> {
                    payload.currentTrack?.let { nested(1, encodeTrack(it)) }
                    bool(2, payload.isPlaying)
                    long(3, payload.position)
                    long(4, payload.lastUpdate)
                    payload.queue?.forEach { nested(5, encodeTrack(it)) }
                    float(6, payload.volume ?: 0f)
                    long(7, payload.revision)
                    string(8, payload.queueTitle)
                }

                is ReconnectedPayload -> {
                    string(1, payload.roomCode)
                    string(2, payload.userId)
                    nested(3, encodeRoom(payload.state))
                    bool(4, payload.isHost)
                }

                is UserReconnectedPayload -> {
                    string(1, payload.userId)
                    string(2, payload.username)
                }

                is UserDisconnectedPayload -> {
                    string(1, payload.userId)
                    string(2, payload.username)
                }

                is SuggestionReceivedPayload -> {
                    string(1, payload.suggestionId)
                    string(2, payload.fromUserId)
                    string(3, payload.fromUsername)
                    nested(4, encodeTrack(payload.trackInfo))
                }

                is SuggestionApprovedPayload -> {
                    string(1, payload.suggestionId)
                    nested(2, encodeTrack(payload.trackInfo))
                }

                is SuggestionRejectedPayload -> {
                    string(1, payload.suggestionId)
                    string(2, payload.reason)
                }

                else -> {
                    error("Unsupported payload")
                }
            }
        }

    private fun encodeTrack(track: TrackInfo) =
        message {
            string(1, track.id)
            string(2, track.title)
            string(3, track.artist)
            string(4, track.album)
            long(5, track.duration)
            string(6, track.thumbnail)
            string(7, track.suggestedBy)
        }

    private fun encodeRoom(room: RoomState) =
        message {
            string(1, room.roomCode)
            string(2, room.hostId)
            room.users.forEach { user ->
                nested(
                    3,
                    message {
                        string(1, user.userId)
                        string(2, user.username)
                        bool(3, user.isHost)
                        bool(4, user.isConnected)
                    },
                )
            }
            room.currentTrack?.let { nested(4, encodeTrack(it)) }
            bool(5, room.isPlaying)
            long(6, room.position)
            long(7, room.lastUpdate)
            float(8, room.volume)
            room.queue.forEach { nested(9, encodeTrack(it)) }
            long(10, room.revision)
            string(11, room.queueTitle)
        }

    private fun ProtoFields.track() =
        TrackInfo(string(1), string(2), string(3), optionalString(4), long(5), optionalString(6), optionalString(7))

    private fun ProtoFields.room() =
        RoomState(
            string(1),
            string(2),
            repeated(3).map { UserInfo(it.string(1), it.string(2), it.bool(3), it.bool(4)) },
            nested(4)?.track(),
            bool(5),
            long(6),
            long(7),
            float(8),
            repeated(9).map { it.track() },
            long(10),
            optionalString(11),
        )
}

private fun message(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).buffer.readByteArray()

private class ProtoWriter {
    val buffer = Buffer()

    fun long(
        field: Int,
        value: Long,
    ) {
        if (value == 0L) return
        varint((field shl 3).toLong())
        varint(value)
    }

    fun bool(
        field: Int,
        value: Boolean,
    ) = long(field, if (value) 1 else 0)

    fun float(
        field: Int,
        value: Float,
    ) {
        if (value.toRawBits() == 0) return
        varint(((field shl 3) or 5).toLong())
        buffer.writeIntLe(value.toRawBits())
    }

    fun string(
        field: Int,
        value: String?,
    ) {
        if (!value.isNullOrEmpty()) nested(field, value.encodeToByteArray())
    }

    fun bytes(
        field: Int,
        value: ByteArray,
    ) {
        if (value.isNotEmpty()) nested(field, value)
    }

    fun nested(
        field: Int,
        value: ByteArray,
    ) {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        buffer.write(value)
    }

    private fun varint(value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) {
            buffer.writeByte((remaining.toInt() and 127) or 128)
            remaining = remaining ushr 7
        }
        buffer.writeByte(remaining.toInt())
    }
}

private class ProtoFields(
    data: ByteArray,
) {
    private data class Field(
        val wire: Int,
        val number: Long = 0,
        val bytes: ByteArray = byteArrayOf(),
    )

    private val fields = mutableMapOf<Int, MutableList<Field>>()

    init {
        val input = Buffer().write(data)
        var fieldCount = 0
        while (!input.exhausted()) {
            require(++fieldCount <= 16_384) { "Too many protobuf fields" }
            val tag = input.varint()
            require(tag in 1..0xffffffffL && tag ushr 3 != 0L) { "Invalid protobuf field tag" }
            val wire = (tag and 7).toInt()
            val field =
                when (wire) {
                    0 -> {
                        Field(wire, input.varint())
                    }

                    1 -> {
                        require(input.size >= 8) { "Truncated protobuf fixed64" }
                        Field(wire, input.readLongLe())
                    }

                    2 -> {
                        val length = input.varint()
                        require(length >= 0 && length <= input.size) { "Invalid protobuf field length" }
                        Field(wire, bytes = input.readByteArray(length))
                    }

                    5 -> {
                        require(input.size >= 4) { "Truncated protobuf fixed32" }
                        Field(wire, input.readIntLe().toLong())
                    }

                    else -> {
                        throw IllegalArgumentException("Unsupported protobuf wire type: $wire")
                    }
                }
            fields.getOrPut((tag ushr 3).toInt()) { mutableListOf() }.add(field)
        }
    }

    private fun field(
        id: Int,
        wire: Int,
    ): Field? = fields[id]?.lastOrNull()?.also { require(it.wire == wire) { "Invalid protobuf wire type" } }

    fun long(id: Int): Long = field(id, 0)?.number ?: 0L

    fun bool(id: Int): Boolean = long(id) != 0L

    fun float(id: Int): Float = Float.fromBits(field(id, 5)?.number?.toInt() ?: 0)

    fun bytes(id: Int): ByteArray = field(id, 2)?.bytes ?: byteArrayOf()

    fun string(id: Int): String = bytes(id).decodeToString(throwOnInvalidSequence = true)

    fun optionalString(id: Int): String? = string(id).takeIf { it.isNotEmpty() }

    fun strings(id: Int): List<String> = byteFields(id).map { it.decodeToString(throwOnInvalidSequence = true) }

    fun repeated(id: Int): List<ProtoFields> = byteFields(id).map(::ProtoFields)

    fun nested(id: Int): ProtoFields? =
        fields[id]?.let { ProtoFields(Buffer().apply { byteFields(id).forEach { write(it) } }.readByteArray()) }

    private fun byteFields(id: Int): List<ByteArray> =
        fields[id].orEmpty().map {
            require(it.wire == 2) { "Invalid protobuf wire type" }
            it.bytes
        }
}

private fun ProtoFields?.orEmpty(): ProtoFields = this ?: ProtoFields(byteArrayOf())

private fun Buffer.varint(): Long {
    var value = 0L
    for (index in 0..9) {
        require(!exhausted()) { "Truncated protobuf varint" }
        val byte = readByte().toInt() and 255
        require(index < 9 || byte <= 1) { "Overflowing protobuf varint" }
        value = value or ((byte and 127).toLong() shl (index * 7))
        if (byte and 128 == 0) return value
    }
    throw IllegalArgumentException("Invalid protobuf varint")
}
