package com.metrolist.music.utils.cipher

import android.content.Context
import android.util.Base64
import com.metrolist.innertube.YouTube
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Cosmetic "when did we add cipher support for this player" dates, shown in the song-details
 * sheet next to the player hash.
 *
 * Pulled **purely from a remote file** on the cipher repo — `player_dates.json` is NOT bundled
 * in the APK, so adding a date is just a push to that file and already-installed apps pick it
 * up with no APK update. A small on-disk cache makes it instant/offline on later launches.
 *
 * Deliberately decoupled from [PlayerConfigStore] and the decipher path: it is a separate file
 * old apps never fetch (so it cannot affect them), it is parsed tolerantly, and every failure
 * (no network, bad JSON, no cache yet) just yields an unknown date — playback is never touched.
 *
 * File shape — a flat map, no schemaVersion, no validation:
 *   { "959dabb2": "2026-06-12", "445213fb": "2026-06-10", ... }
 */
object PlayerDatesStore {
    private const val TAG = "Metrolist_CipherDates"

    private val REMOTE_URL by lazy {
        val encoded = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL01ldHJvbGlzdEdyb3VwL01ldHJvbGlzdC9tYWluL2FwcC9zcmMvbWFpbi9hc3NldHMvcGxheWVyX2RhdGVzLmpzb24="
        String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
    }

    // Own dir, NOT the shared cipher_cache (PlayerJsFetcher purges/wipes that one).
    private const val CACHE_DIR = "cipher_dates"
    private const val CACHE_FILE = "player_dates.json"

    @Volatile
    private var dates: Map<String, String> = emptyMap()

    /** Tolerant parse of a flat `hash -> date` object. Non-string values are skipped; never throws. */
    internal fun parse(text: String): Map<String, String> =
        runCatching {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
            buildMap {
                for ((hash, value) in root) {
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { put(hash, it) }
                }
            }
        }.getOrDefault(emptyMap())

    /** Load the last-fetched cache (instant/offline), then refresh from the remote file in the background. */
    fun initialize(context: Context) {
        val cache = File(File(context.filesDir, CACHE_DIR).apply { mkdirs() }, CACHE_FILE)

        dates = runCatching {
            if (cache.exists()) parse(cache.readText()) else emptyMap()
        }.getOrDefault(emptyMap())

        Thread {
            runCatching {
                val body = fetchRemote()
                val remote = parse(body)
                if (remote.isNotEmpty()) {
                    dates = remote // the remote file is the single source of truth
                    runCatching { cache.writeText(body) } // persist for the next launch / offline
                }
            }.onFailure { Timber.tag(TAG).d("dates refresh skipped: ${it.message}") }
        }.apply { isDaemon = true; name = "PlayerDatesRefresh" }.start()
    }

    /** Onboarding date for [hash] (`YYYY-MM-DD`), or null if unknown. */
    fun get(hash: String?): String? = hash?.let { dates[it] }

    private fun fetchRemote(): String {
        val url = URL(REMOTE_URL)
        val conn = (YouTube.proxy?.let { url.openConnection(it) } ?: url.openConnection()) as HttpURLConnection
        return try {
            conn.run {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect() // release the socket immediately, including on the error path
        }
    }
}
