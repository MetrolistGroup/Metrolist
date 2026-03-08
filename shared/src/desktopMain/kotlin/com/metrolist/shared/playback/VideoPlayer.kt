package com.metrolist.shared.playback

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.playback.ffmpeg.FFmpegFrameGrabber
import com.metrolist.shared.playback.ffmpeg.Frame
import com.metrolist.shared.playback.ffmpeg.HttpRangeInputStream
import com.metrolist.shared.playback.ffmpeg.LoopingVideoInput
import com.metrolist.shared.playback.ffmpeg.PlaybackPositionInput
import com.metrolist.shared.playback.ffmpeg.isTrustedDirectMediaEndpoint
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLevel
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.isLiveStreamUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.bytedeco.ffmpeg.global.avutil.AVCOL_RANGE_JPEG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT2020_CL
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT2020_NCL
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT470BG
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_BT709
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_SMPTE170M
import org.bytedeco.ffmpeg.global.avutil.AVCOL_SPC_SMPTE240M
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV21
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUVJ420P
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class VideoPixelFormat {
    NV12,
    YUV420P,
    BGRA,
}

enum class VideoColorSpace {
    BT709,
    BT601,
    BT2020,
}

enum class VideoColorRange {
    LIMITED,
    FULL,
}

actual class VideoFrame(
    val width: Int,
    val height: Int,
    val format: VideoPixelFormat,
    val colorSpace: VideoColorSpace = VideoColorSpace.BT709,
    val colorRange: VideoColorRange = VideoColorRange.LIMITED,
    val planeY: Bitmap? = null,
    val planeU: Bitmap? = null,
    val planeV: Bitmap? = null,
    val planeUV: Bitmap? = null,
    val bgraBitmap: Bitmap? = null,
    val timestampMs: Long = 0L,
    private val imageLock: Any = Any(),
) {
    private var closed = false

    fun drawSafely(block: VideoFrame.() -> Unit): Boolean =
        synchronized(imageLock) {
            if (closed) {
                false
            } else {
                block()
                true
            }
        }

    fun close() {
        synchronized(imageLock) {
            if (closed) return
            closed = true
        }
    }
}

