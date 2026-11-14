package com.metrolist.shared.deviceconnect

import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class DeviceConnectRemote(
    parentScope: CoroutineScope,
    private val localDeviceId: () -> String,
    private val send: suspend (String, DcSyncMessage) -> Boolean,
    private val transferTimeoutMs: Long = DEVICE_CONNECT_TRANSFER_TIMEOUT_MS,
    private val monotonicClockMs: () -> Long =
        run {
            val origin = TimeSource.Monotonic.markNow()
            val readClock: () -> Long = { origin.elapsedNow().inWholeMilliseconds + 1L }
            readClock
        },
    private val defaultDeviceId: () -> String? = { null },
    private val useLegacyShuffle: () -> Boolean = { false },
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val events = Channel<suspend () -> Unit>(128)
    private val _state = MutableStateFlow(DcSyncState())
    val state: StateFlow<DcSyncState> = _state.asStateFlow()
    val isActive: Boolean get() = state.value.sessionId != null

    val willStartRemotePlayback: Boolean get() = isActive || availableDefaultDevice() != null

    private fun availableDefaultDevice(): String? =
        defaultDeviceId()?.takeIf {
            !transferBlocked() && port?.canStartDefaultPlayback == true && it in peers && it != localDeviceId()
        }

    val isFollowing: Boolean get() = isActive && state.value.targetId != localDeviceId()
    val queueRevision: Long? get() = canonical?.queueRevision
    val trackRevision: Long? get() = canonical?.trackRevision

    @Volatile var transferBlocked: () -> Boolean = { false }

    @Volatile private var peers = emptySet<String>()
    private val clockPeers = mutableSetOf<String>()
    private val pitchPeers = mutableSetOf<String>()
    private val wireQueueCache = DcWireQueueCache()
    private var lastClockBroadcastMs = 0L
    private val controllers = mutableSetOf<String>()

    @Volatile private var port: DeviceConnectPlaybackPort? = null

    @Volatile private var canonical: DcPlaybackSnapshot? = null
    private var controllerAnchorMs = 0L
    private var awaitingTarget: String? = null
    private var awaitingPull = false
    private var pendingJob: Job? = null
    private var applyJob: Job? = null
    private var tickJob: Job? = null
    private var extendJob: Job? = null
    private var lastEndedTrack = -1L

    @Volatile private var applyGeneration = 0L

    @Volatile private var applying = false

    @Volatile private var startingDefaultPlayback = false

    @Volatile private var defaultPlaybackGeneration = 0L

    init {
        scope.launch {
            for (event in events) {
                try {
                    event()
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (error !is CancellationException) {
                        PlaybackEventLog.error(
                            PlaybackEventKind.SYNC,
                            "DeviceConnect",
                            "Playback event handling failed",
                            error,
                        )
                    }
                    _state.value = _state.value.copy(error = DcSyncError.UNAVAILABLE)
                }
                if (canonical?.playback?.playing == true) {
                    startTicker()
                } else {
                    tickJob?.cancel()
                    tickJob = null
                }
            }
        }
    }

    private fun enqueue(event: suspend () -> Unit): Boolean =
        events.trySend(event).isSuccess.also { accepted ->
            if (!accepted) _state.value = _state.value.copy(error = DcSyncError.UNAVAILABLE)
        }

    fun reportError(error: DcSyncError) {
        enqueue { _state.value = _state.value.copy(error = error) }
    }

    fun cancelPendingDefaultPlayback() {
        defaultPlaybackGeneration++
        startingDefaultPlayback = false
    }

    fun attach(playback: DeviceConnectPlaybackPort) {
        enqueue {
            if (port === playback) return@enqueue
            cancelApply()
            port = playback
            playback.setExternalReportingEnabled(enabled = !isFollowing, session = canonical?.playback?.scrobbleSession)
            _state.value = _state.value.copy(playbackAvailable = true)
            canonical?.let { applyCanonical(it) }
        }
    }

    fun detach(playback: DeviceConnectPlaybackPort) {
        if (port !== playback) return
        applyGeneration++
        port = null
        enqueue {
            if (port != null) return@enqueue
            cancelApply()
            _state.value = _state.value.copy(playbackAvailable = false)
        }
    }

    fun intercept(
        command: DcPlaybackCommand,
        expectedQueueRevision: Long? = null,
        expectedSessionId: String? = null,
        expectedQueue: MusicQueueSnapshot? = null,
    ): Boolean {
        val expectedSession = state.value.sessionId
        if (expectedSessionId != null && expectedSessionId != expectedSession) return true
        if (expectedSession == null && !startingDefaultPlayback &&
            (
                command.action !in setOf(DcPlaybackAction.PLAY, DcPlaybackAction.SELECT, DcPlaybackAction.REPLACE_QUEUE) ||
                    availableDefaultDevice() == null ||
                    command.songs.any { it.isLocal || it.id.startsWith("local_") } ||
                    (
                        command.action != DcPlaybackAction.REPLACE_QUEUE && expectedQueue != null &&
                            !DcLocalPlaybackState(queue = expectedQueue).isShareableDeviceConnectState()
                    )
            )
        ) {
            return false
        }
        val revision = expectedQueueRevision ?: canonical?.queueRevision ?: 0L
        val defaultPlaybackGeneration = defaultPlaybackGeneration
        if (expectedSession == null) startingDefaultPlayback = true
        val accepted =
            enqueue {
                if (defaultPlaybackGeneration != this@DeviceConnectRemote.defaultPlaybackGeneration) return@enqueue
                if (expectedSession != null && expectedSession != state.value.sessionId) return@enqueue
                if (!isActive) {
                    try {
                        startDefaultPlayback(command, expectedQueue)
                    } finally {
                        startingDefaultPlayback = false
                    }
                    return@enqueue
                }
                startingDefaultPlayback = false
                if (command.isQueueIndexed()) {
                    val displayed = (expectedQueue ?: port?.snapshot()?.queue)?.forDeviceConnect()
                    val current = canonical?.playback?.queue
                    if (displayed == null || current == null || !sameDcQueue(displayed, current)) {
                        _state.value = _state.value.copy(error = DcSyncError.STALE_QUEUE)
                        if (isFollowing) state.value.targetId?.let { requestState(it) }
                        return@enqueue
                    }
                }
                val commandRevision = if (expectedSession == null) canonical?.queueRevision ?: 0L else revision
                if (isFollowing) {
                    val target = state.value.targetId ?: return@enqueue
                    if (command.action == DcPlaybackAction.PITCH && target !in pitchPeers) {
                        _state.value = _state.value.copy(error = DcSyncError.NOT_ALLOWED)
                        return@enqueue
                    }
                    sendChecked(
                        target,
                        DcSyncMessage(
                            DcSyncMessageType.COMMAND,
                            sessionId = state.value.sessionId,
                            command = command,
                            queueRevision = commandRevision,
                        ),
                    )
                } else {
                    handleCommand(localDeviceId(), command, commandRevision)
                }
            }
        if (!accepted) startingDefaultPlayback = false
        return true
    }

    fun transferTo(deviceId: String) {
        enqueue {
            if (deviceId == localDeviceId()) pullBack() else startTransfer(deviceId)
        }
    }

    fun discoverPlayback() {
        enqueue {
            if (isActive || peers.isEmpty() || transferBlocked()) return@enqueue
            if (port?.snapshot()?.playing != false) return@enqueue
            peers.toList().forEach { send(it, DcSyncMessage(DcSyncMessageType.ATTACH, auto = true)) }
        }
    }

    fun localStateChanged(trackChanged: Boolean = false) {
        if (applying) return
        val generation = applyGeneration
        enqueue {
            if (generation == applyGeneration && isActive && !isFollowing && !applying) observeLocal(trackChanged)
        }
    }

    fun playbackEnded() {
        val ended = canonical ?: return
        if (isFollowing || applyJob?.isActive == true) return
        enqueue {
            val current = canonical ?: return@enqueue
            if (current.trackRevision != ended.trackRevision || current.sessionId != ended.sessionId ||
                lastEndedTrack == ended.trackRevision
            ) {
                return@enqueue
            }
            lastEndedTrack = ended.trackRevision
            if (!isFollowing) advanceNaturally()
        }
    }

    fun onPeerConnected(
        peerId: String,
        supportsPlaybackClock: Boolean = false,
        supportsPlaybackPitch: Boolean = false,
    ) {
        enqueue {
            peers += peerId
            if (supportsPlaybackClock) clockPeers += peerId else clockPeers -= peerId
            if (supportsPlaybackPitch) pitchPeers += peerId else pitchPeers -= peerId
        }
    }

    fun onPeerDisconnected(
        peerId: String,
        intentional: Boolean,
    ) {
        enqueue {
            peers -= peerId
            clockPeers -= peerId
            pitchPeers -= peerId
            if (isFollowing && peerId == state.value.targetId) {
                endSession(pause = true, error = DcSyncError.UNAVAILABLE.takeUnless { intentional })
            } else if (controllers.remove(peerId)) {
                if (controllers.isEmpty()) endSession(pause = false) else publishState()
            }
        }
    }

    suspend fun stopAndAwait(pause: Boolean = true) {
        val stopped = CompletableDeferred<Unit>()
        events.send {
            try {
                notifyPeersOfStop()
                endSession(pause)
                peers = emptySet()
                clockPeers.clear()
                pitchPeers.clear()
            } finally {
                stopped.complete(Unit)
            }
        }
        stopped.await()
    }

    fun close() {
        applyGeneration++
        scope.cancel()
        events.close()
    }

    fun onMessage(
        peerId: String,
        message: DcSyncMessage,
    ) {
        enqueue {
            if (peerId !in peers) return@enqueue
            when (message.type) {
                DcSyncMessageType.ATTACH -> {
                    handleAttach(peerId, message.auto)
                }

                DcSyncMessageType.TRANSFER -> {
                    handleTransfer(peerId, message)
                }

                DcSyncMessageType.PULL -> {
                    handlePull(peerId, message)
                }

                DcSyncMessageType.STATE -> {
                    receiveState(peerId, message)
                }

                DcSyncMessageType.UPDATE -> {
                    receiveUpdate(peerId, message)
                }

                DcSyncMessageType.COMMAND -> {
                    if (!isFollowing && peerId in controllers && message.sessionId == state.value.sessionId) {
                        message.command?.let { handleCommand(peerId, it, message.queueRevision) }
                    }
                }

                DcSyncMessageType.RELEASE -> {
                    handleRelease(peerId, message)
                }

                DcSyncMessageType.ERROR -> {
                    handleError(peerId, message)
                }
            }
        }
    }

    private suspend fun startDefaultPlayback(
        command: DcPlaybackCommand,
        expectedQueue: MusicQueueSnapshot?,
    ) {
        val playback = port ?: return fail(DcSyncError.UNAVAILABLE)
        val local = playback.snapshot()
        if (command.isQueueIndexed() && expectedQueue != null && !sameDcQueue(expectedQueue, local.queue)) {
            return fail(DcSyncError.STALE_QUEUE)
        }
        val next = reduceDcPlayback(local, command, useLegacyShuffle())?.playback ?: return fail(DcSyncError.INVALID_MESSAGE)
        if (!validDcPlayback(next)) return fail(DcSyncError.INVALID_MESSAGE)
        val target = availableDefaultDevice()
        val snapshot = DcPlaybackSnapshot(newId(), 1L, 1L, 1L, next, monotonicClockMs(), newId())
        if (target != null && next.isShareableDeviceConnectState() &&
            fitsTransport(DcSyncMessageType.TRANSFER, snapshot.copy(playback = next.forWire()))
        ) {
            startTransfer(target, next)
        } else {
            playback.apply(snapshot, next.positionMs) { port === playback && !isActive }
        }
    }

    private suspend fun startTransfer(
        deviceId: String,
        requestedPlayback: DcLocalPlaybackState? = null,
    ) {
        val playback = port ?: return fail(DcSyncError.UNAVAILABLE)
        if (deviceId !in peers) return fail(DcSyncError.UNAVAILABLE)
        if (transferBlocked()) return fail(DcSyncError.NOT_ALLOWED)
        val previousTarget = state.value.targetId?.takeIf { isFollowing && it != deviceId }
        val local = (requestedPlayback ?: playback.snapshot()).forWire()
        val session = newId()
        if (local.queue.displayedItems.isEmpty()) {
            previousTarget?.let {
                send(
                    it,
                    DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = state.value.sessionId, pause = true),
                )
            }
            beginControlling(deviceId, session, null)
            if (!send(deviceId, DcSyncMessage(DcSyncMessageType.ATTACH))) abortControlling(DcSyncError.UNAVAILABLE)
            return
        }
        if (!validDcPlayback(local)) return fail(DcSyncError.INVALID_MESSAGE)
        if (!local.isShareableDeviceConnectState()) return fail(DcSyncError.UNSHAREABLE_QUEUE)
        val snapshot =
            DcPlaybackSnapshot(
                sessionId = session,
                revision = 1L,
                queueRevision = 1L,
                trackRevision = 1L,
                playback = local,
                anchorTimeMs = monotonicClockMs(),
                queueStateId = newId(),
            )
        if (!fitsTransport(DcSyncMessageType.TRANSFER, snapshot)) return fail(DcSyncError.TOO_LARGE)
        previousTarget?.let { send(it, DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = state.value.sessionId, pause = true)) }
        beginControlling(deviceId, session, snapshot)
        if (!send(deviceId, DcSyncMessage(DcSyncMessageType.TRANSFER, sessionId = session, snapshot = snapshot))) {
            abortControlling(DcSyncError.UNAVAILABLE)
        }
    }

    private suspend fun pullBack() {
        if (!isFollowing) return
        val target = state.value.targetId ?: return
        if (transferBlocked()) return fail(DcSyncError.NOT_ALLOWED)
        if (!send(target, DcSyncMessage(DcSyncMessageType.PULL, sessionId = state.value.sessionId))) {
            fail(DcSyncError.UNAVAILABLE)
            return
        }
        startPendingTimeout(target, pullingBack = true)
    }

    private suspend fun beginControlling(
        deviceId: String,
        sessionId: String,
        snapshot: DcPlaybackSnapshot?,
    ) {
        if (!isFollowing) notifyPeersOfStop()
        cancelApply()
        tickJob?.cancel()
        tickJob = null
        controllers.clear()
        canonical = snapshot
        controllerAnchorMs = monotonicClockMs()
        _state.value =
            DcSyncState(sessionId = sessionId, targetId = deviceId, playbackAvailable = port != null)
        startingDefaultPlayback = false
        port?.setExternalReportingEnabled(enabled = false, session = null)
        port?.setAudioOutputEnabled(false)
        port?.setControllerPlayback((snapshot?.playback ?: DcLocalPlaybackState()).copy(positionMs = snapshot?.playback?.positionMs ?: 0L))
        startPendingTimeout(deviceId)
    }

    private fun startPendingTimeout(
        deviceId: String,
        pullingBack: Boolean = false,
    ) {
        awaitingTarget = deviceId
        awaitingPull = pullingBack
        pendingJob?.cancel()
        pendingJob =
            scope.launch {
                delay(transferTimeoutMs.milliseconds)
                enqueue {
                    if (awaitingTarget == deviceId) abortControlling(DcSyncError.UNAVAILABLE)
                }
            }
    }

    private fun clearPending() {
        awaitingTarget = null
        awaitingPull = false
        pendingJob?.cancel()
        pendingJob = null
    }

    private suspend fun abortControlling(error: DcSyncError) {
        if (awaitingPull) {
            clearPending()
            fail(error)
            return
        }
        val resume = canonical
        endSession(pause = true, error = error)
        val playback = port
        if (resume != null && playback != null) playback.apply(resume, resume.playback.positionMs) { port === playback && !isActive }
    }

    private suspend fun receiveState(
        peerId: String,
        message: DcSyncMessage,
    ) {
        val snapshot = message.snapshot ?: return
        if (snapshot.sessionId.isBlank() || !validDcPlayback(snapshot.playback) || !snapshot.playback.isShareableDeviceConnectState()) {
            sendError(peerId, DcSyncError.INVALID_MESSAGE)
            return
        }
        if (!isFollowing) {
            if (isActive || transferBlocked() || port?.snapshot()?.playing != false) return
            if (snapshot.sessionId.isBlank()) return
            beginControlling(peerId, snapshot.sessionId, null)
        }
        if (peerId != state.value.targetId) return
        val current = canonical
        if (current != null && current.sessionId == snapshot.sessionId) {
            if (snapshot.revision < current.revision || (snapshot.revision == current.revision && applyJob?.isActive == true)) return
        }
        clearPending()
        val accepted = if (peerId in clockPeers) snapshot else snapshot.copy(playback = snapshot.playback.copy(ready = true))
        canonical = accepted
        controllerAnchorMs = monotonicClockMs()
        _state.value = _state.value.copy(sessionId = snapshot.sessionId, playbackAvailable = port != null, error = null)
        applyCanonical(accepted)
    }

    private suspend fun receiveUpdate(
        peerId: String,
        message: DcSyncMessage,
    ) {
        if (!isFollowing || peerId != state.value.targetId || message.sessionId != state.value.sessionId) return
        val current =
            canonical ?: run {
                requestState(peerId)
                return
            }
        if (message.revision < current.revision) return
        val clockCapable = peerId in clockPeers
        if (message.queueRevision != current.queueRevision ||
            (clockCapable && (message.queueStateId.isBlank() || message.queueStateId != current.queueStateId)) ||
            (!clockCapable && (message.trackRevision != current.trackRevision || message.index != current.playback.queue.currentIndex)) ||
            message.trackRevision < current.trackRevision
        ) {
            requestState(peerId)
            return
        }
        val playback =
            current.playback.copy(
                positionMs = message.positionMs,
                durationMs = message.durationMs,
                playing = message.playing,
                speed = message.speed,
                pitch = message.pitch ?: current.playback.pitch,
                volume = message.volume,
                repeatMode = message.repeatMode,
                ready = message.ready,
                scrobbleSession = if (clockCapable) message.scrobbleSession else current.playback.scrobbleSession,
                queue = current.playback.queue.copy(currentIndex = message.index),
            )
        if (!validDcPlaybackScalars(playback)) {
            sendError(peerId, DcSyncError.INVALID_MESSAGE)
            return
        }
        val updated = current.copy(revision = message.revision, trackRevision = message.trackRevision, playback = playback)
        canonical = updated
        controllerAnchorMs = monotonicClockMs()
        applyCanonical(updated)
    }

    private suspend fun requestState(peerId: String) = sendChecked(peerId, DcSyncMessage(DcSyncMessageType.ATTACH))

    private suspend fun handleAttach(
        peerId: String,
        auto: Boolean,
    ) {
        if (isFollowing) return if (auto) Unit else sendError(peerId, DcSyncError.NOT_ALLOWED)
        if (transferBlocked()) return if (auto) Unit else sendError(peerId, DcSyncError.NOT_ALLOWED)
        if (canonical == null) {
            if (auto && port?.snapshot()?.let { it.playing && it.queue.displayedItems.isNotEmpty() } != true) return
            if (!startTargetSessionFromLocal(peerId)) return
        }
        controllers += peerId
        publishState()
        sendSnapshot(peerId)
    }

    private suspend fun startTargetSessionFromLocal(requester: String): Boolean {
        val local = port?.snapshot()?.forWire()
        if (local == null || !validDcPlayback(local)) {
            sendError(requester, DcSyncError.UNAVAILABLE)
            return false
        }
        if (!local.isShareableDeviceConnectState()) {
            sendError(requester, DcSyncError.UNSHAREABLE_QUEUE)
            return false
        }
        val initial =
            DcPlaybackSnapshot(
                sessionId = newId(),
                revision = 1L,
                queueRevision = 1L,
                trackRevision = 1L,
                playback = local,
                anchorTimeMs = monotonicClockMs(),
                queueStateId = newId(),
            )
        if (!fitsTransport(DcSyncMessageType.STATE, initial)) {
            sendError(requester, DcSyncError.TOO_LARGE)
            return false
        }
        canonical = initial
        return true
    }

    private suspend fun handleTransfer(
        peerId: String,
        message: DcSyncMessage,
    ) {
        val snapshot = message.snapshot ?: return
        if (isFollowing && peerId == state.value.targetId) {
            receiveTransferBack(snapshot)
            return
        }
        if (transferBlocked()) return sendError(peerId, DcSyncError.NOT_ALLOWED)
        if (snapshot.sessionId.isBlank() || !validDcPlayback(snapshot.playback) || !snapshot.playback.isShareableDeviceConnectState()) {
            return sendError(peerId, DcSyncError.INVALID_MESSAGE)
        }
        if (isFollowing) {
            state.value.targetId?.let {
                send(
                    it,
                    DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = state.value.sessionId, pause = true),
                )
            }
            endSession(pause = false)
        }
        val previous = canonical
        val adopted =
            snapshot.copy(
                playback = snapshot.playback.copy(volume = port?.snapshot()?.volume ?: return),
                sessionId = previous?.sessionId ?: snapshot.sessionId,
                revision = (previous?.revision ?: 0L) + 1L,
                queueRevision = (previous?.queueRevision ?: 0L) + 1L,
                trackRevision = (previous?.trackRevision ?: 0L) + 1L,
                anchorTimeMs = monotonicClockMs(),
                queueStateId = newId(),
            )
        if (!fitsTransport(DcSyncMessageType.STATE, adopted)) return sendError(peerId, DcSyncError.TOO_LARGE)
        canonical = adopted
        controllers += peerId
        publishState()
        applyCanonical(adopted, seek = true)
        broadcastSnapshot()
    }

    private suspend fun receiveTransferBack(snapshot: DcPlaybackSnapshot) {
        if (!validDcPlayback(snapshot.playback) || !snapshot.playback.isShareableDeviceConnectState()) return
        clearPending()
        cancelApplyAndWait()
        tickJob?.cancel()
        tickJob = null
        val playback = port
        _state.value = DcSyncState(playbackAvailable = playback != null)
        canonical = null
        wireQueueCache.clear()
        playback?.setControllerPlayback(null)
        playback?.setExternalReportingEnabled(enabled = true, session = snapshot.playback.scrobbleSession)
        playback?.setAudioOutputEnabled(true)
        if (playback != null) playback.apply(snapshot, snapshot.playback.positionMs) { port === playback && !isActive }
    }

    private suspend fun handlePull(
        peerId: String,
        message: DcSyncMessage,
    ) {
        if (isFollowing || peerId !in controllers || message.sessionId != state.value.sessionId) return
        val current = canonical ?: return
        val handoff = current.copy(playback = current.playback.copy(positionMs = targetPosition(current)))
        if (!send(peerId, DcSyncMessage(DcSyncMessageType.TRANSFER, sessionId = current.sessionId, snapshot = handoff))) {
            fail(DcSyncError.UNAVAILABLE)
            return
        }
        controllers -= peerId
        controllers.toList().forEach { send(it, DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = current.sessionId)) }
        endSession(pause = true)
    }

    private suspend fun handleRelease(
        peerId: String,
        message: DcSyncMessage,
    ) {
        if (message.sessionId != state.value.sessionId) return
        if (isFollowing) {
            if (peerId == state.value.targetId) endSession(pause = true)
            return
        }
        if (peerId !in controllers) return
        controllers -= peerId
        if (message.pause) port?.setPlaying(false)
        if (controllers.isEmpty()) endSession(pause = false) else publishState()
    }

    private suspend fun handleError(
        peerId: String,
        message: DcSyncMessage,
    ) {
        val error = message.error ?: DcSyncError.UNAVAILABLE
        when {
            isFollowing && peerId == state.value.targetId && awaitingTarget == peerId -> abortControlling(error)
            peerId == state.value.targetId || peerId in controllers -> _state.value = _state.value.copy(error = error)
        }
    }

    private suspend fun handleCommand(
        sender: String,
        command: DcPlaybackCommand,
        expectedQueue: Long,
    ) {
        val current = canonical ?: return
        if ((command.isQueueIndexed() && expectedQueue != current.queueRevision) ||
            (command.action == DcPlaybackAction.REPLACE_CURRENT && command.expectedTrackRevision != current.trackRevision)
        ) {
            commandError(sender, DcSyncError.STALE_QUEUE)
            if (sender != localDeviceId()) sendSnapshot(sender)
            return
        }
        val base = current.playback.copy(positionMs = targetPosition(current))
        val change =
            reduceDcPlayback(base, command, useLegacyShuffle()) ?: run {
                commandError(sender, DcSyncError.INVALID_MESSAGE)
                return
            }
        val playback = if (change.playback.queue === current.playback.queue) change.playback else wireQueueCache.sanitize(change.playback)
        val queueChanged = !sameDcQueue(current.playback.queue, playback.queue)
        if (!(if (queueChanged) validDcPlayback(playback) else validDcPlaybackScalars(playback))) {
            commandError(sender, DcSyncError.INVALID_MESSAGE)
            return
        }
        if (queueChanged && !playback.isShareableDeviceConnectState()) {
            commandError(sender, DcSyncError.UNSHAREABLE_QUEUE)
            return
        }
        val updated =
            current.copy(
                revision = current.revision + 1,
                queueRevision = current.queueRevision + if (queueChanged) 1 else 0,
                trackRevision = current.trackRevision + if (change.trackChanged) 1 else 0,
                playback = playback,
                anchorTimeMs = monotonicClockMs(),
                queueStateId = if (queueChanged) newId() else current.queueStateId,
            )
        if (queueChanged && !fitsTransport(DcSyncMessageType.STATE, updated)) {
            commandError(sender, DcSyncError.TOO_LARGE)
            return
        }
        val seek = change.trackChanged || command.action in setOf(DcPlaybackAction.SEEK, DcPlaybackAction.PREVIOUS)
        if (command.action == DcPlaybackAction.VOLUME) {
            val target = port ?: return commandError(sender, DcSyncError.UNAVAILABLE)
            target.setVolume(playback.volume)
        }
        canonical = updated
        publishState()
        if (command.action != DcPlaybackAction.VOLUME) applyCanonical(updated, seek = seek)
        if (queueChanged) broadcastSnapshot() else broadcastUpdate(seek, trackChanged = change.trackChanged)
    }

    private suspend fun commandError(
        sender: String,
        error: DcSyncError,
    ) {
        if (sender == localDeviceId()) _state.value = _state.value.copy(error = error) else sendError(sender, error)
    }

    private suspend fun observeLocal(forceTrackChanged: Boolean) {
        val current = canonical ?: return
        val local = port?.snapshot()?.let(wireQueueCache::sanitize) ?: return
        val queueChanged = !sameDcQueue(current.playback.queue, local.queue)
        if (!(if (queueChanged) validDcPlayback(local) else validDcPlaybackScalars(local))) return
        val trackChanged =
            forceTrackChanged || current.playback.queue.currentIndex != local.queue.currentIndex ||
                current.playback.queue.displayedItems
                    .getOrNull(current.playback.queue.currentIndex)
                    ?.id !=
                local.queue.displayedItems
                    .getOrNull(local.queue.currentIndex)
                    ?.id
        if ((queueChanged || trackChanged) && !local.isShareableDeviceConnectState()) {
            _state.value = _state.value.copy(error = DcSyncError.UNSHAREABLE_QUEUE)
            applyCanonical(current)
            return
        }
        val playingChanged = applyJob?.isActive != true && local.ready && local.playing != current.playback.playing
        val changed =
            queueChanged || trackChanged || local.repeatMode != current.playback.repeatMode ||
                local.title != current.playback.title || local.ready != current.playback.ready || local.volume != current.playback.volume ||
                local.pitch != current.playback.pitch || playingChanged ||
                (local.durationMs > 0L && local.durationMs != current.playback.durationMs)
        if (!changed) {
            canonical =
                current.copy(
                    playback =
                        current.playback.copy(
                            queue = local.queue,
                            scrobbleSession = local.scrobbleSession,
                            durationMs = local.durationMs.takeIf { it > 0 } ?: current.playback.durationMs,
                        ),
                )
            if (local.scrobbleSession != current.playback.scrobbleSession) {
                controllers.filter { it !in clockPeers && it in peers }.forEach { sendSnapshot(it) }
            }
            // Follow the audible clock after load or seek completes so controllers do not run ahead.
            val latest = canonical ?: return
            if (applyJob?.isActive != true && local.ready && local.playing && latest.playback.playing &&
                abs(local.positionMs - targetPosition(latest)) > TARGET_REANCHOR_TOLERANCE_MS
            ) {
                canonical =
                    latest.copy(
                        revision = latest.revision + 1,
                        anchorTimeMs = monotonicClockMs(),
                        playback = latest.playback.copy(positionMs = local.positionMs),
                    )
                broadcastUpdate()
            }
            return
        }
        val playback =
            if (trackChanged || local.ready != current.playback.ready || playingChanged) {
                local
            } else {
                local.copy(positionMs = targetPosition(current), playing = current.playback.playing, speed = current.playback.speed)
            }
        val updated =
            current.copy(
                playback = playback,
                anchorTimeMs = monotonicClockMs(),
                revision = current.revision + 1,
                queueRevision = current.queueRevision + if (queueChanged) 1 else 0,
                trackRevision = current.trackRevision + if (trackChanged) 1 else 0,
                queueStateId = if (queueChanged) newId() else current.queueStateId,
            )
        if (queueChanged && !fitsTransport(DcSyncMessageType.STATE, updated)) {
            _state.value = _state.value.copy(error = DcSyncError.TOO_LARGE)
            applyCanonical(current)
            return
        }
        canonical = updated
        publishState()
        port?.acknowledge(updated)
        if (queueChanged || local.title != current.playback.title) {
            broadcastSnapshot()
        } else {
            broadcastUpdate(trackChanged = trackChanged)
        }
    }

    private suspend fun advanceNaturally(allowExtend: Boolean = true) {
        val current = canonical ?: return
        if (allowExtend && !current.playback.finite && current.playback.repeatMode == DcRepeatMode.OFF &&
            current.playback.queue.currentIndex == current.playback.queue.displayedItems.lastIndex
        ) {
            if (extendJob?.isActive == true) return
            val playback = port ?: return
            extendJob =
                scope.launch {
                    val extended =
                        playback.extendQueue {
                            canonical?.sessionId == current.sessionId && canonical?.revision == current.revision
                        }
                    enqueue {
                        val latest = canonical ?: return@enqueue
                        if (latest.sessionId != current.sessionId || latest.trackRevision != current.trackRevision ||
                            !latest.playback.playing ||
                            (latest.playback.durationMs > 0 && targetPosition(latest) < latest.playback.durationMs - 750L)
                        ) {
                            return@enqueue
                        }
                        if (extended != null) observeLocal(false)
                        advanceNaturally(allowExtend = false)
                    }
                }
            return
        }
        val command =
            if (current.playback.repeatMode == DcRepeatMode.ONE) {
                DcPlaybackCommand(DcPlaybackAction.SELECT, index = current.playback.queue.currentIndex)
            } else {
                DcPlaybackCommand(DcPlaybackAction.NEXT)
            }
        handleCommand(localDeviceId(), command, current.queueRevision)
    }

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob =
            scope.launch {
                while (true) {
                    delay(1_000L.milliseconds)
                    enqueue {
                        if (!isActive) return@enqueue
                        if (isFollowing) {
                            canonical?.let { port?.setControllerPlayback(it.playback.copy(positionMs = targetPosition(it))) }
                            return@enqueue
                        }
                        val previous = canonical
                        if (applyJob?.isActive != true) observeLocal(false)
                        val current = canonical
                        if (current != null && current.playback.playing && current.playback.durationMs > 0 &&
                            targetPosition(current) >= current.playback.durationMs &&
                            port?.snapshot()?.ready == false
                        ) {
                            advanceNaturally()
                        }
                        if (canonical?.playback?.playing == true || canonical != previous) {
                            val correction = monotonicClockMs() - lastClockBroadcastMs >= CLOCK_CORRECTION_INTERVAL_MS
                            broadcastUpdate(
                                legacyOnly =
                                    !correction && canonical?.revision == previous?.revision &&
                                        canonical?.playback?.durationMs == previous?.playback?.durationMs,
                            )
                        }
                    }
                }
            }
    }

    private fun applyCanonical(
        snapshot: DcPlaybackSnapshot,
        seek: Boolean = false,
    ) {
        val playback = port ?: return
        cancelApply()
        val generation = applyGeneration
        applying = true
        applyJob =
            scope.launch {
                try {
                    playback.setAudioOutputEnabled(!isFollowing)
                    if (isFollowing) {
                        playback.setControllerPlayback(snapshot.playback.copy(positionMs = targetPosition(snapshot)))
                        return@launch
                    }
                    playback.setControllerPlayback(null)
                    val requestedPosition = targetPosition(snapshot)
                    playback.apply(snapshot, requestedPosition) { generation == applyGeneration && port === playback }
                    if (generation != applyGeneration) return@launch
                    if (seek) playback.seekTo(requestedPosition)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    reportError(DcSyncError.UNAVAILABLE)
                    val target = state.value.targetId
                    if (isFollowing && target != null) {
                        delay(APPLY_RETRY_DELAY_MS.milliseconds)
                        if (generation == applyGeneration) enqueue { requestState(target) }
                    }
                } finally {
                    if (generation == applyGeneration) {
                        applying = false
                        applyJob = null
                        // Paused sessions have no ticker to publish load completion.
                        if (canonical?.playback?.playing == false) localStateChanged()
                    }
                }
            }
    }

    private fun cancelApply() {
        applyGeneration++
        applying = false
        applyJob?.cancel()
        applyJob = null
    }

    private suspend fun cancelApplyAndWait() {
        val job = applyJob
        cancelApply()
        job?.join()
    }

    private fun targetPosition(snapshot: DcPlaybackSnapshot): Long {
        val playback = snapshot.playback
        val anchor = if (isFollowing) controllerAnchorMs else snapshot.anchorTimeMs
        val elapsed = if (playback.playing && playback.ready) (monotonicClockMs() - anchor).coerceAtLeast(0L) else 0L
        return (playback.positionMs + (elapsed * playback.speed).toLong()).coerceIn(
            0L,
            playback.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE,
        )
    }

    private fun publishState() {
        val snapshot = canonical ?: return
        val following = isFollowing
        port?.setExternalReportingEnabled(enabled = !following, session = snapshot.playback.scrobbleSession)
        val previous = _state.value
        _state.value =
            previous.copy(
                sessionId = snapshot.sessionId,
                targetId = if (following) previous.targetId else localDeviceId(),
                controllerIds = if (following) emptySet() else controllers.toSet(),
                playbackAvailable = port != null,
            )
    }

    private fun snapshotMessage(): DcSyncMessage? {
        val current = canonical ?: return null
        return DcSyncMessage(
            type = DcSyncMessageType.STATE,
            sessionId = current.sessionId,
            snapshot =
                current.copy(
                    playback = current.playback.copy(positionMs = targetPosition(current)),
                    anchorTimeMs = monotonicClockMs(),
                ),
            queueStateId = current.queueStateId,
        )
    }

    private suspend fun sendSnapshot(peerId: String) {
        snapshotMessage()?.let { sendChecked(peerId, it) }
    }

    private suspend fun broadcastSnapshot() {
        val message = snapshotMessage() ?: return
        controllers.toList().forEach { if (it in peers) sendChecked(it, message) }
    }

    private suspend fun broadcastUpdate(
        seek: Boolean = false,
        trackChanged: Boolean = false,
        legacyOnly: Boolean = false,
    ) {
        val current = canonical ?: return
        val message =
            DcSyncMessage(
                DcSyncMessageType.UPDATE,
                sessionId = current.sessionId,
                revision = current.revision,
                queueRevision = current.queueRevision,
                trackRevision = current.trackRevision,
                index = current.playback.queue.currentIndex,
                positionMs = targetPosition(current),
                durationMs = current.playback.durationMs,
                playing = current.playback.playing,
                speed = current.playback.speed,
                pitch = current.playback.pitch,
                volume = current.playback.volume,
                repeatMode = current.playback.repeatMode,
                seek = seek,
                ready = current.playback.ready,
                scrobbleSession = current.playback.scrobbleSession,
                queueStateId = current.queueStateId,
            )
        if (!legacyOnly) lastClockBroadcastMs = monotonicClockMs()
        controllers.toList().forEach {
            if (it in peers && (!legacyOnly || it !in clockPeers)) {
                if (trackChanged && it !in clockPeers) sendSnapshot(it) else sendChecked(it, message)
            }
        }
    }

    private fun fitsTransport(
        type: DcSyncMessageType,
        snapshot: DcPlaybackSnapshot,
    ): Boolean =
        DcJson
            .encodeToString(DcSyncMessage(type, sessionId = snapshot.sessionId, snapshot = snapshot, queueStateId = snapshot.queueStateId))
            .encodeToByteArray()
            .size <= DEVICE_CONNECT_MAX_PLAYBACK_BYTES

    private suspend fun sendChecked(
        peerId: String,
        message: DcSyncMessage,
    ) {
        if (!send(peerId, message)) _state.value = _state.value.copy(error = DcSyncError.UNAVAILABLE)
    }

    private suspend fun sendError(
        peerId: String,
        error: DcSyncError,
    ) {
        send(peerId, DcSyncMessage(DcSyncMessageType.ERROR, sessionId = state.value.sessionId, error = error))
    }

    private fun fail(error: DcSyncError) {
        _state.value = _state.value.copy(error = error)
    }

    private suspend fun notifyPeersOfStop() {
        if (isFollowing) {
            state.value.targetId?.let { send(it, DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = state.value.sessionId)) }
        } else {
            controllers.toList().forEach { send(it, DcSyncMessage(DcSyncMessageType.RELEASE, sessionId = state.value.sessionId)) }
        }
    }

    private suspend fun endSession(
        pause: Boolean,
        error: DcSyncError? = null,
    ) {
        cancelApplyAndWait()
        clearPending()
        tickJob?.cancel()
        tickJob = null
        extendJob?.cancel()
        extendJob = null
        try {
            port?.setControllerPlayback(null)
            if (pause) port?.setPlaying(false)
            port?.setExternalReportingEnabled(enabled = true, session = null)
            port?.setAudioOutputEnabled(true)
        } finally {
            canonical = null
            wireQueueCache.clear()
            controllers.clear()
            lastEndedTrack = -1L
            _state.value = DcSyncState(playbackAvailable = port != null, error = error)
        }
    }

    private fun newId(): String = "${monotonicClockMs()}:${Random.nextLong().toString(16)}"
}

private const val DEVICE_CONNECT_TRANSFER_TIMEOUT_MS = 10_000L
private const val CLOCK_CORRECTION_INTERVAL_MS = 10_000L
private const val APPLY_RETRY_DELAY_MS = 3_000L
private const val TARGET_REANCHOR_TOLERANCE_MS = 300L
