/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.storage

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.network.sharedHttpEngine
import com.metrolist.shared.network.sharedHttpTransportHandlesFailures
import com.metrolist.shared.playback.downloadSabrAudio
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.storage.DownloadedPlaylists
import com.metrolist.shared.utils.XdgUtils
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.PlaybackResource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.mp.KoinPlatform
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

enum class DownloadState { NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

private fun playlistSongIds(songs: List<SongItem>): List<String> = songs.map(SongItem::id).distinct()

internal fun cancelDownloadJobs(
    songIds: Set<String>,
    jobs: MutableMap<String, Job>,
) {
    songIds.forEach { songId -> jobs.remove(songId)?.cancel() }
}

internal fun playlistDownloadState(
    songs: List<SongItem>,
    states: Map<String, DownloadState>,
): DownloadState {
    val songIds = playlistSongIds(songs)
    return when {
        songIds.isEmpty() -> DownloadState.NOT_DOWNLOADED
        songIds.all { states[it] == DownloadState.DOWNLOADED } -> DownloadState.DOWNLOADED
        songIds.any { states[it] == DownloadState.QUEUED || states[it] == DownloadState.DOWNLOADING } -> DownloadState.DOWNLOADING
        else -> DownloadState.NOT_DOWNLOADED
    }
}

internal fun playlistDownloadProgress(
    songs: List<SongItem>,
    states: Map<String, DownloadState>,
    progress: Map<String, Float>,
): Int {
    val songIds = playlistSongIds(songs)
    if (songIds.isEmpty()) return 0
    return (
        songIds.sumOf { songId ->
            if (states[songId] == DownloadState.DOWNLOADED) {
                1.0
            } else {
                progress[songId]?.coerceIn(0f, 1f)?.toDouble()
                    ?: 0.0
            }
        } / songIds.size * 100
    ).toInt()
}

@Serializable
data class DownloadedFile(
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val audioFilePath: String,
    val thumbnailFilePath: String?,
    val duration: Long?,
    val downloadDate: Long,
    val album: String? = null,
    val year: Int? = null,
    val sourceUrl: String? = null,
    val mimeType: String? = null,
    val explicitness: Boolean? = null,
    val loudnessDb: Double? = null,
    val perceptualLoudnessDb: Double? = null,
) {
    fun toPlaybackResource(song: SongItem?): PlaybackResource =
        PlaybackResource(
            videoId = songId,
            url = audioFilePath,
            headers = emptyMap(),
            loudnessDb = loudnessDb,
            perceptualLoudnessDb = perceptualLoudnessDb,
            expiresAt = null,
            clientName = "local",
            allowPlaybackTrackingFallback =
                song != null && song.id == songId && !song.isLocal && !song.isUploaded && !song.isPodcast && !songId.startsWith("local_"),
            profileId = "downloaded-audio",
        )

    fun toSongItem(): SongItem {
        val localThumb =
            thumbnailFilePath?.takeIf { DownloadManager.safePersistedFile(it)?.isFile == true }?.let {
                File(it)
                    .toURI()
                    .toString()
            }
        return SongItem(
            id = songId,
            title = title,
            artists = artist.takeIf(String::isNotBlank)?.let { listOf(ArtistTiny(id = null, name = it)) }.orEmpty(),
            thumbnail = localThumb ?: thumbnailUrl ?: "https://i.ytimg.com/vi/$songId/hqdefault.jpg",
            duration = duration,
            album = album?.let { AlbumTiny(id = null, name = it) },
            year = year,
            isExplicit = explicitness == true,
            explicitness = explicitness,
        )
    }
}

internal data class HttpByteRange(
    val start: Long,
    val end: Long,
    val total: Long,
)

internal fun parseHttpByteRange(value: String?): HttpByteRange? {
    val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(value ?: return null) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    return HttpByteRange(start, end, total).takeIf { start <= end && end < total }
}

object DownloadManager {
    private val database: MusicDatabase? by lazy { KoinPlatform.getKoinOrNull()?.getOrNull<MusicDatabase>() }
    private const val PROGRESS_STEP_BYTES = 256L * 1024
    private const val MAX_AUDIO_BYTES = 512L * 1024 * 1024
    private const val MAX_THUMBNAIL_BYTES = 16L * 1024 * 1024
    private const val MIN_AUDIO_BYTES = 1024L
    private const val MIN_THUMBNAIL_BYTES = 512L
    private val RETRYABLE_STREAM_FAILURE_STATUS_CODES =
        setOf(HttpStatusCode.Forbidden.value, HttpStatusCode.Gone.value)