actual class VideoPlayer actual constructor(
    private val onFrame: (VideoFrame) -> Unit,
    private val onEnded: () -> Unit,
    private val onSeekLanded: ((Long) -> Unit)?,
    private val onVideoSizeChanged: (width: Int, height: Int) -> Unit,
    private val onRenderedFirstFrame: () -> Unit,
    private val looping: Boolean,
) {
    private var grabber: FFmpegFrameGrabber? = null
    private var seekableSource: SeekableMediaSource? = null

    var currentUrl: String? = null
        private set

    var onStreamExpired: ((Int?) -> Unit)? = null

    fun getBufferedPositionMs(overrideDurationMs: Long = 0L): Long {
        val dur =
            overrideDurationMs.takeIf { it > 0L }
                ?: cachedDurationMs
        val source = seekableSource
        if (source != null) {
            val totalSize = source.mediaSize ?: 0L
            if (totalSize > 0L && dur > 0L) {
                val bufferedBytes = source.bufferedBytePosition
                val fraction = (bufferedBytes.toDouble() / totalSize.toDouble()).coerceIn(0.0, 1.0)
                return (fraction * dur).toLong().coerceIn(0L, dur)
            }
        }
        val videoPts = lastVideoPtsMs
        return if (dur > 0L) videoPts.coerceIn(0L, dur) else videoPts
    }

    @Volatile
    private var isRunning = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var pendingSeekMs: Long? = null

    @Volatile
    private var positionProvider: () -> Long = { 0L }

    @Volatile
    private var volume = 1.0f

    @Volatile
    private var playbackSpeed = 1.0f

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val clockLock = Object()

    @Volatile
    private var pausedPositionMs = 0L

    @Volatile
    private var loopClock = LoopClockAnchor()

    private val loopingTimeline = LoopingVideoTimeline()
    private val liveTimeline = LiveVideoTimeline()

    private val isLiveStream: Boolean
        get() = currentUrl?.let { isLiveStreamUrl(it) } == true

    actual var audioPtsProvider: (() -> Long?)? = null

    private fun normalizeTimelinePts(rawPtsMs: Long): Long {
        if (looping) return loopingTimeline.normalize(rawPtsMs)
        val audioPts = audioPtsProvider?.invoke()
        if (audioPts != null && audioPts > 0L) {
            return liveTimeline.normalizeWithAudioPts(rawPtsMs, audioPts, audioClock())
        }
        if (isLiveStream || liveTimeline.isTracking || abs(rawPtsMs - audioClock()) > 5000L) {
            return liveTimeline.normalize(rawPtsMs, audioClock())
        }
        return rawPtsMs
    }

    private fun resetLoopClock(positionMs: Long) {
        loopClock = LoopClockAnchor(positionMs.coerceAtLeast(0L), System.nanoTime())
    }

    private fun resetMasterClock(initialMs: Long = 0L) {
        synchronized(clockLock) {
            pausedPositionMs = initialMs
        }
    }

    private var decodeThread: Thread? = null

    @Volatile
    private var targetWidth = 0

    @Volatile
    private var targetHeight = 0

    @Volatile
    private var nativeVideoWidth = 0

    @Volatile
    private var nativeVideoHeight = 0

    @Volatile
    private var requestedTargetWidth = 0

    @Volatile
    private var requestedTargetHeight = 0

    @Volatile
    private var requestedFillViewport = false

    @Volatile
    private var loadJobId = 0L

    private val lifecycleLock = Any()
    private val prepareLock = Any()
    private var teardownJob: Job? = null
    private var disposed = false

    @Volatile
    private var currentVideoId: String = ""

    @Volatile
    private var currentCpn: String = ""

    @Volatile
    private var decodedFramesCount = 0L

    @Volatile
    private var renderedFramesCount = 0L

    @Volatile
    private var droppedFramesCount = 0L

    @Volatile
    private var lastRenderDurationNanos = 0L

    @Volatile
    private var lastVideoPtsMs = 0L

    @Volatile
    private var streamSourceDescription = ""

    @Volatile
    private var cachedVideoCodec = ""

    @Volatile
    private var cachedAudioCodec = ""

    @Volatile
    private var cachedContainerFormat = ""

    @Volatile
    private var cachedNativeResolution = ""

    @Volatile
    private var cachedTargetFps = 0.0

    @Volatile
    private var cachedPixelFormat = ""

    @Volatile
    private var cachedColorSpace = ""

    @Volatile
    private var cachedDurationMs = 0L

    @Volatile
    private var cachedVideoBitrateKbps = 0L

    @Volatile
    private var cachedAudioBitrateKbps = 0L

    @Volatile
    private var cachedSampleRate = 0

    @Volatile
    private var cachedAudioChannels = 0

    private val frameRenderTimestamps = ArrayDeque<Long>(120)
    private val renderDurations = ArrayDeque<Long>(60)

    private fun generateCpn(): String {
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val random =
            java.util.concurrent.ThreadLocalRandom
                .current()
        return (1..16)
            .map { allowedChars[random.nextInt(allowedChars.length)] }
            .joinToString("")
    }

    actual val hasOwnAudio: Boolean = false

    actual fun getStats(): VideoPlayerStats {
        val now = System.nanoTime()
        val currentFps =
            synchronized(frameRenderTimestamps) {
                val cutoff = now - 1_000_000_000L
                while (frameRenderTimestamps.isNotEmpty() && frameRenderTimestamps.first() < cutoff) {
                    frameRenderTimestamps.removeFirst()
                }
                frameRenderTimestamps.size.toDouble()
            }
        val avgRenderDurationMs =
            synchronized(renderDurations) {
                if (renderDurations.isEmpty()) 0.0 else renderDurations.average() / 1_000_000.0
            }

        val masterClock = audioClock()
        val pts = lastVideoPtsMs
        val syncOffset = masterClock - pts
        val mediaSize = seekableSource?.mediaSize ?: 0L
        val mediaPos = seekableSource?.mediaPosition ?: 0L
        val resStr = cachedNativeResolution
        val tFps = cachedTargetFps

        return VideoPlayerStats(
            videoId = currentVideoId,
            cpn = currentCpn,
            videoCodec = cachedVideoCodec,
            audioCodec = cachedAudioCodec,
            containerFormat = cachedContainerFormat,
            videoResolution = resStr,
            optimalResolution = if (targetWidth > 0) "${targetWidth}x$targetHeight" else "",
            viewportResolution = if (requestedTargetWidth > 0) "${requestedTargetWidth}x$requestedTargetHeight" else "",
            fps = currentFps,
            targetFps = tFps,
            videoBitrateKbps = cachedVideoBitrateKbps,
            audioBitrateKbps = cachedAudioBitrateKbps,
            audioSampleRate = cachedSampleRate,
            audioChannels = cachedAudioChannels,
            pixelFormat = cachedPixelFormat,
            colorSpace = cachedColorSpace,
            colorConversion = "",
            decodedFrames = decodedFramesCount,
            renderedFrames = renderedFramesCount,
            droppedFrames = droppedFramesCount,
            videoPtsMs = pts,
            audioClockMs = masterClock,
            avSyncOffsetMs = syncOffset,
            renderDurationMs = avgRenderDurationMs.takeIf { it > 0.0 } ?: (lastRenderDurationNanos / 1_000_000.0),
            streamSource = streamSourceDescription,
            mediaSizeBytes = mediaSize,
            streamPositionBytes = mediaPos,
            bufferHealthMs = 0L,
            volume = volume,
            isPaused = isPaused,
            isResolving = !isRunning && loadJobId != 0L,
        )
    }

    actual fun load(
        url: String,
        headers: Map<String, String>,
        positionProvider: () -> Long,
    ): Boolean {
        if (url.startsWith("sabr-video://", ignoreCase = true)) {
            return false
        }
        currentVideoId = Regex("[?&]v=([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1) ?: ""
        val useRangeStream = shouldUseRangeStreamForVideoUrl(url)
        streamSourceDescription =
            when {
                useRangeStream -> "HTTP Range (Direct Chunked)"
                url.startsWith("http", ignoreCase = true) -> "HTTP URL Stream"
                else -> "Local File Stream"
            }
        var rangeInput: HttpRangeInputStream? = null
        val grabberInput: SeekableMediaSource? =
            if (useRangeStream) {
                val clen =
                    Regex("[?&]clen=([0-9]+)")
                        .find(url)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                HttpRangeInputStream(
                    url = url,
                    requestHeaders = headers,
                    mediaSize = clen ?: 0L,
                    chunkSize = 4 * 1024 * 1024,
                    prefetchAhead = true,
                    onStreamExpired = { status ->
                        synchronized(lifecycleLock) {
                            if (seekableSource === rangeInput) onStreamExpired?.invoke(status)
                        }
                    },
                ).also { rangeInput = it }
            } else if (looping && url.toHttpUrlOrNull()?.encodedPath?.let {
                    it.endsWith(".mp4", ignoreCase = true) || it.endsWith(".webm", ignoreCase = true) ||
                        it.endsWith(".m3u8", ignoreCase = true)
                } == true
            ) {
                LoopingVideoInput(url, headers)
            } else {
                null
            }
        return prepare(positionProvider, grabberInput, url) {
            val useInput = grabberInput != null && (grabberInput !is LoopingVideoInput || grabberInput.prepare())
            if (useInput) {
                FFmpegFrameGrabber(grabberInput as InputStream).apply {
                    options["hwaccel"] = "auto"
                    options["probesize"] = "500000"
                    options["analyzeduration"] = "500000"
                    videoOptions["threads"] = OPTIMAL_VIDEO_THREADS
                    videoOptions["flags"] = "low_delay"
                    imageScalingFlags = 1
                }
            } else {
                FFmpegFrameGrabber(url).apply {
                    options["hwaccel"] = "auto"
                    videoOptions["threads"] = OPTIMAL_VIDEO_THREADS
                    videoOptions["flags"] = "low_delay"
                    imageScalingFlags = 1
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        options["reconnect"] = "1"
                        options["reconnect_streamed"] = "1"
                        options["reconnect_delay_max"] = "5"
                        options["timeout"] = "15000000"
                        options["rw_timeout"] = "15000000"
                        options["user_agent"] =
                            headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                        headers
                            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
                            .takeIf { it.isNotEmpty() }
                            ?.entries
                            ?.joinToString(separator = "\r\n", postfix = "\r\n") { "${it.key}: ${it.value}" }
                            ?.let { options["headers"] = it }

                        options["probesize"] = "500000"
                        options["analyzeduration"] = "500000"
                    }
                }
            }
        }
    }

    actual fun load(
        source: SeekableMediaSource,
        positionProvider: () -> Long,
    ): Boolean {
        val input = source as? InputStream
        if (input == null) {
            source.close()
            return false
        }
        streamSourceDescription = if (source is HttpRangeInputStream) "HTTP Range (Direct Chunked)" else "SABR Video Stream"
        return prepare(positionProvider, source, null) {
            FFmpegFrameGrabber(input).apply {
                options["hwaccel"] = "auto"
                options["probesize"] = "500000"
                options["analyzeduration"] = "500000"
                videoOptions["threads"] = OPTIMAL_VIDEO_THREADS
                videoOptions["flags"] = "low_delay"
                imageScalingFlags = 1
            }
        }
    }

    private fun prepare(
        positionProvider: () -> Long,
        source: SeekableMediaSource?,
        mediaUrl: String?,
        createGrabber: () -> FFmpegFrameGrabber,
    ): Boolean =
        synchronized(prepareLock) {
            val retirement: Job
            val currentLoadJobId =
                synchronized(lifecycleLock) {
                    if (disposed) {
                        runCatching { source?.close() }
                        return false
                    }
                    retirement = closeAsync()
                    loadJobId = nextLoadJobId(loadJobId)
                    seekableSource = source
                    loadJobId
                }
            runBlocking { retirement.join() }
            synchronized(lifecycleLock) {
                if (loadJobId != currentLoadJobId) return false
                this.positionProvider = positionProvider
                resetMasterClock(0L)
                resetLoopClock(0L)
                loopingTimeline.reset()
                liveTimeline.reset()
                currentCpn = generateCpn()
                decodedFramesCount = 0L
                renderedFramesCount = 0L
                droppedFramesCount = 0L
                lastVideoPtsMs = 0L
                synchronized(frameRenderTimestamps) { frameRenderTimestamps.clear() }
                synchronized(renderDurations) { renderDurations.clear() }
            }
            var preparedGrabber: FFmpegFrameGrabber? = null
            return try {
                val g = createGrabber().also { preparedGrabber = it }
                if (loadJobId != currentLoadJobId) return false
                g.apply {
                    pixelFormat = AV_PIX_FMT_BGRA
                    audioStream = -2
                    start()
                }
                if (loadJobId != currentLoadJobId) {
                    return false
                }
                if (!g.hasVideo()) {
                    PlaybackEventLog.record(
                        level = PlaybackEventLevel.ERROR,
                        kind = PlaybackEventKind.PLAYER,
                        message = "[VideoPlayer] Prepared stream contains no video",
                    )
                    Logger.w("VideoPlayer", "prepare failed: stream has no video")
                    return false
                }
                val nativeWidth = g.imageWidth
                val nativeHeight = g.imageHeight
                val isHw = g.isHardwareAccelerated
                val hwName = g.hardwareDeviceName
                val hwStr =
                    if (isHw && !hwName.isNullOrBlank()) {
                        " (HW: $hwName)"
                    } else if (isHw) {
                        " (HW)"
                    } else {
                        ""
                    }
                val accepted =
                    synchronized(lifecycleLock) {
                        if (loadJobId != currentLoadJobId) {
                            false
                        } else {
                            targetWidth = nativeWidth
                            targetHeight = nativeHeight
                            nativeVideoWidth = nativeWidth
                            nativeVideoHeight = nativeHeight
                            cachedVideoCodec = (g.videoCodecName ?: "") + hwStr
                            cachedAudioCodec = g.audioCodecName ?: ""
                            cachedContainerFormat = g.formatName ?: ""
                            cachedNativeResolution = if (nativeWidth > 0) "${nativeWidth}x$nativeHeight" else ""
                            cachedTargetFps = g.frameRate
                            cachedPixelFormat = g.rawPixelFormatName ?: ""
                            cachedColorSpace = g.colorSpaceName ?: ""
                            cachedDurationMs = g.getLengthInTime() / 1000L
                            cachedVideoBitrateKbps = g.videoBitrate / 1000L
                            cachedAudioBitrateKbps = g.audioBitrate / 1000L
                            cachedSampleRate = g.sampleRate
                            cachedAudioChannels = g.audioChannels
                            grabber = g
                            currentUrl = mediaUrl
                            true
                        }
                    }
                if (!accepted) return false
                preparedGrabber = null
                synchronized(lifecycleLock) {
                    if (loadJobId != currentLoadJobId || grabber !== g) return false
                    onVideoSizeChanged(nativeWidth, nativeHeight)
                }
                Logger.i(
                    "VideoPlayer",
                    "prepare success: codec=$cachedVideoCodec res=$cachedNativeResolution" +
                        " target=${nativeWidth}x$nativeHeight fps=$cachedTargetFps",
                )
                true
            } catch (e: Exception) {
                if (loadJobId == currentLoadJobId) {
                    PlaybackEventLog.error(PlaybackEventKind.PLAYER, "VideoPlayer", "Video preparation failed", e)
                    Logger.w("VideoPlayer", "prepare failed type=${e::class.simpleName ?: "unknown"}")
                }
                synchronized(lifecycleLock) {
                    if (loadJobId == currentLoadJobId) close()
                }
                false
            } finally {
                runCatching { preparedGrabber?.release() }
            }
        }

    actual fun play(startPaused: Boolean): Unit =
        synchronized(lifecycleLock) {
            if (decodeThread?.isAlive == true) return
            val g = grabber ?: return
            if (looping) {
                resetLoopClock(0L)
                loopingTimeline.reset()
                resetMasterClock(0L)
            } else {
                liveTimeline.reset()
            }
            val playbackLoadJobId = loadJobId
            val playbackSource = seekableSource
            val currentPositionProvider = positionProvider
            isRunning = true
            isPaused = startPaused
            decodeThread =
                Thread {
                    var initialVideoFrameRendered = false
                    try {
                        val startMs = currentPositionProvider()
                        Logger.i("VideoPlayer", "decodeThread started: isPaused=$isPaused startMs=${startMs}ms")
                        if (isRunning) {
                            playbackSource?.updatePlaybackPosition(startMs)
                            if (startMs > INITIAL_SEEK_MIN_MS) {
                                try {
                                    val target = (startMs - INITIAL_SEEK_LEAD_MS).coerceAtLeast(0L)
                                    Logger.d("VideoPlayer", "Performing initial seek to ${target}ms")
                                    g.setTimestamp(target * 1000, false)
                                    val landedMs = (g.timestamp / 1000).takeIf { it > 0 } ?: target
                                    Logger.d("VideoPlayer", "Initial seek landed at ${landedMs}ms")
                                    synchronized(lifecycleLock) {
                                        if (loadJobId == playbackLoadJobId) onSeekLanded?.invoke(landedMs)
                                    }
                                } catch (e: Exception) {
                                    PlaybackEventLog.error(PlaybackEventKind.PLAYER, "VideoPlayer", "Initial video seek failed", e)
                                    Logger.w("VideoPlayer", "Initial seek failed type=${e::class.simpleName ?: "unknown"}")
                                }
                            }
                            if (looping) resetLoopClock(startMs)
                            resetMasterClock(startMs)
                        }
                        while (isRunning) {
                            val seekTarget =
                                synchronized(clockLock) {
                                    while (isRunning && isPaused && initialVideoFrameRendered && pendingSeekMs == null) {
                                        clockLock.wait()
                                    }
                                    pendingSeekMs.also { pendingSeekMs = null }
                                }
                            if (!isRunning) break
                            if (seekTarget != null) {
                                Logger.d("VideoPlayer", "Processing user seek to ${seekTarget}ms")
                                playbackSource?.updatePlaybackPosition(seekTarget)
                                var landedMs = seekTarget
                                try {
                                    g.setTimestamp(seekTarget * 1000, false)
                                    landedMs = (g.timestamp / 1000).takeIf { it > 0 } ?: seekTarget
                                    Logger.d("VideoPlayer", "User seek landed at ${landedMs}ms")
                                } catch (e: Exception) {
                                    PlaybackEventLog.error(PlaybackEventKind.PLAYER, "VideoPlayer", "Video seek failed", e)
                                    Logger.w("VideoPlayer", "Seek failed type=${e::class.simpleName ?: "unknown"}")
                                }
                                synchronized(lifecycleLock) {
                                    if (loadJobId == playbackLoadJobId) onSeekLanded?.invoke(landedMs)
                                }
                                if (looping) resetLoopClock(landedMs)
                                liveTimeline.reset()
                                resetMasterClock(landedMs)
                                initialVideoFrameRendered = false
                            }
                            var frame = g.grabFrame(false, true, false, false, false)
                            if (frame == null) {
                                if (looping && isRunning) {
                                    try {
                                        playbackSource?.updatePlaybackPosition(0L)
                                        frame = restartLoop(g)
                                        resetLoopClock(0L)
                                        loopingTimeline.reset()
                                        resetMasterClock(0L)
                                        initialVideoFrameRendered = false
                                    } catch (e: Exception) {
                                        PlaybackEventLog.error(PlaybackEventKind.PLAYER, "VideoPlayer", "Video loop restart failed", e)
                                        Logger.w("VideoPlayer", "Loop restart failed type=${e::class.simpleName ?: "unknown"}")
                                    }
                                }
                                if (frame == null && isRunning && !isPaused) {
                                    val startWaitMs = System.currentTimeMillis()
                                    Logger.d("VideoPlayer", "Frame is null from grabber, waiting for buffer...")
                                    while (frame == null &&
                                        isRunning &&
                                        !isPaused &&
                                        (System.currentTimeMillis() - startWaitMs) < MAX_WAIT_MS
                                    ) {
                                        val streamFailure =
                                            (playbackSource as? PlaybackPositionInput)
                                                ?.streamFailure
                                        if (streamFailure != null) break
                                        try {
                                            Thread.sleep(25)
                                        } catch (_: InterruptedException) {
                                            Thread.currentThread().interrupt()
                                            break
                                        }
                                        frame = g.grabFrame(false, true, false, false, false)
                                    }
                                }
                                if (frame == null) {
                                    val streamErr =
                                        (playbackSource as? PlaybackPositionInput)
                                            ?.streamFailure
                                    Logger.i(
                                        "VideoPlayer",
                                        "Decode loop exit: null frame after buffer wait" +
                                            " decoded=$decodedFramesCount rendered=$renderedFramesCount dropped=$droppedFramesCount" +
                                            " lastPts=${lastVideoPtsMs}ms streamError=${streamErr != null}",
                                    )
                                    break
                                }
                            }
                            playbackSource?.updatePlaybackPosition(currentPositionProvider())
                            when {
                                frame.image != null || frame.type == Frame.Type.VIDEO -> {
                                    val rawPtsMs = frame.timestamp / 1000
                                    val framePtsMs = normalizeTimelinePts(rawPtsMs)
                                    if (!initialVideoFrameRendered) {
                                        val clock = audioClock()
                                        if (clock > 300L && framePtsMs < (clock - 80L)) {
                                            continue
                                        }
                                    }
                                    decodedFramesCount++
                                    val force = isPaused || !initialVideoFrameRendered
                                    val rendered = renderVideo(frame, forceRender = force, isInitialFrame = !initialVideoFrameRendered)
                                    if (rendered) {
                                        if (!initialVideoFrameRendered) {
                                            Logger.i(
                                                "VideoPlayer",
                                                "Rendered FIRST video frame at PTS=${framePtsMs}ms (AudioClock=${audioClock()}ms)",
                                            )
                                            droppedFramesCount = 0L
                                            decodedFramesCount = 1L
                                            renderedFramesCount = 1L
                                            synchronized(lifecycleLock) {
                                                if (loadJobId == playbackLoadJobId) onRenderedFirstFrame()
                                            }
                                        }
                                        initialVideoFrameRendered = true
                                    }
                                }
                            }
                        }
                    } catch (_: InterruptedException) {
                        Logger.d("VideoPlayer", "Decode loop interrupted")
                    } catch (e: Exception) {
                        PlaybackEventLog.error(PlaybackEventKind.PLAYER, "VideoPlayer", "Video decode loop failed", e)
                        Logger.e("VideoPlayer", "Decode loop failed type=${e::class.simpleName ?: "unknown"}")
                    } finally {
                        Logger.i("VideoPlayer", "decodeThread exiting (loadJobId matches=${loadJobId == playbackLoadJobId})")
                        synchronized(lifecycleLock) {
                            if (loadJobId == playbackLoadJobId) {
                                isRunning = false
                                onEnded()
                            }
                        }
                    }
                }.apply {
                    priority = Thread.NORM_PRIORITY + 1
                    name = "VideoDecode-Thread"
                    isDaemon = true
                    start()
                }
        }

    actual fun pause() {
        synchronized(clockLock) {
            val currentPosition = audioClock()
            isPaused = true
            pausedPositionMs = currentPosition
            clockLock.notifyAll()
        }
    }

    actual fun resume() {
        if (decodeThread?.isAlive != true) {
            play(startPaused = false)
        } else {
            synchronized(clockLock) {
                val currentPosition = if (looping) pausedPositionMs else positionProvider()
                if (looping) resetLoopClock(currentPosition)
                pausedPositionMs = currentPosition
                isPaused = false
                clockLock.notifyAll()
            }
        }
    }

    actual fun seekTo(positionMs: Long) {
        val seekTargetMs = positionMs.coerceAtLeast(0L)
        seekableSource?.updatePlaybackPosition(seekTargetMs)
        synchronized(clockLock) {
            pendingSeekMs = seekTargetMs
            clockLock.notifyAll()
        }
    }

    actual fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0.0f, 1.0f)
    }

    fun setPlaybackSpeed(speed: Float) {
        synchronized(clockLock) {
            val currentPosition = audioClock()
            if (looping) resetLoopClock(currentPosition)
            pausedPositionMs = currentPosition
            playbackSpeed = speed.coerceIn(0.5f, 2.0f)
            clockLock.notifyAll()
        }
    }

    fun setTargetSize(
        width: Int,
        height: Int,
        fillViewport: Boolean = false,
    ) {
        val viewportWidth = width.coerceAtLeast(0)
        val viewportHeight = height.coerceAtLeast(0)
        synchronized(lifecycleLock) {
            requestedTargetWidth = viewportWidth
            requestedTargetHeight = viewportHeight
            requestedFillViewport = fillViewport
        }
    }

    actual fun setSurface(surface: Any?) {
    }

    actual fun close() {
        closeAsync()
    }

    fun dispose() {
        disposeAsync()
    }

    internal fun disposeAsync(): Job {
        val retirement =
            synchronized(lifecycleLock) {
                disposed = true
                closeAsync()
            }
        return cleanupScope.launch {
            retirement.join()
            // An unpublished grabber belongs to prepare() until its native start returns.
            synchronized(prepareLock) { }
        }
    }

    internal fun closeAsync(): Job =
        synchronized(lifecycleLock) {
            loadJobId = nextLoadJobId(loadJobId)
            currentUrl = null
            isRunning = false
            isPaused = false
            nativeVideoWidth = 0
            nativeVideoHeight = 0
            val activeSource = seekableSource
            seekableSource = null
            val activeGrabber = grabber
            grabber = null
            val activeDecodeThread = decodeThread
            decodeThread = null
            pendingSeekMs = null
            liveTimeline.reset()
            activeDecodeThread?.interrupt()
            val previousTeardown = teardownJob
            cleanupScope
                .launch {
                    previousTeardown?.join()
                    runCatching { activeSource?.close() }
                    var interrupted = false
                    while (activeDecodeThread?.isAlive == true) {
                        try {
                            activeDecodeThread.join()
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    // A decoder or an in-progress draw may still own these pixels until the join completes.
                    runCatching { activeGrabber?.release() }
                    frameHolders.forEach { it.reset() }
                    nextFrameHolderIndex = 0
                    cachedW = 0
                    cachedH = 0
                    if (interrupted) Thread.currentThread().interrupt()
                }.also { teardownJob = it }
        }

    private fun renderVideo(
        frame: Frame,
        forceRender: Boolean = false,
        isInitialFrame: Boolean = false,
    ): Boolean {
        val rawFrameMs = frame.timestamp / 1000
        val frameMs = normalizeTimelinePts(rawFrameMs)
        val clock = audioClock()
        if (!forceRender) {
            val diff = clock - frameMs
            if (diff > DROP_THRESHOLD_MS && !isInitialFrame) {
                droppedFramesCount++
                if (droppedFramesCount <= 10L || droppedFramesCount % 60L == 0L) {
                    Logger.d(
                        "VideoPlayer",
                        "[DROP] Frame #$decodedFramesCount PTS=${frameMs}ms" +
                            " AudioClock=${clock}ms Diff=${diff}ms > ${DROP_THRESHOLD_MS}ms",
                    )
                }
                return false
            }
        }

        val g = grabber ?: return false
        val (currentTargetW, currentTargetH) =
            synchronized(lifecycleLock) {
                videoOutputSize(
                    nativeVideoWidth.takeIf { it > 0 } ?: frame.imageWidth.takeIf { it > 0 } ?: g.imageWidth,
                    nativeVideoHeight.takeIf { it > 0 } ?: frame.imageHeight.takeIf { it > 0 } ?: g.imageHeight,
                    requestedTargetWidth,
                    requestedTargetHeight,
                    requestedFillViewport,
                )
            }
        if (currentTargetW <= 0 || currentTargetH <= 0) return false
        cachedW = currentTargetW
        cachedH = currentTargetH
        targetWidth = currentTargetW
        targetHeight = currentTargetH

        val holder = frameHolders[nextFrameHolderIndex]
        holder.prepareForReuse()
        holder.allocate(cachedW, cachedH)
        if (holder.yAddr == 0L) return false

        val renderStartNanos = System.nanoTime()
        val planarInfo =
            g.processPlanarImageToAddresses(
                yAddress = holder.yAddr,
                yStride = holder.yStride,
                uAddress = holder.uAddr,
                uStride = holder.uStride,
                vAddress = holder.vAddr,
                vStride = holder.vStride,
                destinationWidth = cachedW,
                destinationHeight = cachedH,
            )

        val videoFrame: VideoFrame?
        if (planarInfo != null) {
            val colorSpace =
                when (planarInfo.colorSpace) {
                    AVCOL_SPC_BT709 -> VideoColorSpace.BT709
                    AVCOL_SPC_BT470BG, AVCOL_SPC_SMPTE170M, AVCOL_SPC_SMPTE240M -> VideoColorSpace.BT601
                    AVCOL_SPC_BT2020_NCL, AVCOL_SPC_BT2020_CL -> VideoColorSpace.BT2020
                    else -> if (nativeVideoHeight >= 720) VideoColorSpace.BT709 else VideoColorSpace.BT601
                }
            val colorRange = if (planarInfo.colorRange == AVCOL_RANGE_JPEG) VideoColorRange.FULL else VideoColorRange.LIMITED

            when (planarInfo.pixelFormat) {
                AV_PIX_FMT_YUV420P, AV_PIX_FMT_YUVJ420P, AV_PIX_FMT_NV12, AV_PIX_FMT_NV21 -> {
                    holder.planeY.notifyPixelsChanged()
                    holder.planeU.notifyPixelsChanged()
                    holder.planeV.notifyPixelsChanged()
                    videoFrame =
                        VideoFrame(
                            width = currentTargetW,
                            height = currentTargetH,
                            format = VideoPixelFormat.YUV420P,
                            colorSpace = colorSpace,
                            colorRange = colorRange,
                            planeY = holder.planeY,
                            planeU = holder.planeU,
                            planeV = holder.planeV,
                            timestampMs = frameMs,
                            imageLock = holder.imageLock,
                        )
                }

                else -> {
                    videoFrame = null
                }
            }
        } else {
            holder.ensureBgra(currentTargetW, currentTargetH)
            val success =
                g.processImageToAddress(
                    dstAddress = holder.bgraAddr,
                    dstStride = holder.bgraStride,
                    destinationWidth = currentTargetW,
                    destinationHeight = currentTargetH,
                )
            if (success) {
                holder.bgraBitmap.notifyPixelsChanged()
                videoFrame =
                    VideoFrame(
                        width = currentTargetW,
                        height = currentTargetH,
                        format = VideoPixelFormat.BGRA,
                        bgraBitmap = holder.bgraBitmap,
                        timestampMs = frameMs,
                        imageLock = holder.imageLock,
                    )
            } else {
                videoFrame = null
            }
        }

        val processDuration = System.nanoTime() - renderStartNanos

        if (videoFrame == null) {
            droppedFramesCount++
            return false
        }

        if (!forceRender) {
            val currentClock = audioClock()
            if (currentClock < frameMs) {
                if (!waitForAudioClock(frameMs)) {
                    videoFrame.close()
                    droppedFramesCount++
                    return false
                }
            }
        }

        val emitStartNanos = System.nanoTime()
        holder.currentFrame = videoFrame
        nextFrameHolderIndex = (nextFrameHolderIndex + 1) % frameHolders.size
        synchronized(lifecycleLock) {
            if (!isRunning || grabber !== g) {
                videoFrame.close()
                return false
            }
            Snapshot.withMutableSnapshot {
                onFrame(videoFrame)
            }
        }
        val renderDuration = processDuration + (System.nanoTime() - emitStartNanos)

        lastRenderDurationNanos = renderDuration
        lastVideoPtsMs = frameMs
        renderedFramesCount++

        if (renderedFramesCount <= 10L || renderedFramesCount % 60L == 0L) {
            Logger.d(
                "VideoPlayer",
                "[RENDER] Frame #$renderedFramesCount PTS=${frameMs}ms" +
                    " AudioClock=${clock}ms Diff=${clock - frameMs}ms format=${videoFrame.format}",
            )
        }

        val now = System.nanoTime()
        synchronized(frameRenderTimestamps) {
            frameRenderTimestamps.addLast(now)
            val cutoff = now - 1_000_000_000L
            while (frameRenderTimestamps.isNotEmpty() && frameRenderTimestamps.first() < cutoff) {
                frameRenderTimestamps.removeFirst()
            }
        }
        synchronized(renderDurations) {
            renderDurations.addLast(renderDuration)
            if (renderDurations.size > 60) renderDurations.removeFirst()
        }

        return true
    }

    private fun audioClock(): Long =
        synchronized(clockLock) {
            if (isPaused) {
                return@synchronized pausedPositionMs
            }
            if (looping) {
                val anchor = loopClock
                advancedClockPositionMs(anchor.positionMs, System.nanoTime() - anchor.startedNanos, playbackSpeed)
            } else {
                positionProvider()
            }
        }

    private fun waitForAudioClock(frameMs: Long): Boolean =
        synchronized(clockLock) {
            var previousClock: Long? = null
            while (isRunning && !isPaused) {
                if (pendingSeekMs != null || Thread.currentThread().isInterrupted) return@synchronized false
                val clock = audioClock()
                val remainingMs = frameMs - clock
                if (remainingMs <= 0L) return@synchronized true
                val limitNanos = if (previousClock == clock) 10_000_000L else 100_000_000L
                previousClock = clock
                TimeUnit.NANOSECONDS.timedWait(clockLock, frameDelayNanos(remainingMs, playbackSpeed).coerceAtMost(limitNanos))
            }
            isRunning
        }

    private class FrameBitmapHolder {
        val imageLock = Any()
        var currentFrame: VideoFrame? = null
        val planeY = Bitmap()
        val planeU = Bitmap()
        val planeV = Bitmap()
        val bgraBitmap = Bitmap()

        var yAddr: Long = 0L
        var uAddr: Long = 0L
        var vAddr: Long = 0L
        var bgraAddr: Long = 0L

        var yStride: Int = 0
        var uStride: Int = 0
        var vStride: Int = 0
        var bgraStride: Int = 0

        fun prepareForReuse() {
            synchronized(imageLock) {
                currentFrame?.close()
                currentFrame = null
            }
        }

        fun allocate(
            width: Int,
            height: Int,
        ) {
            val uvW = (width + 1) / 2
            val uvH = (height + 1) / 2

            if (planeY.width != width || planeY.height != height) {
                bgraBitmap.reset()
                bgraAddr = 0L
                bgraStride = 0
                planeY.allocVideoPixels(ImageInfo(width, height, ColorType.ALPHA_8, ColorAlphaType.PREMUL))
                yAddr = planeY.peekPixels()?.use { it.addr } ?: 0L
                yStride = planeY.rowBytes
            }
            if (planeU.width != uvW || planeU.height != uvH) {
                planeU.allocVideoPixels(ImageInfo(uvW, uvH, ColorType.ALPHA_8, ColorAlphaType.PREMUL))
                uAddr = planeU.peekPixels()?.use { it.addr } ?: 0L
                uStride = planeU.rowBytes
            }
            if (planeV.width != uvW || planeV.height != uvH) {
                planeV.allocVideoPixels(ImageInfo(uvW, uvH, ColorType.ALPHA_8, ColorAlphaType.PREMUL))
                vAddr = planeV.peekPixels()?.use { it.addr } ?: 0L
                vStride = planeV.rowBytes
            }
        }

        fun ensureBgra(
            width: Int,
            height: Int,
        ) {
            if (bgraBitmap.width != width || bgraBitmap.height != height) {
                bgraBitmap.allocVideoPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB))
                bgraAddr = bgraBitmap.peekPixels()?.use { it.addr } ?: 0L
                bgraStride = bgraBitmap.rowBytes
            }
        }

        fun reset() {
            synchronized(imageLock) {
                currentFrame?.close()
                currentFrame = null
                planeY.reset()
                planeU.reset()
                planeV.reset()
                bgraBitmap.reset()
                yAddr = 0L
                uAddr = 0L
                vAddr = 0L
                bgraAddr = 0L
                yStride = 0
                uStride = 0
                vStride = 0
                bgraStride = 0
            }
        }
    }

    private val frameHolders = Array(8) { FrameBitmapHolder() }
    private var nextFrameHolderIndex = 0
    private var cachedW = 0
    private var cachedH = 0

    private companion object {
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val OPTIMAL_VIDEO_THREADS = minOf(8, maxOf(4, Runtime.getRuntime().availableProcessors())).toString()
        const val DROP_THRESHOLD_MS = 200L
        const val MAX_WAIT_MS = 20_000L
        const val INITIAL_SEEK_MIN_MS = 3500L
        const val INITIAL_SEEK_LEAD_MS = 300L
    }
}

