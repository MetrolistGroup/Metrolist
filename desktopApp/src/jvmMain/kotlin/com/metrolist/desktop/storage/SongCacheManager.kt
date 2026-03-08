/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.metrolist.desktop.storage

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.unknown_artist
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.network.sharedHttpEngine
import com.metrolist.shared.network.sharedHttpTransportHandlesFailures
import com.metrolist.shared.playback.PlaybackPreCache
import com.metrolist.shared.playback.canReuseCachedAudio
import com.metrolist.shared.playback.downloadSabrAudio
import com.metrolist.shared.playback.ffmpeg.DirectPlaybackCache
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.storage.audioCacheBudget
import com.metrolist.shared.utils.XdgUtils
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import org.koin.mp.KoinPlatform
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Serializable
data class CachedSongEntry(
    val songId: String,
    val title: String,
    val artist: String,
    val filePath: String,
    val thumbnailPath: String? = null,
    val fileSize: Long,
    val cachedAt: Long,
    val explicitness: Boolean? = null,
    val loudnessDb: Double? = null,
    val perceptualLoudnessDb: Double? = null,
    val itag: Int? = null,
    val mimeType: String? = null,
    val codecs: String? = null,
    val lastAccessedAt: Long = cachedAt,
) {
    fun toPlaybackResource(song: SongItem?): PlaybackResource =
        PlaybackResource(
            videoId = songId,
            url = filePath,
            headers = emptyMap(),
            loudnessDb = loudnessDb,
            perceptualLoudnessDb = perceptualLoudnessDb,
            expiresAt = null,
            clientName = "local",
            allowPlaybackTrackingFallback =
                song != null && song.id == songId && !song.isLocal && !song.isUploaded && !song.isPodcast && !songId.startsWith("local_"),
            profileId = "cached-audio",
            contentLengthBytes = fileSize,
            itag = itag,
            mimeType = mimeType,
            codecs = codecs,
        )

    fun toSongItem(): SongItem =
        SongItem(
            id = songId,
            title = title,
            artists =
                listOf(
                    ArtistTiny(id = null, name = artist),
                ),
            thumbnail = thumbnailPath?.let { File(it).toURI().toString() },
            duration = null,
            album = null,
            isExplicit = explicitness == true,
            explicitness = explicitness,
        )
}

internal fun cacheEvictionCount(
    oldestEntrySizes: List<Long>,
    currentBytes: Long,
    maximumBytes: Long,
    reservedBytes: Long,
): Int {
    if (maximumBytes < 0L) return 0
    var remainingBytes = currentBytes
    oldestEntrySizes.forEachIndexed { index, size ->
        if (remainingBytes + reservedBytes <= maximumBytes) return index
        remainingBytes -= size
    }
    return oldestEntrySizes.size
}

