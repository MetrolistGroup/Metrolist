/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.listentogether

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.metrolist.music.R
import com.metrolist.music.constants.ListenTogetherAutoApprovalKey
import com.metrolist.music.constants.ListenTogetherAutoApproveSuggestionsKey
import com.metrolist.music.constants.ListenTogetherIsHostKey
import com.metrolist.music.constants.ListenTogetherRoomCodeKey
import com.metrolist.music.constants.ListenTogetherServerUrlKey
import com.metrolist.music.constants.ListenTogetherSessionTimestampKey
import com.metrolist.music.constants.ListenTogetherSessionTokenKey
import com.metrolist.music.constants.ListenTogetherUserIdKey
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection state for the Listen Together feature
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
}

/**
 * Room role for the current user
 */
enum class RoomRole {
    HOST,
    GUEST,
    NONE,
}

/**
 * Log entry for debugging
 */
data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val details: String? = null,
)

enum class LogLevel {
    INFO,
    WARNING,
    ERROR,
    DEBUG,
}

/**
 * Pending action to execute when connected
 */
sealed class PendingAction {
    data class CreateRoom(
        val username: String,
    ) : PendingAction()

    data class JoinRoom(
        val roomCode: String,
        val username: String,
    ) : PendingAction()
}

/**
 * Event types for the Listen Together client
 */
sealed class ListenTogetherEvent {
    // Connection events
    data class Connected(
        val userId: String,
    ) : ListenTogetherEvent()

    data object Disconnected : ListenTogetherEvent()

    data class ConnectionError(
        val error: String,
    ) : ListenTogetherEvent()

    data class Reconnecting(
        val attempt: Int,
        val maxAttempts: Int,
    ) : ListenTogetherEvent()

    // Room events
    data class RoomCreated(
        val roomCode: String,
        val userId: String,
    ) : ListenTogetherEvent()

    data class JoinRequestReceived(
        val userId: String,
        val username: String,
    ) : ListenTogetherEvent()

    data class JoinApproved(
        val roomCode: String,
        val userId: String,
        val state: RoomState,
    ) : ListenTogetherEvent()

    data class JoinRejected(
        val reason: String,
    ) : ListenTogetherEvent()

    data class UserJoined(
        val userId: String,
        val username: String,
    ) : ListenTogetherEvent()

    data class UserLeft(
        val userId: String,
        val username: String,
    ) : ListenTogetherEvent()

    data class HostChanged(
        val newHostId: String,
        val newHostName: String,
    ) : ListenTogetherEvent()

    data class Kicked(
        val reason: String,
    ) : ListenTogetherEvent()

    data class Reconnected(
        val roomCode: String,
        val userId: String,
        val state: RoomState,
        val isHost: Boolean,
    ) : ListenTogetherEvent()

    data class UserReconnected(
        val userId: String,
        val username: String,
    ) : ListenTogetherEvent()

    data class UserDisconnected(
        val userId: String,
        val username: String,
    ) : ListenTogetherEvent()

    // Playback events
    data class PlaybackSync(
        val action: PlaybackActionPayload,
    ) : ListenTogetherEvent()

    data class BufferWait(
        val trackId: String,
        val waitingFor: List<String>,
    ) : ListenTogetherEvent()

    data class BufferComplete(
        val trackId: String,
    ) : ListenTogetherEvent()

    data class SyncStateReceived(
        val state: SyncStatePayload,
    ) : ListenTogetherEvent()

    // Error events
    data class ServerError(
        val code: String,
        val message: String,
    ) : ListenTogetherEvent()
}

/**
 * WebSocket client for Listen Together feature
 */
