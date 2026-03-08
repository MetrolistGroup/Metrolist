/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.listentogether.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object MessageTypes {
    const val CREATE_ROOM = "create_room"
    const val JOIN_ROOM = "join_room"
    const val LEAVE_ROOM = "leave_room"
    const val APPROVE_JOIN = "approve_join"
    const val REJECT_JOIN = "reject_join"
    const val PLAYBACK_ACTION = "playback_action"
    const val BUFFER_READY = "buffer_ready"
    const val KICK_USER = "kick_user"
    const val TRANSFER_HOST = "transfer_host"
    const val PING = "ping"
    const val REQUEST_SYNC = "request_sync"
    const val RECONNECT = "reconnect"
    const val SUGGEST_TRACK = "suggest_track"
    const val APPROVE_SUGGESTION = "approve_suggestion"
    const val REJECT_SUGGESTION = "reject_suggestion"
    const val CLIENT_CAPABILITIES = "client_capabilities"

    const val ROOM_CREATED = "room_created"
    const val JOIN_REQUEST = "join_request"
    const val JOIN_APPROVED = "join_approved"
    const val JOIN_REJECTED = "join_rejected"
    const val USER_JOINED = "user_joined"
    const val USER_LEFT = "user_left"
    const val SYNC_PLAYBACK = "sync_playback"
    const val BUFFER_WAIT = "buffer_wait"
    const val BUFFER_COMPLETE = "buffer_complete"
    const val ERROR = "error"
    const val PONG = "pong"
    const val HOST_CHANGED = "host_changed"
    const val KICKED = "kicked"
    const val SYNC_STATE = "sync_state"
    const val RECONNECTED = "reconnected"
    const val USER_RECONNECTED = "user_reconnected"
    const val USER_DISCONNECTED = "user_disconnected"
    const val SUGGESTION_RECEIVED = "suggestion_received"
    const val SUGGESTION_APPROVED = "suggestion_approved"
    const val SUGGESTION_REJECTED = "suggestion_rejected"
    const val SERVER_CAPABILITIES = "server_capabilities"
    const val ACK = "ack"
    const val SERVER_RESTARTING = "server_restarting"
    const val SUGGESTION_SUBMITTED = "suggestion_submitted"
}

object ProtocolFeatures {
    const val REQUEST_ID = "request_id"
    const val ORIGIN_TAG = "origin_tag"
    const val DRIFT_REPORT = "drift_report"
    const val DESYNC_CANARY = "desync_canary"
    const val SERVER_RESTARTING = "server_restarting"
    const val SUGGESTION_ACK = "suggestion_ack"
    const val REPLACE_QUEUE = "replace_queue"
    const val HOST_SEQUENCE = "host_sequence"
    const val TRACK_REVISION = "track_revision"

    val supported =
        listOf(
            REQUEST_ID,
            ORIGIN_TAG,
            DRIFT_REPORT,
            DESYNC_CANARY,
            SERVER_RESTARTING,
            SUGGESTION_ACK,
            REPLACE_QUEUE,
            HOST_SEQUENCE,
            TRACK_REVISION,
        )
}

object RejectionReasons {
    const val BLOCKED_BY_HOST = "blocked_by_host"
    const val USER_IS_BLOCKED = "user_is_blocked"
}

object PlaybackActions {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SEEK = "seek"
    const val SKIP_NEXT = "skip_next"
    const val SKIP_PREV = "skip_prev"
    const val CHANGE_TRACK = "change_track"
    const val QUEUE_ADD = "queue_add"
    const val QUEUE_REMOVE = "queue_remove"
    const val QUEUE_CLEAR = "queue_clear"
    const val SYNC_QUEUE = "sync_queue"
    const val SET_VOLUME = "set_volume"
}

@Serializable
data class TrackInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long, // milliseconds
    val thumbnail: String? = null,
    @SerialName("suggested_by") val suggestedBy: String? = null,
)

@Serializable
data class UserInfo(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("is_host") val isHost: Boolean,
    @SerialName("is_connected") val isConnected: Boolean = true,
)

