package com.metrolist.android.playback

import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ListeningHistoryClock
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AndroidListeningHistory(
    private val player: AndroidPlaybackPlayer,
    private val scope: CoroutineScope,
    private val recordPlay: suspend (SongItem, Long) -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val writerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var pendingWrite: Job? = null
    private var listeningSongId: String? = null
    private var lastListeningSong: SongItem? = null
    private val listeningClock = ListeningHistoryClock(nowMs)
    private var listeningIsPlaying: Boolean = false

    private val reportingEnabledState = MutableStateFlow(true)
    val reportingEnabled = reportingEnabledState.asStateFlow()

    @Volatile
    var enabled = true
        private set

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        reportingEnabledState.value = value
        if (!value) {
            listeningSongId = null
            lastListeningSong = null
            listeningClock.reset()
            listeningIsPlaying = false
        } else {
            val song = player.currentSong.value
            listeningSongId = song?.id
            lastListeningSong = song
            listeningClock.reset()
            listeningIsPlaying = player.isPlaying.value
            listeningClock.update(listeningIsPlaying, Settings.shared.pauseListenHistory)
        }
    }

    fun start() {
        scope.launch {
            snapshotFlow { Settings.shared.pauseListenHistory }.collect(::onHistoryPausedChanged)
        }
        scope.launch {
            observeAndroidScrobbles(player, ::onPlaybackState)
        }
    }

    internal fun onPlaybackState(
        song: SongItem?,
        playing: Boolean,
        newPlayback: Boolean,
    ) {
        if (!enabled) return
        if (newPlayback || song?.id != listeningSongId) {
            finishSession()
            lastListeningSong = song
            listeningSongId = song?.id
        }
        listeningIsPlaying = playing
        listeningClock.update(playing, Settings.shared.pauseListenHistory)
    }

    internal fun onHistoryPausedChanged(paused: Boolean) {
        listeningClock.update(enabled && listeningIsPlaying, paused)
    }

    private fun finishSession() {
        val song = lastListeningSong
        val playTime = listeningClock.elapsedMs()
        lastListeningSong = null
        listeningSongId = null
        listeningClock.reset()
        listeningIsPlaying = false
        if (song != null && enabled && !Settings.shared.pauseListenHistory && playTime >= Settings.shared.historyDuration * 1000L) {
            val previousWrite = pendingWrite
            pendingWrite =
                writerScope.launch {
                    previousWrite?.join()
                    recordLocalPlayIfQualified(song, playTime)
                }
        }
    }

    fun flush(): Job? {
        finishSession()
        val write = pendingWrite
        if (write == null) writerScope.cancel() else write.invokeOnCompletion { writerScope.cancel() }
        return write
    }

    private suspend fun recordLocalPlayIfQualified(
        song: SongItem,
        playTimeMs: Long,
    ) {
        Logger.i(REPORTING_TAG, "local-history attempt")
        try {
            withContext(Dispatchers.IO) {
                recordPlay(song, playTimeMs)
            }
            Logger.i(REPORTING_TAG, "local-history outcome=success")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(REPORTING_TAG, "local-history outcome=failure type=${error::class.simpleName}")
        }
    }
}
