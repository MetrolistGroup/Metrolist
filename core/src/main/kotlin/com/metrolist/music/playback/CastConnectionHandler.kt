package com.metrolist.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.Player
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata as AppMediaMetadata
import com.metrolist.music.utils.resize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Manages Google Cast connections and media playback on Cast devices.
 * This class handles the entire Cast lifecycle including:
 * - Device discovery
 * - Session management
 * - Media loading and playback control
 * - Synchronization between local and remote playback
 */
class CastConnectionHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val musicService: MusicService
) {
    private var castContext: CastContext? = null
    private var sessionManager: SessionManager? = null
    private var mediaRouter: MediaRouter? = null
    private var routeSelector: MediaRouteSelector? = null
    private var remoteMediaClient: RemoteMediaClient? = null
    private var castSession: CastSession? = null
    
    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()
    
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()
    
    private val _castDeviceName = MutableStateFlow<String?>(null)
    val castDeviceName: StateFlow<String?> = _castDeviceName.asStateFlow()
    
    private val _castPosition = MutableStateFlow(0L)
    val castPosition: StateFlow<Long> = _castPosition.asStateFlow()
    
    private val _castDuration = MutableStateFlow(0L)
    val castDuration: StateFlow<Long> = _castDuration.asStateFlow()
    
    private val _castIsPlaying = MutableStateFlow(false)
    val castIsPlaying: StateFlow<Boolean> = _castIsPlaying.asStateFlow()
    
    private val _castIsBuffering = MutableStateFlow(false)
    val castIsBuffering: StateFlow<Boolean> = _castIsBuffering.asStateFlow()
    
    private val _castVolume = MutableStateFlow(1.0f)
    val castVolume: StateFlow<Float> = _castVolume.asStateFlow()
    
    private var positionUpdateJob: Job? = null
    private var currentMediaId: String? = null
    private var lastCastItemId: Int = -1
    private var isReloadingQueue: Boolean = false
    
    // Flag to prevent reverse sync when Cast triggers local player update
    var isSyncingFromCast: Boolean = false
        private set
    
    private val remoteMediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            remoteMediaClient?.let { client ->
                val mediaStatus = client.mediaStatus
                val playerState = mediaStatus?.playerState
                // Show as "playing" when playing OR buffering/loading (so pause icon shows during buffering)
                _castIsPlaying.value = playerState == MediaStatus.PLAYER_STATE_PLAYING ||
                                       playerState == MediaStatus.PLAYER_STATE_BUFFERING ||
                                       playerState == MediaStatus.PLAYER_STATE_LOADING
                _castIsBuffering.value = playerState == MediaStatus.PLAYER_STATE_BUFFERING || 
                                         playerState == MediaStatus.PLAYER_STATE_LOADING
                _castDuration.value = client.streamDuration
                
                // Check if the current Cast item changed (user skipped on Cast widget)
                val currentItemId = mediaStatus?.currentItemId ?: -1
                if (currentItemId != -1 && currentItemId != lastCastItemId && lastCastItemId != -1 && !isReloadingQueue && mediaStatus != null) {
                    Timber.d("Cast item changed: $lastCastItemId -> $currentItemId")
                    handleCastItemChanged(mediaStatus)
                }
                lastCastItemId = currentItemId
                
                Timber.d("Cast status updated: playing=${_castIsPlaying.value}, buffering=${_castIsBuffering.value}, itemId=$currentItemId")
            }
        }
        
        override fun onMediaError(error: com.google.android.gms.cast.MediaError) {
            Timber.e("Cast media error: ${error.reason}")
        }
        
        override fun onQueueStatusUpdated() {
            Timber.d("Cast queue status updated")
        }
    }
    
    // Job for resetting sync flag
    private var syncResetJob: Job? = null
    
    /**
     * Handle when Cast changes to a different item (user pressed next/prev on Cast widget)
     * This syncs the local player - we don't reload the queue since the item is already loaded
     */
    private fun handleCastItemChanged(mediaStatus: MediaStatus) {
        val queueItems = mediaStatus.queueItems
        if (queueItems.isEmpty()) return
        val currentItemId = mediaStatus.currentItemId
        val currentIndex = queueItems.indexOfFirst { it.itemId == currentItemId }
        
        if (currentIndex < 0) return
        
        // Get the mediaId from the current Cast item's custom data
        val currentQueueItem = queueItems[currentIndex]
        val customData = currentQueueItem.media?.customData
        val castMediaId = customData?.optString("mediaId")
        
        Timber.d("Cast switched to item: index=$currentIndex, mediaId=$castMediaId, queueSize=${queueItems.size}")
        
        if (castMediaId != null && castMediaId != currentMediaId) {
            currentMediaId = castMediaId
            
            // Cancel any pending sync reset
            syncResetJob?.cancel()
            
            // Set flag immediately to prevent reverse sync
            isSyncingFromCast = true
            
            // Find this song in the local player queue and switch to it
            val player = musicService.player
            val playerItemCount = player.mediaItemCount
            
            // Find the matching item in local player
            for (i in 0 until playerItemCount) {
                val mediaItem = player.getMediaItemAt(i)
                if (mediaItem.mediaId == castMediaId) {
                    Timber.d("Syncing local player to index $i (mediaId=$castMediaId)")
                    
                    // Ensure local player is paused before seeking
                    player.pause()
                    
                    // Move local player to match Cast (just for metadata sync)
                    player.seekTo(i, 0)
                    
                    // Make absolutely sure local player stays paused
                    player.pause()
                    
                    // Extend queue if needed (in background)
                    val itemsAhead = queueItems.size - 1 - currentIndex
                    val itemsBehind = currentIndex
                    
                    if (itemsAhead < 2 || itemsBehind < 2) {
                        scope.launch {
                            val metadata = mediaItem.metadata
                            if (metadata != null) {
                                extendQueueIfNeeded(i, playerItemCount, queueItems)
                            }
                        }
                    }
                    break
                }
            }
            
            // Reset flag after a short delay
            syncResetJob = scope.launch {
                delay(300)
                isSyncingFromCast = false
            }
        }
    }
    
    /**
     * Extend the Cast queue by adding more items at the edges if needed
     * This avoids a full queue reload which causes the widget to refresh
     */
    private suspend fun extendQueueIfNeeded(localPlayerIndex: Int, playerItemCount: Int, currentCastQueue: List<MediaQueueItem>) {
        if (isReloadingQueue) return
        
        val client = remoteMediaClient ?: return
        val currentCastIndex = currentCastQueue.indexOfFirst { 
            it.media?.customData?.optString("mediaId") == currentMediaId 
        }
        if (currentCastIndex < 0) return
        
        isReloadingQueue = true
        
        try {
            // Add more items to the end of queue if needed
            val itemsAhead = currentCastQueue.size - 1 - currentCastIndex
            if (itemsAhead < 2) {
                // Find what songs we need to add
                val lastCastItem = currentCastQueue.lastOrNull()
                val lastMediaId = lastCastItem?.media?.customData?.optString("mediaId")
                
                // Find the index of the last Cast item in local player
                var lastLocalIndex = -1
                for (i in 0 until playerItemCount) {
                    if (musicService.player.getMediaItemAt(i).mediaId == lastMediaId) {
                        lastLocalIndex = i
                        break
                    }
                }
                
                // Add next items from local player
                if (lastLocalIndex >= 0 && lastLocalIndex < playerItemCount - 1) {
                    val itemsToAdd = mutableListOf<MediaQueueItem>()
                    val addCount = minOf(2, playerItemCount - lastLocalIndex - 1)
                    
                    for (i in 1..addCount) {
                        val nextItem = musicService.player.getMediaItemAt(lastLocalIndex + i)
                        nextItem.metadata?.let { metadata ->
                            buildMediaInfo(metadata)?.let { mediaInfo ->
                                itemsToAdd.add(MediaQueueItem.Builder(mediaInfo).build())
                            }
                        }
                    }
                    
                    if (itemsToAdd.isNotEmpty()) {
                        Timber.d("Appending ${itemsToAdd.size} items to Cast queue")
                        withContext(Dispatchers.Main) {
                            client.queueAppendItem(itemsToAdd.first(), null)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to extend Cast queue")
        } finally {
            delay(500)
            isReloadingQueue = false
        }
    }
    
    /**
     * Reload the Cast queue centered on the current item
     * This updates prev/next context after a skip
     * Respects shuffle mode when determining prev/next items
     */
    private fun reloadQueueForCurrentItem(metadata: AppMediaMetadata) {
        if (!_isCasting.value || isReloadingQueue) return
        
        isReloadingQueue = true
        scope.launch {
            try {
                val player = musicService.player
                val currentIndex = player.currentMediaItemIndex
                val shuffleEnabled = player.shuffleModeEnabled
                val timeline = player.currentTimeline
                
                // Build new queue items: up to 2 previous, current, and up to 2 next
                val queueItems = mutableListOf<MediaQueueItem>()
                
                // Get previous items respecting shuffle order
                val prevItems = mutableListOf<androidx.media3.common.MediaItem>()
                if (!timeline.isEmpty) {
                    var prevIdx = currentIndex
                    for (i in 0 until 2) {
                        prevIdx = timeline.getPreviousWindowIndex(prevIdx, Player.REPEAT_MODE_OFF, shuffleEnabled)
                        if (prevIdx == androidx.media3.common.C.INDEX_UNSET) break
                        prevItems.add(0, player.getMediaItemAt(prevIdx))
                    }
                }
                
                // Add previous items
                for (prevItem in prevItems) {
                    prevItem.metadata?.let { prevMetadata ->
                        buildMediaInfo(prevMetadata)?.let { mediaInfo ->
                            queueItems.add(MediaQueueItem.Builder(mediaInfo).build())
                        }
                    }
                }
                val startIndex = queueItems.size // Current item index after previous items
                
                // Add current item
                val currentMediaInfo = buildMediaInfo(metadata)
                if (currentMediaInfo != null) {
                    queueItems.add(MediaQueueItem.Builder(currentMediaInfo).build())
                }
                
                // Get next items respecting shuffle order
                if (!timeline.isEmpty) {
                    var nextIdx = currentIndex
                    for (i in 0 until 2) {
                        nextIdx = timeline.getNextWindowIndex(nextIdx, Player.REPEAT_MODE_OFF, shuffleEnabled)
                        if (nextIdx == androidx.media3.common.C.INDEX_UNSET) break
                        val nextItem = player.getMediaItemAt(nextIdx)
                        nextItem.metadata?.let { nextMetadata ->
                            buildMediaInfo(nextMetadata)?.let { mediaInfo ->
                                queueItems.add(MediaQueueItem.Builder(mediaInfo).build())
                            }
                        }
                    }
                }
                
                if (queueItems.isNotEmpty()) {
                    Timber.d("Reloading Cast queue: ${queueItems.size} items, startIndex=$startIndex, shuffle=$shuffleEnabled")
                    
                    withContext(Dispatchers.Main) {
                        remoteMediaClient?.queueLoad(
                            queueItems.toTypedArray(),
                            startIndex,
                            MediaStatus.REPEAT_MODE_REPEAT_OFF,
                            0L, // Start from beginning since Cast already has position
                            org.json.JSONObject()
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to reload Cast queue")
            } finally {
                // Delay before allowing another reload to prevent rapid reloads
                delay(1000)
                isReloadingQueue = false
            }
        }
    }
    
    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            Timber.d("Cast session starting")
            _isConnecting.value = true
        }
        
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Timber.d("Cast session started: $sessionId")
            _isCasting.value = true
            _isConnecting.value = false
            _castDeviceName.value = session.castDevice?.friendlyName
            castSession = session
            remoteMediaClient = session.remoteMediaClient
            remoteMediaClient?.registerCallback(remoteMediaClientCallback)
            
            // Get initial volume
            _castVolume.value = session.volume.toFloat()
            
            // Start position updates
            startPositionUpdates()
            
            // Load current media
            loadCurrentMedia()
        }
        
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Timber.e("Cast session start failed: $error")
            _isCasting.value = false
            _isConnecting.value = false
        }
        
        override fun onSessionEnding(session: CastSession) {
            Timber.d("Cast session ending")
            // Capture Cast position before session ends
            val castPosition = remoteMediaClient?.approximateStreamPosition ?: _castPosition.value
            if (castPosition > 0) {
                // Seek local player to Cast position so playback can continue from there
                musicService.player.seekTo(castPosition)
                Timber.d("Saved Cast position: $castPosition")
            }
        }
        
        override fun onSessionEnded(session: CastSession, error: Int) {
            Timber.d("Cast session ended: error=$error")
            _isCasting.value = false
            _isConnecting.value = false
            _castDeviceName.value = null
            castSession = null
            
            remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
            remoteMediaClient = null
            
            stopPositionUpdates()
            
            // Pause local playback when disconnecting from Cast
            musicService.player.pause()
        }
        
        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _isConnecting.value = true
        }
        
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            _isCasting.value = true
            _isConnecting.value = false
            _castDeviceName.value = session.castDevice?.friendlyName
            
            remoteMediaClient = session.remoteMediaClient
            remoteMediaClient?.registerCallback(remoteMediaClientCallback)
            
            startPositionUpdates()
        }
        
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            _isConnecting.value = false
        }
        
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }
    
    fun initialize(): Boolean {
        return try {
            castContext = CastContext.getSharedInstance(context)
            sessionManager = castContext?.sessionManager
            mediaRouter = MediaRouter.getInstance(context)
            routeSelector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build()
            
            sessionManager?.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
            
            // Check if already connected
            sessionManager?.currentCastSession?.let { session ->
                _isCasting.value = true
                _castDeviceName.value = session.castDevice?.friendlyName
                remoteMediaClient = session.remoteMediaClient
                remoteMediaClient?.registerCallback(remoteMediaClientCallback)
                startPositionUpdates()
            }
            
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Cast")
            false
        }
    }
    
    fun getAvailableRoutes(): List<MediaRouter.RouteInfo> {
        val router = mediaRouter ?: return emptyList()
        val selector = routeSelector ?: return emptyList()
        
        return router.routes.filter { route ->
            route.matchesSelector(selector) && !route.isDefault
        }
    }
    
    fun connectToRoute(route: MediaRouter.RouteInfo) {
        // Ensure we're initialized before trying to connect
        if (mediaRouter == null) {
            initialize()
        }
        _isConnecting.value = true
        mediaRouter?.selectRoute(route)
    }
    
    fun disconnect() {
        sessionManager?.endCurrentSession(true)
    }
    
    fun loadCurrentMedia() {
        val metadata = musicService.currentMediaMetadata.value ?: return
        loadMediaWithQueue(metadata)
    }
    
    fun loadMedia(metadata: AppMediaMetadata) {
        loadMediaWithQueue(metadata)
    }
    
    /**
     * Build MediaInfo for a single track
     */
    private suspend fun buildMediaInfo(metadata: AppMediaMetadata): MediaInfo? {
        val streamUrl = musicService.getStreamUrl(metadata.id) ?: return null
        
        val castMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, metadata.title)
            putString(MediaMetadata.KEY_ARTIST, metadata.artists.joinToString(", ") { it.name })
            metadata.album?.title?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
            metadata.thumbnailUrl?.let { thumbUrl ->
                // Use high quality thumbnail (1080x1080) for Cast display
                val highQualityUrl = thumbUrl.resize(1080, 1080)
                addImage(WebImage(Uri.parse(highQualityUrl)))
            }
        }
        
        return MediaInfo.Builder(streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("audio/mp4")
            .setMetadata(castMetadata)
            .setCustomData(org.json.JSONObject().put("mediaId", metadata.id))
            .build()
    }
    
    /**
     * Load media with queue context to enable skip prev/next buttons on Cast widget
     * Loads up to 5 items: 2 previous, current, and 2 next songs for smoother transitions
     * Respects shuffle mode when determining prev/next items
     */
    private fun loadMediaWithQueue(metadata: AppMediaMetadata) {
        if (!_isCasting.value) return
        
        isReloadingQueue = true // Prevent sync logic from triggering during load
        scope.launch {
            try {
                currentMediaId = metadata.id
                _castIsBuffering.value = true
                lastCastItemId = -1 // Reset to prevent false change detection
                
                val player = musicService.player
                val currentIndex = player.currentMediaItemIndex
                val mediaItemCount = player.mediaItemCount
                val shuffleEnabled = player.shuffleModeEnabled
                val timeline = player.currentTimeline
                
                // Build queue items: up to 2 previous, current, and up to 2 next songs
                val queueItems = mutableListOf<MediaQueueItem>()
                
                // Get previous items respecting shuffle order
                val prevItems = mutableListOf<androidx.media3.common.MediaItem>()
                if (!timeline.isEmpty) {
                    var prevIdx = currentIndex
                    for (i in 0 until 2) {
                        prevIdx = timeline.getPreviousWindowIndex(prevIdx, Player.REPEAT_MODE_OFF, shuffleEnabled)
                        if (prevIdx == androidx.media3.common.C.INDEX_UNSET) break
                        prevItems.add(0, player.getMediaItemAt(prevIdx)) // Add at beginning to maintain order
                    }
                }
                
                // Add previous items
                for (prevItem in prevItems) {
                    prevItem.metadata?.let { prevMetadata ->
                        buildMediaInfo(prevMetadata)?.let { mediaInfo ->
                            queueItems.add(MediaQueueItem.Builder(mediaInfo).build())
                        }
                    }
                }
                val startIndex = queueItems.size // Current item index after previous items
                
                // Add current item
                val currentMediaInfo = buildMediaInfo(metadata)
                if (currentMediaInfo == null) {
                    Timber.e("Failed to get stream URL for Cast")
                    _castIsBuffering.value = false
                    return@launch
                }
                queueItems.add(MediaQueueItem.Builder(currentMediaInfo).build())
                
                // Get next items respecting shuffle order
                if (!timeline.isEmpty) {
                    var nextIdx = currentIndex
                    for (i in 0 until 2) {
                        nextIdx = timeline.getNextWindowIndex(nextIdx, Player.REPEAT_MODE_OFF, shuffleEnabled)
                        if (nextIdx == androidx.media3.common.C.INDEX_UNSET) break
                        val nextItem = player.getMediaItemAt(nextIdx)
                        nextItem.metadata?.let { nextMetadata ->
                            buildMediaInfo(nextMetadata)?.let { mediaInfo ->
                                queueItems.add(MediaQueueItem.Builder(mediaInfo).build())
                            }
                        }
                    }
                }
                
                // Get current position from local player if same song
                val startPosition = if (player.currentMediaItem?.mediaId == metadata.id) {
                    player.currentPosition
                } else {
                    0L
                }
                
                Timber.d("Loading Cast queue: ${queueItems.size} items, startIndex=$startIndex, shuffle=$shuffleEnabled")
                
                withContext(Dispatchers.Main) {
                    val client = remoteMediaClient ?: return@withContext
                    
                    // Load the queue
                    client.queueLoad(
                        queueItems.toTypedArray(),
                        startIndex,
                        MediaStatus.REPEAT_MODE_REPEAT_OFF,
                        startPosition,
                        org.json.JSONObject()
                    )
                    
                    // Pause local playback
                    musicService.player.pause()
                }
                
                Timber.d("Loaded media on Cast: ${metadata.title}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load media on Cast")
                _castIsBuffering.value = false
            } finally {
                // Allow sync logic after a delay
                delay(1500)
                isReloadingQueue = false
            }
        }
    }
    
    fun play() {
        remoteMediaClient?.play()
    }
    
    fun pause() {
        remoteMediaClient?.pause()
    }
    
    fun seekTo(position: Long) {
        val seekOptions = MediaSeekOptions.Builder()
            .setPosition(position)
            .build()
        remoteMediaClient?.seek(seekOptions)
    }
    
    /**
     * Set the Cast device volume (0.0 to 1.0)
     */
    fun setVolume(volume: Float) {
        try {
            val clampedVolume = volume.coerceIn(0f, 1f)
            castSession?.volume = clampedVolume.toDouble()
            _castVolume.value = clampedVolume
            Timber.d("Set Cast volume to $clampedVolume")
        } catch (e: Exception) {
            Timber.e(e, "Failed to set Cast volume")
        }
    }
    
    /**
     * Try to navigate to a media item if it's already in the Cast queue
     * Returns true if successful, false if the item isn't in the queue
     */
    fun navigateToMediaIfInQueue(mediaId: String): Boolean {
        val client = remoteMediaClient ?: return false
        val mediaStatus = client.mediaStatus ?: return false
        val queueItems = mediaStatus.queueItems
        if (queueItems.isEmpty()) return false
        
        // Find the item in Cast queue
        val targetIndex = queueItems.indexOfFirst { 
            it.media?.customData?.optString("mediaId") == mediaId 
        }
        
        if (targetIndex < 0) {
            Timber.d("Media $mediaId not found in Cast queue")
            return false
        }
        
        val currentItemId = mediaStatus.currentItemId
        val currentIndex = queueItems.indexOfFirst { it.itemId == currentItemId }
        
        if (targetIndex == currentIndex) {
            // Already on this item - ensure local player is paused
            currentMediaId = mediaId
            musicService.player.pause()
            return true
        }
        
        // Navigate to the item on Cast
        val targetItem = queueItems[targetIndex]
        Timber.d("Navigating Cast to item at index $targetIndex (mediaId=$mediaId)")
        
        // Set flag to prevent reverse sync loop
        isSyncingFromCast = true
        
        // Update local player to match (for UI sync) - find the item in local queue
        val player = musicService.player
        for (i in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == mediaId) {
                player.seekTo(i, 0)
                break
            }
        }
        player.pause()
        
        // Navigate Cast
        client.queueJumpToItem(targetItem.itemId, org.json.JSONObject())
        currentMediaId = mediaId
        
        // Reset sync flag after a short delay
        scope.launch {
            delay(300)
            isSyncingFromCast = false
        }
        
        return true
    }
    
    fun skipToNext() {
        // First try to use Cast queue
        val client = remoteMediaClient
        val mediaStatus = client?.mediaStatus
        if (mediaStatus != null && mediaStatus.queueItemCount > 0) {
            // Check if there's a next item in Cast queue
            val currentItemId = mediaStatus.currentItemId
            val queueItems = mediaStatus.queueItems
            val currentIndex = queueItems.indexOfFirst { it.itemId == currentItemId }
            if (currentIndex >= 0 && currentIndex < queueItems.size - 1) {
                // There's a next item in Cast queue, use it
                client.queueNext(org.json.JSONObject())
                // Ensure local player stays paused
                musicService.player.pause()
                return
            }
        }
        
        // Fall back to loading from MusicService queue
        val player = musicService.player
        if (player.hasNextMediaItem()) {
            // Pause first, then seek
            player.pause()
            player.seekToNextMediaItem()
            // The player listener will handle loading the new media to Cast
        }
    }
    
    fun skipToPrevious() {
        // First try to use Cast queue
        val client = remoteMediaClient
        val mediaStatus = client?.mediaStatus
        if (mediaStatus != null && mediaStatus.queueItemCount > 0) {
            // Check if there's a previous item in Cast queue
            val currentItemId = mediaStatus.currentItemId
            val queueItems = mediaStatus.queueItems
            val currentIndex = queueItems.indexOfFirst { it.itemId == currentItemId }
            if (currentIndex > 0) {
                // There's a previous item in Cast queue, use it
                client.queuePrev(org.json.JSONObject())
                // Ensure local player stays paused
                musicService.player.pause()
                return
            }
        }
        
        // Fall back to loading from MusicService queue
        val player = musicService.player
        if (player.hasPreviousMediaItem()) {
            // Pause first, then seek
            player.pause()
            player.seekToPreviousMediaItem()
        }
    }
    
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive && _isCasting.value) {
                remoteMediaClient?.let { client ->
                    _castPosition.value = client.approximateStreamPosition
                }
                delay(500)
            }
        }
    }
    
    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }
    
    fun release() {
        stopPositionUpdates()
        remoteMediaClient?.unregisterCallback(remoteMediaClientCallback)
        sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
    }
}
