@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.discord

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.network.hasHttpTransportFailureInCauseChain
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.MetrolistClock
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class DiscordUser(
    val id: String,
    val username: String,
    val name: String,
    val avatar: String?,
)

data class DiscordErrorInfo(
    val type: String,
    val message: String,
)

object DiscordRpcManager {
    private const val TAG = "DiscordSvc"
    private const val DISCORD_APP_ID = "1447278780795064401"

    @Volatile private var initialized: Boolean = false

    @Volatile private var shuttingDown: Boolean = false

    @Volatile private var ready: Boolean = false

    @Volatile private var authorized: Boolean = false

    @Volatile private var accessToken: String? = null

    @Volatile private var lastActivity: ActivityPayload? = null

    @Volatile private var pendingPresence: PresenceAttempt? = null

    @Volatile private var currentSongId: String? = null

    @Volatile private var currentIsPlaying: Boolean = false

    @Volatile private var imageResolutionJob: Job? = null

    @Volatile private var currentActivityHadImages: Boolean = false

    @Volatile private var needsPresenceResend: Boolean = false

    @Volatile private var pendingActivity: PendingActivity? = null

    @Volatile private var presenceGeneration = 0L
    private val playbackActive = MutableStateFlow(false)

    @Volatile private var gatewayRequested = false
    private var lifecycleJob: Job? = null
    private val reconnectMutex = Mutex()
    private val accountGeneration = DiscordConnectionGeneration()
    private var profileJob: Job? = null

    @Volatile private var refreshRetryJob: Job? = null
    private val refreshBackoff = DiscordReconnectBackoff()

    private val _accessTokenFlow = MutableStateFlow<String?>(null)
    val accessTokenFlow: StateFlow<String?> = _accessTokenFlow

    private val _connectionStatus = MutableStateFlow(Status.Disconnected)
    val connectionStatus: StateFlow<Status> = _connectionStatus

    private val _lastError = MutableStateFlow<DiscordErrorInfo?>(null)
    val lastError: StateFlow<DiscordErrorInfo?> = _lastError

    private val _currentUser = MutableStateFlow<DiscordUser?>(null)
    val currentUser: StateFlow<DiscordUser?> = _currentUser

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var httpClient: HttpClient? = null
    private var gateway: DiscordGateway? = null
    private var presenceSender: DiscordPresenceSender? = null

    val auth: DiscordAuth by lazy { DiscordAuth(httpClient!!) }

    enum class Status { Disconnected, Authorizing, Connected }

    enum class RefreshResult { Queued, RateLimited, NotReady, Error }

    private data class PendingActivity(
        val activity: DiscordActivity,
        val songId: String?,
        val isPlaying: Boolean,
        val status: PresenceStatus,
    )

    private data class PresenceAttempt(
        val generation: Long,
        val activity: ActivityPayload?,
    )

    private val _settingsRevision = MutableStateFlow(0L)
    val settingsRevision = _settingsRevision.asStateFlow()

    fun notifySettingsChanged() {
        _settingsRevision.update { it + 1 }
        currentSongId = null
        currentIsPlaying = false
    }

    fun init(httpClient: HttpClient) = init(httpClient, Dispatchers.Default)

