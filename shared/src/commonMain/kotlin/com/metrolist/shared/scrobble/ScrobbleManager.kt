/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.scrobble

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.api.ScrobbleApiException
import com.metrolist.shared.api.ScrobbleFailureDisposition
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.listenbrainz.ListenBrainz
import com.metrolist.shared.deviceconnect.DcScrobbleSession
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.getCurrentTimeMillis
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class ScrobbleManager internal constructor(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    startRetryLoop: Boolean,
    private val accountsOverride: ((Boolean) -> List<ScrobbleAccount>)? = null,
    private val submitOverride: (suspend (ScrobbleAccount, String, String, Long, String?, String?, Long?) -> Result<Unit>)? = null,
    private val nowPlayingOverride: (suspend (ScrobbleAccount, SongItem) -> Result<Unit>)? = null,
    private val monotonicClock: () -> Long = clock,
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(scope: CoroutineScope) : this(
        scope,
        ::getCurrentTimeMillis,
        true,
        monotonicClock = { monotonicOrigin.elapsedNow().inWholeMilliseconds },
    )

    var minSongDuration: Int by Settings.shared::lastFmMinDuration
    var scrobbleDelayPercent: Float by Settings.shared::lastFmDelayPercent
    var scrobbleDelaySeconds: Int by Settings.shared::lastFmMaxDelay
    private var scrobbleJob: Job? = null
    private var totalPlayedMs = 0L
    private var lastUpdateMs: Long? = null
    private var currentSong: SongItem? = null
    private var isScrobbled = false

    private var hasInheritedScrobblePending = false
    private var isScrobbleInFlight = false
    private var songGeneration = 0L
    private var songStartedAtTimestamp = 0L

    @Volatile private var reportingEnabled = true

    @Volatile private var reportingGeneration = 0L

    private val submissionMutex = Mutex()

    private val retryChanges = Channel<Unit>(Channel.CONFLATED)

    init {
        if (startRetryLoop) {
            var previousAccounts = currentAccounts() to currentAccounts(nowPlaying = true)
            scope.launch {
                snapshotFlow { currentAccounts() to currentAccounts(nowPlaying = true) }.collect { accounts ->
                    if (accounts != previousAccounts) {
                        previousAccounts = accounts
                        retryChanges.trySend(Unit)
                        val song = currentSong
                        if (reportingEnabled && lastUpdateMs != null && song != null) {
                            updatePlayedTime()
                            if (!isScrobbled) {
                                updateNowPlaying(song)
                                startTicker()
                            }
                        }
                    }
                }
            }
            val settings = Settings.shared
            scope.launch {
                snapshotFlow {
                    with(settings) {
                        listOf(
                            pendingScrobblesJson,
                            lastFmUsername,
                            lastFmSession,
                            listenBrainzUsername,
                            listenBrainzToken,
                            lastFmEnabled,
                            lastFmActivityEnabled,
                            listenBrainzEnabled,
                        )
                    }
                }.collect { retryChanges.trySend(Unit) }
            }
            scope.launch {
                PendingScrobbleStore.changes.collect { retryChanges.trySend(Unit) }
            }
            scope.launch {
                PlaybackNetworkStateStore.state.collect { retryChanges.trySend(Unit) }
            }
            scope.launch {
                delay(5_000.milliseconds)
                while (isActive) {
                    val next =
                        try {
                            retryPendingScrobbles()
                            retryChanges.tryReceive()
                            val accounts = currentAccounts()
                            if (reportingEnabled && PlaybackNetworkStateStore.current.connected && accounts.isNotEmpty()) {
                                withContext(persistenceDispatcher) { PendingScrobbleStore.all() }
                                    .filter { pending ->
                                        accounts.any { it.provider == pending.provider && it.account == pending.account }
                                    }.minOfOrNull { (it.nextAttemptAtMs - clock()).takeIf { remaining -> remaining > 0L } ?: BASE_RETRY_MS }
                            } else {
                                null
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            retryChanges.tryReceive()
                            BASE_RETRY_MS
                        }
                    if (next == null) retryChanges.receive() else withTimeoutOrNull(next) { retryChanges.receive() }
                }
            }
        }
    }

    fun session(): DcScrobbleSession? =
        currentSong?.let {
            DcScrobbleSession(
                songId = it.id,
                startedAtTimestamp = songStartedAtTimestamp,
                accumulatedPlayedMs = totalPlayedMs,
                completed = isScrobbled,
                pending = hasInheritedScrobblePending || isScrobbleInFlight,
            )
        }

    internal fun pauseAndGetSession(): DcScrobbleSession? {
        onSongPause()
        return session()
    }

    internal fun resumePlaybackSession(
        song: SongItem,
        session: DcScrobbleSession,
        playing: Boolean = true,
    ) {
        onSongStop()
        importSession(song, session)
        if (playing) {
            if (reportingEnabled && !isScrobbled) updateNowPlaying(song)
            onSongResume(song)
        }
    }

    fun setReportingEnabled(
        enabled: Boolean,
        song: SongItem?,
        playing: Boolean,
        session: DcScrobbleSession? = null,
    ) {
        if (!enabled ||
            !reportingEnabled
        ) {
            session?.takeIf { it.songId == song?.id }?.let { imported -> song?.let { importSession(it, imported) } }
        }
        if (reportingEnabled == enabled) return

        reportingEnabled = enabled
        reportingGeneration++
        retryChanges.trySend(Unit)
        Logger.i(REPORTING_TAG, "reporting enabled=$enabled generation=$reportingGeneration imported=${session != null}")
        if (!enabled) {
            updatePlayedTime()
            lastUpdateMs = null
            scrobbleJob?.cancel()
            scrobbleJob = null
            return
        }

        // A handoff cannot distinguish sent from unsent pending work, so duplicate delivery remains possible.
        if (session != null) hasInheritedScrobblePending = false
        if (song != null && playing) {
            if (currentSong?.id != song.id) {
                onSongStart(song)
            } else {
                lastUpdateMs = monotonicClock()
                if (!isScrobbled) updateNowPlaying(song)
                startTicker()
            }
        }
    }

    private fun importSession(
        song: SongItem,
        session: DcScrobbleSession,
    ) {
        val samePlayback = currentSong?.id == session.songId && songStartedAtTimestamp == session.startedAtTimestamp
        if (!samePlayback) {
            scrobbleJob?.cancel()
            currentSong = song
            scrobbleJob = null
            songGeneration++
            isScrobbleInFlight = false
        }
        totalPlayedMs = session.accumulatedPlayedMs
        songStartedAtTimestamp = session.startedAtTimestamp
        isScrobbled = session.completed
        hasInheritedScrobblePending = session.pending && !isScrobbleInFlight
        lastUpdateMs = null
    }

    fun onSongStart(song: SongItem?) {
        if (song == null || currentSong?.id == song.id) return
        startPlaybackInstance(song)
    }

    fun onPlaybackInstanceStart(song: SongItem) {
        startPlaybackInstance(song)
    }

    private fun startPlaybackInstance(song: SongItem) {
        onSongStop()
        currentSong = song
        songGeneration++
        totalPlayedMs = 0L
        lastUpdateMs = monotonicClock()
        isScrobbled = false
        hasInheritedScrobblePending = false
        isScrobbleInFlight = false
        songStartedAtTimestamp = clock() / 1000
        if (reportingEnabled) {
            updateNowPlaying(song)
            startTicker()
        }
    }

    fun onSongResume(song: SongItem) {
        if (currentSong?.id != song.id) {
            onSongStart(song)
            return
        }
        updatePlayedTime()
        currentSong = song.copy(duration = song.duration?.takeIf { it > 0L } ?: currentSong?.duration)
        lastUpdateMs = monotonicClock()
        if (reportingEnabled) startTicker()
    }

    fun onSongPause() {
        updatePlayedTime()
        lastUpdateMs = null
        if (reportingEnabled) checkScrobble()
        scrobbleJob?.cancel()
        scrobbleJob = null
    }

    fun onSongStop() {
        updatePlayedTime()
        if (reportingEnabled) checkScrobble()
        songGeneration++
        scrobbleJob?.cancel()
        scrobbleJob = null
        currentSong = null
        lastUpdateMs = null
    }

    private fun startTicker() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        val song = currentSong ?: return
        val remainingMs = (scrobbleThresholdMs(song) ?: return) - totalPlayedMs
        if (currentAccounts().isEmpty()) return
        scrobbleJob =
            scope.launch {
                delay(remainingMs.coerceAtLeast(0L).milliseconds)
                updatePlayedTime()
                checkScrobble()
            }
    }

    private fun updatePlayedTime() {
        val previous = lastUpdateMs ?: return
        val now = monotonicClock()
        totalPlayedMs += (now - previous).coerceAtLeast(0L)
        lastUpdateMs = now
    }

    private fun checkScrobble() {
        val song = currentSong ?: return
        if (!reportingEnabled || isScrobbled) return
        val thresholdMs = scrobbleThresholdMs(song) ?: return
        if (totalPlayedMs >= thresholdMs) scrobbleSong(song)
    }

    private fun scrobbleThresholdMs(song: SongItem): Long? {
        if (song.isLive) return null
        val duration = song.duration?.takeIf { it > 0L && it >= minSongDuration } ?: return null
        return min(
            (duration * 1000 * scrobbleDelayPercent).toLong(),
            scrobbleDelaySeconds * 1000L,
        )
    }

    private fun scrobbleSong(song: SongItem) {
        val accounts = currentAccounts()
        if (!reportingEnabled || accounts.isEmpty() || isScrobbled || hasInheritedScrobblePending || isScrobbleInFlight) return
        isScrobbleInFlight = true
        val generation = songGeneration
        val timestamp = songStartedAtTimestamp
        launchScrobble(song, timestamp, "$timestamp:$generation:${clock()}", accounts) { completed ->
            if (generation == songGeneration) {
                if (completed) isScrobbled = true
                isScrobbleInFlight = false
            }
        }
    }

    internal fun scrobbleSessionIfThresholdReached(
        song: SongItem,
        session: DcScrobbleSession,
    ) {
        if (!reportingEnabled || session.songId != song.id || session.completed || session.pending) return
        if (session.accumulatedPlayedMs < (scrobbleThresholdMs(song) ?: return)) return
        val accounts = currentAccounts()
        if (accounts.isEmpty()) return
        val timestamp = session.startedAtTimestamp
        launchScrobble(song, timestamp, "$timestamp:${song.id}:${clock()}", accounts)
    }

    private fun launchScrobble(
        song: SongItem,
        timestamp: Long,
        submissionId: String,
        accounts: List<ScrobbleAccount>,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        val reportingGeneration = reportingGeneration
        val artist =
            song.artists
                .firstOrNull()
                ?.name
                .orEmpty()
        val track = song.title
        val album = song.album?.name
        val albumArtist = song.artists.firstOrNull()?.name
        val duration = song.duration

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var shouldMarkScrobbled = false
            var persisted = false
            try {
                val pending =
                    accounts.map { account ->
                        account to
                            PendingScrobble(
                                id = "${account.provider.name}:${account.account}:$submissionId",
                                provider = account.provider,
                                account = account.account,
                                track = track,
                                artist = artist,
                                album = album,
                                albumArtist = albumArtist,
                                duration = duration,
                                timestamp = timestamp,
                            )
                    }
                withContext(NonCancellable + persistenceDispatcher) {
                    ScrobbleSessions.enqueueIfCurrent(pending)
                }
                persisted = true
                submissionMutex.withLock {
                    pending.forEach { (account, entry) ->
                        if (!canReport(reportingGeneration)) return@withLock
                        val queued = withContext(persistenceDispatcher) { PendingScrobbleStore.get(entry.id) }
                        if (queued != entry) return@forEach
                        Logger.i(REPORTING_TAG, "scrobble attempt provider=${account.provider} generation=$reportingGeneration")
                        val result = submit(account, artist, track, timestamp, album, albumArtist, duration)
                        Logger.i(
                            REPORTING_TAG,
                            "scrobble outcome=${if (result.isSuccess) "success" else "failure"} provider=${account.provider}",
                        )
                        withContext(NonCancellable + persistenceDispatcher) {
                            handleSubmissionResult(account, entry, result.exceptionOrNull())
                        }
                    }
                    shouldMarkScrobbled = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                shouldMarkScrobbled = persisted
            } finally {
                onFinished?.invoke(shouldMarkScrobbled)
            }
        }
    }

    private fun canReport(generation: Long): Boolean = reportingEnabled && generation == reportingGeneration

    private fun updateNowPlaying(song: SongItem) {
        val artist =
            song.artists
                .firstOrNull()
                ?.name
                .orEmpty()
        val reportingGeneration = reportingGeneration
        currentAccounts(nowPlaying = true).forEach { account ->
            scope.launch {
                if (!canReport(reportingGeneration)) return@launch
                Logger.i(REPORTING_TAG, "now-playing attempt provider=${account.provider} generation=$reportingGeneration")
                val result =
                    nowPlayingOverride?.invoke(account, song) ?: when (account.provider) {
                        ScrobbleProvider.LAST_FM -> {
                            LastFM.updateNowPlaying(
                                GlobalInnerTube.client,
                                artist,
                                song.title,
                                song.album?.name,
                                song.artists.firstOrNull()?.name,
                                song.duration,
                                account.credential,
                            )
                        }

                        ScrobbleProvider.LISTEN_BRAINZ -> {
                            ListenBrainz.updateNowPlaying(
                                GlobalInnerTube.client,
                                account.credential,
                                artist,
                                song.title,
                                song.album?.name,
                                song.duration,
                            )
                        }
                    }
                Logger.i(
                    REPORTING_TAG,
                    "now-playing outcome=${if (result.isSuccess) "success" else "failure"} provider=${account.provider} generation=$reportingGeneration",
                )
            }
        }
    }

    private suspend fun submit(
        account: ScrobbleAccount,
        artist: String,
        track: String,
        timestamp: Long,
        album: String?,
        albumArtist: String?,
        duration: Long?,
    ): Result<Unit> =
        submitOverride?.invoke(account, artist, track, timestamp, album, albumArtist, duration) ?: when (account.provider) {
            ScrobbleProvider.LAST_FM -> {
                LastFM.scrobble(
                    GlobalInnerTube.client,
                    artist,
                    track,
                    timestamp,
                    album,
                    albumArtist,
                    duration,
                    account.credential,
                )
            }

            ScrobbleProvider.LISTEN_BRAINZ -> {
                ListenBrainz.scrobble(
                    GlobalInnerTube.client,
                    account.credential,
                    artist,
                    track,
                    timestamp,
                    album,
                    duration,
                )
            }
        }

    private suspend fun handleSubmissionResult(
        account: ScrobbleAccount,
        pending: PendingScrobble,
        failure: Throwable?,
    ) {
        val apiFailure = failure as? ScrobbleApiException
        if (failure == null || apiFailure?.disposition == ScrobbleFailureDisposition.AUTHENTICATION ||
            apiFailure?.disposition == ScrobbleFailureDisposition.PERMANENT
        ) {
            PendingScrobbleStore.remove(pending.id)
        } else {
            ScrobbleSessions.enqueueIfCurrent(
                account,
                pending.copy(attemptCount = 1, nextAttemptAtMs = nextAttemptAt(1, apiFailure?.retryAfterMs, pending.id.hashCode())),
            )
        }
    }

    private suspend fun retryPendingScrobbles() =
        withContext(persistenceDispatcher) {
            submissionMutex.withLock {
                if (!reportingEnabled || !PlaybackNetworkStateStore.current.connected || currentAccounts().isEmpty()) return@withLock
                val reportingGeneration = reportingGeneration
                val now = clock()
                val blockedProviders = mutableSetOf<ScrobbleProvider>()
                for (pending in PendingScrobbleStore.all()) {
                    if (pending.provider in blockedProviders || pending.nextAttemptAtMs > now) continue
                    val account = currentAccounts().firstOrNull { it.provider == pending.provider } ?: continue
                    if (account.account != pending.account) continue
                    if (!canReport(reportingGeneration)) return@withLock
                    Logger.i(
                        REPORTING_TAG,
                        "retry attempt=${pending.attemptCount} provider=${pending.provider} generation=$reportingGeneration",
                    )
                    val result =
                        submit(
                            account,
                            pending.artist,
                            pending.track,
                            pending.timestamp,
                            pending.album,
                            pending.albumArtist,
                            pending.duration,
                        )
                    if (!canReport(reportingGeneration)) return@withLock
                    Logger.i(
                        REPORTING_TAG,
                        "retry outcome=${if (result.isSuccess) "success" else "failure"} attempt=${pending.attemptCount} " +
                            "provider=${pending.provider} generation=$reportingGeneration",
                    )
                    val failure = result.exceptionOrNull()
                    val apiFailure = failure as? ScrobbleApiException
                    when {
                        failure == null -> {
                            PendingScrobbleStore.remove(pending.id)
                            delay(1_000.milliseconds)
                        }

                        apiFailure?.disposition == ScrobbleFailureDisposition.AUTHENTICATION -> {
                            PendingScrobbleStore.remove(pending.id)
                        }

                        apiFailure?.disposition == ScrobbleFailureDisposition.PERMANENT -> {
                            PendingScrobbleStore.remove(pending.id)
                        }

                        else -> {
                            val attempts = pending.attemptCount + 1
                            PendingScrobbleStore.upsert(
                                pending.copy(
                                    attemptCount = attempts,
                                    nextAttemptAtMs = nextAttemptAt(attempts, apiFailure?.retryAfterMs, pending.id.hashCode()),
                                ),
                            )
                            blockedProviders += pending.provider
                        }
                    }
                }
            }
        }

    private fun currentAccounts(nowPlaying: Boolean = false): List<ScrobbleAccount> =
        accountsOverride?.invoke(nowPlaying) ?: buildList {
            if (Settings.shared.lastFmEnabled &&
                Settings.shared.lastFmActivityEnabled &&
                (!nowPlaying || Settings.shared.lastFmUseNowPlaying)
            ) {
                ScrobbleSessions.current(ScrobbleProvider.LAST_FM)?.let(::add)
            }
            if (Settings.shared.listenBrainzEnabled && (!nowPlaying || Settings.shared.listenBrainzUseNowPlaying)) {
                ScrobbleSessions.current(ScrobbleProvider.LISTEN_BRAINZ)?.let(::add)
            }
        }

    private fun nextAttemptAt(
        attempt: Int,
        retryAfterMs: Long?,
        seed: Int,
    ): Long {
        val exponent = (attempt - 1).coerceIn(0, 10)
        val backoff = (BASE_RETRY_MS * (1L shl exponent)).coerceAtMost(MAX_RETRY_MS)
        val jitter = (seed.toLong().and(0x7fffffffL) % 10_000L)
        return clock() + maxOf(backoff + jitter, retryAfterMs ?: 0L)
    }

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        song: SongItem?,
        newPlayback: Boolean = false,
    ) {
        if (newPlayback && song != null && currentSong?.id == song.id) onSongStop()
        if (song == null) {
            onSongStop()
            return
        }
        if (isPlaying) {
            if (currentSong?.id != song.id) onSongStart(song) else onSongResume(song)
        } else {
            if (currentSong?.id == song.id) {
                currentSong = song.copy(duration = song.duration?.takeIf { it > 0L } ?: currentSong?.duration)
                onSongPause()
            } else {
                onSongStop()
            }
        }
    }

    private companion object {
        val monotonicOrigin = TimeSource.Monotonic.markNow()
        const val REPORTING_TAG = "ScrobbleReporting"
        const val BASE_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 6 * 60 * 60 * 1000L
    }

    internal val playedMsForTest get() = totalPlayedMs
    internal val startedAtForTest get() = songStartedAtTimestamp
    internal val hasActiveTickerForTest get() = scrobbleJob?.isActive == true

    internal suspend fun retryPendingScrobblesForTest() = retryPendingScrobbles()
}
