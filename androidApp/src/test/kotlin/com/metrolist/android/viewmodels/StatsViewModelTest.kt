package com.metrolist.android.viewmodels

import com.metrolist.android.data.HistoryEntry
import com.metrolist.shared.db.HistoryStatisticsSnapshot
import com.metrolist.shared.db.HistoryTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun reloadsStatisticsAfterRemoteHistorySyncUpdatesDatabase() =
        runTest {
            var playCount = 1
            val recent = mutableListOf(historyEntry(id = 1L, songId = "android-song"))

            val viewModel =
                StatsViewModel(
                    loadSnapshot = { _, _, _ -> snapshot(playCount) },
                    loadRecent = { recent.toList() },
                    loadSongs = { emptyList() },
                    syncHistory = {
                        playCount = 2
                        recent.add(historyEntry(id = 2L, songId = "windows-song"))
                        true
                    },
                    loadLastFm = { _, _ -> emptyList() },
                )

            assertEquals(2, viewModel.uiState.value.totalScrobbles)
            assertEquals(2, viewModel.uiState.value.recentScrobbles.size)
            assertTrue(viewModel.uiState.value.hasLoaded)
            assertFalse(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun keepsLocalStatisticsWhenRemoteHistorySyncFails() =
        runTest {
            val viewModel =
                StatsViewModel(
                    loadSnapshot = { _, _, _ -> snapshot(playCount = 1) },
                    loadRecent = { listOf(historyEntry(id = 1L, songId = "android-song")) },
                    loadSongs = { emptyList() },
                    syncHistory = { throw IllegalStateException("offline") },
                    loadLastFm = { _, _ -> emptyList() },
                )

            assertEquals(1, viewModel.uiState.value.totalScrobbles)
            assertTrue(viewModel.uiState.value.hasLoaded)
            assertFalse(viewModel.uiState.value.loadFailed)
        }

    @Test
    fun skipsSecondSnapshotLoadWhenRemoteHistorySyncHasNoChanges() =
        runTest {
            var snapshotLoads = 0
            val viewModel =
                StatsViewModel(
                    loadSnapshot = { _, _, _ ->
                        snapshotLoads++
                        snapshot(playCount = 1)
                    },
                    loadRecent = { listOf(historyEntry(id = 1L, songId = "android-song")) },
                    loadSongs = { emptyList() },
                    syncHistory = { false },
                    loadLastFm = { _, _ -> emptyList() },
                )

            assertEquals(1, snapshotLoads)
            assertEquals(1, viewModel.uiState.value.totalScrobbles)
        }

    private fun snapshot(playCount: Int) =
        HistoryStatisticsSnapshot(
            songs = emptyList(),
            artists = emptyList(),
            albums = emptyList(),
            totals = HistoryTotals(playCount = playCount, playTimeMs = 0L),
            daily = emptyList(),
            hourly = emptyList(),
        )

    private fun historyEntry(
        id: Long,
        songId: String,
    ) = HistoryEntry(
        id = id,
        songId = songId,
        title = songId,
        artists = "Artist",
        album = null,
        duration = 180_000L,
        playTimeMs = 60_000L,
        thumbnailUrl = null,
        playedAt = 1_000L,
        source = "LOCAL",
        remoteFeedbackToken = null,
    )
}
