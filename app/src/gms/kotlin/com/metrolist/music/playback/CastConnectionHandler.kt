package com.metrolist.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.metrolist.music.extensions.metadata
import com.metrolist.music.ui.utils.resize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt
import com.metrolist.music.models.MediaMetadata as AppMediaMetadata

@UnstableApi
class CastConnectionHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val musicService: MusicService,
) {
    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

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

    private val _castVolume = MutableStateFlow(1f)
    val castVolume: StateFlow<Float> = _castVolume.asStateFlow()

    @Volatile
    var isSyncingFromCast = false
        private set

    private var castContext: CastContext? = null
    private var castPlayer: RemoteCastPlayer? = null
    private var positionJob: Job? = null
    private var loadJob: Job? = null
    private var extensionJob: Job? = null
    private var syncResetJob: Job? = null

    private val playerListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = updatePlayerState()

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) = updatePlayerState()

            override fun onDeviceVolumeChanged(
                volume: Int,
                muted: Boolean,
            ) = updateVolume()

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                mediaItem?.mediaId?.let(::syncLocalPlayer)
                appendQueueIfNeeded()
                updatePlayerState()
            }
        }

    private val sessionListener =
        object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                _isCasting.value = true
                _castDeviceName.value =
                    castContext
                        ?.sessionManager
                        ?.currentCastSession
                        ?.castDevice
                        ?.friendlyName
                musicService.player.pause()
                startPositionUpdates()
                updatePlayerState()
                updateVolume()

                val player = castPlayer ?: return
                if (player.mediaItemCount == 0) {
                    loadCurrentMedia()
                } else {
                    player.currentMediaItem?.mediaId?.let(::syncLocalPlayer)
                }
            }

            override fun onCastSessionUnavailable() {
                val player = castPlayer
                if (player != null && player.currentPosition > 0) {
                    musicService.player.seekTo(player.currentPosition)
                }
                _isCasting.value = false
                _castDeviceName.value = null
                _castIsPlaying.value = false
                _castIsBuffering.value = false
                stopPositionUpdates()
                musicService.player.pause()
            }
        }

    fun initialize(): Boolean {
        if (castPlayer != null) return true
        return runCatching {
            castContext = CastContext.getSharedInstance(context)
            castPlayer =
                RemoteCastPlayer
                    .Builder(context)
                    .build()
                    .also { player ->
                        player.addListener(playerListener)
                        player.setSessionAvailabilityListener(sessionListener)
                        if (player.isCastSessionAvailable) {
                            sessionListener.onCastSessionAvailable()
                        }
                    }
            true
        }.getOrElse { error ->
            Timber.e(error, "Failed to initialize Cast")
            false
        }
    }

    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun loadCurrentMedia() {
        musicService.currentMediaMetadata.value?.let(::loadMedia)
    }

    fun loadMedia(metadata: AppMediaMetadata) {
        if (!_isCasting.value) return
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val localPlayer = musicService.player
                val centerIndex = localPlayer.indexOfMediaId(metadata.id)
                if (centerIndex == C.INDEX_UNSET) return@launch

                val indices = queueIndices(centerIndex)
                val items = indices.mapNotNull { resolvedMediaItem(it) }
                val startIndex = indices.takeWhile { it != centerIndex }.size
                if (items.size != indices.size || startIndex !in items.indices) {
                    Timber.w("Unable to resolve the Cast queue for ${metadata.id}")
                    return@launch
                }

                val startPosition =
                    if (localPlayer.currentMediaItemIndex == centerIndex) {
                        localPlayer.currentPosition
                    } else {
                        0L
                    }
                castPlayer?.apply {
                    setMediaItems(items, startIndex, startPosition)
                    prepare()
                    play()
                }
                localPlayer.pause()
            }
    }

    fun play() {
        castPlayer?.play()
    }

    fun pause() {
        castPlayer?.pause()
    }

    fun seekTo(position: Long) {
        castPlayer?.seekTo(position)
    }

    fun setVolume(volume: Float) {
        val player = castPlayer ?: return
        val maxVolume = player.deviceInfo.maxVolume.takeIf { it > 0 } ?: return
        player.setDeviceVolume((volume.coerceIn(0f, 1f) * maxVolume).roundToInt(), 0)
    }

    fun navigateToMediaIfInQueue(mediaId: String): Boolean {
        val player = castPlayer ?: return false
        repeat(player.mediaItemCount) { index ->
            if (player.getMediaItemAt(index).mediaId == mediaId) {
                if (index != player.currentMediaItemIndex) player.seekTo(index, 0L)
                musicService.player.pause()
                return true
            }
        }
        return false
    }

    fun skipToNext() {
        val player = castPlayer ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (musicService.player.hasNextMediaItem()) {
            musicService.player.pause()
            musicService.player.seekToNextMediaItem()
        }
    }

    fun skipToPrevious() {
        val player = castPlayer ?: return
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else if (musicService.player.hasPreviousMediaItem()) {
            musicService.player.pause()
            musicService.player.seekToPreviousMediaItem()
        }
    }

    private fun syncLocalPlayer(mediaId: String) {
        if (!_isCasting.value) return
        val localPlayer = musicService.player
        val index = localPlayer.indexOfMediaId(mediaId)
        if (index == C.INDEX_UNSET) return

        localPlayer.pause()
        if (index == localPlayer.currentMediaItemIndex) return

        syncResetJob?.cancel()
        isSyncingFromCast = true
        localPlayer.seekTo(index, 0L)
        localPlayer.pause()
        syncResetJob =
            scope.launch {
                delay(300)
                isSyncingFromCast = false
            }
    }

    private fun appendQueueIfNeeded() {
        val player = castPlayer ?: return
        if (extensionJob?.isActive == true || player.currentMediaItemIndex < player.mediaItemCount - 2) return

        extensionJob =
            scope.launch {
                if (player.mediaItemCount == 0) return@launch
                val lastMediaId = player.getMediaItemAt(player.mediaItemCount - 1).mediaId
                val localPlayer = musicService.player
                var index = localPlayer.indexOfMediaId(lastMediaId)
                if (index == C.INDEX_UNSET || localPlayer.currentTimeline.isEmpty) return@launch

                val indices = mutableListOf<Int>()
                while (indices.size < 2) {
                    index =
                        localPlayer.currentTimeline.getNextWindowIndex(
                            index,
                            Player.REPEAT_MODE_OFF,
                            localPlayer.shuffleModeEnabled,
                        )
                    if (index == C.INDEX_UNSET) break
                    indices += index
                }
                val items = indices.mapNotNull { resolvedMediaItem(it) }
                if (items.isNotEmpty()) player.addMediaItems(items)
            }
    }

    private fun queueIndices(centerIndex: Int): List<Int> {
        val player = musicService.player
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return listOf(centerIndex)

        val previous = mutableListOf<Int>()
        var index = centerIndex
        while (previous.size < 2) {
            index = timeline.getPreviousWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            previous.add(0, index)
        }

        val next = mutableListOf<Int>()
        index = centerIndex
        while (next.size < 2) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            next += index
        }
        return previous + centerIndex + next
    }

    private suspend fun resolvedMediaItem(index: Int): MediaItem? {
        val item = musicService.player.getMediaItemAt(index)
        val metadata = item.metadata ?: return null
        val streamUrl = musicService.getStreamUrl(metadata.id) ?: return null
        val castMetadata =
            item.mediaMetadata
                .buildUpon()
                .setTitle(metadata.title)
                .setArtist(metadata.artists.joinToString(", ") { it.name })
                .setAlbumTitle(metadata.album?.title)
                .setArtworkUri(metadata.thumbnailUrl?.resize(1080, 1080)?.let(Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        return item
            .buildUpon()
            .setUri(streamUrl)
            .setMimeType(MimeTypes.AUDIO_MP4)
            .setMediaMetadata(castMetadata)
            .build()
    }

    private fun Player.indexOfMediaId(mediaId: String): Int {
        repeat(mediaItemCount) { index ->
            if (getMediaItemAt(index).mediaId == mediaId) return index
        }
        return C.INDEX_UNSET
    }

    private fun updatePlayerState() {
        val player = castPlayer ?: return
        _castIsBuffering.value = player.playbackState == Player.STATE_BUFFERING
        _castIsPlaying.value =
            player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        _castDuration.value = player.duration.takeUnless { it == C.TIME_UNSET } ?: 0L
    }

    private fun updateVolume() {
        val player = castPlayer ?: return
        val maxVolume = player.deviceInfo.maxVolume
        if (maxVolume > 0) _castVolume.value = player.deviceVolume.toFloat() / maxVolume
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob =
            scope.launch {
                while (isActive && _isCasting.value) {
                    castPlayer?.let { player ->
                        _castPosition.value = player.currentPosition
                        _castDuration.value = player.duration.takeUnless { it == C.TIME_UNSET } ?: 0L
                    }
                    delay(500)
                }
            }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    fun release() {
        loadJob?.cancel()
        extensionJob?.cancel()
        syncResetJob?.cancel()
        stopPositionUpdates()
        castPlayer?.removeListener(playerListener)
        castPlayer?.release()
        castPlayer = null
    }
}