@Serializable
data class RoomState(
    @SerialName("room_code") val roomCode: String,
    @SerialName("host_id") val hostId: String,
    val users: List<UserInfo>,
    @SerialName("current_track") val currentTrack: TrackInfo? = null,
    @SerialName("is_playing") val isPlaying: Boolean,
    val position: Long, // milliseconds
    @SerialName("last_update") val lastUpdate: Long, // unix timestamp ms
    val volume: Float = 1f,
    val queue: List<TrackInfo> = emptyList(),
    val revision: Long = 0L,
    @SerialName("queue_title") val queueTitle: String? = null,
)

@Serializable
data class CreateRoomPayload(
    val username: String,
)

@Serializable
data class JoinRoomPayload(
    @SerialName("room_code") val roomCode: String,
    val username: String,
)

@Serializable
data class ApproveJoinPayload(
    @SerialName("user_id") val userId: String,
)

@Serializable
data class RejectJoinPayload(
    @SerialName("user_id") val userId: String,
    val reason: String? = null,
)

@Serializable
data class PlaybackActionPayload(
    val action: String,
    @SerialName("track_id") val trackId: String? = null,
    val position: Long? = null, // milliseconds
    @SerialName("track_info") val trackInfo: TrackInfo? = null,
    @SerialName("insert_next") val insertNext: Boolean? = null,
    val queue: List<TrackInfo>? = null,
    @SerialName("queue_title") val queueTitle: String? = null,
    val volume: Float? = null,
    @SerialName("server_time") val serverTime: Long? = null,
    val revision: Long = 0L,
    @SerialName("captured_at_server_time") val capturedAtServerTime: Long? = null,
    @SerialName("replace_queue") val replaceQueue: Boolean = false,
    @SerialName("origin_user_id") val originUserId: String? = null,
    @SerialName("host_sequence") val hostSequence: Long = 0L,
)

@Serializable
data class PingPayload(
    @SerialName("client_time") val clientTime: Long,
    val sequence: Long,
    @SerialName("has_position") val hasPosition: Boolean = false,
    @SerialName("track_id") val trackId: String? = null,
    val position: Long = 0L,
    @SerialName("position_captured_at_server_time") val positionCapturedAtServerTime: Long = 0L,
    @SerialName("has_state_hash") val hasStateHash: Boolean = false,
    @SerialName("state_hash") val stateHash: Long = 0L,
)

@Serializable
data class PongPayload(
    @SerialName("client_time") val clientTime: Long,
    @SerialName("server_receive_time") val serverReceiveTime: Long,
    @SerialName("server_send_time") val serverSendTime: Long,
    val sequence: Long,
    @SerialName("drift_valid") val driftValid: Boolean = false,
    @SerialName("drift_ms") val driftMs: Long = 0L,
    @SerialName("state_mismatch") val stateMismatch: Boolean = false,
)

@Serializable
data class BufferReadyPayload(
    @SerialName("track_id") val trackId: String,
    @SerialName("track_revision") val trackRevision: Long = 0L,
)

@Serializable
data class KickUserPayload(
    @SerialName("user_id") val userId: String,
    val reason: String? = null,
)

@Serializable
data class TransferHostPayload(
    @SerialName("new_host_id") val newHostId: String,
)

@Serializable
data class SuggestTrackPayload(
    @SerialName("track_info") val trackInfo: TrackInfo,
)

@Serializable
data class SuggestionReceivedPayload(
    @SerialName("suggestion_id") val suggestionId: String,
    @SerialName("from_user_id") val fromUserId: String,
    @SerialName("from_username") val fromUsername: String,
    @SerialName("track_info") val trackInfo: TrackInfo,
)

@Serializable
data class ApproveSuggestionPayload(
    @SerialName("suggestion_id") val suggestionId: String,
)

@Serializable
data class RejectSuggestionPayload(
    @SerialName("suggestion_id") val suggestionId: String,
    val reason: String? = null,
)

@Serializable
data class SuggestionApprovedPayload(
    @SerialName("suggestion_id") val suggestionId: String,
    @SerialName("track_info") val trackInfo: TrackInfo,
)

@Serializable
data class SuggestionRejectedPayload(
    @SerialName("suggestion_id") val suggestionId: String,
    val reason: String? = null,
)

