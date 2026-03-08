package com.metrolist.shared.youtube.strategy

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.innertubex.utils.sha1
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.time.Clock

private const val CLIENT_HEALTH_SCHEMA = 2

@Serializable
data class ClientHealth(
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastFailure: ClientFailureKind? = null,
    val lastFailureAtMs: Long? = null,
    val cooldownUntilMs: Long? = null,
)

class ClientHealthTracker(
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val settings: Settings = Settings.shared,
    private val accountFingerprint: () -> String =
        { settings.dataSyncId + "|" + settings.accountName + "|" + settings.accountEmail },
    private val networkState: () -> PlaybackNetworkState = { PlaybackNetworkStateStore.current },
    private val persistenceEnabled: Boolean = true,
) : ClientHealthMonitor {
    private data class HealthKey(
        val clientId: String,
        val scope: ClientHealthScope?,
        val account: String,
        val network: String,
    )

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val lock = SynchronizedObject()
    private val state = MutableStateFlow(if (persistenceEnabled) load() else emptyMap())

    private fun key(
        clientId: String,
        scope: ClientHealthScope?,
    ): HealthKey = HealthKey(clientId, scope, sha1(accountFingerprint()).take(32), networkState().healthScope())

    fun health(
        clientId: String,
        scope: ClientHealthScope? = null,
    ): ClientHealth = state.value[key(clientId, scope)] ?: ClientHealth()

    override fun scoreAdjustment(
        clientId: String,
        scope: ClientHealthScope?,
    ): Int = healthAdjustment(health(clientId)) + (scope?.let { healthAdjustment(health(clientId, it)) } ?: 0)

    private fun healthAdjustment(health: ClientHealth): Int {
        val successBonus = min(health.successes, MAX_SUCCESS_BONUS_COUNT) * SUCCESS_BONUS
        val failurePenalty = min(health.consecutiveFailures, MAX_CONSECUTIVE_FAILURES) * FAILURE_PENALTY
        val cooldownPenalty = if ((health.cooldownUntilMs ?: 0L) > nowMs()) COOLDOWN_PENALTY else 0
        return successBonus - failurePenalty - cooldownPenalty
    }

    fun clientsInCooldown(scope: ClientHealthScope): Set<String> {
        val current = key("", scope)
        val now = nowMs()
        return state.value.entries
            .filter { (key, health) ->
                key.account == current.account && key.network == current.network &&
                    (key.scope == null || key.scope == scope) && (health.cooldownUntilMs ?: 0L) > now &&
                    (health.lastFailure == ClientFailureKind.PLAYABILITY || health.lastFailure == ClientFailureKind.PLAYER_REQUEST)
            }.mapTo(mutableSetOf()) { it.key.clientId }
    }

    override fun recordSuccess(
        clientId: String,
        scope: ClientHealthScope?,
    ) {
        synchronized(persistenceLock) {
            synchronized(lock) {
                val healthByClient = if (persistenceEnabled) load() else state.value
                val key = key(clientId, scope)
                val current = healthByClient[key] ?: ClientHealth()
                state.value =
                    (healthByClient - key) +
                    (
                        key to
                            current.copy(
                                successes = current.successes + 1,
                                consecutiveFailures = 0,
                                cooldownUntilMs = null,
                            )
                    )
                if (persistenceEnabled) persistLocked()
            }
        }
    }

    override fun recordFailure(
        clientId: String,
        kind: ClientFailureKind,
        scope: ClientHealthScope?,
    ) {
        val timestamp = nowMs()
        synchronized(persistenceLock) {
            synchronized(lock) {
                val healthByClient = if (persistenceEnabled) load() else state.value
                val key = key(clientId, scope)
                val current = healthByClient[key] ?: ClientHealth()
                val consecutive = current.consecutiveFailures + 1
                val cooldownMs =
                    when {
                        kind == ClientFailureKind.MEDIA_FORBIDDEN -> FORBIDDEN_COOLDOWN_MS
                        consecutive >= FAILURES_BEFORE_COOLDOWN -> DEFAULT_COOLDOWN_MS
                        else -> 0L
                    }
                state.value =
                    (healthByClient - key) +
                    (
                        key to
                            current.copy(
                                failures = current.failures + 1,
                                consecutiveFailures = consecutive,
                                lastFailure = kind,
                                lastFailureAtMs = timestamp,
                                cooldownUntilMs = (timestamp + cooldownMs).takeIf { cooldownMs > 0L },
                            )
                    )
                if (persistenceEnabled) persistLocked()
            }
        }
    }

    /** A track that every client reports unavailable says nothing about the clients, so it must not cool them down. */
    fun forgivePlayabilityFailuresSince(sinceMs: Long) {
        synchronized(persistenceLock) {
            synchronized(lock) {
                val current = key("", null)
                state.value =
                    (if (persistenceEnabled) load() else state.value).mapValues { (key, health) ->
                        if (key.account == current.account && key.network == current.network &&
                            health.lastFailure == ClientFailureKind.PLAYABILITY && (health.lastFailureAtMs ?: 0L) >= sinceMs
                        ) {
                            health.copy(consecutiveFailures = 0, cooldownUntilMs = null)
                        } else {
                            health
                        }
                    }
                if (persistenceEnabled) persistLocked()
            }
        }
    }

    fun diagnosticSummary(): String {
        val now = nowMs()
        val current = key("", null)
        return state.value.entries
            .filter { (key, _) -> key.account == current.account && key.network == current.network }
            .filter { (_, health) -> health.successes > 0 || health.failures > 0 }
            .sortedBy { it.key.clientId }
            .joinToString("; ") { (key, health) ->
                val scope =
                    key.scope?.let { current ->
                        buildString {
                            append(current.content.name)
                            append(if (current.authenticated) "/auth" else "/anon")
                            if (current.wantVideo) append("/video")
                        }
                    } ?: "global"
                buildString {
                    append("${key.clientId}[$scope] ok=${health.successes} fail=${health.failures} streak=${health.consecutiveFailures}")
                    health.lastFailure?.let { append(" last=${it.name}") }
                    health.cooldownUntilMs?.takeIf { it > now }?.let { remaining ->
                        append(" cooldownMs=${remaining - now}")
                    }
                }
            }
    }

    fun reset(
        clientId: String? = null,
        scope: ClientHealthScope? = null,
    ) {
        synchronized(persistenceLock) {
            synchronized(lock) {
                val persisted = if (persistenceEnabled) load() else state.value
                state.value =
                    if (clientId == null) {
                        emptyMap()
                    } else if (scope == null) {
                        persisted.filterKeys { it.clientId != clientId }
                    } else {
                        persisted - key(clientId, scope)
                    }
                if (persistenceEnabled) persistLocked()
            }
        }
    }

    fun snapshot(): Map<String, ClientHealth> =
        state.value
            .filterKeys { it.account == sha1(accountFingerprint()).take(32) && it.network == networkState().healthScope() }
            .mapKeys { (key, _) -> "${key.clientId}|${key.scope}" }

    private fun load(): Map<HealthKey, ClientHealth> =
        runCatching {
            json
                .decodeFromString<HealthSnapshot>(settings.playbackClientHealthJson)
                .takeIf { it.schema == CLIENT_HEALTH_SCHEMA }
                ?.entries
                .orEmpty()
                .takeLast(MAX_PERSISTED_ENTRIES)
                .associate { HealthKey(it.clientId, it.scope, it.account, it.network) to it.health }
        }.getOrDefault(emptyMap())

    private fun persistLocked() {
        settings.playbackClientHealthJson =
            json.encodeToString(
                HealthSnapshot(
                    entries =
                        state.value.entries.toList().takeLast(MAX_PERSISTED_ENTRIES).map { entry ->
                            val key = entry.key
                            PersistedHealth(key.clientId, key.scope, key.account, key.network, entry.value)
                        },
                ),
            )
    }

    private companion object {
        val persistenceLock = SynchronizedObject()

        const val MAX_SUCCESS_BONUS_COUNT = 5
        const val SUCCESS_BONUS = 2
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val FAILURE_PENALTY = 18
        const val COOLDOWN_PENALTY = 1_000
        const val FAILURES_BEFORE_COOLDOWN = 2
        const val DEFAULT_COOLDOWN_MS = 2 * 60 * 1000L
        const val FORBIDDEN_COOLDOWN_MS = 10 * 60 * 1000L
        const val MAX_PERSISTED_ENTRIES = 128
    }
}

private fun PlaybackNetworkState.healthScope(): String = "$scope:${if (connected) "validated" else "offline"}"

@Serializable
private data class HealthSnapshot(
    val schema: Int = CLIENT_HEALTH_SCHEMA,
    val entries: List<PersistedHealth> = emptyList(),
)

@Serializable
private data class PersistedHealth(
    val clientId: String,
    val scope: ClientHealthScope?,
    val account: String,
    val network: String,
    val health: ClientHealth,
)
