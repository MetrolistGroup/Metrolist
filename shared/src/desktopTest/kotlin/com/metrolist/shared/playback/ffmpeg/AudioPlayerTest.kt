package com.metrolist.shared.playback.ffmpeg

import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.playback.PlaybackProgressTimeoutException
import com.metrolist.shared.playback.TestPlaybackLine
import com.metrolist.shared.playback.testAudioRenderer
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.LineUnavailableException
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AudioPlayerTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun workerParksUntilResumeAndRechecksStateAfterNotification() {
        val signal = PlaybackWorkerSignal()
        val paused = AtomicBoolean(true)
        val waiting = CountDownLatch(1)
        val rechecked = CountDownLatch(1)
        val checks = AtomicInteger()
        val resumed = CountDownLatch(1)
        val worker =
            thread(isDaemon = true) {
                signal.awaitWhile {
                    if (checks.incrementAndGet() == 1) waiting.countDown() else rechecked.countDown()
                    paused.get()
                }
                resumed.countDown()
            }
        try {
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            signal.signal()
            assertTrue(rechecked.await(5, TimeUnit.SECONDS))
            assertEquals(1L, resumed.count)
            paused.set(false)
            signal.signal()
            assertTrue(resumed.await(5, TimeUnit.SECONDS))
        } finally {
            worker.interrupt()
            worker.join(5_000)
        }
    }

    @Test
    fun pendingDecodedFrameSurvivesPauseAtFullQueue() {
        val frame = byteArrayOf(1, 2, 3, 4)
        val queued = mutableListOf<ByteArray>()
        var paused = false
        var offers = 0
        var waits = 0
        enqueueDecodedFrame(
            isCurrent = { true },
            isPaused = { paused },
            offer = {
                if (++offers == 1) {
                    paused = true
                    false
                } else {
                    queued += frame
                    true
                }
            },
            awaitResume = {
                waits++
                paused = false
            },
        )
        assertEquals(1, waits)
        assertSame(frame, queued.single())
    }

    @Test
    fun pendingDecodedFrameIsDiscardedWhenSeekOrCloseInvalidatesItDuringPause() {
        var current = true
        enqueueDecodedFrame(
            isCurrent = { current },
            isPaused = { true },
            offer = { error("Invalidated frame must not be queued") },
            awaitResume = { current = false },
        )
        assertFalse(current)
    }

    @Test
    fun asynchronousCloseStopsTheAudioLineBeforeReturning() {
        val file = Files.createTempFile("metrolist-audio-close", ".wav")
        val line = TestPlaybackLine()
        val player = AudioPlayer(rendererFactory = { rate, channels -> AudioRenderer(rate, channels, line.line) })
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 40_000))
            assertTrue(player.loadFile(file.toString()))
            line.awaitBuffered()
            val renderer =
                AudioPlayer::class.java
                    .getDeclaredField("renderer")
                    .apply { isAccessible = true }
                    .get(player) as AudioRenderer
            assertTrue(renderer.isPlaying())

            player.closeAsync()
            assertFalse(renderer.isPlaying(), "Retired audio must stop before a replacement becomes audible")
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun streamFailureRetainsBufferedAudioPositionAndPauseIntent() {
        val file = Files.createTempFile("metrolist-buffered-recovery", ".wav")
        val line = TestPlaybackLine()
        val failures = AtomicInteger()
        val player =
            AudioPlayer(
                onPlaybackFailed = { _, _ -> failures.incrementAndGet() },
                rendererFactory = { rate, channels -> AudioRenderer(rate, channels, line.line) },
            )
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 80_000))
            assertTrue(player.loadFile(file.toString()))
            line.awaitBuffered()
            player.reportPlaybackStall(PlaybackProgressTimeoutException())
            assertEquals(1, failures.get())
            assertTrue(player.wantsPlayback)
            assertTrue(player.isPlaying())
            line.advanceUntil(800L)
            assertTrue(player.getCurrentPosition() >= 100L)
            player.pause()
            assertFalse(player.wantsPlayback)
            assertFalse(player.isPlaying())
            val pausedPosition = player.getCurrentPosition()
            player.resume()
            assertTrue(player.wantsPlayback)
            assertTrue(player.isPlaying())
            assertEquals(pausedPosition, player.getCurrentPosition())
            player.reportPlaybackStall(PlaybackProgressTimeoutException())
            assertEquals(1, failures.get())
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun nativeLoaderFailureIsReportedWithoutStartingPlayback() {
        val failure = FFmpegFrameGrabber.Companion.Exception("Native library unavailable", UnsatisfiedLinkError())
        val loadingException =
            FFmpegFrameGrabber::class.java.getDeclaredField("loadingException").apply { isAccessible = true }
        val previous = loadingException.get(null)
        val player = AudioPlayer(rendererFactory = ::testAudioRenderer)
        try {
            loadingException.set(null, failure)
            repeat(2) {
                assertFalse(player.prepareFile("unused.wav", startPaused = true))
                assertSame(failure, player.prepareFailure)
                assertFalse(player.prepareStream(ByteArrayInputStream(byteArrayOf()), maximumSize = 0, startPaused = true))
                assertSame(failure, player.prepareFailure)
                assertFalse(player.isPrepared)
                assertFalse(player.workersActive)
            }
        } finally {
            player.close()
            loadingException.set(null, previous)
        }
    }

    @Test
    fun seekNearEndDoesNotCompleteTheOldPlaybackGeneration() {
        val file = Files.createTempFile("metrolist-audio-player-seek-completion", ".wav")
        val completed = CountDownLatch(1)
        val line = TestPlaybackLine(automatic = false)
        val player =
            AudioPlayer(onPlaybackCompleted = { completed.countDown() }, rendererFactory = {
                rate,
                channels,
                ->
                AudioRenderer(rate, channels, line.line)
            })
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 16_000))
            assertTrue(player.prepareFile(file.toString(), startPaused = true))
            player.play()
            line.advanceUntil(12_000L)
            assertTrue(player.getCurrentPosition() in 1_500..1_600)

            assertTrue(player.seekToAwait(0L, 5_000L), "Seek did not complete")
            assertEquals(1L, completed.count, "Stale EOF completed after seek")
            line.automatic = true
            assertTrue(completed.await(5, TimeUnit.SECONDS), "Seeked playback did not complete")
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun completionWaitsForSlowedAudioToFinishPlaying() {
        val file = Files.createTempFile("metrolist-audio-player-speed", ".wav")
        val completed = CountDownLatch(1)
        val line = TestPlaybackLine()
        val player =
            AudioPlayer(onPlaybackCompleted = { completed.countDown() }, rendererFactory = { rate, channels ->
                AudioRenderer(rate, channels, line.line)
            })
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 8_000))
            player.setPlaybackSpeed(0.5f)
            assertTrue(player.prepareFile(file.toString(), startPaused = true))
            player.play()
            line.advanceUntil(8_000L)
            assertEquals(1L, completed.count, "Completion preceded slowed audio output")
            line.automatic = true
            assertTrue(completed.await(5, TimeUnit.SECONDS), "Playback did not complete")
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun completedPlaybackRemainsSeekableAndResumesAtTheSelectedPosition() {
        val file = Files.createTempFile("metrolist-ended-seek", ".wav")
        val completions = LinkedBlockingQueue<Long>()
        val line = TestPlaybackLine(automatic = true)
        val player =
            AudioPlayer(
                onPlaybackCompleted = { completions.offer(it.getCurrentPosition()) },
                rendererFactory = { rate, channels -> AudioRenderer(rate, channels, line.line) },
            )
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 16_000))
            assertTrue(player.loadFile(file.toString()))
            assertEquals(2_000L, completions.poll(5, TimeUnit.SECONDS))
            assertFalse(player.isPlaying())
            assertFalse(player.wantsPlayback)

            line.automatic = false
            for (position in listOf(1_230L, 740L, 20L)) {
                assertTrue(player.seekToAwait(position, 5_000L), "Seek after completion was rejected")
                assertEquals(position, player.getCurrentPosition())
                assertFalse(player.isPlaying())
                assertFalse(player.wantsPlayback)
            }
            player.resume()
            line.advanceUntil(800L + 16_000L)
            assertTrue(player.wantsPlayback)
            assertTrue(player.isPlaying())
            assertEquals(120L, player.getCurrentPosition())
            assertTrue(completions.isEmpty(), "Seeking must not emit another completion")
            line.automatic = true
            assertEquals(2_000L, completions.poll(5, TimeUnit.SECONDS))
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun resumeAtTheEndRestartsAndDeliversCompletionAgain() {
        val file = Files.createTempFile("metrolist-ended-replay", ".wav")
        val completions = LinkedBlockingQueue<Long>()
        val line = TestPlaybackLine(automatic = true)
        val player =
            AudioPlayer(
                onPlaybackCompleted = { completions.offer(it.getCurrentPosition()) },
                rendererFactory = { rate, channels -> AudioRenderer(rate, channels, line.line) },
            )
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 8_000))
            assertTrue(player.loadFile(file.toString()))
            repeat(3) { replay ->
                assertEquals(1_000L, completions.poll(5, TimeUnit.SECONDS))
                line.automatic = false
                assertFalse(player.wantsPlayback)
                if (replay == 1) assertTrue(player.seekToAwait(player.getDuration(), 5_000L))
                player.resume()
                assertEquals(0L, player.getCurrentPosition())
                line.advanceUntil((replay + 1) * 8_000L + 800L)
                assertTrue(player.isPlaying())
                assertTrue(player.getCurrentPosition() in 1L..100L)
                line.automatic = true
            }
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun pausedPreparationInitializesMediaWithoutStartingWorkers() {
        val file = Files.createTempFile("metrolist-audio-player", ".wav")
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 8_000))
            val player = AudioPlayer(rendererFactory = ::testAudioRenderer)

            val prepared = player.prepareFile(file.toString(), startPaused = true)
            assertTrue(prepared, "prepareFile failed: ${player.prepareFailure?.javaClass?.simpleName}")
            assertTrue(player.isPrepared)
            assertFalse(player.workersActive)
            assertFalse(player.isPlaying())
            assertTrue(player.prebuffer())
            assertEquals(0L, player.getCurrentPosition())

            player.close()
            assertFalse(player.isPrepared)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun prebufferRetainsStreamFailuresHiddenByNativeEof() {
        val bytes = silentWav(sampleRate = 8_000, samples = 8_000 * 120)
        for (failure in listOf(
            SocketTimeoutException("Timed out"),
            SabrProtocolException("Rejected", SabrFailureKind.ATTESTATION_REQUIRED),
        )) {
            val input = BlockingSeekInputStream(bytes)
            val player = AudioPlayer(rendererFactory = ::testAudioRenderer)
            try {
                assertTrue(player.prepareStream(input, bytes.size, startPaused = true))
                input.streamFailure = failure
                assertTrue(player.seekToAwait(60_000L, 5_000L))
                assertFalse(player.prebuffer())
                assertSame(failure, player.prepareFailure)
                assertFalse(player.workersActive)
            } finally {
                player.close()
            }
        }
    }

    @Test
    fun handoffPausePreservesIntentWithoutRequestingStallSampling() {
        val file = Files.createTempFile("metrolist-audio-handoff", ".wav")
        val player = AudioPlayer(rendererFactory = ::testAudioRenderer)
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 40_000))
            assertTrue(player.loadFile(file.toString()))
            assertTrue(player.needsProgressSampling)
            player.pauseForHandoff()
            assertTrue(player.wantsPlayback)
            assertFalse(player.needsProgressSampling)
            player.resume()
            assertTrue(player.needsProgressSampling)
            player.pause()
            assertFalse(player.wantsPlayback)
            assertFalse(player.needsProgressSampling)
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun audioOutputGateMutesTheRendererWithoutChangingPlaybackIntentOrDesiredVolume() {
        val file = Files.createTempFile("metrolist-audio-output-gate", ".wav")
        val player = AudioPlayer(rendererFactory = ::testAudioRenderer)
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 40_000))
            player.setVolume(0.35f)
            player.setAudioOutputEnabled(false)
            assertTrue(player.prepareFile(file.toString(), startPaused = true))
            player.play()

            assertTrue(player.wantsPlayback)
            assertEquals(0f, player.getVolume(), 0f)

            player.setVolume(0.72f)
            assertTrue(player.wantsPlayback)
            assertEquals(0f, player.getVolume(), 0f)

            player.setAudioOutputEnabled(true)
            assertTrue(player.wantsPlayback)
            assertEquals(0.72f, player.getVolume(), 0f)
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun failedPreparationLeavesNoPreparedPlayerOrWorkers() {
        val player = AudioPlayer(rendererFactory = ::testAudioRenderer)

        assertFalse(player.prepareFile("/path/that/does/not/exist.wav", startPaused = true))
        assertFalse(player.isPrepared)
        assertFalse(player.workersActive)
        assertNotNull(player.prepareFailure)
    }

    @Test
    fun unavailableAudioOutputFailsPreparationWithoutStartingPlayback() {
        val file = Files.createTempFile("metrolist-audio-unavailable", ".wav")
        val failure = LineUnavailableException()
        val player = AudioPlayer(rendererFactory = { _, _ -> throw failure })
        try {
            Files.write(file, silentWav(sampleRate = 8_000, samples = 8_000))
            assertFalse(player.prepareFile(file.toString(), startPaused = true))
            assertSame(failure, player.prepareFailure)
            assertFalse(player.isPrepared)
            assertFalse(player.isPlaying())
            assertFalse(player.workersActive)
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun asynchronousCloseCancelsInputThatIsStillPreparing() {
        val input = BlockingInputStream()
        val player = AudioPlayer(rendererFactory = ::testAudioRenderer)
        var prepared = true
        val preparation =
            thread(isDaemon = true) {
                prepared = player.prepareStream(input, maximumSize = 1_024, startPaused = false)
            }

        assertTrue(input.readStarted.await(30, TimeUnit.SECONDS), "FFmpeg did not begin reading the test input")
        player.closeAsync()
        preparation.join(5_000)

        assertFalse(preparation.isAlive, "Audio preparation remained blocked after close")
        assertFalse(prepared)
        assertFalse(player.isPrepared)
    }

    @Test
    fun renderedPlaybackPositionAdvancesTheStreamReadaheadClock() {
        val bytes = silentWav(sampleRate = 8_000, samples = 40_000)
        val input = BlockingSeekInputStream(bytes)
        val line = TestPlaybackLine()
        val player = AudioPlayer(rendererFactory = { rate, channels -> AudioRenderer(rate, channels, line.line) })
        try {
            assertTrue(player.prepareStream(input, bytes.size, startPaused = true))
            player.play()
            line.advanceUntil(2_000L)
            val position = player.getCurrentPosition()
            assertTrue(position >= 250L, "Audio renderer did not advance")
            assertEquals(position, input.playbackPositionMs, "SABR must see the audible position, not a frozen or decode-ahead clock")
            player.pause()
            assertEquals(player.getCurrentPosition(), input.playbackPositionMs)
        } finally {
            player.close()
        }
    }

    @Test
    fun unknownRemoteSizeDoesNotReportTheWholeTrackBuffered() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/ffmpeg/opus-251.webm")).use { it.readBytes() }
        val player = AudioPlayer(rendererFactory = ::testAudioRenderer)
        try {
            assertTrue(player.prepareStream(BlockingSeekInputStream(bytes, knownSize = false), bytes.size))
            assertTrue(player.getDuration() > 0)
            assertEquals(0L, player.getBufferedPositionMs())
        } finally {
            player.close()
        }
    }

    @Test
    fun pendingSeekPositionBecomesMasterClockImmediately() {
        val bytes = silentWav(sampleRate = 8_000, samples = 8_000)
        val input = BlockingSeekInputStream(bytes)
        val player = AudioPlayer(rendererFactory = ::testAudioRenderer)
        try {
            assertTrue(player.prepareStream(input, bytes.size, startPaused = true))

            input.blockSeeks = true
            player.seekTo(500L)
            assertTrue(input.seekStarted.await(5, TimeUnit.SECONDS), "FFmpeg did not begin the seek")

            assertEquals(500L, player.getCurrentPosition())
        } finally {
            input.allowSeek.countDown()
            player.close()
        }
    }

    private fun silentWav(
        sampleRate: Int,
        samples: Int,
    ): ByteArray {
        val dataSize = samples * 2
        val bytes = ByteArray(44 + dataSize)

        fun putAscii(
            offset: Int,
            value: String,
        ) {
            value.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
        }

        fun putInt(
            offset: Int,
            value: Int,
        ) {
            ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        }

        fun putShort(
            offset: Int,
            value: Int,
        ) {
            ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        }

        putAscii(0, "RIFF")
        putInt(4, bytes.size - 8)
        putAscii(8, "WAVEfmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2)
        putShort(34, 16)
        putAscii(36, "data")
        putInt(40, dataSize)
        return bytes
    }

    private class BlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            val byte = ByteArray(1)
            return read(byte, 0, 1)
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            readStarted.countDown()
            closed.await()
            throw InterruptedIOException("closed")
        }

        override fun close() {
            closed.countDown()
        }
    }

    private class BlockingSeekInputStream(
        private val bytes: ByteArray,
        private val knownSize: Boolean = true,
    ) : InputStream(),
        SeekableMediaInput,
        PlaybackPositionInput {
        @Volatile
        override var streamFailure: Throwable? = null

        @Volatile
        var playbackPositionMs = 0L

        @Volatile
        private var position = 0L

        @Volatile
        var blockSeeks = false

        val seekStarted = CountDownLatch(1)
        val allowSeek = CountDownLatch(1)

        override val mediaSize: Long? = bytes.size.toLong().takeIf { knownSize }

        override val mediaPosition: Long
            get() = position

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }

        @Synchronized
        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val count = readAtMediaPosition(position, buffer, offset, length)
            if (count > 0) position += count
            return count
        }

        override fun readAtMediaPosition(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            streamFailure?.let { throw it }
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun seekToMediaPosition(position: Long): Long {
            if (blockSeeks) {
                seekStarted.countDown()
                allowSeek.await()
            }
            return position.coerceIn(0L, bytes.size.toLong()).also { this.position = it }
        }

        override fun updatePlaybackPosition(positionMs: Long) {
            playbackPositionMs = positionMs
        }

        override fun close() {
            allowSeek.countDown()
        }
    }
}
