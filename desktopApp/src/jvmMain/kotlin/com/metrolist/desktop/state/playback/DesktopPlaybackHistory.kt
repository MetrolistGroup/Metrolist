package com.metrolist.desktop.state.playback

import com.metrolist.desktop.history.HistoryRepository
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ListeningHistoryClock
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DesktopPlaybackHistory(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val record: suspend (SongItem, Long) -> Unit = { song, playTimeMs ->
        HistoryRepository.recordPlay(song, playTimeMs = playTimeMs)
    },
) {
    var currentSongId: String? = null
        private set
    var currentSong: SongItem? = null
        private set
    private val clock = ListeningHistoryClock(nowMs)
    private var playing = false
    private var closed = false
    private val finalization = Mutex()

    fun elapsedMs(): Long? = currentSongId?.let { clock.elapsedMs() }

    fun onPlayingChanged(isPlaying: Boolean) {
        if (closed) return
        playing = isPlaying
        clock.update(playing, Settings.shared.pauseListenHistory)
    }

    fun onHistoryPausedChanged(paused: Boolean) {
        if (closed) return
        clock.update(playing, paused)
    }

    fun reset(
        songId: String?,
        song: SongItem?,
    ) {
        if (closed) return
        currentSongId = songId
        currentSong = song
        clock.reset()
        playing = false
    }

    fun updateMetadata(song: SongItem) {
        if (song.id == currentSongId) currentSong = song
    }

    fun restart(
        song: SongItem,
        isPlaying: Boolean,
    ): Pair<SongItem, Long>? {
        val previous = currentSong?.let { it to (elapsedMs() ?: 0L) }
        reset(song.id, song)
        onPlayingChanged(isPlaying)
        return previous
    }

    suspend fun finalizeSession(
        nextSong: SongItem? = null,
        isPlaying: Boolean = false,
        reportingEnabled: Boolean,
        minDurationMs: Long,
        pauseHistory: Boolean,
        close: Boolean = false,
    ) = finalization.withLock {
        if (closed) return@withLock
        val song = currentSong
        val elapsed = elapsedMs() ?: 0L
        reset(nextSong?.id, nextSong)
        onPlayingChanged(isPlaying)
        closed = close
        withContext(NonCancellable) {
            recordPreviousSong(song, elapsed, reportingEnabled, minDurationMs, pauseHistory)
        }
    }

    suspend fun recordPreviousSong(
        song: SongItem?,
        elapsedMs: Long,
        reportingEnabled: Boolean,
        minDurationMs: Long,
        pauseHistory: Boolean,
    ) {
        val playedSong = song ?: return
        if (!reportingEnabled || elapsedMs < minDurationMs || pauseHistory) return
        Logger.i("DeviceConnectReporting", "local-history attempt")
        try {
            withContext(Dispatchers.IO) { record(playedSong, elapsedMs) }
            Logger.i("DeviceConnectReporting", "local-history outcome=success")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w("DeviceConnectReporting", "local-history outcome=failure type=${error::class.simpleName}")
            throw error
        }
    }
}
