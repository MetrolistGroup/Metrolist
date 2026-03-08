package com.metrolist.shared.api.musicvideo

import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.MusicVideoType
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canSwitchToAudioTrack
import com.metrolist.shared.model.canSwitchToMusicVideo
import com.metrolist.shared.model.isOfficialVideoType
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.model.isVideoType
import com.metrolist.shared.model.parseMusicVideoType
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock

private fun isOfficialOrLiveVideoType(type: String?): Boolean =
    isOfficialVideoType(type) || type?.contains("LIVE", ignoreCase = true) == true

private fun acceptsMusicVideoType(type: String?): Boolean = type == null || isOfficialOrLiveVideoType(type)

private fun acceptsMusicTrackType(type: String?): Boolean =
    type == null || parseMusicVideoType(type) == MusicVideoType.ATV || !isVideoType(type)

sealed interface MusicVideoResult {
    data class Found(
        val videoId: String,
        val positionMap: CounterpartPositionMap? = null,
        val source: CounterpartResolutionSource = CounterpartResolutionSource.HEURISTIC,
    ) : MusicVideoResult

    data object NotFound : MusicVideoResult

    data object Error : MusicVideoResult
}

sealed interface MusicTrackResult {
    data class Found(
        val song: SongItem,
        val positionMap: CounterpartPositionMap? = null,
        val source: CounterpartResolutionSource = CounterpartResolutionSource.HEURISTIC,
    ) : MusicTrackResult

    data object NotFound : MusicTrackResult

    data object Error : MusicTrackResult
}

