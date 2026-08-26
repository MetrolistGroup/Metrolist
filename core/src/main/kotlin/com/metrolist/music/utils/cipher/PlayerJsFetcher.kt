package com.metrolist.music.utils.cipher

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

/**
 * Fetches and caches YouTube's player.js for cipher operations.
 *
 * The player.js contains the signature deobfuscation and n-transform functions
 * that are required to access stream URLs on web clients.
 */
object PlayerJsFetcher {
    private const val TAG = "Metrolist_CipherFetcher"
    private const val IFRAME_API_URL = "https://www.youtube.com/iframe_api"
    private const val PLAYER_JS_URL_TEMPLATE = "https://www.youtube.com/s/player/%s/player_ias.vflset/en_GB/base.js"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    // Regex to extract player hash from iframe_api response
    private val PLAYER_HASH_REGEX = Regex("""\\?/s\\?/player\\?/([a-zA-Z0-9_-]+)\\?/""")

    // Serializes cache mutations: getPlayerJs has unsynchronized concurrent callers, and an
    // unlocked writeToCache purge racing another writer's writeAtomic tmp window would delete
    // the tmp mid-write and silently degrade to a truncating non-atomic write.
    private val cacheWriteLock = Any()

    private fun getCacheDir(): File = File(CipherDeobfuscator.appContext.filesDir, "cipher_cache")

    private fun getCacheFile(hash: String): File = File(getCacheDir(), "player_$hash.js")

    private fun getHashFile(): File = File(getCacheDir(), "current_hash.txt")

    /**
     * Get player.js content and hash.
     *
     * Uses cached version if available and not expired, otherwise fetches fresh.
     * Returns Pair(playerJs, hash) or null if failed.
     */
    suspend fun getPlayerJs(forceRefresh: Boolean = false): Pair<String, String>? = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("=== GET PLAYER.JS ===")
        Timber.tag(TAG).d("forceRefresh: $forceRefresh")