object SongCacheManager {
    private val database: MusicDatabase? by lazy { KoinPlatform.getKoinOrNull()?.getOrNull<MusicDatabase>() }
    private const val DEFAULT_MAX_CACHE_SIZE_MB = 1024L
    private const val CACHE_INDEX_FILE_NAME = "cache_index_v1.json"
    private const val MAX_AUDIO_BYTES = 512L * 1024 * 1024
    private const val MAX_THUMBNAIL_BYTES = 16L * 1024 * 1024
    private val client by lazy {
        HttpClient(sharedHttpEngine) {
            install(HttpFailureLog) { transportHandledByEngine = sharedHttpTransportHandlesFailures }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 10 * 60_000
                socketTimeoutMillis = 30_000
            }
            expectSuccess = false
        }
    }
    private val scope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val indexLock = Any()
    private val accessTimes = CacheAccessTimes()
    private var accessSaveJob: Job? = null
    private var entriesById: Map<String, CachedSongEntry> = emptyMap()
    private val downloadMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _cachedSongIds = MutableStateFlow<Set<String>>(emptySet())
    val cachedSongIds: StateFlow<Set<String>> = _cachedSongIds.asStateFlow()
    private val _cachedSongs = MutableStateFlow<List<CachedSongEntry>>(emptyList())
    val cachedSongs: StateFlow<List<CachedSongEntry>> = _cachedSongs.asStateFlow()
    private val _cacheSizeBytes = MutableStateFlow(0L)
    private val settingsDir by lazy {
        File(
            XdgUtils.getXdgConfigHome(),
            "metrolist",
        ).also { check(it.mkdirs() || it.isDirectory) }
    }
    private val cacheSettingsFile by lazy { File(settingsDir, "cache.properties") }
    val cacheDir: File by lazy {
        File(
            XdgUtils.getXdgCacheHome(),
            "metrolist/cache",
        ).also { check(it.mkdirs() || it.isDirectory) }
    }
    private val indexFile by lazy { File(cacheDir, CACHE_INDEX_FILE_NAME) }

    init {
        DirectPlaybackCache.reconcileDirectory(playbackCaptureDirectory())
        val entries = readIndex()
        reconcileCacheDirectory(cacheDir, entries)
        publishIndex(entries)
    }

    private fun safeFile(path: String): File? =
        runCatching {
            val base = cacheDir.canonicalFile.toPath()
            File(path).canonicalFile.takeIf { it.toPath().startsWith(base) && it.parentFile?.toPath() == base }
        }.getOrNull()

    private fun cacheFile(name: String): File {
        val base = cacheDir.canonicalFile
        return File(base, name).canonicalFile.also { require(it.parentFile == base) }
    }

    private fun readIndex(): List<CachedSongEntry> =
        synchronized(indexLock) {
            if (!indexFile.isFile) {
                emptyList()
            } else {
                runCatching {
                    json.decodeFromString<List<CachedSongEntry>>(indexFile.readText()).filter {
                        DownloadManager.isValidSongId(it.songId) &&
                            safeFile(it.filePath) != null &&
                            (it.thumbnailPath == null || safeFile(it.thumbnailPath) != null)
                    }
                }.getOrDefault(emptyList())
            }
        }

    private fun publishIndex(entries: List<CachedSongEntry>) {
        entriesById = entries.associateBy { it.songId }
        _cacheSizeBytes.value =
            entries.sumOf { entry ->
                (safeFile(entry.filePath)?.takeIf { it.exists() }?.length() ?: 0L) +
                    (
                        entry.thumbnailPath
                            ?.let(::safeFile)
                            ?.takeIf { it.exists() }
                            ?.length() ?: 0L
                    )
            }
        _cachedSongIds.value =
            entries
                .filter {
                    safeFile(it.filePath)?.let { file ->
                        file.isFile && file.length() >= 1024
                    } == true
                }.mapTo(mutableSetOf()) { it.songId }
        _cachedSongs.value = entries.sortedByDescending { it.cachedAt }
    }

    private fun saveIndex(entries: List<CachedSongEntry>) =
        synchronized(indexLock) {
            val temp = File(indexFile.parentFile, "$CACHE_INDEX_FILE_NAME.${System.nanoTime()}.tmp")
            try {
                temp.writeText(json.encodeToString(entries))
                check(DownloadManager.promoteTempFile(temp, indexFile, 1)) { "Failed to save cache index" }
            } finally {
                temp.delete()
            }
        }

    private fun saveAndPublish(entries: List<CachedSongEntry>) =
        synchronized(indexLock) {
            val updated = accessTimes.applyTo(entries)
            saveIndex(updated)
            publishIndex(updated)
            accessTimes.clear()
        }

    private fun flushAccessTimes() =
        synchronized(indexLock) {
            if (accessTimes.isEmpty) return@synchronized
            try {
                saveIndex(accessTimes.applyTo(_cachedSongs.value))
            } catch (e: Exception) {
                AppLogger.error("SongCacheManager", "Could not save cache access time", e)
            }
        }

    private fun mutateIndex(block: (MutableList<CachedSongEntry>) -> Unit) =
        synchronized(indexLock) {
            saveAndPublish(_cachedSongs.value.toMutableList().also(block))
        }

    fun getMaxCacheSizeMB(): Long =
        cacheSettingsFile
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.toLongOrNull() ?: DEFAULT_MAX_CACHE_SIZE_MB

    fun setMaxCacheSizeMB(sizeMB: Long) {
        synchronized(indexLock) {
            cacheSettingsFile.writeText(sizeMB.toString())
            DirectPlaybackCache.clear(playbackCaptureDirectory())
            evictIfNeeded()
        }
    }

    fun isSongCached(songId: String): Boolean = songId in _cachedSongIds.value

    fun getCachedFilePath(songId: String): String? =
        _cachedSongs.value
            .find { it.songId == songId }
            ?.takeIf { isSongCached(songId) }
            ?.filePath
            ?.takeIf { File(it).isFile }

    private fun cachedEntry(songId: String): CachedSongEntry? =
        _cachedSongs.value
            .find { it.songId == songId }
            ?.takeIf { entry -> safeFile(entry.filePath)?.let { it.isFile && it.length() >= 1024L } == true }

    suspend fun getCachedPlaybackResource(
        songId: String,
        song: SongItem?,
    ): PlaybackResource? {
        val entry =
            synchronized(indexLock) {
                val entry =
                    entriesById[songId]
                        ?.takeIf { safeFile(it.filePath)?.let { file -> file.isFile && file.length() >= 1024L } == true }
                        ?: return@synchronized null
                accessTimes.record(songId, System.currentTimeMillis())
                if (accessSaveJob?.isActive != true) {
                    accessSaveJob =
                        scope.launch {
                            delay(60_000)
                            synchronized(indexLock) {
                                flushAccessTimes()
                                accessSaveJob = null
                            }
                        }
                }
                entry
            } ?: return null
        return entry.toPlaybackResource(storedAudioReportingSong(song, database))
    }

    fun getCacheSizeBytes(): Long = _cacheSizeBytes.value

    fun getCacheSizeMB(): Long = getCacheSizeBytes() / (1024 * 1024)

    fun getCachedSongs(): List<CachedSongEntry> = _cachedSongs.value.filter { it.songId in _cachedSongIds.value }

    private fun extension(
        contentType: String?,
        image: Boolean,
    ) = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/webm" -> ".webm"
        "audio/mpeg", "audio/mp3" -> ".mp3"
        "audio/mp4", "audio/x-m4a", "audio/aac", "audio/mp4a-latm" -> ".m4a"
        "audio/ogg", "audio/opus" -> ".ogg"
        "audio/flac" -> ".flac"
        "video/mp4" -> ".mp4"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/avif" -> ".avif"
        else -> if (image) ".jpg" else ".audio"
    }

    internal suspend fun fetch(
        url: String,
        baseName: String,
        image: Boolean,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = if (image) MAX_THUMBNAIL_BYTES else MAX_AUDIO_BYTES,
    ): File? {
        var temp: File? = null
        try {
            return client
                .prepareGet(url) {
                    if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                        header("User-Agent", "Mozilla/5.0")
                    }
                    headers.forEach { (name, value) -> header(name, value) }
                }.execute { response ->
                    if (!response.status.isSuccess()) return@execute null
                    val limit = minOf(if (image) MAX_THUMBNAIL_BYTES else MAX_AUDIO_BYTES, maxBytes)
                    if (limit <= 0L) return@execute null
                    val expected = response.headers["Content-Length"]?.toLongOrNull()
                    if (expected != null && expected > limit) return@execute null
                    if (cacheDir.usableSpace <= (expected ?: 1024L) + 1024 * 1024) return@execute null
                    val destination = cacheFile(baseName + extension(response.headers["Content-Type"], image))
                    val pendingFile = cacheFile("$baseName.${System.nanoTime()}.tmp")
                    temp = pendingFile
                    var total = 0L
                    var spaceRemaining = 0L
                    response.bodyAsChannel().toInputStream().use { input ->
                        pendingFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (spaceRemaining < count) spaceRemaining = minOf(8L * 1024 * 1024, cacheDir.usableSpace - 1024 * 1024)
                                if (total > limit || spaceRemaining < count) {
                                    throw IOException("Download limit or disk space exceeded")
                                }
                                spaceRemaining -= count
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                        }
                    }
                    val minimumBytes = if (image) 256L else 1024L
                    if (total < minimumBytes || (expected != null && total != expected)) return@execute null
                    if (!image && reserveCacheBytes(total, completedTemporaryBytes = total) < total) return@execute null
                    if (!DownloadManager.promoteTempFile(pendingFile, destination, minimumBytes)) return@execute null
                    destination
                }
        } catch (e: CancellationException) {
            throw e
        } finally {
            temp?.delete()
        }
    }

    suspend fun copyCachedAudio(
        resource: PlaybackResource,
        destination: File,
    ): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(indexLock) {
                val entry = _cachedSongs.value.find { it.songId == resource.videoId } ?: return@synchronized false
                if (!canReuseCachedAudio(resource, entry.itag, entry.mimeType, entry.codecs, entry.fileSize)) return@synchronized false
                val source = safeFile(entry.filePath)?.takeIf { it.isFile && it.length() == entry.fileSize } ?: return@synchronized false
                try {
                    source.copyTo(destination, overwrite = true)
                    true
                } catch (_: IOException) {
                    destination.delete()
                    false
                }
            }
        }

    fun capturePlayback(resource: PlaybackResource): DirectPlaybackCache.Capture? {
        if (!automaticCachingEnabled()) return null
        val budget = playbackCacheBudget()
        if (budget < 1024L) return null
        val size = resource.contentLengthBytes ?: return null
        if (size > budget || reserveCacheBytes(size, size) < size) return null
        return DirectPlaybackCache.begin(resource, playbackCaptureDirectory(), budget)
    }

    suspend fun prefetchPlayback(resource: PlaybackResource) {
        try {
            if (!automaticCachingEnabled()) return
            if (getCachedPlaybackResource(resource.musicVideoId ?: resource.videoId, song = null) != null) return
            val budget = playbackCacheBudget()
            if (budget < 1024L) return
            val size = resource.contentLengthBytes ?: return
            if (size > budget || reserveCacheBytes(size, size) < size) return
            PlaybackPreCache.prefetch(resource, playbackCaptureDirectory(), budget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    private fun automaticCachingEnabled(): Boolean =
        Settings.shared.songCacheEnabled && Settings.shared.devtoolsPlaybackClient == PlaybackClientCatalog.AUTOMATIC_ID

    private fun playbackCacheBudget(): Long {
        val maxMb = getMaxCacheSizeMB()
        return if (maxMb < 0) MAX_AUDIO_BYTES else minOf(MAX_AUDIO_BYTES, maxMb * 1024 * 1024)
    }

    private fun playbackCaptureDirectory(): File = File(cacheDir, "playback-tee")

    private suspend fun importPlaybackCapture(
        resource: PlaybackResource,
        songId: String,
        budget: Long,
    ): File? {
        val size = resource.contentLengthBytes ?: return null
        if (size > budget) return null
        val pending = cacheFile("$songId.${System.nanoTime()}.tmp")
        val destination = cacheFile(songId + extension(resource.mimeType, false))
        try {
            if (reserveCacheBytes(size) < size) return null
            if (!DirectPlaybackCache.copyComplete(resource, pending)) return null
            if (!DownloadManager.promoteTempFile(pending, destination, 1024L)) return null
            return destination
        } finally {
            pending.delete()
        }
    }

    private suspend fun fetchSabr(
        resource: PlaybackResource,
        songId: String,
        maxBytes: Long,
        awaitReady: suspend () -> Unit,
    ): File {
        val pending = cacheFile("$songId.${System.nanoTime()}.tmp")
        val destination = cacheFile(songId + extension(resource.mimeType, false))
        try {
            awaitReady()
            val reservation = resource.contentLengthBytes
            if (reservation != null && (reservation > maxBytes || reserveCacheBytes(reservation) < reservation)) {
                throw IOException("Audio exceeds cache budget")
            }
            downloadSabrAudio(resource, pending, maxBytes = maxBytes, awaitReady = awaitReady)
            val size = pending.length()
            if (reserveCacheBytes(size, completedTemporaryBytes = size) < size) throw IOException("Audio exceeds cache budget")
            if (!DownloadManager.promoteTempFile(pending, destination, 1024L)) {
                throw IOException("Audio cache promotion failed")
            }
            return destination
        } finally {
            pending.delete()
        }
    }

    fun cacheSong(
        song: SongItem,
        resource: PlaybackResource? = null,
        awaitReady: suspend () -> Unit = {},
    ) {
        if (resource != null && !resource.isDirectFile && !resource.isSabr) return
        if (resource?.clientName == "local") return
        val mvId = resource?.musicVideoId
        val targetSong =
            if (mvId != null && mvId != song.id) {
                song.copy(id = mvId)
            } else {
                song
            }
        if (!DownloadManager.isValidSongId(targetSong.id) || cachedEntry(targetSong.id) != null) return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (resource != null && resource.isDirectFile && resource.contentLengthBytes != null &&
                        !DirectPlaybackCache.awaitCompletion(resource)
                    ) {
                        return@launch
                    }
                    awaitReady()
                    downloadMutex.withLock {
                        var audio: File? = null
                        var thumbnail: File? = null
                        try {
                            awaitReady()
                            val cacheResource =
                                resource?.takeIf { candidate ->
                                    candidate.expiresAt?.let { it > Clock.System.now() + 60.seconds } != false
                                }
                                    ?: runCatching {
                                        val quality =
                                            try {
                                                AudioQuality.valueOf(Settings.shared.audioQuality)
                                            } catch (_: Exception) {
                                                AudioQuality.AUTO
                                            }
                                        val hints = targetSong.toContentHints().copy(playbackClientOverrideId = null, sabrFirst = false)
                                        GlobalYouTubeRepository.streamResolver?.resolveRemote(
                                            targetSong.id,
                                            hints,
                                            quality,
                                            sourceSong = targetSong,
                                            refresh = true,
                                        )
                                    }.getOrNull()
                                    ?: throw IOException("Failed to get stream URL")
                            if (!cacheResource.isDirectFile && !cacheResource.isSabr) {
                                return@withLock
                            }
                            val maximumCacheBytes = getMaxCacheSizeMB() * 1024 * 1024
                            val cacheBudget = if (maximumCacheBytes < 0L) MAX_AUDIO_BYTES else minOf(MAX_AUDIO_BYTES, maximumCacheBytes)
                            audio =
                                if (importPlaybackCapture(cacheResource, targetSong.id, cacheBudget) != null) {
                                    cacheFile(targetSong.id + extension(cacheResource.mimeType, false))
                                } else if (cacheResource.isSabr) {
                                    fetchSabr(cacheResource, targetSong.id, cacheBudget, awaitReady)
                                } else if (cacheResource.isDirectFile && cacheResource.contentLengthBytes == null) {
                                    fetch(cacheResource.url, targetSong.id, false, cacheResource.headers, cacheBudget)
                                        ?: throw IOException("Audio cache download failed")
                                } else {
                                    throw IOException("Playback capture is incomplete")
                                }
                            val thumbnailBudget =
                                if (maximumCacheBytes < 0L) {
                                    MAX_THUMBNAIL_BYTES
                                } else {
                                    (maximumCacheBytes - getCacheSizeBytes() - audio.length()).coerceAtLeast(0L)
                                }
                            thumbnail =
                                targetSong.thumbnail?.takeIf { it.isNotBlank() }?.let {
                                    try {
                                        fetch(it, "${targetSong.id}-thumb", true, maxBytes = thumbnailBudget)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            database?.let { db -> db.withDatabaseTransaction { db.dao().upsertHistorySong(targetSong) } }
                            val unknownArtistLabel = getString(Res.string.unknown_artist)
                            synchronized(indexLock) {
                                if (jobs[targetSong.id] !== coroutineContext[Job]) throw CancellationException("Cache entry removed")
                                mutateIndex { entries ->
                                    entries.removeAll { it.songId == targetSong.id }
                                    entries.add(
                                        CachedSongEntry(
                                            targetSong.id,
                                            targetSong.title,
                                            targetSong.artists.firstOrNull()?.name ?: unknownArtistLabel,
                                            audio.absolutePath,
                                            thumbnail?.absolutePath,
                                            audio.length(),
                                            System.currentTimeMillis(),
                                            targetSong.artworkExplicitness,
                                            loudnessDb = cacheResource.loudnessDb,
                                            perceptualLoudnessDb = cacheResource.perceptualLoudnessDb,
                                            itag = cacheResource.itag,
                                            mimeType = cacheResource.mimeType,
                                            codecs = cacheResource.codecs,
                                        ),
                                    )
                                }
                                evictIfNeeded()
                            }
                        } catch (e: CancellationException) {
                            audio?.delete()
                            thumbnail?.delete()
                            throw e
                        } catch (e: Exception) {
                            audio?.delete()
                            thumbnail?.delete()
                        }
                    }
                } finally {
                    jobs.remove(targetSong.id, coroutineContext[Job])
                }
            }
        if (jobs.putIfAbsent(targetSong.id, job) == null) job.start() else job.cancel()
    }

    fun removeSongFromCache(songId: String) = removeSongsFromCache(setOf(songId))

    fun removeSongsFromCache(songIds: Set<String>) {
        if (songIds.isEmpty()) return
        synchronized(indexLock) {
            songIds.forEach { jobs.remove(it)?.cancel() }
            removeCachedSongs(_cachedSongs.value, songIds, ::safeFile, ::saveAndPublish)
        }
    }

    fun cancelPendingCache() {
        synchronized(indexLock) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            DirectPlaybackCache.clear(playbackCaptureDirectory())
        }
    }

    fun clearCache() {
        synchronized(indexLock) {
            DirectPlaybackCache.clear(playbackCaptureDirectory())
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            removeSongsFromCache(_cachedSongs.value.mapTo(mutableSetOf()) { it.songId })
        }
    }

    private fun reserveCacheBytes(
        requestedBytes: Long,
        additionalTemporaryBytes: Long = 0L,
        completedTemporaryBytes: Long = 0L,
    ): Long =
        synchronized(indexLock) {
            val maximum =
                audioCacheBudget(
                    getMaxCacheSizeMB() * 1024 * 1024,
                    _cacheSizeBytes.value,
                    cacheDir.usableSpace,
                    1024 * 1024L,
                    additionalTemporaryBytes,
                    completedTemporaryBytes,
                )
            if (requestedBytes > maximum) return@synchronized 0L
            if (_cacheSizeBytes.value + requestedBytes <= maximum) {
                return@synchronized maximum - _cacheSizeBytes.value
            }
            val entries = cacheEntriesByLastAccess(accessTimes.applyTo(_cachedSongs.value))
            val evictionCount =
                cacheEvictionCount(
                    entries.map { entry ->
                        (safeFile(entry.filePath)?.length() ?: 0L) +
                            (entry.thumbnailPath?.let(::safeFile)?.length() ?: 0L)
                    },
                    _cacheSizeBytes.value,
                    maximum,
                    requestedBytes,
                )
            if (evictionCount > 0) {
                entries.take(evictionCount).forEach { entry ->
                    safeFile(entry.filePath)?.delete()
                    entry.thumbnailPath?.let(::safeFile)?.delete()
                }
                saveAndPublish(entries.drop(evictionCount))
            }
            (maximum - _cacheSizeBytes.value).coerceAtLeast(0L)
        }

    private fun evictIfNeeded() {
        reserveCacheBytes(0L)
    }

    fun release() {
        synchronized(indexLock) {
            accessSaveJob?.cancel()
            flushAccessTimes()
        }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        client.close()
        scope.cancel()
    }
}

internal fun removeCachedSongs(
    entries: List<CachedSongEntry>,
    songIds: Set<String>,
    safeFile: (String) -> File?,
    save: (List<CachedSongEntry>) -> Unit,
) {
    var failure: Exception? = null
    val remaining =
        entries.filter { entry ->
            if (entry.songId !in songIds) return@filter true
            try {
                listOfNotNull(entry.thumbnailPath, entry.filePath).forEach { path ->
                    val file = safeFile(path) ?: throw IOException("Cache path is unavailable")
                    Files.deleteIfExists(file.toPath())
                }
                false
            } catch (e: Exception) {
                if (failure == null) failure = e
                true
            }
        }
    save(remaining)
    failure?.let { throw it }
}