    private val client =
        HttpClient(sharedHttpEngine) {
            install(HttpFailureLog) { transportHandledByEngine = sharedHttpTransportHandlesFailures }
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 10 * 60_000
                socketTimeoutMillis = 30_000
            }
            expectSuccess = false
        }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadSemaphore = Semaphore(6)
    private val transcodeSemaphore = Semaphore(2)
    private val enrichmentSemaphore = Semaphore(6)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pendingSongs = mutableMapOf<String, SongItem>()
    private val enrichmentJobs = ConcurrentHashMap<String, Job>()
    private val lyricsEnricher by lazy {
        DownloadLyricsEnricher(
            scope,
            cachedIds = { ids ->
                database
                    ?.dao()
                    ?.cachedLyricsIds(ids)
                    ?.toSet()
                    .orEmpty()
            },
            fetch = ::cacheDownloadLyrics,
            isDownloaded = ::isDownloaded,
        )
    }
    private val metadataLock = Any()
    private val removedDownloadIds = mutableSetOf<String>()

    // Songs the user stopped or dismissed; tracked playlists must not queue them again after a restart.
    private val stoppedDownloadIds: MutableSet<String> by lazy {
        runCatching { Json.decodeFromString<Set<String>>(Settings.shared.desktopStoppedDownloadIdsJson) }
            .getOrDefault(emptySet())
            .toMutableSet()
    }

    private fun saveStoppedDownloadIds() {
        Settings.shared.desktopStoppedDownloadIdsJson = Json.encodeToString<Set<String>>(stoppedDownloadIds)
    }

    private val metadataStore by lazy { DownloadMetadataStore(downloadMetadataFile) }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()
    private val _downloadedFiles = MutableStateFlow<List<DownloadedFile>>(emptyList())
    val downloadedFiles: StateFlow<List<DownloadedFile>> = _downloadedFiles.asStateFlow()

    private val _sessionCompletedCount = MutableStateFlow(0)
    val sessionCompletedCount: StateFlow<Int> = _sessionCompletedCount.asStateFlow()
    private val _downloadTitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloadTitles: StateFlow<Map<String, String>> = _downloadTitles.asStateFlow()

    fun getDefaultDownloadDir(): File = File(XdgUtils.getXdgDataHome(), "metrolist/downloads").also { it.mkdirs() }

    fun getDownloadDir(): File =
        Settings.shared.customDownloadPath
            .takeIf(String::isNotBlank)
            ?.let(::File) ?: getDefaultDownloadDir()

    private val directories by lazy {
        DownloadDirectories(getDefaultDownloadDir(), File(downloadMetadataFile.parentFile, "download-directories.json"))
    }

    fun changeDownloadDirectory(directory: File?) {
        synchronized(metadataLock) {
            directories.rememberChange(getDownloadDir(), directory ?: getDefaultDownloadDir())
            Settings.shared.customDownloadPath = directory?.canonicalPath.orEmpty()
        }
    }

    private val downloadBaseDir: File
        get() = getDownloadDir()

    private val downloadMetadataFile: File by lazy {
        val dir = File(XdgUtils.getXdgStateHome(), "metrolist").also { check(it.mkdirs() || it.isDirectory) }
        File(dir, "downloads.json")
    }

    init {
        val loaded =
            runCatching { synchronized(metadataLock) { metadataStore.load() } }.getOrElse {
                AppLogger.error("DownloadManager", "Download catalog is unavailable; metadata changes are blocked until it can be read", it)
                emptyList()
            }
        _downloadedFiles.value = loaded.filter { isValidSongId(it.songId) }
        _downloadStates.value =
            loaded
                .filter { entry ->
                    isValidSongId(entry.songId) &&
                        safePersistedFile(entry.audioFilePath)?.let { it.isFile && it.length() >= MIN_AUDIO_BYTES } == true
                }.associate { it.songId to DownloadState.DOWNLOADED }
        scope.launch {
            database?.let {
                restoreDownloadedSongs(it, loaded.filter { entry -> isDownloaded(entry.songId) })
                DownloadedPlaylists.observe(scope, it, ::downloadMissing)
                DownloadedPlaylists.sync(it, ::downloadMissing)
            }
            lyricsEnricher.restore(_downloadedFiles.value.filter { isDownloaded(it.songId) }.map { it.toSongItem() })
        }
    }