class MusicVideoResolver(
    private val search: suspend (query: String, filter: SearchFilter) -> SearchResultPage = { query, filter ->
        GlobalYouTubeRepository.instance.searchFiltered(query, filter, recordSearchHistory = false, allowOfflineFallback = false)
    },
    private val counterpartLookup: suspend (SongItem) -> MusicVideoCounterpart? = { item ->
        GlobalYouTubeRepository.instance.getMusicVideoCounterpart(item.id, item.endpointParams)
    },
    private val albumLookup: suspend (String) -> AlbumPage? = { albumId ->
        GlobalYouTubeRepository.instance.getAlbumVideoPage(albumId)
            ?: GlobalYouTubeRepository.instance.getAlbum(albumId)
    },
    private val songLookup: suspend (String) -> SongItem? = { videoId ->
        GlobalYouTubeRepository.instance.getSongMetadata(videoId)
    },
    private val settings: Settings = Settings.shared,
    private val clock: Clock = Clock.System,
) {
    private data class CacheEntry(
        val videoId: String?,
        val cachedAtMs: Long,
        val positionMap: CounterpartPositionMap? = null,
    )

    private val cache = mutableMapOf<String, CacheEntry>()
    private val authoritativeCache = mutableMapOf<String, Pair<Long, MusicVideoCounterpart>>()

    private data class TrackLookupKey(
        val videoId: String,
        val title: String,
        val artists: List<ArtistTiny>,
        val duration: Long?,
        val endpointParams: String?,
        val session: List<String>,
    )

    private val trackCache = mutableMapOf<TrackLookupKey, Pair<Long, MusicTrackResult.Found>>()
    private val trackInFlight = mutableMapOf<TrackLookupKey, CompletableDeferred<MusicTrackResult>>()
    private var cacheGeneration = 0L
    private val inFlight = mutableMapOf<String, CompletableDeferred<MusicVideoResult>>()
    private val cacheMutex = Mutex()
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    init {
        loadCacheFromDisk()
    }

    suspend fun resolveMusicVideo(song: SongItem): MusicVideoResult {
        if (!song.canSwitchToMusicVideo) return MusicVideoResult.NotFound
        while (true) {
            val request = CompletableDeferred<MusicVideoResult>()
            val (inFlightRequest, generation) =
                cacheMutex.withLock {
                    inFlight.getOrPut(song.id) { request } to cacheGeneration
                }
            if (inFlightRequest !== request) {
                try {
                    return inFlightRequest.await()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    cacheMutex.withLock { if (inFlight[song.id] === inFlightRequest) inFlight.remove(song.id) }
                    continue
                }
            }
            return try {
                val result = resolveMusicVideoUncoalesced(song, generation)
                cacheMutex.withLock {
                    val current = if (generation == cacheGeneration) result else MusicVideoResult.Error
                    request.complete(current)
                    current
                }
            } catch (e: CancellationException) {
                request.completeExceptionally(e)
                throw e
            } catch (e: Exception) {
                YtLogger.w(TAG, "media link lookup failed type=${e::class.simpleName ?: "unknown"}")
                request.complete(MusicVideoResult.Error)
                MusicVideoResult.Error
            } finally {
                withContext(NonCancellable) {
                    cacheMutex.withLock { if (inFlight[song.id] === request) inFlight.remove(song.id) }
                }
            }
        }
    }

    private suspend fun resolveMusicVideoUncoalesced(
        song: SongItem,
        generation: Long,
    ): MusicVideoResult {
        YtLogger.d(TAG, "resolveMusicVideo start")
        val cachedResult = cachedMusicVideoResult(song)
        if (cachedResult is MusicVideoResult.Found) return cachedResult
        lookupAuthoritative(song, generation)?.let { counterpart ->
            val type = counterpart.targetMusicVideoType
            if (counterpart.targetVideoId != song.id && acceptsMusicVideoType(type)) {
                val result =
                    MusicVideoResult.Found(
                        videoId = counterpart.targetVideoId,
                        positionMap = counterpart.positionMap,
                        source = CounterpartResolutionSource.AUTHORITATIVE,
                    )
                cacheResult(song, result, generation)
                return result
            }
        }
        val albumId = song.album?.id ?: songLookup(song.id)?.album?.id
        if (albumId != null) {
            lookupAlbumVideo(song, albumId)?.takeIf { it != song.id }?.let { mvId ->
                val result =
                    MusicVideoResult.Found(
                        videoId = mvId,
                        source = CounterpartResolutionSource.ALBUM_RELEASE,
                    )
                cacheResult(song, result, generation)
                return result
            }
        }
        cachedResult?.let { return it }

        val result = runLookup(song)
        cacheResult(song, result, generation)
        return result
    }

    private suspend fun lookupAlbumVideo(
        song: SongItem,
        albumId: String,
    ): String? {
        val albumPage =
            try {
                albumLookup(albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                YtLogger.w(TAG, "album video lookup failed type=${e::class.simpleName ?: "unknown"}")
                null
            } ?: return null

        if (albumPage.songs.isEmpty()) return null

        val cleanSongTitle = MusicVideoMatcher.cleanCoreTitle(song.title)
        val normSongTitle = MusicVideoMatcher.normalizeTitle(song.title)

        val match =
            if (albumPage.songs.size == 1) {
                val single = albumPage.songs.first()
                if (single.id != song.id &&
                    (single.musicVideoType?.contains("OMV", ignoreCase = true) == true || single.isVideoSong)
                ) {
                    single
                } else {
                    null
                }
            } else {
                albumPage.songs.firstOrNull { albumSong ->
                    if (albumSong.id == song.id) return@firstOrNull false
                    val isVideo =
                        albumSong.musicVideoType?.contains("OMV", ignoreCase = true) == true ||
                            albumSong.isVideoSong
                    if (!isVideo) return@firstOrNull false
                    val cleanAlbumTitle = MusicVideoMatcher.cleanCoreTitle(albumSong.title)
                    val normAlbumTitle = MusicVideoMatcher.normalizeTitle(albumSong.title)
                    cleanAlbumTitle == cleanSongTitle ||
                        normAlbumTitle == normSongTitle ||
                        albumSong.title.equals(song.title, ignoreCase = true)
                }
            }

        if (match != null && match.id != song.id) {
            YtLogger.d(TAG, "resolved official music video from album release")
            return match.id
        }
        return null
    }

    suspend fun resolveMusicTrack(video: SongItem): MusicTrackResult {
        if (!video.canSwitchToAudioTrack) return MusicTrackResult.NotFound
        while (true) {
            val key = TrackLookupKey(video.id, video.title, video.artists, video.duration, video.endpointParams, trackSession())
            val request = CompletableDeferred<MusicTrackResult>()
            val (cached, pending, generation) =
                cacheMutex.withLock {
                    val now = clock.nowMs()
                    trackCache.entries.removeAll { now - it.value.first >= TRACK_CACHE_TTL_MS || it.key.session != key.session }
                    val cached = trackCache[key]?.second
                    Triple(cached, if (cached != null) request else trackInFlight.getOrPut(key) { request }, cacheGeneration)
                }
            if (pending !== request) {
                try {
                    return pending.await()
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    cacheMutex.withLock { if (trackInFlight[key] === pending) trackInFlight.remove(key) }
                    continue
                }
            }
            return try {
                val result = cached ?: resolveMusicTrackUncoalesced(video, generation)
                val current =
                    cacheMutex.withLock {
                        val current = if (generation == cacheGeneration && key.session == trackSession()) result else MusicTrackResult.Error
                        if (current is MusicTrackResult.Found) {
                            if (cached == null) trackCache[key] = clock.nowMs() to current
                            while (trackCache.size > CACHE_MAX_ENTRIES) {
                                trackCache.minByOrNull { it.value.first }?.key?.let(trackCache::remove)
                            }
                            if (current.song.canSwitchToMusicVideo) {
                                // The audio search need not return this video when searched in reverse.
                                cache[buildCacheKey(current.song)] = CacheEntry(video.id, clock.nowMs(), current.positionMap?.reversed())
                                evictExpiredAndOversizedLocked()
                            }
                        }
                        request.complete(current)
                        current
                    }
                if (current is MusicTrackResult.Found) saveCacheToDisk()
                current
            } catch (error: CancellationException) {
                request.completeExceptionally(error)
                throw error
            } catch (error: Exception) {
                YtLogger.w(TAG, "music track lookup failed type=${error::class.simpleName ?: "unknown"}")
                request.complete(MusicTrackResult.Error)
                MusicTrackResult.Error
            } finally {
                withContext(NonCancellable) {
                    cacheMutex.withLock { if (trackInFlight[key] === request) trackInFlight.remove(key) }
                }
            }
        }
    }

    private fun trackSession(): List<String> =
        listOf(
            settings.innerTubeCookie,
            settings.dataSyncId,
            settings.innerTubeAuthUser,
            settings.contentLanguage,
            settings.contentCountry,
            settings.lastResolvedContentLanguage,
            settings.lastResolvedContentCountry,
        )

    private suspend fun resolveMusicTrackUncoalesced(
        video: SongItem,
        generation: Long,
    ): MusicTrackResult {
        lookupAuthoritative(video, generation)?.let { counterpart ->
            val type = counterpart.targetMusicVideoType
            if (counterpart.targetVideoId != video.id && acceptsMusicTrackType(type)) {
                val song =
                    try {
                        songLookup(counterpart.targetVideoId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        YtLogger.w(TAG, "counterpart track metadata failed type=${e::class.simpleName ?: "unknown"}")
                        null
                    }
                if (song != null && song.id != video.id) {
                    return MusicTrackResult.Found(
                        song = song,
                        positionMap = counterpart.positionMap,
                        source = CounterpartResolutionSource.AUTHORITATIVE,
                    )
                }
            }
        }
        val page =
            try {
                search(buildTrackSearchQuery(video), SearchFilter.SONG)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                YtLogger.w(TAG, "music track search failed type=${e::class.simpleName ?: "unknown"}")
                return MusicTrackResult.Error
            }
        if (page.onlineSearchFailed) return MusicTrackResult.Error
        val match = MusicVideoMatcher.findBestTrackMatch(video, page.items)
        return if (match != null && match.id != video.id) MusicTrackResult.Found(match) else MusicTrackResult.NotFound
    }

    suspend fun evictMapping(song: SongItem) {
        cacheMutex.withLock {
            cacheGeneration++
            cache.entries.removeAll { it.key == buildCacheKey(song) || it.value.videoId == song.id }
            authoritativeCache.remove(song.id)
            trackCache.entries.removeAll { it.key.videoId == song.id || it.value.second.song.id == song.id }
            trackInFlight.keys.filter { it.videoId == song.id }.forEach { key ->
                trackInFlight.remove(key)?.complete(MusicTrackResult.Error)
            }
            inFlight.remove(song.id)?.let { if (!it.isCompleted) it.complete(MusicVideoResult.Error) }
        }
        saveCacheToDisk()
    }

    suspend fun getCacheEntryCount(): Int = cacheMutex.withLock { cache.size }

    fun getCacheSizeBytes(): Long {
        val raw = settings.musicVideoCacheJson
        return if (raw.isBlank() || raw == "{}") 0L else raw.encodeToByteArray().size.toLong()
    }

    suspend fun clearCache() {
        cacheMutex.withLock {
            cacheGeneration++
            cache.clear()
            authoritativeCache.clear()
            trackCache.clear()
            trackInFlight.values.forEach { it.complete(MusicTrackResult.Error) }
            trackInFlight.clear()
            inFlight.values.forEach { if (!it.isCompleted) it.complete(MusicVideoResult.Error) }
            inFlight.clear()
            settings.musicVideoCacheJson = "{}"
        }
        YtLogger.d(TAG, "media link cache cleared")
    }

    private suspend fun runLookup(item: SongItem): MusicVideoResult {
        val officialPage =
            try {
                search(buildVideoSearchQuery(item, includeOfficial = true), SearchFilter.VIDEO)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                YtLogger.w(TAG, "media link search failed type=${e::class.simpleName ?: "unknown"}")
                return MusicVideoResult.Error
            }
        if (officialPage.onlineSearchFailed) return MusicVideoResult.Error
        val officialMatch = MusicVideoMatcher.findBestMatch(item, officialPage.items)
        if (officialMatch != null && isOfficialVideoType(officialMatch.musicVideoType)) {
            return MusicVideoResult.Found(officialMatch.id)
        }

        val widePage =
            try {
                search(buildVideoSearchQuery(item, includeOfficial = false), SearchFilter.VIDEO)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                YtLogger.w(TAG, "media link search (wide) failed type=${e::class.simpleName ?: "unknown"}")
                return MusicVideoResult.Error
            }
        if (widePage.onlineSearchFailed) return MusicVideoResult.Error
        val wideMatch = MusicVideoMatcher.findBestMatch(item, widePage.items)

        if (wideMatch != null && isOfficialVideoType(wideMatch.musicVideoType)) {
            return MusicVideoResult.Found(wideMatch.id)
        }

        val bestMatch =
            officialMatch?.takeIf { acceptsMusicVideoType(it.musicVideoType) }
                ?: wideMatch?.takeIf { acceptsMusicVideoType(it.musicVideoType) }

        return bestMatch?.let { MusicVideoResult.Found(it.id) } ?: MusicVideoResult.NotFound
    }

    private suspend fun lookupAuthoritative(
        item: SongItem,
        generation: Long,
    ): MusicVideoCounterpart? {
        cacheMutex
            .withLock {
                evictExpiredAndOversizedLocked()
                authoritativeCache[item.id]?.second
            }?.let { return it }
        val counterpart =
            try {
                counterpartLookup(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                YtLogger.w(TAG, "authoritative counterpart lookup unavailable type=${e::class.simpleName ?: "unknown"}")
                null
            }
        if (counterpart != null) {
            cacheMutex.withLock {
                if (generation == cacheGeneration) {
                    authoritativeCache[item.id] = clock.nowMs() to counterpart
                    evictExpiredAndOversizedLocked()
                }
            }
        }
        return counterpart
    }

    private suspend fun cacheResult(
        item: SongItem,
        result: MusicVideoResult,
        generation: Long,
    ) {
        if (result is MusicVideoResult.Error) return
        val found = result as? MusicVideoResult.Found
        cacheMutex.withLock {
            if (generation != cacheGeneration) return
            cache[buildCacheKey(item)] =
                CacheEntry(
                    videoId = found?.videoId,
                    cachedAtMs = clock.nowMs(),
                    positionMap = found?.positionMap,
                )
            evictExpiredAndOversizedLocked()
        }
        saveCacheToDisk()
    }

    private suspend fun cachedMusicVideoResult(item: SongItem): MusicVideoResult? {
        val cached =
            cacheMutex.withLock { cache[buildCacheKey(item)]?.takeIf { !isExpired(it) } }
                ?: return null
        YtLogger.d(TAG, "media link lookup cache hit")
        return cached.videoId?.let { MusicVideoResult.Found(it, cached.positionMap) } ?: MusicVideoResult.NotFound
    }

    private fun isExpired(entry: CacheEntry): Boolean {
        val ttlMs = if (entry.videoId != null) CACHE_FOUND_TTL_MS else CACHE_NEGATIVE_TTL_MS
        return clock.nowMs() - entry.cachedAtMs > ttlMs
    }

    private fun evictExpiredAndOversizedLocked() {
        val nowMs = clock.nowMs()
        authoritativeCache.entries.removeAll { nowMs - it.value.first > CACHE_FOUND_TTL_MS }
        while (authoritativeCache.size > CACHE_MAX_ENTRIES) {
            authoritativeCache.minByOrNull { it.value.first }?.key?.let(authoritativeCache::remove)
        }
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val ttlMs = if (entry.videoId != null) CACHE_FOUND_TTL_MS else CACHE_NEGATIVE_TTL_MS
            if (nowMs - entry.cachedAtMs > ttlMs) iterator.remove()
        }
        while (cache.size > CACHE_MAX_ENTRIES) {
            val oldestKey = cache.minByOrNull { (_, entry) -> entry.cachedAtMs }?.key ?: return
            cache.remove(oldestKey)
        }
    }

    private suspend fun saveCacheToDisk() {
        try {
            cacheMutex.withLock {
                val map =
                    buildJsonObject {
                        put(CACHE_SCHEMA_KEY, CACHE_SCHEMA_VERSION)
                        for ((key, entry) in cache) {
                            put(key, encodeCacheEntry(entry))
                        }
                    }
                settings.musicVideoCacheJson = json.encodeToString(JsonObject(map))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.w(TAG, "failed to persist media link cache (${e::class.simpleName})")
        }
    }

    private fun encodeCacheEntry(entry: CacheEntry): JsonElement =
        buildJsonObject {
            put("v", entry.videoId?.let(::JsonPrimitive) ?: JsonNull)
            put("t", entry.cachedAtMs)
            entry.positionMap?.let { put("p", json.encodeToJsonElement(it)) }
        }

    private fun loadCacheFromDisk() {
        try {
            val raw = settings.musicVideoCacheJson
            if (raw.isBlank() || raw == "{}") return
            val obj = json.parseToJsonElement(raw).jsonObject
            if (obj[CACHE_SCHEMA_KEY]?.jsonPrimitive?.contentOrNull != CACHE_SCHEMA_VERSION) {
                settings.musicVideoCacheJson = "{}"
                return
            }
            for ((key, value) in obj) {
                if (key == CACHE_SCHEMA_KEY) continue
                cache[key] = decodeCacheEntry(value)
            }
            evictExpiredAndOversizedLocked()
            YtLogger.d(TAG, "loaded ${cache.size} music video cache entries")
        } catch (e: Exception) {
            YtLogger.w(TAG, "failed to load music video cache (${e::class.simpleName})")
        }
    }

    private fun decodeCacheEntry(value: JsonElement): CacheEntry {
        val obj = (value as? JsonObject) ?: return CacheEntry(videoId = null, cachedAtMs = 0L)
        val videoId =
            obj["v"]?.let { stored ->
                (stored as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: runCatching { json.decodeFromJsonElement(SongItem.serializer(), stored).id }.getOrNull()
            }
        val cachedAtMs = obj["t"]?.jsonPrimitive?.longOrNull ?: 0L
        val positionMap =
            obj["p"]?.let { runCatching { json.decodeFromJsonElement<CounterpartPositionMap>(it) }.getOrNull() }
        return CacheEntry(videoId = videoId, cachedAtMs = cachedAtMs, positionMap = positionMap)
    }

    private fun buildVideoSearchQuery(
        song: SongItem,
        includeOfficial: Boolean,
    ): String {
        val artists = song.artists.map { it.name.trim() }.filter { it.isNotBlank() }
        val artistPart =
            when {
                artists.isEmpty() -> ""
                artists.size == 1 -> artists[0]
                else -> artists.take(2).joinToString(" ")
            }
        val cleanTitle = song.title.trim()
        val base = if (artistPart.isBlank()) cleanTitle else "$cleanTitle $artistPart"
        return if (includeOfficial) "$base official video" else base
    }

    private fun buildCacheKey(item: SongItem): String {
        val artists =
            item.artists
                .map { MusicVideoMatcher.normalizeName(it.name) }
                .filter { it.isNotBlank() }
                .sorted()
        val artistPart = if (artists.isEmpty()) "" else artists.joinToString(",")
        return "v:${item.id}|$artistPart|${MusicVideoMatcher.normalizeTitle(item.title)}"
    }

    private fun buildTrackSearchQuery(video: SongItem): String {
        val artist = video.artists.firstOrNull()?.name
        return listOf(video.title.trim(), artist?.trim()).filterNot { it.isNullOrBlank() }.joinToString(" ")
    }

    private fun Clock.nowMs(): Long = now().toEpochMilliseconds()

    companion object {
        private const val TAG = "MusicVideoResolver"
        private const val CACHE_FOUND_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val CACHE_NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val CACHE_MAX_ENTRIES = 200
        private const val TRACK_CACHE_TTL_MS = 5L * 60 * 1000
        private const val CACHE_SCHEMA_KEY = "_schema"
        private const val CACHE_SCHEMA_VERSION = "3"
    }
}
