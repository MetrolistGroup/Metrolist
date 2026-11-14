package com.metrolist.shared.youtube.benchmark

import com.metrolist.innertubex.extraction.strategy.PlaybackClientOption

enum class PlaybackBenchmarkCase {
    NORMAL,
    EXPLICIT,
    KIDS,
}

data class PlaybackBenchmarkSample(
    val videoId: String,
    val case: PlaybackBenchmarkCase,
)

data class PlaybackBenchmarkConfig(
    val samples: List<PlaybackBenchmarkSample>,
    val selectedProfiles: List<PlaybackClientOption>,
    val anonymousSession: Boolean = false,
    val includeAutomaticSelection: Boolean = true,
    val playbackWindowPerCaseMs: Long = 90_000L,
    val seekOffsetMs: Long = 5_000L,
    val seekSettleTimeoutMs: Long = 10_000L,
) {
    init {
        require(samples.isNotEmpty()) { "At least one benchmark sample is required" }
        require(
            samples.map { it.case }.toSet() == PlaybackBenchmarkCase.entries.toSet(),
        ) {
            "The benchmark requires at least one normal, explicit, and kids sample"
        }
        require(includeAutomaticSelection || selectedProfiles.isNotEmpty()) {
            "Automatic selection or at least one playback profile is required"
        }
        require(playbackWindowPerCaseMs >= 90_000L) { "Each benchmark case must play for at least 90 seconds" }
        require(seekOffsetMs > 0L)
        require(seekSettleTimeoutMs > 0L)
    }
}

data class PlaybackBenchmarkProgress(
    val completedCases: Int = 0,
    val totalCases: Int = 0,
    val activeProfileId: String? = null,
    val activeCase: PlaybackBenchmarkCase? = null,
    val running: Boolean = false,
    val cancelled: Boolean = false,
)

enum class PlaybackBenchmarkStatus {
    PASSED,
    FAILED,
    CANCELLED,
}

data class PlaybackBenchmarkAssertions(
    val exactlyOneProfileAttempt: Boolean,
    val requestedProfileUsed: Boolean,
    val playerResponseUsable: Boolean,
    val noHttp4xxOrMediaFailure: Boolean,
    val stableDuration: Boolean,
    val playbackAdvanced: Boolean,
    val seekSettled: Boolean,
    val oneLogicalNonce: Boolean,
    val nonOverlappingWatchtime: Boolean,
    val finalRecorded: Boolean,
    val singleViewStart: Boolean = true,
) {
    val passed: Boolean
        get() =
            exactlyOneProfileAttempt && requestedProfileUsed && playerResponseUsable && noHttp4xxOrMediaFailure &&
                stableDuration && playbackAdvanced && seekSettled && singleViewStart && oneLogicalNonce &&
                nonOverlappingWatchtime && finalRecorded
}

data class PlaybackBenchmarkCaseResult(
    val profileId: String,
    val case: PlaybackBenchmarkCase,
    val sampleId: String,
    val nonce: String,
    val status: PlaybackBenchmarkStatus,
    val assertions: PlaybackBenchmarkAssertions,
    val durationMs: Long? = null,
    val advancedMs: Long? = null,
    val forwardSeekMs: Long? = null,
    val backwardSeekMs: Long? = null,
    val recordedWatchtimeMs: Long = 0L,
    val finalRecorded: Boolean = false,
    val attemptedProfileIds: List<String> = emptyList(),
    val mediaStatuses: List<Int> = emptyList(),
    val failureCategory: String? = null,
    val failureType: String? = null,
    val selectedClientName: String? = null,
    val selectedProfileId: String? = null,
    val transport: String? = null,
    val usedPoToken: Boolean = false,
    val usedAuthenticatedWatchPage: Boolean = false,
    val playerResponseAttempts: Int = 0,
    val fallbackHops: Int = 0,
    val resolutionMs: Long? = null,
    val preparationMs: Long? = null,
    val firstMediaResponseMs: Long? = null,
    val timeToPlaybackStartMs: Long? = null,
)

data class PlaybackBenchmarkReport(
    val results: List<PlaybackBenchmarkCaseResult>,
    val cancelled: Boolean,
    val anonymousSession: Boolean = false,
)
