package com.metrolist.android.viewmodels

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.ui.theme.extractThemeColor
import com.metrolist.android.utils.artworkUiVisible
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.artworkColors
import com.metrolist.shared.utils.collectLatestWhileVisible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@UnstableApi
class PlaybackViewModel(
    application: Application,
    private val imageLoader: ImageLoader,
    val playerConnection: PlayerConnection,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "PlaybackViewModel"
    }

    private val initStartTime: Long = SystemClock.elapsedRealtime()

    init {
        Timber.tag(TAG).d("PlayerConnection ready elapsed=%dms", SystemClock.elapsedRealtime() - initStartTime)
    }

    val isPlaying: StateFlow<Boolean> = playerConnection.isPlaying
    val currentSong: StateFlow<SongItem?> = playerConnection.currentSong
    val currentPosition: StateFlow<Long> = playerConnection.currentPosition
    val duration: StateFlow<Long> = playerConnection.duration
    val progress: StateFlow<Float> = playerConnection.progress

    val canSkipPrevious: StateFlow<Boolean> = playerConnection.canSkipPrevious
    val canSkipNext: StateFlow<Boolean> = playerConnection.canSkipNext

    val repeatMode: StateFlow<Int> = playerConnection.repeatMode
    val shuffleModeEnabled: StateFlow<Boolean> = playerConnection.shuffleModeEnabled

    val playbackState: StateFlow<PlayerConnection.PlaybackState> = playerConnection.playbackState

    private val _seedColor = MutableStateFlow<Color?>(null)
    val seedColor: StateFlow<Color?> = _seedColor.asStateFlow()
    private var seedArtworkUrl: String? = null

    val hasMedia: StateFlow<Boolean> =
        currentSong
            .map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            combine(
                currentSong.map { it?.thumbnail }.distinctUntilChanged(),
                snapshotFlow { Settings.shared.materialYouEnabled && Settings.shared.dynamicTheme },
            ) { thumbnail, enabled -> thumbnail.takeIf { enabled } }
                .distinctUntilChanged()
                .collectLatestWhileVisible(artworkUiVisible(application)) { url ->
                    if (url != null) {
                        extractColor(url)
                    } else {
                        seedArtworkUrl = null
                        _seedColor.value = null
                    }
                }
        }
    }

    private suspend fun extractColor(url: String) {
        if (seedArtworkUrl == url) return
        try {
            val request =
                ImageRequest
                    .Builder(getApplication())
                    .artworkColors(url)
                    .allowHardware(false)
                    .build()
            val result = imageLoader.execute(request)
            _seedColor.value =
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap()
                    withContext(Dispatchers.Default) { bitmap.extractThemeColor() }
                } else {
                    null
                }
            seedArtworkUrl = url.takeIf { result is SuccessResult }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            seedArtworkUrl = null
            _seedColor.value = null
        }
    }

    fun togglePlayPause() = playerConnection.togglePlayPause()

    fun seekTo(position: Long) = playerConnection.seekTo(position)

    fun skipToNext() = playerConnection.skipToNext()

    fun skipToPrevious() = playerConnection.skipToPrevious()

    fun setRepeatMode(mode: Int) = playerConnection.setRepeatMode(mode)

    fun toggleShuffle() = playerConnection.toggleShuffle()

    fun cycleRepeatMode() {
        val nextMode =
            when (repeatMode.value) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
        setRepeatMode(nextMode)
    }
}
