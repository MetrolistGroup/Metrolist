package com.metrolist.music.utils.cipher

import android.content.Context
import android.util.Base64
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Owns the player-config table at runtime: bundled asset as the offline default, overlaid
 * by the same JSON fetched from the remote source so rotated players are fixed without
 * an APK update. Parsing/validation is delegated to [PlayerConfigParser]; only validated
 * payloads ever replace the in-memory map or touch the disk cache.
 *
 * Read path is lock-free: lookups hit an immutable map behind a @Volatile reference that
 * refreshes swap wholesale.
 */
object PlayerConfigStore {
    private const val TAG = "Metrolist_CipherConfig"
    private const val ASSET_NAME = "player_configs.json"

    private val REMOTE_URL by lazy {
        val encoded = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL01ldHJvbGlzdEdyb3VwL01ldHJvbGlzdC9tYWluL2FwcC9zcmMvbWFpbi9hc3NldHMvcGxheWVyX2NvbmZpZ3MuanNvbg=="
        String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
    }

    // Mirrors PlayerJsFetcher.CACHE_TTL_MS.
    private const val REFRESH_TTL_MS = 6 * 60 * 60 * 1000L

    // Failure-triggered refreshes are rate-limited so a player that is unknown both locally
    // and remotely doesn't turn every song into a GitHub request.
    private const val FORCE_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L

    // Note: names must not start with "player_" — PlayerJsFetcher.writeToCache() purges
    // "player_*" from this shared dir on every player-JS refresh. PlayerJsFetcher.invalidateCache()
    // deletes only player_*/current_hash.txt, so the config cache + ETag survive cipher retries.
    private const val CACHE_FILE = "configs_remote.json"
    private const val META_FILE = "configs_remote.meta"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var bundledConfigs: Map<String, FunctionNameExtractor.HardcodedPlayerConfig> = emptyMap()

    @Volatile
    private var mergedConfigs: Map<String, FunctionNameExtractor.HardcodedPlayerConfig> = emptyMap()

    // Advanced every time a remote refresh actually changes the table. The cipher records the epoch
    // its WebView was built under and rebuilds when this advances, so a corrected config for the
    // current player — arriving by any refresh path AFTER the WebView was built from a missing or
    // wrong entry — takes effect on the next decipher instead of being ignored until the process is
    // restarted. See CipherDeobfuscator.getOrCreateWebView().
    @Volatile
    var configEpoch: Int = 0
        private set

    @Volatile
    private var lastForcedAttemptMs = 0L

    // Separate from lastForcedAttemptMs so a stream-rejection refresh (which fires on any 403,
    // including unrelated/expired-URL ones) can never arm a cooldown that blocks the unknown-hash
    // forceRefresh self-heal, or vice versa. They still serialize on refreshMutex (single-flight).
    @Volatile
    private var lastRejectionAttemptMs = 0L

    // The two cooldown gates read DIFFERENT stamps on purpose (see above). Routed through these
    // functions — which forceRefresh / refreshAfterStreamRejection actually call — so a unit test
    // can prove neither path is gated by the other's cooldown without touching the network.
    /**
     * True iff [stampMs] lies within [windowMs] of [now]. The in-range check (not a plain
     * `now - stamp < window`) matters: these are wall-clock stamps, and a backward clock
     * adjustment (NTP correction, manual change) makes the delta negative — a plain
     * less-than would then hold the window for the entire skew duration, wedging
     * cooldowns/TTLs exactly while playback is broken. Every wall-clock window check in
     * this module MUST go through this helper.
     */
    internal fun withinWindow(now: Long, stampMs: Long, windowMs: Long) =
        (now - stampMs) in 0 until windowMs

    internal fun forcedCooldownActive(now: Long) = withinWindow(now, lastForcedAttemptMs, FORCE_REFRESH_COOLDOWN_MS)

    internal fun rejectionCooldownActive(now: Long) = withinWindow(now, lastRejectionAttemptMs, FORCE_REFRESH_COOLDOWN_MS)

