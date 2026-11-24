@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.metrolist.shared.ui.wrapped

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.fetchSabrAudio
import com.metrolist.shared.playback.iosPlaybackAudioQuality
import com.metrolist.shared.playback.isWebmOpus
import com.metrolist.shared.playback.opus.OpusMp4Remuxer
import com.metrolist.shared.playback.resolveIosAudioResource
import com.metrolist.shared.playback.withIosAudioCapabilities
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.storage.clearFragmentedMp4MovieDurations
import com.metrolist.shared.storage.downloadAudioWithRanges
import com.metrolist.shared.youtube.extraction.models.toContentHints
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForUpdatingAtPath
import platform.Foundation.seekToFileOffset
import platform.Foundation.truncateFileAtOffset

internal actual fun createWrappedAudioLibrary(onInterrupted: () -> Unit): WrappedAudioLibrary = IosWrappedAudioLibrary(onInterrupted)

private class IosWrappedAudioLibrary(
    onInterrupted: () -> Unit,
) : WrappedAudioLibrary {
    private val files = NSFileManager.defaultManager
    private val directory = "${NSTemporaryDirectory()}wrapped-${NSUUID.UUID().UUIDString}"
    private val closed = atomic(false)

    private val sessionObservers = mutableListOf<Any>()

    init {
        check(files.createDirectoryAtPath(directory, true, null, null))
        val center = NSNotificationCenter.defaultCenter
        try {
            sessionObservers +=
                center.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { notification ->
                    val type = notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber
                    if (!closed.value && type?.unsignedLongValue == AVAudioSessionInterruptionTypeBegan) onInterrupted()
                }
            sessionObservers +=
                center.addObserverForName(AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue) { notification ->
                    val reason = notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber
                    if (!closed.value && reason?.unsignedLongValue == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) onInterrupted()
                }
        } catch (error: Throwable) {
            sessionObservers.forEach { center.removeObserver(it) }
            files.removeItemAtPath(directory, null)
            throw error
        }
    }

    override suspend fun prepare(song: SongItem): String =
        withContext(Dispatchers.Default) {
            check(!closed.value)
            val resolver = checkNotNull(GlobalYouTubeRepository.streamResolver)
            val resource =
                resolveIosAudioResource(iosPlaybackAudioQuality(Settings.shared.audioQuality), forDownload = true) { quality, refresh, _ ->
                    val hints = song.toContentHints(wantVideo = false).withIosAudioCapabilities()
                    if (refresh) resolver.refresh(song.id, hints, quality, song) else resolver.resolve(song.id, hints, quality, song)
                }
            currentCoroutineContext().ensureActive()
            check(!closed.value && !resource.isLive)
            val path = "$directory/${NSUUID.UUID().UUIDString}.audio"
            check(files.createFileAtPath(path, null, null))
            val output = checkNotNull(NSFileHandle.fileHandleForUpdatingAtPath(path))
            var complete = false
            try {
                val available =
                    (files.attributesOfFileSystemForPath(directory, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
                val budget = minOf(WRAPPED_AUDIO_FILE_LIMIT, available - WRAPPED_AUDIO_RESERVE)
                val write: (Long, ByteArray, Int) -> Unit = { offset, bytes, length ->
                    check(!closed.value)
                    val free =
                        (files.attributesOfFileSystemForPath(directory, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
                    check(free >= length + WRAPPED_AUDIO_RESERVE)
                    output.seekToFileOffset(offset.toULong())
                    val data = bytes.usePinned { NSData.create(it.addressOf(0), length.toULong()) }
                    check(output.writeData(data, error = null))
                }
                if (resource.clientName == "local") {
                    val source = checkNotNull(NSURL.URLWithString(resource.url)?.takeIf { it.isFileURL() }?.path)
                    val input = checkNotNull(NSFileHandle.fileHandleForReadingAtPath(source))
                    try {
                        var written = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val data = checkNotNull(input.readDataUpToLength(65_536u, null))
                            if (data.length == 0uL) break
                            check(written + data.length.toLong() <= budget)
                            val bytes = checkNotNull(data.bytes).readBytes(data.length.toInt())
                            write(written, bytes, bytes.size)
                            written += bytes.size
                        }
                        check(written >= 1024 && (resource.contentLengthBytes == null || written == resource.contentLengthBytes))
                    } finally {
                        input.closeFile()
                    }
                } else if (resource.isSabr) {
                    fetchSabrAudio(GlobalYouTube.client, resource, budget, write = {
                        offset,
                        bytes,
                        length,
                        ->
                        write(offset, bytes, length)
                    })
                } else {
                    downloadAudioWithRanges(GlobalYouTube.client, resource, budget, write)
                }
                if (resource.isWebmOpus()) {
                    output.seekToFileOffset(0u)
                    val data = checkNotNull(output.readDataUpToLength(WRAPPED_AUDIO_FILE_LIMIT.toULong(), null))
                    val input = checkNotNull(data.bytes).readBytes(data.length.toInt())
                    val remuxed = OpusMp4Remuxer.remux(input)
                    output.truncateFileAtOffset(0u)
                    write(0L, remuxed, remuxed.size)
                }
                output.seekToFileOffset(0u)
                val headerData = checkNotNull(output.readDataUpToLength(65_536u, null))
                val header = headerData.bytes?.readBytes(headerData.length.toInt()) ?: ByteArray(0)
                if (clearFragmentedMp4MovieDurations(header)) write(0L, header, header.size)
                currentCoroutineContext().ensureActive()
                check(!closed.value)
                complete = true
                path
            } finally {
                output.closeFile()
                if (!complete) files.removeItemAtPath(path, null)
                if (closed.value) files.removeItemAtPath(directory, null)
            }
        }

    override fun close() {
        closed.value = true
        sessionObservers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        files.removeItemAtPath(directory, null)
    }
}
