package com.metrolist.shared.playback

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.logging.MetrolistInnerTubeLogger
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@OptIn(ExperimentalSabrApi::class)
suspend fun downloadSabrAudio(
    resource: PlaybackResource,
    destination: File,
    awaitReady: suspend () -> Unit = {},
    onProgress: (Long, Long?) -> Unit = { _, _ -> },
    maxBytes: Long = 512L * 1024 * 1024,
) {
    val bootstrap = requireNotNull(resource.sabrBootstrap)
    val quality = runCatching { AudioQuality.valueOf(Settings.shared.audioQuality) }.getOrDefault(AudioQuality.AUTO)
    val chunks =
        renewingSabrDownload(
            resource,
            openStream = { current, positionMs ->
                SabrAudioStream(
                    GlobalYouTube.client,
                    checkNotNull(current.sabrBootstrap),
                    initialPlayerTimeMs = positionMs,
                    tokenSupplier = current.sabrTokenSupplier,
                    logger = MetrolistInnerTubeLogger,
                ).chunks()
            },
            renew = { current ->
                val resolver =
                    GlobalYouTubeRepository.streamResolver
                        ?: throw IncompleteAudioDownloadException("SABR download renewal is unavailable")
                resolver.resolveRemote(
                    current.videoId,
                    ContentHints(
                        wantVideo = false,
                        sabrFirst = true,
                        playbackClientOverrideId = current.profileId.removeSuffix("__nopo").removeSuffix("__po"),
                    ).withStreamCapabilities(allowHls = false, allowSabr = true, allowBoundedRange = true),
                    quality,
                    refresh = true,
                )
            },
        )
    writeSabrAudio(chunks, destination, bootstrap.contentLengthBytes ?: resource.contentLengthBytes, awaitReady, onProgress, maxBytes)
}

internal suspend fun writeSabrAudio(
    chunks: Flow<SabrChunk>,
    destination: File,
    contentLength: Long?,
    awaitReady: suspend () -> Unit = {},
    onProgress: (Long, Long?) -> Unit = { _, _ -> },
    maxBytes: Long = 512L * 1024 * 1024,
) {
    try {
        withContext(Dispatchers.IO) {
            destination.outputStream().buffered().use { output ->
                collectSabrAudio(chunks, contentLength, maxBytes, awaitReady = awaitReady, onProgress = onProgress) { _, bytes, length ->
                    if (destination.absoluteFile.parentFile!!.usableSpace <= length + 16L * 1024 * 1024) {
                        throw IOException("Insufficient space for audio download")
                    }
                    output.write(bytes, 0, length)
                }
            }
        }
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
}