    internal fun init(
        httpClient: HttpClient,
        dispatcher: CoroutineDispatcher,
    ) {
        if (initialized && scope.isActive) return
        scope = CoroutineScope(SupervisorJob() + dispatcher)

        this.httpClient = httpClient
        this.gateway =
            DiscordGateway(
                DISCORD_APP_ID,
                { "Bearer ${accessToken ?: ""}" },
                scope,
                httpClient,
                connectionAllowed = ::connectionAllowed,
            )

        presenceSender = DiscordPresenceSender(scope, send = { gateway?.sendAndAwait(it) == true })
        refreshBackoff.reset()
        shuttingDown = false
        initialized = true
        _connectionStatus.value = Status.Disconnected
        startEventCollection()

        val settings = Settings.shared
        val restoringGeneration = accountGeneration.current()
        lifecycleJob =
            scope.launch {
                if (accountGeneration.isCurrent(restoringGeneration)) {
                    val saved = DiscordTokenStore.retrieve()
                    if (!saved.isNullOrEmpty() && accountGeneration.isCurrent(restoringGeneration)) reconnectWithToken(saved)
                }
                discordGatewayConnectionAllowed(
                    enabled =
                        snapshotFlow { settings.enableDiscordIntegration }.onEach { enabled ->
                            if (!enabled) {
                                clear()
                                refreshBackoff.reset()
                            }
                        },
                    active = combine(playbackActive, snapshotFlow { discordSettingsVisible }) { playing, visible -> playing || visible },
                    online = PlaybackNetworkStateStore.state.map { it.connected },
                ).collect { allowed ->
                    gatewayRequested = allowed
                    if (allowed) {
                        reconnect()
                    } else {
                        suspendGateway()
                    }
                }
            }
    }

    private fun connectionAllowed(): Boolean =
        gatewayRequested && Settings.shared.enableDiscordIntegration && !shuttingDown &&
            accessToken != null && PlaybackNetworkStateStore.current.connected

    private suspend fun suspendGateway() {
        refreshRetryJob?.cancel()
        refreshRetryJob = null
        val finalFrame =
            if (ready) DiscordPresence.buildPresenceUpdate(PresenceStatus.Online, activities = emptyList()) else null
        presenceSender?.reset()
        imageResolutionJob?.cancel()
        imageResolutionJob = null
        presenceGeneration++
        pendingPresence = null
        lastActivity = null
        needsPresenceResend = pendingActivity != null
        ready = false
        authorized = false
        flushDiscordPresenceBeforeClose(finalFrame != null) { clearPresence ->
            gateway?.suspendConnection(finalFrameJson = finalFrame.takeIf { clearPresence })
        }
        _connectionStatus.value = Status.Disconnected
    }

    private fun startEventCollection() {
        scope.launch {
            gateway?.events?.collect { event -> handleGatewayEvent(event) }
        }
    }

    fun setError(
        type: String,
        message: String? = null,
    ) {
        _lastError.value = DiscordErrorInfo(type, message ?: type)
    }

    fun clearLastError() {
        _lastError.value = null
    }

    fun storeAndConnect(
        accessToken: String,
        refreshToken: String,
        expiresInSec: Long,
    ) {
        val generation = accountGeneration.next()
        profileJob?.cancel()
        _currentUser.value = null
        DiscordTokenStore.storeFull(accessToken, refreshToken, expiresInSec)
        this.accessToken = accessToken
        _accessTokenFlow.value = accessToken
        refreshRetryJob?.cancel()
        refreshRetryJob = null

        scope.launch {
            reconnectMutex.withLock {
                refreshBackoff.reset()
                performConnection(accessToken, generation)
            }
        }
    }

