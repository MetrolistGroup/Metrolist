package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import com.metrolist.shared.db.HistoryStatisticsSnapshot
import com.metrolist.shared.db.HistoryTotals
import com.metrolist.shared.ui.statistics.StatisticsDateRange
import com.metrolist.shared.ui.statistics.StatisticsPeriodMode
import com.metrolist.shared.ui.statistics.epochRange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2024-07-18T12:00:00Z")
        }

    private fun snapshot(plays: Int = 0) =
        HistoryStatisticsSnapshot(emptyList(), emptyList(), emptyList(), HistoryTotals(plays, 0), emptyList(), emptyList())

    @Test
    fun periodModeAndCustomSelectionReachTheQueriesAndSurviveRefresh() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ViewModelStore()
            val queries = mutableListOf<Pair<Long, Long>>()
            try {
                val model =
                    StatsViewModel({ start, end ->
                        queries += start to end
                        snapshot()
                    }, { emptyList() }, { emptyList() }, clock)
                store.put("statistics", model)
                advanceUntilIdle()
                model.setMode(StatisticsPeriodMode.CALENDAR)
                model.setPeriod(StatsPeriod.THREE_MONTHS)
                advanceUntilIdle()
                assertEquals(StatsPeriod.THREE_MONTHS.epochRange(clock = clock, mode = StatisticsPeriodMode.CALENDAR), queries.last())
                val dates = StatisticsDateRange(LocalDate(2024, 2, 1), LocalDate(2024, 2, 29))
                model.setPeriod(StatsPeriod.THREE_MONTHS, dates)
                advanceUntilIdle()
                assertEquals(dates.epochRange(), queries.last())
                assertEquals(dates, model.uiState.value.dateRange)
                model.refreshStats()
                advanceUntilIdle()
                assertEquals(dates.epochRange(), queries.last())
                model.setPeriod(StatsPeriod.MONTH)
                advanceUntilIdle()
                assertNull(model.uiState.value.dateRange)
                assertEquals(StatisticsPeriodMode.CALENDAR, model.uiState.value.mode)
                assertEquals(StatsPeriod.MONTH.epochRange(clock = clock, mode = StatisticsPeriodMode.CALENDAR), queries.last())
                model.setPeriod(StatsPeriod.MONTH, dates)
                advanceUntilIdle()
                model.setMode(StatisticsPeriodMode.CONTINUOUS)
                advanceUntilIdle()
                assertNull(model.uiState.value.dateRange)
                assertEquals(StatsPeriod.MONTH.epochRange(clock = clock), queries.last())
                assertFalse(model.uiState.value.isLoading)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun switchingModeCancelsOldRequestsAndRetainsTheSnapshotDuringRefresh() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ViewModelStore()
            val pending = CompletableDeferred<HistoryStatisticsSnapshot>()
            var call = 0
            try {
                val model =
                    StatsViewModel({ _, _ ->
                        when (++call) {
                            1 -> snapshot(5)
                            2 -> pending.await()
                            else -> snapshot(9)
                        }
                    }, { emptyList() }, { emptyList() }, clock)
                store.put("statistics", model)
                advanceUntilIdle()
                model.setPeriod(StatsPeriod.YEAR)
                runCurrent()
                assertTrue(model.uiState.value.isLoading)
                assertTrue(model.uiState.value.hasLoaded)
                assertEquals(5, model.uiState.value.totalScrobbles)
                model.setMode(StatisticsPeriodMode.CALENDAR)
                advanceUntilIdle()
                pending.complete(snapshot(1))
                advanceUntilIdle()
                assertEquals(9, model.uiState.value.totalScrobbles)
                assertEquals(StatisticsPeriodMode.CALENDAR, model.uiState.value.mode)
                assertFalse(model.uiState.value.isLoading)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
}
