package com.metrolist.music.listentogether

import com.metrolist.music.listentogether.proto.Listentogether

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
    const val CHAT = "chat"
    const val REQUEST_SYNC = "request_sync"
    const val RECONNECT = "reconnect"
    const val SUGGEST_TRACK = "suggest_track"
    const val APPROVE_SUGGESTION = "approve_suggestion"
    const val REJECT_SUGGESTION = "reject_suggestion"
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

typealias TrackInfo = Listentogether.TrackInfo
typealias UserInfo = Listentogether.UserInfo
typealias RoomState = Listentogether.RoomState
typealias CreateRoomPayload = Listentogether.CreateRoomPayload
typealias JoinRoomPayload = Listentogether.JoinRoomPayload
typealias ApproveJoinPayload = Listentogether.ApproveJoinPayload
typealias RejectJoinPayload = Listentogether.RejectJoinPayload
typealias PlaybackActionPayload = Listentogether.PlaybackActionPayload
typealias PingPayload = Listentogether.PingPayload
typealias BufferReadyPayload = Listentogether.BufferReadyPayload
typealias KickUserPayload = Listentogether.KickUserPayload
typealias TransferHostPayload = Listentogether.TransferHostPayload
typealias SuggestTrackPayload = Listentogether.SuggestTrackPayload
typealias ApproveSuggestionPayload = Listentogether.ApproveSuggestionPayload
typealias RejectSuggestionPayload = Listentogether.RejectSuggestionPayload
typealias RoomCreatedPayload = Listentogether.RoomCreatedPayload
typealias JoinRequestPayload = Listentogether.JoinRequestPayload
typealias JoinApprovedPayload = Listentogether.JoinApprovedPayload
typealias JoinRejectedPayload = Listentogether.JoinRejectedPayload
typealias UserJoinedPayload = Listentogether.UserJoinedPayload
typealias UserLeftPayload = Listentogether.UserLeftPayload
typealias BufferWaitPayload = Listentogether.BufferWaitPayload
typealias BufferCompletePayload = Listentogether.BufferCompletePayload
typealias ErrorPayload = Listentogether.ErrorPayload
typealias HostChangedPayload = Listentogether.HostChangedPayload
typealias KickedPayload = Listentogether.KickedPayload
typealias SyncStatePayload = Listentogether.SyncStatePayload
typealias PongPayload = Listentogether.PongPayload
typealias ReconnectPayload = Listentogether.ReconnectPayload
typealias ReconnectedPayload = Listentogether.ReconnectedPayload
typealias UserReconnectedPayload = Listentogether.UserReconnectedPayload
typealias UserDisconnectedPayload = Listentogether.UserDisconnectedPayload
typealias SuggestionReceivedPayload = Listentogether.SuggestionReceivedPayload
typealias SuggestionApprovedPayload = Listentogether.SuggestionApprovedPayload
typealias SuggestionRejectedPayload = Listentogether.SuggestionRejectedPayload

fun TrackInfo(
    id: String,
    title: String,
    artist: String,
    album: String? = null,
    duration: Long,
    thumbnail: String? = null,
    suggestedBy: String? = null,
): TrackInfo =
    TrackInfo
        .newBuilder()
        .setId(id)
        .setTitle(title)
        .setArtist(artist)
        .setAlbum(album.orEmpty())
        .setDuration(duration)
        .setThumbnail(thumbnail.orEmpty())
        .setSuggestedBy(suggestedBy.orEmpty())
        .build()

fun UserInfo(
    userId: String,
    username: String,
    isHost: Boolean,
    isConnected: Boolean = true,
): UserInfo =
    UserInfo
        .newBuilder()
        .setUserId(userId)
        .setUsername(username)
        .setIsHost(isHost)
        .setIsConnected(isConnected)
        .build()

fun RoomState(
    roomCode: String,
    hostId: String,
    users: List<UserInfo>,
    currentTrack: TrackInfo? = null,
    isPlaying: Boolean,
    position: Long,
    lastUpdate: Long,
    volume: Float = 1f,
    queue: List<TrackInfo> = emptyList(),
    revision: Long = 0L,
): RoomState =
    RoomState
        .newBuilder()
        .setRoomCode(roomCode)
        .setHostId(hostId)
        .addAllUsers(users)
        .setIsPlaying(isPlaying)
        .setPosition(position)
        .setLastUpdate(lastUpdate)
        .setVolume(volume)
        .addAllQueue(queue)
        .setRevision(revision)
        .apply { currentTrack?.let(::setCurrentTrack) }
        .build()

fun CreateRoomPayload(username: String): CreateRoomPayload = CreateRoomPayload.newBuilder().setUsername(username).build()

fun JoinRoomPayload(
    roomCode: String,
    username: String,
): JoinRoomPayload =
    JoinRoomPayload
        .newBuilder()
        .setRoomCode(roomCode)
        .setUsername(username)
        .build()

fun ApproveJoinPayload(userId: String): ApproveJoinPayload = ApproveJoinPayload.newBuilder().setUserId(userId).build()

fun RejectJoinPayload(
    userId: String,
    reason: String? = null,
): RejectJoinPayload =
    RejectJoinPayload
        .newBuilder()
        .setUserId(userId)
        .setReason(reason.orEmpty())
        .build()

fun PlaybackActionPayload(
    action: String,
    trackId: String? = null,
    position: Long? = null,
    trackInfo: TrackInfo? = null,
    insertNext: Boolean? = null,
    queue: List<TrackInfo>? = null,
    queueTitle: String? = null,
    volume: Float? = null,
    serverTime: Long? = null,
    revision: Long = 0L,
    capturedAtServerTime: Long? = null,
): PlaybackActionPayload =
    PlaybackActionPayload
        .newBuilder()
        .setAction(action)
        .setPosition(position ?: 0L)
        .setInsertNext(insertNext ?: false)
        .setVolume(volume ?: 1f)
        .setServerTime(serverTime ?: 0L)
        .setRevision(revision)
        .setCapturedAtServerTime(capturedAtServerTime ?: 0L)
        .apply {
            trackId?.let(::setTrackId)
            trackInfo?.let(::setTrackInfo)
            queue?.let(::addAllQueue)
            queueTitle?.let(::setQueueTitle)
        }.build()

