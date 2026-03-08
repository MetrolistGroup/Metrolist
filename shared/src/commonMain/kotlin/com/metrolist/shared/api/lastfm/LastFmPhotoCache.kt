package com.metrolist.shared.api.lastfm

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalInnerTube
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.TimeSource

internal class LastFmPhotoBackoff(
    val retryAfterMs: Long,
    val details: LastFmPhotoDetails? = null,
) : Exception()

private val photoCacheClock = TimeSource.Monotonic.markNow()

internal class LastFmPhotoCache(
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val load: suspend (String) -> LastFmPhotoDetails?,
    private val now: () -> Long = { photoCacheClock.elapsedNow().inWholeMilliseconds },
    private val wallNow: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    @Serializable
    private data class Entry(
        val details: LastFmPhotoDetails,
        val expiresAt: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val entries =
        MutableStateFlow(
            runCatching {
                json
                    .decodeFromString<Map<String, Entry>>(read())
                    .toList()
                    .takeLast(MAX_ENTRIES)
            }.getOrDefault(emptyList()),
        )

    // Guards only the network path, so a cache hit never waits behind a throttled request.
    private val mutex = Mutex()
    private var nextRequestAt = 0L
    private var nextPrefetchAt = 0L
    private var cooldownUntil = 0L

    fun peek(url: String): LastFmPhotoDetails? = entries.value.entry(url)?.details

    private fun fresh(url: String): LastFmPhotoDetails? {
        var hit: Entry? = null
        entries.update { current ->
            hit = current.entry(url)?.takeIf { wallNow() < it.expiresAt }
            hit?.let { current.without(url) + (url to it) } ?: current
        }
        return hit?.details
    }

    // An ordered list rather than a map: StateFlow drops updates that compare equal, and maps ignore order.
    private fun List<Pair<String, Entry>>.entry(url: String) = firstOrNull { it.first == url }?.second

    private fun List<Pair<String, Entry>>.without(url: String) = filter { it.first != url }

    suspend fun get(
        url: String,
        prefetch: Boolean = false,
    ): LastFmPhotoDetails? {
        fresh(url)?.let { return it }
        if (prefetch) {
            // Wait outside the lock, so a foreground load never queues behind a sleeping prefetch.
            if (now() < cooldownUntil) return peek(url)
            delay((nextPrefetchAt - now()).coerceAtLeast(0))
        }
        return mutex.withLock {
            fresh(url)?.let { return@withLock it }
            val stale = peek(url)
            if (prefetch && now() < maxOf(nextPrefetchAt, cooldownUntil)) return@withLock stale
            delay((maxOf(nextRequestAt, cooldownUntil) - now()).coerceAtLeast(0))

            var backoffMs: Long? = null

            suspend fun fetch(): LastFmPhotoDetails? {
                nextRequestAt = now() + 1_000
                backoffMs = null
                return try {
                    load(url)
                } catch (error: LastFmPhotoBackoff) {
                    val wait = error.retryAfterMs.coerceAtLeast(5_000)
                    backoffMs = wait
                    cooldownUntil = now() + wait
                    error.details
                } finally {
                    nextPrefetchAt = now() + 5_000
                }
            }
            var details = fetch()
            if (details == null && !prefetch && now() < cooldownUntil) {
                delay((maxOf(nextRequestAt, cooldownUntil) - now()).coerceAtLeast(0))
                details = fetch()
            }
            details ?: return@withLock stale
            val partial = backoffMs != null
            val stored = if (partial) details.copy(authorAvatarUrl = details.authorAvatarUrl ?: stale?.authorAvatarUrl) else details
            entries.update { (it.without(url) + (url to Entry(stored, wallNow() + (backoffMs ?: TTL_MS)))).takeLast(MAX_ENTRIES) }
            write(json.encodeToString(entries.value.toMap()))
            stored
        }
    }

    companion object {
        private const val MAX_ENTRIES = 100
        private const val TTL_MS = 24 * 60 * 60_000L

        val shared by lazy {
            LastFmPhotoCache(
                read = { Settings.shared.lastFmPhotoCacheJson },
                write = { Settings.shared.lastFmPhotoCacheJson = it },
                load = { url -> LastFM.getPhotoDetails(GlobalInnerTube.client, url, LastFM.renderedPageLoader) },
            )
        }
    }
}
