/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.listentogether

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.constants.ListenTogetherSyncVolumeKey
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.MediaMetadata.Album
import com.metrolist.music.models.MediaMetadata.Artist
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

internal fun canonicalPlaybackQueue(
    currentTrack: TrackInfo,
    upcomingQueue: List<TrackInfo>,
): List<TrackInfo> =
    buildList {
        add(currentTrack)
        upcomingQueue
            .asSequence()
            .filter { it.id != currentTrack.id }
            .distinctBy { it.id }
            .forEach(::add)
    }

internal fun <T> upcomingQueueItems(
    queue: List<T>,
    currentIndex: Int,
): List<T> = if (currentIndex in queue.indices) queue.drop(currentIndex + 1) else emptyList()

/**
 * Manager that bridges the Listen Together WebSocket client with the music player.
 * Handles syncing playback actions between connected users.
 */
@Singleton
class ListenTogetherManager
    @Inject
    constructor(
        private val client: ListenTogetherClient,
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "ListenTogetherManager"

            private const val SOFT_SYNC_THRESHOLD_MS = 50L
            private const val HARD_SYNC_THRESHOLD_MS = 750L
            private const val DRIFT_CORRECTION_SPEED = 0.02f
            private const val DRIFT_CHECK_INTERVAL_MS = 250L
        }

        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        init {
            observePreferences()
        }

        private var playerConnection: PlayerConnection? = null
        private var eventCollectorJob: Job? = null
        private var queueObserverJob: Job? = null
        private var volumeObserverJob: Job? = null
        private var playerListenerRegistered = false

        private val syncHostVolumeEnabled = MutableStateFlow(true)
        private var lastSyncedVolume: Float? = null
        private var previousMuteState: Boolean? = null
        private var muteForcedByPreference = false

        private var lastRole: RoomRole = RoomRole.NONE

        // Whether we're currently syncing (to prevent feedback loops)
        @Volatile
        private var isSyncing = false

        // Track the last state we synced to avoid duplicate events
        private var lastSyncedIsPlaying: Boolean? = null
        private var lastSyncedTrackId: String? = null

        // Track last sync action time for debouncing (prevents excessive seeking/pausing)
        private var lastSyncActionTime: Long = 0L

        // Track ID being buffered
        private var bufferingTrackId: String? = null

        // Track active sync job to cancel it if a better update arrives
        private var activeSyncJob: Job? = null
        private var driftCorrectionJob: Job? = null
        private var driftBasePlaybackParameters: PlaybackParameters? = null
        private var driftAppliedPlaybackParameters: PlaybackParameters? = null
        private var driftCorrectedPlayer: Player? = null
        private var driftCorrectionGeneration = 0
        private var lastAppliedRevision = 0L
        private var queueSyncGeneration = 0
        private var queueMutationJob: Job? = null
        private var applyNextHostSnapshot = false

        // Generation ID for track changes - incremented on each new track change
        // Used to prevent old coroutines from overwriting newer track loads
        private var currentTrackGeneration: Int = 0

        // Pending sync to apply after buffering completes for guest
        private var pendingSyncState: SyncStatePayload? = null

        // Track if a buffer-complete arrived before the pending sync was ready
        private var bufferCompleteReceivedForTrack: String? = null

        // Expose client state
        val connectionState = client.connectionState
        val roomState = client.roomState
        val role = client.role
        val userId = client.userId
        val pendingJoinRequests = client.pendingJoinRequests
        val bufferingUsers = client.bufferingUsers
        val logs = client.logs
        val events = client.events
        val blockedUsernames = client.blockedUsernames
        val pendingSuggestions = client.pendingSuggestions

        val isInRoom: Boolean get() = client.isInRoom
        val isHost: Boolean get() = client.isHost
        val hasPersistedSession: Boolean get() = client.hasPersistedSession

        private val playerListener =
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    try {
                        if (isSyncing || !isHost || !isInRoom) return

                        val connection = playerConnection ?: return
                        if (connection.allowInternalSync) return
                        val player = connection.player

                        Timber.tag(TAG).d("Play state changed: $playWhenReady (reason: $reason)")

                        // ALWAYS ensure track is synced before play/pause
                        val currentTrackId = player.currentMediaItem?.mediaId
                        if (currentTrackId != null && currentTrackId != lastSyncedTrackId) {
                            Timber
                                .tag(TAG)
                                .d("[SYNC] Sending track change before play state: track = $currentTrackId")
                            player.currentMetadata?.let { metadata ->
                                sendTrackChangeInternal(metadata)
                                lastSyncedTrackId = currentTrackId
                                // Reset play state since server resets IsPlaying on track change
                                lastSyncedIsPlaying = false
                            }
                            if (playWhenReady) {
                                Timber.tag(TAG).d("[SYNC] Host is playing, sending PLAY after track change")
                                lastSyncedIsPlaying = true
                                client.sendPlaybackAction(
                                    PlaybackActions.PLAY,
                                    trackId = currentTrackId,
                                    position = player.currentPosition,
                                )
                            }
                            return
                        }

                        // Only send play/pause if track is already synced
                        sendPlayState(playWhenReady, player)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error in onPlayWhenReadyChanged")
                    }
                }

                private fun sendPlayState(
                    playWhenReady: Boolean,
                    player: Player,
                ) {
                    try {
                        val position = player.currentPosition
                        val currentTrackId = player.currentMediaItem?.mediaId

                        if (playWhenReady) {
                            Timber.tag(TAG).d("Host sending PLAY at position $position (track: $currentTrackId)")
                            client.sendPlaybackAction(PlaybackActions.PLAY, trackId = currentTrackId, position = position)
                            lastSyncedIsPlaying = true
                        } else if (!playWhenReady && (lastSyncedIsPlaying == true)) {
                            Timber.tag(TAG).d("Host sending PAUSE at position $position (track: $currentTrackId)")
                            client.sendPlaybackAction(PlaybackActions.PAUSE, trackId = currentTrackId, position = position)
                            lastSyncedIsPlaying = false
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error in sendPlayState")
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    try {
                        if (isSyncing || !isHost || !isInRoom) return
                        if (mediaItem == null) return

                        val connection = playerConnection ?: return
                        if (connection.allowInternalSync) return
                        val player = connection.player

                        val trackId = mediaItem.mediaId
                        if (trackId == lastSyncedTrackId) return

                        // Get metadata and send track change
                        player.currentMetadata?.let { metadata ->
                            lastSyncedTrackId = trackId
                            // Reset play state tracking since server resets IsPlaying on track change
                            lastSyncedIsPlaying = false

                            Timber.tag(TAG).d("Host sending track change: ${metadata.title}")
                            sendTrackChangeInternal(metadata)

                            val isPlaying = player.playWhenReady
                            if (isPlaying) {
                                Timber.tag(TAG).d("Host is playing during track change, sending PLAY")
                                lastSyncedIsPlaying = true
                                client.sendPlaybackAction(
                                    PlaybackActions.PLAY,
                                    trackId = trackId,
                                    position = player.currentPosition,
                                )
                            }
                        } ?: Timber
                            .tag(TAG)
                            .w("onMediaItemTransition: metadata not ready for $trackId; lastSyncedTrackId unchanged")
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error in onMediaItemTransition")
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    try {
                        if (isSyncing || !isHost || !isInRoom) return
                        if (playerConnection?.allowInternalSync == true) return

                        // Only send seek if it was a user-initiated seek
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            val trackId = playerConnection?.player?.currentMediaItem?.mediaId
                            Timber.tag(TAG).d("Host sending SEEK to ${newPosition.positionMs} (track: $trackId)")
                            client.sendPlaybackAction(PlaybackActions.SEEK, trackId = trackId, position = newPosition.positionMs)
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error in onPositionDiscontinuity")
                    }
                }
            }

        /**
         * Set the player connection for playback sync.
         * Should be called when PlayerConnection is available.
         */
        fun setPlayerConnection(connection: PlayerConnection?) {
            Timber.tag(TAG).d("setPlayerConnection: ${connection != null}, isInRoom: $isInRoom")

            try {
                // Remove old listener and callback safely
                val oldConnection = playerConnection
                if (playerListenerRegistered && oldConnection != null) {
                    try {
                        oldConnection.player.removeListener(playerListener)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error removing old player listener")
                    }
                    playerListenerRegistered = false
                }
                oldConnection?.shouldBlockPlaybackChanges = null
                oldConnection?.onSkipPrevious = null
                oldConnection?.onSkipNext = null
                oldConnection?.onRestartSong = null

                cancelDriftCorrection()
                invalidatePendingQueueMutations()

                playerConnection = connection

                // Set up playback blocking for guests
                connection?.shouldBlockPlaybackChanges = {
                    // Block if we're in a room as a guest (not host)
                    isInRoom && !isHost
                }

                // Add listener if in room
                if (connection != null && isInRoom) {
                    try {
                        connection.player.addListener(playerListener)
                        playerListenerRegistered = true
                        Timber.tag(TAG).d("Added player listener for room sync")
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to add player listener")
                        playerListenerRegistered = false
                    }

                    // Hook up restart action
                    connection.onRestartSong = {
                        try {
                            if (isHost && !isSyncing) {
                                val trackId = connection.player.currentMediaItem?.mediaId
                                Timber.tag(TAG).d("Host Restart Song triggered (sending 1ms as 0ms workaround, track: $trackId)")
                                client.sendPlaybackAction(PlaybackActions.SEEK, trackId = trackId, position = 1L)
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error in onRestartSong")
                        }
                    }
                }

                // Start/stop queue observation based on role
                if (connection != null && isInRoom && isHost) {
                    startQueueSyncObservation()
                    startHeartbeat()
                    startVolumeSyncObservation()
                } else {
                    stopQueueSyncObservation()
                    stopHeartbeat()
                    stopVolumeSyncObservation()
                }
                if (connection != null && oldConnection !== connection && isInRoom && (!isHost || oldConnection != null)) {
                    applyNextHostSnapshot = isHost
                    client.requestSync()
                }
                updateGuestMuteState()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in setPlayerConnection")
            }
        }

        private fun observePreferences() {
            scope.launch {
                context.dataStore.data
                    .map { it[ListenTogetherSyncVolumeKey] ?: true }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        syncHostVolumeEnabled.value = enabled
                    }
            }
        }

        /**
         * Initialize event collection. Should be called once at app start.
         */
        fun initialize() {
            Timber.tag(TAG).d("Initializing ListenTogetherManager")
            eventCollectorJob?.cancel()
            eventCollectorJob =
                scope.launch {
                    client.events.collect { event ->
                        try {
                            Timber.tag(TAG).d("Received event: $event")
                            handleEvent(event)
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error handling event: $event")
                        }
                    }
                }

            // Role change listener
            scope.launch {
                role.collect { newRole ->
                    try {
                        val previousRole = lastRole
                        lastRole = newRole

                        val wasHost = previousRole == RoomRole.HOST
                        if (newRole == RoomRole.HOST && !wasHost) {
                            val connection = playerConnection
                            if (connection != null) {
                                Timber.tag(TAG).d("Role changed to HOST, starting sync services")
                                startQueueSyncObservation()
                                startHeartbeat()
                                startVolumeSyncObservation()
                                // Re-register listener if needed
                                if (!playerListenerRegistered) {
                                    try {
                                        connection.player.addListener(playerListener)
                                        playerListenerRegistered = true
                                    } catch (e: Exception) {
                                        Timber.tag(TAG).e(e, "Failed to add player listener on role change")
                                    }
                                }
                            }
                        } else if (newRole != RoomRole.HOST && wasHost) {
                            Timber.tag(TAG).d("Role changed from HOST, stopping sync services")
                            stopQueueSyncObservation()
                            stopHeartbeat()
                            stopVolumeSyncObservation()
                        }
                        updateGuestMuteState()
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error in role change handler")
                    }
                }
            }
        }

        private fun handleEvent(event: ListenTogetherEvent) {
            when (event) {
                is ListenTogetherEvent.Connected -> {
                    Timber.tag(TAG).d("Connected to server with userId: ${event.userId}")
                }

                is ListenTogetherEvent.RoomCreated -> {
                    Timber.tag(TAG).d("Room created: ${event.roomCode}")
                    try {
                        // Register player listener for host
                        val connection = playerConnection
                        val player = connection?.player
                        if (player != null && !playerListenerRegistered) {
                            try {
                                player.addListener(playerListener)
                                playerListenerRegistered = true
                                Timber.tag(TAG).d("Added player listener as host")
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to add player listener on room create")
                            }
                        }
                        // Initialize sync state
                        lastSyncedIsPlaying = player?.playWhenReady
                        lastSyncedTrackId = player?.currentMediaItem?.mediaId

                        // If there's already a track loaded, send it to the server
                        player?.currentMetadata?.let { metadata ->
                            Timber.tag(TAG).d("Room created with existing track: ${metadata.title}")
                            // Send track change so server has the current track info
                            sendTrackChangeInternal(metadata)
                            // WebSocket sends from one client are ordered, so PLAY can follow immediately.
                            val isPlaying = player.playWhenReady
                            if (isPlaying) {
                                lastSyncedIsPlaying = true
                                Timber.tag(TAG).d("Host already playing on room create, sending PLAY")
                                client.sendPlaybackAction(
                                    PlaybackActions.PLAY,
                                    trackId = metadata.id,
                                    position = player.currentPosition,
                                )
                            }
                        }
                        startQueueSyncObservation()
                        startHeartbeat()
                        startVolumeSyncObservation()
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error handling RoomCreated event")
                    }
                }

                is ListenTogetherEvent.JoinApproved -> {
                    Timber.tag(TAG).d("Join approved for room: ${event.roomCode}")
                    // Save current mute state before joining as guest so we can restore it on leave
                    saveMuteStateOnJoin()
                    lastAppliedRevision = event.state.revision
                    // Apply the full initial state including queue
                    applyPlaybackState(
                        currentTrack = event.state.currentTrack,
                        isPlaying = event.state.isPlaying,
                        position = event.state.position,
                        queue = event.state.queue,
                        effectiveAtServerTime = event.state.lastUpdate,
                        // bypassBuffer=false (default) for initial join buffer sync
                    )
                    applyHostVolumeIfNeeded(event.state.volume)
                    updateGuestMuteState()
                }

                is ListenTogetherEvent.PlaybackSync -> {
                    Timber.tag(TAG).d("PlaybackSync received: ${event.action.action}")
                    // Guests handle all sync actions. Host should also apply queue ops.
                    val actionType = event.action.action
                    val isQueueOp =
                        actionType == PlaybackActions.QUEUE_ADD ||
                            actionType == PlaybackActions.QUEUE_REMOVE ||
                            actionType == PlaybackActions.QUEUE_CLEAR
                    if (!isHost || isQueueOp) {
                        handlePlaybackSync(event.action)
                    }
                }

                is ListenTogetherEvent.UserJoined -> {
                    Timber.tag(TAG).d("[SYNC] User joined: ${event.username}")
                }

                is ListenTogetherEvent.BufferWait -> {
                    Timber.tag(TAG).d("BufferWait: waiting for ${event.waitingFor.size} users")
                }

                is ListenTogetherEvent.BufferComplete -> {
                    Timber.tag(TAG).d("BufferComplete for track: ${event.trackId}")
                    if (!isHost && bufferingTrackId == event.trackId) {
                        bufferCompleteReceivedForTrack = event.trackId
                        applyPendingSyncIfReady()
                    }
                }

                is ListenTogetherEvent.SyncStateReceived -> {
                    Timber
                        .tag(
                            TAG,
                        ).d(
                            "SyncStateReceived: playing=${event.state.isPlaying}, pos=${event.state.position}, track=${event.state.currentTrack?.id}",
                        )
                    if (!isHost || applyNextHostSnapshot) {
                        val forceFullState = applyNextHostSnapshot
                        applyNextHostSnapshot = false
                        handleSyncState(event.state, forceFullState)
                    }
                }

                is ListenTogetherEvent.Kicked -> {
                    Timber.tag(TAG).d("Kicked from room: ${event.reason}")
                    cleanup()
                }

                is ListenTogetherEvent.Disconnected -> {
                    Timber.tag(TAG).d("Disconnected from server")
                    // Don't cleanup on disconnect - we might reconnect
                    // cleanup() is called when leaving room intentionally or when kicked
                }

                is ListenTogetherEvent.Reconnecting -> {
                    Timber.tag(TAG).d("Reconnecting: attempt ${event.attempt}/${event.maxAttempts}")
                }

                is ListenTogetherEvent.Reconnected -> {
                    Timber.tag(TAG).d("Reconnected to room: ${event.roomCode}, isHost: ${event.isHost}")
                    try {
                        // Re-register player listener
                        val connection = playerConnection
                        val player = connection?.player
                        if (player != null && !playerListenerRegistered) {
                            try {
                                player.addListener(playerListener)
                                playerListenerRegistered = true
                                Timber.tag(TAG).d("Re-added player listener after reconnect")
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to re-add player listener after reconnect")
                            }
                        }

                        // Sync state based on role
                        if (event.isHost) {
                            // Host: only send sync if necessary
                            lastSyncedIsPlaying = player?.playWhenReady
                            lastSyncedTrackId = player?.currentMediaItem?.mediaId

                            val currentMetadata = player?.currentMetadata
                            if (currentMetadata != null) {
                                // Check if server already has the right track (from event.state)
                                val serverTrackId = event.state.currentTrack?.id
                                if (serverTrackId != currentMetadata.id) {
                                    Timber
                                        .tag(
                                            TAG,
                                        ).d(
                                            "Reconnected as host, server track ($serverTrackId) differs from local (${currentMetadata.id}), syncing",
                                        )
                                    sendTrackChangeInternal(currentMetadata)
                                } else {
                                    Timber.tag(TAG).d("Reconnected as host, server already has current track $serverTrackId")
                                }

                                if (player.playWhenReady) {
                                    val pos = player.currentPosition
                                    val trackId = player.currentMediaItem?.mediaId
                                    Timber.tag(TAG).d("Reconnected host is playing, sending PLAY at $pos (track: $trackId)")
                                    client.sendPlaybackAction(PlaybackActions.PLAY, trackId = trackId, position = pos)
                                }
                            }
                        } else {
                            // Guest: ALWAYS sync to host's state after reconnection
                            Timber.tag(TAG).d("Reconnected as guest, syncing to host's current state")
                            lastAppliedRevision = event.state.revision
                            applyPlaybackState(
                                currentTrack = event.state.currentTrack,
                                isPlaying = event.state.isPlaying,
                                position = event.state.position,
                                queue = event.state.queue,
                                effectiveAtServerTime = event.state.lastUpdate,
                                bypassBuffer = true, // Reconnect: bypass buffer protocol
                            )
                            applyHostVolumeIfNeeded(event.state.volume)
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error handling Reconnected event")
                    }
                }

                is ListenTogetherEvent.UserReconnected -> {
                    Timber.tag(TAG).d("User reconnected: ${event.username}")
                    // No action needed - reconnected user already synced via reconnect state
                }

                is ListenTogetherEvent.UserDisconnected -> {
                    Timber.tag(TAG).d("User temporarily disconnected: ${event.username}")
                    // User might reconnect, no action needed
                }

                is ListenTogetherEvent.HostChanged -> {
                    Timber.tag(TAG).d("Host changed: new host is ${event.newHostName} (${event.newHostId})")
                    val wasHost = isHost
                    val nowIsHost = event.newHostId == userId.value

                    if (wasHost && !nowIsHost) {
                        // Lost host role
                        Timber.tag(TAG).d("Local user lost host role")
                        stopQueueSyncObservation()
                        stopVolumeSyncObservation()
                        if (playerListenerRegistered) {
                            playerConnection?.player?.removeListener(playerListener)
                            playerListenerRegistered = false
                        }
                        // Restore guest mute state since we're now a guest
                        updateGuestMuteState()
                    } else if (!wasHost && nowIsHost) {
                        // Gained host role
                        Timber.tag(TAG).d("Local user gained host role")
                        updateGuestMuteState() // This will restore mute state since we're now host

                        // Register player listener
                        val connection = playerConnection
                        val player = connection?.player
                        if (player != null && !playerListenerRegistered) {
                            try {
                                player.addListener(playerListener)
                                playerListenerRegistered = true
                                Timber.tag(TAG).d("Added player listener as new host")
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to add player listener on host transfer")
                            }
                        }

                        // Start the queue and volume sync observations now that we're host
                        startQueueSyncObservation()
                        startVolumeSyncObservation()

                        // Send current player state to guests
                        val metadata = player?.currentMetadata
                        if (metadata != null) {
                            Timber.tag(TAG).d("New host sending current track: ${metadata.title}")
                            sendTrackChangeInternal(metadata)

                            if (player.playWhenReady) {
                                Timber.tag(TAG).d("New host is playing, sending PLAY")
                                client.sendPlaybackAction(
                                    PlaybackActions.PLAY,
                                    trackId = metadata.id,
                                    position = player.currentPosition,
                                )
                            }
                        }
                    }
                }

                is ListenTogetherEvent.ConnectionError -> {
                    Timber.tag(TAG).e("Connection error: ${event.error}")
                    cleanup()
                }

                else -> { /* Other events handled by UI */ }
            }
        }

        private fun cleanup() {
            if (lastRole == RoomRole.GUEST) {
                restoreGuestMuteState()
            }
            if (playerListenerRegistered) {
                playerConnection?.player?.removeListener(playerListener)
                playerListenerRegistered = false
            }
            stopQueueSyncObservation()
            stopHeartbeat()
            stopVolumeSyncObservation()
            cancelDriftCorrection()
            // Note: Don't clear shouldBlockPlaybackChanges callback - it checks isInRoom dynamically
            lastSyncedIsPlaying = null
            lastSyncedTrackId = null
            bufferingTrackId = null
            isSyncing = false
            bufferCompleteReceivedForTrack = null
            lastRole = RoomRole.NONE
            lastSyncActionTime = 0L // Reset sync debouncing
            lastAppliedRevision = 0L
            applyNextHostSnapshot = false
            invalidatePendingQueueMutations()
            ++currentTrackGeneration // Increment to invalidate any pending track-change coroutines
        }

        private fun updateGuestMuteState() {
            // Guests are no longer forced to mute - they can hear the music too
            val connection = playerConnection ?: return
            // Just restore any previously forced mute state (should typically be none)
            restoreGuestMuteState()
        }

        /**
         * Save the current mute state when joining a room as guest.
         * This allows us to restore it when leaving the room.
         */
        private fun saveMuteStateOnJoin() {
            val connection = playerConnection ?: return
            // Only save if we haven't already saved (avoid overwriting on role changes)
            if (previousMuteState == null) {
                previousMuteState = connection.isMuted.value
                Timber.tag(TAG).d("Saved mute state on join: $previousMuteState")
            }
        }

        /**
         * Restore the mute state that was saved when joining the room.
         * This is called when leaving the room to ensure the user's
         * mute preference is restored to what it was before joining Listen Together.
         */
        private fun restoreGuestMuteState() {
            val connection = playerConnection ?: return
            val savedState = previousMuteState

            if (savedState != null) {
                Timber.tag(TAG).d("Restoring mute state on leave: was muted=$savedState, currently muted=${connection.isMuted.value}")
                connection.setMuted(savedState)
            } else {
                // No saved state means we never properly saved (e.g., player wasn't ready on join)
                // In this case, if currently muted, unmute as a fallback
                if (connection.isMuted.value) {
                    Timber.tag(TAG).d("No saved mute state on leave, unmuting player as fallback")
                    connection.setMuted(false)
                }
            }

            previousMuteState = null
            muteForcedByPreference = false
        }

        private fun applyHostVolumeIfNeeded(volume: Float?) {
            if (!syncHostVolumeEnabled.value || isHost || !isInRoom) return
            val connection = playerConnection ?: return
            val target = volume?.coerceIn(0f, 1f) ?: return
            connection.service.playerVolume.value = target
        }

        private fun cancelDriftCorrection() {
            driftCorrectionGeneration++
            driftCorrectionJob?.cancel()
            driftCorrectionJob = null
            restoreDriftCorrectionSpeed()
        }

        private fun restoreDriftCorrectionSpeed() {
            val player = driftCorrectedPlayer
            val parameters = driftBasePlaybackParameters
            val appliedParameters = driftAppliedPlaybackParameters
            if (player != null && parameters != null && appliedParameters != null && player.playbackParameters == appliedParameters) {
                player.setPlaybackParameters(parameters)
            }
            driftBasePlaybackParameters = null
            driftAppliedPlaybackParameters = null
            driftCorrectedPlayer = null
        }

        private fun invalidatePendingQueueMutations() {
            queueSyncGeneration++
            queueMutationJob?.cancel()
            queueMutationJob = null
        }

        private fun enqueueQueueMutation(
            generation: Int,
            mutation: suspend () -> Unit,
        ) {
            val previous = queueMutationJob
            queueMutationJob =
                scope.launch {
                    previous?.join()
                    if (generation != queueSyncGeneration) return@launch
                    mutation()
                }
        }

        private fun applyCanonicalUpcomingQueue(
            connection: PlayerConnection,
            queue: List<TrackInfo>,
            queueTitle: String?,
        ) {
            invalidatePendingQueueMutations()
            if (playerConnection !== connection) return

            val player = connection.player
            val state = roomState.value
            val currentTrack = state?.currentTrack
            if (currentTrack != null && player.currentMediaItem?.mediaId != currentTrack.id) {
                applyPlaybackState(
                    currentTrack = currentTrack,
                    isPlaying = state.isPlaying,
                    position = state.position,
                    queue = queue,
                    queueTitle = queueTitle,
                    effectiveAtServerTime = state.lastUpdate,
                    bypassBuffer = true,
                )
                return
            }

            val currentIndex = player.currentMediaItemIndex
            if (currentIndex < 0) return
            val mediaItems =
                currentTrack
                    ?.let { canonicalPlaybackQueue(it, queue).drop(1) }
                    .orEmpty()
                    .asSequence()
                    .map { it.toMediaMetadata().toMediaItem() }
                    .toList()

            connection.allowInternalSync = true
            try {
                player.shuffleModeEnabled = false
                if (player.mediaItemCount > currentIndex + 1) {
                    player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
                }
                if (mediaItems.isNotEmpty()) {
                    player.addMediaItems(currentIndex + 1, mediaItems)
                }
                if (queueTitle != null) {
                    connection.service.queueTitle = queueTitle
                }
            } finally {
                connection.allowInternalSync = false
            }
        }

        private fun startDriftCorrection(
            connection: PlayerConnection,
            trackId: String?,
            position: Long,
            effectiveAtServerTime: Long?,
        ) {
            cancelDriftCorrection()
            val player = connection.player
            if (!player.playWhenReady || effectiveAtServerTime == null || client.serverTimeNow() == null) return

            val baseParameters = player.playbackParameters
            driftBasePlaybackParameters = baseParameters
            driftCorrectedPlayer = player
            val generation = driftCorrectionGeneration
            driftCorrectionJob =
                scope.launch {
                    while (
                        generation == driftCorrectionGeneration &&
                        playerConnection === connection &&
                        !isHost &&
                        player.playWhenReady &&
                        (trackId == null || player.currentMediaItem?.mediaId == trackId)
                    ) {
                        val target = client.positionAtServerTime(position, effectiveAtServerTime, isPlaying = true)
                        val drift = target - player.currentPosition
                        val absoluteDrift = kotlin.math.abs(drift)
                        if (absoluteDrift <= SOFT_SYNC_THRESHOLD_MS) break
                        if (absoluteDrift >= HARD_SYNC_THRESHOLD_MS) {
                            connection.seekTo(target)
                            break
                        }

                        val appliedParameters = driftAppliedPlaybackParameters
                        if (appliedParameters != null && player.playbackParameters != appliedParameters) break
                        val multiplier = if (drift > 0L) 1f + DRIFT_CORRECTION_SPEED else 1f - DRIFT_CORRECTION_SPEED
                        val correctedParameters = baseParameters.withSpeed(baseParameters.speed * multiplier)
                        player.setPlaybackParameters(correctedParameters)
                        driftAppliedPlaybackParameters = correctedParameters
                        delay(DRIFT_CHECK_INTERVAL_MS)
                    }

                    if (generation == driftCorrectionGeneration) {
                        restoreDriftCorrectionSpeed()
                        driftCorrectionJob = null
                    }
                }
        }

        private fun applyPendingSyncIfReady() {
            val pending = pendingSyncState ?: return
            val pendingTrackId = pending.currentTrack?.id ?: bufferingTrackId ?: return
            val completeForTrack = bufferCompleteReceivedForTrack

            if (completeForTrack != pendingTrackId) return

            val connection = playerConnection ?: return
            val player = connection.player

            Timber.tag(TAG).d("Applying pending sync: track=$pendingTrackId, pos=${pending.position}, play=${pending.isPlaying}")
            isSyncing = true

            val targetPos =
                client.positionAtServerTime(
                    pending.position,
                    pending.lastUpdate.takeIf { it > 0L },
                    pending.isPlaying,
                )
            val posDiff = kotlin.math.abs(player.currentPosition - targetPos)
            val willPlay = pending.isPlaying

            if (posDiff > SOFT_SYNC_THRESHOLD_MS) {
                Timber
                    .tag(
                        TAG,
                    ).d("Applying pending sync: seeking ${player.currentPosition} -> $targetPos (diff ${posDiff}ms)")
                connection.seekTo(targetPos)
            }

            // Apply play/pause state only if it needs to change
            if (willPlay && !player.playWhenReady) {
                Timber.tag(TAG).d("Applying pending sync: starting playback")
                connection.play()
            } else if (!willPlay && player.playWhenReady) {
                Timber.tag(TAG).d("Applying pending sync: pausing playback")
                connection.pause()
            }
            if (willPlay) {
                startDriftCorrection(connection, pendingTrackId, pending.position, pending.lastUpdate.takeIf { it > 0L })
            } else {
                cancelDriftCorrection()
            }

            scope.launch {
                delay(200)
                isSyncing = false
            }

            bufferingTrackId = null
            pendingSyncState = null
            bufferCompleteReceivedForTrack = null
        }

        /**
         * True when the host attached a [PlaybackActionPayload.trackId] and the local player
         * is not on that item (or has no item). Prevents PLAY/PAUSE/SEEK from adjusting the wrong song.
         */
        private fun guestNeedsTrackReconcile(
            actionTrackId: String?,
            localMediaId: String?,
        ): Boolean {
            val expected = actionTrackId?.takeIf { it.isNotEmpty() } ?: return false
            return localMediaId == null || localMediaId != expected
        }

        /**
         * Reload guest playback from [roomState] when it matches [expectedTrackId], else ask the server.
         */
        private fun reconcileGuestToHostTrack(
            expectedTrackId: String,
            wantPlaying: Boolean,
            positionMs: Long,
        ) {
            val snapshot = roomState.value
            val serverTrack = snapshot?.currentTrack
            val queue = snapshot?.queue?.takeIf { it.isNotEmpty() }
            if (serverTrack?.id == expectedTrackId) {
                Timber
                    .tag(TAG)
                    .w("Guest: wrong local track — reloading ${serverTrack.title} from room state")
                applyPlaybackState(
                    currentTrack = serverTrack,
                    isPlaying = wantPlaying,
                    position = positionMs,
                    queue = queue,
                    bypassBuffer = true,
                )
            } else {
                Timber
                    .tag(TAG)
                    .w(
                        "Guest: track mismatch (PLAY/PAUSE expected id=$expectedTrackId, room has ${serverTrack?.id}) — requestSync",
                    )
                client.requestSync()
            }
        }

        private fun handlePlaybackSync(action: PlaybackActionPayload) {
            if (action.revision > 0L && action.revision < lastAppliedRevision) {
                Timber.tag(TAG).d("Ignoring stale playback revision ${action.revision} < $lastAppliedRevision")
                return
            }
            lastAppliedRevision = maxOf(lastAppliedRevision, action.revision)
            val connection = playerConnection
            if (connection == null) {
                Timber.tag(TAG).w("Cannot sync playback - no player connection")
                return
            }
            val player = connection.player

            Timber.tag(TAG).d("Handling playback sync: ${action.action}, position: ${action.position}")

            isSyncing = true

            try {
                when (action.action) {
                    PlaybackActions.PLAY -> {
                        val basePos = action.position ?: 0L
                        val now = SystemClock.elapsedRealtime()
                        val adjustedPos = client.positionAtServerTime(basePos, action.serverTime, isPlaying = true)

                        Timber.tag(TAG).d("Guest: PLAY at position $adjustedPos, currently playing=${player.playWhenReady}")

                        val playTarget = action.trackId?.takeIf { it.isNotEmpty() }
                        if (bufferingTrackId != null &&
                            playTarget != null &&
                            playTarget != bufferingTrackId
                        ) {
                            Timber
                                .tag(TAG)
                                .w("Guest: PLAY targets $playTarget but was buffering $bufferingTrackId — switching")
                            pendingSyncState = null
                            bufferCompleteReceivedForTrack = null
                            bufferingTrackId = null
                            reconcileGuestToHostTrack(playTarget, wantPlaying = true, positionMs = adjustedPos)
                            lastSyncActionTime = now
                            return
                        }

                        if (bufferingTrackId != null) {
                            pendingSyncState =
                                (
                                    pendingSyncState ?: SyncStatePayload(
                                        currentTrack = roomState.value?.currentTrack,
                                        isPlaying = true,
                                        position = basePos,
                                        lastUpdate = action.serverTime ?: 0L,
                                        revision = action.revision,
                                    )
                                ).copy(
                                    isPlaying = true,
                                    position = basePos,
                                    lastUpdate = action.serverTime ?: 0L,
                                    revision = action.revision,
                                )
                            applyPendingSyncIfReady()
                            return
                        }

                        if (guestNeedsTrackReconcile(playTarget, player.currentMediaItem?.mediaId)) {
                            reconcileGuestToHostTrack(playTarget!!, wantPlaying = true, positionMs = adjustedPos)
                            lastSyncActionTime = now
                            return
                        }

                        val posDiff = kotlin.math.abs(player.currentPosition - adjustedPos)
                        val alreadyPlaying = player.playWhenReady
                        if (alreadyPlaying) {
                            if (posDiff >= HARD_SYNC_THRESHOLD_MS) {
                                Timber.tag(TAG).d("Guest: hard sync ${player.currentPosition} -> $adjustedPos (diff ${posDiff}ms)")
                                connection.seekTo(adjustedPos)
                            }
                        } else {
                            if (posDiff > SOFT_SYNC_THRESHOLD_MS) {
                                connection.seekTo(adjustedPos)
                            }
                            connection.play()
                        }
                        if (posDiff > SOFT_SYNC_THRESHOLD_MS && posDiff < HARD_SYNC_THRESHOLD_MS) {
                            startDriftCorrection(connection, playTarget, basePos, action.serverTime)
                        } else {
                            cancelDriftCorrection()
                        }
                        lastSyncActionTime = now
                    }

                    PlaybackActions.PAUSE -> {
                        val pos = action.position ?: 0L
                        val now = SystemClock.elapsedRealtime()
                        cancelDriftCorrection()

                        Timber.tag(TAG).d("Guest: PAUSE at position $pos, currently playing=${player.playWhenReady}")

                        val pauseTarget = action.trackId?.takeIf { it.isNotEmpty() }
                        if (bufferingTrackId != null &&
                            pauseTarget != null &&
                            pauseTarget != bufferingTrackId
                        ) {
                            pendingSyncState = null
                            bufferCompleteReceivedForTrack = null
                            bufferingTrackId = null
                            reconcileGuestToHostTrack(pauseTarget, wantPlaying = false, positionMs = pos)
                            lastSyncActionTime = now
                            return
                        }

                        if (bufferingTrackId != null) {
                            pendingSyncState =
                                (
                                    pendingSyncState ?: SyncStatePayload(
                                        currentTrack = roomState.value?.currentTrack,
                                        isPlaying = false,
                                        position = pos,
                                        lastUpdate = action.serverTime ?: 0L,
                                        revision = action.revision,
                                    )
                                ).copy(
                                    isPlaying = false,
                                    position = pos,
                                    lastUpdate = action.serverTime ?: 0L,
                                    revision = action.revision,
                                )
                            applyPendingSyncIfReady()
                            return
                        }

                        if (guestNeedsTrackReconcile(pauseTarget, player.currentMediaItem?.mediaId)) {
                            reconcileGuestToHostTrack(pauseTarget!!, wantPlaying = false, positionMs = pos)
                            lastSyncActionTime = now
                            return
                        }

                        val posDiff = kotlin.math.abs(player.currentPosition - pos)
                        if (player.playWhenReady) {
                            Timber.tag(TAG).d("Guest: Pausing playback")
                            connection.pause()
                        }

                        if (posDiff > SOFT_SYNC_THRESHOLD_MS) {
                            Timber.tag(TAG).d("Guest: PAUSE seeking ${player.currentPosition} -> $pos (diff ${posDiff}ms)")
                            connection.seekTo(pos)
                        }
                        lastSyncActionTime = now
                    }

                    PlaybackActions.SEEK -> {
                        val pos = action.position ?: 0L
                        val now = SystemClock.elapsedRealtime()
                        val playing = roomState.value?.isPlaying == true
                        val adjustedPos = client.positionAtServerTime(pos, action.serverTime, playing)

                        val seekTarget = action.trackId?.takeIf { it.isNotEmpty() }
                        if (bufferingTrackId != null && (seekTarget == null || seekTarget == bufferingTrackId)) {
                            pendingSyncState =
                                (pendingSyncState ?: SyncStatePayload(
                                    currentTrack = roomState.value?.currentTrack,
                                    isPlaying = playing,
                                    position = pos,
                                    lastUpdate = action.serverTime ?: 0L,
                                    revision = action.revision,
                                )).copy(
                                    position = pos,
                                    lastUpdate = action.serverTime ?: 0L,
                                    revision = action.revision,
                                )
                            return
                        }
                        if (guestNeedsTrackReconcile(seekTarget, player.currentMediaItem?.mediaId)) {
                            if (seekTarget != null) {
                                reconcileGuestToHostTrack(
                                    seekTarget,
                                    wantPlaying = player.playWhenReady,
                                    positionMs = adjustedPos,
                                )
                            } else {
                                client.requestSync()
                            }
                            lastSyncActionTime = now
                            return
                        }

                        cancelDriftCorrection()
                        if (kotlin.math.abs(player.currentPosition - adjustedPos) > SOFT_SYNC_THRESHOLD_MS) {
                            connection.seekTo(adjustedPos)
                        }
                        if (playing) {
                            startDriftCorrection(connection, seekTarget, pos, action.serverTime)
                        }
                        lastSyncActionTime = now
                    }

                    PlaybackActions.CHANGE_TRACK -> {
                        cancelDriftCorrection()
                        action.trackInfo?.let { track ->
                            Timber.tag(TAG).d("Guest: CHANGE_TRACK to ${track.title}, queue size=${action.queue?.size}")

                            // Reset sync debounce timer on track change - this is a fresh sync cycle
                            lastSyncActionTime = 0L

                            // If we have a queue, use it! This is the "smart" sync path.
                            if (action.revision > 0L || !action.queue.isNullOrEmpty()) {
                                val queueTitle = action.queueTitle
                                applyPlaybackState(
                                    currentTrack = track,
                                    isPlaying = false, // Will be updated by subsequent PLAY or pending sync
                                    position = 0,
                                    queue = action.queue.orEmpty(),
                                    queueTitle = queueTitle,
                                )
                            } else {
                                // Fallback to old behavior (network fetch) if no queue provided
                                bufferingTrackId = track.id
                                syncToTrack(track, false, 0)
                            }
                        }
                    }

                    PlaybackActions.SKIP_NEXT -> {
                        Timber.tag(TAG).d("Guest: SKIP_NEXT")
                        connection.seekToNext()
                    }

                    PlaybackActions.SKIP_PREV -> {
                        Timber.tag(TAG).d("Guest: SKIP_PREV")
                        connection.seekToPrevious()
                    }

                    PlaybackActions.QUEUE_ADD -> {
                        val track = action.trackInfo
                        if (action.revision > 0L) {
                            applyCanonicalUpcomingQueue(connection, action.queue.orEmpty(), action.queueTitle)
                        } else if (track == null) {
                            Timber.tag(TAG).w("QUEUE_ADD missing trackInfo")
                        } else {
                            Timber.tag(TAG).d("Guest: QUEUE_ADD ${track.title}, insertNext=${action.insertNext == true}")
                            val actionQueueGeneration = queueSyncGeneration
                            enqueueQueueMutation(actionQueueGeneration) {
                                val result = withContext(Dispatchers.IO) { YouTube.queue(listOf(track.id)) }
                                if (queueSyncGeneration != actionQueueGeneration || playerConnection !== connection) {
                                    return@enqueueQueueMutation
                                }
                                result.onSuccess { list ->
                                    val mediaItem =
                                        list
                                            .firstOrNull()
                                            ?.toMediaMetadata()
                                            ?.copy(
                                                suggestedBy = track.suggestedBy,
                                            )?.toMediaItem()
                                    if (mediaItem != null) {
                                        connection.allowInternalSync = true
                                        try {
                                            if (action.insertNext == true) {
                                                connection.playNext(mediaItem)
                                            } else {
                                                connection.addToQueue(mediaItem)
                                            }
                                        } finally {
                                            connection.allowInternalSync = false
                                        }
                                    } else {
                                        Timber.tag(TAG).w("QUEUE_ADD failed to resolve media item for ${track.id}")
                                    }
                                }.onFailure {
                                    Timber.tag(TAG).e(it, "QUEUE_ADD metadata fetch failed")
                                }
                            }
                        }
                    }

                    PlaybackActions.QUEUE_REMOVE -> {
                        val removeId = action.trackId
                        if (action.revision > 0L) {
                            applyCanonicalUpcomingQueue(connection, action.queue.orEmpty(), action.queueTitle)
                        } else if (removeId.isNullOrEmpty()) {
                            Timber.tag(TAG).w("QUEUE_REMOVE missing trackId")
                        } else {
                            val actionQueueGeneration = queueSyncGeneration
                            enqueueQueueMutation(actionQueueGeneration) {
                                if (playerConnection !== connection) return@enqueueQueueMutation
                                val startIndex = player.currentMediaItemIndex + 1
                                var removeIndex = -1
                                val total = player.mediaItemCount
                                for (i in startIndex until total) {
                                    val id = player.getMediaItemAt(i).mediaId
                                    if (id == removeId) {
                                        removeIndex = i
                                        break
                                    }
                                }
                                if (removeIndex >= 0) {
                                    Timber.tag(TAG).d("Guest: QUEUE_REMOVE index=$removeIndex id=$removeId")
                                    player.removeMediaItem(removeIndex)
                                } else {
                                    Timber.tag(TAG).w("QUEUE_REMOVE id not found in queue: $removeId")
                                }
                            }
                        }
                    }

                    PlaybackActions.QUEUE_CLEAR -> {
                        if (action.revision > 0L) {
                            applyCanonicalUpcomingQueue(connection, action.queue.orEmpty(), action.queueTitle)
                        } else {
                            val actionQueueGeneration = queueSyncGeneration
                            enqueueQueueMutation(actionQueueGeneration) {
                                if (playerConnection !== connection) return@enqueueQueueMutation
                                val currentIndex = player.currentMediaItemIndex
                                val count = player.mediaItemCount
                                val itemsAfter = count - (currentIndex + 1)
                                if (itemsAfter > 0) {
                                    Timber.tag(TAG).d("Guest: QUEUE_CLEAR removing $itemsAfter items after current")
                                    player.removeMediaItems(currentIndex + 1, count - (currentIndex + 1))
                                }
                            }
                        }
                    }

                    PlaybackActions.SET_VOLUME -> {
                        applyHostVolumeIfNeeded(action.volume)
                    }

                    PlaybackActions.SYNC_QUEUE -> {
                        invalidatePendingQueueMutations()
                        val queue = action.queue
                        val queueTitle = action.queueTitle
                        if (queue != null) {
                            Timber.tag(TAG).d("Guest: SYNC_QUEUE size=${queue.size}")
                            if (action.revision > 0L) {
                                applyCanonicalUpcomingQueue(connection, queue, queueTitle)
                                return
                            }
                            // Cancel any pending "smart" sync (e.g. YouTube radio fetch) in favor of this authoritative queue
                            activeSyncJob?.cancel()

                            if (playerConnection !== connection) return
                            val player = connection.player

                            val mediaItems =
                                queue.map { track ->
                                    track.toMediaMetadata().toMediaItem()
                                }

                            val currentId = player.currentMediaItem?.mediaId
                            var newIndex = -1
                            if (currentId != null) {
                                newIndex = mediaItems.indexOfFirst { it.mediaId == currentId }
                            }

                            val currentPos = player.currentPosition
                            val wasPlaying = player.isPlaying

                            connection.allowInternalSync = true
                            try {
                                if (newIndex != -1) {
                                    player.setMediaItems(mediaItems, newIndex, currentPos)
                                } else {
                                    val hostCurrentId = roomState.value?.currentTrack?.id
                                    val hostIdx =
                                        if (hostCurrentId != null) {
                                            mediaItems.indexOfFirst { it.mediaId == hostCurrentId }
                                        } else {
                                            -1
                                        }
                                    val hostPos = roomState.value?.position?.coerceAtLeast(0L) ?: 0L
                                    if (hostIdx >= 0) {
                                        Timber
                                            .tag(TAG)
                                            .d("SYNC_QUEUE: aligning to host current index=$hostIdx from room state")
                                        player.setMediaItems(mediaItems, hostIdx, hostPos)
                                    } else {
                                        Timber
                                            .tag(TAG)
                                            .w("SYNC_QUEUE: host current track not in queue; defaulting to start")
                                        player.setMediaItems(mediaItems, 0, 0L)
                                    }
                                }
                            } finally {
                                connection.allowInternalSync = false
                            }

                            if (wasPlaying && !player.isPlaying) {
                                connection.play()
                            }

                            try {
                                connection.service.queueTitle = queueTitle
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to set queue title during SYNC_QUEUE")
                            }
                        }
                    }
                }
            } finally {
                // Minimal delay to prevent feedback loops
                scope.launch {
                    delay(200)
                    isSyncing = false
                }
            }
        }

        private fun handleSyncState(
            state: SyncStatePayload,
            forceFullState: Boolean = false,
        ) {
            if (state.revision > 0L && state.revision < lastAppliedRevision) {
                Timber.tag(TAG).d("Ignoring stale sync revision ${state.revision} < $lastAppliedRevision")
                return
            }
            lastAppliedRevision = maxOf(lastAppliedRevision, state.revision)
            Timber.tag(TAG).d("handleSyncState: playing=${state.isPlaying}, pos=${state.position}, track=${state.currentTrack?.id}")
            if (!forceFullState && state.currentTrack != null && bufferingTrackId == state.currentTrack.id) {
                pendingSyncState = state
                applyPendingSyncIfReady()
                applyHostVolumeIfNeeded(state.volume)
                return
            }
            val localTrackId = playerConnection?.player?.currentMediaItem?.mediaId
            if (!forceFullState && state.currentTrack != null && localTrackId == state.currentTrack.id && bufferingTrackId == null) {
                handlePlaybackSync(
                    PlaybackActionPayload(
                        action = if (state.isPlaying) PlaybackActions.PLAY else PlaybackActions.PAUSE,
                        trackId = state.currentTrack.id,
                        position = state.position,
                        serverTime = state.lastUpdate,
                        revision = state.revision,
                    ),
                )
                applyHostVolumeIfNeeded(state.volume)
                return
            }
            applyPlaybackState(
                currentTrack = state.currentTrack,
                isPlaying = state.isPlaying,
                position = state.position,
                queue = state.queue,
                effectiveAtServerTime = state.lastUpdate,
                bypassBuffer = true, // Manual sync: bypass buffer
            )
            applyHostVolumeIfNeeded(state.volume)
        }

        private fun applyPlaybackState(
            currentTrack: TrackInfo?,
            isPlaying: Boolean,
            position: Long,
            queue: List<TrackInfo>?,
            queueTitle: String? = null, // New param
            effectiveAtServerTime: Long? = null,
            bypassBuffer: Boolean = false,
        ) {
            invalidatePendingQueueMutations()
            val connection = playerConnection
            if (connection == null) {
                Timber.tag(TAG).w("Cannot apply playback state - no player")
                return
            }
            val player = connection.player
            cancelDriftCorrection()
            val initialPosition = client.positionAtServerTime(position, effectiveAtServerTime, isPlaying)

            Timber
                .tag(
                    TAG,
                ).d("Applying playback state: track=${currentTrack?.id}, pos=$position, queue=${queue?.size}, bypassBuffer=$bypassBuffer")

            // Cancel any pending sync job
            activeSyncJob?.cancel()

            // If no track, just pause and clear/set queue
            if (currentTrack == null) {
                Timber.tag(TAG).d("No track in state, pausing")
                val generation = ++currentTrackGeneration
                scope.launch(Dispatchers.Main) {
                    // Verify we're still on the same track generation (no newer track change arrived)
                    if (currentTrackGeneration != generation) {
                        Timber.tag(TAG).d("Skipping stale track generation: $generation vs current $currentTrackGeneration")
                        return@launch
                    }

                    if (playerConnection !== connection) return@launch
                    isSyncing = true
                    connection.allowInternalSync = true
                    player.shuffleModeEnabled = false
                    if (queue != null && queue.isNotEmpty()) {
                        val mediaItems = queue.map { it.toMediaMetadata().toMediaItem() }
                        player.setMediaItems(mediaItems)
                    } else {
                        player.clearMediaItems()
                    }
                    connection.pause()
                    try {
                        connection.service.queueTitle = queueTitle
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to set queue title for empty state")
                    }
                    connection.allowInternalSync = false
                    isSyncing = false
                }
                return
            }

            bufferingTrackId = currentTrack.id
            val generation = ++currentTrackGeneration

            scope.launch(Dispatchers.Main) {
                // Verify we're still on the same track generation (no newer track change arrived)
                if (currentTrackGeneration != generation) {
                    Timber
                        .tag(
                            TAG,
                        ).d("Skipping stale track generation: $generation vs current $currentTrackGeneration (track ${currentTrack.id})")
                    return@launch
                }

                if (playerConnection !== connection) return@launch
                isSyncing = true
                connection.allowInternalSync = true

                try {
                    player.shuffleModeEnabled = false
                    // Re-verify generation before applying media items (critical section)
                    if (currentTrackGeneration != generation) {
                        Timber.tag(TAG).d("Stale generation detected before setMediaItems: $generation vs $currentTrackGeneration")
                        return@launch
                    }

                    // Apply queue/media (same)
                    val mediaItems =
                        canonicalPlaybackQueue(currentTrack, queue.orEmpty())
                            .map { it.toMediaMetadata().toMediaItem() }
                    player.setMediaItems(mediaItems, 0, initialPosition)

                    connection.seekTo(initialPosition)

                    // Sync queue title
                    try {
                        connection.service.queueTitle = queueTitle ?: "Listen Together"
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to set queue title during applyPlaybackState")
                    }

                    if (bypassBuffer) {
                        // Manual sync/reconnect: apply play/pause immediately, no buffer protocol
                        Timber.tag(TAG).d("Bypass buffer: immediately applying play=$isPlaying at pos=$position")

                        // Wait for player to be ready before seek/play
                        var attempts = 0
                        while (player.playbackState != Player.STATE_READY && attempts < 100) {
                            delay(50)
                            attempts++
                        }
                        if (player.playbackState == Player.STATE_READY) {
                            val readyPosition = client.positionAtServerTime(position, effectiveAtServerTime, isPlaying)
                            Timber.tag(TAG).d("Player ready after ${attempts * 50}ms, seeking to $readyPosition")
                            player.seekTo(readyPosition)
                            if (isPlaying) {
                                connection.play()
                                startDriftCorrection(connection, currentTrack.id, position, effectiveAtServerTime)
                                Timber.tag(TAG).d("Bypass: PLAY issued")
                            } else {
                                connection.pause()
                                Timber.tag(TAG).d("Bypass: PAUSE issued")
                            }
                        } else {
                            Timber.tag(TAG).w("Player not ready after 5s timeout during bypass sync")
                        }

                        // Clear sync state
                        pendingSyncState = null
                        bufferingTrackId = null
                        bufferCompleteReceivedForTrack = null
                    } else {
                        // Normal sync: pause, store pending, send buffer_ready
                        connection.pause()
                        pendingSyncState =
                            SyncStatePayload(
                                currentTrack = currentTrack,
                                isPlaying = isPlaying,
                                position = position,
                                lastUpdate = effectiveAtServerTime ?: 0L,
                            )
                        applyPendingSyncIfReady()
                        client.sendBufferReady(currentTrack.id)
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error applying playback state")
                } finally {
                    connection.allowInternalSync = false
                    delay(200)
                    isSyncing = false
                }
            }
        }

        private fun syncToTrack(
            track: TrackInfo,
            shouldPlay: Boolean,
            position: Long,
        ) {
            Timber.tag(TAG).d("syncToTrack: ${track.title}, play: $shouldPlay, pos: $position")

            // Track which buffer-complete we expect for this load
            bufferingTrackId = track.id
            val generation = currentTrackGeneration

            activeSyncJob?.cancel()
            activeSyncJob =
                scope.launch(Dispatchers.IO) {
                    try {
                        // Check if a newer track change arrived - skip this load if stale
                        if (currentTrackGeneration != generation) {
                            Timber
                                .tag(
                                    TAG,
                                ).d("Skipping stale syncToTrack for ${track.id} (generation $generation vs $currentTrackGeneration)")
                            isSyncing = false
                            return@launch
                        }

                        // Use YouTube API to play the track by ID
                        YouTube
                            .queue(listOf(track.id))
                            .onSuccess { queue ->
                                Timber.tag(TAG).d("Got queue for track ${track.id}")
                                launch(Dispatchers.Main) {
                                    // Final generation check before applying changes
                                    if (currentTrackGeneration != generation) {
                                        Timber
                                            .tag(
                                                TAG,
                                            ).d(
                                                "Skipping stale track application for ${track.id} (generation $generation vs $currentTrackGeneration)",
                                            )
                                        isSyncing = false
                                        return@launch
                                    }

                                    val connection =
                                        playerConnection ?: run {
                                            isSyncing = false
                                            return@launch
                                        }
                                    if (playerConnection !== connection) {
                                        isSyncing = false
                                        return@launch
                                    }
                                    isSyncing = true
                                    // Allow internal sync to bypass playback blocking for guests
                                    connection.allowInternalSync = true
                                    connection.playQueue(
                                        YouTubeQueue(
                                            endpoint = WatchEndpoint(videoId = track.id),
                                            preloadItem = queue.firstOrNull()?.toMediaMetadata(),
                                        ),
                                    )
                                    try {
                                        connection.service.queueTitle = "Listen Together" // Set default title
                                    } catch (e: Exception) {
                                        Timber.tag(TAG).e(e, "Failed to set queue title")
                                    }
                                    connection.allowInternalSync = false

                                    // Wait for player to be ready - monitor actual player state
                                    var waitCount = 0
                                    while (waitCount < 40) { // Max 2 seconds (40 * 50ms)
                                        // Check generation again while waiting
                                        if (currentTrackGeneration != generation) {
                                            Timber
                                                .tag(
                                                    TAG,
                                                ).d("Generation changed while waiting for player ready - aborting sync for ${track.id}")
                                            isSyncing = false
                                            return@launch
                                        }
                                        try {
                                            val player = connection.player
                                            if (player.playbackState == Player.STATE_READY) {
                                                Timber.tag(TAG).d("Player ready after ${waitCount * 50}ms")
                                                break
                                            }
                                        } catch (e: Exception) {
                                            Timber.tag(TAG).e(e, "Error checking player state")
                                            break
                                        }
                                        delay(50)
                                        waitCount++
                                    }

                                    // Do NOT seek here; defer the exact seek until after the server signals buffer-complete
                                    // Ensure paused state before signaling ready
                                    connection.pause()

                                    // Store pending sync (guest will apply seek + play/pause after BufferComplete)
                                    pendingSyncState =
                                        SyncStatePayload(
                                            currentTrack = track,
                                            isPlaying = shouldPlay,
                                            position = position,
                                        lastUpdate = 0L,
                                        )

                                    // Apply immediately if buffer-complete already arrived
                                    applyPendingSyncIfReady()

                                    // Signal we're ready to play
                                    client.sendBufferReady(track.id)
                                    Timber
                                        .tag(
                                            TAG,
                                        ).d("Sent buffer ready for ${track.id}, pending sync stored: pos=$position, play=$shouldPlay")

                                    // Minimal delay before accepting sync commands
                                    delay(100)
                                    isSyncing = false
                                }
                            }.onFailure { e ->
                                Timber.tag(TAG).e(e, "Failed to load track ${track.id}")
                                playerConnection?.allowInternalSync = false
                                isSyncing = false
                            }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error syncing to track")
                        playerConnection?.allowInternalSync = false
                        isSyncing = false
                    }
                }
        }

        // Public API for host actions

        /**
         * Connect to the Listen Together server
         */
        fun connect() {
            Timber.tag(TAG).d("Connecting to server")
            client.connect()
        }

        /**
         * Disconnect from the server
         */
        fun disconnect() {
            Timber.tag(TAG).d("Disconnecting from server")
            cleanup()
            client.disconnect()
        }

        /**
         * Create a new room
         */
        fun createRoom(username: String) {
            Timber.tag(TAG).d("Creating room with username: $username")
            client.createRoom(username)
        }

        /**
         * Join an existing room
         */
        fun joinRoom(
            roomCode: String,
            username: String,
        ) {
            Timber.tag(TAG).d("Joining room $roomCode as $username")
            client.joinRoom(roomCode, username)
        }

        /**
         * Leave the current room
         */
        fun leaveRoom() {
            Timber.tag(TAG).d("Leaving room")
            cleanup()
            client.leaveRoom()
        }

        /**
         * Approve a join request
         */
        fun approveJoin(userId: String) = client.approveJoin(userId)

        /**
         * Reject a join request
         */
        fun rejectJoin(
            userId: String,
            reason: String? = null,
        ) = client.rejectJoin(userId, reason)

        /**
         * Kick a user
         */
        fun kickUser(
            userId: String,
            reason: String? = null,
        ) = client.kickUser(userId, reason)

        /**
         * Block a user permanently (internal list)
         */
        fun blockUser(username: String) = client.blockUser(username)

        /**
         * Unblock a previously blocked user
         */
        fun unblockUser(username: String) = client.unblockUser(username)

        /**
         * Get all currently blocked usernames
         */
        fun getBlockedUsernames(): Set<String> = blockedUsernames.value

        /**
         * Transfer host role to another user
         */
        fun transferHost(newHostId: String) = client.transferHost(newHostId)

        /**
         * Send track change (host only) - called when host changes track
         */
        fun sendTrackChange(metadata: MediaMetadata) {
            if (!isHost || isSyncing) return
            sendTrackChangeInternal(metadata)
        }

        /**
         * Internal track change - bypasses isSyncing check for initial state sync
         */
        private fun sendTrackChangeInternal(metadata: MediaMetadata) {
            if (!isHost) return

            // Use a default duration of 3 minutes if duration is 0 or negative
            val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L

             val trackInfo =
                 TrackInfo(
                     id = metadata.id,
                     title = metadata.title,
                     artist = metadata.artists.joinToArtistString(getArtistSeparator(context)) { it.name },
                    album = metadata.album?.title,
                    duration = durationMs,
                    thumbnail = metadata.thumbnailUrl,
                    suggestedBy = metadata.suggestedBy,
                )

            Timber.tag(TAG).d("Sending track change: ${trackInfo.title}, duration: $durationMs")

            // Also grab current queue to send along with track change
            val currentQueue =
                try {
                    playerConnection?.let { connection ->
                        upcomingQueueItems(connection.queueWindows.value, connection.currentWindowIndex.value)
                            .map { it.toTrackInfo() }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to get current queue")
                    null
                }
            val currentTitle =
                try {
                    playerConnection?.queueTitle?.value
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to get current title")
                    null
                }

            client.sendPlaybackAction(
                PlaybackActions.CHANGE_TRACK,
                queueTitle = currentTitle,
                trackInfo = trackInfo,
                queue = currentQueue,
            )
        }

        private fun startQueueSyncObservation() {
            if (queueObserverJob?.isActive == true) return

            Timber.tag(TAG).d("Starting queue sync observation")
            queueObserverJob =
                scope.launch {
                    playerConnection
                        ?.let { connection ->
                            combine(connection.queueWindows, connection.currentWindowIndex) { windows, currentIndex ->
                                upcomingQueueItems(windows, currentIndex).map { it.toTrackInfo() }
                            }
                        }?.distinctUntilChanged()
                        ?.collectLatest { tracks ->
                            if (!isHost || !isInRoom || isSyncing) return@collectLatest

                            delay(500) // Debounce rapid playlist manipulations

                            Timber.tag(TAG).d("Sending SYNC_QUEUE with ${tracks.size} items")
                            val queueTitle =
                                try {
                                    playerConnection?.queueTitle?.value
                                } catch (e: Exception) {
                                    Timber.tag(TAG).e(e, "Failed to get queue title")
                                    null
                                }
                            client.sendPlaybackAction(
                                PlaybackActions.SYNC_QUEUE,
                                queueTitle = queueTitle,
                                queue = tracks,
                            )
                        }
                }
        }

        private fun startVolumeSyncObservation() {
            if (volumeObserverJob?.isActive == true) return

            Timber.tag(TAG).d("Starting volume sync observation")
            volumeObserverJob =
                scope.launch {
                    playerConnection
                        ?.service
                        ?.playerVolume
                        ?.collectLatest { volume ->
                            if (!isHost || !isInRoom || !syncHostVolumeEnabled.value) return@collectLatest

                            val normalized = volume.coerceIn(0f, 1f)
                            val last = lastSyncedVolume
                            if (last != null && kotlin.math.abs(last - normalized) < 0.01f) return@collectLatest

                            lastSyncedVolume = normalized
                            client.sendPlaybackAction(PlaybackActions.SET_VOLUME, volume = normalized)
                        }
                }
        }

        private fun stopVolumeSyncObservation() {
            volumeObserverJob?.cancel()
            volumeObserverJob = null
            lastSyncedVolume = null
        }

        private fun androidx.media3.common.Timeline.Window.toTrackInfo(): TrackInfo {
            val metadata = mediaItem.metadata ?: return TrackInfo("unknown", "Unknown", "Unknown", "", 0, "")
            val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L
             return TrackInfo(
                 id = metadata.id,
                 title = metadata.title,
                 artist = metadata.artists.joinToArtistString(getArtistSeparator(context)) { it.name },
                album = metadata.album?.title,
                duration = durationMs,
                thumbnail = metadata.thumbnailUrl,
                suggestedBy = metadata.suggestedBy,
            )
        }

        private fun stopQueueSyncObservation() {
            queueObserverJob?.cancel()
            queueObserverJob = null
        }

        private fun TrackInfo.toMediaMetadata(): MediaMetadata =
            MediaMetadata(
                id = id,
                title = title,
                artists = listOf(Artist(id = "", name = artist)),
                album = if (album != null) Album(id = "", title = album) else null,
                duration = (duration / 1000).toInt(),
                thumbnailUrl = thumbnail,
                suggestedBy = suggestedBy,
            )

        /**
         * Request sync state from server (for guests to re-sync)
         * Call this when a guest presses play/pause to sync with host
         */
        fun requestSync() {
            if (!isInRoom || isHost) {
                Timber.tag(TAG).d("requestSync: not applicable (isInRoom=$isInRoom, isHost=$isHost)")
                return
            }
            Timber.tag(TAG).d("Requesting sync from server")
            client.requestSync()
        }

        /**
         * Clear logs
         */
        fun clearLogs() = client.clearLogs()

        // Suggestions API

        /**
         * Suggest the given track to the host (guest only)
         */
        fun suggestTrack(track: TrackInfo) = client.suggestTrack(track)

        /**
         * Approve a suggestion (host only)
         */
        fun approveSuggestion(suggestionId: String) {
            if (!isHost) return
            // Send approval; server will insert-next and broadcast once
            client.approveSuggestion(suggestionId)
        }

        /**
         * Reject a suggestion (host only)
         */
        fun rejectSuggestion(
            suggestionId: String,
            reason: String? = null,
        ) = client.rejectSuggestion(suggestionId, reason)

        /**
         * Force reconnection to server (for manual recovery)
         */
        fun forceReconnect() {
            Timber.tag(TAG).d("Forcing reconnection")
            client.forceReconnect()
        }

        /**
         * Get persisted room code if available
         */
        fun getPersistedRoomCode(): String? = client.getPersistedRoomCode()

        /**
         * Get current session age
         */
        fun getSessionAge(): Long = client.getSessionAge()

        // Heartbeat timer
        private var heartbeatJob: Job? = null

        private fun startHeartbeat() {
            if (heartbeatJob?.isActive == true) return
            heartbeatJob =
                scope.launch {
                    while (heartbeatJob?.isActive == true && isInRoom && isHost) {
                        delay(4000L)
                        playerConnection?.player?.let { player ->
                            if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                                val pos = player.currentPosition
                                val beatTrackId = player.currentMediaItem?.mediaId
                                Timber.tag(TAG).d("Host heartbeat: sending PLAY at pos $pos track=$beatTrackId")
                                client.sendPlaybackAction(
                                    PlaybackActions.PLAY,
                                    trackId = beatTrackId,
                                    position = pos,
                                )
                            }
                        }
                    }
                }
            Timber.tag(TAG).d("Host heartbeat started (4s interval)")
        }

        private fun stopHeartbeat() {
            heartbeatJob?.cancel()
            heartbeatJob = null
            Timber.tag(TAG).d("Host heartbeat stopped")
        }
    }