fun PingPayload(
    clientTime: Long,
    sequence: Long,
): PingPayload =
    PingPayload
        .newBuilder()
        .setClientTime(clientTime)
        .setSequence(sequence)
        .build()

fun BufferReadyPayload(trackId: String): BufferReadyPayload = BufferReadyPayload.newBuilder().setTrackId(trackId).build()

fun KickUserPayload(
    userId: String,
    reason: String? = null,
): KickUserPayload =
    KickUserPayload
        .newBuilder()
        .setUserId(userId)
        .setReason(reason.orEmpty())
        .build()

fun TransferHostPayload(newHostId: String): TransferHostPayload = TransferHostPayload.newBuilder().setNewHostId(newHostId).build()

fun SuggestTrackPayload(trackInfo: TrackInfo): SuggestTrackPayload = SuggestTrackPayload.newBuilder().setTrackInfo(trackInfo).build()

fun ApproveSuggestionPayload(suggestionId: String): ApproveSuggestionPayload =
    ApproveSuggestionPayload.newBuilder().setSuggestionId(suggestionId).build()

fun RejectSuggestionPayload(
    suggestionId: String,
    reason: String? = null,
): RejectSuggestionPayload =
    RejectSuggestionPayload
        .newBuilder()
        .setSuggestionId(suggestionId)
        .setReason(reason.orEmpty())
        .build()

fun ReconnectPayload(sessionToken: String): ReconnectPayload = ReconnectPayload.newBuilder().setSessionToken(sessionToken).build()

fun SyncStatePayload(
    currentTrack: TrackInfo?,
    isPlaying: Boolean,
    position: Long,
    lastUpdate: Long,
    queue: List<TrackInfo>? = null,
    volume: Float? = null,
    revision: Long = 0L,
): SyncStatePayload =
    SyncStatePayload
        .newBuilder()
        .setIsPlaying(isPlaying)
        .setPosition(position)
        .setLastUpdate(lastUpdate)
        .setVolume(volume ?: 0f)
        .setRevision(revision)
        .apply {
            currentTrack?.let(::setCurrentTrack)
            queue?.let(::addAllQueue)
        }.build()

val RoomState.users: List<UserInfo> get() = usersList
val RoomState.queue: List<TrackInfo> get() = queueList
val RoomState.currentTrackOrNull: TrackInfo? get() = takeIf { hasCurrentTrack() }?.currentTrack
val SyncStatePayload.currentTrackOrNull: TrackInfo? get() = takeIf { hasCurrentTrack() }?.currentTrack
val SyncStatePayload.queue: List<TrackInfo>? get() = queueList
val PlaybackActionPayload.trackIdOrNull: String? get() = trackId.takeIf(String::isNotEmpty)
val PlaybackActionPayload.positionOrNull: Long?
    get() = position.takeIf { it != 0L || action in listOf(PlaybackActions.PLAY, PlaybackActions.PAUSE, PlaybackActions.SEEK) }
val PlaybackActionPayload.trackInfoOrNull: TrackInfo? get() = takeIf { hasTrackInfo() }?.trackInfo
val PlaybackActionPayload.queue: List<TrackInfo>? get() = queueList
val PlaybackActionPayload.queueTitleOrNull: String? get() = queueTitle.takeIf(String::isNotEmpty)
val PlaybackActionPayload.volumeOrNull: Float? get() = volume.takeIf { action == PlaybackActions.SET_VOLUME }
val PlaybackActionPayload.serverTimeOrNull: Long? get() = serverTime.takeIf { it > 0L }
val PlaybackActionPayload.capturedAtServerTimeOrNull: Long? get() = capturedAtServerTime.takeIf { it > 0L }
val PlaybackActionPayload.insertNextOrNull: Boolean? get() = insertNext.takeIf { it }
val SuggestionRejectedPayload.reasonOrNull: String? get() = reason.takeIf(String::isNotEmpty)
val BufferWaitPayload.waitingFor: List<String> get() = waitingForList

fun UserInfo.copy(
    userId: String = this.userId,
    username: String = this.username,
    isHost: Boolean = this.isHost,
    isConnected: Boolean = this.isConnected,
): UserInfo = UserInfo(userId, username, isHost, isConnected)

fun RoomState.copy(
    roomCode: String = this.roomCode,
    hostId: String = this.hostId,
    users: List<UserInfo> = this.users,
    currentTrack: TrackInfo? = currentTrackOrNull,
    isPlaying: Boolean = this.isPlaying,
    position: Long = this.position,
    lastUpdate: Long = this.lastUpdate,
    volume: Float = this.volume,
    queue: List<TrackInfo> = this.queue,
    revision: Long = this.revision,
): RoomState = RoomState(roomCode, hostId, users, currentTrack, isPlaying, position, lastUpdate, volume, queue, revision)

fun SyncStatePayload.copy(
    currentTrack: TrackInfo? = currentTrackOrNull,
    isPlaying: Boolean = this.isPlaying,
    position: Long = this.position,
    lastUpdate: Long = this.lastUpdate,
    queue: List<TrackInfo>? = this.queue,
    volume: Float? = this.volume,
    revision: Long = this.revision,
): SyncStatePayload = SyncStatePayload(currentTrack, isPlaying, position, lastUpdate, queue, volume, revision)