    // Test-only: arm a cooldown stamp without invoking the network refresh paths.
    internal fun armForcedCooldownForTest(ms: Long) { lastForcedAttemptMs = ms }

    internal fun armRejectionCooldownForTest(ms: Long) { lastRejectionAttemptMs = ms }

    // True when the most recent fetch got ANY HTTP response (200/304/404/...). The forced-refresh
    // cooldown only arms in that case — it exists to protect the config host from repeat hits,
    // not to delay recovery after a pure network failure (e.g. rotation hit while offline).
    @Volatile
    private var lastAttemptReachedServer = false

    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Built once on first use (mirrors PlayerJsFetcher's single client) so config refreshes
    // reuse one connection pool/dispatcher instead of allocating a fresh client per fetch.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { YouTube.proxy?.let { proxy(it) } }
            .build()
    }

    /**
     * Synchronous: loads the bundled asset and, if present and valid, the last-good cached
     * remote copy. Cheap (a ~1.5 KB asset + at most one small file) and guarantees configs
     * exist before any lookup.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext

        bundledConfigs = when (val result = parseSource("bundled asset") { loadBundledJson(context) }) {
            null -> emptyMap()
            else -> result
        }
        if (bundledConfigs.isEmpty()) {
            Timber.tag(TAG).e("Bundled $ASSET_NAME missing or invalid — config table starts empty")
        } else {
            Timber.tag(TAG).d("Loaded bundled configs (${bundledConfigs.size} hashes)")
        }

        applyCachedOverlay()
    }

    /**
     * Overlays the last-good cached remote copy onto the bundled table. On ANY failure to
     * load it, the cache body AND the meta file are deleted together: an ETag surviving a
     * corrupt/missing body would make every subsequent conditional fetch 304 without a
     * re-download, locking the device on bundled-only configs until the remote content
     * happens to change. (No-op deletes on a clean first run.)
     */
    internal fun applyCachedOverlay() {
        val cached = parseSource("cached remote copy") { cacheFile()?.takeIf { it.exists() }?.readText() }
        mergedConfigs = if (cached != null) {
            Timber.tag(TAG).d("Overlaying cached remote configs (${cached.size} hashes)")
            PlayerConfigParser.merge(bundledConfigs, cached)
        } else {
            cacheFile()?.delete()
            metaFile()?.delete()
            bundledConfigs
        }
    }

    /** Non-blocking TTL-gated refresh, kicked once from CipherDeobfuscator.initialize(). */
    fun scheduleStartupRefresh() {
        scope.launch {
            try {
                refreshIfStale()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Startup config refresh failed: ${e.message}")
            }
        }
    }

    fun get(hash: String): FunctionNameExtractor.HardcodedPlayerConfig? {
        val configs = mergedConfigs
        if (configs.isEmpty()) {
            Timber.tag(TAG).w("Config table is empty (initialize not called or bundled asset broken)")
        }
        return configs[hash]
    }

    fun knownHashes(): Set<String> = mergedConfigs.keys

    /** Test-only: swaps the in-memory table without touching disk, context, or network. */
    internal fun setTableForTest(configs: Map<String, FunctionNameExtractor.HardcodedPlayerConfig>) {
        mergedConfigs = configs
    }

    /**
     * Failure-triggered refresh: called when a player-hash lookup misses. Single-flight, with
     * the cooldown decided under the lock (a check-then-set outside it would let concurrent
     * misses race). Returns true iff [missingHash] is now in the table — whether THIS call's
     * fetch did the work or a concurrent/just-finished refresh already brought it in — so
     * callers retry extraction exactly when it can succeed.
     */
    suspend fun forceRefresh(missingHash: String): Boolean = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            // A refresh that held the lock while we waited (startup TTL, another miss) may
            // have just landed this config — don't burn a fetch or arm the cooldown.
            if (mergedConfigs.containsKey(missingHash)) {
                Timber.tag(TAG).d("forceRefresh: $missingHash arrived via concurrent refresh")
                return@withLock true
            }