internal fun Bitmap.allocVideoPixels(info: ImageInfo): Boolean =
    // FFmpeg's SIMD scaler needs padded rows, including chroma planes smaller than a vector.
    allocPixels(info, (info.minRowBytes + 31) / 32 * 32)

private data class LoopClockAnchor(
    val positionMs: Long = 0L,
    val startedNanos: Long = 0L,
)

internal fun scaledClockElapsedMs(
    elapsedNanos: Long,
    playbackSpeed: Float,
): Long = (elapsedNanos / 1_000_000.0 * playbackSpeed.coerceAtLeast(0f)).toLong()

internal fun frameDelayNanos(
    mediaDeltaMs: Long,
    playbackSpeed: Float,
): Long = (mediaDeltaMs * 1_000_000.0 / playbackSpeed.coerceAtLeast(0.01f)).toLong()

internal fun advancedClockPositionMs(
    anchorPositionMs: Long,
    elapsedNanos: Long,
    playbackSpeed: Float,
): Long = anchorPositionMs + scaledClockElapsedMs(elapsedNanos, playbackSpeed)

internal fun shouldUseRangeStreamForVideoUrl(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isTrustedDirectMediaEndpoint) == true

internal fun restartLoop(grabber: FFmpegFrameGrabber): Frame? {
    try {
        grabber.setTimestamp(0L, true)
        grabber.grabFrame(false, true, false, false, false)?.let { return it }
    } catch (_: Exception) {
        // Some remote demuxers cannot seek. Reopen only after rewind actually fails.
    }
    // restart() closes custom input streams; only URL/file inputs can be opened again.
    if (grabber.mediaInputStream != null) return null
    grabber.restart()
    return grabber.grabFrame(false, true, false, false, false)
}