@Singleton
class ListenTogetherClient
    @Inject
    constructor(
        private val context: Context,
    ) {
        companion object {
            private const val TAG = "ListenTogether"
            private val DEFAULT_SERVER_URL = ListenTogetherServers.defaultServerUrl
            private const val MAX_RECONNECT_ATTEMPTS = 15 // Increased from 5 to 15
            private const val INITIAL_RECONNECT_DELAY_MS = 1000L // Start at 1 second
            private const val MAX_RECONNECT_DELAY_MS = 120000L // Cap at 2 minutes
            private const val PING_INTERVAL_MS = 25000L
            private const val INITIAL_PING_INTERVAL_MS = 250L
            private const val MAX_LOG_ENTRIES = 500
            private const val SESSION_GRACE_PERIOD_MS = 10 * 60 * 1000L // 10 minutes
            private const val BACKGROUND_DISCONNECT_DELAY_MS = 30 * 60 * 1000L // 30 minutes

            // Notification constants
            private const val NOTIFICATION_CHANNEL_ID = "listen_together_channel"
            const val ACTION_APPROVE_JOIN = "com.metrolist.music.LISTEN_TOGETHER_APPROVE_JOIN"
            const val ACTION_REJECT_JOIN = "com.metrolist.music.LISTEN_TOGETHER_REJECT_JOIN"
            const val ACTION_APPROVE_SUGGESTION = "com.metrolist.music.LISTEN_TOGETHER_APPROVE_SUGGESTION"
            const val ACTION_REJECT_SUGGESTION = "com.metrolist.music.LISTEN_TOGETHER_REJECT_SUGGESTION"
            const val EXTRA_USER_ID = "extra_user_id"
            const val EXTRA_SUGGESTION_ID = "extra_suggestion_id"
            const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

            @Volatile
            private var instance: ListenTogetherClient? = null

            fun getInstance(): ListenTogetherClient? = instance

            fun setInstance(client: ListenTogetherClient) {
                instance = client
            }
        }

        // Initialize scope early before init block since it's used in observeNetworkChanges()
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // State flows - initialized before init block to avoid NullPointerException when accessing log()
        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _roomState = MutableStateFlow<RoomState?>(null)
        val roomState: StateFlow<RoomState?> = _roomState.asStateFlow()

        private val _role = MutableStateFlow(RoomRole.NONE)
        val role: StateFlow<RoomRole> = _role.asStateFlow()

        private val _userId = MutableStateFlow<String?>(null)
        val userId: StateFlow<String?> = _userId.asStateFlow()

        private val _pendingJoinRequests = MutableStateFlow<List<JoinRequestPayload>>(emptyList())
        val pendingJoinRequests: StateFlow<List<JoinRequestPayload>> = _pendingJoinRequests.asStateFlow()

        private val _bufferingUsers = MutableStateFlow<List<String>>(emptyList())
        val bufferingUsers: StateFlow<List<String>> = _bufferingUsers.asStateFlow()

        // Suggestions: pending items visible to host
        private val _pendingSuggestions = MutableStateFlow<List<SuggestionReceivedPayload>>(emptyList())
        val pendingSuggestions: StateFlow<List<SuggestionReceivedPayload>> = _pendingSuggestions.asStateFlow()

        // Blocked usernames (internal list for privacy)
        private val _blockedUsernames = MutableStateFlow<Set<String>>(emptySet())
        val blockedUsernames: StateFlow<Set<String>> = _blockedUsernames.asStateFlow()

        private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
        val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

        // Event flow
        private val _events = MutableSharedFlow<ListenTogetherEvent>()
        val events: SharedFlow<ListenTogetherEvent> = _events.asSharedFlow()
        private val eventQueue = Channel<ListenTogetherEvent>(Channel.UNLIMITED)

        // Used from [loadPersistedSession] launched in init — must be declared before init (Kotlin
        // initialization order + IO thread can run the coroutine before later properties run).
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /**
         * Incremented when the user explicitly starts create/join so a late-finishing
         * [loadPersistedSession] cannot restore disk state over that intent (would make
         * [onOpen] choose RECONNECT instead of [executePendingAction]).
         */
        private val sessionApplyGeneration = AtomicInteger(0)

        init {
            setInstance(this)
            ensureNotificationChannel()
            observeAppLifecycle()
            scope.launch {
                for (event in eventQueue) {
                    _events.emit(event)
                }
            }
            // Load persisted session info asynchronously after construction to avoid calling log() before flows are initialized
            scope.launch {
                loadPersistedSession()
                observeNetworkChanges()
            }
        }

        private fun emitEvent(event: ListenTogetherEvent) {
            if (eventQueue.trySend(event).isFailure) {
                log(LogLevel.ERROR, "Failed to queue Listen Together event", event::class.simpleName)
            }
        }

        private fun observeAppLifecycle() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        evaluateBackgroundDisconnectPolicy("app_foreground")
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        evaluateBackgroundDisconnectPolicy("app_background")
                    }
                },
            )
        }

        private fun shouldDisconnectForBackgroundIdle(): Boolean {
            val connectedOrConnecting =
                _connectionState.value == ConnectionState.CONNECTED ||
                    _connectionState.value == ConnectionState.CONNECTING ||
                    _connectionState.value == ConnectionState.RECONNECTING

            return !ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
                _roomState.value == null &&
                pendingAction == null &&
                connectedOrConnecting
        }

        private fun evaluateBackgroundDisconnectPolicy(source: String) {
            if (!shouldDisconnectForBackgroundIdle()) {
                if (backgroundDisconnectJob?.isActive == true) {
                    log(LogLevel.DEBUG, "Cancelled background idle disconnect", source)
                }
                backgroundDisconnectJob?.cancel()
                backgroundDisconnectJob = null
                return
            }

            if (backgroundDisconnectJob?.isActive == true) return

            log(
                LogLevel.INFO,
                "Scheduling background idle disconnect",
                "Disconnecting in ${BACKGROUND_DISCONNECT_DELAY_MS / 60000} minutes ($source)",
            )

            backgroundDisconnectJob =
                scope.launch {
                    delay(BACKGROUND_DISCONNECT_DELAY_MS)
                    if (shouldDisconnectForBackgroundIdle()) {
                        log(LogLevel.INFO, "Background idle timeout reached", "Disconnecting to save battery")
                        backgroundDisconnectJob = null
                        disconnect()
                    }
                }
        }

        /**
         * Observe network changes to trigger reconnections
         */
        private fun observeNetworkChanges() {
            scope.launch {
                try {
                    val observer = connectivityObserver ?: return@launch
                    observer.networkStatus.collect { available: Boolean ->
                        val previous = isNetworkAvailable
                        isNetworkAvailable = available

                        if (available && !previous) {
                            log(LogLevel.INFO, "Network restored, checking if reconnection needed")
                            // Reset attempts when network is restored to allow a fresh set of retries
                            if (_connectionState.value == ConnectionState.ERROR ||
                                _connectionState.value == ConnectionState.DISCONNECTED
                            ) {
                                if (sessionToken != null || _roomState.value != null || pendingAction != null) {
                                    log(LogLevel.INFO, "Network restored, triggering reconnection")
                                    reconnectAttempts = 0 // Reset attempts for a fresh start
                                    connect()
                                }
                            }
                        } else if (!available && previous) {
                            log(LogLevel.WARNING, "Network lost")
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error observing network changes")
                }
            }
        }

        /**
         * Load persisted session information from storage
         */
        private fun loadPersistedSession() {
            val generationAtLoadStart = sessionApplyGeneration.get()
            try {
                val token = context.dataStore.get(ListenTogetherSessionTokenKey, "")
                val roomCode = context.dataStore.get(ListenTogetherRoomCodeKey, "")
                val userId = context.dataStore.get(ListenTogetherUserIdKey, "")
                val isHost = context.dataStore.get(ListenTogetherIsHostKey, false)
                val timestamp = context.dataStore.get(ListenTogetherSessionTimestampKey, 0L)

                // Check if session is still valid (within grace period)
                if (token.isNotEmpty() && roomCode.isNotEmpty() &&
                    (System.currentTimeMillis() - timestamp < SESSION_GRACE_PERIOD_MS)
                ) {
                    if (generationAtLoadStart != sessionApplyGeneration.get()) {
                        log(
                            LogLevel.INFO,
                            "Skipping persisted session restore",
                            "User started a new room flow before restore completed",
                        )
                        return
                    }
                    sessionToken = token
                    storedRoomCode = roomCode
                    _userId.value = userId.ifEmpty { null }
                    wasHost = isHost
                    sessionStartTime = timestamp
                    log(LogLevel.INFO, "Loaded persisted session", "Room: $roomCode, Host: $isHost")
                } else if (token.isNotEmpty()) {
                    if (generationAtLoadStart != sessionApplyGeneration.get()) {
                        return
                    }
                    log(LogLevel.WARNING, "Session expired", "Age: ${System.currentTimeMillis() - timestamp}ms")
                    clearPersistedSession()
                }
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to load persisted session", e.message)
            }

            // Also load blocked usernames
            loadBlockedUsernames()

            // Migrate old server URL to new one
            migrateServerUrl()
        }

        /**
         * Load blocked usernames from storage
         */
        private fun loadBlockedUsernames() {
            try {
                val blockedJson = context.dataStore.get(com.metrolist.music.constants.ListenTogetherBlockedUsersKey, "")
                val blockedList =
                    if (blockedJson.isNotEmpty()) {
                        json.decodeFromString<List<String>>(blockedJson)
                    } else {
                        emptyList()
                    }
                _blockedUsernames.value = blockedList.toSet()
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to load blocked usernames", e.message)
                _blockedUsernames.value = emptySet()
            }
        }

        /**
         * Save blocked usernames to storage
         */
        private suspend fun saveBlockedUsernames() {
            try {
                val blockedJson = json.encodeToString(_blockedUsernames.value.toList())
                context.safeDataStoreEdit { preferences ->
                    preferences[com.metrolist.music.constants.ListenTogetherBlockedUsersKey] = blockedJson
                }
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to save blocked usernames", e.message)
            }
        }

        /**
         * Migrate old server URL to new one if needed
         */
        private fun migrateServerUrl() {
            try {
                val configuredUrl = context.dataStore.get(ListenTogetherServerUrlKey, DEFAULT_SERVER_URL)
                val normalizedUrl = normalizeServerUrl(configuredUrl)

                if (normalizedUrl != configuredUrl) {
                    log(LogLevel.INFO, "Migrating server URL", "Old: $configuredUrl -> New: $normalizedUrl")
                    scope.launch {
                        context.safeDataStoreEdit { preferences ->
                            preferences[ListenTogetherServerUrlKey] = normalizedUrl
                        }
                    }
                }
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to migrate server URL", e.message)
            }
        }

        /**
         * Save current session information to persistent storage
         */
        private fun savePersistedSession() {
            try {
                scope.launch {
                    context.safeDataStoreEdit { preferences ->
                        if (sessionToken != null) {
                            preferences[ListenTogetherSessionTokenKey] = sessionToken!!
                            preferences[ListenTogetherRoomCodeKey] = storedRoomCode ?: ""
                            preferences[ListenTogetherUserIdKey] = _userId.value ?: ""
                            preferences[ListenTogetherIsHostKey] = wasHost
                            preferences[ListenTogetherSessionTimestampKey] = System.currentTimeMillis()
                        }
                    }
                }
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to save persisted session", e.message)
            }
        }

        /**
         * Clear persisted session information
         */
        private fun clearPersistedSession() {
            try {
                scope.launch {
                    context.safeDataStoreEdit { preferences ->
                        preferences.remove(ListenTogetherSessionTokenKey)
                        preferences.remove(ListenTogetherRoomCodeKey)
                        preferences.remove(ListenTogetherUserIdKey)
                        preferences.remove(ListenTogetherIsHostKey)
                        preferences.remove(ListenTogetherSessionTimestampKey)
                    }
                }
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to clear persisted session", e.message)
            }
        }

        // Message codec - uses Protobuf with compression enabled
        private val codec = MessageCodec(true)
        private val serverClock = ServerClock(SystemClock::elapsedRealtime)
        private val pingSequence = AtomicLong(0L)
        private val lastPlaybackRevision = AtomicLong(0L)

        private var webSocket: WebSocket? = null
        private var pingJob: Job? = null
        private var reconnectAttempts = 0
        private var backgroundDisconnectJob: Job? = null

        // Session info for reconnection
        private var sessionToken: String? = null
        private var storedUsername: String? = null
        private var storedRoomCode: String? = null
        private var wasHost: Boolean = false
        private var sessionStartTime: Long = 0

        // Pending actions to execute when connected
        private var pendingAction: PendingAction? = null

        // Wake lock to keep connection alive when in a room
        private var wakeLock: PowerManager.WakeLock? = null

        // Track notification IDs for join requests to dismiss them from both UI and notification actions
        private val joinRequestNotifications = mutableMapOf<String, Int>()

        // Track notification IDs for suggestions to dismiss them similarly
        private val suggestionNotifications = mutableMapOf<String, Int>()

        // Network connectivity monitoring - use lazy to avoid initialization order issues
        private val connectivityObserver: NetworkConnectivityObserver? by lazy {
            try {
                NetworkConnectivityObserver(context)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to create NetworkConnectivityObserver")
                null
            }
        }
        private var isNetworkAvailable =
            try {
                connectivityObserver?.isCurrentlyConnected() ?: true
            } catch (e: Exception) {
                true
            }

        private val client =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(60, TimeUnit.SECONDS) // Match server ping interval
                .build()

        private fun normalizeServerUrl(url: String): String {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
            return if (trimmed.contains("metroserver.meowery.eu", ignoreCase = true)) {
                DEFAULT_SERVER_URL
            } else {
                trimmed
            }
        }

        private fun getServerUrl(): String {
            val configuredUrl = context.dataStore.get(ListenTogetherServerUrlKey, DEFAULT_SERVER_URL)
            return normalizeServerUrl(configuredUrl)
        }

        /**
         * Calculate exponential backoff delay with jitter
         */
        private fun calculateBackoffDelay(attempt: Int): Long {
            val exponentialDelay = INITIAL_RECONNECT_DELAY_MS * (2 shl (minOf(attempt - 1, 4)))
            val cappedDelay = minOf(exponentialDelay, MAX_RECONNECT_DELAY_MS)
            // Add 0-20% jitter to prevent thundering herd
            val jitter = (cappedDelay * 0.2 * Math.random()).toLong()
            return cappedDelay + jitter
        }

        private fun log(
            level: LogLevel,
            message: String,
            details: String? = null,
        ) {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
            val entry = LogEntry(timestamp, level, message, details)

            _logs.value = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)

            when (level) {
                LogLevel.ERROR -> Timber.tag(TAG).e("$message ${details ?: ""}")
                LogLevel.WARNING -> Timber.tag(TAG).w("$message ${details ?: ""}")
                LogLevel.DEBUG -> Timber.tag(TAG).d("$message ${details ?: ""}")
                LogLevel.INFO -> Timber.tag(TAG).i("$message ${details ?: ""}")
            }
        }

        fun clearLogs() {
            _logs.value = emptyList()
        }

        /**
         * Connect to the Listen Together server
         */
        fun connect() {
            if (_connectionState.value == ConnectionState.CONNECTED ||
                _connectionState.value == ConnectionState.CONNECTING
            ) {
                log(LogLevel.WARNING, "Already connected or connecting")
                return
            }

            _connectionState.value = ConnectionState.CONNECTING
            serverClock.reset()
            evaluateBackgroundDisconnectPolicy("connect")
            log(LogLevel.INFO, "Connecting to server", getServerUrl())

            val request =
                Request
                    .Builder()
                    .url(getServerUrl())
                    .header("User-Agent", context.packageName)
                    .build()

            webSocket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            log(LogLevel.INFO, "Connected to server")
                            _connectionState.value = ConnectionState.CONNECTED
                            reconnectAttempts = 0
                            startPingJob()
                            evaluateBackgroundDisconnectPolicy("socket_open")

                            // Try to reconnect to previous session if we have a valid token
                            if (sessionToken != null && storedRoomCode != null) {
                                log(LogLevel.INFO, "Attempting to reconnect to previous session", "Room: $storedRoomCode")
                                sendMessage(MessageTypes.RECONNECT, ReconnectPayload(sessionToken!!))
                            } else {
                                // Execute any pending action
                                executePendingAction()
                            }
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: okio.ByteString,
                        ) {
                            // Handle binary protobuf messages
                            handleMessage(bytes.toByteArray())
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            log(LogLevel.INFO, "Server closing connection", "Code: $code, Reason: $reason")
                            webSocket.close(1000, null)
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            log(LogLevel.INFO, "Connection closed", "Code: $code, Reason: $reason")
                            handleDisconnect()
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            log(LogLevel.ERROR, "Connection failure", t.message)
                            handleConnectionFailure(t)
                        }
                    },
                )
        }

        private fun executePendingAction() {
            val action = pendingAction ?: return
            pendingAction = null
            evaluateBackgroundDisconnectPolicy("pending_action_started")

            when (action) {
                is PendingAction.CreateRoom -> {
                    log(LogLevel.INFO, "Executing pending create room", action.username)
                    sendMessage(MessageTypes.CREATE_ROOM, CreateRoomPayload(action.username))
                }

                is PendingAction.JoinRoom -> {
                    log(LogLevel.INFO, "Executing pending join room", "${action.roomCode} as ${action.username}")
                    sendMessage(MessageTypes.JOIN_ROOM, JoinRoomPayload(action.roomCode.uppercase(), action.username))
                }
            }
        }

        /**
         * Disconnect from the server
         */
        fun disconnect() {
            log(LogLevel.INFO, "Disconnecting from server")
            backgroundDisconnectJob?.cancel()
            backgroundDisconnectJob = null
            releaseWakeLock() // Release wake lock when disconnecting
            pingJob?.cancel()
            pingJob = null
            serverClock.reset()
            lastPlaybackRevision.set(0L)
            webSocket?.close(1000, "User disconnected")
            webSocket = null
            _connectionState.value = ConnectionState.DISCONNECTED

            // Clear session and state on explicit disconnect
            sessionToken = null
            storedRoomCode = null
            storedUsername = null
            pendingAction = null
            _roomState.value = null
            _role.value = RoomRole.NONE
            _userId.value = null
            _pendingJoinRequests.value = emptyList()
            _bufferingUsers.value = emptyList()

            // Clear from persistent storage
            clearPersistedSession()
            reconnectAttempts = 0

            emitEvent(ListenTogetherEvent.Disconnected)
        }

        private fun startPingJob() {
            pingJob?.cancel()
            pingJob =
                scope.launch {
                    repeat(3) {
                        sendTimeSyncPing()
                        delay(INITIAL_PING_INTERVAL_MS)
                    }
                    while (true) {
                        // Refresh the WakeLock on every ping cycle so it never expires while the
                        // connection is active. Without this, the 10-minute timeout can lapse during
                        // long sessions with the screen off, allowing the CPU to throttle and
                        // causing the WebSocket to degrade, resulting in choppy audio.
                        acquireWakeLock()
                        sendTimeSyncPing()
                        delay(PING_INTERVAL_MS)
                    }
                }
        }

        private fun sendTimeSyncPing() {
            sendMessage(
                MessageTypes.PING,
                PingPayload(
                    clientTime = SystemClock.elapsedRealtime(),
                    sequence = pingSequence.incrementAndGet(),
                ),
            )
        }

        internal fun serverTimeNow(): Long? = serverClock.now()

        internal fun positionAtServerTime(
            position: Long,
            effectiveAtServerTime: Long?,
            isPlaying: Boolean,
        ): Long = serverClock.positionAt(position, effectiveAtServerTime, isPlaying)

        private fun acceptPlaybackRevision(revision: Long): Boolean {
            if (revision <= 0L) return true
            while (true) {
                val current = lastPlaybackRevision.get()
                if (revision < current) return false
                if (revision == current || lastPlaybackRevision.compareAndSet(current, revision)) return true
            }
        }

        @Suppress("DEPRECATION")
        private fun acquireWakeLock() {
            if (wakeLock == null) {
                val powerManager = context.getSystemService<PowerManager>()
                wakeLock =
                    powerManager?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Metrolist:ListenTogether",
                    )
            }
            // Always release before acquiring so that the timeout is reset on each call.
            // This is safe because the ping job calls acquireWakeLock() every PING_INTERVAL_MS
            // (25 s), ensuring the lock is refreshed well before the 10-minute window elapses.
            // Without the release-and-reacquire pattern the first acquire() sets the countdown
            // and subsequent calls while isHeld is true are no-ops, so the lock would still
            // expire after 10 minutes of continuous screen-off sessions.
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock?.acquire(10 * 60 * 1000L)
            log(LogLevel.DEBUG, "Wake lock acquired")
        }

        private fun releaseWakeLock() {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                log(LogLevel.DEBUG, "Wake lock released")
            }
        }

        private fun ensureNotificationChannel() {
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                val existing = nm?.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
                if (existing == null) {
                    val channel =
                        NotificationChannel(
                            NOTIFICATION_CHANNEL_ID,
                            context.getString(R.string.listen_together_notification_channel_name),
                            NotificationManager.IMPORTANCE_HIGH,
                        )
                    channel.description = context.getString(R.string.listen_together_notification_channel_desc)
                    nm?.createNotificationChannel(channel)
                }
            } catch (e: Exception) {
                log(LogLevel.WARNING, "Failed to create notification channel", e.message)
            }
        }

        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        private fun showJoinRequestNotification(payload: JoinRequestPayload) {
            val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

            // Store notification ID for this user so we can dismiss it from UI actions
            joinRequestNotifications[payload.userId] = notifId

            val approveIntent =
                Intent(context, ListenTogetherActionReceiver::class.java).apply {
                    action = ACTION_APPROVE_JOIN
                    putExtra(EXTRA_USER_ID, payload.userId)
                    putExtra(EXTRA_NOTIFICATION_ID, notifId)
                }
            val rejectIntent =
                Intent(context, ListenTogetherActionReceiver::class.java).apply {
                    action = ACTION_REJECT_JOIN
                    putExtra(EXTRA_USER_ID, payload.userId)
                    putExtra(EXTRA_NOTIFICATION_ID, notifId)
                }

            val approvePI =
                PendingIntent.getBroadcast(
                    context,
                    payload.userId.hashCode(),
                    approveIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val rejectPI =
                PendingIntent.getBroadcast(
                    context,
                    payload.userId.hashCode().inv(),
                    rejectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val content = context.getString(R.string.listen_together_join_request_notification, payload.username)

            val builder =
                NotificationCompat
                    .Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.share)
                    .setContentTitle(context.getString(R.string.listen_together))
                    .setContentText(content)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .addAction(0, context.getString(R.string.approve), approvePI)
                    .addAction(0, context.getString(R.string.reject), rejectPI)

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(context).notify(notifId, builder.build())
            }
        }

        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        private fun showSuggestionNotification(payload: SuggestionReceivedPayload) {
            val notifId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()

            // Store notification ID for this suggestion so we can dismiss it from UI actions
            suggestionNotifications[payload.suggestionId] = notifId

            val approveIntent =
                Intent(context, ListenTogetherActionReceiver::class.java).apply {
                    action = ACTION_APPROVE_SUGGESTION
                    putExtra(EXTRA_SUGGESTION_ID, payload.suggestionId)
                    putExtra(EXTRA_NOTIFICATION_ID, notifId)
                }
            val rejectIntent =
                Intent(context, ListenTogetherActionReceiver::class.java).apply {
                    action = ACTION_REJECT_SUGGESTION
                    putExtra(EXTRA_SUGGESTION_ID, payload.suggestionId)
                    putExtra(EXTRA_NOTIFICATION_ID, notifId)
                }

            val approvePI =
                PendingIntent.getBroadcast(
                    context,
                    payload.suggestionId.hashCode(),
                    approveIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val rejectPI =
                PendingIntent.getBroadcast(
                    context,
                    payload.suggestionId.hashCode().inv(),
                    rejectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val content = context.getString(R.string.listen_together_suggestion_received, payload.fromUsername, payload.trackInfo.title)

            val builder =
                NotificationCompat
                    .Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.share)
                    .setContentTitle(context.getString(R.string.listen_together))
                    .setContentText(content)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .addAction(0, context.getString(R.string.approve), approvePI)
                    .addAction(0, context.getString(R.string.reject), rejectPI)

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                NotificationManagerCompat.from(context).notify(notifId, builder.build())
            }
        }

        private fun handleDisconnect() {
            pingJob?.cancel()
            pingJob = null
            serverClock.reset()

            // Don't clear room state - we might reconnect
            // Only update connection state
            _connectionState.value = ConnectionState.DISCONNECTED
            _pendingJoinRequests.value = emptyList()
            _bufferingUsers.value = emptyList()

            // If we have a session, try to reconnect
            if (sessionToken != null && _roomState.value != null) {
                log(LogLevel.INFO, "Connection lost, will attempt to reconnect")
                handleConnectionFailure(Exception("Connection lost"))
            } else {
                emitEvent(ListenTogetherEvent.Disconnected)
            }
            evaluateBackgroundDisconnectPolicy("socket_disconnected")
        }

        private fun handleConnectionFailure(t: Throwable) {
            pingJob?.cancel()
            pingJob = null

            // Always try to reconnect if we have a session token or pending action
            val shouldReconnect = sessionToken != null || _roomState.value != null || pendingAction != null

            if (!isNetworkAvailable) {
                log(LogLevel.WARNING, "Connection failure, waiting for network", t.message)
                _connectionState.value = ConnectionState.DISCONNECTED
                evaluateBackgroundDisconnectPolicy("connection_failure_no_network")
                return
            }

            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && shouldReconnect) {
                reconnectAttempts++
                _connectionState.value = ConnectionState.RECONNECTING

                val delayMs = calculateBackoffDelay(reconnectAttempts)
                val delaySeconds = delayMs / 1000

                log(
                    LogLevel.INFO,
                    "Attempting reconnect",
                    "Attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS, waiting ${delaySeconds}s, reason: ${t.message}",
                )

                emitEvent(ListenTogetherEvent.Reconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS))
                scope.launch {
                    delay(delayMs)

                    // Check if we're still supposed to be reconnecting
                    if (_connectionState.value == ConnectionState.RECONNECTING || _connectionState.value == ConnectionState.DISCONNECTED) {
                        log(LogLevel.INFO, "Reconnecting after backoff", "Delay was ${delaySeconds}s")
                        connect()
                    }
                }
                evaluateBackgroundDisconnectPolicy("connection_failure_retrying")
            } else {
                _connectionState.value = ConnectionState.ERROR

                // If we had a session, notify user but keep session data for manual retry
                if (sessionToken != null) {
                    log(
                        LogLevel.ERROR,
                        "Reconnection failed",
                        "Max attempts reached, but session preserved for manual reconnect",
                    )
                    emitEvent(
                        ListenTogetherEvent.ConnectionError(
                            "Connection failed after $MAX_RECONNECT_ATTEMPTS attempts. ${t.message ?: "Unknown error"}",
                        ),
                    )
                } else {
                    // No session, so clear everything
                    sessionToken = null
                    storedRoomCode = null
                    storedUsername = null
                    _roomState.value = null
                    _role.value = RoomRole.NONE
                    clearPersistedSession()

                    emitEvent(ListenTogetherEvent.ConnectionError(t.message ?: "Unknown error"))
                }
                evaluateBackgroundDisconnectPolicy("connection_failure_exhausted")
            }
        }

        private fun handleMessage(data: ByteArray) {
            log(LogLevel.DEBUG, "Received message", "${data.size} bytes")

            try {
                // Decode message using Protobuf
                val (msgType, payloadBytes) = codec.decode(data)

                when (msgType) {
                    MessageTypes.ROOM_CREATED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? RoomCreatedPayload ?: return
                        _userId.value = payload.userId
                        _role.value = RoomRole.HOST
                        lastPlaybackRevision.set(0L)
                        sessionToken = payload.sessionToken
                        storedRoomCode = payload.roomCode
                        wasHost = true
                        sessionStartTime = System.currentTimeMillis()

                        _roomState.value =
                            RoomState(
                                roomCode = payload.roomCode,
                                hostId = payload.userId,
                                users = listOf(UserInfo(payload.userId, storedUsername ?: "", true)),
                                isPlaying = false,
                                position = 0,
                                lastUpdate = System.currentTimeMillis(),
                                volume = 1f,
                            )

                        // Save session to persistent storage
                        savePersistedSession()
                        evaluateBackgroundDisconnectPolicy("room_created")

                        acquireWakeLock() // Keep connection alive while in room
                        log(LogLevel.INFO, "Room created", "Code: ${payload.roomCode}")
                        emitEvent(ListenTogetherEvent.RoomCreated(payload.roomCode, payload.userId))
                        // Global toast for room creation so the host sees it regardless of UI
                        scope.launch(Dispatchers.Main) {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.listen_together_room_created, payload.roomCode),
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                    }

                    MessageTypes.JOIN_REQUEST -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? JoinRequestPayload ?: return

                        // Check if user is blocked
                        if (isUserBlocked(payload.username)) {
                            log(LogLevel.INFO, "Join request from blocked user ignored", "User: ${payload.username}")
                            // Silently reject blocked users
                            rejectJoin(payload.userId, "You are blocked")
                            return
                        }

                        _pendingJoinRequests.value += payload
                        log(LogLevel.INFO, "Join request received", "User: ${payload.username}")

                        // Check if auto-approval is enabled
                        val autoApprovalEnabled = context.dataStore.get(ListenTogetherAutoApprovalKey, false)

                        if (_role.value == RoomRole.HOST) {
                            if (autoApprovalEnabled) {
                                // Automatically approve the join request
                                log(LogLevel.INFO, "Auto-approving join request", "User: ${payload.username}")
                                approveJoin(payload.userId)
                            } else {
                                // Notify host with Approve/Reject actions
                                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    showJoinRequestNotification(payload)
                                }
                            }
                        }
                        emitEvent(ListenTogetherEvent.JoinRequestReceived(payload.userId, payload.username))
                    }

                    MessageTypes.JOIN_APPROVED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? JoinApprovedPayload ?: return
                        _userId.value = payload.userId
                        _role.value = RoomRole.GUEST
                        sessionToken = payload.sessionToken
                        storedRoomCode = payload.roomCode
                        wasHost = false
                        sessionStartTime = System.currentTimeMillis()

                        _roomState.value = payload.state
                        lastPlaybackRevision.set(payload.state.revision)

                        // Save session to persistent storage
                        savePersistedSession()
                        evaluateBackgroundDisconnectPolicy("join_approved")

                        acquireWakeLock() // Keep connection alive while in room
                        log(LogLevel.INFO, "Joined room", "Code: ${payload.roomCode}")
                        emitEvent(ListenTogetherEvent.JoinApproved(payload.roomCode, payload.userId, payload.state))
                    }

                    MessageTypes.JOIN_REJECTED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? JoinRejectedPayload ?: return
                        log(LogLevel.WARNING, "Join rejected", payload.reason)
                        emitEvent(ListenTogetherEvent.JoinRejected(payload.reason))
                    }

                    MessageTypes.USER_JOINED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? UserJoinedPayload ?: return
                        _roomState.value =
                            _roomState.value?.copy(
                                users = _roomState.value!!.users + UserInfo(payload.userId, payload.username, false),
                            )
                        _pendingJoinRequests.value = _pendingJoinRequests.value.filter { it.userId != payload.userId }

                        // Dismiss notification if it exists
                        joinRequestNotifications.remove(payload.userId)?.let { notifId ->
                            NotificationManagerCompat.from(context).cancel(notifId)
                        }

                        log(LogLevel.INFO, "User joined", payload.username)
                        emitEvent(ListenTogetherEvent.UserJoined(payload.userId, payload.username))
                    }

                    MessageTypes.USER_LEFT -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? UserLeftPayload ?: return
                        _roomState.value =
                            _roomState.value?.copy(
                                users = _roomState.value!!.users.filter { it.userId != payload.userId },
                            )
                        log(LogLevel.INFO, "User left", payload.username)
                        emitEvent(ListenTogetherEvent.UserLeft(payload.userId, payload.username))
                    }

                    MessageTypes.HOST_CHANGED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? HostChangedPayload ?: return
                        _roomState.value =
                            _roomState.value?.copy(
                                hostId = payload.newHostId,
                                users =
                                    _roomState.value!!.users.map {
                                        it.copy(isHost = it.userId == payload.newHostId)
                                    },
                            )
                        if (payload.newHostId == _userId.value) {
                            _role.value = RoomRole.HOST
                        } else if (_role.value == RoomRole.HOST) {
                            // Lost host role
                            _role.value = RoomRole.GUEST
                        }
                        log(LogLevel.INFO, "Host changed", "New host: ${payload.newHostName}")
                        emitEvent(ListenTogetherEvent.HostChanged(payload.newHostId, payload.newHostName))
                    }

                    MessageTypes.KICKED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? KickedPayload ?: return
                        log(LogLevel.WARNING, "Kicked from room", payload.reason)
                        releaseWakeLock() // Release wake lock when kicked
                        sessionToken = null
                        _roomState.value = null
                        _role.value = RoomRole.NONE
                        lastPlaybackRevision.set(0L)
                        evaluateBackgroundDisconnectPolicy("kicked")
                        emitEvent(ListenTogetherEvent.Kicked(payload.reason))
                    }

                    MessageTypes.SYNC_PLAYBACK -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? PlaybackActionPayload ?: return
                        if (!acceptPlaybackRevision(payload.revision)) {
                            log(LogLevel.DEBUG, "Discarding stale playback event", "Revision: ${payload.revision}")
                            return
                        }
                        log(LogLevel.DEBUG, "Playback sync", "Action: ${payload.action}")

                        // Update room state based on action
                        when (payload.action) {
                            PlaybackActions.PLAY -> {
                                _roomState.value =
                                    _roomState.value?.copy(
                                        isPlaying = true,
                                     position = payload.position ?: _roomState.value!!.position,
                                        lastUpdate = payload.serverTime ?: _roomState.value!!.lastUpdate,
                                        revision = maxOf(_roomState.value!!.revision, payload.revision),
                                    )
                            }

                            PlaybackActions.PAUSE -> {
                                _roomState.value =
                                    _roomState.value?.copy(
                                        isPlaying = false,
                                        position = payload.position ?: _roomState.value!!.position,
                                        lastUpdate = payload.serverTime ?: _roomState.value!!.lastUpdate,
                                        revision = maxOf(_roomState.value!!.revision, payload.revision),
                                    )
                            }

                            PlaybackActions.SEEK -> {
                                _roomState.value =
                                    _roomState.value?.copy(
                                        position = payload.position ?: _roomState.value!!.position,
                                        lastUpdate = payload.serverTime ?: _roomState.value!!.lastUpdate,
                                        revision = maxOf(_roomState.value!!.revision, payload.revision),
                                    )
                            }

                            PlaybackActions.CHANGE_TRACK -> {
                                _roomState.value =
                                    _roomState.value?.copy(
                                        currentTrack = payload.trackInfo,
                                        isPlaying = false,
                                        position = 0,
                                        lastUpdate = payload.serverTime ?: _roomState.value!!.lastUpdate,
                                        queue = if (payload.revision > 0L) payload.queue.orEmpty() else _roomState.value!!.queue,
                                        revision = maxOf(_roomState.value!!.revision, payload.revision),
                                    )
                            }

                            PlaybackActions.QUEUE_ADD -> {
                                val ti = payload.trackInfo
                                if (ti != null) {
                                    val currentQueue = _roomState.value?.queue ?: emptyList()
                                    _roomState.value =
                                        _roomState.value?.copy(
                                            queue =
                                                if (payload.revision > 0L) {
                                                    payload.queue.orEmpty()
                                                } else if (payload.insertNext == true) {
                                                    listOf(ti) + currentQueue
                                                } else {
                                                    currentQueue + ti
                                                },
                                        )
                                }
                            }

                            PlaybackActions.QUEUE_REMOVE -> {
                                val id = payload.trackId
                                if (!id.isNullOrEmpty()) {
                                    val currentQueue = _roomState.value?.queue ?: emptyList()
                                    _roomState.value =
                                        _roomState.value?.copy(
                                            queue = if (payload.revision > 0L) payload.queue.orEmpty() else currentQueue.filter { it.id != id },
                                        )
                                }
                            }

                            PlaybackActions.QUEUE_CLEAR -> {
                                _roomState.value =
                                    _roomState.value?.copy(
                                        queue = if (payload.revision > 0L) payload.queue.orEmpty() else emptyList(),
                                    )
                            }

                            PlaybackActions.SYNC_QUEUE -> {
                                _roomState.value = _roomState.value?.copy(queue = payload.queue.orEmpty())
                            }

                            PlaybackActions.SET_VOLUME -> {
                                val vol = payload.volume
                                if (vol != null) {
                                    _roomState.value = _roomState.value?.copy(volume = vol.coerceIn(0f, 1f))
                                }
                            }
                        }

                        _roomState.value =
                            _roomState.value?.copy(
                                revision = maxOf(_roomState.value!!.revision, payload.revision),
                            )

                        emitEvent(ListenTogetherEvent.PlaybackSync(payload))
                    }

                    MessageTypes.BUFFER_WAIT -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? BufferWaitPayload ?: return
                        _bufferingUsers.value = payload.waitingFor
                        log(LogLevel.DEBUG, "Waiting for buffering", "Users: ${payload.waitingFor.size}")
                        emitEvent(ListenTogetherEvent.BufferWait(payload.trackId, payload.waitingFor))
                    }

                    MessageTypes.BUFFER_COMPLETE -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? BufferCompletePayload ?: return
                        _bufferingUsers.value = emptyList()
                        log(LogLevel.INFO, "All users buffered", "Track: ${payload.trackId}")
                        emitEvent(ListenTogetherEvent.BufferComplete(payload.trackId))
                    }

                    MessageTypes.SYNC_STATE -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? SyncStatePayload ?: return
                        if (!acceptPlaybackRevision(payload.revision)) {
                            log(LogLevel.DEBUG, "Discarding stale sync state", "Revision: ${payload.revision}")
                            return
                        }
                        _roomState.value =
                            _roomState.value?.copy(
                                currentTrack = payload.currentTrack,
                                isPlaying = payload.isPlaying,
                                position = payload.position,
                                lastUpdate = payload.lastUpdate,
                                volume = payload.volume ?: _roomState.value!!.volume,
                                queue = payload.queue ?: _roomState.value!!.queue,
                                revision = maxOf(_roomState.value!!.revision, payload.revision),
                            )
                        log(LogLevel.INFO, "Sync state received", "Playing: ${payload.isPlaying}, Position: ${payload.position}")
                        emitEvent(ListenTogetherEvent.SyncStateReceived(payload))
                    }

                    MessageTypes.SUGGESTION_RECEIVED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? SuggestionReceivedPayload ?: return
                        // Only host should receive suggestions
                        if (_role.value == RoomRole.HOST) {
                            // Check if user is blocked
                            if (isUserBlocked(payload.fromUsername)) {
                                log(LogLevel.INFO, "Suggestion from blocked user ignored", "User: ${payload.fromUsername}")
                                return
                            }

                            log(LogLevel.INFO, "Suggestion received", "${payload.fromUsername}: ${payload.trackInfo.title}")

                            // Check if auto-approval of suggestions is enabled
                            val autoApproveSuggestionsEnabled = context.dataStore.get(ListenTogetherAutoApproveSuggestionsKey, false)

                            if (autoApproveSuggestionsEnabled) {
                                // Automatically approve the suggestion
                                log(LogLevel.INFO, "Auto-approving suggestion", "${payload.fromUsername}: ${payload.trackInfo.title}")
                                approveSuggestion(payload.suggestionId)
                            } else {
                                // Add to pending list and show notification
                                _pendingSuggestions.value += payload
                                // Notify the host with actionable notification
                                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    showSuggestionNotification(payload)
                                }
                            }
                        }
                    }

                    MessageTypes.SUGGESTION_APPROVED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? SuggestionApprovedPayload ?: return
                        log(LogLevel.INFO, "Suggestion approved", payload.trackInfo.title)

                        // Dismiss notification if it exists (for host who approved via another device/modal)
                        suggestionNotifications.remove(payload.suggestionId)?.let { notifId ->
                            NotificationManagerCompat.from(context).cancel(notifId)
                        }

                        // For guests, optionally notify via events; UI can react if needed
                    }

                    MessageTypes.SUGGESTION_REJECTED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? SuggestionRejectedPayload ?: return
                        log(LogLevel.WARNING, "Suggestion rejected", payload.reason ?: "")

                        // Dismiss notification if it exists
                        suggestionNotifications.remove(payload.suggestionId)?.let { notifId ->
                            NotificationManagerCompat.from(context).cancel(notifId)
                        }

                        // For guests, optionally notify via events
                    }

                    MessageTypes.ERROR -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? ErrorPayload ?: return
                        log(LogLevel.ERROR, "Server error", "${payload.code}: ${payload.message}")

                        // Handle specific error cases
                        when (payload.code) {
                            "session_not_found" -> {
                                // Clear stale room state immediately - server no longer recognizes the session
                                _roomState.value = null
                                _role.value = RoomRole.NONE
                                _pendingJoinRequests.value = emptyList()
                                _bufferingUsers.value = emptyList()

                                // Session expired on server, try to rejoin the room
                                if (storedRoomCode != null && storedUsername != null && !wasHost) {
                                    log(
                                        LogLevel.WARNING,
                                        "Session expired on server",
                                        "Attempting automatic rejoin to room: $storedRoomCode",
                                    )
                                    // Try rejoining as a guest
                                    scope.launch {
                                        delay(500) // Small delay before rejoin attempt
                                        joinRoom(storedRoomCode!!, storedUsername!!)
                                    }
                                } else if (storedRoomCode != null && storedUsername != null) {
                                    // Host session expired - would need to create new room
                                    log(
                                        LogLevel.WARNING,
                                        "Host session expired",
                                        "Room: $storedRoomCode - manual intervention may be needed",
                                    )
                                    clearPersistedSession()
                                    sessionToken = null
                                } else {
                                    clearPersistedSession()
                                    sessionToken = null
                                }
                            }

                            else -> {}
                        }

                        emitEvent(ListenTogetherEvent.ServerError(payload.code, payload.message))
                    }

                    MessageTypes.PONG -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? PongPayload
                        if (payload != null) {
                            val firstSample =
                                serverClock.recordPong(
                                    payload.clientTime,
                                    payload.serverReceiveTime,
                                    payload.serverSendTime,
                                )
                            if (firstSample && _role.value == RoomRole.GUEST) requestSync()
                        }
                        log(LogLevel.DEBUG, "Pong received")
                    }

                    MessageTypes.RECONNECTED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? ReconnectedPayload ?: return
                        _userId.value = payload.userId
                        _role.value = if (payload.isHost) RoomRole.HOST else RoomRole.GUEST
                        _roomState.value = payload.state
                        lastPlaybackRevision.set(payload.state.revision)

                        // Update persisted session info
                        wasHost = payload.isHost
                        sessionStartTime = System.currentTimeMillis()
                        savePersistedSession()
                        evaluateBackgroundDisconnectPolicy("reconnected")

                        // Reset reconnection attempts on successful reconnection
                        reconnectAttempts = 0

                        acquireWakeLock() // Re-acquire wake lock after reconnection
                        log(
                            LogLevel.INFO,
                            "Successfully reconnected to room",
                            "Code: ${payload.roomCode}, isHost: ${payload.isHost}, attempt was $reconnectAttempts",
                        )
                        emitEvent(ListenTogetherEvent.Reconnected(payload.roomCode, payload.userId, payload.state, payload.isHost))
                    }

                    MessageTypes.USER_RECONNECTED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? UserReconnectedPayload ?: return
                        // Mark user as connected in the room state
                        _roomState.value =
                            _roomState.value?.copy(
                                users =
                                    _roomState.value!!.users.map { user ->
                                        if (user.userId == payload.userId) user.copy(isConnected = true) else user
                                    },
                            )
                        log(LogLevel.INFO, "User reconnected", payload.username)
                        emitEvent(ListenTogetherEvent.UserReconnected(payload.userId, payload.username))
                    }

                    MessageTypes.USER_DISCONNECTED -> {
                        val payload = codec.decodePayload(msgType, payloadBytes) as? UserDisconnectedPayload ?: return
                        // Mark user as disconnected in the room state
                        _roomState.value =
                            _roomState.value?.copy(
                                users =
                                    _roomState.value!!.users.map { user ->
                                        if (user.userId == payload.userId) user.copy(isConnected = false) else user
                                    },
                            )
                        log(LogLevel.INFO, "User temporarily disconnected", payload.username)
                        emitEvent(ListenTogetherEvent.UserDisconnected(payload.userId, payload.username))
                    }

                    else -> {
                        log(LogLevel.WARNING, "Unknown message type", msgType)
                    }
                }
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Error parsing message", e.message)
            }
        }

        private inline fun <reified T> sendMessage(
            type: String,
            payload: T?,
        ) {
            try {
                val data = codec.encode(type, payload)
                log(LogLevel.DEBUG, "Sending message", "$type (protobuf)")

                val success = webSocket?.send(okio.ByteString.of(*data)) ?: false
                if (!success) {
                    log(LogLevel.ERROR, "Failed to send message", type)
                }
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Error encoding message", "$type: ${e.message}")
            }
        }

        private fun sendMessageNoPayload(type: String) {
            sendMessage<Unit>(type, null)
        }

        // Public API methods

        /**
         * Create a new listening room.
         * If not connected, will queue the action and connect first.
         */
        fun createRoom(username: String) {
            sessionApplyGeneration.incrementAndGet()
            lastPlaybackRevision.set(0L)
            // Clear any existing session to ensure we create a new room instead of reconnecting
            clearPersistedSession()
            sessionToken = null
            storedRoomCode = null
            wasHost = false

            storedUsername = username

            if (_connectionState.value == ConnectionState.CONNECTED) {
                sendMessage(MessageTypes.CREATE_ROOM, CreateRoomPayload(username))
            } else {
                log(LogLevel.INFO, "Not connected, queueing create room action")
                pendingAction = PendingAction.CreateRoom(username)
                evaluateBackgroundDisconnectPolicy("create_room_queued")
                if (_connectionState.value == ConnectionState.DISCONNECTED ||
                    _connectionState.value == ConnectionState.ERROR
                ) {
                    connect()
                }
                // If CONNECTING or RECONNECTING, the action will be executed when connected
            }
        }

        /**
         * Join an existing room.
         * If not connected, will queue the action and connect first.
         */
        fun joinRoom(
            roomCode: String,
            username: String,
        ) {
            sessionApplyGeneration.incrementAndGet()
            lastPlaybackRevision.set(0L)
            // Clear any existing session to ensure we join the new room instead of reconnecting
            clearPersistedSession()
            sessionToken = null
            storedRoomCode = null
            wasHost = false

            storedUsername = username

            if (_connectionState.value == ConnectionState.CONNECTED) {
                sendMessage(MessageTypes.JOIN_ROOM, JoinRoomPayload(roomCode.uppercase(), username))
            } else {
                log(LogLevel.INFO, "Not connected, queueing join room action")
                pendingAction = PendingAction.JoinRoom(roomCode, username)
                evaluateBackgroundDisconnectPolicy("join_room_queued")
                if (_connectionState.value == ConnectionState.DISCONNECTED ||
                    _connectionState.value == ConnectionState.ERROR
                ) {
                    connect()
                }
                // If CONNECTING or RECONNECTING, the action will be executed when connected
            }
        }

        /**
         * Leave the current room
         */
        fun leaveRoom() {
            sendMessageNoPayload(MessageTypes.LEAVE_ROOM)

            // Clear session info on intentional leave
            sessionToken = null
            storedRoomCode = null
            storedUsername = null
            pendingAction = null
            _roomState.value = null
            _role.value = RoomRole.NONE
            _userId.value = null
            _pendingJoinRequests.value = emptyList()
            _bufferingUsers.value = emptyList()
            lastPlaybackRevision.set(0L)

            // Clear from persistent storage
            clearPersistedSession()

            releaseWakeLock()
            evaluateBackgroundDisconnectPolicy("leave_room")
        }

        /**
         * Approve a join request (host only)
         */
        fun approveJoin(userId: String) {
            if (_role.value != RoomRole.HOST) {
                log(LogLevel.ERROR, "Cannot approve join", "Not host")
                return
            }
            sendMessage(MessageTypes.APPROVE_JOIN, ApproveJoinPayload(userId))

            // Dismiss notification immediately when approved from UI
            joinRequestNotifications.remove(userId)?.let { notifId ->
                NotificationManagerCompat.from(context).cancel(notifId)
            }
        }

        /**
         * Reject a join request (host only)
         */
        fun rejectJoin(
            userId: String,
            reason: String? = null,
        ) {
            if (_role.value != RoomRole.HOST) {
                log(LogLevel.ERROR, "Cannot reject join", "Not host")
                return
            }
            sendMessage(MessageTypes.REJECT_JOIN, RejectJoinPayload(userId, reason))
            _pendingJoinRequests.value = _pendingJoinRequests.value.filter { it.userId != userId }

            // Dismiss notification immediately when rejected from UI
            joinRequestNotifications.remove(userId)?.let { notifId ->
                NotificationManagerCompat.from(context).cancel(notifId)
            }
        }

        /**
         * Kick a user from the room (host only)
         */
        fun kickUser(
            userId: String,
            reason: String? = null,
        ) {
            if (_role.value != RoomRole.HOST) {
                log(LogLevel.ERROR, "Cannot kick user", "Not host")
                return
            }
            sendMessage(MessageTypes.KICK_USER, KickUserPayload(userId, reason))
        }

        /**
         * Transfer host role to another user (host only)
         */
        fun transferHost(newHostId: String) {
            if (_role.value != RoomRole.HOST) {
                log(LogLevel.ERROR, "Cannot transfer host", "Not host")
                return
            }
            sendMessage(MessageTypes.TRANSFER_HOST, TransferHostPayload(newHostId))
        }

        /**
         * Send a playback action (host only)
         */
        fun sendPlaybackAction(
            action: String,
            trackId: String? = null,
            position: Long? = null,
            trackInfo: TrackInfo? = null,
            insertNext: Boolean? = null,
            queue: List<TrackInfo>? = null,
            queueTitle: String? = null,
            volume: Float? = null,
        ) {
            if (_role.value != RoomRole.HOST) {
                log(LogLevel.ERROR, "Cannot control playback", "Not host")
                return
            }
            sendMessage(
                MessageTypes.PLAYBACK_ACTION,
                PlaybackActionPayload(
                    action,
                    trackId,
                    position,
                    trackInfo,
                    insertNext,
                    queue,
                    queueTitle,
                    volume,
                    capturedAtServerTime =
                        serverTimeNow().takeIf {
                            position != null &&
                                (action == PlaybackActions.PLAY || action == PlaybackActions.PAUSE || action == PlaybackActions.SEEK)
                        },
                ),
            )
        }

        /**
         * Signal that buffering is complete for the current track
         */
        fun sendBufferReady(trackId: String) {
            val sanitizedTrackId = trackId.trim()
            if (sanitizedTrackId.isEmpty()) {
                log(LogLevel.WARNING, "Skipping buffer ready", "Track ID is blank")
                return
            }
            sendMessage(MessageTypes.BUFFER_READY, BufferReadyPayload(sanitizedTrackId))
        }

        /**
         * Suggest a track to the host (guest only)
         */
        fun suggestTrack(trackInfo: TrackInfo) {
            if (!isInRoom) {
                log(LogLevel.ERROR, "Cannot suggest track", "Not in room")
                return
            }
            if (_role.value == RoomRole.HOST) {
                log(LogLevel.WARNING, "Host should not suggest tracks")
                return
            }
            sendMessage(MessageTypes.SUGGEST_TRACK, SuggestTrackPayload(trackInfo))
            scope.launch(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.listen_together_suggestion_sent), Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Approve a suggestion (host only)
         */
        fun approveSuggestion(suggestionId: String) {
            if (_role.value != RoomRole.HOST) {
                log(LogLevel.ERROR, "Cannot approve suggestion", "Not host")
                return
            }
            sendMessage(MessageTypes.APPROVE_SUGGESTION, ApproveSuggestionPayload(suggestionId))
            // Remove locally from pending list
            _pendingSuggestions.value = _pendingSuggestions.value.filter { it.suggestionId != suggestionId }

            // Dismiss notification immediately when approved from UI
            suggestionNotifications.remove(suggestionId)?.let { notifId ->
                NotificationManagerCompat.from(context).cancel(notifId)
            }
        }

        /**
         * Reject a suggestion (host only)
         */
        fun rejectSuggestion(
            suggestionId: String,
            reason: String? = null,
        ) {
            if (_role.value != RoomRole.HOST) {
                log(LogLevel.ERROR, "Cannot reject suggestion", "Not host")
                return
            }
            sendMessage(MessageTypes.REJECT_SUGGESTION, RejectSuggestionPayload(suggestionId, reason))
            _pendingSuggestions.value = _pendingSuggestions.value.filter { it.suggestionId != suggestionId }

            // Dismiss notification immediately when rejected from UI
            suggestionNotifications.remove(suggestionId)?.let { notifId ->
                NotificationManagerCompat.from(context).cancel(notifId)
            }
        }

        /**
         * Request current playback state from server (for guest re-sync)
         */
        fun requestSync() {
            if (_roomState.value == null) {
                log(LogLevel.ERROR, "Cannot request sync", "Not in room")
                return
            }
            log(LogLevel.INFO, "Requesting sync state from server")
            sendMessageNoPayload(MessageTypes.REQUEST_SYNC)
        }

        /**
         * Block a user permanently (internal list). Prevents their join requests and suggestions from appearing.
         */
        fun blockUser(username: String) {
            val updated = _blockedUsernames.value.toMutableSet()
            updated.add(username)
            _blockedUsernames.value = updated

            // Filter out blocked users from pending requests and suggestions
            _pendingJoinRequests.value =
                _pendingJoinRequests.value
                    .filter { it.username !in _blockedUsernames.value }
            _pendingSuggestions.value =
                _pendingSuggestions.value
                    .filter { it.fromUsername !in _blockedUsernames.value }

            // Save to storage
            scope.launch {
                saveBlockedUsernames()
            }

            log(LogLevel.INFO, "User blocked", username)
        }

        /**
         * Unblock a previously blocked user
         */
        fun unblockUser(username: String) {
            val updated = _blockedUsernames.value.toMutableSet()
            updated.remove(username)
            _blockedUsernames.value = updated

            // Save to storage
            scope.launch {
                saveBlockedUsernames()
            }

            log(LogLevel.INFO, "User unblocked", username)
        }

        /**
         * Check if a user is blocked
         */
        fun isUserBlocked(username: String): Boolean = username in _blockedUsernames.value

        /**
         * Check if currently in a room
         */
        val isInRoom: Boolean
            get() = _roomState.value != null

        /**
         * Check if current user is host
         */
        val isHost: Boolean
            get() = _role.value == RoomRole.HOST

        /**
         * Force reconnection to server (useful for manual recovery)
         */
        fun forceReconnect() {
            log(LogLevel.INFO, "Forcing reconnection to server")
            reconnectAttempts = 0 // Reset attempts to retry from start

            if (webSocket != null) {
                try {
                    webSocket?.close(1000, "Forcing reconnection")
                } catch (e: Exception) {
                    log(LogLevel.DEBUG, "Error closing WebSocket", e.message)
                }
                webSocket = null
            }

            _connectionState.value = ConnectionState.DISCONNECTED
            evaluateBackgroundDisconnectPolicy("force_reconnect")

            // Attempt connection with reset backoff
            scope.launch {
                delay(500)
                connect()
            }
        }

        /**
         * Check if there's a persisted session available for recovery
         */
        val hasPersistedSession: Boolean
            get() = sessionToken != null && storedRoomCode != null

        /**
         * Get the persisted room code if available
         */
        fun getPersistedRoomCode(): String? = storedRoomCode

        /**
         * Get current session age in milliseconds
         */
        fun getSessionAge(): Long =
            if (sessionStartTime > 0) {
                System.currentTimeMillis() - sessionStartTime
            } else {
                0L
            }
    }
