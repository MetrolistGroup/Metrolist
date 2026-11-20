package com.metrolist.shared.playback.ffmpeg

import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AudioOutputTest {
    @Test
    fun retriesBusyWindowsStereoOutputBeforeQuadFallback() {
        val attemptedChannels = mutableListOf<Int>()
        val stereoAttempts = AtomicInteger()
        val delays = mutableListOf<Long>()
        val line =
            Proxy.newProxyInstance(
                SourceDataLine::class.java.classLoader,
                arrayOf(SourceDataLine::class.java),
            ) { _, method, args ->
                if (method.name == "open") {
                    val outputFormat = args!![0] as AudioFormat
                    attemptedChannels += outputFormat.channels
                    if (outputFormat.channels == 2 && stereoAttempts.getAndIncrement() == 0) {
                        throw LineUnavailableException()
                    }
                }
                null
            } as SourceDataLine

        val openedLine =
            openAudioLine(
                AudioFormat(44_100f, 16, 2, true, false),
                4_096,
                "Windows 11",
                null,
                { emptyList() },
                { _, _ -> line },
                delays::add,
            )

        assertSame(line, openedLine)
        assertEquals(listOf(2, 2), attemptedChannels)
        assertEquals(listOf(100L), delays)
    }
}
