/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.playback

import com.metrolist.shared.equalizer.NoiseCancellation
import com.metrolist.shared.equalizer.NoiseMicrophone
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** Captures the local microphone for feed-forward noise reduction; no sample is sent anywhere. */
object DesktopNoiseMicrophone : NoiseMicrophone {
    private val format =
        AudioFormat(
            NoiseCancellation.MICROPHONE_SAMPLE_RATE.toFloat(),
            16,
            1,
            true,
            false,
        )

    @Volatile private var captureThread: Thread? = null

    override val isSupported: Boolean
        get() = AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, format))

    override fun hasPermission(): Boolean = isSupported

    override fun requestPermission() = Unit

    override fun start(
        sampleRate: Int,
        onSamples: (ShortArray, Int, Int) -> Unit,
    ): Boolean {
        if (captureThread?.isAlive == true) return true
        val line =
            try {
                AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
            } catch (_: Exception) {
                return false
            }
        return try {
            line.open(format, sampleRate / 50 * 2)
            line.start()
            val thread =
                Thread(
                    {
                        val bytes = ByteArray(sampleRate / 50 * 2)
                        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        val samples = ShortArray(bytes.size / 2)
                        try {
                            while (!Thread.currentThread().isInterrupted) {
                                val read = line.read(bytes, 0, bytes.size)
                                if (read < 0) break
                                buffer.rewind()
                                val count = read / 2
                                for (index in 0 until count) samples[index] = buffer.getShort()
                                onSamples(samples, count, line.format.sampleRate.toInt())
                            }
                        } catch (_: Exception) {
                            // Capture ended; the line is released below.
                        } finally {
                            line.stop()
                            line.close()
                        }
                    },
                    "MetrolistNoiseMicrophone",
                ).apply { isDaemon = true }
            captureThread = thread
            thread.start()
            true
        } catch (_: Exception) {
            line.close()
            false
        }
    }

    override fun stop() {
        captureThread?.interrupt()
        captureThread = null
    }
}
