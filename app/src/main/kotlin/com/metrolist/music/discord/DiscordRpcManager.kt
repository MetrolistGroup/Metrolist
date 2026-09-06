package com.metrolist.music.discord

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

data class DiscordUser(
    val id: String,
    val username: String,
    val name: String,
    val avatar: String?,
)

internal sealed interface ConnectionIntent {
    data object EnsureConnected : ConnectionIntent
    data class Resume(val sessionId: String, val sequence: Int) : ConnectionIntent
    data object Identify : ConnectionIntent
    data object ForceRefreshAndIdentify : ConnectionIntent
}

internal fun intentPriority(intent: ConnectionIntent): Int = when (intent) {
    ConnectionIntent.EnsureConnected -> 0
    is ConnectionIntent.Resume -> 1
    ConnectionIntent.Identify -> 2
    ConnectionIntent.ForceRefreshAndIdentify -> 3
}

internal fun mergeIntents(current: ConnectionIntent, incoming: ConnectionIntent): ConnectionIntent =
    if (intentPriority(incoming) >= intentPriority(current)) incoming else current

object DiscordRpcManager {
    private const val TAG = "DiscordSvc"
    private const val MAX_RECONNECT_ATTEMPTS = 7

    private data class PendingConnectionRequest(
        var intent: ConnectionIntent,
        val completions: MutableList<CompletableDeferred<Boolean>> = mutableListOf(),
    )

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var _ready: Boolean = false

    @Volatile
    private var _authorized: Boolean = false

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var authorizeInProgress: Boolean = false

    @Volatile
    private var lastActivitySentAtMs: Long = 0L

    @Volatile
    private var lastActivity: ActivityPayload? = null

    @Volatile private var currentSongId: String? = null
    @Volatile private var currentIsPlaying: Boolean = false
    @Volatile private var lastForcedRefreshAtMs: Long = 0L
    private val currentActivityId = AtomicLong(0L)
    @Volatile private var imageResolutionJob: Job? = null
    @Volatile private var currentActivityHadImages: Boolean = false

    // Every connection lifecycle mutation is serialized through this state and its single worker.
    private val coordinatorLock = Any()
    private var pendingConnectionRequest: PendingConnectionRequest? = null
    private var activeConnectionIntent: ConnectionIntent? = null
    private var coordinatorJob: Job? = null
    private var retryJob: Job? = null
    private var reconnectAttempts: Int = 0
    private var retryExhausted: Boolean = false
    private var connectionEpoch: Long = 0L
    private var currentGatewayConnectionId: Long? = null
    private var forceRefreshRequired: Boolean = false
    private var terminalGatewayFailure: Boolean = false

    private val _accessTokenFlow = MutableStateFlow<String?>(null)
    val accessTokenFlow: StateFlow<String?> = _accessTokenFlow

    private val _connectionStatus = MutableStateFlow(Status.Disconnected)
    val connectionStatus: StateFlow<Status> = _connectionStatus

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _currentUser = MutableStateFlow<DiscordUser?>(null)
    val currentUser: StateFlow<DiscordUser?> = _currentUser

    private val _settingsChanged = MutableStateFlow(0)
    val settingsChanged: StateFlow<Int> = _settingsChanged

    fun notifySettingsChanged() {
        Timber.tag(TAG).d("notifySettingsChanged: incrementing (count=%d), invalidating dedup", _settingsChanged.value + 1)
        _settingsChanged.value++
        currentSongId = null
        currentIsPlaying = false
    }

    enum class Status { Disconnected, Authorizing, Connected }

    fun getAccessToken(): String? = accessToken

    fun isInitialized(): Boolean = initialized

    fun isAuthorized(): Boolean = _authorized

    fun isReady(): Boolean = _ready

    fun isShowingSong(songId: String, isPlaying: Boolean): Boolean {
        if (currentSongId != songId || currentIsPlaying != isPlaying) {
            return false
        }
        if (lastActivity == null) {
            return false
        }
        // If the last activity had images to resolve but none were sent,
        // and no resolution is in progress, allow the caller to retry.
        if (currentActivityHadImages &&
            lastActivity?.largeImage == null && lastActivity?.smallImage == null &&
            (imageResolutionJob == null || imageResolutionJob?.isCompleted == true)
        ) {
            return false
        }
        return true
    }

    fun clearLastError() {
        _lastError.value = null
    }

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appId: String = BuildConfigProvider.appId

    private val auth: DiscordAuth = DiscordAuth()

    private var gateway: DiscordGateway = createGateway(scope)

    private fun createGateway(scope: CoroutineScope): DiscordGateway =
        DiscordGateway(
            appId = appId,
            externalScope = scope,
        )

    private fun startEventCollection() {
        scope.launch {
            gateway.events.collect { event -> handleGatewayEvent(event) }
        }
    }

