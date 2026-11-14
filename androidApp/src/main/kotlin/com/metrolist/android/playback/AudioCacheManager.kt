/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.playback

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.unknown_artist
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.network.sharedOkHttpClient
import com.metrolist.shared.playback.PlaybackPreCache
import com.metrolist.shared.playback.canReuseCachedAudio
import com.metrolist.shared.playback.downloadSabrAudio
import com.metrolist.shared.playback.ffmpeg.DirectPlaybackCache
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.audioCacheBudget
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.toCachedFormatEntity
import com.metrolist.shared.youtube.playback.withCachedFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jetbrains.compose.resources.getString
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal fun shouldSkipManuallyRemovedCache(
    wasManuallyRemoved: Boolean,
    removedAtPlaybackInstanceId: Long?,
    playbackInstanceId: Long,
    isCurrentSong: Boolean,
): Boolean = wasManuallyRemoved && (!isCurrentSong || removedAtPlaybackInstanceId == playbackInstanceId)

internal fun audioBelongsToTree(
    path: String,
    tree: String,
): Boolean =
    runCatching {
        val source = path.toUri()
        val target = tree.toUri()
        source.scheme == "content" && source.authority == target.authority &&
            DocumentsContract.getTreeDocumentId(source) == DocumentsContract.getTreeDocumentId(target)
    }.getOrDefault(false)

internal fun moveAudioToDirectory(
    source: File,
    directory: File,
    fileName: String,
): String {
    check(directory.mkdirs() || directory.isDirectory)
    val preferred = File(directory, fileName)
    val target =
        if (preferred.createNewFile()) {
            preferred
        } else {
            File.createTempFile(
                "${preferred.nameWithoutExtension}-".padEnd(3, '_'),
                ".${preferred.extension}",
                directory,
            )
        }
    try {
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        return target.absolutePath
    } catch (error: Exception) {
        target.delete()
        throw error
    }
}

internal fun downloadFileBaseName(song: SongItem): String {
    val artist = song.artists.joinToString(", ") { it.name }.trim()
    return listOf(artist, song.title.trim())
        .filter(String::isNotBlank)
        .joinToString(" - ")
        .replace(Regex("""[\\/:*?\"<>|\p{Cc}]"""), "_")
        .trim(' ', '.')
        .take(120)
        .ifBlank { song.id }
}

internal fun cacheableAudioResource(
    resource: PlaybackResource?,
    now: Instant = Clock.System.now(),
): PlaybackResource? =
    resource?.takeIf {
        (it.isDirectFile || it.isSabr) && it.profileId != "cached-audio" && !it.url.startsWith("file:") &&
            (it.expiresAt?.let { expiry -> expiry > now + 60.seconds } != false)
    }

internal fun playbackCaptureDirectory(context: Context): File = File(context.cacheDir, "playback-tee")

internal fun requireDownloadableAudioResource(resource: PlaybackResource) {
    if (resource.isSabr) return
    if (!resource.isDirectFile || !resource.url.startsWith("https://", ignoreCase = true)) {
        throw DownloadTransferException(IllegalArgumentException("Resolved audio is not a downloadable file"))
    }
}

internal suspend fun <T> withSabrDownloadControl(
    control: AudioDownloadControl?,
    download: suspend () -> T,
): T =
    coroutineScope {
        val cancellation =
            control?.let {
                launch {
                    control.awaitCancellation()
                    throw IOException("Download cancelled")
                }
            }
        try {
            check(control?.isCancelled != true) { "Download cancelled" }
            download().also { check(control?.isCancelled != true) { "Download cancelled" } }
        } finally {
            cancellation?.cancel()
        }
    }

internal suspend fun <T : Any> downloadWithRangeFallback(
    control: AudioDownloadControl?,
    range: suspend () -> T?,
    single: suspend () -> T,
    onRangeFailure: (Exception) -> Unit = {},
): T {
    val ranged =
        try {
            range()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (control?.isCancelled == true) throw e
            onRangeFailure(e)
            null
        }
    if (ranged == null && control?.isCancelled == true) error("Download cancelled")
    return ranged ?: single()
}