    private suspend fun performConnection(
        token: String,
        generation: Long,
    ) {
        fun current() =
            accountGeneration.isCurrent(generation) && accessToken == token &&
                connectionAllowed()
        if (!current()) return
        _connectionStatus.value = Status.Authorizing
        presenceSender?.setReady(false)
        ready = false
        authorized = false
        try {
            gateway?.close(4000, "re-authorizing")
            if (gateway?.connect() != true) return
            if (!current()) return
            gateway?.identify("Bearer $token")
            authorized = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!current()) return
            Logger.e(TAG, "Failed to connect", e)
            _connectionStatus.value = Status.Disconnected
            ready = false
            setError("discord_error_connection_failed", e.message)
        }
    }

    suspend fun fetchCurrentUser(token: String): DiscordUser? {
        val client = httpClient ?: return null
        return try {
            val response =
                client.get("https://discord.com/api/v10/users/@me") {
                    header("Authorization", "Bearer $token")
                }
            if (response.status.value in 200..299) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val id = json["id"]?.jsonPrimitive?.content ?: ""
                val username = json["username"]?.jsonPrimitive?.content ?: ""
                val name = json["global_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: username
                val avatarHash = json["avatar"]?.jsonPrimitive?.contentOrNull
                val avatar = if (!avatarHash.isNullOrEmpty()) "https://cdn.discordapp.com/avatars/$id/$avatarHash.png" else null
                DiscordUser(id, username, name, avatar)
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!e.hasHttpTransportFailureInCauseChain()) {
                PlaybackEventLog.error(PlaybackEventKind.SYNC, TAG, "Fetching current Discord user failed", e)
            }
            Logger.e(TAG, "fetchCurrentUser failed", e)
            null
        }
    }

    fun setActivity(
        activity: DiscordActivity,
        songId: String? = null,
        isPlaying: Boolean = true,
        status: PresenceStatus = PresenceStatus.Online,
        force: Boolean = false,
    ): Boolean {
        if (shuttingDown || !Settings.shared.enableDiscordIntegration) return false
        if (!isPlaying) return clear()
        val samePending =
            pendingActivity?.let { it.songId == songId && it.status == status && sameDiscordActivity(it.activity, activity) } == true
        pendingActivity = PendingActivity(activity, songId, isPlaying, status)
        playbackActive.value = true
        if (!ready) return false

        currentActivityHadImages = !activity.largeImage.isNullOrEmpty() || !activity.smallImage.isNullOrEmpty()

        val buttons =
            buildList {
                if (!activity.button1Label.isNullOrEmpty() && !activity.button1Url.isNullOrEmpty()) {
                    add(activity.button1Label to activity.button1Url)
                }
                if (!activity.button2Label.isNullOrEmpty() && !activity.button2Url.isNullOrEmpty()) {
                    add(activity.button2Label to activity.button2Url)
                }
            }

        if (!samePending) imageResolutionJob?.cancel()
        val activityUrl = songId?.let { "${DiscordDefaults.YOUTUBE_WATCH_URL}$it" }

        val largeImageUrl = activity.largeImage
        val smallImageUrl = activity.smallImage
        val largeCached = largeImageUrl?.let { DiscordExternalAssets.peekCache(it) }
        val smallCached = smallImageUrl?.let { DiscordExternalAssets.peekCache(it) }
        val allCached = (largeImageUrl == null || largeCached != null) && (smallImageUrl == null || smallCached != null)

        if (allCached && (largeImageUrl != null || smallImageUrl != null)) {
            return sendPresence(activity, status, activityUrl, largeCached, smallCached, buttons, force)
        }

        val queued = sendPresence(activity, status, activityUrl, largeCached, smallCached, buttons, force)

        if (!currentActivityHadImages || accessToken == null || (samePending && imageResolutionJob?.isActive == true)) return queued

        imageResolutionJob =
            scope.launch {
                val client = httpClient ?: return@launch
                val token = accessToken ?: return@launch
                val resolved =
                    DiscordExternalAssets.resolveAll(
                        listOfNotNull(largeImageUrl, smallImageUrl),
                        DISCORD_APP_ID,
                        "Bearer $token",
                        client,
                    )

                val newLarge = largeImageUrl?.let { resolved[it] } ?: largeCached
                val newSmall = smallImageUrl?.let { resolved[it] } ?: smallCached

                if (newLarge != null || newSmall != null) {
                    val latest = pendingActivity ?: return@launch
                    if (latest.songId != songId || latest.status != status || !sameDiscordActivity(latest.activity, activity)) return@launch
                    sendPresence(latest.activity, status, activityUrl, newLarge, newSmall, buttons)
                }
            }
        return queued
    }

    private fun sendPresence(
        activity: DiscordActivity,
        status: PresenceStatus,
        url: String?,
        largeImage: String?,
        smallImage: String?,
        buttons: List<Pair<String, String>>,
        force: Boolean = false,
    ): Boolean {
        if (shuttingDown || pendingActivity == null) return false
        val sender = presenceSender ?: return false
        val payload =
            DiscordPresence.buildActivity(
                name = activity.name ?: "",
                type = activityTypeFromInt(activity.activityType),
                applicationId = DISCORD_APP_ID,
                url = url,
                details = activity.details,
                state = activity.state,
                largeImage = largeImage,
                largeText = activity.largeText,
                smallImage = smallImage,
                smallText = activity.smallText,
                startMs = activity.startTimestamp.takeIf { it > 0 },
                endMs = activity.endTimestamp?.takeIf { it > 0 },
                buttons = buttons,
            )
        val pendingClear = pendingPresence?.activity == null && pendingPresence != null
        if (!pendingClear &&
            !shouldSendDiscordPresence(lastActivity, pendingPresence?.activity, payload, needsPresenceResend || force)
        ) {
            return false
        }
        val generation = ++presenceGeneration
        val attempt = PresenceAttempt(generation, payload)
        pendingPresence = attempt
        val json = DiscordPresence.buildPresenceUpdate(status, activities = listOf(payload))
        Logger.i(TAG, "presence attempt generation=$generation images=${largeImage != null || smallImage != null}")
        sender.submit(
            json,
            onSuccess = success@{
                if (pendingPresence != attempt || generation != presenceGeneration || pendingActivity == null) return@success
                lastActivity = payload
                currentSongId = pendingActivity?.songId
                currentIsPlaying = pendingActivity?.isPlaying == true
                needsPresenceResend = false
                pendingPresence = null
                Logger.i(TAG, "presence submitted generation=$generation")
            },
            onFailure = failure@{
                if (pendingPresence != attempt || generation != presenceGeneration) return@failure
                needsPresenceResend = true
                Logger.w(TAG, "presence failed generation=$generation")
            },
        )
        return true
    }

    private fun activityTypeFromInt(type: Int): ActivityType =
        when (type) {
            0 -> ActivityType.Playing
            1 -> ActivityType.Streaming
            2 -> ActivityType.Listening
            3 -> ActivityType.Watching
            5 -> ActivityType.Competing
            else -> ActivityType.Listening
        }

    fun clear(): Boolean {
        if (shuttingDown) return false
        pendingActivity = null
        playbackActive.value = false
        imageResolutionJob?.cancel()
        imageResolutionJob = null
        if (pendingPresence?.activity == null && pendingPresence != null) return true
        presenceGeneration++
        val priorAttempt = pendingPresence
        if (lastActivity == null && priorAttempt?.activity == null) return false
        val sender = presenceSender ?: return false
        val generation = presenceGeneration
        val attempt = PresenceAttempt(generation, null)
        pendingPresence = attempt
        val json = DiscordPresence.buildPresenceUpdate(PresenceStatus.Online, activities = emptyList())
        Logger.i(TAG, "presence clear attempt")
        sender.submit(
            json,
            onSuccess = success@{
                if (pendingPresence != attempt || generation != presenceGeneration || pendingActivity != null) return@success
                currentSongId = null
                currentIsPlaying = false
                lastActivity = null
                needsPresenceResend = false
                pendingPresence = null
                Logger.i(TAG, "presence clear submitted")
            },
            onFailure = failure@{
                if (pendingPresence != attempt || generation != presenceGeneration) return@failure
                needsPresenceResend = true
                Logger.w(TAG, "presence clear failed")
            },
        )
        return true
    }

    fun reconnectWithToken(token: String) {
        if (!initialized || shuttingDown) return
        if (accessToken != token) {
            accountGeneration.next()
            profileJob?.cancel()
            _currentUser.value = null
        }
        accessToken = token
        _accessTokenFlow.value = token
        reconnect()
    }

    private fun reconnect(rejectedToken: String? = null) {
        if (!connectionAllowed()) return
        val generation = accountGeneration.current()
        scope.launch {
            reconnectMutex.withLock {
                if (!connectionAllowed() || !accountGeneration.isCurrent(generation)) return@withLock
                val token = accessToken ?: return@withLock
                if (rejectedToken != null && rejectedToken != token) return@withLock
                refreshRetryJob?.cancel()
                refreshRetryJob = null
                val expiresAt = DiscordTokenStore.getExpiresAt()
                val nowSec = MetrolistClock.nowMs() / 1000
                if (rejectedToken != null || (expiresAt > 0 && (expiresAt - nowSec) < 3600)) {
                    try {
                        val refreshToken = DiscordTokenStore.getRefreshToken()
                        if (refreshToken != null) {
                            val auth = DiscordAuth(httpClient!!)
                            val res = auth.refresh(refreshToken)
                            if (accessToken != token || shuttingDown || !accountGeneration.isCurrent(generation)) return@withLock
                            DiscordTokenStore.storeFull(res.accessToken, res.refreshToken, res.expiresInSec)
                            accessToken = res.accessToken
                            _accessTokenFlow.value = res.accessToken
                        } else if (rejectedToken != null) {
                            setError("discord_error_token_refresh_failed")
                            return@withLock
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DiscordAuthException) {
                        Logger.w(TAG, "Token refresh failed", e)
                        _connectionStatus.value = Status.Disconnected
                        setError("discord_error_token_refresh_failed", e.message)
                        if (e is DiscordAuthException.NetworkFailure) scheduleRefreshRetry(token, rejectedToken)
                        return@withLock
                    }
                }

                refreshBackoff.reset()
                gateway?.resetForFreshIdentify()
                performConnection(accessToken ?: return@withLock, generation)
            }
        }
    }

    private fun scheduleRefreshRetry(
        token: String,
        rejectedToken: String?,
    ) {
        if (!connectionAllowed() || accessToken != token) return
        refreshRetryJob?.cancel()
        val retryDelayMs = refreshBackoff.nextDelayMs(rateLimited = false)
        refreshRetryJob =
            scope.launch {
                delay(retryDelayMs.milliseconds)
                if (accessToken == token) reconnect(rejectedToken)
            }
    }

    fun refreshPresence(
        activity: DiscordActivity,
        songId: String? = null,
        isPlaying: Boolean = true,
    ): RefreshResult {
        if (!ready || shuttingDown || !Settings.shared.enableDiscordIntegration) return RefreshResult.NotReady
        if (presenceSender == null) return RefreshResult.Error
        return if (setActivity(activity, songId, isPlaying, force = true)) RefreshResult.Queued else RefreshResult.NotReady
    }

    private fun handleGatewayEvent(event: GatewayEvent) {
        if (!connectionAllowed()) return
        when (event) {
            is GatewayEvent.Ready, is GatewayEvent.Resumed -> {
                gateway?.markReady()
                ready = true
                authorized = true
                _connectionStatus.value = Status.Connected
                clearLastError()
                needsPresenceResend = true
                pendingActivity?.let { setActivity(it.activity, it.songId, it.isPlaying, it.status) }
                presenceSender?.setReady(true)
                val token = accessToken ?: return
                val generation = accountGeneration.current()
                profileJob?.cancel()
                profileJob =
                    scope.launch {
                        val user = fetchCurrentUser(token)
                        if (accountGeneration.isCurrent(generation) && accessToken == token) _currentUser.value = user
                    }
            }

            is GatewayEvent.Disconnected -> {
                Logger.w(TAG, "Gateway disconnected (code=${event.code})")
                ready = false
                presenceSender?.setReady(false)
                needsPresenceResend = pendingActivity != null
                _connectionStatus.value = Status.Disconnected
            }

            is GatewayEvent.Fatal -> {
                setError("discord_error_gateway_configuration")
            }

            is GatewayEvent.RefreshToken -> {
                accessToken?.let { reconnect(rejectedToken = it) }
            }
        }
    }

    fun disconnect() {
        ready = false
        authorized = false
        needsPresenceResend = pendingActivity != null
        _connectionStatus.value = Status.Disconnected
        presenceSender?.setReady(false)
        gateway?.close()
    }

    suspend fun destroy() {
        if (!initialized || shuttingDown) return
        val shouldClear = ready
        shuttingDown = true
        gatewayRequested = false
        lifecycleJob?.cancelAndJoin()
        lifecycleJob = null
        accountGeneration.next()
        profileJob?.cancel()
        profileJob = null
        refreshRetryJob?.cancel()
        refreshRetryJob = null
        ready = false
        authorized = false
        val activeGateway = gateway
        presenceSender?.stop()
        presenceSender = null
        presenceGeneration++
        pendingActivity = null
        imageResolutionJob?.cancel()
        imageResolutionJob = null
        try {
            flushDiscordPresenceBeforeClose(shouldClear) { clearPresence ->
                val finalFrame =
                    if (clearPresence) {
                        DiscordPresence.buildPresenceUpdate(PresenceStatus.Online, activities = emptyList())
                    } else {
                        null
                    }
                activeGateway?.closeAndAwait(finalFrameJson = finalFrame)
            }
        } finally {
            initialized = false
            currentSongId = null
            currentIsPlaying = false
            playbackActive.value = false
            pendingPresence = null
            lastActivity = null
            needsPresenceResend = false
            gateway = null
            _connectionStatus.value = Status.Disconnected
            scope.cancel()
        }
    }

    suspend fun reloadAfterRestore(httpClient: HttpClient) {
        destroy()
        accessToken = null
        _accessTokenFlow.value = null
        _currentUser.value = null
        _lastError.value = null
        init(httpClient)
    }

    fun logout() {
        accountGeneration.next()
        playbackActive.value = false
        accessToken = null
        profileJob?.cancel()
        profileJob = null
        refreshRetryJob?.cancel()
        refreshRetryJob = null
        presenceSender?.reset()
        imageResolutionJob?.cancel()
        imageResolutionJob = null
        presenceGeneration++
        gateway?.close()
        _accessTokenFlow.value = null
        _currentUser.value = null
        DiscordTokenStore.clear()
        Settings.shared.discordUsername = ""
        Settings.shared.discordName = ""
        Settings.shared.discordAvatar = ""
        _connectionStatus.value = Status.Disconnected
        ready = false
        authorized = false
        pendingActivity = null
        pendingPresence = null
        lastActivity = null
    }
}

