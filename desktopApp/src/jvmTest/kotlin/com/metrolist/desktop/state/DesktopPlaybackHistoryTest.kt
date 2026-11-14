package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.DesktopPlaybackHistory
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopPlaybackHistoryTest {
    private var previousSettings: Settings? = null

    @BeforeTest
    fun setUp() {
        previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
    }

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(previousSettings)

    @Test
    fun resumingHistoryPreservesOnlyAllowedListeningTime() =
        runBlocking {
            val previous = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            try {
                var now = 0L
                val recorded = mutableListOf<Long>()
                val history = DesktopPlaybackHistory({ now }, { _, time -> recorded += time })
                Settings.shared.pauseListenHistory = true
                history.restart(song, true)
                now += 60_000
                Settings.shared.pauseListenHistory = false
                history.onHistoryPausedChanged(false)
                now += 1_000
                val first = history.restart(song, true)!!
                history.recordPreviousSong(first.first, first.second, true, 30_000, false)
                assertEquals(1_000L, first.second)
                assertEquals(emptyList(), recorded)
                now += 20_000
                Settings.shared.pauseListenHistory = true
                history.onHistoryPausedChanged(true)
                now += 60_000
                Settings.shared.pauseListenHistory = false
                history.onHistoryPausedChanged(false)
                now += 15_000
                assertEquals(35_000L, history.elapsedMs())
            } finally {
                Settings.replaceSharedForTest(previous)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun releaseWaitsForAnInFlightRecordAndConsumesPausedSessionOnce() =
        runTest {
            var now = 0L
            val recording = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val records = mutableListOf<Pair<SongItem, Long>>()
            val history =
                DesktopPlaybackHistory({ now }, { song, time ->
                    recording.complete(Unit)
                    finish.await()
                    records += song to time
                })
            history.restart(song, true)
            now = 40_000L
            history.onPlayingChanged(false)
            now = 100_000L
            val transition = launch { history.finalizeSession(reportingEnabled = true, minDurationMs = 30_000L, pauseHistory = false) }
            recording.await()
            val release =
                launch { history.finalizeSession(reportingEnabled = true, minDurationMs = 30_000L, pauseHistory = false, close = true) }
            runCurrent()
            assertFalse(release.isCompleted)
            finish.complete(Unit)
            transition.join()
            release.join()
            history.finalizeSession(reportingEnabled = true, minDurationMs = 30_000L, pauseHistory = false, close = true)
            assertEquals(listOf(song to 40_000L), records)
            assertNull(history.elapsedMs())
        }

    @Test
    fun releaseHonorsPrivacyAndQualificationGates() =
        runTest {
            for ((enabled, paused, threshold) in listOf(Triple(false, false, 0L), Triple(true, true, 0L), Triple(true, false, 60_000L))) {
                var now = 0L
                val history = DesktopPlaybackHistory({ now }, { _, _ -> error("must not record") })
                history.restart(song, true)
                now = 40_000L
                history.finalizeSession(reportingEnabled = enabled, minDurationMs = threshold, pauseHistory = paused, close = true)
                assertNull(history.elapsedMs())
            }
        }

    @Test
    fun metadataUpdatesPreserveListeningAndRepeatCreatesSeparateCompletedSessions() =
        runBlocking {
            var now = 1_000L
            val recorded = mutableListOf<Pair<SongItem, Long>>()
            val history = DesktopPlaybackHistory({ now }, { song, time -> recorded += song to time })
            history.restart(song, true)
            now += 20_000L
            val enriched = song.copy(duration = 120L)
            history.updateMetadata(enriched)
            history.onPlayingChanged(true)
            now += 20_000L
            val first = history.restart(enriched, true)!!
            history.recordPreviousSong(first.first, first.second, true, 30_000L, false)
            now += 40_000L
            val second = history.restart(enriched, true)!!
            history.recordPreviousSong(second.first, second.second, true, 30_000L, false)
            assertEquals(listOf(enriched to 40_000L, enriched to 40_000L), recorded)
            assertEquals(0L, history.elapsedMs())
        }

    private val song = SongItem(id = "played", title = "Played", artists = emptyList(), thumbnail = null, album = null)

    @Test
    fun accumulatedPlaybackExcludesPausesAndHonorsReportingGate() =
        runBlocking {
            var now = 1_000L
            val recorded = mutableListOf<Pair<SongItem, Long>>()
            val history = DesktopPlaybackHistory({ now }, { song, playedMs -> recorded += song to playedMs })
            history.reset(song.id, song)
            history.onPlayingChanged(true)
            now += 2_000
            history.onPlayingChanged(false)
            now += 20_000
            history.onPlayingChanged(true)
            now += 3_000
            assertEquals(5_000L, history.elapsedMs())
            history.recordPreviousSong(song, history.elapsedMs()!!, true, 6_000L, false)
            history.recordPreviousSong(song, history.elapsedMs()!!, false, 4_000L, false)
            history.recordPreviousSong(song, history.elapsedMs()!!, true, 4_000L, true)
            assertEquals(emptyList(), recorded)
            history.recordPreviousSong(song, history.elapsedMs()!!, true, 4_000L, false)
            assertEquals(listOf(song to 5_000L), recorded)
            history.reset(null, null)
            assertNull(history.elapsedMs())
        }
}