    fun init(context: Context) {
        DiscordTokenStore.init(context.applicationContext)
        if (initialized && scope.isActive) {
            Timber.tag(TAG).i("init: already initialized and active, skipping")
            return
        }
        if (!scope.isActive) {
            Timber.tag(TAG).i("init: recreating scope after previous destroy")
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            gateway = createGateway(scope)
        }
        initialized = true
        _connectionStatus.value = Status.Disconnected
        startEventCollection()
        Timber.tag(TAG).i("init: token store initialized, scheduling auto-rehydrate")

        scope.launch {
            val saved = DiscordTokenStore.retrieveSuspend()
            if (!saved.isNullOrEmpty()) {
                Timber.tag(TAG).i("init: found persisted token, reconnecting")
                reconnect()
            } else {
                Timber.tag(TAG).i("init: no persisted token, waiting for explicit authorize")
            }
        }
    }

    fun authorize(activity: Activity, onComplete: (Boolean) -> Unit) {
        if (authorizeInProgress) {
            Timber.tag(TAG).w("authorize: already in progress, ignoring double-tap")
            scope.launch(Dispatchers.Main) { onComplete(false) }
            return
        }
        authorizeInProgress = true

        fun completeWith(success: Boolean) {
            scope.launch(Dispatchers.Main) { onComplete(success) }
        }

        if (_ready && _authorized) {
            Timber.tag(TAG).d("authorize: short-circuit — already ready and authorized")
            authorizeInProgress = false
            scope.launch(Dispatchers.Main) { onComplete(true) }
            return
        }
        if (_authorized) {
            Timber.tag(TAG).d("authorize: short-circuit — authorized but not ready, reconnecting")
            authorizeInProgress = false
            reconnect()
            scope.launch(Dispatchers.Main) { onComplete(true) }
            return
        }

        _connectionStatus.value = Status.Authorizing
        _lastError.value = null

        scope.launch {
            try {
                val result = auth.authorize(activity)
                val connected = requestConnectionAndAwait(
                    intent = ConnectionIntent.Identify,
                    newAuthorization = result,
                )
                if (connected) {
                    completeWith(true)
                } else {
                    completeWith(false)
                }
            } catch (e: DiscordAuthException.UserCancelled) {
                Timber.tag(TAG).i("authorize: user cancelled")
                _lastError.value = "discord_error_loopback_timeout"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.StateMismatch) {
                Timber.tag(TAG).w(e, "authorize: state mismatch")
                _lastError.value = "discord_error_invalid_scope"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.NetworkFailure) {
                Timber.tag(TAG).e(e, "authorize: network failure")
                _lastError.value = "discord_error_loopback_timeout"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.NoBrowser) {
                Timber.tag(TAG).w(e, "authorize: no browser available")
                _lastError.value = "discord_error_no_browser"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.InvalidGrant) {
                Timber.tag(TAG).w(e, "authorize: invalid grant")
                _lastError.value = "discord_error_token_refresh_failed"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "authorize: unexpected failure")
                _lastError.value = "discord_error_loopback_timeout"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } finally {
                authorizeInProgress = false
            }
        }
    }

    fun cancelAuthorize() {
        Timber.tag(TAG).i("cancelAuthorize: cancelling active authorization")
        auth.cancel()
    }

    fun fetchCurrentUser(token: String): DiscordUser? {
        return try {
            val url = URL("https://discord.com/api/v10/users/@me")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()

            if (responseCode !in 200..299) {
                Timber.tag(TAG).w("fetchCurrentUser: HTTP $responseCode body=$responseBody")
                return null
            }

            val json = JSONObject(responseBody)
            val id = json.getString("id")
            val username = json.getString("username")
            val name = json.optString("global_name", username)
            val avatarHash = json.optString("avatar")
            val avatar = if (avatarHash.isNotEmpty() && avatarHash != "null") {
                "https://cdn.discordapp.com/avatars/$id/$avatarHash.png"
            } else null

            DiscordUser(id, username, name, avatar)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "fetchCurrentUser: exception")
            null
        }
    }

    fun setActivity(
        activity: DiscordActivity,
        songId: String? = null,
        isPlaying: Boolean = true,
        status: PresenceStatus = PresenceStatus.Online,
    ) {
        if (!_ready) {
            Timber.tag(TAG).w("setActivity: skipping — not ready (name=%s)", activity.name)
            return
        }

        val stateChanged = songId != currentSongId || isPlaying != currentIsPlaying ||
            (activity.largeImage != null && activity.largeImage != lastActivity?.largeImage) ||
            (activity.smallImage != null && activity.smallImage != lastActivity?.smallImage)

        val now = System.currentTimeMillis()
        if (!stateChanged &&
            lastActivitySentAtMs > 0L && (now - lastActivitySentAtMs) < 2_000L
        ) {
            Timber.tag(TAG).v("setActivity: debounced (<2s since last, stateChanged=%s)", stateChanged)
            return
        }
        lastActivitySentAtMs = now

        currentSongId = songId
        currentIsPlaying = isPlaying
        currentActivityId.incrementAndGet()
        currentActivityHadImages = !activity.largeImage.isNullOrEmpty() || !activity.smallImage.isNullOrEmpty()

        val buttons = buildList {
            if (!activity.button1Label.isNullOrEmpty() && !activity.button1Url.isNullOrEmpty()) {
                add(activity.button1Label to activity.button1Url)
            }
            if (!activity.button2Label.isNullOrEmpty() && !activity.button2Url.isNullOrEmpty()) {
                add(activity.button2Label to activity.button2Url)
            }
        }
        val payloadNoImages = DiscordPresence.buildActivity(
            name = activity.name.orEmpty(),
            type = activityTypeToEnum(activity.activityType),
            details = activity.details,
            state = activity.state,
            startMs = activity.startTimestamp.takeIf { it > 0L },
            endMs = activity.endTimestamp?.takeIf { it > 0L },
            buttons = buttons,
        )

        lastActivity = payloadNoImages

        try {
            val presenceJson = DiscordPresence.buildPresenceUpdate(
                status = status,
                activities = listOf(payloadNoImages),
            )
            Timber.tag(TAG).i("setActivity: sending (type=%d, name=%s, details=%s, state=%s, songId=%s, isPlaying=%s, buttons=%d)",
                activity.activityType, activity.name, activity.details, activity.state, songId, isPlaying, buttons.size)
            gateway.presenceUpdate(presenceJson)
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).w(e, "setActivity: gateway not open")
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "setActivity: send failed")
        }

        imageResolutionJob?.cancel()

        val currentToken = accessToken ?: return
        val largeImageUrl = activity.largeImage
        val smallImageUrl = activity.smallImage
        if (largeImageUrl.isNullOrEmpty() && smallImageUrl.isNullOrEmpty()) return

        Timber.tag(TAG).d(
            "setActivity: resolving images — large=%s, small=%s",
            largeImageUrl?.take(80),
            smallImageUrl?.take(80),
        )

        val activityIdAtLaunch = currentActivityId.get()
        val songIdAtLaunch = songId

        imageResolutionJob = scope.launch {
            val tokenHeader = "Bearer $currentToken"
            val largeResolved = if (!largeImageUrl.isNullOrEmpty()) {
                DiscordExternalAssets.resolve(largeImageUrl, appId, tokenHeader)
            } else null
            val smallResolved = if (!smallImageUrl.isNullOrEmpty()) {
                DiscordExternalAssets.resolve(smallImageUrl, appId, tokenHeader)
            } else null

            if (largeResolved == null && smallResolved == null) {
                Timber.tag(TAG).i("setActivity: image resolution returned null, keeping text-only presence")
                return@launch
            }

            if (activityIdAtLaunch != currentActivityId.get()) {
                Timber.tag(TAG).i(
                    "setActivity: stale image resolution (launched activityId=%d, current=%d), skipping re-send",
                    activityIdAtLaunch, currentActivityId.get(),
                )
                return@launch
            }

            val payloadWithImages = DiscordPresence.buildActivity(
                name = activity.name.orEmpty(),
                type = activityTypeToEnum(activity.activityType),
                details = activity.details,
                state = activity.state,
                largeImage = largeResolved,
                largeText = activity.largeText,
                smallImage = smallResolved,
                smallText = activity.smallText,
                startMs = activity.startTimestamp.takeIf { it > 0L },
                endMs = activity.endTimestamp?.takeIf { it > 0L },
                buttons = buttons,
            )

            lastActivity = payloadWithImages

            try {
                val presenceJson = DiscordPresence.buildPresenceUpdate(
                    status = status,
                    activities = listOf(payloadWithImages),
                )
                Timber.tag(TAG).i("setActivity: re-sending with images for songId=%s", songIdAtLaunch)
                gateway.presenceUpdate(presenceJson)
            } catch (e: IllegalStateException) {
                Timber.tag(TAG).w(e, "setActivity: image re-send gateway not open")
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "setActivity: image re-send failed")
            }
        }
    }

    fun clear() {
        if (!_ready) {
            Timber.tag(TAG).w("clear: skipping — not ready")
            return
        }
        if (lastActivity == null && currentSongId == null) {
            Timber.tag(TAG).d("clear: already cleared, skipping")
            return
        }
        lastActivity = null
        currentSongId = null
        currentIsPlaying = false
        currentActivityHadImages = false
        currentActivityId.incrementAndGet()
        imageResolutionJob?.cancel()
        try {
            gateway.presenceUpdate(
                DiscordPresence.buildPresenceUpdate(
                    status = PresenceStatus.Online,
                    activities = emptyList(),
                ),
            )
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).w(e, "clear: gateway not open")
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "clear: send failed")
        }
    }

    fun reconnect(forceRefresh: Boolean = false) {
        if (!initialized) {
            Timber.tag(TAG).w("reconnect: not initialized, ignoring")
            return
        }
        val intent = if (forceRefresh) {
            ConnectionIntent.ForceRefreshAndIdentify
        } else {
            ConnectionIntent.EnsureConnected
        }
        requestConnection(intent)
    }

    private suspend fun requestConnectionAndAwait(
        intent: ConnectionIntent,
        newAuthorization: DiscordAuthResult? = null,
    ): Boolean {
        val completion = CompletableDeferred<Boolean>()
        if (!requestConnection(intent, completion, newAuthorization = newAuthorization)) {
            return false
        }
        return completion.await()
    }

    private fun requestConnection(
        requestedIntent: ConnectionIntent,
        completion: CompletableDeferred<Boolean>? = null,
        newAuthorization: DiscordAuthResult? = null,
        fromRetry: Boolean = false,
        requiredEpoch: Long? = null,
    ): Boolean {
        var delayedRetryToCancel: Job? = null
        val accepted = synchronized(coordinatorLock) {
            if (!initialized || !scope.isActive) {
                completion?.complete(false)
                return@synchronized false
            }
            if (requiredEpoch != null &&
                (connectionEpoch != requiredEpoch || _ready)
            ) {
                return@synchronized false
            }

            val hasNewAuthorization = newAuthorization != null
            if (newAuthorization != null) {
                connectionEpoch++
                currentGatewayConnectionId = null
                gateway.close(4000, "new authorization")
                pendingConnectionRequest?.intent = requestedIntent
                reconnectAttempts = 0
                retryExhausted = false
                forceRefreshRequired = false
                terminalGatewayFailure = false
                lastForcedRefreshAtMs = 0L
                accessToken = newAuthorization.accessToken
                _accessTokenFlow.value = newAuthorization.accessToken
                DiscordTokenStore.storeFull(
                    newAuthorization.accessToken,
                    newAuthorization.refreshToken,
                    newAuthorization.expiresInSec,
                )
                _authorized = true
                _ready = false
                _connectionStatus.value = Status.Authorizing
            }

            var intent = requestedIntent
            if (intent is ConnectionIntent.ForceRefreshAndIdentify) {
                forceRefreshRequired = true
            } else if (intent is ConnectionIntent.EnsureConnected && forceRefreshRequired) {
                intent = ConnectionIntent.ForceRefreshAndIdentify
            }

            if (terminalGatewayFailure && !hasNewAuthorization) {
                Timber.tag(TAG).w("requestConnection: ignoring reconnect after terminal close")
                completion?.complete(false)
                return@synchronized false
            }
            if (intent is ConnectionIntent.EnsureConnected && _ready) {
                completion?.complete(true)
                return@synchronized true
            }
            if (intent is ConnectionIntent.EnsureConnected &&
                currentGatewayConnectionId != null &&
                _connectionStatus.value == Status.Authorizing
            ) {
                Timber.tag(TAG).d("requestConnection: coalescing with gateway authentication")
                completion?.complete(true)
                return@synchronized true
            }

            val supersedesDelay = hasNewAuthorization ||
                intent is ConnectionIntent.ForceRefreshAndIdentify
            if (retryJob?.isActive == true) {
                if (!supersedesDelay) {
                    Timber.tag(TAG).d("requestConnection: coalescing with scheduled retry")
                    completion?.complete(false)
                    return@synchronized true
                }
                delayedRetryToCancel = retryJob
                retryJob = null
            }

            if (intent is ConnectionIntent.EnsureConnected &&
                retryExhausted &&
                !fromRetry &&
                activeConnectionIntent == null &&
                pendingConnectionRequest == null
            ) {
                Timber.tag(TAG).i("requestConnection: re-arming exhausted reconnect ladder")
                reconnectAttempts = 0
                retryExhausted = false
            }

            if (!hasNewAuthorization &&
                completion == null &&
                activeConnectionIntent != null &&
                intentPriority(activeConnectionIntent!!) >= intentPriority(intent)
            ) {
                Timber.tag(TAG).d("requestConnection: coalescing with active %s", activeConnectionIntent)
                return@synchronized true
            }

            val pending = pendingConnectionRequest
            if (pending == null) {
                pendingConnectionRequest = PendingConnectionRequest(
                    intent = intent,
                    completions = completion?.let { mutableListOf(it) } ?: mutableListOf(),
                )
            } else {
                pending.intent = mergeIntents(pending.intent, intent)
                if (completion != null) pending.completions += completion
            }
            startCoordinatorLocked()
            true
        }
        delayedRetryToCancel?.cancel()
        return accepted
    }

    private fun startCoordinatorLocked() {
        if (pendingConnectionRequest == null || coordinatorJob?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runConnectionCoordinator()
        }
        coordinatorJob = job
        job.start()
    }

    private suspend fun runConnectionCoordinator() {
        val workerJob = currentCoroutineContext()[Job] ?: return
        try {
            while (currentCoroutineContext().isActive) {
                val request = synchronized(coordinatorLock) {
                    if (coordinatorJob !== workerJob) return
                    val next = pendingConnectionRequest
                    if (next == null) {
                        activeConnectionIntent = null
                        coordinatorJob = null
                        return
                    }
                    pendingConnectionRequest = null
                    activeConnectionIntent = next.intent
                    next
                }

                val success = try {
                    processConnectionIntent(request.intent)
                } catch (e: CancellationException) {
                    request.completions.forEach { it.complete(false) }
                    throw e
                } catch (e: Throwable) {
                    Timber.tag(TAG).e(e, "connection coordinator: unexpected failure")
                    false
                }
                val currentSuccess = synchronized(coordinatorLock) {
                    success &&
                        coordinatorJob === workerJob &&
                        currentGatewayConnectionId != null
                }
                request.completions.forEach { it.complete(currentSuccess) }
                synchronized(coordinatorLock) {
                    if (coordinatorJob === workerJob) {
                        activeConnectionIntent = null
                    }
                }
            }
        } finally {
            synchronized(coordinatorLock) {
                if (coordinatorJob === workerJob) {
                    coordinatorJob = null
                    activeConnectionIntent = null
                    startCoordinatorLocked()
                }
            }
        }
    }

    private suspend fun processConnectionIntent(intent: ConnectionIntent): Boolean {
        val initialEpoch = synchronized(coordinatorLock) {
            if (terminalGatewayFailure) return false
            connectionEpoch
        }
        val forceRefresh = intent is ConnectionIntent.ForceRefreshAndIdentify
        val token = resolveConnectionToken(forceRefresh, initialEpoch) ?: return false
        currentCoroutineContext().ensureActive()

        val establishIntent = when (intent) {
            is ConnectionIntent.Resume -> intent
            ConnectionIntent.EnsureConnected,
            ConnectionIntent.Identify,
            ConnectionIntent.ForceRefreshAndIdentify -> ConnectionIntent.Identify
        }

        var attemptEpoch: Long? = null
        return try {
            val currentAttemptEpoch = beginEstablish(initialEpoch) ?: throw GatewaySupersededException()
            attemptEpoch = currentAttemptEpoch
            if (establishIntent is ConnectionIntent.Identify) {
                gateway.invalidateSession()
            }
            gateway.close(4000, "reconnecting")
            ensureCurrentEpoch(currentAttemptEpoch)

            val connectionId = gateway.connect { createdConnectionId ->
                // Register before opening so an immediate close cannot outrun connect()'s return.
                synchronized(coordinatorLock) {
                    if (connectionEpoch != currentAttemptEpoch) throw GatewaySupersededException()
                    currentGatewayConnectionId = createdConnectionId
                }
            }
            ensureCurrentEpoch(currentAttemptEpoch)
            synchronized(coordinatorLock) {
                if (connectionEpoch != currentAttemptEpoch ||
                    currentGatewayConnectionId != connectionId
                ) {
                    throw GatewaySupersededException()
                }
            }

            when (establishIntent) {
                is ConnectionIntent.Resume -> gateway.resume(
                    sessionId = establishIntent.sessionId,
                    seq = establishIntent.sequence,
                    token = "Bearer $token",
                    connectionId = connectionId,
                )
                ConnectionIntent.Identify -> gateway.identify(
                    token = "Bearer $token",
                    connectionId = connectionId,
                )
                else -> error("Unsupported establish intent: $establishIntent")
            }
            ensureCurrentEpoch(currentAttemptEpoch)
            true
        } catch (e: GatewaySupersededException) {
            Timber.tag(TAG).i("connection attempt superseded")
            false
        } catch (e: GatewayConnectionException) {
            Timber.tag(TAG).w(e, "gateway connection failed before open")
            val epoch = attemptEpoch
            if (epoch != null && publishConnectionFailure(epoch, "discord_error_loopback_timeout")) {
                scheduleRetry(establishIntent, e.statusCode, e.retryReason, epoch)
            }
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "gateway connect/authenticate failed")
            val epoch = attemptEpoch
            if (epoch != null && publishConnectionFailure(epoch, "discord_error_loopback_timeout")) {
                gateway.close(1011, "authentication send failed")
                scheduleRetry(establishIntent, 4000, e.message ?: "failure", epoch)
            }
            false
        }
    }

    private suspend fun resolveConnectionToken(forceRefresh: Boolean, expectedEpoch: Long): String? {
        val storedToken = accessToken ?: DiscordTokenStore.retrieveSuspend()
        if (!isCurrentEpoch(expectedEpoch)) return null
        if (storedToken.isNullOrEmpty()) {
            Timber.tag(TAG).w("resolveConnectionToken: no token available")
            return null
        }
        synchronized(coordinatorLock) {
            if (connectionEpoch != expectedEpoch) return null
            accessToken = storedToken
            _accessTokenFlow.value = storedToken
        }

        val refreshToken = DiscordTokenStore.getRefreshToken()
        val expiresAt = DiscordTokenStore.getExpiresAt()
        val nowSec = System.currentTimeMillis() / 1000L
        val needsRefresh = forceRefresh ||
            (!refreshToken.isNullOrEmpty() && expiresAt > 0L && (expiresAt - nowSec) < 3600L)

        Timber.tag(TAG).i(
            "resolveConnectionToken: hasRefreshToken=%s, expiresAt=%d, now=%d, needsRefresh=%s, forced=%s",
            !refreshToken.isNullOrEmpty(),
            expiresAt,
            nowSec,
            needsRefresh,
            forceRefresh,
        )
        if (!needsRefresh) return storedToken

        if (refreshToken.isNullOrEmpty()) {
            Timber.tag(TAG).w("resolveConnectionToken: refresh needed but no refresh token")
            performLogout("discord_error_token_refresh_failed")
            return null
        }

        val now = System.currentTimeMillis()
        synchronized(coordinatorLock) {
            if (connectionEpoch != expectedEpoch) return null
            if (forceRefresh && (now - lastForcedRefreshAtMs) < 60_000L) {
                Timber.tag(TAG).w("resolveConnectionToken: forced refresh throttled")
                _lastError.value = "discord_error_token_refresh_failed"
                _connectionStatus.value = Status.Disconnected
                return null
            }
            if (forceRefresh) lastForcedRefreshAtMs = now
        }

        val refreshed = try {
            auth.refresh(refreshToken)
        } catch (e: DiscordAuthException.InvalidGrant) {
            if (isCurrentEpoch(expectedEpoch)) {
                Timber.tag(TAG).w(e, "resolveConnectionToken: refresh token rejected")
                performLogout("discord_error_token_refresh_failed")
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "resolveConnectionToken: token refresh failed")
            null
        }

        if (!isCurrentEpoch(expectedEpoch)) return null
        if (refreshed == null) {
            if (forceRefresh) {
                synchronized(coordinatorLock) {
                    if (connectionEpoch != expectedEpoch) return null
                    _lastError.value = "discord_error_token_refresh_failed"
                    _connectionStatus.value = Status.Disconnected
                }
                return null
            }
            return storedToken
        }

        synchronized(coordinatorLock) {
            if (connectionEpoch != expectedEpoch) return null
            accessToken = refreshed.accessToken
            _accessTokenFlow.value = refreshed.accessToken
            DiscordTokenStore.storeFull(
                refreshed.accessToken,
                refreshed.refreshToken,
                refreshed.expiresInSec,
            )
            if (forceRefresh) forceRefreshRequired = false
        }
        return refreshed.accessToken
    }

    private fun beginEstablish(expectedEpoch: Long): Long? = synchronized(coordinatorLock) {
        if (!initialized || connectionEpoch != expectedEpoch) return@synchronized null
        connectionEpoch++
        currentGatewayConnectionId = null
        _ready = false
        _connectionStatus.value = Status.Authorizing
        connectionEpoch
    }

    private suspend fun ensureCurrentEpoch(expectedEpoch: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrentEpoch(expectedEpoch)) throw GatewaySupersededException()
    }

    private fun isCurrentEpoch(expectedEpoch: Long): Boolean = synchronized(coordinatorLock) {
        initialized && connectionEpoch == expectedEpoch
    }

    private fun publishConnectionFailure(expectedEpoch: Long, error: String): Boolean =
        synchronized(coordinatorLock) {
            if (!initialized || connectionEpoch != expectedEpoch) return@synchronized false
            currentGatewayConnectionId = null
            _ready = false
            _authorized = false
            _lastError.value = error
            _connectionStatus.value = Status.Disconnected
            true
        }

    private fun scheduleRetry(
        intent: ConnectionIntent,
        closeCode: Int,
        reason: String,
        expectedEpoch: Long,
    ) {
        var exhausted = false
        synchronized(coordinatorLock) {
            if (!initialized || connectionEpoch != expectedEpoch || _ready || retryJob?.isActive == true) {
                return
            }
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                retryExhausted = true
                exhausted = true
                return@synchronized
            }

            reconnectAttempts++
            val attempt = reconnectAttempts
            val delayMs = DiscordReconnectStrategy.backoffDelayMs(attempt, closeCode, reason)
            lateinit var scheduledJob: Job
            scheduledJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    delay(delayMs)
                    val shouldRun = synchronized(coordinatorLock) {
                        if (retryJob !== scheduledJob ||
                            connectionEpoch != expectedEpoch ||
                            _ready ||
                            !initialized
                        ) {
                            false
                        } else {
                            retryJob = null
                            true
                        }
                    }
                    if (shouldRun) {
                        requestConnection(
                            requestedIntent = intent,
                            fromRetry = true,
                            requiredEpoch = expectedEpoch,
                        )
                    }
                } finally {
                    synchronized(coordinatorLock) {
                        if (retryJob === scheduledJob) retryJob = null
                    }
                }
            }
            retryJob = scheduledJob
            Timber.tag(TAG).i(
                "scheduleRetry: retrying in %dms (attempt %d, code=%d, intent=%s)",
                delayMs,
                attempt,
                closeCode,
                intent,
            )
            scheduledJob.start()
        }
        if (exhausted) {
            Timber.tag(TAG).w("scheduleRetry: max reconnect attempts reached")
            val error = when (closeCode) {
                4001, 4014 -> "discord_error_invalid_scope"
                4004 -> "discord_error_token_refresh_failed"
                else -> "discord_error_loopback_timeout"
            }
            publishConnectionFailure(expectedEpoch, error)
        }
    }

    fun disconnect() {
        Timber.tag(TAG).i("disconnect: closing gateway, clearing ready/authorized")
        cancelConnectionWork(closeReason = "user disconnect")
        currentActivityId.incrementAndGet()
        imageResolutionJob?.cancel()
        currentSongId = null
        currentIsPlaying = false
        currentActivityHadImages = false
    }

    fun destroy() {
        Timber.tag(TAG).i("destroy: cancelling scope and tearing down (initialized=%s)", initialized)
        cancelConnectionWork(
            closeReason = "destroy",
            clearTerminalState = true,
            deactivate = true,
        )
        currentActivityId.incrementAndGet()
        imageResolutionJob?.cancel()
        runCatching { gateway.closeHttp() }
        scope.cancel()
        lastActivity = null
        currentSongId = null
        currentIsPlaying = false
        currentActivityHadImages = false
    }

    fun logout() {
        Timber.tag(TAG).i("logout: clearing tokens and disconnecting")
        performLogout(null)
    }

    private fun performLogout(error: String?) {
        cancelConnectionWork(
            closeReason = "logout",
            clearTerminalState = true,
            clearCredentials = true,
            connectionError = error,
        )
        currentActivityId.incrementAndGet()
        imageResolutionJob?.cancel()
        _currentUser.value = null
        lastActivity = null
        currentSongId = null
        currentIsPlaying = false
        currentActivityHadImages = false
    }

    private fun cancelConnectionWork(
        closeReason: String,
        clearTerminalState: Boolean = false,
        deactivate: Boolean = false,
        clearCredentials: Boolean = false,
        connectionError: String? = null,
    ) {
        val pendingCompletions: List<CompletableDeferred<Boolean>>
        val worker: Job?
        val delayedRetry: Job?
        synchronized(coordinatorLock) {
            connectionEpoch++
            currentGatewayConnectionId = null
            pendingCompletions = pendingConnectionRequest?.completions?.toList().orEmpty()
            pendingConnectionRequest = null
            activeConnectionIntent = null
            worker = coordinatorJob
            delayedRetry = retryJob
            coordinatorJob = null
            retryJob = null
            _ready = false
            _authorized = false
            _connectionStatus.value = Status.Disconnected
            if (deactivate) initialized = false
            gateway.close(1000, closeReason)
            if (clearTerminalState) {
                terminalGatewayFailure = false
                forceRefreshRequired = false
                reconnectAttempts = 0
                retryExhausted = false
                lastForcedRefreshAtMs = 0L
            }
            if (clearCredentials) {
                accessToken = null
                _accessTokenFlow.value = null
                DiscordTokenStore.clear()
                DiscordSuperProperties.reset()
                _lastError.value = connectionError
            }
        }
        pendingCompletions.forEach { it.complete(false) }
        delayedRetry?.cancel()
        worker?.cancel()
    }

    private suspend fun handleGatewayEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Ready -> {
                var obsoleteRetry: Job? = null
                val eventEpoch = synchronized(coordinatorLock) {
                    if (event.connectionId != currentGatewayConnectionId) return
                    reconnectAttempts = 0
                    retryExhausted = false
                    obsoleteRetry = retryJob
                    retryJob = null
                    _ready = true
                    _authorized = true
                    _connectionStatus.value = Status.Connected
                    _lastError.value = null
                    connectionEpoch
                }
                obsoleteRetry?.cancel()
                Timber.tag(TAG).i("gateway: READY (sessionId prefix=%s)", event.sessionId.take(8))
                val token = accessToken ?: return
                scope.launch {
                    val user = fetchCurrentUser(token)
                    val current = synchronized(coordinatorLock) {
                        connectionEpoch == eventEpoch &&
                            currentGatewayConnectionId == event.connectionId &&
                            _ready
                    }
                    if (current) {
                        _currentUser.value = user
                    }
                    if (current && user != null) {
                        Timber.tag(TAG).i("gateway READY: fetched user %s", user.username)
                    }
                }
            }
            is GatewayEvent.Resumed -> {
                var obsoleteRetry: Job? = null
                synchronized(coordinatorLock) {
                    if (event.connectionId != currentGatewayConnectionId) return
                    reconnectAttempts = 0
                    retryExhausted = false
                    obsoleteRetry = retryJob
                    retryJob = null
                    _ready = true
                    _authorized = true
                    _connectionStatus.value = Status.Connected
                    _lastError.value = null
                }
                obsoleteRetry?.cancel()
                Timber.tag(TAG).i("gateway: RESUMED")
            }
            is GatewayEvent.Disconnected -> {
                val action = if (event.code == 1000 && event.remote) {
                    null
                } else {
                    DiscordReconnectStrategy.decide(
                        closeCode = event.code,
                        hadSession = gateway.sessionId != null,
                        seq = gateway.currentSeq,
                        sessionId = gateway.sessionId,
                    )
                }
                val eventEpoch = synchronized(coordinatorLock) {
                    if (event.connectionId != currentGatewayConnectionId) return
                    currentGatewayConnectionId = null
                    _ready = false
                    _authorized = false
                    _connectionStatus.value = Status.Disconnected
                    when (action) {
                        ReconnectAction.RefreshAndReIdentify -> forceRefreshRequired = true
                        ReconnectAction.SurfaceFatal -> {
                            terminalGatewayFailure = true
                            _lastError.value = "discord_error_invalid_scope"
                        }
                        else -> Unit
                    }
                    connectionEpoch
                }
                Timber.tag(TAG).i("gateway: Disconnected (code=%d, remote=%s, reason=%s)",
                    event.code, event.remote, event.reason)
                currentSongId = null
                currentIsPlaying = false
                imageResolutionJob?.cancel()
                imageResolutionJob = null

                if (event.code == 1000 && event.remote) {
                    invalidateGatewaySession(eventEpoch)
                    return
                }

                val reconnectAction = checkNotNull(action)
                Timber.tag(TAG).i(
                    "gateway: reconnect strategy=%s for closeCode=%d",
                    reconnectAction::class.simpleName,
                    event.code,
                )
                when (reconnectAction) {
                    is ReconnectAction.Resume -> scheduleRetry(
                        intent = ConnectionIntent.Resume(reconnectAction.sessionId, reconnectAction.seq),
                        closeCode = event.code,
                        reason = event.reason,
                        expectedEpoch = eventEpoch,
                    )
                    ReconnectAction.ReIdentify -> {
                        invalidateGatewaySession(eventEpoch)
                        scheduleRetry(
                            intent = ConnectionIntent.Identify,
                            closeCode = event.code,
                            reason = event.reason,
                            expectedEpoch = eventEpoch,
                        )
                    }
                    ReconnectAction.RefreshAndReIdentify -> {
                        invalidateGatewaySession(eventEpoch)
                        requestConnection(
                            requestedIntent = ConnectionIntent.ForceRefreshAndIdentify,
                            requiredEpoch = eventEpoch,
                        )
                    }
                    ReconnectAction.SurfaceFatal -> {
                        invalidateGatewaySession(eventEpoch)
                    }
                }
            }
            is GatewayEvent.InvalidSession -> {
                val current = synchronized(coordinatorLock) {
                    event.connectionId == currentGatewayConnectionId
                }
                if (!current) return
                Timber.tag(TAG).w("gateway: InvalidSession (resumable=%s), closing WS to trigger reconnect", event.resumable)
                imageResolutionJob?.cancel()
                imageResolutionJob = null
            }
            is GatewayEvent.Hello -> Unit
            is GatewayEvent.HeartbeatAck -> Unit
            is GatewayEvent.TextDispatch -> {
                Timber.tag(TAG).v("gateway: TextDispatch op=%d t=%s", event.op, event.t)
            }
        }
    }

    private fun invalidateGatewaySession(expectedEpoch: Long) {
        synchronized(coordinatorLock) {
            if (connectionEpoch == expectedEpoch) {
                gateway.invalidateSession()
            }
        }
    }

    private fun activityTypeToEnum(code: Int): ActivityType = when (code) {
        0 -> ActivityType.Playing
        1 -> ActivityType.Streaming
        2 -> ActivityType.Listening
        3 -> ActivityType.Watching
        4 -> ActivityType.Custom
        5 -> ActivityType.Competing
        else -> ActivityType.Listening
    }
}

private object BuildConfigProvider {
    val appId: String = com.metrolist.music.BuildConfig.DISCORD_APP_ID.toString()
}
