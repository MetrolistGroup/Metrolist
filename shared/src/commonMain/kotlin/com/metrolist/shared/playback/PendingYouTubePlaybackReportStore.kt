package com.metrolist.shared.playback

import com.metrolist.shared.settings.Settings
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

@Serializable
internal data class PendingYouTubePlaybackReport(
    val id: String,
    val accountKey: String,
    val videoId: String,
    val clientPlaybackNonce: String,
    val format: Int?,
    val pings: List<PendingYouTubePlaybackPing>,
    val lastAcknowledgedOffsetMs: Long = 0L,
    val attemptCount: Int = 0,
    val nextAttemptAtMs: Long = 0L,
)

@Serializable
internal sealed interface PendingYouTubePlaybackPing {
    val offsetMs: Long

    @Serializable
    data class Start(
        override val offsetMs: Long,
        val currentMediaTimeMs: Long,
    ) : PendingYouTubePlaybackPing

    @Serializable
    data class Watchtime(
        override val offsetMs: Long,
        val startSeconds: String,
        val endSeconds: String,
        val currentMediaTimeMs: Long,
        val realTimeMs: Long,
        val final: Boolean,
        val segments: List<Pair<Long, Long>>,
    ) : PendingYouTubePlaybackPing
}

internal object PendingYouTubePlaybackReportStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val revision = MutableStateFlow(0L)
    internal val changes = revision.asStateFlow()
    private val lock = SynchronizedObject()
    private var currentGeneration = 0L

    val generation: Long
        get() = synchronized(lock) { currentGeneration }

    fun all(): List<PendingYouTubePlaybackReport> = synchronized(lock) { read() }

    fun append(
        report: PendingYouTubePlaybackReport,
        ping: PendingYouTubePlaybackPing,
        expectedGeneration: Long = generation,
    ): Boolean =
        synchronized(lock) {
            if (expectedGeneration != currentGeneration) return@synchronized false
            val entries = read()
            val existing = entries.firstOrNull { it.id == report.id }
            val updated =
                if (existing == null) {
                    if (entries.size >= MAX_PENDING_REPORTS || totalPings(entries) >= MAX_PENDING_PINGS) return@synchronized false
                    report.copy(pings = listOf(ping))
                } else {
                    if (ping in existing.pings) return@synchronized true
                    if (totalPings(entries) >= MAX_PENDING_PINGS) return@synchronized false
                    existing.copy(pings = existing.pings + ping)
                }
            write(entries.filterNot { it.id == report.id } + updated)
            true
        }

    fun upsert(report: PendingYouTubePlaybackReport) =
        synchronized(lock) {
            val entries = read()
            if (entries.none { it.id == report.id } && entries.size >= MAX_PENDING_REPORTS) return@synchronized
            write(entries.filterNot { it.id == report.id } + report)
        }

    fun markRetry(
        id: String,
        nowMs: Long,
    ) = synchronized(lock) { write(read().map { if (it.id == id) it.nextAttempt(nowMs) else it }) }

    fun remove(id: String) = synchronized(lock) { write(read().filterNot { it.id == id }) }

    fun removeIfEmpty(id: String) =
        synchronized(lock) {
            val entries = read()
            if (entries.any { it.id == id && it.pings.isEmpty() }) {
                write(entries.filterNot { it.id == id && it.pings.isEmpty() })
            }
        }

    fun removePing(
        id: String,
        ping: PendingYouTubePlaybackPing,
        retainEmpty: Boolean = false,
    ) = synchronized(lock) {
        val updated =
            read().mapNotNull { report ->
                if (report.id != id) return@mapNotNull report
                val pings = report.pings.toMutableList().also { it.remove(ping) }
                report.copy(pings = pings, lastAcknowledgedOffsetMs = ping.offsetMs).takeIf { pings.isNotEmpty() || retainEmpty }
            }
        write(updated)
    }

    fun clear() =
        synchronized(lock) {
            currentGeneration++
            write(emptyList())
        }

    private fun read(): List<PendingYouTubePlaybackReport> {
        val raw = Settings.shared.queuedYouTubeHistoryJson.ifBlank { "[]" }
        return runCatching {
            val elements = json.parseToJsonElement(raw).jsonArray
            val valid = elements.mapNotNull { runCatching { json.decodeFromJsonElement<PendingYouTubePlaybackReport>(it) }.getOrNull() }
            if (valid.size != elements.size) write(valid)
            valid
        }.getOrElse {
            write(emptyList())
            emptyList()
        }
    }

    private fun write(entries: List<PendingYouTubePlaybackReport>) {
        Settings.shared.queuedYouTubeHistoryJson = json.encodeToString(entries)
        revision.update { it + 1L }
    }

    private fun totalPings(entries: List<PendingYouTubePlaybackReport>): Int = entries.sumOf { it.pings.size }

    private fun PendingYouTubePlaybackReport.nextAttempt(nowMs: Long): PendingYouTubePlaybackReport {
        val attempts = attemptCount + 1
        val backoff = (BASE_RETRY_MS * (1L shl (attempts - 1).coerceIn(0, 6))).coerceAtMost(MAX_RETRY_MS)
        return copy(attemptCount = attempts, nextAttemptAtMs = nowMs + backoff)
    }

    internal const val MAX_PENDING_REPORTS = 1_000
    internal const val MAX_PENDING_PINGS = 50_000
    private const val BASE_RETRY_MS = 30_000L
    private const val MAX_RETRY_MS = 60L * 60L * 1000L
}
