/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Timeline
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.getCurrentQueueIndex
import com.metrolist.music.extensions.getQueueWindows
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.togglePlayPause
import com.metrolist.music.playback.MusicService.MusicBinder
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    context: Context,
    binder: MusicBinder,
    val database: MusicDatabase,
    scope: CoroutineScope,
) : Player.Listener {
    private companion object {
        private const val TAG = "PlayerConnection"
        private const val PLAYER_INIT_TIMEOUT_MS = 5000L // 5 second timeout for player initialization
    }

    val service = binder.service
    private val playerReadinessFlow = service.isPlayerReady
    
    /**
     * Safe player accessor checks readiness & handles errors.
     * Should be used by all player access within this class.
     */
    private fun getPlayerSafe(): Player {
        return try {
            if (!playerReadinessFlow.value) {
                Log.w(TAG, "Player accessed before service initialization complete; returning best-effort reference")
            }
            service.player
        } catch (e: UninitializedPropertyAccessException) {
            Log.e(TAG, "Fatal: player property accessed but not initialized", e)
            throw IllegalStateException("MusicService.player not initialized; possible race condition in service startup", e)
        }
    }

    /**
     * Public accessor for player. Throws if player not ready.
     * Callers should check [isPlayerInitialized] before calling, or handle exceptions.
     */
    val player: Player
        get() = getPlayerSafe()

    /** Tracks whether player initialization completed successfully */
    private val isPlayerInitialized = MutableStateFlow(service.isPlayerReady.value)

    val playbackState: MutableStateFlow<Int>
    private val playWhenReady: MutableStateFlow<Boolean>
    val isPlaying: kotlinx.coroutines.flow.StateFlow<Boolean>
    
    init {
        Log.d(TAG, "PlayerConnection init: playerReady=${playerReadinessFlow.value}")
        
        try {
            // Initialize with safe player access
            val initialPlayer = getPlayerSafe()
            
            playbackState = MutableStateFlow(initialPlayer.playbackState)
            playWhenReady = MutableStateFlow(initialPlayer.playWhenReady)
            isPlaying =
                combine(playbackState, playWhenReady) { playbackState, playWhenReady ->
                    playWhenReady && playbackState != STATE_ENDED
                }.stateIn(
                    scope,
                    SharingStarted.Lazily,
                    initialPlayer.playWhenReady && initialPlayer.playbackState != STATE_ENDED
                )
            
            // Track service readiness changes in background.
            scope.launch {
                playerReadinessFlow.collect { ready ->
                    isPlayerInitialized.value = ready
                    if (ready) {
                        Log.d(TAG, "Service player initialization detected by PlayerConnection")
                    }
                }
            }
            
            Log.d(TAG, "PlayerConnection state flows initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during PlayerConnection initialization", e)
            // Initialize with safe defaults even on error, allowing retries
            playbackState = MutableStateFlow(Player.STATE_IDLE)
            playWhenReady = MutableStateFlow(false)
            isPlaying = combine(playbackState, playWhenReady) { playbackState, playWhenReady ->
                playWhenReady && playbackState != STATE_ENDED
            }.stateIn(
                scope,
                SharingStarted.Lazily,
                false
            )
            throw e // Re-throw for caller to handle
        }
    }
    
    // Effective playing state, considers Cast when active
    val isEffectivelyPlaying = combine(
        isPlaying,
        service.castConnectionHandler?.isCasting ?: MutableStateFlow(false),
        service.castConnectionHandler?.castIsPlaying ?: MutableStateFlow(false)
    ) { localPlaying, isCasting, castPlaying ->
        if (isCasting) castPlaying else localPlaying
    }.stateIn(
        scope,
        SharingStarted.Lazily,
        player.playbackState != STATE_ENDED && player.playWhenReady
    )
    
    val mediaMetadata = MutableStateFlow(player.currentMetadata)
    val currentSong =
        mediaMetadata.flatMapLatest {
            database.song(it?.id)
        }
    val currentLyrics = mediaMetadata.flatMapLatest { mediaMetadata ->
        database.lyrics(mediaMetadata?.id)
    }
    val currentFormat =
        mediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    val queueTitle = MutableStateFlow<String?>(null)
    val queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())
    val currentMediaItemIndex = MutableStateFlow(-1)
    val currentWindowIndex = MutableStateFlow(-1)

    val shuffleModeEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(REPEAT_MODE_OFF)

    val canSkipPrevious = MutableStateFlow(true)
    val canSkipNext = MutableStateFlow(true)

    val error = MutableStateFlow<PlaybackException?>(null)
    val waitingForNetworkConnection = service.waitingForNetworkConnection

    init {
        try {
            // Register listener with the player for state updates
            player.addListener(this)
            Log.d(TAG, "Player listener registered successfully")

            // Initialize state flows from current player state
            // These will be kept in sync via listener callbacks
            playbackState.value = player.playbackState
            playWhenReady.value = player.playWhenReady
            mediaMetadata.value = player.currentMetadata
            queueTitle.value = service.queueTitle
            queueWindows.value = player.getQueueWindows()
            currentWindowIndex.value = player.getCurrentQueueIndex()
            currentMediaItemIndex.value = player.currentMediaItemIndex
            shuffleModeEnabled.value = player.shuffleModeEnabled
            repeatMode.value = player.repeatMode
            
            Log.d(TAG, "PlayerConnection fully initialized with player state")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PlayerConnection listener or state", e)
            // Propagate the error so MainActivity can retry
            throw e
        }
    }

    fun playQueue(queue: Queue) {
        if (!playerReadinessFlow.value) {
            Log.w(TAG, "playQueue called before player ready; delegating to service")
        }
        try {
            service.playQueue(queue)
        } catch (e: Exception) {
            Log.e(TAG, "Error in playQueue", e)
            throw e
        }
    }

    fun startRadioSeamlessly() {
        if (!playerReadinessFlow.value) {
            Log.w(TAG, "startRadioSeamlessly called before player ready; delegating to service")
        }
        try {
            service.startRadioSeamlessly()
        } catch (e: Exception) {
            Log.e(TAG, "Error in startRadioSeamlessly", e)
            throw e
        }
    }

    fun playNext(item: MediaItem) = playNext(listOf(item))

    fun playNext(items: List<MediaItem>) {
        try {
            service.playNext(items)
        } catch (e: Exception) {
            Log.e(TAG, "Error in playNext", e)
            throw e
        }
    }

    fun addToQueue(item: MediaItem) = addToQueue(listOf(item))

    fun addToQueue(items: List<MediaItem>) {
        try {
            service.addToQueue(items)
        } catch (e: Exception) {
            Log.e(TAG, "Error in addToQueue", e)
            throw e
        }
    }

    fun toggleLike() {
        try {
            service.toggleLike()
        } catch (e: Exception) {
            Log.e(TAG, "Error in toggleLike", e)
        }
    }

    fun toggleLibrary() {
        try {
            service.toggleLibrary()
        } catch (e: Exception) {
            Log.e(TAG, "Error in toggleLibrary", e)
        }
    }

    /**
     * Toggle play/pause - handles Cast when active
     */
    fun togglePlayPause() {
        try {
            val castHandler = service.castConnectionHandler
            if (castHandler?.isCasting?.value == true) {
                if (castHandler.castIsPlaying.value) {
                    castHandler.pause()
                } else {
                    castHandler.play()
                }
            } else {
                player.togglePlayPause()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in togglePlayPause", e)
        }
    }
    
    /**
     * Start playback - handles Cast when active
     */
    fun play() {
        try {
            val castHandler = service.castConnectionHandler
            if (castHandler?.isCasting?.value == true) {
                castHandler.play()
            } else {
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                player.playWhenReady = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in play", e)
        }
    }
    
    /**
     * Pause playback - handles Cast when active
     */
    fun pause() {
        try {
            val castHandler = service.castConnectionHandler
            if (castHandler?.isCasting?.value == true) {
                castHandler.pause()
            } else {
                player.playWhenReady = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in pause", e)
        }
    }

    /**
     * Seek to position - handles Cast when active
     */
    fun seekTo(position: Long) {
        try {
            val castHandler = service.castConnectionHandler
            if (castHandler?.isCasting?.value == true) {
                castHandler.seekTo(position)
            } else {
                player.seekTo(position)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in seekTo", e)
        }
    }

    fun seekToNext() {
        try {
            // When casting, use Cast skip instead of local player
            val castHandler = service.castConnectionHandler
            if (castHandler?.isCasting?.value == true) {
                castHandler.skipToNext()
                return
            }
            player.seekToNext()
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Error in seekToNext", e)
        }
    }

    fun seekToPrevious() {
        try {
            // When casting, use Cast skip instead of local player
            val castHandler = service.castConnectionHandler
            if (castHandler?.isCasting?.value == true) {
                castHandler.skipToPrevious()
                return
            }
            player.seekToPrevious()
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Error in seekToPrevious", e)
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        playbackState.value = state
        error.value = player.playerError
    }

    override fun onPlayWhenReadyChanged(
        newPlayWhenReady: Boolean,
        reason: Int,
    ) {
        playWhenReady.value = newPlayWhenReady
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        mediaMetadata.value = mediaItem?.metadata
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onTimelineChanged(
        timeline: Timeline,
        reason: Int,
    ) {
        queueWindows.value = player.getQueueWindows()
        queueTitle.value = service.queueTitle
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = enabled
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onRepeatModeChanged(mode: Int) {
        repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        if (playbackError != null) {
            reportException(playbackError)
        }
        error.value = playbackError
    }

    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window =
                player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) ||
                    !window.isLive ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive &&
                    window.isDynamic ||
                    player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    fun dispose() {
        try {
            player.removeListener(this)
            Log.d(TAG, "PlayerConnection disposed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during PlayerConnection disposal", e)
        }
    }
}