    internal fun isValidSongId(songId: String): Boolean =
        songId.isNotBlank() && songId.length <= 128 && songId.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun fileInDownloadDir(
        name: String,
        isImage: Boolean = false,
    ): File {
        val base = (if (isImage) getDefaultDownloadDir() else downloadBaseDir).canonicalFile
        val file = File(base, name).canonicalFile
        require(file.parentFile == base) { "Invalid download path" }
        return file
    }

    internal fun buildAudioBaseName(
        title: String,
        artist: String,
        songId: String,
    ): String {
        val rawTitle = title.takeIf(String::isNotBlank) ?: songId
        val rawName = artist.takeIf(String::isNotBlank)?.let { "$rawTitle by $it" } ?: rawTitle
        val sanitized =
            rawName
                .replace(Regex("""[\u0000-\u001F\u007F<>:"/\\|?*]"""), "_")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trimEnd('.', ' ')
                .ifBlank { songId }
                .let { if (WINDOWS_RESERVED_FILE_NAME.matches(it)) "_$it" else it }
        return truncateUtf8(sanitized, MAX_AUDIO_BASE_NAME_BYTES)
    }

    private fun reserveM4aDestination(song: SongItem): File {
        val artist = song.artists.joinToString(", ") { it.name }
        val baseName = buildAudioBaseName(song.title, artist, song.id)
        val idSuffix = song.id.take(8)
        repeat(MAX_FILE_NAME_ATTEMPTS) { attempt ->
            val suffix =
                when (attempt) {
                    0 -> ""
                    1 -> " [$idSuffix]"
                    else -> " [$idSuffix-$attempt]"
                }
            val candidate = fileInDownloadDir("$baseName$suffix.m4a")
            try {
                Files.createFile(candidate.toPath())
                return candidate
            } catch (_: FileAlreadyExistsException) {
            }
        }
        throw IOException("Unable to reserve a unique download filename")
    }

    private fun truncateUtf8(
        value: String,
        maxBytes: Int,
    ): String {
        val result = StringBuilder()
        var byteCount = 0
        val codePoints = value.codePoints().iterator()
        while (codePoints.hasNext()) {
            val part = String(Character.toChars(codePoints.nextInt()))
            val partBytes = part.toByteArray(Charsets.UTF_8).size
            if (byteCount + partBytes > maxBytes) break
            result.append(part)
            byteCount += partBytes
        }
        return result.toString().trimEnd('.', ' ')
    }

    fun safePersistedFile(path: String): File? = directories.safeFile(path, getDownloadDir())

    private suspend fun cacheDownloadLyrics(song: SongItem) {
        try {
            val lyricsRepo = KoinPlatform.getKoinOrNull()?.getOrNull<LyricsRepository>() ?: return
            val preferred =
                LyricsProvider.preferredProvidersForFetch(
                    LyricsProvider.parseUserOrderFromStoredCsv(Settings.shared.lyricsProviderOrder),
                    LyricsProvider.parseEnabledFromStoredCsv(Settings.shared.lyricsEnabledProviders),
                )
            lyricsRepo.prefetchDownloadLyrics(song, preferred)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.error("DownloadManager", "Lyrics remain pending for ${song.id}", e)
        }
    }

    private fun mimeToExtension(
        mime: String?,
        isImage: Boolean,
    ): String =
        when (mime?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/webm" -> ".webm"
            "audio/mpeg", "audio/mp3" -> ".mp3"
            "audio/mp4", "audio/x-m4a", "audio/aac", "audio/mp4a-latm" -> ".m4a"
            "audio/ogg", "audio/opus" -> ".ogg"
            "audio/flac" -> ".flac"
            "video/mp4" -> ".mp4"
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/avif" -> ".avif"
            else -> if (isImage) ".jpg" else ".audio"
        }

