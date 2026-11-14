package com.metrolist.shared.listentogether

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.listentogether.network.ApproveJoinPayload
import com.metrolist.shared.listentogether.network.ApproveSuggestionPayload
import com.metrolist.shared.listentogether.network.BufferCompletePayload
import com.metrolist.shared.listentogether.network.BufferReadyPayload
import com.metrolist.shared.listentogether.network.BufferWaitPayload
import com.metrolist.shared.listentogether.network.ClientCapabilities
import com.metrolist.shared.listentogether.network.CreateRoomPayload
import com.metrolist.shared.listentogether.network.ErrorPayload
import com.metrolist.shared.listentogether.network.HostChangedPayload
import com.metrolist.shared.listentogether.network.JoinApprovedPayload
import com.metrolist.shared.listentogether.network.JoinRejectedPayload
import com.metrolist.shared.listentogether.network.JoinRequestPayload
import com.metrolist.shared.listentogether.network.JoinRoomPayload
import com.metrolist.shared.listentogether.network.KickUserPayload
import com.metrolist.shared.listentogether.network.KickedPayload
import com.metrolist.shared.listentogether.network.ListenTogetherMessageCodec
import com.metrolist.shared.listentogether.network.MessageTypes
import com.metrolist.shared.listentogether.network.PingPayload
import com.metrolist.shared.listentogether.network.PlaybackActionPayload
import com.metrolist.shared.listentogether.network.PlaybackActions
import com.metrolist.shared.listentogether.network.PongPayload
import com.metrolist.shared.listentogether.network.ProtocolFeatures
import com.metrolist.shared.listentogether.network.ReconnectPayload
import com.metrolist.shared.listentogether.network.ReconnectedPayload
import com.metrolist.shared.listentogether.network.RejectJoinPayload
import com.metrolist.shared.listentogether.network.RejectSuggestionPayload
import com.metrolist.shared.listentogether.network.RejectionReasons
import com.metrolist.shared.listentogether.network.RoomCreatedPayload
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.ServerCapabilities
import com.metrolist.shared.listentogether.network.ServerClock
import com.metrolist.shared.listentogether.network.ServerRestartingPayload
import com.metrolist.shared.listentogether.network.SuggestTrackPayload
import com.metrolist.shared.listentogether.network.SuggestionApprovedPayload
import com.metrolist.shared.listentogether.network.SuggestionReceivedPayload
import com.metrolist.shared.listentogether.network.SuggestionRejectedPayload
import com.metrolist.shared.listentogether.network.SuggestionSubmittedPayload
import com.metrolist.shared.listentogether.network.SyncStatePayload
import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.listentogether.network.UserDisconnectedPayload
import com.metrolist.shared.listentogether.network.UserInfo
import com.metrolist.shared.listentogether.network.UserJoinedPayload
import com.metrolist.shared.listentogether.network.UserLeftPayload
import com.metrolist.shared.listentogether.network.UserReconnectedPayload
import com.metrolist.shared.listentogether.network.createListenTogetherMessageCodec
import com.metrolist.shared.listentogether.network.isAllowedListenTogetherServerUrl
import com.metrolist.shared.listentogether.network.roomStateHash
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.ShareUrls
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import com.metrolist.shared.listentogether.toSongItem as trackInfoToSongItem

