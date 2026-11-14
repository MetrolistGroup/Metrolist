package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.android.data.PlayHistoryRepository
import com.metrolist.shared.db.HistoryAlbumStatistic
import com.metrolist.shared.db.HistoryArtistStatistic
import com.metrolist.shared.db.HistoryStatGroup
import com.metrolist.shared.db.HistoryStatisticsSnapshot
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.screens.StatisticsPeriod
import com.metrolist.shared.ui.statistics.StatisticsDateRange
import com.metrolist.shared.ui.statistics.StatisticsPeriodMode
import com.metrolist.shared.ui.statistics.epochRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import com.metrolist.android.data.HistoryEntry as PlayHistoryEntry

typealias StatsPeriod = StatisticsPeriod

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.WEEK,
    val dateRange: StatisticsDateRange? = null,
    val mode: StatisticsPeriodMode = StatisticsPeriodMode.CONTINUOUS,
    val topSongs: List<HistoryStatGroup> = emptyList(),
    val songItems: Map<String, SongItem> = emptyMap(),
    val topArtists: List<HistoryArtistStatistic> = emptyList(),
    val topAlbums: List<HistoryAlbumStatistic> = emptyList(),
    val totalScrobbles: Int = 0,
    val totalTimeMs: Long = 0L,
    val dailyStats: Map<String, Int> = emptyMap(),
    val hourlyStats: Map<Int, Int> = emptyMap(),
    val recentScrobbles: List<PlayHistoryEntry> = emptyList(),
    val isLoading: Boolean = true,
    val hasLoaded: Boolean = false,
    val loadFailed: Boolean = false,
    val artistCount: Int = 0,
    val albumCount: Int = 0,
)

class StatsViewModel internal constructor(
    private val loadSnapshot: suspend (Long, Long) -> HistoryStatisticsSnapshot,
    private val loadRecent: suspend () -> List<PlayHistoryEntry>,
    private val loadSongs: suspend (List<HistoryStatGroup>) -> List<SongItem>,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    constructor(
        repository: PlayHistoryRepository,
    ) : this(repository::getStatisticsSnapshot, { repository.getRecentHistory(20) }, repository::getStatisticsSongs)

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    private var statsJob: Job? = null

    init {
        loadStats(StatsPeriod.WEEK)
    }

    fun setPeriod(
        period: StatsPeriod,
        dateRange: StatisticsDateRange? = null,
        mode: StatisticsPeriodMode = _uiState.value.mode,
    ) {
        _uiState.value = _uiState.value.copy(period = period, dateRange = dateRange, mode = mode, isLoading = true)
        loadStats(period, dateRange, mode)
    }

    fun setMode(mode: StatisticsPeriodMode) {
        _uiState.value = _uiState.value.copy(mode = mode, dateRange = null, isLoading = true)
        refreshStats()
    }

    suspend fun rankedSongs(): List<SongItem> = loadSongs(_uiState.value.topSongs)

    suspend fun songItem(id: String): SongItem? =
        _uiState.value.songItems[id] ?: loadSongs(_uiState.value.topSongs.filter { it.songId == id }).firstOrNull()

    fun refreshStats() {
        loadStats(_uiState.value.period, _uiState.value.dateRange, _uiState.value.mode)
    }

    private fun loadStats(
        period: StatsPeriod,
        dateRange: StatisticsDateRange? = null,
        mode: StatisticsPeriodMode = StatisticsPeriodMode.CONTINUOUS,
    ) {
        statsJob?.cancel()
        statsJob =
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, loadFailed = false)
                try {
                    val (sinceMs, untilMs) = period.epochRange(dateRange, clock, mode = mode)

                    val snapshot = loadSnapshot(sinceMs, untilMs)
                    val songs = snapshot.songs
                    val artists = snapshot.artists
                    val albums = snapshot.albums
                    val totals = snapshot.totals
                    val daily = snapshot.daily.associate { it.bucket to it.playCount }
                    val hourly = snapshot.hourly.associate { it.bucket.toInt() to it.playCount }
                    val recent = loadRecent()

                    currentCoroutineContext().ensureActive()
                    _uiState.value =
                        StatsUiState(
                            period = period,
                            mode = mode,
                            dateRange = dateRange,
                            topSongs = songs,
                            songItems = snapshot.songItems.associateBy { it.id },
                            topArtists = artists,
                            topAlbums = albums,
                            totalScrobbles = totals.playCount,
                            totalTimeMs = totals.playTimeMs,
                            dailyStats = daily,
                            hourlyStats = hourly,
                            recentScrobbles = recent,
                            isLoading = false,
                            hasLoaded = true,
                            artistCount = artists.size,
                            albumCount = albums.size,
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _uiState.value = _uiState.value.copy(loadFailed = true)
                } finally {
                    if (currentCoroutineContext().isActive) _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
    }
}