    internal fun promoteTempFile(
        temp: File,
        destination: File,
        minimumBytes: Long,
    ): Boolean {
        if (!temp.isFile || temp.length() < minimumBytes) return false
        return try {
            try {
                Files.move(
                    temp.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            destination.isFile && destination.length() >= minimumBytes && !temp.exists()
        } catch (_: IOException) {
            false
        }
    }

    private const val RANGE_CHUNK_BYTES = 1024L * 1024L

    private suspend fun downloadUrl(
        url: String,
        baseName: String,
        isImage: Boolean,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((written: Long, total: Long?) -> Unit)? = null,
        expectedFailureStatusCodes: Set<Int> = emptySet(),
    ): String? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            if (!isImage) {
                val parallelResult =
                    runCatching {
                        downloadUrlParallel(
                            url,
                            baseName,
                            headers,
                            onProgress,
                            expectedFailureStatusCodes + RETRYABLE_STREAM_FAILURE_STATUS_CODES,
                        )
                    }.getOrNull()
                if (parallelResult != null) return@withContext parallelResult
            }
            downloadUrlSingle(url, baseName, isImage, headers, onProgress, expectedFailureStatusCodes)
        }

    private suspend fun downloadUrlParallel(
        url: String,
        baseName: String,
        headers: Map<String, String>,
        onProgress: ((written: Long, total: Long?) -> Unit)?,
        expectedFailureStatusCodes: Set<Int>,
    ): String? =
        coroutineScope {
            val (headStatus, headHeaders) =
                client
                    .prepareGet(url) {
                        attributes.put(ExpectedHttpFailureStatusCodes, expectedFailureStatusCodes + (400..599))
                        headers.forEach { (key, value) -> this.headers.append(key, value) }
                        this.headers.append("Range", "bytes=0-0")
                    }.execute { it.status to it.headers }
            if (headStatus.value != 206) {
                if (headStatus.value == 403 || headStatus.value == 410) {
                    throw IOException("HTTP ${headStatus.value}: Stream URL expired")
                }
                return@coroutineScope null
            }
            val initialRange = parseHttpByteRange(headHeaders["Content-Range"]) ?: return@coroutineScope null
            if (initialRange.start != 0L || initialRange.end != 0L) return@coroutineScope null
            val totalBytes = initialRange.total
            if (totalBytes <= 0L || totalBytes > MAX_AUDIO_BYTES) return@coroutineScope null
            if (downloadBaseDir.usableSpace <= totalBytes + 1024 * 1024L) return@coroutineScope null

            val destination = fileInDownloadDir(baseName + mimeToExtension(headHeaders["Content-Type"], false))
            val temp = fileInDownloadDir(destination.name + ".${System.nanoTime()}.tmp")

            try {
                RandomAccessFile(temp, "rw").use { it.setLength(totalBytes) }
                val numChunks = ((totalBytes + RANGE_CHUNK_BYTES - 1) / RANGE_CHUNK_BYTES).toInt()
                val writtenBytes = AtomicLong(0L)
                var lastReported = 0L

                val chunkSem = Semaphore(4)
                val jobs =
                    (0 until numChunks).map { index ->
                        val start = index * RANGE_CHUNK_BYTES
                        val end = (start + RANGE_CHUNK_BYTES - 1).coerceAtMost(totalBytes - 1)
                        async(Dispatchers.IO) {
                            chunkSem.withPermit {
                                client
                                    .prepareGet(url) {
                                        if (expectedFailureStatusCodes.isNotEmpty()) {
                                            attributes.put(ExpectedHttpFailureStatusCodes, expectedFailureStatusCodes)
                                        }
                                        headers.forEach { (key, value) -> this.headers.append(key, value) }
                                        this.headers.append("Range", "bytes=$start-$end")
                                    }.execute { response ->
                                        if (response.status.value != 206) throw IOException("Chunk HTTP ${response.status.value}")
                                        val range = parseHttpByteRange(response.headers["Content-Range"])
                                        if (range == null || range.start != start || range.end != end || range.total != totalBytes) {
                                            throw IOException("Invalid chunk Content-Range")
                                        }
                                        val expectedBytes = (end - start + 1).toInt()
                                        val bytes =
                                            response
                                                .bodyAsChannel()
                                                .toInputStream()
                                                .use { it.readNBytes(expectedBytes + 1) }
                                        if (bytes.size != expectedBytes) throw IOException("Invalid chunk size")
                                        RandomAccessFile(temp, "rw").use { raf ->
                                            raf.seek(start)
                                            raf.write(bytes)
                                        }
                                        val newWritten = writtenBytes.addAndGet(bytes.size.toLong())
                                        if (newWritten - lastReported >= PROGRESS_STEP_BYTES) {
                                            lastReported = newWritten
                                            onProgress?.invoke(newWritten, totalBytes)
                                        }
                                    }
                            }
                        }
                    }
                jobs.awaitAll()
                onProgress?.invoke(totalBytes, totalBytes)

                if (!promoteTempFile(temp, destination, MIN_AUDIO_BYTES)) return@coroutineScope null
                destination.absolutePath
            } finally {
                temp.delete()
            }
        }

    internal suspend fun downloadUrlSingle(
        url: String,
        baseName: String,
        isImage: Boolean,
        headers: Map<String, String>,
        onProgress: ((written: Long, total: Long?) -> Unit)?,
        expectedFailureStatusCodes: Set<Int> = emptySet(),
    ): String? =
        withContext(Dispatchers.IO) {
            var temp: File? = null
            try {
                client
                    .prepareGet(url) {
                        if (expectedFailureStatusCodes.isNotEmpty()) {
                            attributes.put(ExpectedHttpFailureStatusCodes, expectedFailureStatusCodes)
                        }
                        headers.forEach { (key, value) -> this.headers.append(key, value) }
                    }.execute { response ->
                        if (!response.status.isSuccess()) {
                            if (response.status.value == 403 || response.status.value == 410) {
                                throw IOException("HTTP ${response.status.value}: Stream URL expired")
                            }
                            return@execute null
                        }
                        val limit = if (isImage) MAX_THUMBNAIL_BYTES else MAX_AUDIO_BYTES
                        val totalBytes = response.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 0 }
                        totalBytes?.let { if (it > limit) return@execute null }
                        val destination =
                            fileInDownloadDir(
                                baseName + mimeToExtension(response.headers["Content-Type"], isImage),
                                isImage = isImage,
                            )
                        val pendingFile =
                            fileInDownloadDir(destination.name + ".${System.nanoTime()}.tmp", isImage = isImage)
                        temp = pendingFile
                        val targetBaseDir = if (isImage) getDefaultDownloadDir() else downloadBaseDir
                        if (targetBaseDir.usableSpace <= (totalBytes ?: MIN_AUDIO_BYTES) + 1024 * 1024) {
                            return@execute null
                        }
                        var written = 0L
                        val bufferSize = if (isImage) DEFAULT_BUFFER_SIZE else 64 * 1024
                        pendingFile.outputStream().use { output ->
                            response.bodyAsChannel().toInputStream().use { input ->
                                val buffer = ByteArray(bufferSize)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    written += count
                                    if (written > limit || targetBaseDir.usableSpace < count + 1024 * 1024L) {
                                        throw IOException("Download limit or disk space exceeded")
                                    }
                                    output.write(buffer, 0, count)
                                    onProgress?.invoke(written, totalBytes)
                                }
                            }
                            output.flush()
                        }
                        val minimum = if (isImage) MIN_THUMBNAIL_BYTES else MIN_AUDIO_BYTES
                        if (written < minimum || (totalBytes != null && written != totalBytes)) return@execute null
                        if (!promoteTempFile(pendingFile, destination, minimum)) return@execute null
                        destination.absolutePath
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[DownloadManager] Download attempt failed (${e::class.simpleName})")
                throw e
            } finally {
                temp?.delete()
            }
        }