internal fun nextLoadJobId(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L

internal class LoopingVideoTimeline {
    private var firstFrameTimestampMs: Long? = null

    fun normalize(frameTimestampMs: Long): Long {
        val firstTimestamp = firstFrameTimestampMs ?: frameTimestampMs.also { firstFrameTimestampMs = it }
        return (frameTimestampMs - firstTimestamp).coerceAtLeast(0L)
    }

    fun reset() {
        firstFrameTimestampMs = null
    }
}

internal class LiveVideoTimeline {
    private var ptsOffset: Long? = null

    val isTracking: Boolean
        get() = ptsOffset != null

    fun normalizeWithAudioPts(
        videoPtsMs: Long,
        audioPtsMs: Long,
        audioClockMs: Long,
    ): Long {
        val delta = videoPtsMs - audioPtsMs
        val normalized = audioClockMs + delta
        ptsOffset = videoPtsMs - normalized
        return normalized.coerceAtLeast(0L)
    }

    fun normalize(
        rawPtsMs: Long,
        audioClockMs: Long,
    ): Long {
        val currentOffset = ptsOffset
        val offset =
            if (currentOffset == null || abs(audioClockMs - (rawPtsMs - currentOffset)) > 5000L) {
                (rawPtsMs - audioClockMs).also { ptsOffset = it }
            } else {
                currentOffset
            }
        return (rawPtsMs - offset).coerceAtLeast(0L)
    }

    fun reset() {
        ptsOffset = null
    }
}

internal fun videoOutputSize(
    nativeWidth: Int,
    nativeHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    fillViewport: Boolean,
): Pair<Int, Int> {
    if (nativeWidth <= 0 || nativeHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
        return nativeWidth to nativeHeight
    }
    val widthScale = viewportWidth.toDouble() / nativeWidth
    val heightScale = viewportHeight.toDouble() / nativeHeight
    val scale = (if (fillViewport) maxOf(widthScale, heightScale) else minOf(widthScale, heightScale)).coerceAtMost(1.0)
    if (scale == 1.0) return nativeWidth to nativeHeight

    fun scaledEven(size: Int): Int = ((size * scale).toInt() / 2 * 2).coerceAtLeast(minOf(2, size))
    return scaledEven(nativeWidth) to scaledEven(nativeHeight)
}