class ListenTogetherManager(
    private val callerScope: CoroutineScope,
    private val httpClient: HttpClient,
    private val playback: ListenTogetherPlayback,
    private val resolver: ListenTogetherTrackResolver,
    private val monotonicClockMs: () -> Long,
    private val wallClockMs: () -> Long,
    private val timestampFormatter: (Long) -> String,
    private val presentationText: ListenTogetherPresentationText = ListenTogetherPresentationText { it.name },
    private val logger: ListenTogetherLogger = ListenTogetherLogger { _, _ -> },
    private val sessionPersistenceEnabled: Boolean = true,
    private val reconnectDelayMillis: (attempt: Int) -> Long = { attempt ->
        val base = (1_000L shl minOf(attempt - 1, 6)).coerceAtMost(60_000L)
        base + Random.nextLong((base / 5).coerceAtLeast(1L))
    },
    private val roomRequestTimeoutMs: Long = ROOM_REQUEST_TIMEOUT_MS,
    private val codecDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private companion object {
        const val DEFAULT_SERVER_URL = "wss://metroserverx.meowery.eu/ws"
        const val MAX_RECONNECT_ATTEMPTS = 15
        const val MAX_LOG_ENTRIES = 500
        const val OUTGOING_CAPACITY = 64
        const val INITIAL_PING_INTERVAL_MS = 2_000L
        const val STEADY_PING_INTERVAL_MS = 15_000L
        const val INITIAL_CLOCK_SAMPLE_COUNT = 5
        const val CAPABILITY_TIMEOUT_MS = 5_000L
        const val SEND_TIMEOUT_MS = 5_000L
        const val ROOM_REQUEST_TIMEOUT_MS = 5 * 60_000L
        const val PLAYER_OBSERVER_INTERVAL_MS = 800L

        // A cold resolve waits for a PO token and can exceed 10 s; a load failure still ends the wait early.
        const val READY_TIMEOUT_MS = 30_000L
        const val BARRIER_TIMEOUT_MS = 25_000L
        const val MAX_TRACKED_REQUESTS = 128
        const val SYNC_RETRY_DELAY_MS = 1_100L
        val BACKGROUND_REQUEST_TYPES =
            setOf(MessageTypes.PLAYBACK_ACTION, MessageTypes.BUFFER_READY, MessageTypes.REQUEST_SYNC, MessageTypes.PING)
        const val SESSION_SEPARATOR = "\n---\n"
        const val LIST_SEPARATOR = "\n"
    }

    private sealed interface PendingAction {
        data object Create : PendingAction

        data class Join(
            val roomCode: String,
        ) : PendingAction
    }

    private data class OutgoingMessage(
        val session: DefaultClientWebSocketSession,
        val bytes: ByteArray,
        val delivered: CompletableDeferred<Boolean> = CompletableDeferred(),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OutgoingMessage) return false

            if (session != other.session) return false
            if (!bytes.contentEquals(other.bytes)) return false
            if (delivered != other.delivered) return false

            return true
        }

        override fun hashCode(): Int {
            var result = session.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + delivered.hashCode()
            return result
        }
    }

    private data class PendingBarrier(
        val trackId: String,
        val position: Long,
        val effectiveAtServerTime: Long?,
        val playing: Boolean,
        val generation: Long,
        val ready: Boolean = true,
    )

    private data class PendingSuggestion(
        val track: TrackInfo,
        var sentGeneration: Long = -1L,
    )

    private val managerJob = SupervisorJob(callerScope.coroutineContext[Job])
    private val scope = CoroutineScope(callerScope.coroutineContext + managerJob)
    private val codec = createListenTogetherMessageCodec()
    private val serverClock = ServerClock(monotonicClockMs)
    private val revisionFilter = PlaybackRevisionFilter()
    private val outgoing = Channel<OutgoingMessage>(OUTGOING_CAPACITY)
    private val encodeMutex = Mutex()

    private val _connectionState = MutableStateFlow(ListenTogetherConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ListenTogetherConnectionState> = _connectionState.asStateFlow()
    private val _roomState = MutableStateFlow<RoomState?>(null)
    val roomState: StateFlow<RoomState?> = _roomState.asStateFlow()
    private val _role = MutableStateFlow(ListenTogetherRoomRole.NONE)
    val role: StateFlow<ListenTogetherRoomRole> = _role.asStateFlow()
    private val _userId = MutableStateFlow<String?>(null)
    val userId: StateFlow<String?> = _userId.asStateFlow()
    private val _username = MutableStateFlow(Settings.shared.listenTogetherUsername)
    val username: StateFlow<String> = _username.asStateFlow()
    private val _serverUrl =
        MutableStateFlow(
            Settings.shared.listenTogetherServerUrl.takeIf(::isAllowedListenTogetherServerUrl) ?: DEFAULT_SERVER_URL,
        )
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()
    private val _isInRoom = MutableStateFlow(false)
    val isInRoom: StateFlow<Boolean> = _isInRoom.asStateFlow()
    private val _roomRequestPending = MutableStateFlow(false)
    val roomRequestPending: StateFlow<Boolean> = _roomRequestPending.asStateFlow()
    private val _joinRequestPending = MutableStateFlow(false)
    val joinRequestPending: StateFlow<Boolean> = _joinRequestPending.asStateFlow()
    private val _joinRequestSubmitted = MutableStateFlow(false)
    val joinRequestSubmitted: StateFlow<Boolean> = _joinRequestSubmitted.asStateFlow()
    private val _roomAuthenticated = MutableStateFlow(false)
    val roomAuthenticated: StateFlow<Boolean> = _roomAuthenticated.asStateFlow()
    private val _failedRoomTrack = MutableStateFlow<TrackInfo?>(null)
    val failedRoomTrack: StateFlow<TrackInfo?> = _failedRoomTrack.asStateFlow()
    private val _submittedSuggestions = MutableStateFlow<Map<String, TrackInfo>>(emptyMap())
    val submittedSuggestions: StateFlow<Map<String, TrackInfo>> = _submittedSuggestions.asStateFlow()
    private val _pendingJoinRequests = MutableStateFlow<List<ListenTogetherJoinRequest>>(emptyList())
    val pendingJoinRequests: StateFlow<List<ListenTogetherJoinRequest>> = _pendingJoinRequests.asStateFlow()
    private val autoApprovingSuggestions = mutableMapOf<String, ListenTogetherSuggestion>()
    private val _pendingSuggestions = MutableStateFlow<List<ListenTogetherSuggestion>>(emptyList())
    val pendingSuggestions: StateFlow<List<ListenTogetherSuggestion>> = _pendingSuggestions.asStateFlow()
    private val _pendingQueueImport = MutableStateFlow<PendingListenTogetherQueue?>(null)
    val pendingQueueImport: StateFlow<PendingListenTogetherQueue?> = _pendingQueueImport.asStateFlow()
    private val _blockedUsernames = MutableStateFlow(parseSet(Settings.shared.listenTogetherBlockedUsers))
    val blockedUsernames: StateFlow<Set<String>> = _blockedUsernames.asStateFlow()
    private val _autoApproveJoinRequests = MutableStateFlow(Settings.shared.listenTogetherAutoApproveJoinRequests)
    val autoApproveJoinRequests: StateFlow<Boolean> = _autoApproveJoinRequests.asStateFlow()
    private val _autoApproveSongSuggestions = MutableStateFlow(Settings.shared.listenTogetherAutoApproveSongSuggestions)
    val autoApproveSongSuggestions: StateFlow<Boolean> = _autoApproveSongSuggestions.asStateFlow()
    private val _syncHostVolume = MutableStateFlow(Settings.shared.listenTogetherSyncHostVolume)
    val syncHostVolume: StateFlow<Boolean> = _syncHostVolume.asStateFlow()
    private val _logs = MutableStateFlow<List<ListenTogetherLogEntry>>(emptyList())
    val logs: StateFlow<List<ListenTogetherLogEntry>> = _logs.asStateFlow()
    private val _events = MutableSharedFlow<ListenTogetherEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ListenTogetherEvent> = _events.asSharedFlow()
    private val _servers = MutableStateFlow(buildServers())
    val servers: StateFlow<List<ListenTogetherServer>> = _servers.asStateFlow()

    val isGuestPlaybackLocked: Boolean
        get() = listenTogetherControls(Settings.shared.listenTogetherEnabled, _role.value, _isInRoom.value).playbackLocked

    private val hasLoadedRoomTrack: Boolean
        get() {
            val trackId = _roomState.value?.currentTrack?.id ?: return false
            return trackId == playback.currentSong.value?.id && playback.readiness.value != PlaybackReadiness.FAILED
        }

    val hasPersistedSession: Boolean
        get() = !sessionToken.isNullOrBlank()

    private var socket: DefaultClientWebSocketSession? = null
    private var connectJob: Job? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var remotePlaybackJob: Job? = null
    private var queueImportJob: Job? = null
    private var sameTrackChangeJob: Job? = null
    private var readinessRecoveryJob: Job? = null
    private var driftJob: Job? = null
    private var roomRequestTimeoutJob: Job? = null
    private var roomRequestGeneration = 0L

    @Volatile
    private var closed = false

    private var applyingRemoteState = false

    @Volatile
    private var pendingAction: PendingAction? = null

    @Volatile
    private var awaitingRoomResponse = false

    @Volatile
    private var requestedRoomCode: String? = null
    private var sessionToken: String? = null
    private var sessionStartedAt = 0L

    private var reconnectAttempts = 0
    private var sessionReconnectRejected = false
    private var pingSequence = 0L
    private var lastPongSequence = 0L
    private val outstandingPings = LinkedHashMap<Long, Long>()
    private val pendingHostQueues = ArrayDeque<List<TrackInfo>>()
    private var connectionGeneration = 0L
    private var authenticatedConnectionGeneration = -1L
    private var playbackGeneration = 0L
    private var loadingTrackId: String? = null
    private var loadingQueue: List<TrackInfo>? = null
    private var sameTrackChangeGeneration = 0L
    private var pendingCanonicalQueue: List<TrackInfo>? = null
    private var pendingBarrier: PendingBarrier? = null
    private var lastQueueSnapshot: List<TrackInfo>? = null
    private var lastQueueTitle: String? = null
    private var upcomingQueueInput: List<SongItem>? = null
    private var upcomingCurrentId: String? = null
    private var upcomingCurrentIndex: Int? = null
    private var upcomingTrackInfo = emptyList<TrackInfo>()
    private var lastVolume: Float? = null
    private var lastTrackId: String? = null
    private var lastTrackInstanceId: Long? = null
    private var lastPlaying: Boolean? = null
    private var lastPositionBucket: Long? = null
    private var driftGeneration = 0L

    @Volatile
    private var serverFeatures: Set<String> = emptySet()
    private var requestSequence = 0L
    private val requestTypes = LinkedHashMap<Long, String>()
    private val suggestionRequests = mutableMapOf<Long, String>()
    private val suggestionDecisionRequests = mutableMapOf<Long, String>()
    private val outgoingSuggestions = mutableMapOf<String, PendingSuggestion>()
    private val suggestionSendMutex = Mutex()
    private var hostSequence = 0L

    @Volatile
    private var restartDelayMs: Long? = null

    init {
        if (sessionPersistenceEnabled) restoreSession()
        scope.launch {
            for (message in outgoing) {
                if (!Settings.shared.listenTogetherEnabled || socket !== message.session) {
                    message.delivered.complete(false)
                    continue
                }
                val delivered =
                    try {
                        message.session.send(Frame.Binary(true, message.bytes))
                        true
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        false
                    } catch (error: Exception) {
                        PlaybackEventLog.error(
                            PlaybackEventKind.SYNC,
                            "ListenTogether",
                            "WebSocket message send failed",
                            error,
                        )
                        false
                    }
                message.delivered.complete(delivered)
                if (!delivered) log(ListenTogetherLogLevel.ERROR, "Outgoing message failed")
            }
        }
        startHostObserver()
        startGuestRecoveryObserver()
        scope.launch {
            delay(500.milliseconds)
            if (hasPersistedSession) connect()
        }
    }

    fun setEnabled(enabled: Boolean) {
        Settings.shared.listenTogetherEnabled = enabled
        if (!enabled) disconnectIntegration()
    }

    private fun disconnectIntegration() {
        synchronized(participationLock) {
            if (Settings.shared.listenTogetherEnabled) return
            val session = socket
            socket = null
            ++connectionGeneration
            connectJob?.cancel()
            connectJob = null
            pingJob?.cancel()
            pingJob = null
            cleanupRoom(clearSession = true)
            _connectionState.value = ListenTogetherConnectionState.DISCONNECTED
            session?.cancel()
        }
    }

    fun connect() {
        if (!Settings.shared.listenTogetherEnabled || !playbackParticipationAllowed || closed || connectJob?.isActive == true ||
            socket != null
        ) {
            return
        }
        val generation = ++connectionGeneration
        _connectionState.value =
            if (reconnectAttempts > 0) {
                ListenTogetherConnectionState.RECONNECTING
            } else {
                ListenTogetherConnectionState.CONNECTING
            }
        connectJob =
            scope.launch {
                var protocolRejected = false
                var transport: DefaultClientWebSocketSession? = null
                try {
                    if (!Settings.shared.listenTogetherEnabled) return@launch
                    val session = httpClient.webSocketSession { url(_serverUrl.value) }
                    transport = session
                    if (!Settings.shared.listenTogetherEnabled || closed || generation != connectionGeneration) {
                        session.close()
                        return@launch
                    }
                    codec.compressionEnabled = false
                    val capabilities =
                        encode(
                            MessageTypes.CLIENT_CAPABILITIES,
                            ClientCapabilities(
                                supportsProtobuf = true,
                                supportsCompression = true,
                                clientVersion = BuildConfig.APP_VERSION,
                                features = ProtocolFeatures.supported,
                            ),
                        )
                    session.send(Frame.Binary(true, capabilities))
                    val serverCapabilities =
                        withTimeoutOrNull(CAPABILITY_TIMEOUT_MS.milliseconds) {
                            val frame = session.incoming.receive() as? Frame.Binary ?: return@withTimeoutOrNull null
                            withContext(codecDispatcher) { runCatching { codec.decodeServerCapabilities(frame.data) }.getOrNull() }
                        }
                    if (!Settings.shared.listenTogetherEnabled || closed || generation != connectionGeneration) return@launch
                    if (serverCapabilities?.supportsProtobuf != true) {
                        protocolRejected = true
                        clearRoomRequest()
                        log(ListenTogetherLogLevel.ERROR, "Server protocol is incompatible")
                        _connectionState.value = ListenTogetherConnectionState.ERROR
                        emitFailure(ListenTogetherText.CONNECTION_FAILED)
                        session.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Capability negotiation failed"))
                        return@launch
                    }
                    codec.compressionEnabled = serverCapabilities.supportsCompression
                    serverFeatures = serverCapabilities.features.toSet()
                    socket = session
                    reconnectJob?.cancel()
                    reconnectJob = null
                    if (sessionToken == null) {
                        reconnectAttempts = 0
                        sessionReconnectRejected = false
                    }
                    _connectionState.value = ListenTogetherConnectionState.CONNECTED
                    serverClock.reset()
                    pingSequence = 0L
                    lastPongSequence = 0L
                    synchronized(participationLock) { outstandingPings.clear() }
                    log(ListenTogetherLogLevel.INFO, "Transport connected")
                    startPing(session)
                    sendPendingAction()
                    for (frame in session.incoming) {
                        if (generation != connectionGeneration || socket !== session) break
                        if (frame is Frame.Binary) handleBinary(frame.data, generation)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (transport != null) {
                        PlaybackEventLog.error(
                            PlaybackEventKind.SYNC,
                            "ListenTogether",
                            "WebSocket connection or session failed",
                            error,
                        )
                    }
                    log(ListenTogetherLogLevel.ERROR, "Transport connection failed")
                } finally {
                    transport?.cancel()
                    if (generation == connectionGeneration) {
                        connectJob = null
                        if (!protocolRejected) handleDisconnect(generation)
                    }
                }
            }
    }

    fun createRoom(overrideUsername: String? = null): Boolean {
        val username = overrideUsername?.trim() ?: _username.value
        val requestGeneration =
            synchronized(participationLock) {
                if (!Settings.shared.listenTogetherEnabled) return false
                if (!playbackParticipationAllowed || hasActiveOrPendingRoom || username.isBlank() || closed) return false
                if (overrideUsername != null) setUsername(username)
                pendingAction = PendingAction.Create
                awaitingRoomResponse = false
                requestedRoomCode = null
                ++roomRequestGeneration
            }
        startRoomRequestTimeout(requestGeneration)
        if (socket != null) scope.launch { sendPendingAction() } else connect()
        return true
    }

    fun joinRoom(
        roomCode: String,
        overrideUsername: String? = null,
    ): Boolean {
        val username = overrideUsername?.trim() ?: _username.value
        val requestGeneration =
            synchronized(participationLock) {
                if (!Settings.shared.listenTogetherEnabled) return false
                if (!playbackParticipationAllowed || hasActiveOrPendingRoom || username.isBlank() || closed) return false
                val code = sanitizeRoomCode(roomCode)
                if (code.isBlank()) return false
                if (overrideUsername != null) setUsername(username)
                pendingAction = PendingAction.Join(code)
                _joinRequestPending.value = true
                awaitingRoomResponse = false
                requestedRoomCode = code
                ++roomRequestGeneration
            }
        startRoomRequestTimeout(requestGeneration)
        if (socket != null) scope.launch { sendPendingAction() } else connect()
        return true
    }

    fun cancelJoinRequest() {
        synchronized(participationLock) {
            if (!_joinRequestPending.value || _isInRoom.value) return
            val session = socket
            val connection = connectJob
            ++connectionGeneration
            socket = null
            connectJob = null
            reconnectJob?.cancel()
            reconnectJob = null
            pingJob?.cancel()
            pingJob = null
            clearRoomRequest()
            reconnectAttempts = 0
            _connectionState.value = ListenTogetherConnectionState.DISCONNECTED
            session?.cancel()
            connection?.cancel()
        }
    }

    fun retryConnection() {
        if (_connectionState.value != ListenTogetherConnectionState.ERROR || !_isInRoom.value) return
        reconnectAttempts = 0
        reconnectJob?.cancel()
        reconnectJob = null
        connect()
    }

    fun retryRoomTrack() {
        if (!_roomAuthenticated.value || _role.value != ListenTogetherRoomRole.GUEST) return
        val state = _roomState.value ?: return
        val track = state.currentTrack ?: return
        startRemotePlayback(track, state.queue, state.position, state.isPlaying, state.lastUpdate, barrier = false)
    }

    fun leaveRoom() {
        scope.launch { leaveRoomAndWait() }
    }

    suspend fun leaveRoomAndWait() {
        val session = socket
        val connection = connectJob
        ++connectionGeneration
        clearRoomRequest()
        val leaveSent = runCatching { send(MessageTypes.LEAVE_ROOM) }.getOrDefault(false)
        if (socket === session) socket = null
        closeTransport(session, connection, "User disconnected".takeIf { leaveSent })
        if (connectJob === connection) connectJob = null
        cleanupRoom(clearSession = true)
        _connectionState.value = ListenTogetherConnectionState.DISCONNECTED
    }

    fun requestSync() {
        if (
            socket == null ||
            !_isInRoom.value ||
            _role.value != ListenTogetherRoomRole.GUEST ||
            authenticatedConnectionGeneration != connectionGeneration
        ) {
            return
        }
        launchSend(MessageTypes.REQUEST_SYNC)
    }

    fun approveJoin(userId: String) {
        launchSend(MessageTypes.APPROVE_JOIN, ApproveJoinPayload(userId)) {
            _pendingJoinRequests.value = _pendingJoinRequests.value.filterNot { it.userId == userId }
        }
    }

    fun rejectJoin(
        userId: String,
        reason: String? = null,
    ) {
        launchSend(MessageTypes.REJECT_JOIN, RejectJoinPayload(userId, reason)) {
            _pendingJoinRequests.value = _pendingJoinRequests.value.filterNot { it.userId == userId }
        }
    }

    fun kickUser(
        userId: String,
        reason: String? = null,
    ) = launchSend(MessageTypes.KICK_USER, KickUserPayload(userId, reason))

    fun approveSuggestion(suggestionId: String) {
        synchronized(participationLock) { autoApprovingSuggestions.remove(suggestionId) }
        sendSuggestionApproval(suggestionId)
    }

    private fun sendSuggestionApproval(suggestionId: String) {
        launchSend(MessageTypes.APPROVE_SUGGESTION, ApproveSuggestionPayload(suggestionId)) {
            if (ProtocolFeatures.REQUEST_ID !in serverFeatures) {
                _pendingSuggestions.value = _pendingSuggestions.value.filterNot { it.id == suggestionId }
            }
        }
    }

    fun rejectSuggestion(
        suggestionId: String,
        reason: String? = null,
    ) {
        synchronized(participationLock) { autoApprovingSuggestions.remove(suggestionId) }
        launchSend(MessageTypes.REJECT_SUGGESTION, RejectSuggestionPayload(suggestionId, reason)) {
            if (ProtocolFeatures.REQUEST_ID !in serverFeatures) {
                _pendingSuggestions.value = _pendingSuggestions.value.filterNot { it.id == suggestionId }
            }
        }
    }

    fun suggestTrack(song: SongItem) {
        suggestTrack(song.toTrackInfo())
    }

    fun suggestTrack(track: TrackInfo) {
        if (_role.value != ListenTogetherRoomRole.GUEST || !_isInRoom.value) return
        synchronized(participationLock) {
            if (_submittedSuggestions.value.values.any { it.id == track.id }) return
            outgoingSuggestions.getOrPut(track.id) { PendingSuggestion(track) }
        }
        sendPendingSuggestions()
    }

    private fun sendPendingSuggestions() {
        scope.launch {
            suggestionSendMutex.withLock {
                val pending = synchronized(participationLock) { outgoingSuggestions.values.toList() }
                for (suggestion in pending) {
                    if (_role.value != ListenTogetherRoomRole.GUEST || !_isInRoom.value || socket == null ||
                        authenticatedConnectionGeneration != connectionGeneration
                    ) {
                        return@withLock
                    }
                    val generation = connectionGeneration
                    if (synchronized(participationLock) {
                            outgoingSuggestions[suggestion.track.id] !== suggestion || suggestion.sentGeneration == generation
                        }
                    ) {
                        continue
                    }
                    if (!send(MessageTypes.SUGGEST_TRACK, SuggestTrackPayload(suggestion.track))) return@withLock
                    synchronized(participationLock) {
                        if (outgoingSuggestions[suggestion.track.id] === suggestion) {
                            if (ProtocolFeatures.SUGGESTION_ACK in serverFeatures) {
                                suggestion.sentGeneration = generation
                            } else {
                                outgoingSuggestions.remove(suggestion.track.id)
                            }
                        }
                    }
                }
            }
        }
    }

    fun sendSeek(positionMs: Long) {
        if (_role.value != ListenTogetherRoomRole.HOST || !_isInRoom.value || applyingRemoteState) return
        launchPlaybackAction(
            PlaybackActionPayload(PlaybackActions.SEEK, playback.currentSong.value?.id, positionMs, hostSequence = nextHostSequence()),
        )
    }

    fun prepareQueueImport(
        title: String,
        songs: List<SongItem>,
    ) {
        _pendingQueueImport.value = PendingListenTogetherQueue(title, songs.distinctBy { it.id })
    }

    fun cancelQueueImport() {
        _pendingQueueImport.value = null
    }

    fun addPendingQueueImport() {
        val pending = _pendingQueueImport.value ?: return
        scope.launch {
            when (_role.value) {
                ListenTogetherRoomRole.HOST -> pending.songs.forEach { playback.addToQueueFromRemote(it) }
                ListenTogetherRoomRole.GUEST -> pending.songs.forEach(::suggestTrack)
                ListenTogetherRoomRole.NONE -> return@launch
            }
            _pendingQueueImport.value = null
        }
    }

    fun replaceWithPendingQueueImport() {
        if (_role.value != ListenTogetherRoomRole.HOST) return
        val pending = _pendingQueueImport.value ?: return
        queueImportJob?.cancel()
        queueImportJob =
            scope.launch {
                if (pending.songs.isNotEmpty()) {
                    playback.playQueueFromRemote(pending.songs, title = pending.title)
                    playback.setPlayingFromRemote(true)
                }
                _pendingQueueImport.value = null
            }
    }

    fun setUsername(value: String) {
        _username.value = value.trim()
        Settings.shared.listenTogetherUsername = _username.value
    }

    fun setServerUrl(value: String) {
        val normalized = value.trim().ifBlank { DEFAULT_SERVER_URL }
        if (!isAllowedListenTogetherServerUrl(normalized)) return
        synchronized(participationLock) {
            if (closed || normalized == _serverUrl.value) return
            val reconnect = socket != null || connectJob?.isActive == true || hasActiveOrPendingRoom
            val session = socket
            val connection = connectJob
            ++connectionGeneration
            socket = null
            connectJob = null
            pingJob?.cancel()
            pingJob = null
            cleanupRoom(clearSession = true)
            serverFeatures = emptySet()
            requestTypes.clear()
            outstandingPings.clear()
            restartDelayMs = null
            _connectionState.value = ListenTogetherConnectionState.DISCONNECTED
            _serverUrl.value = normalized
            Settings.shared.listenTogetherServerUrl = normalized
            session?.cancel()
            connection?.cancel()
            if (reconnect) connect()
        }
    }

    fun addCustomServer(value: String) {
        val normalized = value.trim()
        if (!isAllowedListenTogetherServerUrl(normalized)) return
        val values = parseSet(Settings.shared.listenTogetherCustomServers) + normalized
        Settings.shared.listenTogetherCustomServers = persistSet(values)
        _servers.value = buildServers()
        setServerUrl(normalized)
    }

    fun removeCustomServer(value: String) {
        val values = parseSet(Settings.shared.listenTogetherCustomServers).filterNot { it == value }.toSet()
        Settings.shared.listenTogetherCustomServers = persistSet(values)
        Settings.shared.listenTogetherServerMetadata = Json.encodeToString(customServerMetadata() - value)
        if (_serverUrl.value == value) setServerUrl(DEFAULT_SERVER_URL)
        _servers.value = buildServers()
    }

    fun updateCustomServerMetadata(
        url: String,
        name: String,
        operator: String,
    ): Boolean {
        if (_servers.value.none { it.url == url && it.isCustom } || name.isBlank() || operator.isBlank()) return false
        val metadata = customServerMetadata() + (url to ListenTogetherServerMetadata(name.trim(), operator.trim()))
        Settings.shared.listenTogetherServerMetadata = Json.encodeToString(metadata)
        _servers.value = buildServers()
        return true
    }

    fun setAutoApproveJoinRequests(enabled: Boolean) {
        _autoApproveJoinRequests.value = enabled
        Settings.shared.listenTogetherAutoApproveJoinRequests = enabled
    }

    fun setAutoApproveSongSuggestions(enabled: Boolean) {
        _autoApproveSongSuggestions.value = enabled
        Settings.shared.listenTogetherAutoApproveSongSuggestions = enabled
    }

    fun setSyncHostVolume(enabled: Boolean) {
        _syncHostVolume.value = enabled
        Settings.shared.listenTogetherSyncHostVolume = enabled
    }

    fun blockUser(username: String) {
        _blockedUsernames.value = ListenTogetherModeration.add(username, _blockedUsernames.value)
        Settings.shared.listenTogetherBlockedUsers = persistSet(_blockedUsernames.value)
        _pendingJoinRequests.value =
            _pendingJoinRequests.value.filterNot {
                ListenTogetherModeration.isBlocked(it.username, _blockedUsernames.value)
            }
    }

    fun blockUser(user: UserInfo) {
        blockUser(user.username)
        if (_role.value == ListenTogetherRoomRole.HOST) kickUser(user.userId, RejectionReasons.BLOCKED_BY_HOST)
    }

    fun unblockUser(username: String) {
        _blockedUsernames.value = ListenTogetherModeration.remove(username, _blockedUsernames.value)
        Settings.shared.listenTogetherBlockedUsers = persistSet(_blockedUsernames.value)
    }

    fun roomLink(useParty: Boolean = false): String? = _roomState.value?.roomCode?.let { ShareUrls.party(it, useParty) }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun close() {
        val shouldClose =
            synchronized(participationLock) {
                if (closed) return@synchronized false
                closed = true
                true
            }
        if (!shouldClose) return
        val restorePlaybackSpeed = _isInRoom.value
        val session = socket
        socket = null
        ++connectionGeneration
        cleanupRoom(clearSession = false)
        _connectionState.value = ListenTogetherConnectionState.DISCONNECTED
        runCatching { session?.cancel() }
        outgoing.close()
        managerJob.cancel()
        if (restorePlaybackSpeed) {
            callerScope.launch {
                playback.setPlaybackSpeedFromRemote(Settings.shared.playbackSpeed.coerceIn(0.5f, 2f))
            }
        }
    }

    private var restoredRoomCode: String? = null
    private val hostPublicationMutex = Mutex()
    private val participationLock = SynchronizedObject()

    @Volatile private var playbackParticipationAllowed = true

    init {
        scope.launch {
            snapshotFlow { Settings.shared.listenTogetherEnabled }.collect { enabled ->
                if (!enabled) disconnectIntegration()
            }
        }
    }

    val hasActiveOrPendingRoom: Boolean
        get() = _isInRoom.value || hasPendingRoomRequest || hasPersistedSession || reconnectJob?.isActive == true

    val hasPendingRoomRequest: Boolean
        get() = pendingAction != null || awaitingRoomResponse

    val pendingRoomCode: String?
        get() = requestedRoomCode

    fun setPlaybackParticipationAllowed(allowed: Boolean): Boolean =
        synchronized(participationLock) {
            if (!allowed && hasActiveOrPendingRoom) return@synchronized false
            playbackParticipationAllowed = allowed
            true
        }

    fun publishHostPlaybackChange(
        trackChanged: Boolean,
        seekPositionMs: Long? = null,
    ) {
        scope.launch {
            publishHostPlayback(trackChanged)
            seekPositionMs?.let(::sendSeek)
        }
    }

    private fun startGuestRecoveryObserver() {
        scope.launch {
            combine(_role, _isInRoom, _connectionState) { role, inRoom, connection ->
                role == ListenTogetherRoomRole.GUEST && inRoom && connection == ListenTogetherConnectionState.CONNECTED
            }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                var failedTrackId: String? = null
                var retryCount = 0
                combine(playback.readiness, _roomState.map { it?.currentTrack?.id }.distinctUntilChanged()) { readiness, trackId ->
                    trackId to (readiness == PlaybackReadiness.FAILED)
                }.distinctUntilChanged().collectLatest recovery@{ (trackId, failed) ->
                    if (trackId != failedTrackId) {
                        failedTrackId = trackId
                        retryCount = 0
                    }
                    if (!failed || trackId == null) return@recovery
                    while (retryCount < 3) {
                        delay(PLAYER_OBSERVER_INTERVAL_MS.milliseconds)
                        remotePlaybackJob?.join()
                        if (playback.readiness.value != PlaybackReadiness.FAILED) return@recovery
                        val state = _roomState.value ?: return@recovery
                        val track = state.currentTrack ?: return@recovery
                        retryCount++
                        startRemotePlayback(
                            track = track,
                            queue = state.queue,
                            position = state.position,
                            playing = state.isPlaying,
                            effectiveAtServerTime = state.lastUpdate,
                            barrier = false,
                        )
                    }
                }
            }
        }
    }

    private fun startHostObserver() {
        scope.launch {
            combine(_role, _isInRoom, _connectionState) { role, inRoom, connection ->
                role == ListenTogetherRoomRole.HOST && inRoom && connection == ListenTogetherConnectionState.CONNECTED
            }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                observeHostPlayback(
                    changes =
                        combine(
                            playback.currentSong,
                            playback.playing,
                            playback.queue,
                            playback.volume,
                            playback.connected,
                        ) { _, playing, _, _, _ -> playing },
                    intervalMs = PLAYER_OBSERVER_INTERVAL_MS,
                    publish = { publishHostPlayback() },
                )
            }
        }
    }

    private suspend fun publishHostPlayback(forceTrackChange: Boolean = false) =
        hostPublicationMutex.withLock {
            if (_role.value != ListenTogetherRoomRole.HOST || !_isInRoom.value || applyingRemoteState) return@withLock
            if (!playback.connected.value) return@withLock
            if (
                socket == null ||
                _connectionState.value != ListenTogetherConnectionState.CONNECTED ||
                authenticatedConnectionGeneration != connectionGeneration
            ) {
                return@withLock
            }
            if (abs(playback.playbackSpeed() - 1f) > 0.001f) playback.setPlaybackSpeedFromRemote(1f)
            val song = playback.currentSong.value
            val trackId = song?.id
            val instanceId = playback.playbackInstanceId()
            val playing = playback.playing.value
            val position = playback.currentPositionMs()
            val observedSequence = nextHostSequence()
            val positionBucket = position / 3_000L
            val upcoming = currentUpcomingTrackInfo()
            val previousTrackId = lastTrackId ?: _roomState.value?.currentTrack?.id
            _roomState.value =
                _roomState.value?.copy(
                    currentTrack = song?.toTrackInfo(),
                    isPlaying = playing,
                    position = position,
                    lastUpdate = serverClock.serverTimeNow() ?: wallClockMs(),
                    volume = playback.volume.value,
                    queue = upcoming,
                    queueTitle = playback.currentQueueTitle(),
                )
            if (trackId == null && previousTrackId != null) {
                if (sendPlaybackAction(
                        PlaybackActionPayload(PlaybackActions.PAUSE, previousTrackId, position, hostSequence = observedSequence),
                    )
                ) {
                    lastTrackId = null
                    lastTrackInstanceId = null
                    lastPlaying = false
                    lastPositionBucket = null
                }
            } else if (trackId != null &&
                (
                    trackId != lastTrackId || (instanceId != null && instanceId != lastTrackInstanceId) ||
                        (forceTrackChange && instanceId == null)
                )
            ) {
                if (
                    sendPlaybackAction(
                        PlaybackActionPayload(
                            action = PlaybackActions.CHANGE_TRACK,
                            trackId = trackId,
                            position = position,
                            trackInfo = song.toTrackInfo(),
                            queue = upcoming,
                            queueTitle = playback.currentQueueTitle(),
                            replaceQueue = true,
                        ),
                    )
                ) {
                    lastQueueSnapshot = upcoming
                    lastTrackId = trackId
                    lastTrackInstanceId = instanceId
                    lastPlaying = null
                    lastPositionBucket = null
                }
            }
            sendQueueSnapshot(upcoming)
            val volume = playback.volume.value
            if (lastVolume == null || abs(lastVolume!! - volume) > 0.01f) {
                if (sendPlaybackAction(PlaybackActionPayload(PlaybackActions.SET_VOLUME, volume = volume))) {
                    lastVolume = volume
                }
            }
            if (trackId != null && lastPlaying != playing) {
                if (
                    sendPlaybackAction(
                        PlaybackActionPayload(
                            if (playing) PlaybackActions.PLAY else PlaybackActions.PAUSE,
                            trackId,
                            position,
                            hostSequence = observedSequence,
                        ),
                    )
                ) {
                    lastPlaying = playing
                }
            } else if (trackId != null && playing && lastPositionBucket != positionBucket) {
                if (sendPlaybackAction(PlaybackActionPayload(PlaybackActions.PLAY, trackId, position, hostSequence = observedSequence))) {
                    lastPositionBucket = positionBucket
                }
            }
        }

    private fun currentUpcomingTrackInfo(): List<TrackInfo> {
        val snapshot = playback.currentQueueSnapshot()
        val queue = snapshot.displayedItems
        val currentId = playback.currentSong.value?.id
        val currentIndex = snapshot.currentIndex
        if (queue == upcomingQueueInput && currentId == upcomingCurrentId && currentIndex == upcomingCurrentIndex) {
            return upcomingTrackInfo
        }
        val index =
            currentIndex.takeIf { it in queue.indices && (currentId == null || queue[it].id == currentId) }
                ?: queue.indexOfFirst { it.id == currentId }
        upcomingQueueInput = queue
        upcomingCurrentId = currentId
        upcomingCurrentIndex = currentIndex
        upcomingTrackInfo = queue.drop(if (index >= 0) index + 1 else 0).map { it.toTrackInfo() }
        return upcomingTrackInfo
    }

    private suspend fun sendQueueSnapshot(queue: List<TrackInfo> = currentUpcomingTrackInfo()) {
        val title = playback.currentQueueTitle()
        if (queue == lastQueueSnapshot && title == lastQueueTitle) return
        if (
            sendPlaybackAction(
                PlaybackActionPayload(
                    PlaybackActions.SYNC_QUEUE,
                    trackId = playback.currentSong.value?.id,
                    queue = queue,
                    queueTitle = title,
                ),
            )
        ) {
            lastQueueSnapshot = queue
            lastQueueTitle = title
        }
    }

    private fun startPing(session: DefaultClientWebSocketSession) {
        pingJob?.cancel()
        pingJob =
            scope.launch {
                while (socket === session) {
                    send(MessageTypes.PING, pingPayload())
                    val interval =
                        if (serverClock.sampleCount() < INITIAL_CLOCK_SAMPLE_COUNT) {
                            INITIAL_PING_INTERVAL_MS
                        } else {
                            STEADY_PING_INTERVAL_MS
                        }
                    delay(interval.milliseconds)
                }
            }
    }

    private fun pingPayload(): PingPayload {
        val ping = PingPayload(monotonicClockMs(), ++pingSequence)
        synchronized(participationLock) {
            outstandingPings[ping.sequence] = ping.clientTime
            if (outstandingPings.size > MAX_TRACKED_REQUESTS) outstandingPings.remove(outstandingPings.keys.first())
        }
        val state = _roomState.value
        if (_role.value != ListenTogetherRoomRole.GUEST || !_isInRoom.value || state == null) return ping
        val withCanary =
            if (ProtocolFeatures.DESYNC_CANARY in serverFeatures) {
                ping.copy(hasStateHash = true, stateHash = roomStateHash(playback.currentSong.value?.id, currentUpcomingTrackInfo()))
            } else {
                ping
            }
        val serverNow = serverClock.serverTimeNow()
        val settled = loadingTrackId == null && pendingBarrier == null && hasLoadedRoomTrack && driftJob?.isActive != true
        if (ProtocolFeatures.DRIFT_REPORT !in serverFeatures || serverNow == null || !settled || !state.isPlaying) return withCanary
        return withCanary.copy(
            hasPosition = true,
            trackId = playback.currentSong.value?.id,
            position = playback.currentPositionMs(),
            positionCapturedAtServerTime = serverNow,
        )
    }

    private fun nextHostSequence(): Long = synchronized(participationLock) { ++hostSequence }

    private suspend fun sendPendingAction() {
        val action = pendingAction
        val delivered =
            when {
                action is PendingAction.Create -> send(MessageTypes.CREATE_ROOM, CreateRoomPayload(_username.value))
                action is PendingAction.Join -> send(MessageTypes.JOIN_ROOM, JoinRoomPayload(action.roomCode, _username.value))
                sessionToken != null -> send(MessageTypes.RECONNECT, ReconnectPayload(sessionToken!!))
                else -> true
            }
        if (delivered && pendingAction == action) {
            synchronized(participationLock) {
                if (pendingAction == action) {
                    pendingAction = null
                    awaitingRoomResponse = action != null
                    _joinRequestSubmitted.value = action is PendingAction.Join
                }
            }
        }
    }

    private fun startRoomRequestTimeout(requestGeneration: Long) {
        synchronized(participationLock) {
            roomRequestTimeoutJob?.cancel()
            _roomRequestPending.value = true
        }
        roomRequestTimeoutJob =
            scope.launch {
                delay(roomRequestTimeoutMs.coerceAtLeast(1L).milliseconds)
                val (session, connection, reconnect) =
                    synchronized(participationLock) {
                        if (roomRequestGeneration != requestGeneration || !hasPendingRoomRequest) {
                            return@launch
                        }
                        val expiredSession = socket
                        val expiredConnection = connectJob
                        val expiredReconnect = reconnectJob
                        socket = null
                        connectJob = null
                        reconnectJob = null
                        ++connectionGeneration
                        pendingAction = null
                        _joinRequestPending.value = false
                        _joinRequestSubmitted.value = false
                        awaitingRoomResponse = false
                        _roomRequestPending.value = false
                        requestedRoomCode = null
                        roomRequestTimeoutJob = null
                        ++roomRequestGeneration
                        _connectionState.value = ListenTogetherConnectionState.ERROR
                        Triple(expiredSession, expiredConnection, expiredReconnect)
                    }
                session?.cancel()
                withTimeoutOrNull(SEND_TIMEOUT_MS.milliseconds) { reconnect?.cancelAndJoin() }
                withTimeoutOrNull(SEND_TIMEOUT_MS.milliseconds) { connection?.cancelAndJoin() }
                emitFailure(ListenTogetherText.CONNECTION_FAILED)
            }
    }

    private fun handleDisconnect(generation: Long) {
        synchronized(participationLock) {
            if (generation != connectionGeneration || closed) return
            socket = null
            authenticatedConnectionGeneration = -1L
            _roomAuthenticated.value = false
            pingJob?.cancel()
            pingJob = null
            cancelDrift()
            cancelRemotePlayback()
            _connectionState.value = ListenTogetherConnectionState.DISCONNECTED
            if (awaitingRoomResponse && requestedRoomCode != null) {
                pendingAction = PendingAction.Join(requestedRoomCode!!)
                awaitingRoomResponse = false
                _joinRequestSubmitted.value = false
            }
            val shouldReconnect = sessionToken != null || _isInRoom.value || pendingAction != null
            if (!shouldReconnect) {
                clearRoomRequest()
                _connectionState.value = ListenTogetherConnectionState.ERROR
                emitFailure(ListenTogetherText.CONNECTION_FAILED)
                return
            }
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                if (sessionReconnectRejected) cleanupRoom(clearSession = true)
                _connectionState.value = ListenTogetherConnectionState.ERROR
                emitFailure(ListenTogetherText.CONNECTION_FAILED)
                return
            }
            val announcedDelay = restartDelayMs
            restartDelayMs = null
            if (announcedDelay == null) reconnectAttempts++
            val attempt = reconnectAttempts.coerceAtLeast(1)
            val delayMs = (announcedDelay ?: reconnectDelayMillis(attempt)).coerceAtLeast(0L)
            _connectionState.value = ListenTogetherConnectionState.RECONNECTING
            _events.tryEmit(ListenTogetherEvent.Reconnecting(attempt, MAX_RECONNECT_ATTEMPTS))
            reconnectJob?.cancel()
            reconnectJob =
                scope.launch {
                    delay(delayMs.milliseconds)
                    connect()
                }
        }
    }

    internal suspend fun handleBinary(
        data: ByteArray,
        expectedConnectionGeneration: Long? = null,
    ) {
        if (!Settings.shared.listenTogetherEnabled) return
        val generation = expectedConnectionGeneration ?: connectionGeneration
        if (generation != connectionGeneration) return
        val decoded =
            try {
                withContext(codecDispatcher) { codec.decodeEnvelope(data) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                log(ListenTogetherLogLevel.WARNING, "Invalid protocol envelope")
                return
            }
        if (generation != connectionGeneration) return
        val type = decoded.type
        val payload =
            try {
                withContext(codecDispatcher) { codec.decodePayload(type, decoded.payload) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                log(ListenTogetherLogLevel.WARNING, "Invalid protocol payload")
                return
            }
        if (generation != connectionGeneration || !Settings.shared.listenTogetherEnabled) return
        val requestType = takeRequestType(decoded.requestId)
        val suggestedTrackId = synchronized(participationLock) { suggestionRequests.remove(decoded.requestId) }
        val decidedSuggestionId = synchronized(participationLock) { suggestionDecisionRequests.remove(decoded.requestId) }
        when (type) {
            MessageTypes.ACK -> {
                if (decidedSuggestionId != null) {
                    _pendingSuggestions.value = _pendingSuggestions.value.filterNot { it.id == decidedSuggestionId }
                    val automatic = synchronized(participationLock) { autoApprovingSuggestions.remove(decidedSuggestionId) }
                    if (automatic != null && requestType == MessageTypes.APPROVE_SUGGESTION &&
                        _role.value == ListenTogetherRoomRole.HOST && _isInRoom.value
                    ) {
                        _events.emit(ListenTogetherEvent.SuggestionAutoApproved(automatic))
                    }
                }
            }

            MessageTypes.SERVER_RESTARTING -> {
                val restart = payload as? ServerRestartingPayload ?: return
                val eta = restart.etaMs.coerceIn(0L, 60_000L)
                restartDelayMs = eta + Random.nextLong(eta / 2 + 1)
                log(ListenTogetherLogLevel.INFO, "Server restarting; reconnecting in ${restartDelayMs}ms")
            }

            MessageTypes.SUGGESTION_SUBMITTED -> {
                if (payload is SuggestionSubmittedPayload && _role.value == ListenTogetherRoomRole.GUEST) {
                    synchronized(participationLock) { outgoingSuggestions.remove(payload.trackInfo.id) }
                    _submittedSuggestions.value += payload.suggestionId to payload.trackInfo
                    emitNotice(ListenTogetherText.SUGGESTION_SUBMITTED)
                }
            }

            MessageTypes.ROOM_CREATED -> {
                handleRoomCreated(payload as? RoomCreatedPayload ?: return)
            }

            MessageTypes.JOIN_REQUEST -> {
                handleJoinRequest(payload as? JoinRequestPayload ?: return)
            }

            MessageTypes.JOIN_APPROVED -> {
                handleJoinApproved(payload as? JoinApprovedPayload ?: return)
            }

            MessageTypes.JOIN_REJECTED -> {
                if (claimRoomRequest()) {
                    emitFailure(ListenTogetherText.JOIN_REJECTED, (payload as? JoinRejectedPayload)?.reason)
                }
            }

            MessageTypes.USER_JOINED -> {
                handleUserJoined(payload as? UserJoinedPayload ?: return)
            }

            MessageTypes.USER_LEFT -> {
                handleUserLeft(payload as? UserLeftPayload ?: return)
            }

            MessageTypes.HOST_CHANGED -> {
                handleHostChanged(payload as? HostChangedPayload ?: return)
            }

            MessageTypes.SYNC_PLAYBACK -> {
                handlePlaybackAction(payload as? PlaybackActionPayload ?: return)
            }

            MessageTypes.SYNC_STATE -> {
                handleSyncState(payload as? SyncStatePayload ?: return)
            }

            MessageTypes.PONG -> {
                handlePong(payload as? PongPayload ?: return)
            }

            MessageTypes.BUFFER_WAIT -> {
                if (payload is BufferWaitPayload) {
                    log(ListenTogetherLogLevel.DEBUG, presentationText.text(ListenTogetherText.BUFFER_WAIT))
                }
            }

            MessageTypes.BUFFER_COMPLETE -> {
                if (payload is BufferCompletePayload) completeBarrier(payload.trackId)
            }

            MessageTypes.RECONNECTED -> {
                handleReconnected(payload as? ReconnectedPayload ?: return)
            }

            MessageTypes.USER_RECONNECTED -> {
                updateConnected(payload as? UserReconnectedPayload ?: return, true)
            }

            MessageTypes.USER_DISCONNECTED -> {
                updateConnected(payload as? UserDisconnectedPayload ?: return, false)
            }

            MessageTypes.SUGGESTION_RECEIVED -> {
                handleSuggestion(payload as? SuggestionReceivedPayload ?: return)
            }

            MessageTypes.SUGGESTION_APPROVED -> {
                if (payload is SuggestionApprovedPayload) {
                    val autoApproved = synchronized(participationLock) { autoApprovingSuggestions.remove(payload.suggestionId) }
                    if (autoApproved != null && _role.value == ListenTogetherRoomRole.HOST && _isInRoom.value) {
                        _events.emit(ListenTogetherEvent.SuggestionAutoApproved(autoApproved))
                    }
                    if (payload.suggestionId in _submittedSuggestions.value) {
                        _submittedSuggestions.value -= payload.suggestionId
                        emitNotice(ListenTogetherText.SUGGESTION_APPROVED)
                    }
                    _pendingSuggestions.value = _pendingSuggestions.value.filterNot { it.id == payload.suggestionId }
                }
            }

            MessageTypes.SUGGESTION_REJECTED -> {
                if (payload is SuggestionRejectedPayload) {
                    synchronized(participationLock) { autoApprovingSuggestions.remove(payload.suggestionId) }
                    if (payload.suggestionId in _submittedSuggestions.value) {
                        _submittedSuggestions.value -= payload.suggestionId
                        emitNotice(ListenTogetherText.SUGGESTION_REJECTED)
                    }
                    _pendingSuggestions.value = _pendingSuggestions.value.filterNot { it.id == payload.suggestionId }
                }
            }

            MessageTypes.KICKED -> {
                val reason = (payload as? KickedPayload)?.reason
                cleanupRoom(clearSession = true)
                emitFailure(ListenTogetherText.KICKED, reason)
            }

            MessageTypes.ERROR -> {
                val error = payload as? ErrorPayload ?: return
                if (decidedSuggestionId != null) {
                    synchronized(participationLock) { autoApprovingSuggestions.remove(decidedSuggestionId) }
                    if (error.code == "suggestion_not_found") {
                        _pendingSuggestions.value = _pendingSuggestions.value.filterNot { it.id == decidedSuggestionId }
                    }
                }
                if (suggestedTrackId != null) {
                    synchronized(participationLock) { outgoingSuggestions.remove(suggestedTrackId) }
                    if (error.code == "duplicate_suggestion") {
                        emitNotice(ListenTogetherText.SUGGESTION_SUBMITTED)
                        return
                    }
                }
                if (isBackgroundRequestError(requestType, error)) {
                    log(ListenTogetherLogLevel.WARNING, "$requestType rejected: ${error.code}")
                    if (error.code == "sync_rate_limited") {
                        scope.launch {
                            delay(SYNC_RETRY_DELAY_MS.milliseconds)
                            requestSync()
                        }
                    }
                    return
                }
                if (sessionToken != null && error.code == "session_not_found") {
                    sessionReconnectRejected = true
                    val session = socket
                    socket = null
                    pingJob?.cancel()
                    pingJob = null
                    session?.cancel()
                    connectJob?.cancel()
                } else {
                    if (
                        error.code == "user_not_found" ||
                        error.code == "session_not_found" ||
                        error.code == "session_expired" ||
                        error.code == "room_not_found" ||
                        error.code == "not_in_room" ||
                        error.message == "User not found in room" ||
                        error.message == "You are not in a room"
                    ) {
                        cleanupRoom(clearSession = true)
                    } else {
                        clearRoomRequest()
                    }
                    val key = if (error.code == "queue_too_large") ListenTogetherText.QUEUE_TOO_LARGE else ListenTogetherText.SERVER_ERROR
                    emitFailure(key, error.message)
                }
            }

            MessageTypes.SERVER_CAPABILITIES -> {
                val capabilities = payload as? ServerCapabilities ?: return
                if (capabilities.supportsProtobuf) {
                    codec.compressionEnabled = capabilities.supportsCompression
                } else {
                    log(ListenTogetherLogLevel.ERROR, "Server protocol became incompatible")
                    val session = socket
                    socket = null
                    ++connectionGeneration
                    _roomAuthenticated.value = false
                    pingJob?.cancel()
                    clearRoomRequest()
                    _connectionState.value = ListenTogetherConnectionState.ERROR
                    emitFailure(ListenTogetherText.CONNECTION_FAILED)
                    runCatching {
                        session?.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Protobuf support required"))
                    }
                }
            }
        }
    }

    private fun handleRoomCreated(payload: RoomCreatedPayload) {
        if (!claimRoomRequest()) return
        sessionToken = payload.sessionToken
        restoredRoomCode = payload.roomCode
        sessionStartedAt = wallClockMs()
        _userId.value = payload.userId
        _role.value = ListenTogetherRoomRole.HOST
        authenticatedConnectionGeneration = connectionGeneration
        _roomAuthenticated.value = true
        resetHostObservation()
        revisionFilter.reset()
        _roomState.value =
            RoomState(
                roomCode = payload.roomCode,
                hostId = payload.userId,
                users = listOf(UserInfo(payload.userId, _username.value, true)),
                currentTrack = playback.currentSong.value?.toTrackInfo(),
                isPlaying = playback.playing.value,
                position = playback.currentPositionMs(),
                lastUpdate = wallClockMs(),
                volume = playback.volume.value,
                queue = currentUpcomingTrackInfo(),
                queueTitle = playback.currentQueueTitle(),
            )
        persistSession(isHost = true)
        markRoomActive()
        emitNotice(ListenTogetherText.ROOM_CREATED)
    }

    private fun handleJoinRequest(payload: JoinRequestPayload) {
        if (_role.value != ListenTogetherRoomRole.HOST) return
        if (ListenTogetherModeration.isBlocked(payload.username, _blockedUsernames.value)) {
            rejectJoin(payload.userId, RejectionReasons.USER_IS_BLOCKED)
        } else if (_autoApproveJoinRequests.value) {
            approveJoin(payload.userId)
        } else {
            _pendingJoinRequests.value = _pendingJoinRequests.value.filterNot { it.userId == payload.userId } +
                ListenTogetherJoinRequest(payload.userId, payload.username)
        }
    }

    private suspend fun handleJoinApproved(payload: JoinApprovedPayload) {
        if (!claimRoomRequest()) return
        cancelRemotePlayback()
        sessionToken = payload.sessionToken
        restoredRoomCode = payload.roomCode
        sessionStartedAt = wallClockMs()
        _userId.value = payload.userId
        _role.value = ListenTogetherRoomRole.GUEST
        authenticatedConnectionGeneration = connectionGeneration
        _roomAuthenticated.value = true
        revisionFilter.reset(payload.state.revision)
        _roomState.value = payload.state
        persistSession(isHost = false)
        markRoomActive()
        emitNotice(ListenTogetherText.JOINED_ROOM)
        applyRoomState(payload.state, barrier = false)
    }

    private fun handleUserJoined(payload: UserJoinedPayload) {
        _roomState.value =
            _roomState.value?.let { state ->
                state.copy(
                    users = state.users.filterNot { it.userId == payload.userId } + UserInfo(payload.userId, payload.username, false),
                )
            }
        _pendingJoinRequests.value = _pendingJoinRequests.value.filterNot { it.userId == payload.userId }
        log(ListenTogetherLogLevel.INFO, presentationText.text(ListenTogetherText.USER_JOINED))
    }

    private fun handleUserLeft(payload: UserLeftPayload) {
        _roomState.value = _roomState.value?.let { it.copy(users = it.users.filterNot { user -> user.userId == payload.userId }) }
        log(ListenTogetherLogLevel.INFO, presentationText.text(ListenTogetherText.USER_LEFT))
    }

    private suspend fun handleHostChanged(payload: HostChangedPayload) {
        val becameHost = _userId.value == payload.newHostId
        if (becameHost) {
            cancelRemotePlayback()
            _failedRoomTrack.value = null
        }
        _roomState.value =
            _roomState.value?.let { state ->
                state.copy(
                    hostId = payload.newHostId,
                    users = state.users.map { it.copy(isHost = it.userId == payload.newHostId) },
                )
            }
        if (!becameHost) synchronized(participationLock) { autoApprovingSuggestions.clear() }
        _role.value = if (becameHost) ListenTogetherRoomRole.HOST else ListenTogetherRoomRole.GUEST
        if (becameHost) {
            resetHostObservation()
            _roomState.value?.let { applyRoomState(it, barrier = false) }
        }
        persistSession(isHost = becameHost)
        emitNotice(if (becameHost) ListenTogetherText.BECAME_HOST else ListenTogetherText.HOST_CHANGED)
    }

    private suspend fun handlePlaybackAction(payload: PlaybackActionPayload) {
        if (hasOlderRevisionTimestamp(payload.revision, payload.serverTime)) return
        if (!revisionFilter.accept(payload.revision)) {
            log(ListenTogetherLogLevel.DEBUG, "Stale playback revision ignored")
            return
        }
        if (
            payload.action == PlaybackActions.QUEUE_ADD ||
            payload.action == PlaybackActions.QUEUE_REMOVE ||
            payload.action == PlaybackActions.QUEUE_CLEAR ||
            payload.action == PlaybackActions.SYNC_QUEUE
        ) {
            hostPublicationMutex.withLock {
                val previous = _roomState.value
                _roomState.value = _roomState.value?.let { ListenTogetherProtocolReducer.reduce(it, payload) }
                // Reapplying host acknowledgements could undo newer local queue edits.
                val legacyEchoIndex =
                    if (_role.value == ListenTogetherRoomRole.HOST && payload.originUserId == null &&
                        payload.action == PlaybackActions.SYNC_QUEUE
                    ) {
                        synchronized(participationLock) {
                            pendingHostQueues.indexOf(payload.queue.orEmpty()).also { index ->
                                repeat(index + 1) { pendingHostQueues.removeFirst() }
                            }
                        }
                    } else {
                        -1
                    }
                val ownEcho =
                    _role.value == ListenTogetherRoomRole.HOST &&
                        (payload.originUserId == _userId.value || legacyEchoIndex >= 0)
                if (ownEcho) _roomState.value = _roomState.value?.copy(queue = currentUpcomingTrackInfo())
                if (_isInRoom.value && _role.value != ListenTogetherRoomRole.NONE && !ownEcho) {
                    val published = lastQueueSnapshot
                    val canonical =
                        if (_role.value == ListenTogetherRoomRole.HOST && payload.revision == 0L &&
                            payload.action != PlaybackActions.SYNC_QUEUE && previous != null && published != null
                        ) {
                            ListenTogetherProtocolReducer.reduce(previous.copy(queue = published), payload).queue
                        } else {
                            _roomState.value?.queue.orEmpty()
                        }
                    applyCanonicalQueue(canonical)
                    if (_role.value == ListenTogetherRoomRole.HOST) {
                        _roomState.value = _roomState.value?.copy(queue = currentUpcomingTrackInfo())
                    }
                }
            }
            return
        }
        _roomState.value = _roomState.value?.let { ListenTogetherProtocolReducer.reduce(it, payload) }
        if (_role.value != ListenTogetherRoomRole.GUEST) return
        when (payload.action) {
            PlaybackActions.PLAY -> {
                if (updatePendingBarrier(payload, playing = true)) return
                if (loadingTrackId == null && hasLoadedRoomTrack) {
                    payload.position?.let { syncPosition(it, payload.serverTime, true) }
                    playback.setPlayingFromRemote(true)
                }
            }

            PlaybackActions.PAUSE -> {
                if (updatePendingBarrier(payload, playing = false)) return
                cancelDrift()
                if (loadingTrackId == null && hasLoadedRoomTrack) {
                    payload.position?.let { playback.seekToFromRemote(it) }
                }
                playback.setPlayingFromRemote(false)
            }

            PlaybackActions.SEEK -> {
                if (updatePendingBarrier(payload)) return
                if (loadingTrackId == null && hasLoadedRoomTrack) {
                    payload.position?.let { syncPosition(it, payload.serverTime, _roomState.value?.isPlaying == true) }
                }
            }

            PlaybackActions.CHANGE_TRACK -> {
                payload.trackInfo?.let { track ->
                    if (
                        (track.id == playback.currentSong.value?.id && playback.readiness.value != PlaybackReadiness.FAILED) ||
                        track.id == loadingTrackId
                    ) {
                        if (payload.replaceQueue || payload.revision > 0L) applyCanonicalQueue(_roomState.value?.queue.orEmpty())
                        if (loadingTrackId == null) acknowledgeSameTrackChange(track, payload)
                        return@let
                    }
                    startRemotePlayback(
                        track = track,
                        queue = payload.queue.orEmpty().takeIf { payload.replaceQueue || payload.revision > 0L },
                        position = payload.position ?: 0L,
                        playing = false,
                        effectiveAtServerTime = payload.serverTime,
                        barrier = true,
                        trackRevision = payload.revision,
                    )
                }
            }

            PlaybackActions.SKIP_NEXT -> {
                if (loadingTrackId != null || !hasLoadedRoomTrack) return
                cancelRemotePlayback()
                playback.skipToNextFromRemote()
            }

            PlaybackActions.SKIP_PREV -> {
                if (loadingTrackId != null || !hasLoadedRoomTrack) return
                cancelRemotePlayback()
                playback.skipToPreviousFromRemote()
            }

            PlaybackActions.SET_VOLUME -> {
                applyHostVolume(payload.volume)
            }
        }
    }

    private suspend fun handleSyncState(payload: SyncStatePayload) {
        if (hasOlderRevisionTimestamp(payload.revision, payload.lastUpdate)) return
        if (!revisionFilter.accept(payload.revision)) return
        _roomState.value =
            _roomState.value?.copy(
                currentTrack = payload.currentTrack,
                isPlaying = payload.isPlaying,
                position = payload.position,
                lastUpdate = payload.lastUpdate,
                queue = payload.queue ?: _roomState.value?.queue.orEmpty(),
                volume = payload.volume ?: _roomState.value?.volume ?: 1f,
                revision = maxOf(_roomState.value?.revision ?: 0L, payload.revision),
                queueTitle = payload.queueTitle,
            )
        if (_role.value != ListenTogetherRoomRole.GUEST) return
        val track = payload.currentTrack
        if (track == null) {
            _failedRoomTrack.value = null
            cancelRemotePlayback()
            playback.setPlayingFromRemote(false)
            applyHostVolume(payload.volume)
            return
        }
        if (hasLoadedRoomTrack || loadingTrackId == track.id) {
            payload.queue?.let { applyCanonicalQueue(it) }
            if (!updatePendingBarrier(track.id, payload.position, payload.lastUpdate, payload.isPlaying) && loadingTrackId == null) {
                syncPosition(payload.position, payload.lastUpdate, payload.isPlaying)
                playback.setPlayingFromRemote(payload.isPlaying)
            }
        } else {
            startRemotePlayback(track, payload.queue, payload.position, payload.isPlaying, payload.lastUpdate, barrier = false)
        }
        applyHostVolume(payload.volume)
    }

    private fun handlePong(payload: PongPayload) {
        if (payload.sequence <= lastPongSequence) return
        if (synchronized(participationLock) { outstandingPings[payload.sequence] } != payload.clientTime) return
        val previousSampleCount = serverClock.sampleCount()
        val firstSample = serverClock.recordSample(payload.clientTime, payload.serverReceiveTime, payload.serverSendTime)
        if (serverClock.sampleCount() == previousSampleCount) return
        lastPongSequence = payload.sequence
        synchronized(participationLock) { outstandingPings.keys.removeAll { it <= payload.sequence } }
        if (payload.driftValid) correctReportedDrift(payload.driftMs)
        if (
            firstSample &&
            _isInRoom.value &&
            _role.value == ListenTogetherRoomRole.GUEST &&
            authenticatedConnectionGeneration == connectionGeneration
        ) {
            requestSync()
        }
    }

    private fun correctReportedDrift(driftMs: Long) {
        val state = _roomState.value ?: return
        if (_role.value != ListenTogetherRoomRole.GUEST || !_isInRoom.value || !state.isPlaying) return
        if (loadingTrackId != null || pendingBarrier != null || !hasLoadedRoomTrack || driftJob?.isActive == true) return
        val generation = playbackGeneration
        scope.launch {
            val serverNow = serverClock.serverTimeNow() ?: return@launch
            if (generation != playbackGeneration || loadingTrackId != null) return@launch
            syncPosition(playback.currentPositionMs() - driftMs, serverNow, true)
        }
    }

    internal suspend fun handleReconnected(payload: ReconnectedPayload) {
        cancelRemotePlayback()
        synchronized(participationLock) {
            if (closed) return
            if (!payload.isHost || _roomState.value?.roomCode != payload.roomCode) autoApprovingSuggestions.clear()
            restoredRoomCode = payload.roomCode
            _userId.value = payload.userId
            _role.value = if (payload.isHost) ListenTogetherRoomRole.HOST else ListenTogetherRoomRole.GUEST
            authenticatedConnectionGeneration = connectionGeneration
            _roomAuthenticated.value = true
            if (payload.isHost) resetHostObservation()
            revisionFilter.reset(payload.state.revision)
            _roomState.value = payload.state
            persistSession(payload.isHost)
            markRoomActive()
            reconnectAttempts = 0
            sessionReconnectRejected = false
        }
        emitNotice(ListenTogetherText.RECONNECTED)
        if (payload.isHost) {
            syncHostReconnectedPlayback(payload.state)
        } else {
            applyRoomState(payload.state, barrier = false)
            sendPendingSuggestions()
        }
    }

    private suspend fun syncHostReconnectedPlayback(roomState: RoomState) {
        val song = playback.currentSong.value
        val trackId = song?.id
        val roomTrackId = roomState.currentTrack?.id
        if (trackId != null && trackId == roomTrackId) {
            val playing = playback.playing.value
            val position = playback.currentPositionMs()
            val positionBucket = position / 3_000L
            val upcoming = currentUpcomingTrackInfo()
            val volume = playback.volume.value
            lastTrackId = trackId
            lastTrackInstanceId = playback.playbackInstanceId()
            lastPlaying = playing
            lastPositionBucket = positionBucket
            lastQueueSnapshot = roomState.queue
            lastVolume = roomState.volume
            _roomState.value =
                _roomState.value?.copy(
                    currentTrack = song.toTrackInfo(),
                    isPlaying = playing,
                    position = position,
                    lastUpdate = serverClock.serverTimeNow() ?: wallClockMs(),
                    volume = volume,
                    queue = upcoming,
                    queueTitle = playback.currentQueueTitle(),
                )
            sendPlaybackAction(
                PlaybackActionPayload(
                    action = if (playing) PlaybackActions.PLAY else PlaybackActions.PAUSE,
                    trackId = trackId,
                    position = position,
                    hostSequence = nextHostSequence(),
                ),
            )
            sendQueueSnapshot(upcoming)
        } else {
            resetHostObservation()
        }
    }

    private fun updateConnected(
        payload: Any,
        connected: Boolean,
    ) {
        val id =
            when (payload) {
                is UserReconnectedPayload -> payload.userId
                is UserDisconnectedPayload -> payload.userId
                else -> return
            }
        _roomState.value =
            _roomState.value?.let { state ->
                state.copy(users = state.users.map { if (it.userId == id) it.copy(isConnected = connected) else it })
            }
    }

    private fun handleSuggestion(payload: SuggestionReceivedPayload) {
        if (_role.value != ListenTogetherRoomRole.HOST) return
        val suggestion =
            ListenTogetherSuggestion(
                payload.suggestionId,
                payload.fromUserId,
                payload.fromUsername,
                payload.trackInfo,
            )
        _pendingSuggestions.value = _pendingSuggestions.value.filterNot { it.id == payload.suggestionId } + suggestion
        if (_autoApproveSongSuggestions.value) {
            synchronized(participationLock) { autoApprovingSuggestions[payload.suggestionId] = suggestion }
            sendSuggestionApproval(payload.suggestionId)
        }
        log(ListenTogetherLogLevel.INFO, presentationText.text(ListenTogetherText.SUGGESTION_RECEIVED))
    }

    private suspend fun applyRoomState(
        state: RoomState,
        barrier: Boolean,
    ) {
        if (_role.value == ListenTogetherRoomRole.NONE) return
        val track = state.currentTrack
        if (track != null && (hasLoadedRoomTrack || loadingTrackId == track.id)) {
            applyCanonicalQueue(state.queue)
            if (!updatePendingBarrier(track.id, state.position, state.lastUpdate, state.isPlaying) && loadingTrackId == null) {
                syncPosition(state.position, state.lastUpdate, state.isPlaying)
                playback.setPlayingFromRemote(state.isPlaying)
            }
        } else if (track != null) {
            startRemotePlayback(track, state.queue, state.position, state.isPlaying, state.lastUpdate, barrier)
        } else {
            playback.setPlayingFromRemote(false)
        }
        applyHostVolume(state.volume)
    }

    private fun startRemotePlayback(
        track: TrackInfo,
        queue: List<TrackInfo>?,
        position: Long,
        playing: Boolean,
        effectiveAtServerTime: Long?,
        barrier: Boolean,
        trackRevision: Long = 0L,
    ) {
        remotePlaybackJob?.cancel()
        cancelSameTrackChange()
        readinessRecoveryJob?.cancel()
        cancelDrift()
        val generation = ++playbackGeneration
        pendingBarrier = null
        pendingCanonicalQueue = null
        loadingTrackId = track.id
        _failedRoomTrack.value = null
        val currentStateQueue = _roomState.value?.takeIf { it.currentTrack?.id == track.id }?.queue
        val effectiveQueue = if (queue == null) null else currentStateQueue ?: queue
        loadingQueue = effectiveQueue
        remotePlaybackJob =
            scope.launch {
                applyingRemoteState = true
                try {
                    playback.setPlayingFromRemote(false)
                    val queuedSong = playback.currentQueueSnapshot().displayedItems.firstOrNull { it.id == track.id }
                    val currentSong =
                        queuedSong?.let { track.mergeInto(it) }
                            ?: resolver.resolve(track) ?: return@launch recoverFromLoadFailure(track.id)
                    if (generation != playbackGeneration) return@launch
                    val songs =
                        if (effectiveQueue == null) {
                            listOf(currentSong)
                        } else {
                            listOf(currentSong) + effectiveQueue.map { it.toSongItem() }
                        }
                    playback.playQueueFromRemote(songs, startIndex = 0, title = _roomState.value?.queueTitle)
                    if (!awaitReady(track.id) || generation != playbackGeneration) {
                        recoverFromLoadFailure(track.id)
                        return@launch
                    }
                    val latest = _roomState.value?.takeIf { it.currentTrack?.id == track.id }
                    val latestPosition = latest?.position ?: position
                    val latestTime = latest?.lastUpdate ?: effectiveAtServerTime
                    val latestPlaying = latest?.isPlaying ?: playing
                    if (barrier) {
                        pendingBarrier = PendingBarrier(track.id, latestPosition, latestTime, latestPlaying, generation)
                        playback.seekToFromRemote(adjustedPosition(latestPosition, latestTime, latestPlaying))
                        send(MessageTypes.BUFFER_READY, BufferReadyPayload(track.id, trackRevision))
                        loadingTrackId = null
                        loadingQueue = null
                        applyPendingCanonicalQueue()
                        startBarrierTimeout(track.id)
                    } else {
                        loadingTrackId = null
                        loadingQueue = null
                        applyPendingCanonicalQueue()
                        syncPosition(latestPosition, latestTime, latestPlaying)
                        playback.setPlayingFromRemote(latestPlaying)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (generation == playbackGeneration) recoverFromLoadFailure(track.id)
                } finally {
                    if (generation == playbackGeneration) {
                        applyingRemoteState = false
                        loadingTrackId = null
                        loadingQueue = null
                        remotePlaybackJob = null
                    }
                }
            }
    }

    private fun acknowledgeSameTrackChange(
        track: TrackInfo,
        payload: PlaybackActionPayload,
    ) {
        val generation = playbackGeneration
        val pending = pendingBarrier?.takeIf { it.trackId == track.id && it.generation == generation }
        val roomState = _roomState.value?.takeIf { it.currentTrack?.id == track.id }
        val position = roomState?.position ?: payload.position ?: 0L
        val effectiveAtServerTime = roomState?.lastUpdate ?: payload.serverTime
        val playing = roomState?.isPlaying ?: false
        if (pending != null) {
            pendingBarrier = pending.copy(position = position, effectiveAtServerTime = effectiveAtServerTime, playing = playing)
            return
        }

        cancelSameTrackChange()
        readinessRecoveryJob?.cancel()
        readinessRecoveryJob = null
        pendingBarrier = PendingBarrier(track.id, position, effectiveAtServerTime, playing, generation, ready = false)
        val requestGeneration = sameTrackChangeGeneration
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!awaitReady(track.id)) {
                        if (generation == playbackGeneration && requestGeneration == sameTrackChangeGeneration) {
                            recoverFromLoadFailure(track.id)
                        }
                        return@launch
                    }
                    if (
                        generation != playbackGeneration ||
                        requestGeneration != sameTrackChangeGeneration ||
                        _role.value != ListenTogetherRoomRole.GUEST ||
                        !_isInRoom.value ||
                        playback.currentSong.value?.id != track.id
                    ) {
                        if (generation == playbackGeneration && pendingBarrier?.trackId == track.id && pendingBarrier?.ready == false) {
                            pendingBarrier = null
                        }
                        return@launch
                    }
                    val latest =
                        pendingBarrier?.takeIf { it.trackId == track.id && it.generation == generation && !it.ready } ?: return@launch
                    cancelDrift()
                    playback.seekToFromRemote(adjustedPosition(latest.position, latest.effectiveAtServerTime, latest.playing))
                    if (
                        generation != playbackGeneration ||
                        requestGeneration != sameTrackChangeGeneration ||
                        _role.value != ListenTogetherRoomRole.GUEST ||
                        !_isInRoom.value ||
                        playback.currentSong.value?.id != track.id
                    ) {
                        if (generation == playbackGeneration && pendingBarrier?.trackId == track.id && pendingBarrier?.ready == false) {
                            pendingBarrier = null
                        }
                        return@launch
                    }
                    val readyPending =
                        pendingBarrier?.takeIf { it.trackId == track.id && it.generation == generation && !it.ready }
                            ?: return@launch
                    pendingBarrier = readyPending.copy(ready = true)
                    playback.setPlayingFromRemote(false)
                    send(MessageTypes.BUFFER_READY, BufferReadyPayload(track.id, payload.revision))
                    startBarrierTimeout(track.id)
                } finally {
                    if (sameTrackChangeJob === coroutineContext[Job]) sameTrackChangeJob = null
                }
            }
        sameTrackChangeJob = job
        job.start()
    }

    private suspend fun awaitReady(trackId: String): Boolean =
        withTimeoutOrNull(READY_TIMEOUT_MS.milliseconds) {
            while (
                playback.currentSong.value?.id != trackId ||
                playback.readiness.value != PlaybackReadiness.READY
            ) {
                if (playback.readiness.value == PlaybackReadiness.FAILED) return@withTimeoutOrNull false
                delay(50.milliseconds)
            }
            true
        } == true

    private suspend fun recoverFromLoadFailure(trackId: String) {
        if (loadingTrackId != trackId && pendingBarrier?.trackId != trackId) return
        playback.setPlayingFromRemote(false)
        _failedRoomTrack.value = _roomState.value?.currentTrack?.takeIf { it.id == trackId }
        emitFailure(ListenTogetherText.TRACK_LOAD_FAILED)
        pendingBarrier = null
        loadingTrackId = null
        loadingQueue = null
        pendingCanonicalQueue = null
        readinessRecoveryJob?.cancel()
        readinessRecoveryJob =
            scope.launch {
                delay(1_000.milliseconds)
                if (_role.value == ListenTogetherRoomRole.GUEST && _isInRoom.value) requestSync()
            }
    }

    private fun startBarrierTimeout(trackId: String) {
        readinessRecoveryJob?.cancel()
        readinessRecoveryJob =
            scope.launch {
                delay(BARRIER_TIMEOUT_MS.milliseconds)
                if (pendingBarrier?.trackId == trackId) {
                    pendingBarrier = null
                    requestSync()
                }
            }
    }

    private suspend fun completeBarrier(trackId: String) {
        val pending = pendingBarrier?.takeIf { it.trackId == trackId && it.generation == playbackGeneration && it.ready }
        if (pending == null) return
        readinessRecoveryJob?.cancel()
        readinessRecoveryJob = null
        pendingBarrier = null
        if (pending.playing) {
            syncPosition(pending.position, pending.effectiveAtServerTime, true)
        } else {
            cancelDrift()
            playback.seekToFromRemote(adjustedPosition(pending.position, pending.effectiveAtServerTime, false))
        }
        playback.setPlayingFromRemote(pending.playing)
    }

    private suspend fun applyCanonicalQueue(queue: List<TrackInfo>) {
        val canonical = canonicalUpcomingQueue(queue)
        if (loadingTrackId != null || (_role.value == ListenTogetherRoomRole.GUEST && !hasLoadedRoomTrack)) {
            pendingCanonicalQueue = canonical.takeIf { it != loadingQueue }
            return
        }
        val currentId = playback.currentSong.value?.id
        val snapshot = playback.currentQueueSnapshot()
        val currentQueue = snapshot.displayedItems
        val currentIndex =
            snapshot.currentIndex.takeIf { it in currentQueue.indices && currentQueue[it].id == currentId }
                ?: currentQueue.indexOfFirst { it.id == currentId }
        val localUpcoming = if (currentId == null) currentQueue else currentQueue.drop(currentIndex + 1)
        val published = lastQueueSnapshot
        val target =
            if (_role.value == ListenTogetherRoomRole.HOST && published != null && currentUpcomingTrackInfo() != published) {
                reconcileHostQueue(published, currentUpcomingTrackInfo(), canonical)
            } else {
                canonical
            }
        val localById = localUpcoming.groupBy { it.id }.mapValues { (_, songs) -> ArrayDeque(songs) }
        val merged = target.map { track -> track.mergeInto(localById[track.id]?.removeFirstOrNull()) }
        if (merged != localUpcoming) playback.replaceUpcomingQueueFromRemote(merged)
        if (_role.value == ListenTogetherRoomRole.GUEST) playback.setQueueTitleFromRemote(_roomState.value?.queueTitle)
        if (_role.value == ListenTogetherRoomRole.HOST) {
            lastQueueSnapshot = canonical
        }
    }

    private suspend fun applyPendingCanonicalQueue() {
        val pending = pendingCanonicalQueue ?: return
        pendingCanonicalQueue = null
        applyCanonicalQueue(pending)
    }

    private fun hasOlderRevisionTimestamp(
        revision: Long,
        timestamp: Long?,
    ): Boolean {
        val state = _roomState.value ?: return false
        return revision > 0L && revision == state.revision && timestamp != null && timestamp < state.lastUpdate
    }

    private suspend fun syncPosition(
        position: Long,
        effectiveAtServerTime: Long?,
        playing: Boolean,
    ) {
        val target = adjustedPosition(position, effectiveAtServerTime, playing)
        when (
            val correction =
                driftCorrection(
                    playback.currentPositionMs(),
                    target,
                    playing,
                    effectiveAtServerTime != null && serverClock.serverTimeNow() != null,
                )
        ) {
            DriftCorrection.None -> {
                cancelDrift()
            }

            is DriftCorrection.Seek -> {
                cancelDrift()
                playback.seekToFromRemote(correction.positionMs)
            }

            is DriftCorrection.Soft -> {
                startDrift(position, effectiveAtServerTime ?: return, correction)
            }
        }
    }

    private fun adjustedPosition(
        position: Long,
        effectiveAtServerTime: Long?,
        playing: Boolean,
    ): Long = serverClock.positionAt(position, effectiveAtServerTime, playing, playback.durationMs.value)

    private fun startDrift(
        anchor: Long,
        effectiveAt: Long,
        correction: DriftCorrection.Soft,
    ) {
        if (driftJob?.isActive == true) return
        val generation = ++driftGeneration
        driftJob =
            scope.launch {
                try {
                    playback.setPlaybackSpeedFromRemote(correction.speed)
                    val deadline = monotonicClockMs() + correction.durationMs
                    while (monotonicClockMs() < deadline) {
                        val drift = adjustedPosition(anchor, effectiveAt, true) - playback.currentPositionMs()
                        if (abs(drift) < 50L || (drift > 0) != (correction.speed > 1f)) break
                        delay(100.milliseconds)
                    }
                } finally {
                    if (generation == driftGeneration) {
                        playback.setPlaybackSpeedFromRemote(1f)
                        driftJob = null
                    }
                }
            }
    }

    private fun cancelDrift() {
        if (driftJob == null) return
        val generation = ++driftGeneration
        driftJob?.cancel()
        driftJob = null
        scope.launch {
            if (generation == driftGeneration) playback.setPlaybackSpeedFromRemote(1f)
        }
    }

    internal fun setGuestRoomStateForTesting(inRoom: Boolean = true) {
        _role.value = ListenTogetherRoomRole.GUEST
        _isInRoom.value = inRoom
    }

    internal suspend fun applyHostVolume(volume: Float?) {
        if (!_syncHostVolume.value || _role.value != ListenTogetherRoomRole.GUEST || !_isInRoom.value) return
        if (playback.volume.value <= 0.01f) return
        playback.setVolumeFromRemote(volume?.coerceIn(0f, 1f) ?: return)
    }

    private fun updatePendingBarrier(
        payload: PlaybackActionPayload,
        playing: Boolean? = null,
    ): Boolean =
        updatePendingBarrier(
            trackId = payload.trackId ?: playback.currentSong.value?.id,
            position = payload.position,
            effectiveAtServerTime = payload.serverTime,
            playing = playing,
        )

    private fun updatePendingBarrier(
        trackId: String?,
        position: Long?,
        effectiveAtServerTime: Long?,
        playing: Boolean?,
    ): Boolean {
        val pending = pendingBarrier ?: return false
        val activeTrackId = playback.currentSong.value?.id ?: loadingTrackId
        if (pending.trackId != trackId || (activeTrackId != null && activeTrackId != pending.trackId)) return false
        pendingBarrier =
            pending.copy(
                position = position ?: pending.position,
                effectiveAtServerTime = effectiveAtServerTime ?: pending.effectiveAtServerTime,
                playing = playing ?: pending.playing,
            )
        return true
    }

    private fun cancelSameTrackChange() {
        sameTrackChangeGeneration++
        sameTrackChangeJob?.cancel()
        sameTrackChangeJob = null
        if (pendingBarrier?.ready == false) pendingBarrier = null
    }

    private fun cancelRemotePlayback() {
        remotePlaybackJob?.cancel()
        cancelSameTrackChange()
        cancelDrift()
        remotePlaybackJob = null
        readinessRecoveryJob?.cancel()
        readinessRecoveryJob = null
        ++playbackGeneration
        loadingTrackId = null
        loadingQueue = null
        pendingCanonicalQueue = null
        pendingBarrier = null
        applyingRemoteState = false
    }

    private fun markRoomActive() {
        _isInRoom.value = true
        scope.launch { playback.setPlaybackSpeedFromRemote(1f) }
    }

    private fun cleanupRoom(clearSession: Boolean) {
        val wasInRoom = _isInRoom.value
        clearRoomRequest()
        reconnectJob?.cancel()
        reconnectJob = null
        cancelRemotePlayback()
        queueImportJob?.cancel()
        queueImportJob = null
        cancelDrift()
        _roomState.value = null
        _role.value = ListenTogetherRoomRole.NONE
        _userId.value = null
        authenticatedConnectionGeneration = -1L
        _roomAuthenticated.value = false
        _isInRoom.value = false
        _joinRequestPending.value = false
        _joinRequestSubmitted.value = false
        _pendingJoinRequests.value = emptyList()
        synchronized(participationLock) { autoApprovingSuggestions.clear() }
        _pendingSuggestions.value = emptyList()
        _submittedSuggestions.value = emptyMap()
        synchronized(participationLock) {
            outgoingSuggestions.clear()
            suggestionRequests.clear()
            suggestionDecisionRequests.clear()
        }
        _failedRoomTrack.value = null
        _pendingQueueImport.value = null
        reconnectAttempts = 0
        sessionReconnectRejected = false
        resetHostObservation()
        revisionFilter.reset()
        serverClock.reset()
        if (clearSession) {
            sessionToken = null
            restoredRoomCode = null
            sessionStartedAt = 0L
            if (sessionPersistenceEnabled) Settings.shared.listenTogetherDesktopSession = ""
        }
        if (wasInRoom) {
            scope.launch {
                playback.setPlaybackSpeedFromRemote(Settings.shared.playbackSpeed.coerceIn(0.5f, 2f))
            }
        }
    }

    private fun clearRoomRequest() {
        synchronized(participationLock) {
            roomRequestTimeoutJob?.cancel()
            roomRequestTimeoutJob = null
            pendingAction = null
            _joinRequestPending.value = false
            _joinRequestSubmitted.value = false
            awaitingRoomResponse = false
            _roomRequestPending.value = false
            requestedRoomCode = null
            ++roomRequestGeneration
        }
    }

    private fun claimRoomRequest(): Boolean =
        synchronized(participationLock) {
            if (!hasPendingRoomRequest) {
                false
            } else {
                roomRequestTimeoutJob?.cancel()
                roomRequestTimeoutJob = null
                pendingAction = null
                _joinRequestPending.value = false
                _joinRequestSubmitted.value = false
                _roomRequestPending.value = false
                awaitingRoomResponse = false
                requestedRoomCode = null
                ++roomRequestGeneration
                true
            }
        }

    private fun launchPlaybackAction(payload: PlaybackActionPayload) {
        scope.launch { sendPlaybackAction(payload) }
    }

    private suspend fun sendPlaybackAction(payload: PlaybackActionPayload): Boolean {
        if (socket == null || authenticatedConnectionGeneration != connectionGeneration) return false
        if (ProtocolFeatures.ORIGIN_TAG !in serverFeatures &&
            payload.action == PlaybackActions.SYNC_QUEUE
        ) {
            synchronized(participationLock) {
                pendingHostQueues.addLast(payload.queue.orEmpty())
                if (pendingHostQueues.size > MAX_TRACKED_REQUESTS) pendingHostQueues.removeFirst()
            }
        }
        return send(MessageTypes.PLAYBACK_ACTION, payload.copy(capturedAtServerTime = serverClock.serverTimeNow()))
    }

    private fun launchSend(
        type: String,
        payload: Any? = null,
        onDelivered: () -> Unit = {},
    ) {
        if (socket == null || authenticatedConnectionGeneration != connectionGeneration) return
        val generation = connectionGeneration
        scope.launch {
            if (generation == connectionGeneration && send(type, payload) && generation == connectionGeneration) onDelivered()
        }
    }

    private suspend fun closeTransport(
        session: DefaultClientWebSocketSession?,
        connection: Job?,
        closeMessage: String?,
    ) {
        if (session != null) {
            val closedCleanly =
                closeMessage?.let { message ->
                    withTimeoutOrNull(SEND_TIMEOUT_MS.milliseconds) {
                        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, message)) }.isSuccess
                    }
                } == true
            if (!closedCleanly) session.cancel()
        }
        withTimeoutOrNull(SEND_TIMEOUT_MS.milliseconds) { connection?.cancelAndJoin() }
    }

    private suspend fun encode(
        type: String,
        payload: Any?,
        requestId: Long = 0L,
        compressionEnabled: Boolean = codec.compressionEnabled,
    ): ByteArray =
        withContext(codecDispatcher) {
            createListenTogetherMessageCodec().apply { this.compressionEnabled = compressionEnabled }.encode(type, payload, requestId)
        }

    private suspend fun send(
        type: String,
        payload: Any? = null,
    ): Boolean {
        val session = socket ?: return false
        val generation = connectionGeneration
        val compressionEnabled = codec.compressionEnabled
        val requestId = if (ProtocolFeatures.REQUEST_ID in serverFeatures) trackRequest(type) else 0L
        if (requestId != 0L && payload is SuggestTrackPayload) {
            synchronized(participationLock) { suggestionRequests[requestId] = payload.trackInfo.id }
        }
        val decisionId =
            when (payload) {
                is ApproveSuggestionPayload -> payload.suggestionId
                is RejectSuggestionPayload -> payload.suggestionId
                else -> null
            }
        if (requestId != 0L && decisionId != null) {
            synchronized(participationLock) { suggestionDecisionRequests[requestId] = decisionId }
        }
        val message =
            encodeMutex.withLock {
                if (generation != connectionGeneration || socket !== session) return false
                val bytes =
                    try {
                        encode(type, payload, requestId, compressionEnabled)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        log(ListenTogetherLogLevel.ERROR, "Protocol encoding failed")
                        return false
                    }
                if (generation != connectionGeneration || socket !== session) return false
                OutgoingMessage(session, bytes).also {
                    if (outgoing.trySend(it).isFailure) {
                        log(ListenTogetherLogLevel.ERROR, "Outgoing queue full")
                        socket = null
                        session.cancel()
                        return false
                    }
                }
            }
        val delivered =
            withTimeoutOrNull(SEND_TIMEOUT_MS.milliseconds) {
                message.delivered.await()
            } == true
        if (!delivered && socket === session) {
            socket = null
            session.cancel()
        }
        return delivered
    }

    private fun trackRequest(type: String): Long =
        synchronized(participationLock) {
            val id = ++requestSequence
            requestTypes[id] = type
            if (requestTypes.size > MAX_TRACKED_REQUESTS) {
                val oldest = requestTypes.keys.first()
                requestTypes.remove(oldest)
                suggestionRequests.remove(oldest)
                suggestionDecisionRequests.remove(oldest)
            }
            id
        }

    private fun takeRequestType(requestId: Long): String? =
        if (requestId == 0L) null else synchronized(participationLock) { requestTypes.remove(requestId) }

    private fun isBackgroundRequestError(
        requestType: String?,
        error: ErrorPayload,
    ): Boolean = requestType in BACKGROUND_REQUEST_TYPES && error.code != "queue_too_large"

    private fun resetHostObservation() {
        synchronized(participationLock) { pendingHostQueues.clear() }
        lastTrackId = null
        lastTrackInstanceId = null
        lastPlaying = null
        lastPositionBucket = null
        lastQueueSnapshot = null
        lastQueueTitle = null
        lastVolume = null
    }

    private fun persistSession(isHost: Boolean) {
        if (!sessionPersistenceEnabled) return
        val token = sessionToken ?: return
        val roomCode = restoredRoomCode ?: return
        Settings.shared.listenTogetherDesktopSession =
            listOf(
                token,
                roomCode,
                _username.value,
                isHost.toString(),
                sessionStartedAt.toString(),
                _serverUrl.value,
            ).joinToString(SESSION_SEPARATOR)
    }

    private fun restoreSession() {
        val fields = Settings.shared.listenTogetherDesktopSession.split(SESSION_SEPARATOR)
        if (fields.size != 6) return
        val restoredUrl = fields[5].takeIf(::isAllowedListenTogetherServerUrl) ?: return
        sessionToken = fields[0].takeIf { it.isNotBlank() }
        restoredRoomCode = fields[1].takeIf { it.isNotBlank() }
        if (sessionToken == null || restoredRoomCode == null) {
            sessionToken = null
            restoredRoomCode = null
            return
        }
        if (_username.value.isBlank()) _username.value = fields[2]
        sessionStartedAt = fields[4].toLongOrNull() ?: 0L
        _serverUrl.value = restoredUrl
    }

    private fun emitNotice(key: ListenTogetherText) {
        _events.tryEmit(ListenTogetherEvent.Notice(key))
        log(ListenTogetherLogLevel.INFO, presentationText.text(key))
    }

    private fun emitFailure(
        key: ListenTogetherText,
        reason: String? = null,
    ) {
        _events.tryEmit(ListenTogetherEvent.Failure(key, reason))
        log(ListenTogetherLogLevel.ERROR, reason?.let { "${presentationText.text(key)}: $it" } ?: presentationText.text(key))
    }

    private fun log(
        level: ListenTogetherLogLevel,
        message: String,
    ) {
        val entry = ListenTogetherLogEntry(timestampFormatter(wallClockMs()), level, message)
        _logs.value = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)
        logger.log(level, message)
    }

    private fun SongItem.toTrackInfo(): TrackInfo =
        TrackInfo(
            id = id,
            title = title,
            artist = artists.joinToString(", ") { it.name },
            album = album?.name,
            duration = (duration ?: 0L) * 1_000L,
            thumbnail = thumbnail,
        )

    private fun TrackInfo.toSongItem(): SongItem = trackInfoToSongItem(omitNonPositiveDuration = true)

    private fun customServerMetadata(): Map<String, ListenTogetherServerMetadata> =
        runCatching { Json.decodeFromString<Map<String, ListenTogetherServerMetadata>>(Settings.shared.listenTogetherServerMetadata) }
            .getOrDefault(emptyMap())

    private fun buildServers(): List<ListenTogetherServer> {
        val metadata = customServerMetadata()
        return listOf(ListenTogetherServer("The Meowery", DEFAULT_SERVER_URL, "Poland", "Nyx")) +
            parseSet(Settings.shared.listenTogetherCustomServers)
                .filter(::isAllowedListenTogetherServerUrl)
                .filterNot { it == DEFAULT_SERVER_URL }
                .map { url ->
                    val entry = metadata[url]
                    ListenTogetherServer(entry?.name ?: "Custom server", url, "Custom", entry?.operator ?: "User", isCustom = true)
                }
    }

    private fun sanitizeRoomCode(value: String): String = value.filter { it.isLetterOrDigit() }.take(8).uppercase()

    private fun parseSet(raw: String): Set<String> =
        raw
            .split(LIST_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

    private fun persistSet(values: Set<String>): String = values.sorted().joinToString(LIST_SEPARATOR)
}

internal fun ListenTogetherMessageCodec.decodeServerCapabilities(data: ByteArray): ServerCapabilities? {
    val (type, payloadBytes) = decode(data)
    if (type != MessageTypes.SERVER_CAPABILITIES) return null
    return decodePayload(type, payloadBytes) as? ServerCapabilities
}
