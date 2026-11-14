package com.metrolist.shared.storage

import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal const val IOS_QUARANTINED_DOWNLOAD_LOCATION = "IOS_APPLICATION_SUPPORT_QUARANTINED"

internal fun quarantineAudioDownload(entry: CachedAudioEntity): CachedAudioEntity =
    entry.copy(cacheLocation = IOS_QUARANTINED_DOWNLOAD_LOCATION)

internal fun isQuarantinedDownload(entry: CachedAudioEntity): Boolean =
    entry.kind == KIND_DOWNLOAD && entry.cacheLocation == IOS_QUARANTINED_DOWNLOAD_LOCATION

internal fun isAvailableDownload(entry: CachedAudioEntity): Boolean = entry.kind == KIND_DOWNLOAD && !isQuarantinedDownload(entry)

internal fun isAvailableCachedAudio(entry: CachedAudioEntity): Boolean = !isQuarantinedDownload(entry)

internal fun canUseDownloadedAudio(
    entry: CachedAudioEntity,
    fileIsValid: Boolean,
): Boolean = isAvailableDownload(entry) && fileIsValid

internal fun rebasedAudioPath(
    filePath: String,
    directory: String,
): String? = filePath.takeUnless { it.startsWith("$directory/") }?.let { "$directory/${it.substringAfterLast('/')}" }

internal fun shouldRetainAudioFile(
    entry: CachedAudioEntity,
    fileIsValid: Boolean,
): Boolean = isQuarantinedDownload(entry) || fileIsValid

internal fun shouldKeepDownloadInsteadOfCache(
    entry: CachedAudioEntity,
    fileIsValid: Boolean,
): Boolean = entry.kind == KIND_DOWNLOAD && shouldRetainAudioFile(entry, fileIsValid)

internal fun matchesAudioFile(
    entry: CachedAudioEntity,
    filePath: String,
): Boolean = entry.filePath == filePath

internal suspend fun <T> replaceAudioAfterCommit(
    previousPath: String?,
    commit: suspend () -> Pair<String, T>,
    deleteFile: (String) -> Unit,
): T =
    withContext(NonCancellable) {
        val (publishedPath, result) = commit()
        if (previousPath != null && previousPath != publishedPath) deleteFile(previousPath)
        result
    }

internal suspend fun removeAudioAfterFileDeletion(
    entry: CachedAudioEntity,
    deleteFile: (String) -> Boolean,
    removeMetadata: suspend (CachedAudioEntity) -> Unit,
): Boolean {
    if (!deleteFile(entry.filePath)) return false
    removeMetadata(entry)
    return true
}

internal fun safeAudioFileId(id: String): String =
    id
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.')
        .take(120)
        .ifBlank { "audio" }

internal fun canWriteAudioChunk(
    writtenBytes: Long,
    chunkBytes: Long,
    availableBytes: Long,
    reservedBytes: Long,
): Boolean {
    if (writtenBytes < 0L || chunkBytes <= 0L || availableBytes < 0L || reservedBytes < 0L) return false
    val writableBytes = (availableBytes - reservedBytes).coerceAtLeast(0L)
    return writtenBytes <= writableBytes && chunkBytes <= writableBytes - writtenBytes
}

internal fun isIosPlayableAudioMimeType(mimeType: String?): Boolean =
    mimeType?.substringBefore(';')?.lowercase()?.let {
        it in setOf("audio/mp4", "audio/aac", "audio/x-m4a", "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav")
    } == true

internal fun isCompleteAudioDownload(
    downloadedBytes: Long,
    expectedBytes: Long?,
): Boolean = downloadedBytes >= MINIMUM_AUDIO_BYTES && (expectedBytes == null || expectedBytes == downloadedBytes)

internal fun recoverInterruptedAudioDownload(state: AudioDownloadState): AudioDownloadState =
    if (state == AudioDownloadState.RUNNING) AudioDownloadState.QUEUED else state

internal fun cancelAudioDownload(state: AudioDownloadState): AudioDownloadState =
    if (state == AudioDownloadState.QUEUED || state == AudioDownloadState.RUNNING || state == AudioDownloadState.PAUSED) {
        AudioDownloadState.CANCELLED
    } else {
        state
    }

internal fun shouldKeepTrackedPlaylistRequest(state: AudioDownloadState): Boolean =
    state == AudioDownloadState.PAUSED || state == AudioDownloadState.CANCELLED

internal fun isTrustedAudioDownloadUrl(value: String): Boolean {
    val url = runCatching { Url(value) }.getOrNull() ?: return false
    return url.protocol == URLProtocol.HTTPS && url.port == 443 && url.user == null && url.password == null &&
        (
            url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com") ||
                url.host == "youtube.com" || url.host.endsWith(".youtube.com")
        ) &&
        url.encodedPath == "/videoplayback"
}

internal fun canFollowAudioDownloadRedirect(
    redirectCount: Int,
    targetUrl: String,
): Boolean = redirectCount in 0 until MAX_AUDIO_DOWNLOAD_REDIRECTS && isTrustedAudioDownloadUrl(targetUrl)

@Serializable
internal enum class AudioDownloadState {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    COMPLETED,
    CANCELLED,
}

internal const val MAX_AUDIO_DOWNLOAD_REDIRECTS = 5
internal const val MINIMUM_AUDIO_BYTES = 1_024L

internal fun totalAudioSize(sizes: Iterable<Long>): Long =
    sizes.fold(0L) { total, size ->
        val nonNegativeSize = size.coerceAtLeast(0L)
        if (Long.MAX_VALUE - total < nonNegativeSize) Long.MAX_VALUE else total + nonNegativeSize
    }
