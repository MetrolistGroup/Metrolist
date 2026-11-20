package com.metrolist.shared.playback

import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Per-profile resolve and playback outcome counts, reported once every [REPORT_EVERY_LAUNCHES] launches.
 * Keys are catalog profile IDs only, so the stored map stays bounded and carries no media identifiers.
 */
object PlaybackClientStats {
    const val REPORT_EVERY_LAUNCHES = 5
    private val mutex = Mutex()

    suspend fun recordAttempts(diagnostics: StreamDiagnostics?) {
        val counters =
            diagnostics
                ?.attempts
                .orEmpty()
                .mapNotNull { attempt ->
                    val bucket = attemptBucket(attempt.outcome) ?: return@mapNotNull null
                    profileKey(attempt.profileId)?.let { it to "attempt.$bucket" }
                }
        if (counters.isNotEmpty()) update { stats -> counters.forEach { (profile, counter) -> stats.increment(profile, counter) } }
    }

    suspend fun recordPlayback(
        profileId: String?,
        failure: PlaybackFailureCategory? = null,
    ) {
        val profile = profileKey(profileId) ?: return
        update { it.increment(profile, failure?.let { category -> "play.fail.${category.name.lowercase()}" } ?: "play.ok") }
    }

    /** Counts this launch and, on every [REPORT_EVERY_LAUNCHES]th one, returns and clears the counts. */
    suspend fun takeReportOnLaunch(): Map<String, Map<String, Int>>? =
        mutex.withLock {
            val launches = Settings.shared.playbackClientStatsLaunches + 1
            if (launches < REPORT_EVERY_LAUNCHES) {
                Settings.shared.playbackClientStatsLaunches = launches
                return@withLock null
            }
            Settings.shared.playbackClientStatsLaunches = 0
            read().also { Settings.shared.playbackClientStatsJson = "{}" }.takeIf { it.isNotEmpty() }
        }

    private suspend fun update(block: (MutableMap<String, MutableMap<String, Int>>) -> Unit) =
        mutex.withLock {
            val stats = read().mapValuesTo(mutableMapOf()) { it.value.toMutableMap() }
            block(stats)
            Settings.shared.playbackClientStatsJson = Json.encodeToString(stats)
        }

    private fun read(): Map<String, Map<String, Int>> =
        runCatching { Json.decodeFromString<Map<String, Map<String, Int>>>(Settings.shared.playbackClientStatsJson) }
            .getOrDefault(emptyMap())

    private fun MutableMap<String, MutableMap<String, Int>>.increment(
        profile: String,
        counter: String,
    ) {
        val counts = getOrPut(profile) { mutableMapOf() }
        counts[counter] = (counts[counter] ?: 0) + 1
    }

    private fun profileKey(profileId: String?): String? {
        val manifestId = PlaybackClientCatalog.manifestIdFromProfileId(profileId) ?: return null
        return profileId?.takeIf { it.substringAfter("__", "") in TOKEN_SUFFIXES } ?: manifestId
    }

    // Selection rejections are logged for every unused client on each resolve, so they are not attempts.
    private fun attemptBucket(outcome: String): String? =
        when {
            outcome in PLAIN_OUTCOMES -> outcome
            outcome.startsWith("selection:") -> null
            outcome.startsWith("playability:") -> "playability"
            outcome.startsWith("request:") -> "request_failure"
            outcome.startsWith("transport:") -> "transport"
            else -> "other"
        }

    private val TOKEN_SUFFIXES = setOf("po", "nopo")
    private val PLAIN_OUTCOMES = setOf("playable_response", "no_playable_response", "po_token_unavailable", "request_budget_exhausted")
}
