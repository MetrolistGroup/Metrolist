package com.metrolist.shared.api.lastfm

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Keeps the connected account's scrobbles in memory so statistics period changes only fetch what is missing. */
object LastFmHistoryCache {
    private val mutex = Mutex()
    private var user: String? = null
    private var sinceMs = Long.MAX_VALUE
    private var untilMs = Long.MIN_VALUE
    private var listens: List<LastFmListen> = emptyList()
    private var refreshedAtMs = Long.MIN_VALUE

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
                val now = Clock.System.now().toEpochMilliseconds()
                // A range starting after the cached one ends would otherwise download everything in between.
                if (connected != user || sinceMs < this.sinceMs || sinceMs > this.untilMs) {
                    user = null
                    listens = fetch(connected, sinceMs, untilMs)
                    user = connected
                    this.sinceMs = sinceMs
                    this.untilMs = minOf(untilMs, now)
                    refreshedAtMs = now
                } else if (untilMs > this.untilMs &&
                    (this.untilMs < refreshedAtMs - REFRESH_INTERVAL_MS || now - refreshedAtMs >= REFRESH_INTERVAL_MS)
                ) {
                    // Throttle only caches that reached their last refresh time; older uncovered ranges are always fetched.
                    // Last.fm accepts scrobbles up to two weeks late, so replace that window instead of only appending.
                    val from = maxOf(this.sinceMs, this.untilMs - LATE_SCROBBLE_WINDOW_MS)
                    listens = listens.filter { it.timestampMs < from } + fetch(connected, from, untilMs)
                    this.untilMs = minOf(untilMs, now)
                    refreshedAtMs = now
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
    private const val REFRESH_INTERVAL_MS = 5 * 60_000L
    private const val LATE_SCROBBLE_WINDOW_MS = 14 * 24 * 60 * 60_000L
}
