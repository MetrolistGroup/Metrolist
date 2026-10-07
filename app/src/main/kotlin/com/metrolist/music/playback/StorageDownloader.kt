/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.documentfile.provider.DocumentFile
import androidx.media3.exoplayer.offline.Download
import com.metrolist.innertube.YouTube
import com.metrolist.music.R
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.DownloadAudioFormat
import com.metrolist.music.constants.DownloadFormatKey
import com.metrolist.music.constants.DownloadStorageUriKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.IOException
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class StorageDownloader
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val downloadUtilProvider: Provider<DownloadUtil>,
) {
    private val TAG = "StorageDownloader"
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadSemaphore = Semaphore(2)

    private val streamHttpClient =
        OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }
            .build()

    fun downloadToStorage(mediaMetadata: MediaMetadata, folderUriString: String) {
        scope.launch {
            downloadSemaphore.withPermit {
                executeDownload(mediaMetadata, folderUriString)
            }
        }
    }

    private suspend fun executeDownload(mediaMetadata: MediaMetadata, folderUriString: String) {
        val notificationId = mediaMetadata.id.hashCode()
        val downloadUtil = downloadUtilProvider.get()

        try {
            val folderUri = Uri.parse(folderUriString)
            val folderDoc = DocumentFile.fromTreeUri(context, folderUri)
            if (folderDoc == null || !folderDoc.canWrite()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show()
                }
                downloadUtil.removeExternalDownload(mediaMetadata.id)
                return
            }

            // Register in DB as placeholder
            mediaMetadata.album?.let { album ->
                if (database.albumEntity(album.id) == null) {
                    database.insert(
                        AlbumEntity(
                            id = album.id,
                            title = album.title,
                            thumbnailUrl = mediaMetadata.thumbnailUrl,
                            songCount = 0,
                            duration = 0,
                        ),
                    )
                }
            }
            val existing = database.getSongByIdBlocking(mediaMetadata.id)
            if (existing == null) {
                database.insert(mediaMetadata)
            } else {
                database.update(
                    existing,
                    mediaMetadata,
                    overwriteTitle = false,
                    overwriteArtists = false,
                )
            }

            // Update in-app UI state ONCE to DOWNLOADING (indeterminate spinner)
            // Avoiding frequent state updates prevents expensive Compose recomposition loops
            downloadUtil.updateExternalDownloadProgress(mediaMetadata.id, Download.STATE_DOWNLOADING, 0L, 0L)
            showProgressNotification(notificationId, mediaMetadata.title, 0)

            // Extract high-quality playback stream
            val audioQuality = context.dataStore[AudioQualityKey].toEnum(AudioQuality.HIGH)
            val playbackData = InnerTubeXPlayer.playerResponseForPlayback(
                videoId = mediaMetadata.id,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
                allowBoundedRange = false,
            ).getOrThrow()

            // Resolve format, extension, and MIME type
            val formatSetting = context.dataStore[DownloadFormatKey]?.let {
                runCatching { DownloadAudioFormat.valueOf(it) }.getOrNull()
            } ?: DownloadAudioFormat.OPUS

            val isWebm = playbackData.format.mimeType.contains("webm", ignoreCase = true) ||
                    playbackData.format.mimeType.contains("opus", ignoreCase = true)

            val (extension, mimeType) = if (formatSetting == DownloadAudioFormat.M4A && !isWebm) {
                "m4a" to "audio/mp4"
            } else if (isWebm) {
                "opus" to "audio/opus"
            } else {
                "m4a" to "audio/mp4"
            }

            // Format: Song Name - Artist Name (without thumbnail)
            val artist = mediaMetadata.artists.joinToString(", ") { it.name }.trim()
            val title = mediaMetadata.title.trim()
            val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val safeArtist = artist.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
            val baseName = if (safeArtist.isNotBlank()) "$safeTitle - $safeArtist" else safeTitle
            val fileName = "$baseName.$extension"

            // Target file in SAF tree directory
            val existingFile = folderDoc.findFile(fileName)
            val targetDoc = existingFile ?: folderDoc.createFile(mimeType, fileName)
                ?: throw IOException("Failed to create $fileName in target folder")

            // Format stream URL with Range for full-speed download
            val actualContentLength = playbackData.format.contentLength?.takeIf { it > 0L }
            val streamUrl = if (actualContentLength != null && "&range=" !in playbackData.streamUrl) {
                "${playbackData.streamUrl}&range=0-${actualContentLength - 1}"
            } else {
                playbackData.streamUrl
            }

            val requestBuilder = Request.Builder().get().url(streamUrl)
            playbackData.streamHeaders.forEach { (name, value) ->
                requestBuilder.header(name, value)
            }

            // Stream DIRECTLY to target DocumentFile in a single pass to eliminate disk copy lag
            var totalWritten = 0L
            streamHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected HTTP response code: ${response.code}")
                }
                val body = response.body
                val totalLength = actualContentLength ?: body.contentLength().takeIf { it > 0 } ?: -1L

                context.contentResolver.openOutputStream(targetDoc.uri, "wt")?.let { rawOut ->
                    BufferedOutputStream(rawOut, 128 * 1024).use { outStream ->
                        body.byteStream().use { inStream ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            var lastNotificationTime = System.currentTimeMillis()

                            while (inStream.read(buffer).also { read = it } != -1) {
                                outStream.write(buffer, 0, read)
                                totalWritten += read
                                val now = System.currentTimeMillis()
                                // Throttle notification updates to at most once every 1.5 seconds to prevent SystemUI IPC lag
                                if (now - lastNotificationTime > 1500 && totalLength > 0) {
                                    val progress = ((totalWritten * 100) / totalLength).toInt().coerceIn(0, 100)
                                    showProgressNotification(notificationId, mediaMetadata.title, progress)
                                    lastNotificationTime = now
                                }
                            }
                            outStream.flush()
                        }
                    }
                } ?: throw IOException("Could not open output stream for ${targetDoc.uri}")
            }

            // Trigger MediaScanner broadcast so other players index the file
            try {
                val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, targetDoc.uri)
                context.sendBroadcast(mediaScanIntent)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "MediaScanner broadcast failed for ${targetDoc.uri}")
            }

            // Update local database record as downloaded
            database.updateDownloadedInfo(mediaMetadata.id, true, LocalDateTime.now())

            // Update in-app UI state ONCE to COMPLETED
            downloadUtil.updateExternalDownloadProgress(
                mediaMetadata.id,
                Download.STATE_COMPLETED,
                totalWritten,
                totalWritten,
            )
            showCompletedNotification(notificationId, mediaMetadata.title, fileName)

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error downloading ${mediaMetadata.title} to storage")
            downloadUtil.removeExternalDownload(mediaMetadata.id)
            showFailedNotification(notificationId, mediaMetadata.title)
        }
    }

    /**
     * Deletes the song from external storage / SD card when user taps "Remove download".
     */
    fun deleteFromStorage(songId: String) {
        scope.launch {
            try {
                val folderUriString = context.dataStore[DownloadStorageUriKey]
                if (folderUriString.isNullOrBlank()) {
                    downloadUtilProvider.get().removeExternalDownload(songId)
                    database.updateDownloadedInfo(songId, false, null)
                    return@launch
                }

                val folderUri = Uri.parse(folderUriString)
                val folderDoc = DocumentFile.fromTreeUri(context, folderUri)
                val song = database.getSongByIdBlocking(songId)

                if (song != null && folderDoc != null) {
                    val safeTitle = song.song.title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                    val safeArtist = song.artists.joinToString(", ") { it.name }.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                    val baseName1 = if (safeArtist.isNotBlank()) "$safeTitle - $safeArtist" else safeTitle
                    val baseName2 = if (safeArtist.isNotBlank()) "$safeArtist - $safeTitle" else safeTitle

                    val candidateNames = listOf(
                        "$baseName1.opus", "$baseName1.m4a", "$baseName1.webm",
                        "$baseName2.opus", "$baseName2.m4a", "$baseName2.webm",
                    )

                    for (candidate in candidateNames) {
                        folderDoc.findFile(candidate)?.let { fileDoc ->
                            try {
                                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, fileDoc.uri))
                            } catch (_: Exception) {}
                            fileDoc.delete()
                            Timber.tag(TAG).d("Deleted $candidate from storage")
                        }
                    }
                }

                database.updateDownloadedInfo(songId, false, null)
                downloadUtilProvider.get().removeExternalDownload(songId)

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error deleting song $songId from storage")
            }
        }
    }

    private fun showProgressNotification(notificationId: Int, title: String, progress: Int) {
        val notification = NotificationCompat.Builder(context, ExoDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.downloading_to_storage))
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun showCompletedNotification(notificationId: Int, title: String, fileName: String) {
        val notification = NotificationCompat.Builder(context, ExoDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.download_completed))
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun showFailedNotification(notificationId: Int, title: String) {
        val notification = NotificationCompat.Builder(context, ExoDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.download)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.download_failed))
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