internal suspend fun flushDiscordPresenceBeforeClose(
    shouldClear: Boolean,
    timeoutMs: Long = 750L,
    shutdownGateway: suspend (clearPresence: Boolean) -> Unit,
) {
    require(timeoutMs > 0L)
    withTimeoutOrNull(timeoutMs.milliseconds) { shutdownGateway(shouldClear) }
}

internal fun shouldSendDiscordPresence(
    lastActivity: ActivityPayload?,
    pendingActivity: ActivityPayload?,
    nextActivity: ActivityPayload,
    needsResend: Boolean,
): Boolean =
    needsResend ||
        !sameDiscordPresence(pendingActivity ?: lastActivity, nextActivity)

private fun sameDiscordActivity(
    first: DiscordActivity?,
    second: DiscordActivity,
): Boolean =
    first != null &&
        first.copy(startTimestamp = 0L, endTimestamp = null) == second.copy(startTimestamp = 0L, endTimestamp = null)

private fun sameDiscordPresence(
    first: ActivityPayload?,
    second: ActivityPayload,
): Boolean =
    first != null &&
        first.copy(startMs = null, endMs = null) == second.copy(startMs = null, endMs = null) &&
        timestampsClose(first.startMs, second.startMs) && timestampsClose(first.endMs, second.endMs)

private fun timestampsClose(
    first: Long?,
    second: Long?,
): Boolean = first == second || (first != null && second != null && kotlin.math.abs(first - second) < PRESENCE_TIMESTAMP_TOLERANCE_MS)

private const val PRESENCE_TIMESTAMP_TOLERANCE_MS = 250L