@Serializable
data class RoomCreatedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    @SerialName("session_token") val sessionToken: String,
)

@Serializable
data class JoinRequestPayload(
    @SerialName("user_id") val userId: String,
    val username: String,
)

@Serializable
data class JoinApprovedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    @SerialName("session_token") val sessionToken: String,
    val state: RoomState,
)

@Serializable
data class JoinRejectedPayload(
    val reason: String,
)

@Serializable
data class UserJoinedPayload(
    @SerialName("user_id") val userId: String,
    val username: String,
)

@Serializable
data class UserLeftPayload(
    @SerialName("user_id") val userId: String,
    val username: String,
)

@Serializable
data class BufferWaitPayload(
    @SerialName("track_id") val trackId: String,
    @SerialName("waiting_for") val waitingFor: List<String>,
)

@Serializable
data class BufferCompletePayload(
    @SerialName("track_id") val trackId: String,
)

@Serializable
data class ErrorPayload(
    val code: String,
    val message: String,
)

@Serializable
data class HostChangedPayload(
    @SerialName("new_host_id") val newHostId: String,
    @SerialName("new_host_name") val newHostName: String,
)

@Serializable
data class KickedPayload(
    val reason: String,
)

@Serializable
data class SyncStatePayload(
    @SerialName("current_track") val currentTrack: TrackInfo?,
    @SerialName("is_playing") val isPlaying: Boolean,
    val position: Long,
    @SerialName("last_update") val lastUpdate: Long,
    val queue: List<TrackInfo>? = null,
    val volume: Float? = null,
    val revision: Long = 0L,
    @SerialName("queue_title") val queueTitle: String? = null,
)

@Serializable
data class ReconnectPayload(
    @SerialName("session_token") val sessionToken: String,
)

@Serializable
data class ReconnectedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    val state: RoomState,
    @SerialName("is_host") val isHost: Boolean,
)

@Serializable
data class UserReconnectedPayload(
    @SerialName("user_id") val userId: String,
    val username: String,
)

@Serializable
data class UserDisconnectedPayload(
    @SerialName("user_id") val userId: String,
    val username: String,
)

@Serializable
data class ClientCapabilities(
    @SerialName("supports_protobuf") val supportsProtobuf: Boolean,
    @SerialName("supports_compression") val supportsCompression: Boolean,
    @SerialName("client_version") val clientVersion: String,
    val features: List<String> = emptyList(),
)

@Serializable
data class ServerCapabilities(
    @SerialName("supports_protobuf") val supportsProtobuf: Boolean,
    @SerialName("supports_compression") val supportsCompression: Boolean,
    @SerialName("server_version") val serverVersion: String,
    val features: List<String> = emptyList(),
    @SerialName("can_host") val canHost: Boolean = false,
)

@Serializable
data class ServerRestartingPayload(
    @SerialName("eta_ms") val etaMs: Long,
    val reason: String? = null,
)

@Serializable
data class SuggestionSubmittedPayload(
    @SerialName("suggestion_id") val suggestionId: String,
    @SerialName("track_info") val trackInfo: TrackInfo,
)

/** Server-compatible CRC-32 IEEE over the current and upcoming track IDs, each followed by a newline. */
fun roomStateHash(
    currentTrackId: String?,
    queue: List<TrackInfo>,
): Long {
    var crc = 0xFFFFFFFFL

    fun update(bytes: ByteArray) {
        for (byte in bytes) {
            crc = crc xor (byte.toLong() and 0xFF)
            repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
        }
    }
    val newline = byteArrayOf('\n'.code.toByte())
    currentTrackId?.let { update(it.encodeToByteArray()) }
    update(newline)
    queue.forEach {
        update(it.id.encodeToByteArray())
        update(newline)
    }
    return crc xor 0xFFFFFFFFL
}

internal fun validateRoomResponse(
    roomCode: String,
    userId: String,
    state: RoomState,
) {
    require(roomCode.isNotBlank() && userId.isNotBlank() && state.hostId.isNotBlank()) { "Missing room identifiers" }
    require(state.roomCode == roomCode) { "Room state does not match response" }
}
