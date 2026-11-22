package com.metrolist.shared.api.artwork

import com.metrolist.shared.network.readTextLimited
import com.metrolist.shared.settings.AnimatedCoverResolution
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.logging.YtLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

private fun artworkAlbumSuffix(
    album: String,
    endIndex: Int,
): Pair<Int, String>? {
    var end = endIndex
    while (end > 0 && album[end - 1].isWhitespace()) end--
    if (end == 0) return null
    val closing = album[end - 1]
    var start: Int
    val label: String
    if (closing == ')' || closing == ']') {
        val opening = if (closing == ')') '(' else '['
        var depth = 1
        start = end - 2
        while (start >= 0) {
            when (album[start]) {
                closing -> depth++
                opening -> depth--
            }
            if (depth == 0) break
            start--
        }
        if (start < 0) return null
        var contentStart = start + 1
        while (contentStart < end - 1 && album[contentStart].isWhitespace()) contentStart++
        label =
            if (contentStart < end - 1 && album[contentStart] == '+') {
                "+"
            } else {
                album.substring(contentStart, end - 1).trimEnd().lowercase()
            }
    } else {
        var wordStart = end
        while (wordStart > 0 && album[wordStart - 1].isLetter()) wordStart--
        if (wordStart == end) return null
        label = album.substring(wordStart, end).lowercase()
        start = wordStart
        while (start > 0 && album[start - 1].isWhitespace()) start--
        if (start == 0 || album[start - 1] !in "-–—") return null
        start--
    }
    when (label) {
        "+", "explicit", "clean", "cleaned", "single", "ep" -> Unit
        else -> return null
    }
    while (start > 0 && album[start - 1].isWhitespace()) start--
    return start to label
}

private fun stripReleaseSuffixes(name: String): String {
    var end = name.length
    while (true) {
        val (start, label) = artworkAlbumSuffix(name, end) ?: break
        if (label != "single" && label != "ep") break
        end = start
    }
    return name.substring(0, end).trimEnd()
}

private fun stripArtworkAlbumNotes(album: String): String {
    var end = album.length
    val retainedSuffixes = mutableListOf<IntRange>()
    while (true) {
        val (start, label) = artworkAlbumSuffix(album, end) ?: break
        if (label != "+") retainedSuffixes.add(start until end)
        end = start
    }
    return buildString(album.length) {
        append(album, 0, end)
        for (suffix in retainedSuffixes.asReversed()) append(album, suffix.first, suffix.last + 1)
    }.trim()
}

internal fun resolveArtworkExplicitness(
    album: String?,
    trackExplicitness: Boolean?,
): Boolean? {
    if (album.isNullOrBlank()) return trackExplicitness
    val name = stripReleaseSuffixes(stripArtworkAlbumNotes(album))
    if (name.isBlank()) return trackExplicitness
    return when (artworkAlbumSuffix(name, name.length)?.second) {
        "explicit" -> true
        "clean", "cleaned" -> false
        else -> null
    }
}

internal fun stripArtworkEditionSuffix(album: String): String {
    val name = album.trim()
    val withoutRelease = stripReleaseSuffixes(name)
    val suffix = artworkAlbumSuffix(withoutRelease, withoutRelease.length)
    val base =
        when (suffix?.second) {
            "explicit", "clean", "cleaned" -> withoutRelease.substring(0, suffix.first)
            else -> withoutRelease
        }
    return (base + name.substring(withoutRelease.length)).trim()
}

internal fun matchesAppleMusicExplicitness(
    trackExplicitness: String?,
    collectionExplicitness: String?,
    explicit: Boolean,
    albumKnown: Boolean,
): Boolean {
    val value =
        (if (albumKnown) collectionExplicitness ?: trackExplicitness else trackExplicitness ?: collectionExplicitness)
            ?.lowercase()
            ?: return false
    return if (explicit) value == "explicit" else value == "notexplicit" || value == "cleaned"
}

