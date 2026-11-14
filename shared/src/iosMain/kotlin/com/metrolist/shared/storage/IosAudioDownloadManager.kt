package com.metrolist.shared.storage

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.playback.canReuseCachedAudio
import com.metrolist.shared.playback.hasSameStoredAudio
import com.metrolist.shared.playback.iosPlaybackAudioQuality
import com.metrolist.shared.playback.resolveIosAudioResource
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.toCachedFormatEntity
import com.metrolist.shared.youtube.playback.withCachedFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForUpdatingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToFileOffset
import kotlin.time.Clock

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAudioDownloadManager(
    private val database: MusicDatabase,
    private val scope: CoroutineScope,
) {
    private val fileManager = NSFileManager.defaultManager
    private val managerJob = SupervisorJob(scope.coroutineContext[Job])
    private val managerScope = CoroutineScope(scope.coroutineContext + managerJob)
    private val queueMutex = Mutex()

    internal val storageMutex = Mutex()
    private val songStorageLocks = List(32) { Mutex() }
    private val activeTemporaryPaths = mutableSetOf<String>()
    private val storageUsageRefreshes = Channel<Unit>(Channel.CONFLATED)
    private val mutableStorageUsage = MutableStateFlow<IosStorageUsage?>(null)
    val storageUsageUpdates = mutableStorageUsage.asStateFlow()
    private var cacheGeneration = 0L
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val httpClient =
        HttpClient(Darwin) {
            install(HttpFailureLog)
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
    internal val playbackCache = IosPlaybackCache(httpClient, managerScope, onFilesChanged = ::invalidateStorageUsage)
    private val activeTransfers = mutableMapOf<String, Deferred<Unit>>()
    private val ready = CompletableDeferred<Unit>()
    private var processor: Job? = null
    private var started = false
    private lateinit var downloadQueue: IosDownloadQueue
    private var queuePersistenceJob: Job? = null
    private var enqueueGeneration = 0L

    private val downloadsDirectory: String by lazy {
        val support =
            NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: error("Application Support directory unavailable")
        "$support/metrolist/downloads"
    }
    private val cacheDirectory: String by lazy {
        val caches =
            NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: error("Caches directory unavailable")
        "$caches/metrolist/audio"
    }

    init {
        start()
    }

    fun start() {
        if (!managerJob.isActive || started) return
        started = true
        managerScope.launch {
            try {
                playbackCache.initialize()
                reconcileStorage()
                queueMutex.withLock {
                    downloadQueue = IosDownloadQueue(Settings.shared.downloadQueueJson)
                    persistQueueLocked()
                    ready.complete(Unit)
                    startProcessorLocked()
                }
                managerScope.launch {
                    for (request in storageUsageRefreshes) {
                        try {
                            storageUsage()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            PlaybackEventLog.error(
                                PlaybackEventKind.OTHER,
                                "IosAudioDownloadManager",
                                "Could not refresh audio storage usage",
                                error,
                            )
                            Logger.w("IosAudioDownloadManager", "Could not refresh audio storage usage", error)
                        }
                    }
                }
                managerScope.launch {
                    SongLikeState.newLikes.collect { song ->
                        if (Settings.shared.autoDownloadOnLike) download(song)
                    }
                }
                managerScope.launch {
                    snapshotFlow { Settings.shared.songCacheEnabled to Settings.shared.maxSongCacheSize }.collect { (enabled, limit) ->
                        if (!enabled || limit == 0) playbackCache.invalidate()
                    }
                }
                DownloadedPlaylists.observe(managerScope, database) { id, songs ->
                    enqueueAll(songs, preserveBlockedRequests = true, trackedPlaylistId = id)
                }
                syncTrackedPlaylists()
            } catch (error: CancellationException) {
                ready.completeExceptionally(error)
                throw error
            } catch (error: Exception) {
                PlaybackEventLog.error(
                    PlaybackEventKind.OTHER,
                    "IosAudioDownloadManager",
                    "Audio download manager initialization failed",
                    error,
                )
                ready.completeExceptionally(error)
                throw error
            }
        }
    }

    fun close() {
        ready.cancel()
        playbackCache.close()
        managerJob.cancel()
        httpClient.close()
    }

    suspend fun download(song: SongItem): Boolean = enqueueAll(listOf(song)) > 0

    suspend fun downloadAll(songs: List<SongItem>): Int = enqueueAll(songs)

    suspend fun downloadPlaylist(playlistId: String): Int {
        DownloadedPlaylists.track(playlistId)
        return enqueueAll(DownloadedPlaylists.songs(playlistId, database))
    }

    fun stopTrackingPlaylist(playlistId: String) = DownloadedPlaylists.stop(playlistId)

    suspend fun syncTrackedPlaylists() {
        DownloadedPlaylists.sync(database) { id, songs ->
            enqueueAll(songs, preserveBlockedRequests = true, trackedPlaylistId = id)
        }
    }

    suspend fun pauseAll() {
        ready.await()
        val active =
            queueMutex.withLock {
                enqueueGeneration++
                val queue =
                    readQueue().map { request ->
                        if (request.state == IosDownloadQueueState.QUEUED || request.state == IosDownloadQueueState.RUNNING) {
                            request.copy(state = IosDownloadQueueState.PAUSED, updatedAt = now(), downloadedBytes = 0L, totalBytes = null)
                        } else {
                            request
                        }
                    }
                writeQueue(queue)
                activeTransfers.values.toList().also { jobs -> jobs.forEach { it.cancel() } }
            }
        active.forEach { it.join() }
    }

    suspend fun resumeAll() {
        ready.await()
        queueMutex.withLock {
            val queue =
                readQueue().map { request ->
                    if (request.state == IosDownloadQueueState.PAUSED) {
                        request.copy(state = IosDownloadQueueState.QUEUED, updatedAt = now(), lastError = null)
                    } else {
                        request
                    }
                }
            writeQueue(queue)
            startProcessorLocked()
        }
    }

    suspend fun cancel(songId: String) {
        ready.await()
        queueMutex.withLock {
            updateRequestLocked(songId) {
                val state = cancelAudioDownload(it.state)
                it.copy(state = state, updatedAt = if (state == it.state) it.updatedAt else now())
            }
            activeTransfers[songId]?.cancel()
        }
    }

    suspend fun invalidateCachedAudio(
        songId: String,
        failedResourceUrl: String,
    ) {
        ready.await()
        withContext(Dispatchers.Default) {
            storageMutex.withLock {
                val entry = database.dao().cachedAudio(songId) ?: return@withLock
                val failedPath = NSURL.URLWithString(failedResourceUrl)?.path ?: return@withLock
                if (!matchesAudioFile(entry, failedPath)) return@withLock
                when (entry.kind) {
                    KIND_DOWNLOAD -> {
                        database.withDatabaseTransaction {
                            database.dao().upsert(quarantineAudioDownload(entry))
                            database.dao().updateDownloadedInfo(songId, false, null)
                        }
                    }

                    KIND_CACHE -> {
                        removeStoredAudio(entry)
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }

    suspend fun removeDownload(songId: String): Boolean {
        ready.await()
        val song = database.dao().getSongById(songId)?.toSongItem()
        val active =
            queueMutex.withLock {
                if (song != null) {
                    downloadQueue.cancelRemovedDownload(song, now())
                    persistQueueLocked()
                } else {
                    updateRequestLocked(songId) { it.copy(state = IosDownloadQueueState.CANCELLED, updatedAt = now()) }
                }
                activeTransfers[songId]?.also { it.cancel() }
            }
        active?.join()
        return withContext(Dispatchers.Default) {
            storageMutex.withLock {
                val entry = database.dao().cachedAudio(songId)?.takeIf { it.kind == KIND_DOWNLOAD } ?: return@withLock false
                removeStoredAudio(entry)
            }
        }
    }

    suspend fun clearDownloads() {
        ready.await()
        val active =
            queueMutex.withLock {
                enqueueGeneration++
                val queue =
                    readQueue().map { request ->
                        val cancelledState = cancelAudioDownload(request.state)
                        if (cancelledState == request.state) request else request.copy(state = cancelledState, updatedAt = now())
                    }
                writeQueue(queue)
                activeTransfers.values.toList().also { jobs -> jobs.forEach { it.cancel() } }
            }
        active.forEach { it.join() }
        withContext(Dispatchers.Default) {
            storageMutex.withLock {
                val entries = database.dao().cachedAudioByLastAccessed(KIND_DOWNLOAD)
                var removed = true
                entries.forEach { entry -> if (!removeStoredAudio(entry)) removed = false }
                val retainedPaths = database.dao().cachedAudioByLastAccessed(KIND_DOWNLOAD).mapTo(mutableSetOf()) { it.filePath }
                if (!removeDirectoryFiles(downloadsDirectory, retainedPaths)) removed = false
                check(removed) { "Could not remove all audio files" }
            }
        }
    }

    suspend fun clearCache() {
        ready.await()
        playbackCache.invalidate()
        withContext(Dispatchers.Default) {
            storageMutex.withLock {
                cacheGeneration++
                val entries = database.dao().cachedAudioByLastAccessed(KIND_CACHE)
                var removed = true
                entries.forEach { entry -> if (!removeStoredAudio(entry)) removed = false }
                val retainedPaths = database.dao().cachedAudioByLastAccessed(KIND_CACHE).mapTo(mutableSetOf()) { it.filePath }
                if (!removeDirectoryFiles(cacheDirectory, retainedPaths)) removed = false
                check(removed) { "Could not remove all audio files" }
            }
        }
    }

    suspend fun isDownloaded(songId: String): Boolean {
        ready.await()
        return withContext(Dispatchers.Default) {
            val entry = database.dao().cachedAudio(songId) ?: return@withContext false
            canUseDownloadedAudio(entry, isValidAudio(entry.filePath, KIND_DOWNLOAD))
        }
    }

    suspend fun getCachedPlaybackResource(song: SongItem): PlaybackResource? {
        ready.await()
        return withContext(Dispatchers.Default) {
            storageMutex.withLock {
                val entry = database.dao().cachedAudio(song.id) ?: return@withLock null
                if (isQuarantinedDownload(entry)) return@withLock null
                if (!isValidAudio(entry.filePath, entry.kind)) {
                    removeStoredAudio(entry)
                    return@withLock null
                }
                clearFragmentedMp4Durations(entry.filePath)
                val dao = database.dao()
                val size = audioSize(entry.filePath)
                val format = dao.format(song.id).first()
                dao.updateCachedAudioLastAccessed(song.id, now())
                PlaybackResource(
                    videoId = song.id,
                    url = requireNotNull(NSURL.fileURLWithPath(entry.filePath).absoluteString),
                    headers = emptyMap(),
                    loudnessDb = null,
                    expiresAt = null,
                    contentLengthBytes = size,
                    mimeType = entry.mimeType,
                    clientName = "local",
                    profileId = "cached-audio",
                    requireBoundedRange = false,
                    durationSeconds =
                        song.duration?.takeIf { it > 0L }
                            ?: dao
                                .getSongById(song.id)
                                ?.song
                                ?.duration
                                ?.takeIf { it > 0 }
                                ?.toLong(),
                    allowPlaybackTrackingFallback = !song.isLocal && !song.isUploaded && !song.isPodcast && !song.id.startsWith("local_"),
                ).withCachedFormat(format)
            }
        }
    }

    suspend fun enforceCacheLimit() {
        ready.await()
        evictCache()
    }

    suspend fun cacheSong(
        song: SongItem,
        resource: PlaybackResource? = null,
    ) {
        ready.await()
        if (!Settings.shared.songCacheEnabled || Settings.shared.maxSongCacheSize == 0 || song.id.isBlank()) return
        val alreadyStored =
            withContext(Dispatchers.Default) {
                database.dao().cachedAudio(song.id)?.let { entry ->
                    (entry.kind == KIND_DOWNLOAD && (isQuarantinedDownload(entry) || isValidAudio(entry.filePath, KIND_DOWNLOAD))) ||
                        (entry.kind == KIND_CACHE && isValidAudio(entry.filePath, KIND_CACHE))
                } == true
            }
        if (alreadyStored) return
        val directResource = resource ?: return
        val capture = playbackCache.acquireForPublication(directResource) ?: return
        try {
            saveAudio(
                song,
                directResource,
                KIND_CACHE,
                cacheMayPublish = { capture.mayPublish && Settings.shared.songCacheEnabled },
            ) { _, path ->
                capture.copyCompleted(path)
            }
            capture.mayPublish = false
            evictCache()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { playbackCache.release(capture) }
        }
    }

    suspend fun storageUsage(): IosStorageUsage {
        ready.await()
        return withContext(Dispatchers.Default) {
            storageMutex.withLock { refreshStorageUsageLocked() }
        }
    }

    private fun refreshStorageUsageLocked(): IosStorageUsage =
        IosStorageUsage(
            cacheBytes = totalAudioSize(listOf(directorySize(cacheDirectory), directorySize(playbackCache.directory))),
            downloadBytes = directorySize(downloadsDirectory),
            freeBytes = availableSpace(downloadsDirectory),
        ).also { mutableStorageUsage.value = it }

    private fun invalidateStorageUsage() {
        if (mutableStorageUsage.value != null) storageUsageRefreshes.trySend(Unit)
    }

    suspend fun reconcileStorage(): Unit =
        withContext(Dispatchers.Default) {
            storageMutex.withLock {
                ensureDirectories()
                listOf(downloadsDirectory, cacheDirectory).forEach(::removePartialFiles)
                listOf(KIND_DOWNLOAD, KIND_CACHE).forEach { kind ->
                    val directory = if (kind == KIND_DOWNLOAD) downloadsDirectory else cacheDirectory
                    database.dao().cachedAudioByLastAccessed(kind).forEach { entry ->
                        rebasedAudioPath(entry.filePath, directory)
                            ?.takeIf { fileManager.fileExistsAtPath(it) }
                            ?.let { database.dao().upsert(entry.copy(filePath = it)) }
                    }
                    val entries = database.dao().cachedAudioByLastAccessed(kind)
                    entries.forEach { entry ->
                        if (!shouldRetainAudioFile(entry, isValidAudio(entry.filePath, kind))) {
                            removeStoredAudio(entry)
                        }
                    }
                    val knownPaths =
                        database.dao().cachedAudioByLastAccessed(kind).mapTo(mutableSetOf()) { it.filePath }
                    fileManager
                        .contentsOfDirectoryAtPath(directory, error = null)
                        ?.filterIsInstance<String>()
                        ?.map { "$directory/$it" }
                        ?.filterNot { it in knownPaths || it in activeTemporaryPaths }
                        ?.forEach(::deleteManagedFile)
                }
                evictCacheLocked()
                refreshStorageUsageLocked()
            }
        }

    private suspend fun enqueueAll(
        songs: List<SongItem>,
        preserveBlockedRequests: Boolean = false,
        trackedPlaylistId: String? = null,
    ): Int {
        ready.await()
        val candidates = songs.filter { !it.isLocal && it.id.isNotBlank() }.distinctBy(SongItem::id)
        val (generation, observed) = queueMutex.withLock { enqueueGeneration to downloadQueue.snapshot() }
        val candidateIds = candidates.mapTo(mutableSetOf(), SongItem::id)
        val downloaded =
            withContext(Dispatchers.Default) {
                database
                    .dao()
                    .cachedAudioByLastAccessed(KIND_DOWNLOAD)
                    .filter { it.songId in candidateIds && canUseDownloadedAudio(it, isValidAudio(it.filePath, KIND_DOWNLOAD)) }
                    .mapTo(mutableSetOf()) { it.songId }
            }
        return queueMutex.withLock {
            if (generation != enqueueGeneration ||
                (trackedPlaylistId != null && !DownloadedPlaylists.contains(trackedPlaylistId))
            ) {
                return@withLock 0
            }
            val enqueued = downloadQueue.enqueue(candidates, downloaded, observed, preserveBlockedRequests, now())
            if (enqueued > 0) {
                persistQueueLocked()
                startProcessorLocked()
            }
            enqueued
        }
    }

    private fun startProcessorLocked() {
        if (managerJob.isActive && processor?.isActive != true) processor = managerScope.launch { processQueue() }
    }

    private suspend fun processQueue() {
        val thisJob = currentCoroutineContext()[Job]
        try {
            while (true) {
                val next =
                    queueMutex.withLock {
                        val request =
                            readQueue().filter { it.state == IosDownloadQueueState.QUEUED }.minByOrNull { it.enqueuedAt }
                                ?: return
                        updateRequestLocked(
                            request.song.id,
                            persistImmediately = false,
                        ) { it.copy(state = IosDownloadQueueState.RUNNING, updatedAt = now(), lastError = null) }
                        val transfer = managerScope.async(start = CoroutineStart.LAZY) { downloadWithRefresh(request.song) }
                        activeTransfers[request.song.id] = transfer
                        request to transfer
                    }
                try {
                    next.second.start()
                    next.second.await()
                    queueMutex.withLock {
                        updateRequestLocked(next.first.song.id, persistImmediately = false) {
                            if (it.state ==
                                IosDownloadQueueState.RUNNING
                            ) {
                                it.copy(state = IosDownloadQueueState.COMPLETED, updatedAt = now())
                            } else {
                                it
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    queueMutex.withLock {
                        updateRequestLocked(next.first.song.id, persistImmediately = false) {
                            if (it.state ==
                                IosDownloadQueueState.RUNNING
                            ) {
                                it.copy(state = IosDownloadQueueState.QUEUED, updatedAt = now())
                            } else {
                                it
                            }
                        }
                    }
                } catch (error: Exception) {
                    PlaybackEventLog.error(
                        PlaybackEventKind.OTHER,
                        "IosAudioDownloadManager",
                        "Download queue item failed for ${next.first.song.id}",
                        error,
                    )
                    queueMutex.withLock {
                        updateRequestLocked(next.first.song.id, persistImmediately = false) {
                            if (it.state == IosDownloadQueueState.RUNNING) {
                                it.copy(
                                    state = IosDownloadQueueState.FAILED,
                                    updatedAt = now(),
                                    attemptCount = it.attemptCount + 1,
                                    lastError = error.message,
                                )
                            } else {
                                it
                            }
                        }
                    }
                } finally {
                    queueMutex.withLock {
                        if (activeTransfers[next.first.song.id] == next.second) activeTransfers.remove(next.first.song.id)
                    }
                }
            }
        } finally {
            queueMutex.withLock {
                if (processor === thisJob) {
                    processor = null
                    if (readQueue().any { it.state == IosDownloadQueueState.QUEUED }) startProcessorLocked()
                }
            }
        }
    }

    private suspend fun downloadWithRefresh(song: SongItem) {
        var lastFailure: Exception? = null
        repeat(2) { attempt ->
            val resource =
                try {
                    resolvePlaybackResource(song, refresh = attempt > 0)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastFailure = error
                    return@repeat
                }
            try {
                saveAudio(song, resource, KIND_DOWNLOAD)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        throw lastFailure ?: IllegalStateException("No direct audio resource available")
    }

    private suspend fun resolvePlaybackResource(
        song: SongItem,
        refresh: Boolean,
    ): PlaybackResource {
        val resolver = GlobalYouTubeRepository.streamResolver ?: error("Audio stream resolver unavailable")
        val hints =
            song
                .toContentHints()
                .copy(wantVideo = false, playbackClientOverrideId = null, sabrFirst = false)
                .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true)
        val quality = iosPlaybackAudioQuality(Settings.shared.audioQuality)
        return resolveIosAudioResource(quality, refresh, forDownload = true) { requestedQuality, isRefresh, _ ->
            if (isRefresh) {
                resolver.refresh(song.id, hints, requestedQuality, sourceSong = song)
            } else {
                resolver.resolve(song.id, hints, requestedQuality, sourceSong = song)
            }
        }
    }

    internal suspend fun saveAudio(
        song: SongItem,
        resource: PlaybackResource,
        kind: String,
        cacheMayPublish: () -> Boolean = { true },
        metadataTransaction: suspend (suspend () -> Unit) -> Unit = { database.withDatabaseTransaction(it) },
        transfer: suspend (PlaybackResource, String) -> Pair<String?, Long> = ::streamToFile,
    ) = withContext(Dispatchers.Default) {
        songStorageLocks[(song.id.hashCode() and Int.MAX_VALUE) % songStorageLocks.size].withLock songLock@{
            val id = safeAudioFileId(song.id)
            val unique = NSUUID.UUID().UUIDString
            val directory = if (kind == KIND_DOWNLOAD) downloadsDirectory else cacheDirectory
            val temporaryPath = "$directory/.$id-$unique.part"
            val generation: Long
            val previous: CachedAudioEntity?
            var copiedCache: Pair<String?, Long>? = null
            storageMutex.withLock {
                require(resource.isDirectFile && !resource.isSabr) { "Resolved audio is not a downloadable file" }
                require(resource.url.startsWith("https://", ignoreCase = true)) { "Audio resource must use HTTPS" }
                ensureDirectories()
                previous = database.dao().cachedAudio(song.id)
                generation = cacheGeneration
                if (kind == KIND_CACHE && !cacheMayPublish()) return@songLock
                if (previous?.kind == kind && isValidAudio(previous.filePath, kind) &&
                    (kind != KIND_DOWNLOAD || canUseDownloadedAudio(previous, fileIsValid = true))
                ) {
                    return@songLock
                }
                if (kind == KIND_CACHE && previous != null &&
                    shouldKeepDownloadInsteadOfCache(previous, isValidAudio(previous.filePath, KIND_DOWNLOAD))
                ) {
                    return@songLock
                }

                if (kind == KIND_CACHE && resource.contentLengthBytes != null) {
                    val reservation = resource.contentLengthBytes
                    checkCacheBudget(reservation)
                    evictCacheLocked(reservation)
                }
                if (kind == KIND_DOWNLOAD && previous?.kind == KIND_CACHE) {
                    val format = database.dao().format(song.id).first()
                    if (canReuseCachedAudio(resource, format?.itag, format?.mimeType, format?.codecs, previous.fileSize) &&
                        format?.contentLength == previous.fileSize && isValidAudio(previous.filePath, KIND_CACHE) &&
                        audioSize(previous.filePath) == previous.fileSize
                    ) {
                        check(
                            availableSpace(directory) - STORAGE_RESERVE_BYTES >= previous.fileSize,
                        ) { "Insufficient space for audio download" }
                        if (!fileManager.copyItemAtPath(previous.filePath, toPath = temporaryPath, error = null)) {
                            deleteManagedFile(temporaryPath)
                            error("Could not copy cached audio")
                        }
                        copiedCache = previous.mimeType to previous.fileSize
                    }
                }
                activeTemporaryPaths.add(temporaryPath)
            }
            var finalPath: String? = null
            try {
                val (mimeType, size) = copiedCache ?: transfer(resource, temporaryPath)
                clearFragmentedMp4Durations(temporaryPath)
                currentCoroutineContext().ensureActive()
                storageMutex.withLock {
                    if (!hasSameStoredAudio(previous, database.dao().cachedAudio(song.id)) ||
                        (kind == KIND_CACHE && (cacheGeneration != generation || !cacheMayPublish()))
                    ) {
                        check(kind != KIND_DOWNLOAD) { "Audio storage changed during download" }
                        return@withLock
                    }
                    check(
                        isCompleteAudioDownload(size, resource.contentLengthBytes?.takeIf { it > 0L }) && audioSize(temporaryPath) == size,
                    ) {
                        "Stored audio is incomplete or has the wrong length"
                    }
                    val storedMimeType =
                        mimeType ?: resource.mimeType
                            ?.substringBefore(';')
                            ?.trim()
                            ?.lowercase()
                    require(isIosPlayableAudioMimeType(storedMimeType)) { "Resolved audio format is not supported by iOS playback" }
                    if (kind == KIND_CACHE) {
                        checkCacheBudget(size)
                        evictCacheLocked(size, completedTemporaryBytes = size)
                    }
                    val extension = audioExtension(storedMimeType)
                    val publishedPath = "$directory/$id-$unique$extension"
                    finalPath = publishedPath
                    check(
                        fileManager.moveItemAtPath(temporaryPath, toPath = publishedPath, error = null),
                    ) { "Could not publish audio file" }
                    if (kind == KIND_DOWNLOAD) excludeFromBackup(publishedPath)
                    val currentTime = now()
                    replaceAudioAfterCommit(previous?.filePath, {
                        metadataTransaction {
                            val dao = database.dao()
                            val storedSong = dao.getSongById(song.id)?.song
                            val durationSeconds =
                                song.duration?.takeIf { it > 0L }
                                    ?: resource.durationSeconds?.takeIf { it > 0L }
                            if (storedSong == null) {
                                dao.upsert(
                                    SongEntity(
                                        id = song.id,
                                        title = song.canonicalTitle ?: song.title,
                                        customTitle = song.customTitle,
                                        customArtist = song.customArtist,
                                        duration = durationSeconds?.toInt() ?: -1,
                                        thumbnailUrl = song.thumbnail,
                                        albumId = song.album?.id,
                                        albumName = song.album?.name,
                                        explicit = song.isExplicit,
                                        explicitness = song.artworkExplicitness,
                                        year = song.year,
                                        libraryAddToken = song.libraryAddToken,
                                        libraryRemoveToken = song.libraryRemoveToken,
                                        isLocal = song.isLocal,
                                        isUploaded = song.isUploaded,
                                        isVideo = song.isVideoSong,
                                        isEpisode = song.isPodcast,
                                        uploadEntityId = song.uploadEntityId,
                                    ),
                                )
                                (song.canonicalArtists ?: song.artists).forEachIndexed { index, artist ->
                                    val artistId = artist.id ?: ArtistEntity.generateArtistId()
                                    dao.upsert(
                                        dao.getArtistById(artistId)?.copy(
                                            name = artist.name,
                                            thumbnailUrl = artist.thumbnail ?: dao.getArtistById(artistId)?.thumbnailUrl,
                                        ) ?: ArtistEntity(
                                            id = artistId,
                                            name = artist.name,
                                            thumbnailUrl = artist.thumbnail,
                                            isLocal = artist.id == null,
                                        ),
                                    )
                                    dao.upsert(SongArtistMap(song.id, artistId, index))
                                }
                            } else if (storedSong.duration <= 0 && durationSeconds != null) {
                                dao.update(storedSong.copy(duration = durationSeconds.toInt()))
                            }
                            val format =
                                resource.toCachedFormatEntity(
                                    id = song.id,
                                    mimeType = storedMimeType,
                                    contentLengthBytes = resource.contentLengthBytes?.takeIf { it > 0L } ?: size,
                                )
                            if (format != null) {
                                dao.upsert(format)
                            } else {
                                dao.deleteFormat(song.id)
                            }
                            dao.upsert(
                                CachedAudioEntity(
                                    songId = song.id,
                                    title = song.title,
                                    artist =
                                        (song.canonicalArtists ?: song.artists)
                                            .joinToString(
                                                ", ",
                                            ) { it.name }
                                            .ifBlank { song.customArtist.orEmpty() },
                                    album = song.album?.name,
                                    thumbnailUrl = song.thumbnail,
                                    filePath = publishedPath,
                                    mimeType = storedMimeType,
                                    fileSize = size,
                                    cachedAt = currentTime,
                                    lastAccessedAt = currentTime,
                                    cacheLocation = if (kind == KIND_DOWNLOAD) IOS_DOWNLOAD_LOCATION else IOS_CACHE_LOCATION,
                                    kind = kind,
                                ),
                            )
                            if (kind == KIND_DOWNLOAD) {
                                dao.updateDownloadedInfo(song.id, true, Clock.System.now().toLocalDateTime(TimeZone.UTC))
                                dao.updateCachedInfo(song.id, false)
                            } else {
                                dao.updateCachedInfo(song.id, true)
                            }
                        }
                        finalPath = null
                        publishedPath to Unit
                    }, { deleteManagedFile(it) })
                }
            } catch (error: Exception) {
                deleteManagedFile(temporaryPath)
                finalPath?.let(::deleteManagedFile)
                throw error
            } finally {
                withContext(NonCancellable) {
                    storageMutex.withLock {
                        activeTemporaryPaths.remove(temporaryPath)
                        deleteManagedFile(temporaryPath)
                    }
                }
            }
        }
    }

    private suspend fun streamToFile(
        resource: PlaybackResource,
        path: String,
    ): Pair<String?, Long> {
        val availableBytes = availableSpace(path.substringBeforeLast('/'))
        check(fileManager.createFileAtPath(path, contents = null, attributes = null)) { "Could not create audio file" }
        val file = NSFileHandle.fileHandleForWritingAtPath(path) ?: error("Could not open audio file")
        try {
            return downloadAudioWithRanges(
                client = httpClient,
                resource = resource,
                maxBytes =
                    minOf(
                        (availableBytes - STORAGE_RESERVE_BYTES).coerceAtLeast(0L),
                        if (path.substringBeforeLast('/') == cacheDirectory) {
                            val configured = Settings.shared.maxSongCacheSize.toLong() * BYTES_PER_MEGABYTE
                            if (configured < 0L) MAXIMUM_CACHE_FILE_BYTES else minOf(configured, MAXIMUM_CACHE_FILE_BYTES)
                        } else {
                            Long.MAX_VALUE
                        },
                    ),
            ) { offset, bytes, length ->
                file.seekToFileOffset(offset.toULong())
                writeChunk(file, bytes, length)
            }
        } finally {
            file.closeFile()
        }
    }

    private fun clearFragmentedMp4Durations(path: String) {
        val file = NSFileHandle.fileHandleForUpdatingAtPath(path) ?: return
        try {
            val data = file.readDataUpToLength(FRAGMENTED_MP4_HEADER_BYTES.toULong(), error = null) ?: return
            val header = data.bytes?.readBytes(data.length.toInt()) ?: return
            if (!clearFragmentedMp4MovieDurations(header)) return
            file.seekToFileOffset(0u)
            writeChunk(file, header, header.size)
        } finally {
            file.closeFile()
        }
    }

    private suspend fun evictCache() = withContext(Dispatchers.Default) { storageMutex.withLock { evictCacheLocked() } }

    private fun checkCacheBudget(bytes: Long) {
        val configuredBytes = Settings.shared.maxSongCacheSize.toLong() * BYTES_PER_MEGABYTE
        check(configuredBytes < 0L || bytes <= configuredBytes) { "Audio exceeds cache budget" }
    }

    private suspend fun evictCacheLocked(
        reservedBytes: Long = 0L,
        completedTemporaryBytes: Long = 0L,
    ) {
        var used = totalAudioSize(database.dao().cachedAudioByLastAccessed(KIND_CACHE).map { audioSize(it.filePath) })
        val maxBytes =
            audioCacheBudget(
                Settings.shared.maxSongCacheSize.toLong() * BYTES_PER_MEGABYTE,
                used,
                availableSpace(cacheDirectory),
                STORAGE_RESERVE_BYTES,
                completedTemporaryBytes = completedTemporaryBytes,
            )
        for (entry in database.dao().cachedAudioByLastAccessed(KIND_CACHE)) {
            if (used + reservedBytes <= maxBytes) break
            val size = audioSize(entry.filePath)
            if (removeStoredAudio(entry)) used = (used - size).coerceAtLeast(0L)
        }
        check(reservedBytes == 0L || used + reservedBytes <= maxBytes) { "Insufficient space for audio cache" }
    }

    private fun updateRequestLocked(
        songId: String,
        persistImmediately: Boolean = true,
        transform: (IosDownloadQueueRequest) -> IosDownloadQueueRequest,
    ) {
        if (!downloadQueue.update(songId, transform)) return
        if (persistImmediately) {
            persistQueueLocked()
        } else if (queuePersistenceJob?.isActive != true) {
            queuePersistenceJob =
                managerScope.launch {
                    delay(500)
                    queueMutex.withLock {
                        Settings.shared.downloadQueueJson = downloadQueue.encode()
                        queuePersistenceJob = null
                    }
                }
        }
    }

    private fun readQueue(): List<IosDownloadQueueRequest> = downloadQueue.values()

    private fun writeQueue(queue: List<IosDownloadQueueRequest>) {
        downloadQueue.replace(queue)
        persistQueueLocked()
    }

    private fun persistQueueLocked() {
        queuePersistenceJob?.cancel()
        queuePersistenceJob = null
        Settings.shared.downloadQueueJson = downloadQueue.encode()
    }

    private fun ensureDirectories() {
        check(fileManager.createDirectoryAtPath(downloadsDirectory, withIntermediateDirectories = true, attributes = null, error = null)) {
            "Could not create downloads directory"
        }
        check(fileManager.createDirectoryAtPath(cacheDirectory, withIntermediateDirectories = true, attributes = null, error = null)) {
            "Could not create audio cache directory"
        }
        check(excludeFromBackup(downloadsDirectory)) { "Could not exclude downloads from iCloud backup" }
    }

    private fun excludeFromBackup(path: String): Boolean =
        NSURL.fileURLWithPath(path).setResourceValue(true, NSURLIsExcludedFromBackupKey, error = null)

    private fun availableSpace(path: String): Long {
        val value = fileManager.attributesOfFileSystemForPath(path, error = null)?.get(NSFileSystemFreeSize) as? NSNumber
        return requireNotNull(value?.longLongValue?.takeIf { it >= 0L }) { "Could not determine available storage" }
    }

    private fun isValidAudio(
        path: String,
        kind: String,
    ): Boolean =
        isManagedPath(path, if (kind == KIND_DOWNLOAD) downloadsDirectory else cacheDirectory) && audioSize(path) >= MINIMUM_AUDIO_BYTES

    private fun audioSize(path: String): Long =
        (fileManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue?.coerceAtLeast(0L)
            ?: 0L

    private fun directorySize(path: String): Long {
        val entries = fileManager.contentsOfDirectoryAtPath(path, error = null)?.filterIsInstance<String>().orEmpty()
        return totalAudioSize(entries.map { audioSize("$path/$it") })
    }

    private fun isManagedPath(
        path: String,
        directory: String,
    ): Boolean {
        if (!path.startsWith("$directory/")) return false
        val name = path.removePrefix("$directory/")
        return name.isNotBlank() && '/' !in name && name != "." && name != ".."
    }

    private suspend fun removeStoredAudio(entry: CachedAudioEntity): Boolean =
        removeAudioAfterFileDeletion(entry, ::deleteManagedFile) { removed ->
            database.withDatabaseTransaction {
                database.dao().deleteCachedAudio(removed.songId)
                if (removed.kind == KIND_DOWNLOAD) {
                    database.dao().updateDownloadedInfo(removed.songId, false, null)
                } else {
                    database.dao().updateCachedInfo(removed.songId, false)
                }
            }
        }

    private fun deleteManagedFile(path: String): Boolean {
        try {
            if (!fileManager.fileExistsAtPath(path)) return true
            val removed =
                (isManagedPath(path, downloadsDirectory) || isManagedPath(path, cacheDirectory)) &&
                    fileManager.removeItemAtPath(path, error = null)
            if (!removed) Logger.w("IosAudioDownloadManager", "Could not remove managed audio file")
            return removed
        } finally {
            invalidateStorageUsage()
        }
    }

    private fun removePartialFiles(directory: String) {
        fileManager
            .contentsOfDirectoryAtPath(directory, error = null)
            ?.filterIsInstance<String>()
            ?.filter { it.endsWith(".part") && "$directory/$it" !in activeTemporaryPaths }
            ?.forEach { deleteManagedFile("$directory/$it") }
    }

    private fun removeDirectoryFiles(
        directory: String,
        retainedPaths: Set<String>,
    ): Boolean {
        val names = fileManager.contentsOfDirectoryAtPath(directory, error = null)?.filterIsInstance<String>() ?: return false
        var removed = true
        names.forEach { name ->
            val path = "$directory/$name"
            if (path !in retainedPaths && path !in activeTemporaryPaths && !deleteManagedFile(path)) removed = false
        }
        return removed
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun audioExtension(mimeType: String?): String =
        when (mimeType?.substringBefore(';')?.lowercase()) {
            "audio/mp4", "audio/aac", "audio/x-m4a" -> ".m4a"
            "audio/mpeg", "audio/mp3" -> ".mp3"
            "audio/wav", "audio/x-wav" -> ".wav"
            else -> ".audio"
        }

    private fun writeChunk(
        file: NSFileHandle,
        bytes: ByteArray,
        length: Int,
    ) {
        val data = bytes.usePinned { NSData.create(it.addressOf(0), length.toULong()) }
        check(file.writeData(data, error = null)) { "Could not write audio file" }
    }

    companion object {
        private const val MAXIMUM_CACHE_FILE_BYTES = 512L * 1024 * 1024
        private const val STORAGE_RESERVE_BYTES = 16L * 1024L * 1024L
        private const val BYTES_PER_MEGABYTE = 1_048_576L
        private const val IOS_DOWNLOAD_LOCATION = "IOS_APPLICATION_SUPPORT"
        private const val IOS_CACHE_LOCATION = "IOS_CACHES"
    }
}

data class IosStorageUsage(
    val cacheBytes: Long,
    val downloadBytes: Long,
    val freeBytes: Long,
) {
    val totalBytes: Long get() = totalAudioSize(listOf(cacheBytes, downloadBytes))
}

private typealias IosDownloadQueueState = AudioDownloadState
