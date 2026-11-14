package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.playback.SeekableMediaSource
import com.metrolist.shared.playback.instantSilenceSeekTarget
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AudioPlayerFailureSource {
    DECODER,
    RENDERER,
    STREAM,
}

class AudioPlayer(
    private val onPlaybackCompleted: (AudioPlayer) -> Unit = {},
    private val onPlaybackFailed: (AudioPlayer, Throwable) -> Unit = { _, _ -> },
    private val onStateChanged: () -> Unit = {},
    private val rendererFactory: (Int, Int) -> AudioRenderer = ::AudioRenderer,
) {
    @Volatile
    private var decoder: AudioDecoder? = null

    @Volatile
    private var renderer: AudioRenderer? = null

    private val workerSignal = PlaybackWorkerSignal()

    private fun signalStateChanged() {
        workerSignal.signal()
        onStateChanged()
    }

    private val preparationLock = Any()
    private var preparationGeneration = 0L
    private var preparingInputStream: InputStream? = null

    private var decodeThread: Thread? = null
    private var renderThread: Thread? = null
    private var seekThread: Thread? = null

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val seekLock = Object()
    private var pendingSeek: Pair<Long, Long>? = null

    @Volatile
    private var seekRequestVersion = 0L
    private var completedSeekVersion = 0L
    private var seekFailure: Throwable? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isPaused = false

    @Volatile
    private var playbackWasRequested = false

    @Volatile
    private var isSeeking = false

    @Volatile
    private var activeSeekPosition: Long? = null

    @Volatile
    private var acceptingSeeks = false

    private var atEnd = false

    @Volatile
    private var frameQueue = LinkedBlockingQueue<QueuedAudioFrame>(15)

    private val ptsLock = Any()

    @Volatile
    private var anchorAudioPtsMs: Long? = null

    @Volatile
    private var anchorPlayedMs: Long = 0L

    fun currentAudioPtsMs(): Long? {
        val r = renderer ?: return null
        synchronized(ptsLock) {
            val anchorPts = anchorAudioPtsMs ?: return null
            val anchorPlayed = anchorPlayedMs
            val currentPlayed = r.getPlayedPositionMs()
            return anchorPts + (currentPlayed - anchorPlayed).coerceAtLeast(0L)
        }
    }

    private data class QueuedAudioFrame(
        val data: ByteArray?,
        val seekVersion: Long,
        val ptsMs: Long? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as QueuedAudioFrame

            if (seekVersion != other.seekVersion) return false
            if (ptsMs != other.ptsMs) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = seekVersion.hashCode()
            result = 31 * result + (ptsMs?.hashCode() ?: 0)
            result = 31 * result + (data?.contentHashCode() ?: 0)
            return result
        }
    }

    private var currentVolume = 1.0f
    private var normalizationGain = 1f
    private var audioOutputEnabled = true
    private var currentPlaybackPitch = 1f
    private var currentPlaybackSpeed = 1f
    private val playbackFailed = AtomicBoolean(false)
    private val closeStarted = AtomicBoolean(false)

    var videoFrameListener: ((Frame) -> Unit)? = null
    var videoEnabled = false
        set(value) {
            field = value
            decoder?.wantVideo = value
        }

    @Volatile
    private var lastPrepareFailure: Throwable? = null

    internal val hasFailed: Boolean
        get() = playbackFailed.get()

    internal val prepareFailure: Throwable?
        get() = lastPrepareFailure

    @Volatile
    internal var failureSource: AudioPlayerFailureSource? = null
        private set

    fun loadFile(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): Boolean = prepareFile(path, headers, startPaused = false).also { prepared -> if (prepared) play() }

    fun prepareFile(
        path: String,
        headers: Map<String, String> = emptyMap(),
        startPaused: Boolean = true,
    ): Boolean = prepareDecoder(startPaused, null) { AudioDecoder(path, headers) }

    internal fun loadStream(
        inputStream: InputStream,
        maximumSize: Int,
    ): Boolean = prepareStream(inputStream, maximumSize, startPaused = false).also { prepared -> if (prepared) play() }

    internal fun prepareStream(
        inputStream: InputStream,
        maximumSize: Int,
        startPaused: Boolean = true,
    ): Boolean {
        val prepared = prepareDecoder(startPaused, inputStream) { AudioDecoder(inputStream, maximumSize) }
        if (!prepared) {
            (inputStream as? PlaybackPositionInput)?.streamFailure?.let { lastPrepareFailure = it }
            runCatching { inputStream.close() }
        }
        return prepared
    }

    private inline fun prepareDecoder(
        startPaused: Boolean,
        inputStream: InputStream?,
        createDecoder: () -> AudioDecoder,
    ): Boolean {
        close()
        closeStarted.set(false)
        frameQueue = LinkedBlockingQueue(15)
        synchronized(ptsLock) {
            anchorAudioPtsMs = null
            anchorPlayedMs = 0L
        }
        playbackFailed.set(false)
        lastPrepareFailure = null
        failureSource = null
        val generation =
            synchronized(preparationLock) {
                preparingInputStream = inputStream
                ++preparationGeneration
            }
        var preparedDecoder: AudioDecoder? = null
        var preparedRenderer: AudioRenderer? = null
        return try {
            val d = createDecoder()
            preparedDecoder = d
            if (startPaused) {
                preparedRenderer = createRenderer(d).also { it.pause() }
            }
            val committed =
                synchronized(preparationLock) {
                    if (preparationGeneration != generation) {
                        false
                    } else {
                        preparingInputStream = null
                        decoder = d
                        renderer = preparedRenderer
                        preparedRenderer?.setVolume(effectiveVolume())
                        preparedRenderer?.normalizationGain = normalizationGain
                        isPaused = startPaused
                        isRunning = false
                        acceptingSeeks = true
                        true
                    }
                }
            if (!committed) {
                runCatching { preparedRenderer?.close() }
                runCatching { d.close() }
            }
            signalStateChanged()
            committed
        } catch (error: Exception) {
            runCatching { preparedRenderer?.close() }
            runCatching { preparedDecoder?.close() }
            synchronized(preparationLock) {
                if (preparationGeneration == generation) {
                    preparingInputStream = null
                    isRunning = false
                    lastPrepareFailure = error
                }
            }
            false
        }
    }

    private fun createRenderer(decoder: AudioDecoder): AudioRenderer =
        rendererFactory(decoder.sampleRate, decoder.audioChannels).also {
            synchronized(preparationLock) {
                it.setVolume(effectiveVolume())
                it.normalizationGain = normalizationGain
            }
            it.setPlaybackSpeed(currentPlaybackSpeed)
            it.setPlaybackPitch(currentPlaybackPitch)
        }

    private fun ensureRenderer(decoder: AudioDecoder): AudioRenderer? =
        synchronized(preparationLock) {
            renderer?.let { return@synchronized it }
            try {
                createRenderer(decoder).also { renderer = it }
            } catch (error: Exception) {
                lastPrepareFailure = error
                reportPlaybackFailure(error, AudioPlayerFailureSource.RENDERER)
                null
            }
        }

    fun play() {
        if (decodeThread?.isAlive == true || renderThread?.isAlive == true) return

        val d = decoder ?: return
        val r = ensureRenderer(d) ?: return

        isRunning = true
        isPaused = false
        playbackWasRequested = true
        r.resume()
        signalStateChanged()
        val playbackQueue = frameQueue

        decodeThread =
            Thread {
                try {
                    while (isRunning && decoder === d) {
                        val decodeSeekVersion = seekRequestVersion
                        val reachedEnd =
                            playDecodedFrames(
                                isRunning = { isRunning && decoder === d && seekRequestVersion == decodeSeekVersion },
                                readFrame = {
                                    workerSignal.awaitWhile { isRunning && decoder === d && (isSeeking || isPaused) }
                                    if (isRunning && decoder === d && seekRequestVersion == decodeSeekVersion) {
                                        d.readPCMFrame(videoFrameListener)
                                    } else {
                                        null
                                    }
                                },
                                writeFrame = { frame ->
                                    enqueueDecodedFrame(
                                        isCurrent = { isRunning && decoder === d && seekRequestVersion == decodeSeekVersion && !isSeeking },
                                        isPaused = { isPaused },
                                        awaitResume = {
                                            workerSignal.awaitWhile {
                                                isRunning && decoder === d && isPaused &&
                                                    seekRequestVersion == decodeSeekVersion
                                            }
                                        },
                                        offer = {
                                            playbackQueue.offer(
                                                QueuedAudioFrame(frame, decodeSeekVersion, d.lastDecodedPtsMs),
                                                50,
                                                TimeUnit.MILLISECONDS,
                                            )
                                        },
                                    )
                                },
                            )
                        if (reachedEnd) {
                            if (decoder !== d) return@Thread
                            d.streamFailure?.let { error ->
                                reportPlaybackFailure(error, AudioPlayerFailureSource.STREAM)
                                return@Thread
                            }
                            enqueueDecodedFrame(
                                isCurrent = { isRunning && decoder === d && seekRequestVersion == decodeSeekVersion },
                                isPaused = { isPaused },
                                offer = { playbackQueue.offer(QueuedAudioFrame(null, decodeSeekVersion), 50, TimeUnit.MILLISECONDS) },
                                awaitResume = {
                                    workerSignal.awaitWhile {
                                        isRunning && decoder === d && isPaused && seekRequestVersion == decodeSeekVersion
                                    }
                                },
                            )
                            workerSignal.awaitWhile { isRunning && decoder === d && seekRequestVersion == decodeSeekVersion }
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    if (decoder === d && isRunning) {
                        val streamFailure = d.streamFailure
                        reportPlaybackFailure(
                            streamFailure ?: e,
                            if (streamFailure != null) AudioPlayerFailureSource.STREAM else AudioPlayerFailureSource.DECODER,
                        )
                    }
                }
            }.apply {
                priority = Thread.NORM_PRIORITY + 2
                name = "AudioDecoder-Thread"
                start()
            }

        renderThread =
            Thread {
                var silenceSinceNs = 0L
                var nextSilenceSeekNs = 0L
                var silenceSeekVersion = seekRequestVersion
                try {
                    while (isRunning && renderer === r) {
                        workerSignal.awaitWhile { isRunning && renderer === r && (isPaused || isSeeking) }
                        if (!isRunning || renderer !== r) break

                        val frame = playbackQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                        if (frame.seekVersion != seekRequestVersion) continue

                        if (frame.data == null) {
                            val completionSeekVersion = frame.seekVersion
                            val drained =
                                r.drain {
                                    isRunning && renderer === r && !isSeeking && seekRequestVersion == completionSeekVersion
                                }
                            if (drained && claimCompletion(completionSeekVersion, r)) {
                                signalStateChanged()
                                if (!closeStarted.get()) onPlaybackCompleted(this)
                            }
                            if (!isRunning || renderer !== r) break
                            continue
                        }

                        if (frame.data.isNotEmpty()) {
                            if (frame.ptsMs != null) {
                                synchronized(ptsLock) {
                                    val played = r.getPlayedPositionMs()
                                    val currentAnchorPts = anchorAudioPtsMs
                                    val currentAnchorPlayed = anchorPlayedMs
                                    if (currentAnchorPts == null ||
                                        kotlin.math.abs((frame.ptsMs - currentAnchorPts) - (played - currentAnchorPlayed)) > 2000L
                                    ) {
                                        anchorAudioPtsMs = frame.ptsMs
                                        anchorPlayedMs = played
                                    }
                                }
                            }
                            r.writePcmData(frame.data) { frame.seekVersion == seekRequestVersion && !isSeeking }
                            if (silenceSeekVersion != seekRequestVersion || !r.isLongSilence()) {
                                silenceSinceNs = 0L
                                silenceSeekVersion = seekRequestVersion
                            } else if (silenceSinceNs == 0L) {
                                silenceSinceNs = System.nanoTime()
                            } else if (System.nanoTime() - silenceSinceNs >= TimeUnit.MILLISECONDS.toNanos(200)) {
                                if (!isPaused && !isSeeking) {
                                    if (System.nanoTime() >= nextSilenceSeekNs) {
                                        instantSilenceSeekTarget(getCurrentPosition(), getDuration())?.let { target ->
                                            if (requestSeek(target) >= 0L) {
                                                nextSilenceSeekNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350)
                                            }
                                        }
                                    }
                                }
                                silenceSinceNs = 0L
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    if (renderer === r && isRunning) reportPlaybackFailure(e, AudioPlayerFailureSource.RENDERER)
                } finally {
                    if (renderer === r) {
                        isRunning = false
                        signalStateChanged()
                    }
                }
            }.apply {
                priority = Thread.NORM_PRIORITY + 2
                name = "AudioRenderer-Thread"
                start()
            }
    }

    private fun claimCompletion(
        expectedSeekVersion: Long,
        expectedRenderer: AudioRenderer,
    ): Boolean =
        synchronized(seekLock) {
            if (
                !isRunning ||
                renderer !== expectedRenderer ||
                isSeeking ||
                seekRequestVersion != expectedSeekVersion ||
                closeStarted.get()
            ) {
                false
            } else {
                atEnd = true
                isPaused = true
                playbackWasRequested = false
                expectedRenderer.pause()
                true
            }
        }

    fun pause() {
        isPaused = true
        playbackWasRequested = false
        renderer?.pause()
        signalStateChanged()
    }

    internal fun pauseForHandoff() {
        isPaused = true
        renderer?.pause()
        signalStateChanged()
    }

    fun resume() {
        synchronized(seekLock) {
            playbackWasRequested = true
            if (!hasFailed && (atEnd || (getDuration() > 0L && getCurrentPosition() >= getDuration()))) {
                requestSeek(0L)
            }
            if (!hasFailed && decodeThread?.isAlive != true && renderThread?.isAlive != true) {
                play()
            } else {
                renderer?.resume()
                isPaused = false
            }
        }
        signalStateChanged()
    }

    fun seekTo(position: Long) {
        requestSeek(position)
    }

    fun seekToAwait(
        position: Long,
        timeoutMs: Long,
    ): Boolean {
        require(timeoutMs > 0L) { "Seek timeout must be positive" }
        val requestVersion = requestSeek(position)
        if (requestVersion <= 0L) return false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(seekLock) {
            while (decoder != null && completedSeekVersion < requestVersion && seekFailure == null) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                seekLock.wait(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L))
            }
            return completedSeekVersion >= requestVersion && seekFailure == null
        }
    }

    private fun requestSeek(position: Long): Long {
        val d = decoder ?: return -1L
        val boundedPosition = position.coerceAtLeast(0L)
        val r = renderer
        val playbackQueue = frameQueue
        val dur = getDuration()
        var requestVersion = -1L
        if (dur == 0L || boundedPosition <= dur) {
            synchronized(seekLock) {
                if (!acceptingSeeks || decoder !== d) return -1L
                // Native seeks can land on the last packet before the requested end.
                atEnd = dur > 0L && boundedPosition == dur
                requestVersion = ++seekRequestVersion
                seekFailure = null
                d.updatePlaybackSeekTarget(boundedPosition)
                activeSeekPosition = boundedPosition
                pendingSeek = boundedPosition to requestVersion
                if (seekThread?.isAlive != true) {
                    isSeeking = true
                    seekThread =
                        Thread {
                            try {
                                while (!Thread.currentThread().isInterrupted) {
                                    var shouldExit = false
                                    val request =
                                        synchronized(seekLock) {
                                            pendingSeek.also { pendingSeek = null }
                                                ?: run {
                                                    seekThread = null
                                                    isSeeking = false
                                                    shouldExit = true
                                                    0L to 0L
                                                }
                                        }
                                    if (shouldExit) {
                                        signalStateChanged()
                                        return@Thread
                                    }
                                    val (target, requestVersion) = request
                                    if (decoder !== d) return@Thread
                                    playbackQueue.clear()
                                    r?.flush()
                                    d.seekTo(target, managePlaybackPosition = false)
                                    if (decoder !== d) return@Thread
                                    playbackQueue.clear()
                                    r?.flush()
                                    synchronized(seekLock) {
                                        if (seekRequestVersion == requestVersion && decoder === d) {
                                            d.finishPlaybackSeek()
                                            r?.resetPosition(d.getCurrentPosition())
                                            completedSeekVersion = requestVersion
                                            activeSeekPosition = null
                                            synchronized(ptsLock) {
                                                anchorAudioPtsMs = null
                                                anchorPlayedMs = 0L
                                            }
                                            seekLock.notifyAll()
                                        }
                                    }
                                }
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            } catch (error: Exception) {
                                if (decoder === d && isRunning) {
                                    val streamFailure = d.streamFailure
                                    reportPlaybackFailure(
                                        streamFailure ?: error,
                                        if (streamFailure != null) AudioPlayerFailureSource.STREAM else AudioPlayerFailureSource.DECODER,
                                    )
                                }
                                synchronized(seekLock) {
                                    if (decoder === d) {
                                        seekFailure = error
                                        activeSeekPosition = null
                                    }
                                    seekLock.notifyAll()
                                }
                            } finally {
                                synchronized(seekLock) {
                                    if (seekThread === Thread.currentThread()) {
                                        seekThread = null
                                        isSeeking = false
                                    }
                                }
                                signalStateChanged()
                            }
                        }.apply {
                            name = "AudioSeek-Thread"
                            isDaemon = true
                            start()
                        }
                }
            }
        }
        signalStateChanged()
        return requestVersion
    }

    fun setNormalizationGain(gain: Float) {
        synchronized(preparationLock) {
            normalizationGain = gain.coerceAtLeast(1f)
            renderer?.normalizationGain = normalizationGain
        }
    }

    fun setVolume(volume: Float) {
        synchronized(preparationLock) {
            currentVolume = volume
            renderer?.setVolume(effectiveVolume())
        }
    }

    fun setAudioOutputEnabled(enabled: Boolean) {
        synchronized(preparationLock) {
            if (audioOutputEnabled == enabled) return
            audioOutputEnabled = enabled
            renderer?.setVolume(effectiveVolume())
        }
    }

    fun setPlaybackPitch(pitch: Float) {
        require(pitch.isFinite() && pitch in 0.5f..2f)
        currentPlaybackPitch = pitch
        renderer?.setPlaybackPitch(pitch)
    }

    fun setPlaybackSpeed(speed: Float) {
        require(speed.isFinite() && speed in 0.5f..2f)
        currentPlaybackSpeed = speed
        renderer?.setPlaybackSpeed(speed)
    }

    fun getVolume(): Float = synchronized(preparationLock) { renderer?.getVolume() ?: effectiveVolume() }

    private fun effectiveVolume(): Float = if (audioOutputEnabled) currentVolume else 0f

    fun setPreferredDevice(device: Any?) {
        renderer?.setPreferredDevice(device)
    }

    fun getAudioSessionId(): Int = renderer?.getAudioSessionId() ?: 0

    fun getDuration(): Long = decoder?.duration ?: 0L

    val streamFailure: Throwable? get() = decoder?.streamFailure

    fun getCurrentPosition(): Long {
        activeSeekPosition?.let { return it }
        renderer?.let {
            val position = it.getPlayedPositionMs().coerceAtLeast(0L)
            (decoder?.inputStream as? PlaybackPositionInput)?.updatePlaybackPosition(position)
            return position
        }
        return decoder?.getCurrentPosition()?.coerceAtLeast(0L) ?: 0L
    }

    fun getBufferedPositionMs(): Long {
        val d = decoder ?: return 0L
        val dur = d.duration
        if (dur <= 0L) return 0L
        val source = d.inputStream as? SeekableMediaSource
        if (source != null) {
            val totalSize = source.mediaSize ?: 0L
            if (totalSize > 0L) {
                val bufferedBytes = source.bufferedBytePosition
                val fraction = (bufferedBytes.toDouble() / totalSize.toDouble()).coerceIn(0.0, 1.0)
                return (fraction * dur).toLong().coerceIn(0L, dur)
            }
            return getCurrentPosition().coerceIn(0L, dur)
        }
        return dur
    }

    internal fun prebuffer(frameCount: Int = DEFAULT_PREBUFFER_FRAMES): Boolean {
        require(frameCount > 0) { "Prebuffer frame count must be positive" }
        val currentDecoder = decoder ?: return false
        if (isRunning || decodeThread?.isAlive == true || renderThread?.isAlive == true) return false
        return try {
            repeat(frameCount) {
                val frame = currentDecoder.readPCMFrame(null)
                if (frame == null) {
                    // FFmpeg can report a failed media-source read as EOF.
                    lastPrepareFailure = currentDecoder.streamFailure
                    return frameQueue.isNotEmpty()
                }
                frameQueue.put(QueuedAudioFrame(frame, seekRequestVersion, currentDecoder.lastDecodedPtsMs))
            }
            true
        } catch (error: Exception) {
            lastPrepareFailure = currentDecoder.streamFailure ?: error
            false
        }
    }

    fun isPlaying(): Boolean = isRunning && !isPaused && (renderer?.isPlaying() ?: false)

    internal fun reportPlaybackStall(error: Throwable) {
        reportPlaybackFailure(error, AudioPlayerFailureSource.STREAM)
    }

    internal val shouldResumeAfterFailure: Boolean
        get() = playbackWasRequested

    internal val wantsPlayback: Boolean
        get() = playbackWasRequested

    internal val needsProgressSampling: Boolean
        get() = isRunning && !isPaused && playbackWasRequested

    internal val isPrepared: Boolean
        get() = decoder != null

    internal val workersActive: Boolean
        get() = decodeThread?.isAlive == true || renderThread?.isAlive == true

    fun close() {
        if (!beginClose()) return
        finishClose()
    }

    fun closeAsync() {
        if (!beginClose()) return
        Thread(::finishClose).apply {
            name = "AudioClose-Thread"
            isDaemon = true
            start()
        }
    }

    private fun beginClose(): Boolean {
        if (!closeStarted.compareAndSet(false, true)) return false
        acceptingSeeks = false
        isRunning = false
        isPaused = false
        playbackWasRequested = false
        renderer?.pause()
        signalStateChanged()
        return true
    }

    private fun finishClose() {
        val pendingInput =
            synchronized(preparationLock) {
                preparationGeneration++
                preparingInputStream.also { preparingInputStream = null }
            }
        runCatching { pendingInput?.close() }
        val activeSeek =
            synchronized(seekLock) {
                pendingSeek = null
                atEnd = false
                seekRequestVersion++
                activeSeekPosition = null
                seekThread?.also { it.interrupt() }
            }
        val activeDecoder = decodeThread?.also { it.interrupt() }
        val activeRenderer = renderThread?.also { it.interrupt() }
        val currentDecoder = decoder
        val currentRenderer = renderer
        currentDecoder?.cancelPendingReads()

        frameQueue.clear()
        synchronized(ptsLock) {
            anchorAudioPtsMs = null
            anchorPlayedMs = 0L
        }

        try {
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            listOfNotNull(activeSeek, activeDecoder, activeRenderer).forEach { thread ->
                if (thread !== Thread.currentThread()) {
                    val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(1L)
                    thread.join(remainingMillis)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val activeWorkers = listOfNotNull(activeSeek, activeDecoder, activeRenderer).filter { it.isAlive }
        if (activeWorkers.isEmpty()) {
            currentRenderer?.close()
            currentDecoder?.close()
        } else {
            Thread {
                activeWorkers.forEach { runCatching { it.join() } }
                runCatching { currentRenderer?.close() }
                runCatching { currentDecoder?.close() }
            }.apply {
                name = "AudioCleanup-Thread"
                isDaemon = true
                start()
            }
        }

        renderer = null
        decoder = null
        decodeThread = null
        renderThread = null
        synchronized(seekLock) {
            if (seekThread === activeSeek) seekThread = null
            isSeeking = false
            activeSeekPosition = null
            seekFailure = null
            seekLock.notifyAll()
        }
    }

    private fun reportPlaybackFailure(
        error: Throwable,
        source: AudioPlayerFailureSource,
    ) {
        if (playbackFailed.compareAndSet(false, true)) {
            failureSource = source
            if (source != AudioPlayerFailureSource.STREAM) {
                isRunning = false
                renderer?.pause()
            }
            signalStateChanged()
            onPlaybackFailed(this, error)
        }
    }
}

private const val DEFAULT_PREBUFFER_FRAMES = 3
private const val PCM_BYTES_PER_SAMPLE = 2

internal fun awaitAudioDrain(
    shouldContinue: () -> Boolean,
    hasPendingAudio: () -> Boolean,
): Boolean {
    while (shouldContinue()) {
        if (!hasPendingAudio()) return shouldContinue()
        Thread.sleep(5)
    }
    return false
}

internal inline fun playDecodedFrames(
    isRunning: () -> Boolean,
    readFrame: () -> ByteArray?,
    writeFrame: (ByteArray) -> Unit,
): Boolean {
    while (isRunning()) {
        val data = readFrame() ?: return isRunning()
        writeFrame(data)
    }
    return false
}

internal inline fun enqueueDecodedFrame(
    isCurrent: () -> Boolean,
    isPaused: () -> Boolean,
    offer: () -> Boolean,
    awaitResume: () -> Unit,
) {
    while (isCurrent()) {
        if (isPaused()) {
            awaitResume()
        } else if (offer()) {
            return
        }
    }
}