class AnimatedArtworkService internal constructor(
    private val httpClient: HttpClient,
    cacheLoadDispatcher: CoroutineDispatcher,
) {
    constructor(httpClient: HttpClient) : this(httpClient, Dispatchers.Default)

    private val cache = mutableMapOf<String, CacheEntry>()
    private val cacheMutex = Mutex()
    private val artistCache = mutableMapOf<String, ArtistCacheEntry>()
    private var saveJob: Job? = null
    private var artistSaveJob: Job? = null
    private var cacheGeneration = 0L
    private val scope = CoroutineScope(SupervisorJob())
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private var tokenCache: TokenCache? = null
    private val tokenMutex = Mutex()

    private val cacheLoadJob =
        scope.launch(cacheLoadDispatcher) {
            loadCacheFromDisk()
            loadArtistCacheFromDisk()
        }

    internal fun dispose() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "AnimatedArtwork"
        private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"

        private const val CACHE_KEY_VERSION = "v11"
        private const val CACHE_MAX_AGE_SECONDS = 30L * 24 * 60 * 60
        private const val CACHE_MISS_MAX_AGE_SECONDS = 60L
        private const val MAX_COVER_CACHE_ENTRIES = 1_000
        private const val MAX_COLLECTION_CANDIDATES = 8
        private const val ARTIST_NEGATIVE_CACHE_MAX_AGE_SECONDS = 7L * 24 * 60 * 60
        private const val MAX_ARTIST_CACHE_ENTRIES = 500
        private const val MAX_JSON_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_HTML_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_SCRIPT_RESPONSE_BYTES = 16 * 1024 * 1024
        private const val MAX_PLAYLIST_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_METADATA_CHARS = 4_096

        private val titleCleanupPatterns =
            listOf(
                Regex(
                    """\s*\([^)]*?(official|video|music|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit)[^)]*\)""",
                    RegexOption.IGNORE_CASE,
                ),
                Regex(
                    """\s*\[[^\]]*?(official|video|music|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit)[^\]]*]""",
                    RegexOption.IGNORE_CASE,
                ),
                Regex("""\s*【.*?】"""),
                Regex("""\s*\|\s*.*$"""),
                Regex(
                    """\s*-\s*(official|video|music|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit).*$""",
                    RegexOption.IGNORE_CASE,
                ),
                Regex("""\s*\([^)]*\d{4}[^)]*\)""", RegexOption.IGNORE_CASE),
            )

        private val smartQuotePattern = Regex("""[\u2018\u2019\u201C\u201D]""")
    }

    private class TokenCache(
        val token: String,
        val expiresAt: Long,
    )

    private fun loadCacheFromDisk() {
        try {
            val raw = Settings.shared.animatedCoverCacheJson
            if (raw.isBlank() || raw == "{}") return
            val obj = json.parseToJsonElement(raw).jsonObject
            val now = currentTimeSeconds()
            var droppedOldVersion = 0
            var droppedExpired = 0
            var droppedUntrusted = 0
            for ((key, value) in obj) {
                if (!key.startsWith("$CACHE_KEY_VERSION|")) {
                    droppedOldVersion++
                    continue
                }
                val entry = parseCacheEntry(value)
                if (entry == null ||
                    now - entry.cachedAt > if (entry.url == null) CACHE_MISS_MAX_AGE_SECONDS else CACHE_MAX_AGE_SECONDS
                ) {
                    droppedExpired++
                    continue
                }
                val url = entry.url
                if (url != null && !isTrustedAnimatedArtworkUrl(url)) {
                    droppedUntrusted++
                    continue
                }
                cache[key] =
                    entry.copy(
                        tallUrl = entry.tallUrl?.takeIf(::isTrustedAnimatedArtworkUrl),
                        previewImageUrl = entry.previewImageUrl?.takeIf(::isTrustedAnimatedArtworkUrl),
                        tallPreviewImageUrl = entry.tallPreviewImageUrl?.takeIf(::isTrustedAnimatedArtworkUrl),
                    )
            }
            while (cache.size > MAX_COVER_CACHE_ENTRIES) {
                cache.minByOrNull { it.value.cachedAt }?.key?.let(cache::remove)
            }
            if (droppedOldVersion > 0) {
                YtLogger.d(TAG, "Discarded $droppedOldVersion cache entries from an older cache-key format")
            }
            if (droppedExpired > 0) {
                YtLogger.d(TAG, "Discarded $droppedExpired cache entries past the max cache age")
            }
            if (droppedUntrusted > 0) {
                YtLogger.d(TAG, "Discarded $droppedUntrusted cache entries with untrusted artwork URLs")
            }
            if (droppedOldVersion > 0 || droppedExpired > 0 || droppedUntrusted > 0) {
                scope.launch { saveCacheToDisk() }
            }
            YtLogger.d(TAG, "Loaded ${cache.size} entries from disk cache")
        } catch (e: Exception) {
            YtLogger.e(TAG, "Failed to load animated cover cache (${e::class.simpleName})")
        }
    }

    private fun loadArtistCacheFromDisk() {
        runCatching {
            val raw = Settings.shared.appleMusicArtistCacheJson
            if (raw.isBlank() || raw == "{}") return
            val now = currentTimeSeconds()
            json
                .decodeFromString<Map<String, ArtistCacheEntry>>(raw)
                .entries
                .sortedByDescending { it.value.cachedAt }
                .take(MAX_ARTIST_CACHE_ENTRIES)
                .forEach { (key, entry) ->
                    val maxAge = if (entry.shortLived) ARTIST_NEGATIVE_CACHE_MAX_AGE_SECONDS else CACHE_MAX_AGE_SECONDS
                    if (
                        now - entry.cachedAt <= maxAge &&
                        entry.imageUrl?.let(::isTrustedArtworkUrl) != false &&
                        entry.videoUrl?.let(::isTrustedArtworkUrl) != false
                    ) {
                        artistCache[key] = entry
                    }
                }
        }.onFailure {
            YtLogger.w(TAG, "Failed to load artist artwork cache (${it::class.simpleName})")
        }
    }

    private fun parseCacheEntry(value: JsonElement): CacheEntry? {
        val obj = value as? JsonObject ?: return null
        val url =
            when (val u = obj["url"]) {
                is JsonNull -> null
                is JsonPrimitive -> u.content.ifBlank { null }
                else -> null
            }
        val cachedAt = (obj["cachedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        if (cachedAt <= 0L) return null
        val tallUrl = (obj["tallUrl"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.ifBlank { null }
        val previewImageUrl = (obj["previewImageUrl"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.ifBlank { null }
        val tallPreviewImageUrl = (obj["tallPreviewImageUrl"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.ifBlank { null }
        val squareIsTallFallback = (obj["squareIsTallFallback"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        return CacheEntry(
            url = url,
            cachedAt = cachedAt,
            tallUrl = tallUrl,
            previewImageUrl = previewImageUrl,
            tallPreviewImageUrl = tallPreviewImageUrl,
            squareIsTallFallback = squareIsTallFallback,
        )
    }

    private suspend fun saveCacheToDisk() {
        try {
            val (snapshot, generation) = cacheMutex.withLock { cache.toMap() to cacheGeneration }
            val map =
                snapshot.mapValues { (_, entry) ->
                    JsonObject(
                        buildMap {
                            put("url", entry.url?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("tallUrl", entry.tallUrl?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("cachedAt", JsonPrimitive(entry.cachedAt))
                            entry.previewImageUrl?.let { put("previewImageUrl", JsonPrimitive(it)) }
                            entry.tallPreviewImageUrl?.let { put("tallPreviewImageUrl", JsonPrimitive(it)) }
                            if (entry.squareIsTallFallback) put("squareIsTallFallback", JsonPrimitive(true))
                        },
                    )
                }
            val encoded = json.encodeToString(JsonObject(map))
            cacheMutex.withLock {
                if (cacheGeneration == generation) Settings.shared.animatedCoverCacheJson = encoded
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.e(TAG, "Failed to save animated cover cache (${e::class.simpleName})")
        }
    }

    suspend fun fetchArtistArtwork(artist: String): ArtistArtworkFetchResult {
        val includeImage = Settings.shared.enableAppleMusicArtistImages
        val includeVideo = Settings.shared.enableAppleMusicArtistVideos && Settings.shared.isAnimatedArtworkAllowedOnNetwork()
        if (!includeImage && !includeVideo) return ArtistArtworkFetchResult.Unavailable

        cacheLoadJob.join()

        val country =
            Settings.shared.lastResolvedContentCountry
                .lowercase()
                .takeIf(String::isNotBlank) ?: return ArtistArtworkFetchResult.Unavailable
        val language =
            Settings.shared.lastResolvedContentLanguage
                .lowercase()
                .takeIf(String::isNotBlank)
                .orEmpty()
        val videoResolution = Settings.shared.appleMusicArtistVideoResolution
        val cacheKey = "$country|$language|${normalizeArtistProfileName(artist)}|$includeImage|$includeVideo|${videoResolution.name}"
        val generation =
            cacheMutex.withLock {
                artistCache[cacheKey]?.let { entry ->
                    val maxAge = if (entry.shortLived) ARTIST_NEGATIVE_CACHE_MAX_AGE_SECONDS else CACHE_MAX_AGE_SECONDS
                    if (currentTimeSeconds() - entry.cachedAt <= maxAge) return entry.toFetchResult()
                    artistCache.remove(cacheKey)
                }
                cacheGeneration
            }

        val result =
            try {
                val artistId =
                    withContext(Dispatchers.Default) {
                        resolveArtistId(artist, preserveFullName = true, country = country, propagateFailures = true)
                    }
                if (artistId == null) {
                    null
                } else {
                    val token = withContext(Dispatchers.Default) { getDevToken(country) } ?: return ArtistArtworkFetchResult.Unavailable
                    withContext(Dispatchers.Default) {
                        fetchArtistAssets(artistId, token, country, language, includeImage, includeVideo, videoResolution)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                YtLogger.w(TAG, "Artist artwork fetch timed out")
                return ArtistArtworkFetchResult.Unavailable
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                YtLogger.w(TAG, "Artist artwork fetch failed (${e::class.simpleName})")
                return ArtistArtworkFetchResult.Unavailable
            }
        val shouldSave =
            cacheMutex.withLock {
                if (cacheGeneration != generation) return@withLock false
                artistCache[cacheKey] =
                    ArtistCacheEntry(
                        imageUrl = result?.imageUrl,
                        videoUrl = result?.videoUrl,
                        cachedAt = currentTimeSeconds(),
                        isMissing = result == null,
                        shortLived = result == null || (includeVideo && result.videoUrl == null),
                    )
                while (artistCache.size > MAX_ARTIST_CACHE_ENTRIES) {
                    artistCache.minByOrNull { it.value.cachedAt }?.key?.let(artistCache::remove)
                }
                true
            }
        if (shouldSave) scheduleArtistCacheSave()
        return result?.let(ArtistArtworkFetchResult::Found) ?: ArtistArtworkFetchResult.Missing
    }

    private fun scheduleArtistCacheSave() {
        artistSaveJob?.cancel()
        artistSaveJob =
            scope.launch {
                try {
                    delay(500.milliseconds)
                    val (snapshot, generation) = cacheMutex.withLock { artistCache.toMap() to cacheGeneration }
                    val encoded = json.encodeToString(snapshot)
                    cacheMutex.withLock {
                        if (cacheGeneration == generation) Settings.shared.appleMusicArtistCacheJson = encoded
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    YtLogger.w(TAG, "Failed to save artist artwork cache (${e::class.simpleName})")
                }
            }
    }

    suspend fun fetchAnimatedCover(
        title: String,
        artist: String,
        album: String? = null,
        explicit: Boolean? = null,
    ): AnimatedCoverResult? {
        if (title.length > MAX_METADATA_CHARS || artist.length > MAX_METADATA_CHARS || (album?.length ?: 0) > MAX_METADATA_CHARS) {
            return null
        }
        if (!Settings.shared.enableAnimatedCovers || !Settings.shared.isAnimatedArtworkAllowedOnNetwork()) return null

        cacheLoadJob.join()

        val artworkExplicitness = resolveArtworkExplicitness(album, explicit)
        val cacheKey = buildCacheKey(title, artist, album, artworkExplicitness)
        val now = currentTimeSeconds()
        val generation =
            cacheMutex.withLock {
                val entry = cache[cacheKey]
                if (entry != null) {
                    if (now - entry.cachedAt > if (entry.url == null) CACHE_MISS_MAX_AGE_SECONDS else CACHE_MAX_AGE_SECONDS) {
                        cache.remove(cacheKey)
                    } else {
                        val url = entry.url
                        if (url != null && isTrustedArtworkUrl(url)) {
                            YtLogger.d(TAG, "Animated artwork cache hit")
                            return AnimatedCoverResult(
                                url = url,
                                tallUrl = entry.tallUrl,
                                previewImageUrl = entry.previewImageUrl,
                                tallPreviewImageUrl = entry.tallPreviewImageUrl,
                                squareIsTallFallback = entry.squareIsTallFallback,
                            )
                        }
                        if (url != null) {
                            YtLogger.w(TAG, "Refusing to serve untrusted cached animated artwork URL")
                        }
                        return null
                    }
                }
                cacheGeneration
            }

        suspend fun cacheResult(
            value: String?,
            tallValue: String? = null,
            previewImageUrl: String? = null,
            tallPreviewImageUrl: String? = null,
            squareIsTallFallback: Boolean = false,
        ) = cacheAndSave(cacheKey, value, tallValue, previewImageUrl, tallPreviewImageUrl, squareIsTallFallback, generation)

        try {
            YtLogger.d(TAG, "Fetching animated artwork")

            val collectionIds = resolveCollectionIds(title, artist, album, artworkExplicitness)
            if (collectionIds.isEmpty()) {
                YtLogger.d(TAG, "[FAIL] No animated artwork collection ID")
                cacheResult(null)
                return null
            }

            val country = Settings.shared.lastResolvedContentCountry
            val token = getDevToken(country)
            if (token == null) {
                YtLogger.w(TAG, "[FAIL] Cannot get Apple Music dev token")
                cacheResult(null)
                return null
            }

            for (collectionId in collectionIds) {
                val motionVideo = fetchMotionVideo(collectionId, token, country) ?: continue
                val mp4Url =
                    try {
                        resolveBestVariant(motionVideo.url, Settings.shared.animatedCoverResolution)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        YtLogger.w(TAG, "Animated artwork candidate failed (${e::class.simpleName})")
                        continue
                    }
                if (mp4Url != null && isTrustedArtworkUrl(mp4Url)) {
                    YtLogger.d(TAG, "[OK] Resolved animated artwork")
                    val tall =
                        if (motionVideo.tallUrl == motionVideo.url) Result.success(mp4Url) else resolveTallVariant(motionVideo.tallUrl)
                    val tallUrl = tall.getOrNull()
                    if (tall.isSuccess) {
                        cacheResult(
                            mp4Url,
                            tallUrl,
                            motionVideo.previewImageUrl,
                            motionVideo.tallPreviewImageUrl,
                            motionVideo.squareIsTallFallback,
                        )
                    }
                    return AnimatedCoverResult(
                        url = mp4Url,
                        tallUrl = tallUrl,
                        previewImageUrl = motionVideo.previewImageUrl,
                        tallPreviewImageUrl = motionVideo.tallPreviewImageUrl,
                        squareIsTallFallback = motionVideo.squareIsTallFallback,
                    )
                }
            }

            YtLogger.d(TAG, "[FAIL] No suitable variant found in m3u8")
            cacheResult(null)
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            YtLogger.w(TAG, "Animated artwork fetch failed (${e::class.simpleName})")
            cacheResult(null)
            return null
        }
    }

    private suspend fun cacheAndSave(
        key: String,
        value: String?,
        tallValue: String?,
        previewImageUrl: String?,
        tallPreviewImageUrl: String?,
        squareIsTallFallback: Boolean,
        generation: Long,
    ) {
        if (value != null && !isTrustedArtworkUrl(value)) {
            YtLogger.w(TAG, "Refusing to cache untrusted animated artwork URL")
            return
        }
        val saved =
            cacheMutex.withLock {
                if (cacheGeneration != generation) return@withLock false
                cache[key] =
                    CacheEntry(
                        url = value,
                        cachedAt = currentTimeSeconds(),
                        tallUrl = tallValue?.takeIf(::isTrustedArtworkUrl),
                        previewImageUrl = previewImageUrl?.takeIf(::isTrustedArtworkUrl),
                        tallPreviewImageUrl = tallPreviewImageUrl?.takeIf(::isTrustedArtworkUrl),
                        squareIsTallFallback = squareIsTallFallback,
                    )
                while (cache.size > MAX_COVER_CACHE_ENTRIES) {
                    cache.minByOrNull { it.value.cachedAt }?.key?.let(cache::remove)
                }
                true
            }
        if (!saved) return
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(2000.milliseconds)
                saveCacheToDisk()
            }
    }

    suspend fun clearCache() {
        cacheLoadJob.join()
        cacheMutex.withLock {
            cache.clear()
            artistCache.clear()
            cacheGeneration++
        }
        saveJob?.cancel()
        artistSaveJob?.cancel()
        Settings.shared.animatedCoverCacheJson = "{}"
        Settings.shared.appleMusicArtistCacheJson = "{}"
        YtLogger.d(TAG, "Animated artwork URL caches cleared")
    }

    private fun isTrustedArtworkUrl(url: String): Boolean = isTrustedAnimatedArtworkUrl(url)

    private fun buildCacheKey(
        title: String,
        artist: String,
        album: String?,
        explicit: Boolean? = null,
    ): String {
        val cleanArtist = escapeCacheComponent(artist.lowercase().trim())
        val cleanAlbum = normalizeAlbumOrNull(album)
        val rawTitleNormalized = normalizeTitleText(title)
        val key =
            when {
                !cleanAlbum.isNullOrBlank() && rawTitleNormalized == cleanTitleText(title) -> {
                    "$cleanArtist|A|${escapeCacheComponent(cleanAlbum)}"
                }

                !cleanAlbum.isNullOrBlank() -> {
                    "$cleanArtist|A|${escapeCacheComponent(cleanAlbum)}|${escapeCacheComponent(rawTitleNormalized)}"
                }

                rawTitleNormalized != cleanTitleText(title) -> {
                    "$cleanArtist|T|${escapeCacheComponent(rawTitleNormalized)}"
                }

                else -> {
                    "$cleanArtist|T|${escapeCacheComponent(cleanTitleText(title))}"
                }
            }
        val explicitKey = explicit?.let { if (it) "|E" else "|C" }.orEmpty()
        return "$CACHE_KEY_VERSION|$key$explicitKey|R${Settings.shared.animatedCoverResolution.name}" +
            "|C${Settings.shared.lastResolvedContentCountry}"
    }

    private fun escapeCacheComponent(component: String): String = component.replace("%", "%25").replace("|", "%7c")

    private suspend fun resolveCollectionIds(
        title: String,
        artist: String,
        album: String?,
        explicit: Boolean?,
    ): List<Long> {
        val cleanTitle = cleanTitleText(title)
        val cleanArtist = normalizeArtist(artist)
        val cleanAlbum = normalizeAlbumOrNull(album)
        val isVariantTitle = isVariantTitle(title, cleanTitle)

        val seen = mutableSetOf<Long>()
        val allResults = mutableListOf<Pair<AniTunesSearchResult, String>>()

        fun matchingAlbums(): List<Long> {
            if (cleanAlbum != null) {
                val albumMatches =
                    allResults
                        .filter { (r, _) ->
                            matchesArtist(r, cleanArtist) && (explicit == null || matchesExplicit(r, explicit, true))
                        }.filter { (r, _) ->
                            !isVariantTitle || matchesTitle(r, cleanTitle, title)
                        }.filter { (r, _) ->
                            val collectionName = r.collectionName
                            collectionName != null &&
                                (
                                    stripReleaseSuffixes(normalizeAlbum(collectionName)) != cleanTitle ||
                                        stripReleaseSuffixes(cleanAlbum) == cleanTitle
                                ) &&
                                albumNamesMatch(collectionName, cleanAlbum)
                        }
                if (albumMatches.isNotEmpty()) {
                    val exactAlbumMatches =
                        albumMatches.filter {
                            it.first.collectionName
                                ?.let(::normalizeAlbum)
                                ?.let(::stripArtworkEditionSuffix) == cleanAlbum
                        }
                    val candidates = exactAlbumMatches.ifEmpty { albumMatches }
                    YtLogger.d(TAG, "Resolved animated artwork via album match (${candidates.size} candidates)")
                    return candidates.mapNotNull { it.first.collectionId }.distinct().take(MAX_COLLECTION_CANDIDATES)
                }
            }

            return emptyList()
        }

        if (cleanAlbum != null) {
            val albumQuery = "$artist $cleanAlbum"
            YtLogger.d(TAG, "Searching iTunes by album")
            fetchSearchResults(albumQuery, seen).forEach { r ->
                allResults.add(r to "album")
            }
        }

        matchingAlbums().takeIf { it.isNotEmpty() }?.let { return it }

        val titleQuery = "$artist $cleanTitle"
        YtLogger.d(TAG, "Searching iTunes for animated artwork")
        fetchSearchResults(titleQuery, seen).forEach { r ->
            allResults.add(r to "title")
        }

        val hasGoodMatch =
            allResults.any { (r, _) ->
                matchesArtist(r, cleanArtist) && matchesTitle(r, cleanTitle, title) &&
                    (cleanAlbum == null || r.collectionName?.let { albumNamesMatch(it, cleanAlbum) } == true) &&
                    (explicit == null || matchesExplicit(r, explicit, cleanAlbum != null))
            }
        if (!hasGoodMatch || allResults.size < 5) {
            val artistId = resolveArtistId(cleanArtist)
            if (artistId != null) {
                YtLogger.d(TAG, "Looking up additional artist results")
                fetchArtistResults(artistId, seen).forEach { r ->
                    if (matchesArtist(r, cleanArtist)) {
                        allResults.add(r to "artist_lookup")
                    }
                }
            }
        }

        if (allResults.isEmpty()) {
            YtLogger.d(TAG, "No iTunes animated artwork results")
            return emptyList()
        }

        val artistMatches =
            allResults.filter { (r, _) ->
                matchesArtist(r, cleanArtist) && (explicit == null || matchesExplicit(r, explicit, cleanAlbum != null))
            }
        YtLogger.d(TAG, "Total results: ${allResults.size}, artist matches: ${artistMatches.size}")

        matchingAlbums().takeIf { it.isNotEmpty() }?.let { return it }

        val titleMatches =
            artistMatches.filter { (r, _) ->
                matchesTitle(r, cleanTitle, title)
            }
        val selfTitledTitle =
            if (isVariantTitle) {
                normalizeTitleText(title)
            } else {
                cleanTitle
            }
        val selfTitledMatches = titleMatches.filter { (r, _) -> isSelfTitledRelease(r, selfTitledTitle) }
        if (selfTitledMatches.isNotEmpty() && cleanAlbum == null) {
            YtLogger.d(TAG, "Resolved animated artwork via title match (${selfTitledMatches.size} candidates)")
            return selfTitledMatches.mapNotNull { it.first.collectionId }.distinct().take(MAX_COLLECTION_CANDIDATES)
        }

        YtLogger.d(TAG, "No animated artwork match (${artistMatches.size} candidate matches)")
        return emptyList()
    }

    private suspend fun fetchSearchResults(
        query: String,
        seen: MutableSet<Long>,
    ): List<AniTunesSearchResult> {
        try {
            val response =
                withTimeout(8_000.milliseconds) {
                    httpClient
                        .prepareGet("https://itunes.apple.com/search") {
                            parameter("term", query)
                            parameter("entity", "song")
                            parameter("limit", 50)
                            parameter("country", Settings.shared.lastResolvedContentCountry)
                        }.readTextLimited(MAX_JSON_RESPONSE_BYTES)
                }
            val body = response.body
            if (!response.status.isSuccess() || body.isNullOrBlank()) {
                YtLogger.w(TAG, "iTunes search skipped: status=${response.status}, emptyBody=${body.isNullOrBlank()}")
                return emptyList()
            }
            val parsed = json.decodeFromString<AniTunesSearchResponse>(body)
            return parsed.results.filter { it.kind == "song" && it.trackId !in seen }.also {
                it.forEach { r -> seen.add(r.trackId) }
            }
        } catch (_: TimeoutCancellationException) {
            YtLogger.w(TAG, "iTunes search timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            YtLogger.w(TAG, "iTunes search failed (${e::class.simpleName})")
        }
        return emptyList()
    }

    private suspend fun fetchArtistResults(
        artistId: Long,
        seen: MutableSet<Long>,
    ): List<AniTunesSearchResult> {
        try {
            val response =
                withTimeout(10_000.milliseconds) {
                    httpClient
                        .prepareGet("https://itunes.apple.com/lookup") {
                            parameter("id", artistId)
                            parameter("entity", "song")
                            parameter("limit", 200)
                            parameter("country", Settings.shared.lastResolvedContentCountry)
                        }.readTextLimited(MAX_JSON_RESPONSE_BYTES)
                }
            val body = response.body
            if (!response.status.isSuccess() || body.isNullOrBlank()) {
                YtLogger.w(TAG, "Artist lookup skipped: status=${response.status}, emptyBody=${body.isNullOrBlank()}")
                return emptyList()
            }
            val parsed = json.decodeFromString<AniTunesSearchResponse>(body)
            return parsed.results.filter { it.kind == "song" && it.trackId !in seen }.also {
                it.forEach { r -> seen.add(r.trackId) }
            }
        } catch (_: TimeoutCancellationException) {
            YtLogger.w(TAG, "Artist lookup timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            YtLogger.w(TAG, "Artist lookup failed (${e::class.simpleName})")
        }
        return emptyList()
    }

    private suspend fun resolveArtistId(
        artistName: String,
        preserveFullName: Boolean = false,
        country: String = Settings.shared.lastResolvedContentCountry,
        propagateFailures: Boolean = false,
    ): Long? {
        try {
            val response =
                withTimeout(8_000.milliseconds) {
                    httpClient
                        .prepareGet("https://itunes.apple.com/search") {
                            parameter("term", artistName)
                            parameter("entity", "musicArtist")
                            parameter("attribute", "artistTerm")
                            parameter("limit", 200)
                            parameter("country", country)
                        }.readTextLimited(MAX_JSON_RESPONSE_BYTES)
                }
            val body = response.body
            if (!response.status.isSuccess() || body.isNullOrBlank()) {
                YtLogger.w(TAG, "Artist ID lookup skipped: status=${response.status}, emptyBody=${body.isNullOrBlank()}")
                if (propagateFailures) throw ArtistArtworkFetchException("Artist ID lookup unavailable")
                return null
            }
            val parsed = json.decodeFromString<AniTunesArtistSearchResponse>(body)
            val targetName = if (preserveFullName) normalizeArtistProfileName(artistName) else artistName
            val matches =
                parsed.results
                    .filter { result ->
                        val resultName =
                            if (preserveFullName) normalizeArtistProfileName(result.artistName) else normalizeArtist(result.artistName)
                        resultName == targetName && result.artistId > 0L
                    }.distinctBy { it.artistId }
            if (matches.size > 1) {
                YtLogger.w(TAG, "Artist ID lookup is ambiguous (${matches.size} exact matches)")
                return null
            }
            return matches.firstOrNull()?.artistId
        } catch (e: TimeoutCancellationException) {
            YtLogger.w(TAG, "Artist ID lookup timed out")
            if (propagateFailures) throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            YtLogger.w(TAG, "Artist ID lookup failed (${e::class.simpleName})")
            if (propagateFailures) throw e
        }
        return null
    }

    private fun matchesArtist(
        result: AniTunesSearchResult,
        cleanArtist: String,
    ): Boolean = normalizeArtist(result.artistName) == cleanArtist

    private fun matchesExplicit(
        result: AniTunesSearchResult,
        explicit: Boolean,
        albumKnown: Boolean,
    ): Boolean =
        matchesAppleMusicExplicitness(
            trackExplicitness = result.trackExplicitness,
            collectionExplicitness = result.collectionExplicitness,
            explicit = explicit,
            albumKnown = albumKnown,
        )

    private fun matchesTitle(
        result: AniTunesSearchResult,
        cleanTitle: String,
        rawTitle: String,
    ): Boolean {
        if (normalizeTrackTitle(result.trackName) == normalizeTrackTitle(rawTitle)) return true
        return !isVariantTitle(rawTitle, cleanTitle) && cleanTitleText(result.trackName) == cleanTitle
    }

    private fun normalizeTitleText(title: String): String =
        title
            .trim()
            .replace(smartQuotePattern, "'")
            .replace(Regex("""\s+"""), " ")
            .lowercase()

    private fun normalizeTrackTitle(title: String): String = normalizeTitleText(title).replace('[', '(').replace(']', ')')

    private fun isVariantTitle(
        rawTitle: String,
        cleanTitle: String,
    ): Boolean {
        val rawNormalized = normalizeTitleText(rawTitle)
        return rawNormalized != cleanTitle
    }

    private fun albumNamesMatch(
        itunesAlbum: String,
        targetAlbum: String,
    ): Boolean {
        val a = stripArtworkEditionSuffix(normalizeAlbum(itunesAlbum))
        val b = stripArtworkEditionSuffix(normalizeAlbum(targetAlbum))
        return stripReleaseSuffixes(a) == stripReleaseSuffixes(b)
    }

    private fun isSelfTitledRelease(
        result: AniTunesSearchResult,
        cleanTitle: String,
    ): Boolean {
        val collectionName = result.collectionName ?: return false
        val base = stripReleaseSuffixes(stripArtworkEditionSuffix(normalizeAlbum(collectionName)))
        return normalizeTrackTitle(base) == normalizeTrackTitle(cleanTitle)
    }

    private fun cleanTitleText(title: String): String {
        var cleaned = normalizeTitleText(title)
        for (pattern in titleCleanupPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim().lowercase()
    }

    private fun normalizeArtistProfileName(artistName: String): String =
        artistName
            .trim()
            .lowercase()
            .replace(smartQuotePattern, "'")
            .replace(Regex("""\s+"""), " ")

    private fun normalizeArtist(artistName: String): String =
        artistName
            .trim()
            .lowercase()
            .replace(" & ", ", ")
            .replace(" and ", ", ")
            .replace(" feat. ", ", ")
            .replace(" ft. ", ", ")
            .replace(" featuring ", ", ")
            .replace(" with ", ", ")
            .replace(" x ", ", ")
            .replace(" X ", ", ")
            .split(",")
            .firstOrNull()
            ?.trim() ?: artistName.trim().lowercase()

    private fun normalizeAlbumOrNull(album: String?): String? =
        album?.let { stripArtworkEditionSuffix(normalizeAlbum(it)) }?.takeIf { stripReleaseSuffixes(it).isNotBlank() }

    private fun normalizeAlbum(name: String): String =
        normalizeTitleText(name)
            .let(::stripArtworkAlbumNotes)
            .replace('[', '(')
            .replace(']', ')')

    private suspend fun getDevToken(country: String): String? {
        val storefront = country.lowercase().takeIf(String::isNotBlank) ?: return null
        val now = currentTimeSeconds()
        tokenMutex.withLock {
            tokenCache?.let { if (it.expiresAt - now > 300) return it.token }
        }

        try {
            YtLogger.d(TAG, "Fetching Apple Music browse page for dev token...")
            val browseHtml =
                withTimeout(15_000.milliseconds) {
                    val response =
                        httpClient
                            .prepareGet("https://music.apple.com/$storefront/browse") {
                                header("User-Agent", UA)
                                header("Accept", "text/html")
                            }.readTextLimited(MAX_HTML_RESPONSE_BYTES)
                    response.body.takeIf { response.status.isSuccess() }
                } ?: return null
            YtLogger.d(TAG, "Browse page fetched: ${browseHtml.length} bytes")

            val bundleRegex = Regex("""/assets/index[^"'\s]*\.js""")
            val bundlePath = bundleRegex.find(browseHtml)?.value
            if (bundlePath == null) {
                YtLogger.w(TAG, "Cannot find JS bundle path in browse HTML")
                return null
            }
            YtLogger.d(TAG, "Found bundle: $bundlePath")

            val jsUrl = "https://music.apple.com$bundlePath"
            val js =
                withTimeout(15_000.milliseconds) {
                    val response =
                        httpClient
                            .prepareGet(jsUrl) {
                                header("User-Agent", UA)
                            }.readTextLimited(MAX_SCRIPT_RESPONSE_BYTES)
                    response.body.takeIf { response.status.isSuccess() }
                } ?: return null
            YtLogger.d(TAG, "Bundle JS fetched: ${js.length} bytes")

            val jwtRegex = Regex("""eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
            val token =
                jwtRegex
                    .findAll(js)
                    .mapNotNull { match ->
                        val payloadSegment = match.value.split('.')[1]
                        val payloadText = decodeBase64Url(payloadSegment) ?: return@mapNotNull null
                        val payload = runCatching { json.decodeFromString<TokenPayload>(payloadText) }.getOrNull() ?: return@mapNotNull null
                        val expiry = payload.exp?.takeIf { it > now + 300 } ?: return@mapNotNull null
                        match.value.takeIf { payload.iss == "AMPWebPlay" }?.let { TokenCache(it, expiry) }
                    }.maxByOrNull { it.expiresAt }
            if (token == null) {
                YtLogger.w(TAG, "Cannot find a valid Apple Music dev token in JS bundle")
                return null
            }

            tokenMutex.withLock { tokenCache = token }
            YtLogger.d(TAG, "Dev token obtained, expires in ${(token.expiresAt - now) / 3600}h")
            return token.token
        } catch (_: TimeoutCancellationException) {
            YtLogger.w(TAG, "Dev token fetch timed out")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.w(TAG, "Failed to get dev token (${e::class.simpleName})")
            return null
        }
    }

    private fun decodeBase64Url(input: String): String? =
        runCatching {
            Base64.UrlSafe
                .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(input)
                .decodeToString()
        }.getOrNull()

    private suspend fun fetchArtistAssets(
        artistId: Long,
        token: String,
        country: String,
        language: String,
        includeImage: Boolean,
        includeVideo: Boolean,
        videoResolution: AnimatedCoverResolution,
    ): ArtistArtworkResult? {
        val response =
            withTimeout(15_000.milliseconds) {
                httpClient
                    .prepareGet("https://amp-api.music.apple.com/v1/catalog/$country/artists/$artistId") {
                        header("Authorization", "Bearer $token")
                        header("Origin", "https://music.apple.com")
                        header("Accept", "application/json")
                        parameter("art[url]", "c,f")
                        if (includeVideo) parameter("extend", "editorialVideo")
                        parameter("format[resources]", "map")
                        language.takeIf(String::isNotBlank)?.let { parameter("l", "$it-$country") }
                        parameter("platform", "web")
                    }.readTextLimited(MAX_JSON_RESPONSE_BYTES)
            }
        if (response.status.value == 401) {
            tokenMutex.withLock { tokenCache = null }
        }
        if (!response.status.isSuccess()) throw ArtistArtworkFetchException("Artist assets HTTP ${response.status.value}")

        val body =
            response.body ?: throw ArtistArtworkFetchException("Artist assets body unavailable")
        val root = json.parseToJsonElement(body).jsonObject
        val attributes =
            (root["resources"] as? JsonObject)
                ?.get("artists")
                ?.let { it as? JsonObject }
                ?.get(artistId.toString())
                ?.let { it as? JsonObject }
                ?.get("attributes")
                ?.let { it as? JsonObject }
                ?: (root["data"] as? JsonArray)
                    ?.firstOrNull()
                    ?.let { it as? JsonObject }
                    ?.get("attributes")
                    ?.let { it as? JsonObject }
                ?: return null

        val artwork = attributes["artwork"] as? JsonObject
        val imageUrl = if (includeImage) artwork?.let(::resolveArtistImageUrl) else null
        val editorialVideo = if (includeVideo) attributes["editorialVideo"] as? JsonObject else null
        val videoAsset =
            listOf("motionArtistWide16x9", "motionArtistFullscreen16x9", "motionArtistSquare1x1")
                .firstNotNullOfOrNull { key ->
                    (editorialVideo?.get(key) as? JsonObject)?.takeIf {
                        (it["video"] as? JsonPrimitive)?.content?.isNotBlank() == true
                    }
                }
        val masterUrl = (videoAsset?.get("video") as? JsonPrimitive)?.content
        val videoUrl = masterUrl?.let { resolveBestVariant(it, videoResolution) }
        return if (imageUrl != null || videoUrl != null) ArtistArtworkResult(imageUrl, videoUrl) else null
    }

    private fun resolveArtistImageUrl(artwork: JsonObject): String? {
        val template = (artwork["url"] as? JsonPrimitive)?.content ?: return null
        val width = (artwork["width"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val height = (artwork["height"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val url =
            template
                .replace("{w}", width.toString())
                .replace("{h}", height.toString())
                .replace("{c}", "bb")
                .replace("{f}", "webp")
        return url.takeIf { isTrustedArtworkUrl(it) }
    }

    private suspend fun fetchMotionVideo(
        collectionId: Long,
        token: String,
        country: String,
    ): MotionVideos? {
        try {
            val response =
                withTimeout(15_000.milliseconds) {
                    httpClient
                        .prepareGet("https://amp-api.music.apple.com/v1/catalog/${country.lowercase()}/albums/$collectionId") {
                            header("Authorization", "Bearer $token")
                            header("Origin", "https://music.apple.com")
                            header("Accept", "application/json")

                            parameter("art[url]", "f")
                            parameter(
                                "extend",
                                "editorialArtwork,editorialVideo,extendedAssetUrls,offers,seoDescription,seoTitle",
                            )
                            parameter("fields[artists]", "name,url")
                            parameter("fields[curators]", "name")
                            parameter("fields[record-labels]", "name,url")
                            parameter("format[resources]", "map")
                            parameter("include", "record-labels,artists")
                            parameter("include[music-videos]", "artists")
                            parameter("include[playlists]", "curator")
                            parameter("include[songs]", "artists,composers,albums")
                            val resolvedLang =
                                Settings.shared.lastResolvedContentLanguage
                                    .lowercase()
                                    .takeIf(String::isNotBlank)
                            if (resolvedLang != null) {
                                parameter("l", resolvedLang)
                            }
                            parameter("meta[albums:tracks]", "popularity")
                            parameter("platform", "web")
                            parameter(
                                "views",
                                "appears-on,audio-extras,more-by-artist,other-versions,related-videos,video-extras,you-might-also-like",
                            )
                        }.readTextLimited(MAX_JSON_RESPONSE_BYTES)
                }
            if (response.status.value == 401) {
                tokenMutex.withLock { tokenCache = null }
                YtLogger.w(TAG, "AMP API 401 - token rejected")
            }
            if (!response.status.value
                    .toString()
                    .startsWith("2")
            ) {
                YtLogger.w(TAG, "AMP API returned ${response.status.value} (body omitted)")
                return null
            }
            val body = response.body ?: return null

            val parsed = json.parseToJsonElement(body)

            val attributes =
                parsed.jsonObject["resources"]
                    ?.jsonObject
                    ?.get("albums")
                    ?.jsonObject
                    ?.get(collectionId.toString())
                    ?.jsonObject
                    ?.get("attributes")
                    ?.jsonObject
                    ?: parsed.jsonObject["data"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("attributes")
                        ?.jsonObject

            val ev = attributes?.get("editorialVideo")?.jsonObject
            if (ev == null) {
                YtLogger.d(TAG, "No editorialVideo in AMP response")
                return null
            }

            YtLogger.d(TAG, "editorialVideo keys: ${ev.keys}")

            fun videoEntry(key: String): JsonObject? =
                (ev[key] as? JsonObject)?.takeIf {
                    (it["video"] as? JsonPrimitive)?.content?.isNotBlank() == true
                }

            fun videoUrl(entry: JsonObject?): String? = (entry?.get("video") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

            fun previewUrl(
                entry: JsonObject?,
                targetWidth: Int,
                targetHeight: Int,
            ): String? {
                val preview = entry?.get("previewFrame") as? JsonObject ?: return null
                val template = (preview["url"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
                val sourceWidth = (preview["width"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
                val sourceHeight = (preview["height"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
                val scale =
                    if (sourceWidth != null && sourceHeight != null) {
                        minOf(sourceWidth.toDouble() / targetWidth, sourceHeight.toDouble() / targetHeight).coerceAtMost(1.0)
                    } else {
                        1.0
                    }
                val resolvedWidth = (targetWidth * scale).toInt().coerceAtLeast(1)
                val resolvedHeight = (targetHeight * scale).toInt().coerceAtLeast(1)
                val resolved =
                    template
                        .replace("{w}", resolvedWidth.toString())
                        .replace("{h}", resolvedHeight.toString())
                        .replace("{c}", "bb")
                        .replace("{f}", "jpg")
                return resolved.takeIf(::isTrustedArtworkUrl)
            }

            val tallEntry = listOf("motionTallVideo3x4", "motionDetailTall").firstNotNullOfOrNull(::videoEntry)
            val directSquareEntry = listOf("motionSquareVideo1x1", "motionDetailSquare").firstNotNullOfOrNull(::videoEntry)
            val squareEntry =
                directSquareEntry
                    ?: tallEntry
                    ?: ev.keys.firstNotNullOfOrNull(::videoEntry)
            val square = videoUrl(squareEntry)
            val tall = videoUrl(tallEntry)
            if (square != null) {
                val squareIsTallFallback = directSquareEntry == null && tallEntry != null && squareEntry === tallEntry
                val squarePreview = if (squareIsTallFallback) null else previewUrl(squareEntry, 768, 768)
                val tallPreview = previewUrl(tallEntry, 768, 1024)
                YtLogger.d(TAG, "Found motion video (tall variant: ${tall != null})")
                return MotionVideos(square, tall, squarePreview, tallPreview, squareIsTallFallback)
            }

            YtLogger.d(TAG, "No video URLs in editorialVideo")
            return null
        } catch (_: TimeoutCancellationException) {
            YtLogger.w(TAG, "AMP API request timed out")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.w(TAG, "AMP API error (${e::class.simpleName})")
            return null
        }
    }

    private suspend fun resolveTallVariant(masterUrl: String?): Result<String?> {
        masterUrl ?: return Result.success(null)
        return try {
            Result.success(resolveBestVariant(masterUrl, Settings.shared.animatedCoverResolution)?.takeIf { isTrustedArtworkUrl(it) })
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        }
    }

    private suspend fun resolveBestVariant(
        masterUrl: String,
        resolution: AnimatedCoverResolution,
    ): String? {
        if (!isTrustedArtworkUrl(masterUrl)) {
            YtLogger.w(TAG, "Refusing to resolve untrusted animated artwork master URL")
            return null
        }
        val masterPath = URLBuilder().takeFrom(masterUrl).build().encodedPath
        if (masterPath.endsWith(".mp4")) return masterUrl
        if (!masterPath.endsWith(".m3u8")) return null

        try {
            val playlist =
                withTimeout(10_000.milliseconds) {
                    val response = httpClient.prepareGet(masterUrl).readTextLimited(MAX_PLAYLIST_RESPONSE_BYTES)
                    if (!response.status.isSuccess()) throw ArtistArtworkFetchException("HLS HTTP ${response.status.value}")
                    response.body
                } ?: throw ArtistArtworkFetchException("HLS body unavailable")
            val lines = playlist.lines()

            var currentInfo: String? = null
            var bestVariant: String? = null
            var bestPixels = 0L
            var bestBandwidth = 0L
            var fallbackVariant: String? = null
            var fallbackPixels = Long.MAX_VALUE
            val maxDimension = maxDimensionForSetting(resolution)

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                    currentInfo = trimmed
                } else if (!trimmed.startsWith("#") && trimmed.isNotBlank() && currentInfo != null) {
                    val variantUrl = resolvePlaylistUrl(masterUrl, trimmed)
                    if (variantUrl == null || !isTrustedArtworkUrl(variantUrl) || !variantUrl.contains(".m3u8")) {
                        currentInfo = null
                        continue
                    }
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(currentInfo)
                    if (resMatch != null) {
                        val width = resMatch.groupValues[1].toInt()
                        val height = resMatch.groupValues[2].toInt()
                        val pixels = width * height.toLong()
                        if (pixels < fallbackPixels) {
                            fallbackPixels = pixels
                            fallbackVariant = variantUrl
                        }
                        val bandwidth =
                            Regex("""(?:#EXT-X-STREAM-INF:|,)BANDWIDTH=(\d+)""")
                                .find(currentInfo)
                                ?.groupValues
                                ?.get(1)
                                ?.toLongOrNull()
                                ?: 0L

                        if (
                            minOf(width, height) <= maxDimension &&
                            (pixels > bestPixels || (pixels == bestPixels && bandwidth > bestBandwidth))
                        ) {
                            bestVariant = variantUrl
                            bestPixels = pixels
                            bestBandwidth = bandwidth
                        }
                    }
                    currentInfo = null
                }
            }

            val selected = bestVariant ?: fallbackVariant ?: return null
            YtLogger.d(TAG, "Selected HLS variant: ${if (bestVariant != null) bestPixels else fallbackPixels} pixels")
            return selected
        } catch (e: TimeoutCancellationException) {
            YtLogger.w(TAG, "M3U8 request timed out")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.w(TAG, "M3U8 parse error (${e::class.simpleName})")
            throw e
        }
    }

    private fun resolvePlaylistUrl(
        masterUrl: String,
        variant: String,
    ): String? = resolveAnimatedArtworkPlaylistUrl(masterUrl, variant)

    private fun maxDimensionForSetting(resolution: AnimatedCoverResolution): Int =
        when (resolution) {
            AnimatedCoverResolution.LOW_360 -> 360
            AnimatedCoverResolution.MEDIUM_720 -> 720
            AnimatedCoverResolution.HIGH_1080 -> 1080
            AnimatedCoverResolution.ORIGINAL -> Int.MAX_VALUE
        }

    private fun currentTimeSeconds(): Long = (Clock.System.now().toEpochMilliseconds() / 1000)
}

internal fun isTrustedAnimatedArtworkUrl(url: String): Boolean {
    val parsed =
        try {
            URLBuilder().takeFrom(url)
        } catch (_: Exception) {
            return false
        }
    if (parsed.protocol.name != "https") return false
    if (!parsed.user.isNullOrEmpty() || !parsed.password.isNullOrEmpty()) return false
    if (parsed.port != 0 && parsed.port != 443) return false
    val host = parsed.host.lowercase()
    return host == "itunes.apple.com" ||
        host.endsWith(".apple.com") ||
        host.endsWith(".mzstatic.com") ||
        host.endsWith(".akamaized.net")
}

internal fun resolveAnimatedArtworkPlaylistUrl(
    masterUrl: String,
    variant: String,
): String? {
    if (variant.startsWith("https://")) return variant
    val match = Regex("^(https://[^/]+)(/.*)?$").find(masterUrl) ?: return null
    val origin = match.groupValues[1]
    if (variant.startsWith("/")) return origin + variant
    val path =
        match.groupValues
            .getOrNull(2)
            .orEmpty()
            .substringBeforeLast('/', "")
    return "$origin/$path/$variant".replace(Regex("(?<!:)//+"), "/")
}

data class AnimatedCoverResult(
    val url: String,
    val tallUrl: String? = null,
    val previewImageUrl: String? = null,
    val tallPreviewImageUrl: String? = null,
    val squareIsTallFallback: Boolean = false,
)

private class MotionVideos(
    val url: String,
    val tallUrl: String?,
    val previewImageUrl: String?,
    val tallPreviewImageUrl: String?,
    val squareIsTallFallback: Boolean,
)

data class ArtistArtworkResult(
    val imageUrl: String?,
    val videoUrl: String?,
)

sealed interface ArtistArtworkFetchResult {
    data class Found(
        val artwork: ArtistArtworkResult,
    ) : ArtistArtworkFetchResult

    data object Missing : ArtistArtworkFetchResult

    data object Unavailable : ArtistArtworkFetchResult
}

private class ArtistArtworkFetchException(
    message: String,
) : Exception(message)

@Serializable
private data class ArtistCacheEntry(
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val cachedAt: Long = 0L,
    val isMissing: Boolean = imageUrl == null && videoUrl == null,
    val shortLived: Boolean = videoUrl == null,
) {
    fun toFetchResult(): ArtistArtworkFetchResult =
        if (isMissing) {
            ArtistArtworkFetchResult.Missing
        } else {
            ArtistArtworkFetchResult.Found(ArtistArtworkResult(imageUrl, videoUrl))
        }
}

@Serializable
private data class CacheEntry(
    val url: String? = null,
    val cachedAt: Long = 0L,
    val tallUrl: String? = null,
    val previewImageUrl: String? = null,
    val tallPreviewImageUrl: String? = null,
    val squareIsTallFallback: Boolean = false,
)

@Serializable
private data class TokenPayload(
    val iss: String? = null,
    val exp: Long? = null,
)

@Serializable
private data class AniTunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<AniTunesSearchResult> = emptyList(),
)

@Serializable
private data class AniTunesSearchResult(
    val trackName: String = "",
    val artistName: String = "",
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val collectionExplicitness: String? = null,
    val trackExplicitness: String? = null,
    val kind: String = "",
    val trackId: Long = 0,
)

@Serializable
private data class AniTunesArtistSearchResponse(
    val resultCount: Int = 0,
    val results: List<AniTunesArtistResult> = emptyList(),
)

@Serializable
private data class AniTunesArtistResult(
    val artistName: String = "",
    val artistId: Long = 0,
)