internal suspend fun <T> withCancellableAudioCall(
    call: Call,
    control: AudioDownloadControl?,
    read: suspend (Response) -> T,
): T =
    coroutineScope {
        val cancellation =
            launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try {
                    if (control == null) awaitCancellation() else control.awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        try {
            currentCoroutineContext().ensureActive()
            check(control?.isCancelled != true) { "Download cancelled" }
            call.execute().use { read(it) }
        } finally {
            cancellation.cancel()
        }
    }

internal fun verifyAudioLength(
    actual: Long,
    expected: Long?,
) {
    check(expected == null || actual == expected) { "Incomplete audio response" }
}

class AudioCacheManager(
    private val context: Context,
    private val database: MusicDatabase,
    private val client: OkHttpClient = sharedOkHttpClient,
) {
    private val downloadedArtwork = DownloadedArtwork(context)
    private val activeDownloads = mutableSetOf<String>()
    private val activeAudioFiles = mutableSetOf<File>()
    private val manuallyRemovedCacheSongs = ConcurrentHashMap<String, Long>()
    private val storageLock = AudioStorageLock()
    private val cacheGeneration = AtomicLong()
    internal val sabrPlaybackCache = SabrPlaybackCache()

    suspend fun isDownloaded(songId: String): Boolean =
        withContext(Dispatchers.IO) {
            val entry = database.dao().cachedAudio(songId) ?: return@withContext false
            entry.kind == KIND_DOWNLOAD && audioAvailable(entry.filePath)
        }

    suspend fun downloadedIds(songIds: Collection<String>): Set<String> =
        withContext(Dispatchers.IO) {
            val candidates = songIds.toSet()
            if (candidates.isEmpty()) return@withContext emptySet()
            candidates
                .chunked(900)
                .flatMap { database.dao().cachedAudioCandidates(it, KIND_DOWNLOAD) }
                .filter { audioAvailable(it.filePath) }
                .mapTo(mutableSetOf()) { it.songId }
        }

    suspend fun cachedSongIds(candidates: Set<String>? = null): Set<String> =
        withContext(Dispatchers.IO) {
            database
                .dao()
                .cachedAudio()
                .first()
                .filter { (candidates == null || it.songId in candidates) && audioAvailable(it.filePath) }
                .mapTo(mutableSetOf()) { it.songId }
        }

    suspend fun isCached(songId: String): Boolean =
        withContext(Dispatchers.IO) {
            val entry = database.dao().cachedAudio(songId) ?: return@withContext false
            audioAvailable(entry.filePath)
        }

    suspend fun getCachedPlaybackResource(
        song: SongItem,
        touch: Boolean = true,
    ): PlaybackResource? =
        withContext(Dispatchers.IO) {
            val songId = song.id
            val entry = database.dao().cachedAudio(songId) ?: return@withContext null
            val fileSize = audioSize(entry.filePath)
            if (!audioAvailable(entry.filePath)) {
                if (!audioMissing(entry.filePath)) return@withContext null
                storageLock.song(songId) {
                    val current = database.dao().cachedAudio(songId)
                    if (current == entry && audioMissing(entry.filePath)) deleteEntry(entry)
                }
                return@withContext null
            }

            if (touch) database.dao().touchCachedAudio(songId, System.currentTimeMillis(), System.currentTimeMillis() - 60_000L)
            PlaybackResource(
                videoId = songId,
                url = entry.filePath.takeIf(::isContentUri) ?: File(entry.filePath).toUri().toString(),
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                contentLengthBytes = fileSize.takeIf { it >= 0L },
                clientName = "local",
                profileId = "cached-audio",
                requireBoundedRange = false,
                allowPlaybackTrackingFallback = !song.isLocal && !song.isUploaded && !song.isPodcast && !song.id.startsWith("local_"),
            ).withCachedFormat(database.dao().format(songId).first())
        }

    suspend fun prefetchPlayback(resource: PlaybackResource) {
        try {
            if (Settings.shared.maxSongCacheSize == 0) return
            val cacheResource = cacheableAudioResource(resource)?.takeIf { it.isDirectFile } ?: return
            if (isCached(cacheResource.musicVideoId ?: cacheResource.videoId)) return
            val configured = Settings.shared.maxSongCacheSize.toLong() * 1024L * 1024L
            val budget = if (configured < 0L) MAX_AUDIO_BYTES else minOf(MAX_AUDIO_BYTES, configured)
            val incoming = cacheResource.contentLengthBytes ?: return
            if (incoming > budget) return
            evictIfNeeded(incoming, incoming)
            PlaybackPreCache.prefetch(cacheResource, playbackCaptureDirectory(context), budget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Playback pre-cache failed")
        }
    }

    suspend fun cacheSong(
        song: SongItem,
        resource: PlaybackResource? = null,
        playbackInstanceId: Long = 0L,
        isCurrentSong: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        if (Settings.shared.maxSongCacheSize == 0) return@withContext
        val generation = cacheGeneration.get()
        val sabrGeneration = sabrPlaybackCache.generation
        val sabrCandidate = sabrPlaybackCache.candidate(song.id)
        val sabrCapture = sabrCandidate?.takeIf { resource != null && it.matches(resource) }?.capture
        val cacheResource = if (sabrCapture != null) resource else cacheableAudioResource(resource)
        if (cacheResource == null) return@withContext
        val allowSabrDownload = sabrCandidate?.canDownload(Settings.shared.historyDuration) == true
        if (cacheResource.isSabr && sabrCapture == null && !allowSabrDownload) return@withContext
        val mvId = cacheResource.musicVideoId
        val targetSong =
            if (mvId != null && mvId != song.id) {
                song.copy(id = mvId)
            } else {
                song
            }
        val removedAtInstance = manuallyRemovedCacheSongs[targetSong.id]
        if (shouldSkipManuallyRemovedCache(
                manuallyRemovedCacheSongs.containsKey(targetSong.id),
                removedAtInstance,
                playbackInstanceId,
                isCurrentSong,
            )
        ) {
            return@withContext
        }
        if (removedAtInstance != null) manuallyRemovedCacheSongs.remove(targetSong.id, removedAtInstance)
        val existing = database.dao().cachedAudio(targetSong.id)
        if (existing != null && audioAvailable(existing.filePath)) return@withContext

        synchronized(activeDownloads) {
            if (!activeDownloads.add(targetSong.id)) return@withContext
        }

        try {
            val configured = Settings.shared.maxSongCacheSize.toLong() * 1024L * 1024L
            val budget = if (configured < 0L) MAX_AUDIO_BYTES else minOf(MAX_AUDIO_BYTES, configured)
            val incoming = sabrCapture?.size ?: cacheResource.contentLengthBytes
            if (incoming != null) {
                if (incoming > budget) return@withContext
                evictIfNeeded(incoming, if (cacheResource.isDirectFile || sabrCapture != null) incoming else 0L)
            }
            val captureComplete = DirectPlaybackCache.awaitCompletion(cacheResource)
            if (cacheResource.isDirectFile && cacheResource.contentLengthBytes != null && !captureComplete) return@withContext
            currentCoroutineContext().ensureActive()
            if (Settings.shared.maxSongCacheSize == 0) return@withContext
            storeAudioFile(
                targetSong,
                cacheResource,
                KIND_CACHE,
                maxBytes = budget,
                sabrCapture = sabrCapture,
                allowSabrDownload = allowSabrDownload,
                cacheMayPublish = {
                    cacheGeneration.get() == generation &&
                        (sabrCandidate == null || sabrPlaybackCache.generation == sabrGeneration) &&
                        !shouldSkipManuallyRemovedCache(
                            manuallyRemovedCacheSongs.containsKey(targetSong.id),
                            manuallyRemovedCacheSongs[targetSong.id],
                            playbackInstanceId,
                            isCurrentSong,
                        )
                },
            )
            if (isCached(targetSong.id)) sabrPlaybackCache.discard(song.id)
            evictIfNeeded()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "audio cache failed songId=%s", targetSong.id)
        } finally {
            synchronized(activeDownloads) {
                activeDownloads.remove(targetSong.id)
            }
        }
    }

    suspend fun getCacheSizeBytes(): Long = withContext(Dispatchers.IO) { database.dao().cachedAudioSize(KIND_CACHE) }

    suspend fun getDownloadSizeBytes(): Long = withContext(Dispatchers.IO) { database.dao().cachedAudioSize(KIND_DOWNLOAD) }

    suspend fun clearCache(): Boolean =
        withContext(Dispatchers.IO) {
            cacheGeneration.incrementAndGet()
            sabrPlaybackCache.clear()
            DirectPlaybackCache.clear(playbackCaptureDirectory(context))
            clearAudio(KIND_CACHE)
        }

    suspend fun clearDownloads() = clearAudio(KIND_DOWNLOAD)

    private suspend fun clearAudio(kind: String) =
        withContext(Dispatchers.IO) {
            storageLock.all {
                val failed = database.dao().cachedAudioByLastAccessed(kind).filterNot { deleteEntry(it) }
                val swept = sweepInternalAudio(kind)
                if (kind == KIND_DOWNLOAD) pruneDownloadedArtwork()
                failed.isEmpty() && swept
            }
        }

    suspend fun removeDownload(songId: String): Boolean = removeAudio(songId, KIND_DOWNLOAD)

    suspend fun removeCache(
        songId: String,
        playbackInstanceId: Long = 0L,
    ): Boolean {
        manuallyRemovedCacheSongs[songId] = playbackInstanceId
        val removed = removeAudio(songId, KIND_CACHE)
        if (!removed) manuallyRemovedCacheSongs.remove(songId, playbackInstanceId)
        return removed
    }

    private suspend fun removeAudio(
        songId: String,
        kind: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val removed =
                storageLock.song(songId) {
                    val entry = database.dao().cachedAudio(songId)?.takeIf { it.kind == kind } ?: return@song false
                    deleteEntry(entry)
                }
            if (kind == KIND_DOWNLOAD) storageLock.all { pruneDownloadedArtwork() }
            removed
        }

    private suspend fun deleteEntry(entry: CachedAudioEntity): Boolean {
        if (!deleteAudio(entry.filePath)) {
            Timber.tag(TAG).w("Could not delete audio songId=%s", entry.songId)
            return false
        }
        removeEntry(entry)
        return true
    }

    private suspend fun removeEntry(entry: CachedAudioEntity) {
        database.withDatabaseTransaction {
            database.dao().deleteCachedAudio(entry.songId)
            if (entry.kind == KIND_DOWNLOAD) {
                database.dao().updateDownloadedInfo(entry.songId, false, null)
            } else {
                database.dao().updateCachedInfo(entry.songId, false)
            }
        }
    }

    suspend fun reconcileStorage() =
        withContext(Dispatchers.IO) {
            val legacyImportFailure =
                try {
                    importLegacyDownloads()
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    error
                }
            DirectPlaybackCache.reconcileDirectory(playbackCaptureDirectory(context))
            listOf(KIND_CACHE, KIND_DOWNLOAD).forEach { kind ->
                database.dao().cachedAudioByLastAccessed(kind).forEach { candidate ->
                    if (audioMissing(candidate.filePath)) {
                        storageLock.song(candidate.songId) {
                            val entry = database.dao().cachedAudio(candidate.songId) ?: return@song
                            if (entry == candidate && audioMissing(entry.filePath)) deleteEntry(entry)
                        }
                    }
                }
            }
            migrateSharedCacheWebm()
            migrateDownloadsToConfiguredLocation()
            storageLock.all {
                listOf(KIND_CACHE, KIND_DOWNLOAD).forEach { sweepInternalAudio(it) }
                pruneDownloadedArtwork()
            }
            // Fail the audit so the next startup retries the remaining legacy downloads.
            legacyImportFailure?.let { throw it }
        }

    private suspend fun sweepInternalAudio(kind: String): Boolean {
        val indexed =
            database.dao().cachedAudio().first().mapNotNullTo(mutableSetOf()) {
                if (isContentUri(it.filePath)) null else runCatching { File(it.filePath).canonicalFile }.getOrNull()
            }
        val exportName = if (kind == KIND_DOWNLOAD) "download-export" else "cache-export"
        val directories = listOf(internalDirectory(kind), File(context.cacheDir, exportName))
        return synchronized(activeAudioFiles) {
            directories
                .flatMap { it.listFiles().orEmpty().toList() }
                .map { file ->
                    if (!file.isFile || file.name == ".nomedia" || file.canonicalFile in indexed ||
                        file.canonicalFile in activeAudioFiles
                    ) {
                        true
                    } else {
                        val deleted = file.delete()
                        if (!deleted) Timber.tag(TAG).w("Could not remove abandoned audio file %s", file.name)
                        deleted
                    }
                }.all { it }
        }
    }

    private suspend fun pruneDownloadedArtwork() {
        downloadedArtwork.prune(database.dao().cachedAudioByLastAccessed(KIND_DOWNLOAD).mapNotNull { it.thumbnailUrl })
    }

    suspend fun repairDownloadedArtwork() =
        withContext(Dispatchers.IO) {
            database.dao().cachedAudioByLastAccessed(KIND_DOWNLOAD).forEach { candidate ->
                storageLock.song(candidate.songId) {
                    val entry = database.dao().cachedAudio(candidate.songId)
                    if (entry?.kind == KIND_DOWNLOAD) downloadedArtwork.retain(entry.thumbnailUrl)
                }
            }
        }

    internal suspend fun migrateSharedCacheWebm(export: (File, File, AudioExportMetadata) -> Boolean = ::exportWebmOpus) =
        withContext(Dispatchers.IO) {
            database.dao().cachedAudioByLastAccessed(KIND_CACHE).forEach { candidate ->
                if (candidate.cacheLocation != LOCATION_SHARED_MEDIA || isContentUri(candidate.filePath)) return@forEach
                val original = File(candidate.filePath)
                if (!candidate.mimeType
                        ?.substringBefore(';')
                        ?.trim()
                        .equals("audio/webm", ignoreCase = true) &&
                    !original.extension.equals("webm", ignoreCase = true)
                ) {
                    return@forEach
                }
                storageLock.song(candidate.songId) {
                    if (database.dao().cachedAudio(candidate.songId) != candidate || !audioAvailable(candidate.filePath)) return@song
                    val tempDir = File(context.cacheDir, "cache-export").also(File::mkdirs)
                    val copiedFile = File.createTempFile("remux-", ".tmp", tempDir)
                    try {
                        copyAudioTo(candidate.filePath, copiedFile)
                        val (source, mimeType) =
                            prepareStoredAudio(
                                copiedFile,
                                "audio/webm",
                                publicStorage = true,
                                metadata = AudioExportMetadata(candidate.title, candidate.artist, candidate.album),
                                export = export,
                            )
                        val size = source.length()
                        check(size >= MIN_AUDIO_BYTES) { "Remuxed cache audio is too small" }
                        val format = database.dao().format(candidate.songId).first()
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable) {
                            val path =
                                publishStoredAudio(candidate, {
                                    moveAudioToDirectory(
                                        source,
                                        checkNotNull(original.parentFile),
                                        "${original.nameWithoutExtension}.opus",
                                    )
                                }) { storedPath ->
                                    database.withDatabaseTransaction {
                                        database.dao().upsert(candidate.copy(filePath = storedPath, mimeType = mimeType, fileSize = size))
                                        format?.let {
                                            database.dao().upsert(
                                                it.copy(mimeType = checkNotNull(mimeType), contentLength = size),
                                            )
                                        }
                                    }
                                }
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(candidate.filePath, path),
                                arrayOf(candidate.mimeType, mimeType),
                                null,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Timber.tag(TAG).w(error, "Failed to remux shared cache songId=%s", candidate.songId)
                    } finally {
                        copiedFile.delete()
                        File(tempDir, "${copiedFile.nameWithoutExtension}.opus.tmp").delete()
                    }
                }
            }
        }

    /**
     * Moves downloads made by the legacy v13 app, which kept them in an ExoPlayer cache keyed by song id,
     * into internal download storage. Imported entries leave the legacy cache one by one, so a failed run
     * resumes on the next storage audit; the cache directory is removed once every entry is handled.
     */
    internal suspend fun importLegacyDownloads(directory: File = File(context.filesDir, LEGACY_DOWNLOAD_DIRECTORY)) =
        withContext(Dispatchers.IO) {
            if (!directory.isDirectory) return@withContext
            // The legacy app indexed this cache in StandaloneDatabaseProvider's database; SimpleCache deletes spans it cannot index.
            val databaseProvider = StandaloneDatabaseProvider(context)
            val cache = SimpleCache(directory, NoOpCacheEvictor(), databaseProvider)
            var failure: Exception? = null
            try {
                for (songId in cache.keys.toList()) {
                    try {
                        storageLock.song(songId) { importLegacyDownload(cache, songId) }
                        cache.removeResource(songId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Timber.tag(TAG).w(error, "Failed to import legacy download songId=%s", songId)
                        if (failure == null) failure = error
                    }
                }
            } finally {
                cache.release()
            }
            failure?.let { throw it }
            SimpleCache.delete(directory, databaseProvider)
        }

    private suspend fun importLegacyDownload(
        cache: SimpleCache,
        songId: String,
    ) {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(songId))
        // Partial entries were never completed downloads in the legacy app.
        if (length <= 0L || !cache.isCached(songId, 0L, length)) return
        val song = database.dao().song(songId).first() ?: return
        if (isDownloaded(songId)) return
        val downloads = internalDirectory(KIND_DOWNLOAD).also(File::mkdirs)
        check(downloads.usableSpace - length >= MIN_FREE_SPACE_BYTES) { "Not enough space to import legacy download" }
        val safeId = songId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val temporary = File(downloads, "$safeId.legacy.tmp")
        try {
            temporary.outputStream().use { output ->
                var position = 0L
                cache.getCachedSpans(songId).sortedBy { it.position }.forEach { span ->
                    val file = checkNotNull(span.file?.takeIf { span.position == position }) { "Legacy download has a gap" }
                    file.inputStream().use { it.copyTo(output) }
                    position += span.length
                }
                check(position == length && temporary.length() == length) { "Legacy download is incomplete" }
                output.fd.sync()
            }
            val mimeType = song.format?.mimeType
            val artist = song.orderedArtists.joinToString(", ") { it.name }.ifBlank { getString(Res.string.unknown_artist) }
            publishStoredAudio(null, {
                moveAudioToDirectory(temporary, downloads, "$safeId${extensionForMimeType(mimeType)}")
            }) { path ->
                val now = System.currentTimeMillis()
                database.withDatabaseTransaction {
                    database.dao().upsert(
                        CachedAudioEntity(
                            songId = songId,
                            title = song.title,
                            artist = artist,
                            album = song.album?.title,
                            thumbnailUrl = song.thumbnailUrl,
                            filePath = path,
                            mimeType = mimeType,
                            fileSize = length,
                            cachedAt = now,
                            lastAccessedAt = now,
                            cacheLocation = "DOWNLOAD_$LOCATION_INTERNAL",
                            kind = KIND_DOWNLOAD,
                        ),
                    )
                    database.dao().updateDownloadedInfo(
                        songId,
                        true,
                        song.song.dateDownload ?: Clock.System.now().toLocalDateTime(TimeZone.UTC),
                    )
                    database.dao().updateCachedInfo(songId, false)
                }
            }
        } finally {
            temporary.delete()
        }
    }

    suspend fun migrateDownloadsToConfiguredLocation() =
        withContext(Dispatchers.IO) {
            storageLock.all {
                var failure: Exception? = null
                database.dao().cachedAudioByLastAccessed(KIND_DOWNLOAD).forEach { entry ->
                    val targetCustom = isCustom(KIND_DOWNLOAD)
                    val targetShared = isShared(KIND_DOWNLOAD)
                    val alreadyMigrated =
                        when {
                            targetCustom -> {
                                audioBelongsToTree(entry.filePath, Settings.shared.customDownloadPath)
                            }

                            targetShared -> {
                                (isContentUri(entry.filePath) && entry.cacheLocation == audioLocation(KIND_DOWNLOAD)) ||
                                    (
                                        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                            File(entry.filePath).parentFile == sharedMediaDirectory(KIND_DOWNLOAD)
                                    )
                            }

                            else -> {
                                !isContentUri(entry.filePath) && File(entry.filePath).parentFile == internalDirectory(KIND_DOWNLOAD)
                            }
                        }
                    if (alreadyMigrated) return@forEach

                    val safeId = entry.songId.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val tempDir = File(context.cacheDir, "download-export").also(File::mkdirs)
                    val copiedFile = File(tempDir, "$safeId.migrate.tmp")
                    try {
                        copyAudioTo(entry.filePath, copiedFile)
                        check(copiedFile.length() >= MIN_AUDIO_BYTES) { "Downloaded audio is too small" }
                        val (sourceFile, mimeType) =
                            prepareStoredAudio(
                                copiedFile,
                                entry.mimeType,
                                publicStorage = targetShared || targetCustom,
                                metadata = AudioExportMetadata(entry.title, entry.artist, entry.album),
                            )

                        val extension = extensionForMimeType(mimeType)
                        val baseName =
                            listOf(entry.artist, entry.title)
                                .filter(String::isNotBlank)
                                .joinToString(" - ")
                                .replace(Regex("""[\\/:*?\"<>|\p{Cc}]"""), "_")
                                .trim(' ', '.')
                                .take(120)
                                .ifBlank { safeId }
                        publishStoredAudio(entry, {
                            when {
                                targetCustom -> {
                                    publishCustomAudio(sourceFile, "$baseName$extension", mimeType)
                                }

                                targetShared -> {
                                    publishSharedAudio(
                                        sourceFile,
                                        "$baseName$extension",
                                        mimeType,
                                        entry.title,
                                        entry.artist,
                                        entry.album,
                                    )
                                }

                                else -> {
                                    moveAudioToDirectory(sourceFile, internalDirectory(KIND_DOWNLOAD), "$safeId$extension")
                                }
                            }
                        }) { storedPath ->
                            database.dao().upsert(
                                entry.copy(
                                    filePath = storedPath,
                                    mimeType = mimeType,
                                    fileSize = audioSize(storedPath).takeIf { it >= 0 } ?: entry.fileSize,
                                    cacheLocation = audioLocation(KIND_DOWNLOAD),
                                ),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Timber.tag(TAG).w(error, "Failed to migrate download songId=%s", entry.songId)
                        if (failure == null) failure = error
                    } finally {
                        copiedFile.delete()
                        File(tempDir, "$safeId.migrate.opus.tmp").delete()
                        File(tempDir, "$safeId.migrate.m4a.tmp").delete()
                    }
                }
                failure?.let { throw it }
            }
        }

    fun cacheDirectory(): File = audioDirectory(KIND_CACHE)

    fun downloadDirectory(): File = audioDirectory(KIND_DOWNLOAD)

    fun cacheDirectoryLabel(): String = cacheDirectory().absolutePath

    fun downloadDirectoryLabel(internalStorageLabel: String): String =
        if (isCustom(KIND_DOWNLOAD)) {
            formatDocumentTreeUriLabel(Settings.shared.customDownloadPath, internalStorageLabel)
        } else if (isShared(KIND_DOWNLOAD)) {
            sharedMediaDirectory(KIND_DOWNLOAD).absolutePath
        } else {
            downloadDirectory().absolutePath
        }

    suspend fun downloadSong(
        song: SongItem,
        resource: PlaybackResource,
        control: AudioDownloadControl? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        requireDownloadableAudioResource(resource)
        storageLock.song(song.id) { storeAudioFile(song, resource, KIND_DOWNLOAD, control, onProgress) }
    }

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun storeAudioFile(
        song: SongItem,
        resource: PlaybackResource,
        kind: String,
        control: AudioDownloadControl? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        maxBytes: Long = MAX_AUDIO_BYTES,
        sabrCapture: SabrInputStream.CompleteCapture? = null,
        allowSabrDownload: Boolean = true,
        cacheMayPublish: () -> Boolean = { true },
    ) {
        val existing = database.dao().cachedAudio(song.id)
        if (existing?.kind == kind && audioAvailable(existing.filePath)) {
            if (kind == KIND_DOWNLOAD) {
                val url = existing.thumbnailUrl ?: song.thumbnail
                if (url != existing.thumbnailUrl) database.dao().upsert(existing.copy(thumbnailUrl = url))
                downloadedArtwork.retain(url)
            }
            return
        }
        if (kind == KIND_CACHE && existing?.kind == KIND_DOWNLOAD && audioAvailable(existing.filePath)) return

        val cacheDir = audioDirectory(kind)
        val safeId = song.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tempDir =
            if (isShared(kind) || isCustom(kind)) {
                File(context.cacheDir, if (kind == KIND_DOWNLOAD) "download-export" else "cache-export")
            } else {
                cacheDir
            }
        tempDir.mkdirs()
        val tempFile = File(tempDir, "$safeId.tmp")
        val temporaryFiles = listOf(tempFile, File(tempDir, "$safeId.opus.tmp"), File(tempDir, "$safeId.m4a.tmp")).map { it.canonicalFile }
        synchronized(activeAudioFiles) { activeAudioFiles.addAll(temporaryFiles) }

        try {
            val mimeType =
                try {
                    val format = if (existing?.kind == KIND_CACHE && kind == KIND_DOWNLOAD) database.dao().format(song.id).first() else null
                    val reuseCache =
                        existing?.kind == KIND_CACHE && kind == KIND_DOWNLOAD &&
                            canReuseCachedAudio(resource, format?.itag, format?.mimeType, format?.codecs, existing.fileSize) &&
                            audioSize(existing.filePath) == existing.fileSize
                    val downloadedMimeType =
                        if (reuseCache) {
                            copyAudioTo(checkNotNull(existing).filePath, tempFile)
                            existing.mimeType
                        } else if (sabrCapture?.copyTo(tempFile) == true) {
                            checkNotNull(resource.sabrBootstrap).mimeType.substringBefore(';')
                        } else if (DirectPlaybackCache.copyComplete(resource, tempFile)) {
                            resource.mimeType?.substringBefore(';') ?: "application/octet-stream"
                        } else if (resource.isDirectFile && resource.contentLengthBytes != null && kind == KIND_CACHE) {
                            error("Playback capture is incomplete")
                        } else if (resource.isSabr) {
                            check(allowSabrDownload) { "SABR playback did not reach the cache download threshold" }
                            withSabrDownloadControl(control) {
                                downloadSabrAudio(
                                    resource,
                                    tempFile,
                                    maxBytes = maxBytes,
                                    awaitReady = { control?.awaitIfPaused() },
                                    onProgress = onProgress,
                                )
                            }
                            checkNotNull(resource.sabrBootstrap).mimeType.substringBefore(';')
                        } else {
                            downloadWithRangeFallback(
                                control = control,
                                range = {
                                    if (kind == KIND_DOWNLOAD || (resource.useRangeChunks && resource.contentLengthBytes != null)) {
                                        downloadWithRanges(resource, tempFile, control, onProgress)
                                    } else {
                                        null
                                    }
                                },
                                single = { downloadSingleStream(resource, tempFile, control, onProgress, maxBytes) },
                                onRangeFailure = { Timber.tag(TAG).w(it, "range download failed, falling back to single stream") },
                            )
                        }

                    if (tempFile.length() < MIN_AUDIO_BYTES) {
                        tempFile.delete()
                        error("Downloaded audio is too small")
                    }
                    downloadedMimeType
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw DownloadTransferException(e)
                }

            val (sourceFile, storedMimeType) =
                prepareStoredAudio(
                    tempFile,
                    mimeType,
                    publicStorage = isShared(kind) || isCustom(kind),
                    metadata = AudioExportMetadata(song.title, song.artists.joinToString(", ") { it.name }, song.album?.name),
                )

            currentCoroutineContext().ensureActive()
            control?.awaitIfPaused()
            check(control?.isCancelled != true) { "Download cancelled" }
            val sourceBytes = sourceFile.length()
            val publish: suspend () -> String = {
                val extension = extensionForMimeType(storedMimeType)
                val baseName = if (kind == KIND_DOWNLOAD && (isShared(kind) || isCustom(kind))) downloadFileBaseName(song) else safeId
                val storedPath =
                    publishStoredAudio(existing, {
                        if (kind == KIND_DOWNLOAD && isShared(kind)) {
                            publishSharedAudio(
                                sourceFile,
                                "$baseName$extension",
                                storedMimeType,
                                song.title,
                                song.artists.joinToString(", ") {
                                    it.name
                                },
                                song.album?.name,
                            )
                        } else if (kind == KIND_DOWNLOAD && isCustom(kind)) {
                            publishCustomAudio(
                                sourceFile,
                                "$baseName$extension",
                                storedMimeType,
                            )
                        } else {
                            moveAudioToDirectory(sourceFile, cacheDir, "$baseName$extension")
                        }
                    }) { path ->
                        val now = System.currentTimeMillis()
                        val storedBytes = audioSize(path).takeIf { it >= 0 } ?: sourceBytes
                        val artist = song.artists.joinToString(", ") { it.name }.ifBlank { getString(Res.string.unknown_artist) }
                        database.withDatabaseTransaction {
                            database.dao().upsertHistorySong(song)
                            database.dao().upsert(
                                CachedAudioEntity(
                                    songId = song.id,
                                    title = song.title,
                                    artist = artist,
                                    album = song.album?.name,
                                    thumbnailUrl = song.thumbnail,
                                    filePath = path,
                                    mimeType = storedMimeType,
                                    fileSize = storedBytes,
                                    cachedAt = now,
                                    lastAccessedAt = now,
                                    cacheLocation = audioLocation(kind),
                                    kind = kind,
                                ),
                            )
                            resource.toCachedFormatEntity(song.id, storedMimeType, storedBytes)?.let { database.dao().upsert(it) }
                            if (kind == KIND_DOWNLOAD) {
                                database.dao().updateDownloadedInfo(song.id, true, Clock.System.now().toLocalDateTime(TimeZone.UTC))
                                database.dao().updateCachedInfo(song.id, false)
                            } else {
                                database.dao().updateCachedInfo(song.id, true)
                            }
                        }
                    }
                storedPath
            }
            val storedPath =
                if (kind == KIND_CACHE) {
                    storageLock.all {
                        if (!cacheMayPublish() || database.dao().cachedAudio(song.id) != existing) return@all null
                        check(sourceBytes <= maxBytes) { "Audio exceeds cache budget" }
                        evictIfNeeded(sourceBytes, completedTemporaryBytes = sourceBytes, storageLocked = true)
                        publish()
                    }
                } else {
                    publish()
                } ?: return
            scanIfShared(storedPath, storedMimeType, kind, song)
            if (kind == KIND_DOWNLOAD) {
                downloadedArtwork.retain(song.thumbnail)
            } else if (Settings.shared.maxImageCacheSize > 0) {
                song.thumbnail?.let { url ->
                    context.imageLoader.execute(
                        ImageRequest
                            .Builder(context)
                            .data(url)
                            .size(544)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .decoderFactory(BlackholeDecoder.Factory())
                            .build(),
                    )
                }
            }
        } finally {
            synchronized(activeAudioFiles) {
                temporaryFiles.forEach { it.delete() }
                activeAudioFiles.removeAll(temporaryFiles.toSet())
            }
        }
    }

    private suspend fun publishStoredAudio(
        previous: CachedAudioEntity?,
        publish: () -> String,
        commit: suspend (String) -> Unit,
    ): String =
        withContext(NonCancellable) {
            val path = publish()
            try {
                commit(path)
            } catch (e: Exception) {
                if (path != previous?.filePath) deleteAudio(path)
                throw e
            }
            if (previous != null && previous.filePath != path && !deleteAudio(previous.filePath)) {
                Timber.tag(TAG).w("Could not remove previous audio songId=%s", previous.songId)
            }
            path
        }

    private fun buildRequest(
        resource: PlaybackResource,
        range: String? = null,
    ): Request {
        val builder = Request.Builder().url(resource.url)
        resource.headers.forEach { (name, value) -> builder.header(name, value) }
        if (range != null) builder.header("Range", range)
        return builder.build()
    }

    @SuppressLint("UsableSpace")
    private suspend fun downloadSingleStream(
        resource: PlaybackResource,
        tempFile: File,
        control: AudioDownloadControl?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        maxBytes: Long = MAX_AUDIO_BYTES,
    ): String {
        val contentLength =
            resource.contentLengthBytes
                ?: if (resource.requireBoundedRange) probeRange(resource, control)?.totalBytes else null
        val boundedRange =
            if (resource.requireBoundedRange && contentLength != null && contentLength > 0L) {
                "bytes=0-${contentLength - 1}"
            } else {
                null
            }
        contentLength?.let {
            check(it <= maxBytes) { "Audio exceeds cache budget" }
            validateDownloadSize(tempFile, it)
        }
        val spaceBudget = DownloadSpaceBudget(MIN_FREE_SPACE_BYTES) { tempFile.parentFile?.usableSpace ?: 0L }
        return withCancellableAudioCall(client.newCall(buildRequest(resource, boundedRange)), control) { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body
            val totalBytes = contentLength ?: body.contentLength().takeIf { it > 0L }
            if (response.code == 206) {
                val range =
                    CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
                        ?: error("Invalid Content-Range")
                check(
                    range.groupValues[1].toLong() == 0L &&
                        range.groupValues[2].toLong() + 1 == totalBytes &&
                        range.groupValues[3].toLong() == totalBytes,
                ) { "Incomplete audio response" }
            }
            totalBytes?.let {
                check(it <= maxBytes) { "Audio exceeds cache budget" }
                validateDownloadSize(tempFile, it)
            }
            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var downloadedBytes = 0L
                    var lastProgressBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        control?.awaitIfPaused()
                        if (control?.isCancelled == true) error("Download cancelled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        val maxAllowedBytes =
                            minOf(maxBytes, (contentLength ?: totalBytes)?.let { it + 512 * 1024L } ?: MAX_AUDIO_BYTES)
                        check(downloadedBytes + read <= maxAllowedBytes) {
                            "Audio download exceeded the expected size"
                        }
                        spaceBudget.beforeWrite(read)
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (downloadedBytes - lastProgressBytes >= PROGRESS_STEP_BYTES) {
                            lastProgressBytes = downloadedBytes
                            onProgress(downloadedBytes, totalBytes)
                        }
                    }
                    verifyAudioLength(downloadedBytes, totalBytes)
                    onProgress(downloadedBytes, totalBytes)
                }
            }
            body.contentType()?.toString()?.substringBefore(';') ?: "application/octet-stream"
        }
    }

    @SuppressLint("UsableSpace")
    private suspend fun downloadWithRanges(
        resource: PlaybackResource,
        tempFile: File,
        control: AudioDownloadControl?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): String? {
        val probe = if (resource.contentLengthBytes == null) probeRange(resource, control) else null
        val totalBytes = resource.contentLengthBytes ?: probe?.totalBytes ?: return null
        if (totalBytes <= 0L) return null
        validateDownloadSize(tempFile, totalBytes)

        val chunkSize =
            if (resource.useRangeChunks) {
                resource.rangeChunkSizeBytes.coerceAtLeast(1L)
            } else {
                resource.rangeChunkSizeBytes.coerceAtLeast(MIN_RANGE_CHUNK_BYTES)
            }
        val chunkCount = (totalBytes + chunkSize - 1) / chunkSize
        if (chunkCount <= 1) return null

        Timber.tag(TAG).d("range download start videoId=%s bytes=%d chunks=%d", resource.videoId, totalBytes, chunkCount)
        withContext(Dispatchers.IO) {
            RandomAccessFile(tempFile, "rw").use { it.setLength(totalBytes) }
        }
        val downloadedBytes = AtomicLong(0L)
        val spaceBudget = DownloadSpaceBudget(MIN_FREE_SPACE_BYTES) { tempFile.parentFile?.usableSpace ?: 0L }
        val mimeType = arrayOf(probe?.mimeType)

        coroutineScope {
            val nextStart = AtomicLong(0L)
            List(PARALLEL_RANGE_DOWNLOADS) {
                async(Dispatchers.IO) {
                    while (true) {
                        val start = nextStart.getAndAdd(chunkSize)
                        if (start >= totalBytes) break
                        val type =
                            downloadRangeChunk(
                                resource,
                                tempFile,
                                start,
                                minOf(start + chunkSize - 1, totalBytes - 1),
                                totalBytes,
                                downloadedBytes,
                                spaceBudget,
                                control,
                                onProgress,
                            )
                        synchronized(mimeType) { if (mimeType[0] == null) mimeType[0] = type }
                    }
                }
            }.awaitAll()
        }
        onProgress(totalBytes, totalBytes)
        return mimeType[0] ?: resource.mimeType?.substringBefore(';') ?: "application/octet-stream"
    }

    private suspend fun probeRange(
        resource: PlaybackResource,
        control: AudioDownloadControl?,
    ): RangeProbe? =
        try {
            withCancellableAudioCall(client.newCall(buildRequest(resource, "bytes=0-0")), control) { response ->
                if (response.code != 206) return@withCancellableAudioCall null
                val contentRange = response.header("Content-Range") ?: return@withCancellableAudioCall null
                val totalBytes = contentRange.substringAfter('/').toLongOrNull() ?: return@withCancellableAudioCall null
                RangeProbe(
                    totalBytes = totalBytes,
                    mimeType =
                        response.body
                            .contentType()
                            ?.toString()
                            ?.substringBefore(';'),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Timber.tag(TAG).d(e, "range probe failed videoId=%s", resource.videoId)
            null
        }

    private suspend fun downloadRangeChunk(
        resource: PlaybackResource,
        tempFile: File,
        start: Long,
        end: Long,
        totalBytes: Long,
        downloadedBytes: AtomicLong,
        spaceBudget: DownloadSpaceBudget,
        control: AudioDownloadControl?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): String? {
        var attempts = 0
        var chunkProgress = 0L
        while (true) {
            attempts++
            var transportFailure = true
            try {
                return withCancellableAudioCall(client.newCall(buildRequest(resource, "bytes=$start-$end")), control) { response ->
                    transportFailure = false
                    if (response.code != 206) error("HTTP ${response.code}")
                    val body = response.body
                    val expectedBytes = end - start + 1
                    val contentRange = response.header("Content-Range") ?: error("Missing Content-Range")
                    val rangeMatch = CONTENT_RANGE.matchEntire(contentRange) ?: error("Invalid Content-Range")
                    check(rangeMatch.groupValues[1].toLong() == start)
                    check(rangeMatch.groupValues[2].toLong() == end)
                    check(rangeMatch.groupValues[3].toLong() == totalBytes)
                    check(body.contentLength().let { it < 0L || it <= expectedBytes }) { "Range response exceeded requested size" }
                    RandomAccessFile(tempFile, "rw").use { output ->
                        output.seek(start)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            var lastProgressBytes = downloadedBytes.get()
                            var chunkBytes = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                control?.awaitIfPaused()
                                if (control?.isCancelled == true) error("Download cancelled")
                                val read =
                                    try {
                                        input.read(buffer)
                                    } catch (error: IOException) {
                                        transportFailure = true
                                        throw error
                                    }
                                if (read == -1) break
                                check(chunkBytes + read <= expectedBytes) { "Range response exceeded requested size" }
                                spaceBudget.beforeWrite(read)
                                output.write(buffer, 0, read)
                                chunkBytes += read
                                // Retried prefixes overwrite bytes already counted by this chunk.
                                val newBytes = (chunkBytes - chunkProgress).coerceAtLeast(0L)
                                chunkProgress = maxOf(chunkProgress, chunkBytes)
                                val currentBytes = downloadedBytes.addAndGet(newBytes)
                                if (currentBytes - lastProgressBytes >= PROGRESS_STEP_BYTES) {
                                    lastProgressBytes = currentBytes
                                    onProgress(currentBytes.coerceAtMost(totalBytes), totalBytes)
                                }
                            }
                            check(chunkBytes == expectedBytes) { "Incomplete range response" }
                        }
                    }
                    body.contentType()?.toString()?.substringBefore(';')
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (!transportFailure || control?.isCancelled == true || attempts >= MAX_RANGE_ATTEMPTS ||
                    error is ProtocolException || error is SSLHandshakeException || error is SSLPeerUnverifiedException ||
                    (error is InterruptedIOException && error !is SocketTimeoutException)
                ) {
                    throw error
                }
            }
        }
    }

    private data class RangeProbe(
        val totalBytes: Long,
        val mimeType: String?,
    )

    @SuppressLint("UsableSpace")
    private fun validateDownloadSize(
        file: File,
        bytes: Long,
    ) {
        check(bytes in 1..MAX_AUDIO_BYTES) { "Invalid audio download size" }
        if ((file.parentFile?.usableSpace ?: 0L) <= bytes + MIN_FREE_SPACE_BYTES) throw InsufficientAudioStorageException()
    }

    suspend fun enforceCacheLimit() =
        withContext(Dispatchers.IO) {
            cacheGeneration.incrementAndGet()
            sabrPlaybackCache.clear()
            DirectPlaybackCache.clear(playbackCaptureDirectory(context))
            evictIfNeeded()
        }

    @SuppressLint("UsableSpace")
    private suspend fun evictIfNeeded(
        reservedBytes: Long = 0L,
        additionalTemporaryBytes: Long = 0L,
        completedTemporaryBytes: Long = 0L,
        storageLocked: Boolean = false,
    ) {
        var currentSize = database.dao().cachedAudioSize(KIND_CACHE)
        val configuredBytes = Settings.shared.maxSongCacheSize * 1024L * 1024L
        check(configuredBytes < 0L || reservedBytes <= configuredBytes) { "Audio exceeds cache budget" }
        val maxBytes =
            audioCacheBudget(
                configuredBytes,
                currentSize,
                cacheDirectory().usableSpace,
                MIN_FREE_SPACE_BYTES,
                additionalTemporaryBytes,
                completedTemporaryBytes,
            )
        if (currentSize + reservedBytes <= maxBytes) return

        database.dao().cachedAudioByLastAccessed(KIND_CACHE).forEach { candidate ->
            if (currentSize + reservedBytes <= maxBytes) return
            val remove: suspend () -> Unit = {
                val entry = database.dao().cachedAudio(candidate.songId)?.takeIf { it.kind == KIND_CACHE }
                if (entry != null && deleteEntry(entry)) currentSize -= entry.fileSize
            }
            if (storageLocked) remove() else storageLock.song(candidate.songId) { remove() }
        }
        if (reservedBytes > 0L && currentSize + reservedBytes > maxBytes) throw InsufficientAudioStorageException()
    }

    private fun internalDirectory(kind: String): File = File(context.filesDir, if (kind == KIND_DOWNLOAD) "downloads" else "audio_cache")

    @Suppress("DEPRECATION")
    private fun sharedMediaDirectory(kind: String): File {
        if (kind == KIND_DOWNLOAD) {
            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SHARED_DOWNLOAD_RELATIVE_DIR)
        }
        val base = context.externalMediaDirs.firstOrNull { it != null } ?: context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        return File(base ?: internalDirectory(kind), "Metrolist/Cache")
    }

    private fun audioLocation(kind: String): String =
        when (kind) {
            KIND_DOWNLOAD -> "DOWNLOAD_${Settings.shared.downloadLocation}"
            else -> Settings.shared.audioCacheLocation
        }

    private fun audioDirectory(kind: String): File =
        when (kind) {
            KIND_DOWNLOAD -> {
                when (Settings.shared.downloadLocation) {
                    LOCATION_SHARED_MEDIA -> sharedMediaDirectory(kind)
                    else -> internalDirectory(kind)
                }
            }

            else -> {
                when (Settings.shared.audioCacheLocation) {
                    LOCATION_SHARED_MEDIA -> sharedMediaDirectory(kind)
                    else -> internalDirectory(kind)
                }
            }
        }.also { it.mkdirs() }

    private fun isShared(kind: String): Boolean =
        when (kind) {
            KIND_DOWNLOAD -> Settings.shared.downloadLocation == LOCATION_SHARED_MEDIA
            else -> Settings.shared.audioCacheLocation == LOCATION_SHARED_MEDIA
        }

    private fun isCustom(kind: String): Boolean =
        kind == KIND_DOWNLOAD &&
            Settings.shared.downloadLocation == LOCATION_CUSTOM &&
            Settings.shared.customDownloadPath.isNotBlank()

    private fun publishCustomAudio(
        source: File,
        fileName: String,
        mimeType: String?,
    ): String {
        val customUriString = Settings.shared.customDownloadPath
        if (customUriString.isBlank()) {
            val fallbackDir = internalDirectory(KIND_DOWNLOAD)
            return moveAudioToDirectory(source, fallbackDir, fileName)
        }
        val treeUri = customUriString.toUri()
        val parentDocUri =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        val targetUri =
            DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                mimeType ?: "application/octet-stream",
                fileName,
            ) ?: error("Could not create audio file in custom folder")

        try {
            context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not write audio to custom folder")
            source.delete()
            return targetUri.toString()
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, targetUri) }
            throw e
        }
    }

    private fun publishSharedAudio(
        source: File,
        fileName: String,
        mimeType: String?,
        title: String,
        artist: String,
        album: String?,
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return moveAudioToDirectory(source, sharedMediaDirectory(KIND_DOWNLOAD), fileName)
        }
        val values =
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType ?: "application/octet-stream")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$SHARED_DOWNLOAD_RELATIVE_DIR")
                put(MediaStore.Audio.Media.TITLE, title)
                artist.takeIf(String::isNotBlank)?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                album?.takeIf(String::isNotBlank)?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        val uri =
            context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create audio file")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Could not open audio file")
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
            source.delete()
            return uri.toString()
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun copyAudioTo(
        sourcePath: String,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        val input =
            if (isContentUri(sourcePath)) {
                context.contentResolver.openInputStream(sourcePath.toUri()) ?: error("Could not open downloaded audio")
            } else {
                File(sourcePath).inputStream()
            }
        input.use { source -> target.outputStream().use(source::copyTo) }
    }

    private fun audioSize(
        path: String,
        throwOnError: Boolean = false,
    ): Long =
        runCatching {
            if (isContentUri(path)) {
                context.contentResolver.openAssetFileDescriptor(path.toUri(), "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L } ?: descriptor.parcelFileDescriptor.statSize
                } ?: -1L
            } else {
                File(path).takeIf(File::exists)?.length() ?: -1L
            }
        }.getOrElse { if (throwOnError) throw it else -1L }

    private fun audioAvailable(path: String): Boolean =
        runCatching {
            val size = audioSize(path, throwOnError = true)
            if (size >= 0L) return@runCatching size >= MIN_AUDIO_BYTES
            if (!isContentUri(path)) return@runCatching false
            context.contentResolver.openInputStream(path.toUri())?.use { input ->
                val buffer = ByteArray(MIN_AUDIO_BYTES.toInt())
                var read = 0
                while (read < buffer.size) {
                    val count = input.read(buffer, read, buffer.size - read)
                    if (count <= 0) break
                    read += count
                }
                read == buffer.size
            } == true
        }.getOrDefault(false)

    // Keep unreadable files indexed so restored permissions or remounted storage can recover them.
    private fun audioMissing(path: String): Boolean {
        val size =
            try {
                audioSize(path, throwOnError = true)
            } catch (_: FileNotFoundException) {
                return audioAbsent(path)
            } catch (_: Exception) {
                return false
            }
        return if (size < 0L) audioAbsent(path) else size < MIN_AUDIO_BYTES
    }

    private fun deleteAudio(path: String): Boolean =
        runCatching {
            if (isContentUri(path)) {
                val uri = path.toUri()
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else {
                    context.contentResolver.delete(uri, null, null) > 0
                }
            } else {
                val file = File(path)
                !file.exists() || file.delete()
            }
        }.getOrDefault(false) || audioAbsent(path)

    private fun audioAbsent(path: String): Boolean =
        runCatching {
            if (!isContentUri(path)) {
                !File(path).exists()
            } else {
                context.contentResolver
                    .query(path.toUri(), arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { !it.moveToFirst() } == true
            }
        }.getOrDefault(false)

    private fun isContentUri(path: String): Boolean = path.startsWith("content://")

    private fun scanIfShared(
        filePath: String,
        mimeType: String?,
        kind: String,
        song: SongItem,
    ) {
        if (!isShared(kind) || isContentUri(filePath)) return
        MediaScannerConnection.scanFile(context, arrayOf(filePath), arrayOf(mimeType)) { _, uri ->
            uri ?: return@scanFile
            runCatching {
                val values =
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.TITLE, song.title)
                        song.artists.joinToString(", ") { it.name }.takeIf(String::isNotBlank)?.let {
                            put(MediaStore.Audio.Media.ARTIST, it)
                        }
                        song.album
                            ?.name
                            ?.takeIf(String::isNotBlank)
                            ?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                    }
                context.contentResolver.update(uri, values, null, null)
            }.onFailure { Timber.tag(TAG).w(it, "failed to publish downloaded audio metadata songId=%s", song.id) }
        }
    }

    private fun extensionForMimeType(mimeType: String?): String =
        when (mimeType?.lowercase()) {
            "audio/webm" -> ".webm"
            "audio/mpeg", "audio/mp3" -> ".mp3"
            "audio/mp4", "audio/x-m4a", "audio/aac", "audio/mp4a-latm" -> ".m4a"
            "audio/ogg", "audio/opus" -> ".opus"
            "audio/flac" -> ".flac"
            else -> ".audio"
        }

    companion object {
        const val LOCATION_INTERNAL = "INTERNAL"
        const val LOCATION_SHARED_MEDIA = "SHARED_MEDIA"
        const val LOCATION_CUSTOM = "CUSTOM"

        fun formatDocumentTreeUriLabel(
            uriString: String,
            primaryStorageLabel: String,
        ): String =
            runCatching {
                val uri = uriString.toUri()
                val docId = DocumentsContract.getTreeDocumentId(uri)
                if (docId.startsWith("primary:")) {
                    val relativePath = docId.removePrefix("primary:")
                    if (relativePath.isBlank()) primaryStorageLabel else relativePath
                } else if (docId.contains(":")) {
                    val parts = docId.split(":", limit = 2)
                    val volume = parts.getOrNull(0) ?: ""
                    val path = parts.getOrNull(1) ?: ""
                    if (path.isNotBlank()) "$volume/$path" else volume
                } else {
                    docId
                }
            }.getOrDefault(uriString)

        private const val SHARED_DOWNLOAD_RELATIVE_DIR = "Metrolist/Downloads"
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val MIN_RANGE_CHUNK_BYTES = 1024 * 1024L
        private const val PARALLEL_RANGE_DOWNLOADS = 4
        private const val MAX_RANGE_ATTEMPTS = 3
        private const val PROGRESS_STEP_BYTES = 256 * 1024L
        private const val MIN_AUDIO_BYTES = 1024L
        private const val MAX_AUDIO_BYTES = 512L * 1024 * 1024
        private const val MIN_FREE_SPACE_BYTES = 16L * 1024 * 1024
        private const val LEGACY_DOWNLOAD_DIRECTORY = "download"
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        private const val TAG = "AudioCacheManager"
    }
}