    private fun updateState(
        songId: String,
        state: DownloadState,
    ) = _downloadStates.update { it + (songId to state) }

    private fun updateProgress(
        songId: String,
        progress: Float?,
    ) = _downloadProgress.update {
        if (progress == null) it - songId else it + (songId to progress.coerceIn(0f, 1f))
    }

    private fun mutateMetadata(block: (MutableList<DownloadedFile>) -> Unit) =
        synchronized(metadataLock) {
            _downloadedFiles.value = metadataStore.mutate(block).filter { isValidSongId(it.songId) }
        }

    fun isDownloaded(songId: String): Boolean = _downloadStates.value[songId] == DownloadState.DOWNLOADED

    private fun getDownloadMetadata(songId: String): DownloadedFile? = _downloadedFiles.value.find { it.songId == songId }

    fun getLocalAudioPath(songId: String): String? =
        getDownloadMetadata(songId)?.let { metadata ->
            safePersistedFile(metadata.audioFilePath)?.takeIf { it.isFile && it.length() >= MIN_AUDIO_BYTES }?.absolutePath
        }

    suspend fun getLocalPlaybackResource(
        songId: String,
        song: SongItem?,
    ): PlaybackResource? {
        val path = getLocalAudioPath(songId) ?: return null
        return getDownloadMetadata(songId)?.toPlaybackResource(storedAudioReportingSong(song, database))?.copy(url = path)
    }

    private fun resolveAudioQuality(): AudioQuality =
        try {
            AudioQuality.valueOf(Settings.shared.audioQuality)
        } catch (_: Exception) {
            AudioQuality.AUTO
        }

    private suspend fun resolvePlaybackResource(
        song: SongItem,
        refresh: Boolean,
    ): PlaybackResource? {
        val resolver = GlobalYouTubeRepository.streamResolver ?: return null
        val hints =
            song
                .toContentHints()
                .copy(wantVideo = false, playbackClientOverrideId = null, sabrFirst = false)
                .withStreamCapabilities(allowHls = false, allowSabr = true, allowBoundedRange = true)
        return resolver.resolveRemote(song.id, hints, resolveAudioQuality(), sourceSong = song, refresh = refresh)
    }

