package com.metrolist.shared.playback

import com.metrolist.shared.playback.ffmpeg.AudioRenderer
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

internal fun testAudioRenderer(
    sampleRate: Int,
    channels: Int,
): AudioRenderer = AudioRenderer(sampleRate, channels, TestPlaybackLine().line)

internal class TestPlaybackLine(
    @Volatile
    var automatic: Boolean = false,
) {
    private var format = AudioFormat(8_000f, 16, 1, true, false)
    private var capacity = 0
    private var open = false
    private var active = false
    private var writtenFrames = 0L
    private var playedFrames = 0L
    private val writes = LinkedBlockingQueue<Long>()
    private val buffered = CountDownLatch(1)
    private val lock = Any()

    fun awaitBuffered() {
        check(buffered.await(5, TimeUnit.SECONDS)) { "Audio output did not buffer PCM" }
    }

    fun advance(frames: Long) =
        synchronized(lock) {
            playedFrames = minOf(writtenFrames, playedFrames + frames)
        }

    fun advanceUntil(frame: Long) {
        while (synchronized(lock) { playedFrames < frame }) {
            checkNotNull(writes.poll(5, TimeUnit.SECONDS)) { "Audio output stopped before frame $frame" }
            synchronized(lock) { playedFrames = minOf(writtenFrames, frame) }
        }
    }

    val line =
        Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            synchronized(lock) {
                if (automatic && active) playedFrames = writtenFrames
                when (method.name) {
                    "open" -> {
                        format = args!![0] as AudioFormat
                        capacity = args[1] as Int
                        open = true
                        null
                    }

                    "close" -> {
                        open = false
                        active = false
                        null
                    }

                    "start" -> {
                        active = true
                        null
                    }

                    "stop" -> {
                        active = false
                        null
                    }

                    "isOpen" -> {
                        open
                    }

                    "isActive", "isRunning" -> {
                        active
                    }

                    "available" -> {
                        if (writtenFrames > 0L) buffered.countDown()
                        check(capacity > 0) { "Audio output opened with no capacity" }
                        capacity - ((writtenFrames - playedFrames) * format.frameSize).toInt()
                    }

                    "getBufferSize" -> {
                        capacity
                    }

                    "getFormat" -> {
                        format
                    }

                    "getLongFramePosition" -> {
                        playedFrames
                    }

                    "getFramePosition" -> {
                        playedFrames.toInt()
                    }

                    "getMicrosecondPosition" -> {
                        (playedFrames * 1_000_000 / format.sampleRate).toLong()
                    }

                    "write" -> {
                        (args!![2] as Int).also {
                            writtenFrames += it / format.frameSize
                            writes.offer(writtenFrames)
                        }
                    }

                    "flush" -> {
                        writtenFrames = playedFrames
                        null
                    }

                    "isControlSupported" -> {
                        false
                    }

                    "getControls" -> {
                        emptyArray<Control>()
                    }

                    "drain", "addLineListener", "removeLineListener" -> {
                        null
                    }

                    "getLevel" -> {
                        0f
                    }

                    "getLineInfo" -> {
                        DataLine.Info(SourceDataLine::class.java, format)
                    }

                    "toString" -> {
                        "TestPlaybackLine"
                    }

                    "hashCode" -> {
                        System.identityHashCode(this)
                    }

                    "equals" -> {
                        false
                    }

                    else -> {
                        error("Unexpected output call: ${method.name}")
                    }
                }
            }
        } as SourceDataLine
}
