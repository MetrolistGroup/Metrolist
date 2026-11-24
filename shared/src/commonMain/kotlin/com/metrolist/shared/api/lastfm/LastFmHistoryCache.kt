package com.metrolist.shared.api.lastfm

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps the connected account's scrobbles in memory so statistics period changes only fetch what is missing. */
object LastFmHistoryCache {
    private val mutex = Mutex()
    private var user: String? = null
    private var sinceMs = Long.MAX_VALUE
    private var untilMs = Long.MIN_VALUE
    private var listens: List<LastFmListen> = emptyList()

    /** Returns an empty list when Last.fm is not connected or the history cannot be loaded. */
    suspend fun listens(
        sinceMs: Long,
        untilMs: Long,
    ): List<LastFmListen> {
        val connected =
            Settings.shared.lastFmUsername.takeIf {
                it.isNotBlank() && Settings.shared.lastFmSession.isNotBlank() && !Settings.shared.lastFmSessionExpired
            } ?: return emptyList()
        return mutex.withLock {
            try {
                if (connected != user || sinceMs < this.sinceMs) {
                    user = null
                    listens = fetch(connected, sinceMs, untilMs)
                    user = connected
                    this.sinceMs = sinceMs
                    this.untilMs = untilMs
                } else if (untilMs > this.untilMs) {
                    listens = listens + fetch(connected, this.untilMs + 1, untilMs)
                    this.untilMs = untilMs
                }
                listens.filter { it.timestampMs in sinceMs..untilMs }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Logger.w(TAG, "Last.fm history unavailable for statistics", error)
                emptyList()
            }
        }
    }

    private suspend fun fetch(
        user: String,
        sinceMs: Long,
        untilMs: Long,
    ) = LastFM.getListeningHistory(GlobalYouTube.client, user, sinceMs, untilMs).getOrThrow()

    private const val TAG = "LastFmHistoryCache"
}
