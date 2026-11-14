package com.metrolist.shared.scrobble

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.api.ScrobbleApiException
import com.metrolist.shared.api.ScrobbleFailureDisposition
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.db.daos.PendingScrobbleDao
import com.metrolist.shared.deviceconnect.DcScrobbleSession
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScrobbleManagerTest {
    @Test
    fun consecutiveCommittedOccurrencesSubmitSeparatelyButRecoveryKeepsTheListen() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "account", "token")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val submitted = mutableListOf<Long>()
            val nowPlaying = mutableListOf<String>()
            val manager =
                ScrobbleManager(
                    backgroundScope,
                    { 1_000 + testScheduler.currentTime },
                    false,
                    accountsOverride = { listOf(account) },
                    submitOverride = { _, _, _, timestamp, _, _, _ ->
                        submitted += timestamp
                        Result.success(Unit)
                    },
                    nowPlayingOverride = { _, song ->
                        nowPlaying += song.id
                        Result.success(Unit)
                    },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            val track = song().copy(duration = 180)
            manager.onPlayerStateChanged(true, track)
            advanceTimeBy(90_000)
            runCurrent()
            manager.onPlayerStateChanged(true, track, newPlayback = true)
            runCurrent()
            advanceTimeBy(45_000)
            manager.onPlayerStateChanged(true, track.copy(thumbnail = "recovered"))
            advanceTimeBy(45_000)
            runCurrent()
            assertEquals(listOf(1L, 91L), submitted)
            assertEquals(listOf(track.id, track.id), nowPlaying)
        }

    @Test
    fun resolvedDurationReschedulesTheOriginalListenWithoutCountingPauses() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "account", "token")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val submitted = mutableListOf<Pair<Long, Long?>>()
            val manager =
                ScrobbleManager(
                    backgroundScope,
                    { 1_000L + testScheduler.currentTime },
                    startRetryLoop = false,
                    accountsOverride = { listOf(account) },
                    submitOverride = { _, _, _, timestamp, _, _, duration ->
                        submitted += timestamp to duration
                        Result.success(Unit)
                    },
                    nowPlayingOverride = { _, _ -> Result.success(Unit) },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            val unknown = song().copy(duration = null)
            manager.onPlayerStateChanged(true, unknown)
            advanceTimeBy(60_000)
            manager.onPlayerStateChanged(false, unknown)
            advanceTimeBy(10_000)
            manager.onPlayerStateChanged(true, unknown.copy(duration = 180))
            assertEquals(1L, manager.startedAtForTest)
            assertEquals(60_000L, manager.playedMsForTest)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf<Pair<Long, Long?>>(1L to 180L), submitted)
            manager.onPlayerStateChanged(true, unknown)
            advanceTimeBy(180_000)
            runCurrent()
            assertEquals(1, submitted.size)
            manager.onPlaybackInstanceStart(unknown.copy(duration = 180, musicVideoType = "MUSIC_VIDEO_TYPE_LIVE"))
            advanceTimeBy(180_000)
            runCurrent()
            assertEquals(1, submitted.size)
            manager.minSongDuration = 0
            manager.onPlaybackInstanceStart(unknown.copy(duration = 0))
            advanceTimeBy(180_000)
            runCurrent()
            assertEquals(1, submitted.size)
        }

    @Test
    fun retrySchedulingRecoversAfterStorageReadFailure() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "account", "token")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val backing = MemoryPendingScrobbleDao()
            var reads = 0
            PendingScrobbleStore.daoForTest =
                object : PendingScrobbleDao by backing {
                    override suspend fun all(): List<PendingScrobble> {
                        reads++
                        if (reads == 2) error("Storage temporarily unavailable")
                        return backing.all()
                    }
                }
            var sends = 0
            ScrobbleManager(
                backgroundScope,
                { testScheduler.currentTime },
                startRetryLoop = true,
                accountsOverride = { listOf(account) },
                submitOverride = { _, _, _, _, _, _, _ ->
                    sends++
                    Result.success(Unit)
                },
                persistenceDispatcher = StandardTestDispatcher(testScheduler),
            )
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(2, reads)
            backing.upsert(listOf(pending("queued", account.provider, account.account)))
            advanceTimeBy(29_999)
            assertEquals(0, sends)
            runCurrent()
            assertEquals(1, sends)
            advanceTimeBy(1_001)
            runCurrent()
            assertTrue(backing.all().isEmpty())
            val settledReads = reads
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(settledReads, reads)
        }

    @Test
    fun retryLoopDoesNotReadStorageWithoutAnEnabledAccount() =
        runTest {
            var reads = 0
            PendingScrobbleStore.daoForTest =
                object : PendingScrobbleDao by MemoryPendingScrobbleDao() {
                    override suspend fun all(): List<PendingScrobble> {
                        reads++
                        error("No account needs the queue")
                    }
                }
            ScrobbleManager(
                backgroundScope,
                { testScheduler.currentTime },
                startRetryLoop = true,
                accountsOverride = { emptyList() },
                persistenceDispatcher = StandardTestDispatcher(testScheduler),
            )
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, reads)
        }

    @Test
    fun persistenceIsDispatchedAndBatchesDestinationsBeforeShutdown() =
        runTest {
            ScrobbleSessions.connectLastFm("last", "token")
            ScrobbleSessions.connectListenBrainz("brainz", "token")
            val accounts =
                listOfNotNull(ScrobbleSessions.current(ScrobbleProvider.LAST_FM), ScrobbleSessions.current(ScrobbleProvider.LISTEN_BRAINZ))
            val serviceJob = Job()
            val revision = PendingScrobbleStore.changes.value
            var submitted = false
            val manager =
                ScrobbleManager(
                    CoroutineScope(coroutineContext + serviceJob),
                    { 1000L },
                    false,
                    accountsOverride = { accounts },
                    submitOverride = { _, _, _, _, _, _, _ ->
                        submitted = true
                        Result.success(Unit)
                    },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            manager.scrobbleSessionIfThresholdReached(song(), DcScrobbleSession(song().id, 1L, 300_000L, false, false))
            assertEquals(revision, PendingScrobbleStore.changes.value)
            serviceJob.cancel()
            runCurrent()
            assertEquals(revision + 1, PendingScrobbleStore.changes.value)
            assertEquals(accounts.map { it.provider }.toSet(), PendingScrobbleStore.all().map { it.provider }.toSet())
            assertFalse(submitted)
        }

    @Test
    fun pendingQueueRetainsMoreThanTwoHundredPlaysAndRetryUpdates() =
        runTest {
            val plays = (0..250).map { pending("play-$it", ScrobbleProvider.LAST_FM, "account") }
            plays.forEach { PendingScrobbleStore.enqueue(it) }
            PendingScrobbleStore.upsert(plays.first().copy(attemptCount = 2))
            assertEquals(plays.map { it.id }.toSet(), PendingScrobbleStore.all().map { it.id }.toSet())
            assertEquals(2, PendingScrobbleStore.all().single { it.id == "play-0" }.attemptCount)
        }

    @Test
    fun retriesKeepDisabledProvidersPendingUntilReenabled() =
        runTest {
            ScrobbleSessions.connectLastFm("last", "test")
            ScrobbleSessions.connectListenBrainz("brainz", "test")
            val last = pending("last-play", ScrobbleProvider.LAST_FM, "last")
            val brainz = pending("brainz-play", ScrobbleProvider.LISTEN_BRAINZ, "brainz")
            PendingScrobbleStore.enqueue(last)
            PendingScrobbleStore.enqueue(brainz)
            val sends = mutableListOf<String>()
            val manager =
                ScrobbleManager(
                    this,
                    { 1000L },
                    startRetryLoop = false,
                    submitOverride = { _, _, track, _, _, _, _ ->
                        sends += track
                        Result.success(Unit)
                    },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            Settings.shared.lastFmEnabled = false
            Settings.shared.listenBrainzEnabled = false
            manager.retryPendingScrobblesForTest()
            assertTrue(sends.isEmpty())
            assertEquals(2, PendingScrobbleStore.all().size)
            Settings.shared.lastFmEnabled = true
            Settings.shared.lastFmActivityEnabled = false
            manager.retryPendingScrobblesForTest()
            assertTrue(sends.isEmpty())
            Settings.shared.lastFmActivityEnabled = true
            Settings.shared.listenBrainzEnabled = true
            manager.retryPendingScrobblesForTest()
            assertEquals(listOf(last.track, brainz.track), sends)
            assertTrue(PendingScrobbleStore.all().isEmpty())
        }

    @Test
    fun backwardWallClockChangeDoesNotDelayTheListeningThreshold() =
        runTest {
            var wallClock = 100_000L
            val timestamps = mutableListOf<Long>()
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "user", "test")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val manager =
                ScrobbleManager(
                    backgroundScope,
                    { wallClock },
                    startRetryLoop = false,
                    accountsOverride = { nowPlaying -> if (nowPlaying) emptyList() else listOf(account) },
                    submitOverride = { _, _, _, timestamp, _, _, _ ->
                        timestamps += timestamp
                        Result.success(Unit)
                    },
                    monotonicClock = { testScheduler.currentTime },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                ).apply { scrobbleDelaySeconds = 10 }
            manager.onSongStart(song())
            runCurrent()
            advanceTimeBy(5000)
            wallClock -= 60_000
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(listOf(100L), timestamps)
            assertEquals(10_000L, manager.playedMsForTest)
        }

    @Test
    fun connectingAccountDuringPlaybackStartsItsRemainingTimer() =
        runTest {
            var nowPlaying = 0
            var scrobbles = 0
            val manager =
                ScrobbleManager(
                    backgroundScope,
                    { testScheduler.currentTime },
                    startRetryLoop = true,
                    nowPlayingOverride = { _, _ ->
                        nowPlaying++
                        Result.success(Unit)
                    },
                    submitOverride = { _, _, _, _, _, _, _ ->
                        scrobbles++
                        Result.success(Unit)
                    },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                ).apply { scrobbleDelaySeconds = 10 }
            runCurrent()
            manager.onSongStart(song())
            advanceTimeBy(4000)
            ScrobbleSessions.connectLastFm("user", "test")
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertTrue(manager.hasActiveTickerForTest)
            assertEquals(1, nowPlaying)
            advanceTimeBy(6000)
            runCurrent()
            assertEquals(1, scrobbles)
        }

    @BeforeTest
    fun setUp() {
        PendingScrobbleStore.daoForTest = MemoryPendingScrobbleDao()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        ScrobbleSessions.restorePersistedAccounts()
    }

    @AfterTest
    fun tearDown() {
        try {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            ScrobbleSessions.restorePersistedAccounts()
        } finally {
            Settings.replaceSharedForTest(null)
            PendingScrobbleStore.daoForTest = null
        }
    }

    @Test
    fun emptyRetryQueueParksAndNewWorkUsesItsDeadline() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "account", "token")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            var clockReads = 0
            var sends = 0
            ScrobbleManager(
                backgroundScope,
                {
                    clockReads++
                    testScheduler.currentTime
                },
                startRetryLoop = true,
                accountsOverride = { listOf(account) },
                submitOverride = { _, _, _, _, _, _, _ ->
                    sends++
                    Result.success(Unit)
                },
                persistenceDispatcher = StandardTestDispatcher(testScheduler),
            )
            advanceTimeBy(5_001)
            runCurrent()
            val reads = clockReads
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(reads, clockReads)
            PendingScrobbleStore.enqueue(
                PendingScrobble(
                    "pending",
                    account.provider,
                    account.account,
                    "Track",
                    "Artist",
                    timestamp = 1,
                    nextAttemptAtMs = testScheduler.currentTime + 5_000,
                ),
            )
            runCurrent()
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(0, sends)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, sends)
            assertTrue(PendingScrobbleStore.all().isEmpty())
        }

    @Test
    fun qualifiedScrobbleSurvivesShutdownDuringSubmission() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "account", "token")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val serviceJob = Job()
            val serviceScope = CoroutineScope(coroutineContext + serviceJob)
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Result<Unit>>()
            val manager =
                ScrobbleManager(
                    serviceScope,
                    { 1_000L },
                    false,
                    accountsOverride = { if (it) emptyList() else listOf(account) },
                    submitOverride = { _, _, _, _, _, _, _ ->
                        assertEquals(1, PendingScrobbleStore.all().size)
                        started.complete(Unit)
                        response.await()
                    },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            manager.scrobbleSessionIfThresholdReached(song(), DcScrobbleSession(song().id, 1L, 300_000L, false, false))
            started.await()
            serviceJob.cancel()
            runCurrent()
            assertEquals(song().title, PendingScrobbleStore.all().single().track)
            var delivered = 0
            ScrobbleManager(
                backgroundScope,
                { 1_000L },
                false,
                submitOverride = { _, _, _, _, _, _, _ ->
                    delivered++
                    Result.success(Unit)
                },
                persistenceDispatcher = StandardTestDispatcher(testScheduler),
            ).retryPendingScrobblesForTest()
            assertEquals(1, delivered)
            assertTrue(PendingScrobbleStore.all().isEmpty())
        }

    @Test
    fun pausedTimeIsNotCountedWhenSongStops() =
        runTest {
            var now = 1_000L
            val scope = backgroundScope
            val manager =
                ScrobbleManager(
                    scope,
                    { now },
                    startRetryLoop = false,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            manager.onSongStart(song())
            now += 10_000L
            manager.onSongPause()
            now += 120_000L
            manager.onSongStop()

            assertEquals(10_000L, manager.playedMsForTest)
            scope.cancel()
        }

    @Test
    fun cancelledOverlapRestoresOutgoingScrobbleProgress() =
        runTest {
            var now = 1_000L
            val scope = backgroundScope
            val manager =
                ScrobbleManager(
                    scope,
                    { now },
                    startRetryLoop = false,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            val outgoing = song("outgoing")
            manager.onSongStart(outgoing)
            now += 10_000L
            val snapshot = requireNotNull(manager.pauseAndGetSession())
            manager.onPlaybackInstanceStart(song("incoming"))
            now += 5_000L

            manager.resumePlaybackSession(
                outgoing,
                snapshot.copy(accumulatedPlayedMs = snapshot.accumulatedPlayedMs + 5_000L),
            )

            assertEquals(outgoing.id, manager.session()?.songId)
            assertEquals(snapshot.startedAtTimestamp, manager.session()?.startedAtTimestamp)
            assertEquals(15_000L, manager.playedMsForTest)
            now += 10_000L
            manager.onSongPause()
            assertEquals(25_000L, manager.playedMsForTest)
            scope.cancel()
        }

    @Test
    fun overlapFinalizesOutgoingThresholdWithoutReplacingIncomingSession() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "fake", "credential")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val sends = mutableListOf<String>()
            var now = 1_000L
            val manager =
                ScrobbleManager(
                    scope = this,
                    clock = { now },
                    startRetryLoop = false,
                    accountsOverride = { nowPlaying -> if (nowPlaying) emptyList() else listOf(account) },
                    submitOverride = { _, _, track, _, _, _, _ ->
                        sends += track
                        Result.success(Unit)
                    },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                ).apply {
                    minSongDuration = 0
                    scrobbleDelayPercent = 1f
                    scrobbleDelaySeconds = 600
                }
            val outgoing = song("outgoing")
            val incoming = song("incoming")

            manager.onSongStart(outgoing)
            now += 299_000L
            val outgoingSession = requireNotNull(manager.pauseAndGetSession())
            manager.onPlaybackInstanceStart(incoming)
            assertEquals(emptyList(), sends)
            now += 1_000L
            manager.scrobbleSessionIfThresholdReached(
                outgoing,
                outgoingSession.copy(accumulatedPlayedMs = outgoingSession.accumulatedPlayedMs + 1_000L),
            )
            runCurrent()
            manager.onSongPause()

            assertEquals(listOf(outgoing.title), sends)
            assertEquals(incoming.id, manager.session()?.songId)
            assertEquals(1_000L, manager.playedMsForTest)
        }

    @Test
    fun repeatedPlayingStateDoesNotDropElapsedScrobbleTime() =
        runTest {
            var now = 1_000L
            val scope = backgroundScope
            val manager =
                ScrobbleManager(
                    scope,
                    { now },
                    startRetryLoop = false,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            val song = song()
            manager.onSongStart(song)
            now += 5_000L
            manager.onPlayerStateChanged(isPlaying = true, song = song)
            now += 7_000L
            manager.onSongPause()

            assertEquals(12_000L, manager.playedMsForTest)
            scope.cancel()
        }

    @Test
    fun changingSongWhilePlayingStartsNextScrobbleWithoutCountingPausedTime() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "fake", "credential")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val sends = mutableListOf<String>()
            var now = 1_000L
            val manager =
                ScrobbleManager(
                    scope = this,
                    clock = { now },
                    startRetryLoop = false,
                    accountsOverride = { nowPlaying -> if (nowPlaying) emptyList() else listOf(account) },
                    submitOverride = { _, _, track, _, _, _, _ ->
                        sends += track
                        Result.success(Unit)
                    },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                ).apply {
                    minSongDuration = 0
                    scrobbleDelayPercent = 1f
                    scrobbleDelaySeconds = 10
                }
            val first = song("first")
            val second = song("second").copy(title = "Next track")

            manager.onPlayerStateChanged(true, first)
            now += 11_000L
            manager.onPlayerStateChanged(true, second)
            runCurrent()
            now += 11_000L
            manager.onPlayerStateChanged(false, second)
            runCurrent()
            now += 120_000L
            manager.onPlayerStateChanged(false, second)
            runCurrent()

            assertEquals(listOf(first.title, second.title), sends)
            assertEquals(11_000L, manager.playedMsForTest)
        }

    @Test
    fun scrobblesCreditOnlyThePrimaryArtist() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "fake", "credential")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val artists = mutableListOf<String>()
            var now = 1_000L
            val manager =
                ScrobbleManager(
                    scope = this,
                    clock = { now },
                    startRetryLoop = false,
                    accountsOverride = { nowPlaying -> if (nowPlaying) emptyList() else listOf(account) },
                    submitOverride = { _, artist, _, _, _, _, _ ->
                        artists += artist
                        Result.success(Unit)
                    },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                ).apply {
                    minSongDuration = 0
                    scrobbleDelayPercent = 1f
                    scrobbleDelaySeconds = 10
                }
            val credited =
                song().copy(
                    artists = listOf(ArtistTiny("a", "OMGkawaiiAngel"), ArtistTiny("b", "NEEDY GIRL OVERDOSE")),
                )

            manager.onPlayerStateChanged(true, credited)
            now += 11_000L
            manager.onPlayerStateChanged(false, credited)
            runCurrent()

            assertEquals(listOf("OMGkawaiiAngel"), artists)
        }

    @Test
    fun playbackWithoutScrobbleAccountStartsNoTicker() =
        runTest {
            val scope = backgroundScope
            val manager =
                ScrobbleManager(scope, { 1_000L }, startRetryLoop = false, persistenceDispatcher = StandardTestDispatcher(testScheduler))

            manager.onSongStart(song())

            assertFalse(manager.hasActiveTickerForTest)
            scope.cancel()
        }

    @Test
    fun reportingHandoffPreservesScrobbleIdentityAndProgress() =
        runTest {
            var now = 1_000L
            val scope = backgroundScope
            val owner =
                ScrobbleManager(
                    scope,
                    { now },
                    startRetryLoop = false,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            val song = song()
            owner.onSongStart(song)
            now += 10_000L
            owner.onSongPause()
            val session = requireNotNull(owner.session())
            owner.setReportingEnabled(false, song, playing = false)

            val successor =
                ScrobbleManager(
                    scope,
                    { now },
                    startRetryLoop = false,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            successor.setReportingEnabled(false, song, playing = false, session = session)
            successor.setReportingEnabled(true, song, playing = true, session = session)

            assertEquals(session.startedAtTimestamp, successor.session()?.startedAtTimestamp)
            assertEquals(session.accumulatedPlayedMs, successor.session()?.accumulatedPlayedMs)
            scope.cancel()
        }

    @Test
    fun promotionImportsFinalCompletedSnapshotWithoutResending() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "fake", "credential")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val sends = mutableListOf<String>()
            val track = song()
            val manager = testManager(account, sends)
            val incomplete = DcScrobbleSession(track.id, 1L, 1_000L, completed = false, pending = false)

            manager.setReportingEnabled(false, track, playing = false, session = incomplete)
            manager.setReportingEnabled(true, track, playing = true, session = incomplete.copy(completed = true))
            runCurrent()

            assertTrue(requireNotNull(manager.session()).completed)
            assertEquals(emptyList(), sends)
        }

    @Test
    fun returningStandaloneWaitsForInFlightSubmissionInsteadOfResending() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "fake", "credential")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val sends = mutableListOf<String>()
            val submissionGate = CompletableDeferred<Unit>()
            val owner = testManager(account, sends, submissionGate)
            val track = song()

            owner.onSongStart(track)
            runCurrent()
            assertEquals(listOf(track.title), sends)
            assertTrue(requireNotNull(owner.session()).pending)
            owner.setReportingEnabled(false, track, playing = false)
            owner.setReportingEnabled(true, track, playing = true, session = null)
            submissionGate.complete(Unit)
            runCurrent()
            owner.onPlayerStateChanged(isPlaying = true, song = track)
            runCurrent()

            assertTrue(requireNotNull(owner.session()).completed)
            assertEquals(listOf(track.title), sends)
        }

    @Test
    fun failedSubmissionIsPersistedAfterDemotion() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "fake", "credential")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val sends = mutableListOf<String>()
            val submissionGate = CompletableDeferred<Unit>()
            val manager =
                testManager(
                    account,
                    sends,
                    submissionGate,
                    Result.failure(IllegalStateException("network failure")),
                )
            val track = song()

            manager.onSongStart(track)
            runCurrent()
            assertEquals(listOf(track.title), sends)
            manager.setReportingEnabled(false, track, playing = false)
            submissionGate.complete(Unit)
            runCurrent()

            assertEquals(track.title, PendingScrobbleStore.all().single().track)
        }

    @Test
    fun lastFmAuthenticationFailureLeavesAllAccountsIntact() =
        runTest {
            val lastFm = ScrobbleAccount(ScrobbleProvider.LAST_FM, "lastfm-user", "expired-session")
            val listenBrainz = ScrobbleAccount(ScrobbleProvider.LISTEN_BRAINZ, "listenbrainz-user", "valid-token")
            val submissions = mutableListOf<String>()
            Settings.shared.innerTubeCookie = "SAPISID=youtube-session"
            Settings.shared.discordAccessToken = "discord-access"
            Settings.shared.discordRefreshToken = "discord-refresh"
            ScrobbleSessions.connectLastFm(lastFm.account, lastFm.credential)
            ScrobbleSessions.connectListenBrainz(listenBrainz.account, listenBrainz.credential)
            val manager =
                testManager(
                    lastFm,
                    submissions,
                    submissionResult =
                        Result.failure(
                            ScrobbleApiException(
                                "expired",
                                ScrobbleFailureDisposition.AUTHENTICATION,
                            ),
                        ),
                )

            manager.onSongStart(song())
            runCurrent()

            assertEquals(lastFm, ScrobbleSessions.current(ScrobbleProvider.LAST_FM))
            assertEquals(listenBrainz, ScrobbleSessions.current(ScrobbleProvider.LISTEN_BRAINZ))
            assertEquals("SAPISID=youtube-session", Settings.shared.innerTubeCookie)
            assertEquals("discord-access", Settings.shared.discordAccessToken)
            assertEquals("discord-refresh", Settings.shared.discordRefreshToken)
        }

    @Test
    fun nowPlayingAuthenticationFailureKeepsTheAccount() =
        runTest {
            val lastFm = ScrobbleAccount(ScrobbleProvider.LAST_FM, "lastfm-user", "expired-session")
            ScrobbleSessions.connectLastFm(lastFm.account, lastFm.credential)
            val attempted = CompletableDeferred<Unit>()
            val manager =
                ScrobbleManager(
                    scope = this,
                    clock = { 1_000L },
                    startRetryLoop = false,
                    accountsOverride = { nowPlaying -> if (nowPlaying) listOf(lastFm) else emptyList() },
                    nowPlayingOverride = { _, _ ->
                        attempted.complete(Unit)
                        Result.failure(
                            ScrobbleApiException(
                                "expired",
                                ScrobbleFailureDisposition.AUTHENTICATION,
                            ),
                        )
                    },
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )

            manager.onSongStart(song())
            attempted.await()

            assertEquals(lastFm, ScrobbleSessions.current(ScrobbleProvider.LAST_FM))
        }

    @Test
    fun retryAuthenticationFailureDropsOnlyTheFailedSubmission() =
        runTest {
            val lastFm = ScrobbleAccount(ScrobbleProvider.LAST_FM, "lastfm-user", "expired-session")
            ScrobbleSessions.connectLastFm(lastFm.account, lastFm.credential)
            val pending = pending("expired", lastFm.provider, lastFm.account)
            PendingScrobbleStore.upsert(pending)
            val submissions = mutableListOf<String>()

            testManager(
                lastFm,
                submissions,
                submissionResult =
                    Result.failure(
                        ScrobbleApiException(
                            "expired",
                            ScrobbleFailureDisposition.AUTHENTICATION,
                        ),
                    ),
            ).retryPendingScrobblesForTest()

            assertEquals(listOf(pending.track), submissions)
            assertEquals(lastFm, ScrobbleSessions.current(ScrobbleProvider.LAST_FM))
            assertEquals(emptyList(), PendingScrobbleStore.all())
        }

    @Test
    fun retrySkipsMismatchedAccountAndContinuesOtherEntries() =
        runTest {
            val lastFm = ScrobbleAccount(ScrobbleProvider.LAST_FM, "current", "credential")
            val listenBrainz = ScrobbleAccount(ScrobbleProvider.LISTEN_BRAINZ, "valid", "token")
            ScrobbleSessions.connectLastFm(lastFm.account, lastFm.credential)
            ScrobbleSessions.connectListenBrainz(listenBrainz.account, listenBrainz.credential)
            val mismatched = pending("stale", lastFm.provider, "former")
            val valid = pending("valid", listenBrainz.provider, listenBrainz.account)
            PendingScrobbleStore.upsert(mismatched)
            PendingScrobbleStore.upsert(valid)
            val sends = mutableListOf<String>()

            ScrobbleManager(
                this,
                { 1000L },
                startRetryLoop = false,
                accountsOverride = { listOf(lastFm, listenBrainz) },
                submitOverride = { _, _, track, _, _, _, _ ->
                    sends += track
                    Result.success(Unit)
                },
                persistenceDispatcher = StandardTestDispatcher(testScheduler),
            ).retryPendingScrobblesForTest()

            assertEquals(listOf(valid.track), sends)
            assertEquals(listOf(mismatched), PendingScrobbleStore.all())
        }

    @Test
    fun importingNewPlaybackClearsOldInFlightState() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "fake", "credential")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val sends = mutableListOf<String>()
            val submissionGate = CompletableDeferred<Unit>()
            val manager = testManager(account, sends, submissionGate)
            val first = song()
            val replacement = song(id = "replacement")

            manager.onSongStart(first)
            runCurrent()
            assertEquals(listOf(first.title), sends)
            manager.setReportingEnabled(
                false,
                replacement,
                playing = false,
                session = DcScrobbleSession(replacement.id, 2L, 0L, false, false),
            )

            assertFalse(requireNotNull(manager.session()).pending)
            submissionGate.complete(Unit)
        }

    @Test
    fun followerDoesNotRetryPersistedScrobblesButStandaloneDoes() =
        runTest {
            val account = ScrobbleAccount(ScrobbleProvider.LAST_FM, "fake", "credential")
            ScrobbleSessions.connectLastFm(account.account, account.credential)
            val pending =
                PendingScrobble(
                    id = "pending",
                    provider = account.provider,
                    account = account.account,
                    track = "Song",
                    artist = "Artist",
                    album = null,
                    albumArtist = null,
                    duration = 300,
                    timestamp = 1L,
                    attemptCount = 1,
                    nextAttemptAtMs = 0L,
                )
            PendingScrobbleStore.upsert(pending)
            val sends = mutableListOf<String>()
            val follower = testManager(account, sends)
            follower.setReportingEnabled(false, song(), playing = false)

            follower.retryPendingScrobblesForTest()
            assertEquals(emptyList(), sends)
            assertEquals(listOf(pending), PendingScrobbleStore.all())

            val standalone = testManager(account, sends)
            standalone.retryPendingScrobblesForTest()

            assertEquals(listOf(pending.track), sends)
            assertEquals(emptyList(), PendingScrobbleStore.all())
        }

    @Test
    fun persistedLastFmSessionIsRestoredAfterProcessRestart() {
        Settings.shared.lastFmUsername = "user"
        Settings.shared.lastFmSession = "session"
        LastFM.sessionKey = null

        ScrobbleSessions.restorePersistedAccounts()

        assertEquals("session", LastFM.sessionKey)
        assertEquals("user", ScrobbleSessions.current(ScrobbleProvider.LAST_FM)?.account)
    }

    @Test
    fun repeatedPlaybackCreatesFreshInstance() =
        runTest {
            var now = 1_000L
            val scope = backgroundScope
            val manager =
                ScrobbleManager(
                    scope,
                    { now },
                    startRetryLoop = false,
                    persistenceDispatcher = StandardTestDispatcher(testScheduler),
                )
            val song = song()
            manager.onSongStart(song)
            val firstStart = manager.startedAtForTest
            now += 60_000L
            manager.onPlaybackInstanceStart(song)

            assertNotEquals(firstStart, manager.startedAtForTest)
            assertEquals(0L, manager.playedMsForTest)
            scope.cancel()
        }

    private fun TestScope.testManager(
        account: ScrobbleAccount,
        sends: MutableList<String>,
        submissionGate: CompletableDeferred<Unit>? = null,
        submissionResult: Result<Unit> = Result.success(Unit),
    ) = ScrobbleManager(
        scope = this,
        clock = { 1_000L },
        startRetryLoop = false,
        accountsOverride = { nowPlaying -> if (nowPlaying) emptyList() else listOf(account) },
        submitOverride = { _, _, track, _, _, _, _ ->
            sends += track
            submissionGate?.await()
            submissionResult
        },
        persistenceDispatcher = StandardTestDispatcher(testScheduler),
    ).apply {
        minSongDuration = 0
        scrobbleDelayPercent = 0f
        scrobbleDelaySeconds = 0
    }

    private fun pending(
        id: String,
        provider: ScrobbleProvider,
        account: String,
    ) = PendingScrobble(
        id = id,
        provider = provider,
        account = account,
        track = id,
        artist = "Artist",
        duration = 300,
        timestamp = 1L,
    )

    private fun song(id: String = "song") =
        SongItem(
            id = id,
            title = "Song",
            thumbnail = null,
            artists = listOf(ArtistTiny(null, "Artist")),
            album = null,
            duration = 300,
        )
}

private class MemoryPendingScrobbleDao : PendingScrobbleDao {
    private val entries = linkedMapOf<String, PendingScrobble>()

    override suspend fun all() = entries.values.toList()

    override suspend fun get(id: String) = entries[id]

    override suspend fun upsert(scrobbles: List<PendingScrobble>) {
        scrobbles.forEach { entries[it.id] = it }
    }

    override suspend fun importLegacy(scrobbles: List<PendingScrobble>) {
        scrobbles.forEach { if (it.id !in entries) entries[it.id] = it }
    }

    override suspend fun remove(id: String) {
        entries.remove(id)
    }

    override suspend fun clear(provider: ScrobbleProvider) {
        entries.values.removeAll { it.provider == provider }
    }
}