internal class DownloadTransferException(
    cause: Exception,
) : Exception(cause.message, cause)

class AudioDownloadControl {
    private enum class State { ACTIVE, PAUSED, CANCELLED }

    private val state = MutableStateFlow(State.ACTIVE)
    val isPaused: Boolean get() = state.value == State.PAUSED
    val isCancelled: Boolean get() = state.value == State.CANCELLED

    fun pause() {
        state.compareAndSet(State.ACTIVE, State.PAUSED)
    }

    fun resume() {
        state.compareAndSet(State.PAUSED, State.ACTIVE)
    }

    fun cancel() {
        state.value = State.CANCELLED
    }

    suspend fun awaitIfPaused() {
        if (isPaused) state.first { it != State.PAUSED }
    }

    internal suspend fun awaitCancellation() {
        state.first { it == State.CANCELLED }
    }
}

internal class DownloadSpaceBudget(
    private val reserveBytes: Long,
    private val usableSpace: () -> Long,
) {
    private var remainingBytes = 0L

    @Synchronized
    fun beforeWrite(bytes: Int) {
        if (remainingBytes < bytes) {
            remainingBytes = minOf(8L * 1024 * 1024, usableSpace() - reserveBytes - 1)
        }
        if (remainingBytes < bytes) throw InsufficientAudioStorageException()
        remainingBytes -= bytes
    }
}

internal class InsufficientAudioStorageException : IOException("Insufficient space for audio download")