            val now = System.currentTimeMillis()
            if (forcedCooldownActive(now)) {
                Timber.tag(TAG).d("forceRefresh skipped (cooldown)")
                return@withLock false
            }
            lastForcedAttemptMs = now
            fetchAndApplyResetting { lastForcedAttemptMs = 0L }
            mergedConfigs.containsKey(missingHash)
        }
    }

    /**
     * Stream-rejection refresh: a deciphered URL was rejected by the CDN (e.g. a WEB_REMIX 403),
     * which can mean the cipher produced a wrong-but-non-throwing signature from a stale/wrong
     * player config — a failure the [CipherDeobfuscator] exception-retry never sees. Unlike
     * [forceRefresh], this does NOT short-circuit when the current hash is already present (the
     * entry may be present but WRONG), so it always re-fetches; it has its OWN cooldown (so it can't
     * starve the unknown-hash [forceRefresh] path) but shares the single-flight lock. Returns
     * whether the table changed — when true, [configEpoch] has advanced and the cipher rebuilds.
     */
    suspend fun refreshAfterStreamRejection(): Boolean = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (rejectionCooldownActive(now)) {
                Timber.tag(TAG).d("refreshAfterStreamRejection skipped (cooldown)")
                return@withLock false
            }
            lastRejectionAttemptMs = now
            fetchAndApplyResetting { lastRejectionAttemptMs = 0L }
        }
    }

    /**
     * Shared fetch tail for the cooldown-gated refresh paths: fetch + apply, and on a pure network
     * failure (the server was never reached) reset the caller's cooldown stamp so a rotation hit
     * while briefly offline retries on the next trigger instead of waiting out the cooldown.
     * Returns whether the table changed. Must be called with [refreshMutex] held.
     */
    private fun fetchAndApplyResetting(resetCooldown: () -> Unit): Boolean {
        val changed = fetchAndApply()
        if (!lastAttemptReachedServer) resetCooldown()
        return changed
    }

    private suspend fun refreshIfStale() {
        val lastFetchMs = readMeta()?.second ?: 0L
        // lastFetchMs is persisted, so a future stamp (wall clock stepped back after the
        // write) must count as stale, not fresh — withinWindow handles that.
        if (withinWindow(System.currentTimeMillis(), lastFetchMs, REFRESH_TTL_MS)) {
            Timber.tag(TAG).d("Remote configs fresh (fetched ${System.currentTimeMillis() - lastFetchMs} ms ago)")
            return
        }
        withContext(Dispatchers.IO) {
            refreshMutex.withLock { fetchAndApply() }
        }
    }

    /**
     * Fetches the remote JSON (with If-None-Match) and applies it when valid. Any failure —
     * HTTP error (including the 404 served until the file lands on the repo's default
     * branch), network exception, or validation failure — keeps the previous map and cache.
     * lastFetchMs is only advanced on 200/304 so transient failures retry on the next trigger.
     */
    private fun fetchAndApply(): Boolean {
        lastAttemptReachedServer = false
        try {
            val etag = readMeta()?.first
            val request = Request.Builder()
                .url(REMOTE_URL)
                .header("User-Agent", "Mozilla/5.0")
                .apply { if (!etag.isNullOrEmpty()) header("If-None-Match", etag) }
                .build()

            httpClient.newCall(request).execute().use { response ->
                lastAttemptReachedServer = true
                if (response.code == 304) {
                    Timber.tag(TAG).d("Remote configs unchanged (304)")
                    writeMeta(etag.orEmpty(), System.currentTimeMillis())
                    return false
                }
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Remote config fetch HTTP ${response.code} — keeping previous configs")
                    return false
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    Timber.tag(TAG).w("Remote config fetch returned empty body — keeping previous configs")
                    return false
                }

                val remote = when (val result = PlayerConfigParser.parse(body)) {
                    is PlayerConfigParser.ParseResult.Failure -> {
                        Timber.tag(TAG).w("Remote configs rejected: ${result.reason} — keeping previous configs")
                        return false
                    }
                    is PlayerConfigParser.ParseResult.Success -> {
                        if (result.skippedEntries.isNotEmpty()) {
                            Timber.tag(TAG).w("Remote configs: skipped invalid entries ${result.skippedEntries}")
                        }
                        result.configs
                    }
                }

                return applyRemote(remote, body, response.header("ETag").orEmpty())
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Remote config fetch failed: ${e.message} — keeping previous configs")
            return false
        }
    }

    /**
     * Applies a validated remote table to memory FIRST, then best-effort persists the raw
     * body + meta. A disk failure (full disk, IO error) must never discard an in-hand
     * validated fix — losing the cache only costs a refetch on the next start, while losing
     * the memory update costs working playback now. Returns whether the table changed.
     */
    internal fun applyRemote(
        remote: Map<String, FunctionNameExtractor.HardcodedPlayerConfig>,
        body: String,
        etag: String,
    ): Boolean {
        val merged = PlayerConfigParser.merge(bundledConfigs, remote)
        val changed = merged != mergedConfigs
        mergedConfigs = merged
        if (changed) configEpoch++
        Timber.tag(TAG).d("Remote configs applied (${remote.size} hashes, merged=${merged.size}, changed=$changed, epoch=$configEpoch)")

        try {
            cacheFile()?.let { writeAtomic(it, body) }
            writeMeta(etag, System.currentTimeMillis())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not persist remote configs (kept in memory): ${e.message}")
        }
        return changed
    }

    private fun parseSource(
        label: String,
        read: () -> String?,
    ): Map<String, FunctionNameExtractor.HardcodedPlayerConfig>? {
        val text = try {
            read() ?: return null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read $label: ${e.message}")
            return null
        }
        return when (val result = PlayerConfigParser.parse(text)) {
            is PlayerConfigParser.ParseResult.Failure -> {
                Timber.tag(TAG).w("Rejected $label: ${result.reason}")
                null
            }
            is PlayerConfigParser.ParseResult.Success -> {
                if (result.skippedEntries.isNotEmpty()) {
                    Timber.tag(TAG).w("$label: skipped invalid entries ${result.skippedEntries}")
                }
                result.configs
            }
        }
    }

    private fun loadBundledJson(context: Context): String? =
        context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }

    // Test seam: unit tests point this at a temp dir; production resolves from appContext.
    internal var cacheDirForTest: File? = null

    private fun cacheDir(): File? {
        cacheDirForTest?.let { return it.apply { if (!exists()) mkdirs() } }
        val context = appContext ?: return null
        return File(context.filesDir, "cipher_cache").apply { if (!exists()) mkdirs() }
    }

    private fun cacheFile(): File? = cacheDir()?.let { File(it, CACHE_FILE) }

    private fun metaFile(): File? = cacheDir()?.let { File(it, META_FILE) }

    /** Meta file: line 1 = ETag (may be empty), line 2 = lastFetchMs. */
    private fun readMeta(): Pair<String, Long>? {
        return try {
            val file = metaFile()?.takeIf { it.exists() } ?: return null
            val lines = file.readText().split("\n")
            if (lines.size < 2) return null
            val lastFetchMs = lines[1].toLongOrNull() ?: return null
            lines[0] to lastFetchMs
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMeta(etag: String, lastFetchMs: Long) {
        try {
            metaFile()?.let { writeAtomic(it, "$etag\n$lastFetchMs") }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not write config meta: ${e.message}")
        }
    }

    /**
     * Temp-file + rename so a process death mid-write can't leave a truncated file (a
     * corrupt cache body beside a valid ETag is exactly the 304-lock state
     * [applyCachedOverlay] defends against).
     */
    internal fun writeAtomic(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            // renameTo won't overwrite an existing target on some filesystems — retry after
            // deleting it (two cheap metadata ops, still atomic) before the last-resort direct
            // write, which is both non-atomic and a second full write of the content.
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(content)
                tmp.delete()
            }
        }
    }
}
