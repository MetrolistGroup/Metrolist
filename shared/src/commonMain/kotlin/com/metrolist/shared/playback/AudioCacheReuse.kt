package com.metrolist.shared.playback

import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.youtube.playback.PlaybackResource

fun canReuseCachedAudio(
    resource: PlaybackResource,
    itag: Int?,
    mimeType: String?,
    codecs: String?,
    fileSize: Long,
): Boolean =
    resource.isDirectFile && resource.itag != null && resource.itag == itag &&
        resource.mimeType != null && resource.mimeType.substringBefore(';') == mimeType?.substringBefore(';') &&
        resource.codecs == codecs && fileSize >= 1024 && resource.contentLengthBytes == fileSize

internal fun hasSameStoredAudio(
    previous: CachedAudioEntity?,
    current: CachedAudioEntity?,
): Boolean =
    if (previous == null || current == null) {
        previous == current
    } else {
        previous.songId == current.songId && previous.filePath == current.filePath && previous.kind == current.kind &&
            previous.fileSize == current.fileSize && previous.mimeType == current.mimeType &&
            previous.cachedAt == current.cachedAt && previous.cacheLocation == current.cacheLocation
    }