        try {
            val cacheDir = getCacheDir()
            if (!cacheDir.exists()) {
                Timber.tag(TAG).d("Creating cache directory: ${cacheDir.absolutePath}")
                cacheDir.mkdirs()
            }

            // Check cache first (unless forced refresh)
            if (!forceRefresh) {
                val cached = readFromCache()
                if (cached != null) {
                    Timber.tag(TAG).d("=== CACHE HIT ===")
                    Timber.tag(TAG).d("Using cached player JS (hash=${cached.second}, length=${cached.first.length})")
                    return@withContext cached
                }
                Timber.tag(TAG).d("Cache miss, will fetch fresh")
            }

            // Fetch player hash from iframe_api
            Timber.tag(TAG).d("Fetching player hash from iframe_api...")
            val hash = fetchPlayerHash()
            if (hash == null) {
                Timber.tag(TAG).e("Failed to extract player hash from iframe_api")
                return@withContext null
            }
            Timber.tag(TAG).d("Extracted player hash: $hash")

            // Download player JS
            Timber.tag(TAG).d("Downloading player JS for hash: $hash...")
            val playerJs = downloadPlayerJs(hash)
            if (playerJs == null) {
                Timber.tag(TAG).e("Failed to download player JS for hash=$hash")
                return@withContext null
            }

            Timber.tag(TAG).d("=== PLAYER.JS DOWNLOADED ===")
            Timber.tag(TAG).d("hash: $hash")
            Timber.tag(TAG).d("length: ${playerJs.length} chars")
            Timber.tag(TAG).d("preview: ${playerJs.take(100)}...")

            // Cache the result
            writeToCache(hash, playerJs)

            Pair(playerJs, hash)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "getPlayerJs exception: ${e.message}")
            null
        }
    }

    /**
     * Invalidate the player.js cache.
     * Call this when cipher operations fail to force a fresh fetch.
     */
    fun invalidateCache() {
        Timber.tag(TAG).d("Invalidating cache...")
        synchronized(cacheWriteLock) { try {
            val cacheDir = getCacheDir()
            if (cacheDir.exists()) {
                // Only the player.js cache (player_*.js + current_hash.txt) belongs to this fetcher.
                // The dir is shared with PlayerConfigStore (configs_remote.json/.meta) — do NOT wipe
                // those, or every decipher retry destroys the config ETag and forces a full
                // non-conditional re-download of the config file.
                val files = cacheDir.listFiles()?.filter {
                    it.name.startsWith("player_") || it.name == "current_hash.txt"
                }
                Timber.tag(TAG).d("Deleting ${files?.size ?: 0} player-JS cache files")
                files?.forEach {
                    Timber.tag(TAG).v("Deleting: ${it.name}")
                    it.delete()
                }
            }
            Timber.tag(TAG).d("Cache invalidated successfully")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to invalidate cache: ${e.message}")
        } }
    }

    private fun readFromCache(): Pair<String, String>? {
        Timber.tag(TAG).d("Checking cache...")
        try {
            val hashFile = getHashFile()
            if (!hashFile.exists()) {
                Timber.tag(TAG).d("Hash file does not exist")
                return null
            }

            val hashData = hashFile.readText().split("\n")
            if (hashData.size < 2) {
                Timber.tag(TAG).d("Hash file malformed (expected 2 lines, got ${hashData.size})")
                return null
            }

            val hash = hashData[0]
            val timestamp = hashData[1].toLongOrNull()
            if (timestamp == null) {
                Timber.tag(TAG).d("Could not parse timestamp from hash file")
                return null
            }

            val ageMs = System.currentTimeMillis() - timestamp
            val ageHours = ageMs / (1000 * 60 * 60)
            Timber.tag(TAG).d("Cache age: ${ageHours}h (TTL: ${CACHE_TTL_MS / (1000 * 60 * 60)}h)")

            // Check TTL (in-range: a future timestamp from a backward clock step counts as
            // expired, not fresh — see PlayerConfigStore.withinWindow).
            if (!PlayerConfigStore.withinWindow(System.currentTimeMillis(), timestamp, CACHE_TTL_MS)) {
                Timber.tag(TAG).d("Cache expired (hash=$hash, age=${ageHours}h)")
                return null
            }

            val cacheFile = getCacheFile(hash)
            if (!cacheFile.exists()) {
                Timber.tag(TAG).d("Cache file does not exist for hash: $hash")
                return null
            }

            val playerJs = cacheFile.readText()
            if (playerJs.isEmpty()) {
                Timber.tag(TAG).d("Cache file is empty")
                return null
            }

            Timber.tag(TAG).d("Cache valid: hash=$hash, length=${playerJs.length}, age=${ageHours}h")
            return Pair(playerJs, hash)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error reading cache: ${e.message}")
            return null
        }
    }

    private fun writeToCache(hash: String, playerJs: String) {
        Timber.tag(TAG).d("Writing to cache: hash=$hash, length=${playerJs.length}")
        synchronized(cacheWriteLock) {
            try {
                val cacheDir = getCacheDir()

                // Clean old cache files
                val oldFiles = cacheDir.listFiles()?.filter { it.name.startsWith("player_") }
                Timber.tag(TAG).d("Cleaning ${oldFiles?.size ?: 0} old cache files")
                oldFiles?.forEach { it.delete() }

                // Atomic (temp + rename): a plain writeText truncates first, so process death
                // during a same-hash force-refresh rewrite would leave a truncated player.js
                // that readFromCache happily serves until the TTL expires.
                PlayerConfigStore.writeAtomic(getCacheFile(hash), playerJs)
                PlayerConfigStore.writeAtomic(getHashFile(), "$hash\n${System.currentTimeMillis()}")

                Timber.tag(TAG).d("Cache written successfully")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error writing cache: ${e.message}")
            }
        }
    }

    private fun fetchPlayerHash(): String? {
        Timber.tag(TAG).d("Fetching iframe_api from: $IFRAME_API_URL")

        val request = Request.Builder()
            .url(IFRAME_API_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        // .use{} so the response is closed on the error path too (an unread body would
        // otherwise strand its connection).
        val body = httpClient.newCall(request).execute().use { response ->
            Timber.tag(TAG).d("iframe_api response: HTTP ${response.code}")
            if (!response.isSuccessful) {
                Timber.tag(TAG).e("iframe_api HTTP ${response.code}")
                return null
            }
            response.body?.string()
        }
        if (body == null) {
            Timber.tag(TAG).e("iframe_api response body is null")
            return null
        }

        Timber.tag(TAG).d("iframe_api body length: ${body.length}")
        Timber.tag(TAG).v("iframe_api body preview: ${body.take(200)}...")

        val match = PLAYER_HASH_REGEX.find(body)
        if (match == null) {
            Timber.tag(TAG).e("Could not find player hash in iframe_api response")
            Timber.tag(TAG).d("Regex pattern: ${PLAYER_HASH_REGEX.pattern}")
            return null
        }

        val hash = match.groupValues[1]
        Timber.tag(TAG).d("Found player hash: $hash")
        return hash
    }

    private fun downloadPlayerJs(hash: String): String? {
        val url = PLAYER_JS_URL_TEMPLATE.format(hash)
        Timber.tag(TAG).d("Downloading player.js from: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            Timber.tag(TAG).d("player.js response: HTTP ${response.code}")
            if (!response.isSuccessful) {
                Timber.tag(TAG).e("player.js download HTTP ${response.code}")
                return null
            }
            response.body?.string()
        }
        if (body == null) {
            Timber.tag(TAG).e("player.js response body is null")
            return null
        }

        Timber.tag(TAG).d("player.js downloaded: ${body.length} chars")
        return body
    }

    /**
     * Debug method: Get cache information
     */
    fun getCacheInfo(): Map<String, Any?> {
        return try {
            val hashFile = getHashFile()
            if (!hashFile.exists()) {
                return mapOf("exists" to false)
            }

            val hashData = hashFile.readText().split("\n")
            val hash = hashData.getOrNull(0)
            val timestamp = hashData.getOrNull(1)?.toLongOrNull()
            val cacheFile = hash?.let { getCacheFile(it) }

            mapOf(
                "exists" to true,
                "hash" to hash,
                "timestamp" to timestamp,
                "ageMs" to (timestamp?.let { System.currentTimeMillis() - it }),
                "fileExists" to (cacheFile?.exists() == true),
                "fileSize" to (cacheFile?.length()),
            )
        } catch (e: Exception) {
            mapOf("error" to e.message)
        }
    }
}
