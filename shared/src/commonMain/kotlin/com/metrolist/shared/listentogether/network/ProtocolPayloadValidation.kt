package com.metrolist.shared.listentogether.network

internal fun payloadMatchesMessageType(
    msgType: String,
    payload: Any?,
): Boolean =
    when (msgType) {
        MessageTypes.LEAVE_ROOM, MessageTypes.REQUEST_SYNC -> payload == null
        MessageTypes.CREATE_ROOM -> payload is CreateRoomPayload
        MessageTypes.JOIN_ROOM -> payload is JoinRoomPayload
        MessageTypes.APPROVE_JOIN -> payload is ApproveJoinPayload
        MessageTypes.REJECT_JOIN -> payload is RejectJoinPayload
        MessageTypes.PLAYBACK_ACTION, MessageTypes.SYNC_PLAYBACK -> payload is PlaybackActionPayload
        MessageTypes.BUFFER_READY -> payload is BufferReadyPayload
        MessageTypes.KICK_USER -> payload is KickUserPayload
        MessageTypes.TRANSFER_HOST -> payload is TransferHostPayload
        MessageTypes.PING -> payload is PingPayload
        MessageTypes.RECONNECT -> payload is ReconnectPayload
        MessageTypes.SUGGEST_TRACK -> payload is SuggestTrackPayload
        MessageTypes.APPROVE_SUGGESTION -> payload is ApproveSuggestionPayload
        MessageTypes.REJECT_SUGGESTION -> payload is RejectSuggestionPayload
        MessageTypes.CLIENT_CAPABILITIES -> payload is ClientCapabilities
        MessageTypes.ROOM_CREATED -> payload is RoomCreatedPayload
        MessageTypes.JOIN_REQUEST -> payload is JoinRequestPayload
        MessageTypes.JOIN_APPROVED -> payload is JoinApprovedPayload
        MessageTypes.JOIN_REJECTED -> payload is JoinRejectedPayload
        MessageTypes.USER_JOINED -> payload is UserJoinedPayload
        MessageTypes.USER_LEFT -> payload is UserLeftPayload
        MessageTypes.BUFFER_WAIT -> payload is BufferWaitPayload
        MessageTypes.BUFFER_COMPLETE -> payload is BufferCompletePayload
        MessageTypes.ERROR -> payload is ErrorPayload
        MessageTypes.PONG -> payload is PongPayload
        MessageTypes.HOST_CHANGED -> payload is HostChangedPayload
        MessageTypes.KICKED -> payload is KickedPayload
        MessageTypes.SYNC_STATE -> payload is SyncStatePayload
        MessageTypes.RECONNECTED -> payload is ReconnectedPayload
        MessageTypes.USER_RECONNECTED -> payload is UserReconnectedPayload
        MessageTypes.USER_DISCONNECTED -> payload is UserDisconnectedPayload
        MessageTypes.SUGGESTION_RECEIVED -> payload is SuggestionReceivedPayload
        MessageTypes.SUGGESTION_APPROVED -> payload is SuggestionApprovedPayload
        MessageTypes.SUGGESTION_REJECTED -> payload is SuggestionRejectedPayload
        MessageTypes.SERVER_CAPABILITIES -> payload is ServerCapabilities
        MessageTypes.ACK -> payload == null
        MessageTypes.SERVER_RESTARTING -> payload is ServerRestartingPayload
        MessageTypes.SUGGESTION_SUBMITTED -> payload is SuggestionSubmittedPayload
        else -> false
    }

internal val knownMessageTypes =
    setOf(
        MessageTypes.CREATE_ROOM,
        MessageTypes.JOIN_ROOM,
        MessageTypes.LEAVE_ROOM,
        MessageTypes.APPROVE_JOIN,
        MessageTypes.REJECT_JOIN,
        MessageTypes.PLAYBACK_ACTION,
        MessageTypes.BUFFER_READY,
        MessageTypes.KICK_USER,
        MessageTypes.TRANSFER_HOST,
        MessageTypes.PING,
        MessageTypes.REQUEST_SYNC,
        MessageTypes.RECONNECT,
        MessageTypes.SUGGEST_TRACK,
        MessageTypes.APPROVE_SUGGESTION,
        MessageTypes.REJECT_SUGGESTION,
        MessageTypes.ROOM_CREATED,
        MessageTypes.JOIN_REQUEST,
        MessageTypes.JOIN_APPROVED,
        MessageTypes.JOIN_REJECTED,
        MessageTypes.USER_JOINED,
        MessageTypes.USER_LEFT,
        MessageTypes.SYNC_PLAYBACK,
        MessageTypes.BUFFER_WAIT,
        MessageTypes.BUFFER_COMPLETE,
        MessageTypes.ERROR,
        MessageTypes.PONG,
        MessageTypes.HOST_CHANGED,
        MessageTypes.KICKED,
        MessageTypes.SYNC_STATE,
        MessageTypes.RECONNECTED,
        MessageTypes.USER_RECONNECTED,
        MessageTypes.USER_DISCONNECTED,
        MessageTypes.SUGGESTION_RECEIVED,
        MessageTypes.SUGGESTION_APPROVED,
        MessageTypes.SUGGESTION_REJECTED,
        MessageTypes.CLIENT_CAPABILITIES,
        MessageTypes.SERVER_CAPABILITIES,
        MessageTypes.ACK,
        MessageTypes.SERVER_RESTARTING,
        MessageTypes.SUGGESTION_SUBMITTED,
    )

internal val payloadlessMessageTypes = setOf(MessageTypes.LEAVE_ROOM, MessageTypes.REQUEST_SYNC, MessageTypes.ACK)