    fun downloadPlaylist(
        playlistId: String,
        songs: List<SongItem>,
    ) {
        synchronized(metadataLock) { DownloadedPlaylists.track(playlistId) }
        downloadAll(songs)
    }

    fun removePlaylistDownloads(
        playlistId: String,
        songs: List<SongItem>,
    ) {
        synchronized(metadataLock) { DownloadedPlaylists.stop(playlistId) }
        deleteDownloads(songs.map(SongItem::id))
    }

    private fun downloadMissing(
        playlistId: String,
        songs: List<SongItem>,
    ) {
        synchronized(metadataLock) {
            if (!DownloadedPlaylists.contains(playlistId)) return
            songs.filterNot { it.isLocal || it.id in removedDownloadIds || it.id in stoppedDownloadIds }.forEach(::download)
        }
    }

    fun downloadAll(songs: List<SongItem>) {
        val valid = songs.filter { !it.isLocal && isValidSongId(it.id) && !isDownloaded(it.id) }
        if (valid.isEmpty()) return
        valid.forEach { download(it) }
    }

    fun download(song: SongItem) {
        if (song.isUnavailable || song.isLocal || !isValidSongId(song.id) || isDownloaded(song.id)) return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                var audioPath: String? = null
                var thumbnailPath: String? = null
                var failed = false
                try {
                    synchronized(metadataLock) { _downloadedFiles.value = metadataStore.load().filter { isValidSongId(it.songId) } }
                    downloadSemaphore.withPermit {
                        updateState(song.id, DownloadState.DOWNLOADING)
                        updateProgress(song.id, 0f)

                        var downloadedAudioPath: String? = null
                        var downloadedResource: PlaybackResource? = null
                        val maxRetries = 3
                        var attempt = 0

                        while (attempt < maxRetries && downloadedAudioPath == null) {
                            attempt++
                            try {
                                val resource =
                                    resolvePlaybackResource(song, refresh = attempt > 1)
                                        ?: throw IOException("Failed to resolve stream URL")
                                downloadedResource = resource
                                var lastProgressBytes = 0L
                                val onProgress: (Long, Long?) -> Unit = { written, total ->
                                    if (written - lastProgressBytes >= PROGRESS_STEP_BYTES || written == total) {
                                        lastProgressBytes = written
                                        if (total != null && total > 0) {
                                            updateProgress(song.id, (written.toFloat() / total).coerceIn(0f, 1f) * 0.7f)
                                        }
                                    }
                                }
                                val cachedSource = fileInDownloadDir("${song.id}.source.${System.nanoTime()}.tmp")
                                downloadedAudioPath =
                                    if (SongCacheManager.copyCachedAudio(resource, cachedSource)) {
                                        cachedSource.absolutePath
                                    } else if (resource.isSabr) {
                                        val source = fileInDownloadDir("${song.id}.source.${System.nanoTime()}.tmp")
                                        downloadSabrAudio(resource, source, onProgress = onProgress)
                                        source.absolutePath
                                    } else {
                                        if (!resource.isDirectFile) throw IOException("Unsupported download transport")
                                        downloadUrl(
                                            resource.url,
                                            "${song.id}.source",
                                            false,
                                            resource.headers,
                                            onProgress,
                                            expectedFailureStatusCodes =
                                                RETRYABLE_STREAM_FAILURE_STATUS_CODES.takeIf { attempt < maxRetries }.orEmpty(),
                                        )
                                    }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (attempt >= maxRetries) {
                                    throw e
                                }
                                delay((1000L * attempt).milliseconds)
                            }
                        }

                        audioPath = downloadedAudioPath
                            ?: throw IOException("Failed to download audio after $maxRetries attempts")
                        val sourceFile =
                            safePersistedFile(audioPath) ?: throw IOException("Downloaded source file is unavailable")
                        val destination = reserveM4aDestination(song)
                        try {
                            transcodeSemaphore.withPermit {
                                AudioDownloadTranscoder.transcodeToM4a(sourceFile, destination, song) { progress ->
                                    coroutineContext.ensureActive()
                                    updateProgress(song.id, 0.7f + progress * 0.29f)
                                }
                            }
                            audioPath = destination.absolutePath
                            runCatching { Files.deleteIfExists(sourceFile.toPath()) }
                        } catch (e: Throwable) {
                            destination.delete()
                            throw e
                        }

                        database?.let { db -> db.withDatabaseTransaction { db.dao().upsertHistorySong(song) } }
                        val entry =
                            DownloadedFile(
                                songId = song.id,
                                title = song.title,
                                artist = song.artists.joinToString(", ") { it.name },
                                thumbnailUrl = song.thumbnail,
                                audioFilePath = audioPath,
                                thumbnailFilePath = null,
                                duration = song.duration,
                                downloadDate = System.currentTimeMillis(),
                                album = song.album?.name,
                                year = song.year,
                                sourceUrl = "https://music.youtube.com/watch?v=${song.id}",
                                mimeType = "audio/mp4",
                                explicitness = song.artworkExplicitness,
                                loudnessDb = downloadedResource?.loudnessDb,
                                perceptualLoudnessDb = downloadedResource?.perceptualLoudnessDb,
                            )
                        synchronized(metadataLock) {
                            if (activeJobs[song.id] !== coroutineContext[Job]) throw CancellationException("Download removed")
                            mutateMetadata { files ->
                                files.removeAll { it.songId == entry.songId }
                                files.add(entry)
                            }
                            updateProgress(song.id, 1f)
                        }

                        val targetSongId = song.id
                        val targetSongThumbnail = song.thumbnail

                        val enrichmentJob =
                            scope.launch(start = CoroutineStart.LAZY) {
                                var downloadedThumbPath: String? = null
                                try {
                                    enrichmentSemaphore.withPermit {
                                        downloadedThumbPath =
                                            targetSongThumbnail?.takeIf { it.isNotBlank() }?.let { url ->
                                                try {
                                                    downloadUrl(url, "$targetSongId-thumb", true)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    null
                                                }
                                            }
                                    }
                                    coroutineContext.ensureActive()
                                    if (downloadedThumbPath != null) {
                                        var saved = false
                                        synchronized(metadataLock) {
                                            if (enrichmentJobs[targetSongId] === coroutineContext[Job]) {
                                                mutateMetadata { files ->
                                                    val index = files.indexOfFirst { it.songId == targetSongId }
                                                    if (index >= 0) {
                                                        files[index] =
                                                            files[index].copy(thumbnailFilePath = downloadedThumbPath)
                                                        saved = true
                                                    }
                                                }
                                            }
                                            if (saved) {
                                                downloadedThumbPath = null
                                            }
                                        }
                                    }
                                    if (enrichmentJobs[targetSongId] !== coroutineContext[Job]) return@launch
                                } finally {
                                    downloadedThumbPath?.let { safePersistedFile(it)?.delete() }
                                    enrichmentJobs.remove(targetSongId, coroutineContext[Job])
                                }
                            }
                        synchronized(metadataLock) {
                            if (activeJobs[song.id] === coroutineContext[Job]) {
                                enrichmentJobs.put(targetSongId, enrichmentJob)?.cancel()
                                enrichmentJob.start()
                            } else {
                                enrichmentJob.cancel()
                            }
                        }

                        updateState(song.id, DownloadState.DOWNLOADED)
                        _sessionCompletedCount.update { it + 1 }
                    }
                    lyricsEnricher.enqueue(song)
                } catch (e: CancellationException) {
                    audioPath?.let { safePersistedFile(it)?.delete() }
                    thumbnailPath?.let { safePersistedFile(it)?.delete() }
                    throw e
                } catch (e: Exception) {
                    audioPath?.let { safePersistedFile(it)?.delete() }
                    thumbnailPath?.let { safePersistedFile(it)?.delete() }
                    failed = true
                    println("[DownloadManager] Download failed for ${song.id}: ${e.message}")
                } finally {
                    synchronized(metadataLock) {
                        if (activeJobs.remove(song.id, coroutineContext[Job])) {
                            updateProgress(song.id, null)
                            if (failed) {
                                updateState(song.id, DownloadState.FAILED)
                            } else {
                                pendingSongs.remove(song.id)
                            }
                        }
                    }
                }
            }
        synchronized(metadataLock) {
            removedDownloadIds.remove(song.id)
            if (stoppedDownloadIds.remove(song.id)) saveStoppedDownloadIds()
            val startsSession = activeJobs.isEmpty() && _downloadStates.value.values.none { it == DownloadState.FAILED }
            if (isDownloaded(song.id) || activeJobs.putIfAbsent(song.id, job) != null) {
                job.cancel()
                return
            }
            if (startsSession) {
                _sessionCompletedCount.value = 0
            }
            pendingSongs[song.id] = song
            updateState(song.id, DownloadState.QUEUED)
            _downloadTitles.update { it + (song.id to song.title) }
            job.start()
        }
    }

    fun retryFailedDownload(songId: String) {
        synchronized(metadataLock) {
            if (_downloadStates.value[songId] == DownloadState.FAILED) {
                pendingSongs[songId]?.let(::download)
            }
        }
    }

    fun dismissFailedDownload(songId: String) {
        synchronized(metadataLock) {
            if (_downloadStates.value[songId] != DownloadState.FAILED) return
            pendingSongs.remove(songId)
            if (stoppedDownloadIds.add(songId)) saveStoppedDownloadIds()
            _downloadStates.update { it - songId }
            _downloadTitles.update { it - songId }
        }
    }

    fun deleteDownload(songId: String) = deleteDownloads(listOf(songId))

    fun deleteDownloads(songIds: Collection<String>) {
        scope.launch {
            try {
                deleteDownloadsAndAwait(songIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error("DownloadManager", "Failed to delete downloads", e)
            }
        }
    }

    internal suspend fun deleteDownloadsAndAwait(songIds: Collection<String>) {
        val ids = songIds.filter(::isValidSongId).toSet()
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) { deleteStoredDownloads(ids) }
    }

    private fun deleteStoredDownloads(ids: Set<String>) {
        synchronized(metadataLock) {
            _downloadedFiles.value = metadataStore.load().filter { isValidSongId(it.songId) }
            removedDownloadIds.addAll(ids)
            cancelDownloadJobs(ids, activeJobs)
            cancelDownloadJobs(ids, enrichmentJobs)
            lyricsEnricher.cancel(ids)
            val failed = _downloadedFiles.value.filter { it.songId in ids && !deleteStoredFiles(it) }.mapTo(mutableSetOf()) { it.songId }
            val removed = ids - failed
            removed.forEach(pendingSongs::remove)
            mutateMetadata { files -> files.removeAll { it.songId in removed } }
            _downloadStates.update { it - removed }
            _downloadProgress.update { it - ids }
            _downloadTitles.update { it - removed }
            check(failed.isEmpty()) { "Some downloads could not be deleted" }
        }
    }

    private fun deleteStoredFiles(entry: DownloadedFile): Boolean =
        try {
            val files =
                listOfNotNull(entry.thumbnailFilePath, entry.audioFilePath).map { path ->
                    safePersistedFile(path) ?: File(path).also { check(!it.exists()) { "Download path is unavailable" } }
                }
            deleteDownloadFiles(files)
            true
        } catch (e: Exception) {
            AppLogger.error("DownloadManager", "Failed to delete download ${entry.songId}", e)
            false
        }

    fun stopActiveDownloads() {
        synchronized(metadataLock) {
            if (stoppedDownloadIds.addAll(activeJobs.keys + pendingSongs.keys)) saveStoppedDownloadIds()
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
            pendingSongs.clear()
            _downloadStates.update { map ->
                map.filterValues { it == DownloadState.DOWNLOADED }
            }
            _downloadProgress.value = emptyMap()
            _downloadTitles.value = emptyMap()
            _sessionCompletedCount.value = 0
        }
    }

    fun clearAllDownloads() {
        synchronized(metadataLock) {
            val ids = _downloadStates.value.keys + _downloadedFiles.value.map { it.songId } + activeJobs.keys + enrichmentJobs.keys
            deleteStoredDownloads(ids)
            check(_downloadedFiles.value.isEmpty()) { "Some downloads could not be deleted" }
        }
    }

    fun release() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        enrichmentJobs.values.forEach { it.cancel() }
        enrichmentJobs.clear()
        scope.cancel()
        client.close()
    }

    private const val MAX_AUDIO_BASE_NAME_BYTES = 180
    private const val MAX_FILE_NAME_ATTEMPTS = 10_000
    private val WINDOWS_RESERVED_FILE_NAME = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")
}

internal fun deleteDownloadFiles(files: List<File>) {
    files.forEach { Files.deleteIfExists(it.toPath()) }
}

internal suspend fun LyricsRepository.prefetchDownloadLyrics(
    song: SongItem,
    preferredProviders: List<LyricsProvider>,
) = getLyrics(
    videoId = song.id,
    title = song.title,
    artist = song.artists.joinToString(", ") { it.name },
    duration = song.duration?.times(1000L),
    album = song.album?.name,
    preferredProviders = preferredProviders,
)
