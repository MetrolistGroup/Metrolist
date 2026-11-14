package com.metrolist.android.viewmodels

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkConfig
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkProgress
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkReport
import com.metrolist.shared.youtube.benchmark.PlaybackBenchmarkRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaybackBenchmarkViewModel(
    private val runner: PlaybackBenchmarkRunner,
    context: Context,
) : ViewModel() {
    val progress: StateFlow<PlaybackBenchmarkProgress> = runner.progress
    private val _report = MutableStateFlow<PlaybackBenchmarkReport?>(null)
    val report: StateFlow<PlaybackBenchmarkReport?> = _report.asStateFlow()
    private var runJob: Job? = null
    private val wakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Metrolist:PlaybackBenchmark")
            .apply { setReferenceCounted(false) }

    fun run(
        config: PlaybackBenchmarkConfig,
        onFinished: (PlaybackBenchmarkReport) -> Unit = {},
    ) {
        val previous = runJob
        previous?.cancel()
        _report.value = null
        runJob =
            viewModelScope.launch {
                previous?.cancelAndJoin()
                val caseBudgetMs =
                    config.playbackWindowPerCaseMs +
                        2 * config.seekSettleTimeoutMs +
                        60_000L
                wakeLock.acquire((caseBudgetMs * config.samples.size * config.selectedProfiles.size) + 60_000L)
                try {
                    runner.run(config).also { report ->
                        _report.value = report
                        onFinished(report)
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
    }

    fun cancel() {
        runJob?.cancel()
    }

    override fun onCleared() {
        cancel()
        if (wakeLock.isHeld) wakeLock.release()
    }
}
