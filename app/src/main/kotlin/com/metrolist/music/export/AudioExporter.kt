/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.os.StatFs
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.DownloadUtil
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.utils.enumPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * Saves a song as a regular audio file the user can find in a file manager.
 *
 * Bytes come from the offline download if there is one, otherwise from the player cache or the network, using the
 * same stream resolution as offline downloads ([DownloadUtil.createExportDataSourceFactory]).
 * Every step works on temporary files; the visible file is created only once its content is complete.
 */
@Singleton
class AudioExporter
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val downloadUtil: DownloadUtil,
) {
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder().header("Proxy-Authorization", auth).build()
                } ?: response.request
            }
            .build()
    }

    private val mutableStates = MutableStateFlow<Map<String, ExportState>>(emptyMap())

    /** Export state per song id. Finished entries stay until [clearState]. */
    val states: StateFlow<Map<String, ExportState>> = mutableStates.asStateFlow()

    fun clearState(songId: String) {
        mutableStates.update { it - songId }
    }

    /** Makes sure the song and its album exist in the database so tags and the stream can be resolved. */
    suspend fun ensureSongKnown(metadata: MediaMetadata) =
        withContext(Dispatchers.IO) {
            if (database.getSongById(metadata.id) != null) return@withContext
            metadata.album?.let { album ->
                if (database.albumEntity(album.id) == null) {
                    database.insert(
                        AlbumEntity(
                            id = album.id,
                            title = album.title,
                            thumbnailUrl = metadata.thumbnailUrl,
                            songCount = 0,
                            duration = 0,
                        ),
                    )
                }
            }
            database.insert(metadata)
        }

    /** Works out the file name and whether it is already taken, so the UI can ask before replacing. */
    suspend fun plan(request: ExportRequest): ExportPlan =
        withContext(Dispatchers.IO) {
            val song = loadSong(request.songId)
            val extension =
                when (request.format) {
                    ExportFormat.MP3 -> "mp3"
                    ExportFormat.ORIGINAL -> {
                        val format = song.format?.let { it.mimeType to it.codecs } ?: resolveFormat(request.songId)
                        val container = AudioSniffer.fromMimeType(format?.first)
                        // Unknown until the stream is fetched; M4A is by far the most common source.
                        AudioSniffer.originalExtension(
                            if (container == SourceContainer.UNKNOWN) SourceContainer.MP4 else container,
                            format?.second,
                        )
                    }
                }
            val fileName = ExportFileNaming.build(song.orderedArtists.map { it.name }, song.song.title, extension)
            val exists = ExportSink.create(context, request.destination).exists(fileName)
            ExportPlan(fileName = fileName, extension = extension, alreadyExists = exists)
        }

    /**
     * Runs the export. Throws [ExportException] on failure and rethrows [CancellationException] on cancel; the
     * outcome is also published in [states].
     */
    suspend fun export(request: ExportRequest): ExportResult {
        val id = request.songId
        publish(id, ExportState.Preparing)
        try {
            val result = withContext(Dispatchers.IO) { doExport(request) }
            publish(id, ExportState.Done(result))
            return result
        } catch (cancelled: CancellationException) {
            publish(id, ExportState.Cancelled)
            throw cancelled
        } catch (failure: ExportException) {
            Timber.tag(TAG).w(failure, "Export of %s failed", id)
            publish(id, ExportState.Failed(failure.error))
            throw failure
        } catch (failure: Throwable) {
            Timber.tag(TAG).e(failure, "Export of %s failed unexpectedly", id)
            val error = if (failure.isNoSpace()) ExportError.InsufficientSpace(0) else ExportError.WriteFailed
            publish(id, ExportState.Failed(error))
            throw ExportException(error, failure)
        }
    }

    private suspend fun doExport(request: ExportRequest): ExportResult {
        val id = request.songId
        val song = loadSong(id)
        val sink = ExportSink.create(context, request.destination)

        val workDir = File(context.cacheDir, WORK_DIR).apply { mkdirs() }
        deleteStaleWorkFiles(workDir)
        val source = File.createTempFile("src-", ".part", workDir)
        var prepared: File? = null
        try {
            downloadToFile(id, source, workDir)

            val container = AudioSniffer.detect(source)
            if (container == SourceContainer.UNKNOWN) throw ExportException(ExportError.UnsupportedSource)

            val warnings = mutableListOf<ExportWarning>()
            val cover = loadCover(song)
            if (cover == null) warnings += ExportWarning.COVER_UNAVAILABLE
            val tags = buildTags(song, cover)

            prepared = File.createTempFile("out-", ".part", workDir)
            val extension =
                when (request.format) {
                    ExportFormat.MP3 -> {
                        publish(id, ExportState.Converting(0f))
                        try {
                            FileOutputStream(prepared).buffered().use { out ->
                                Mp3Transcoder.transcode(source, out, tags) { progress ->
                                    publish(id, ExportState.Converting(progress))
                                }
                            }
                        } catch (error: IOException) {
                            if (error.isNoSpace()) throw ExportException(ExportError.InsufficientSpace(0), error)
                            throw ExportException(ExportError.TranscodeFailed, error)
                        }
                        "mp3"
                    }
                    ExportFormat.ORIGINAL -> prepareOriginal(container, song, source, prepared, tags, warnings)
                }

            publish(id, ExportState.Writing)
            val fileName = ExportFileNaming.build(song.orderedArtists.map { it.name }, song.song.title, extension)
            val target = sink.create(fileName, ExportSink.mimeTypeFor(extension), request.conflictPolicy)
            try {
                copyToSink(prepared, target.stream)
                target.commit()
            } catch (error: Throwable) {
                withContext(NonCancellable) { target.abort() }
                throw when {
                    error is CancellationException -> error
                    error is ExportException -> error
                    error.isNoSpace() -> ExportException(ExportError.InsufficientSpace(prepared.length()), error)
                    error is SecurityException -> ExportException(ExportError.AccessDenied, error)
                    else -> ExportException(ExportError.WriteFailed, error)
                }
            }

            return ExportResult(
                displayName = target.displayName,
                location = target.location,
                uri = target.uri.toString(),
                format = request.format,
                warnings = warnings,
            )
        } finally {
            withContext(NonCancellable) {
                source.delete()
                prepared?.delete()
            }
        }
    }

    /**
     * Original-format export: no re-encoding. M4A gets tags in place, Opus is re-wrapped from WebM into Ogg.
     * Any container we cannot tag is saved untouched under its real extension.
     * Returns the extension of [prepared].
     */
    private fun prepareOriginal(
        container: SourceContainer,
        song: Song,
        source: File,
        prepared: File,
        tags: ExportTags,
        warnings: MutableList<ExportWarning>,
    ): String {
        when (container) {
            SourceContainer.MP4 -> {
                try {
                    FileOutputStream(prepared).buffered().use { Mp4TagWriter.write(source, it, tags) }
                    return "m4a"
                } catch (error: IOException) {
                    if (error.isNoSpace()) throw ExportException(ExportError.InsufficientSpace(source.length()), error)
                    Timber.tag(TAG).w(error, "Could not write MP4 tags for %s", song.id)
                    warnings += ExportWarning.TAGS_FAILED
                    source.copyTo(prepared, overwrite = true)
                    return "m4a"
                }
            }
            SourceContainer.WEBM -> {
                if (WebmOpusRemuxer.canRemux(source)) {
                    try {
                        FileOutputStream(prepared).buffered().use { WebmOpusRemuxer.remux(source, it, tags) }
                        return "opus"
                    } catch (error: IOException) {
                        if (error.isNoSpace()) throw ExportException(ExportError.InsufficientSpace(source.length()), error)
                        Timber.tag(TAG).w(error, "Opus remux failed for %s", song.id)
                        warnings += ExportWarning.TAGS_FAILED
                    }
                } else {
                    warnings += ExportWarning.TAGS_NOT_SUPPORTED
                }
                source.copyTo(prepared, overwrite = true)
                return "webm"
            }
            SourceContainer.UNKNOWN -> throw ExportException(ExportError.UnsupportedSource)
        }
    }

    private suspend fun downloadToFile(
        songId: String,
        target: File,
        workDir: File,
    ) {
        var retriedExpired = false
        while (true) {
            try {
                downloadOnce(songId, target, workDir)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ExportException) {
                throw error
            } catch (error: Throwable) {
                val expired =
                    error.causes().any {
                        it is HttpDataSource.InvalidResponseCodeException && it.responseCode in EXPIRED_STREAM_CODES
                    }
                if (expired && !retriedExpired) {
                    retriedExpired = true
                    downloadUtil.invalidateStreamUrl(songId)
                    continue
                }
                throw ExportException(classifyDownloadError(error, expired), error)
            }
        }
    }

    private suspend fun downloadOnce(
        songId: String,
        target: File,
        workDir: File,
    ) {
        val dataSource = downloadUtil.createExportDataSourceFactory().createDataSource()
        try {
            val opened = dataSource.open(DataSpec.Builder().setUri(songId.toUri()).setKey(songId).build())
            val total =
                opened.takeIf { it != C.LENGTH_UNSET.toLong() && it > 0 }
                    ?: database.format(songId).first()?.contentLength?.takeIf { it > 0 }
            if (total != null) ensureFreeSpace(workDir, total * 2 + SPACE_MARGIN_BYTES)

            var written = 0L
            var lastReported = -1
            FileOutputStream(target).buffered().use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, read)
                    written += read
                    val percent = if (total != null) (written * 100 / total).toInt() else -1
                    if (percent != lastReported) {
                        lastReported = percent
                        publish(songId, ExportState.Downloading(if (total != null) written.toFloat() / total else null))
                    }
                }
            }
            if (written == 0L || (total != null && written != total)) throw ExportException(ExportError.Incomplete)
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun classifyDownloadError(
        error: Throwable,
        expired: Boolean,
    ): ExportError =
        when {
            error.isNoSpace() -> ExportError.InsufficientSpace(0)
            expired -> ExportError.StreamExpired
            error.causes().any {
                it is UnknownHostException || it is SocketException || it is SSLException ||
                    it is java.net.SocketTimeoutException || it is HttpDataSource.HttpDataSourceException
            } -> ExportError.Network
            error is IOException -> ExportError.Network
            // The resolver threw: no playable stream could be found for this id.
            else -> ExportError.NoStream
        }

    private suspend fun resolveFormat(songId: String): Pair<String, String>? =
        runCatching {
            val song = database.songEntity(songId)
            val data =
                InnerTubeXPlayer.playerResponseForPlayback(
                    songId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    contentHints = ContentHints(isExplicit = song?.explicit, isUploaded = song?.isUploaded),
                    allowBoundedRange = false,
                ).getOrThrow()
            val mime = data.format.mimeType
            mime.substringBefore(";") to
                mime.substringAfter("codecs=", "").substringBefore(";").trim().removeSurrounding("\"")
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    private suspend fun loadSong(songId: String): Song =
        database.getSongById(songId) ?: throw ExportException(ExportError.NoStream)

    private fun buildTags(
        song: Song,
        cover: ExportTags.Cover?,
    ) = ExportTags(
        title = song.song.title,
        artist = song.orderedArtists.joinToString(", ") { it.name }.ifBlank { null },
        album = (song.album?.title ?: song.song.albumName)?.ifBlank { null },
        year = song.song.year ?: song.album?.year,
        cover = cover,
    )

    private suspend fun loadCover(song: Song): ExportTags.Cover? {
        val url = (song.song.thumbnailUrl ?: song.album?.thumbnailUrl)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val bytes =
                httpClient.newCall(Request.Builder().url(url.resize(COVER_SIZE_PX, COVER_SIZE_PX)).build()).execute().use { response ->
                    if (!response.isSuccessful) return null
                    response.body.byteStream().readLimited(MAX_COVER_BYTES)
                } ?: return null
            decodeCover(bytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Could not load cover for %s", song.id)
            null
        }
    }

    private fun decodeCover(bytes: ByteArray): ExportTags.Cover? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return when (bounds.outMimeType) {
            "image/jpeg", "image/png" -> ExportTags.Cover(bytes, bounds.outMimeType!!, bounds.outWidth, bounds.outHeight)
            else -> {
                // WebP and others are poorly supported by tag readers; store JPEG instead.
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                val cover = ExportTags.Cover(out.toByteArray(), "image/jpeg", bitmap.width, bitmap.height)
                bitmap.recycle()
                cover
            }
        }
    }

    private suspend fun copyToSink(
        file: File,
        out: OutputStream,
    ) {
        out.use { stream ->
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    stream.write(buffer, 0, read)
                }
            }
            stream.flush()
        }
    }

    private fun ensureFreeSpace(
        dir: File,
        required: Long,
    ) {
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrNull() ?: return
        if (free < required) throw ExportException(ExportError.InsufficientSpace(required))
    }

    private fun deleteStaleWorkFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_WORK_FILE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun publish(
        songId: String,
        state: ExportState,
    ) {
        mutableStates.update { it + (songId to state) }
    }

    private fun InputStream.readLimited(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "AudioExporter"
        private const val WORK_DIR = "audio_export"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val SPACE_MARGIN_BYTES = 4L * 1024 * 1024
        private const val STALE_WORK_FILE_MS = 60L * 60 * 1000
        private const val COVER_SIZE_PX = 1000
        private const val MAX_COVER_BYTES = 8 * 1024 * 1024
        private val EXPIRED_STREAM_CODES = setOf(403, 410, 416)
    }
}

internal fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.take(10)

internal fun Throwable.isNoSpace(): Boolean =
    causes().any {
        val message = it.message.orEmpty()
        message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)
    }
