package com.metrolist.shared.playback.ffmpeg

import java.lang.reflect.Proxy
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AudioOutputTest {
    private val loopback = mixer("Loopback [plughw:1,0]")
    private val hardware = mixer("Speakers [plughw:0,0]")
    private val systemDefault = mixer("ALSA playback [default]")
    private val pulse = mixer("ALSA playback [pulse]")
    private val format = AudioFormat(48_000f, 16, 2, true, false)

    @Test
    fun selectsSystemRoutesWithoutHardwareOrDiscardOutputs() {
        assertEquals(
            listOf(systemDefault, pulse),
            audioOutputMixers("Linux", null, listOf(loopback, pulse, mixer("Discard [null]"), hardware, systemDefault)),
        )
        assertEquals(emptyList(), audioOutputMixers("Linux", null, listOf(loopback, hardware)))
        assertEquals(listOf(null), audioOutputMixers("Linux", "#Speakers", listOf(systemDefault)))
        assertEquals(listOf(null, hardware), audioOutputMixers("Windows 11", null, listOf(hardware)))
    }

    @Test
    fun retriesBusyDevicesWithBackoffAndClosesFailedLines() {
        var attempts = 0
        var closed = 0
        val waits = mutableListOf<Long>()
        val output = line { }
        assertSame(
            output,
            openAudioLine(format, 4_096, "Linux", null, { listOf(systemDefault) }, { _, _ ->
                attempts++
                if (attempts == 4) output else line(onClose = { closed++ }) { throw LineUnavailableException() }
            }, waits::add),
        )
        assertEquals(4, attempts)
        assertEquals(3, closed)
        assertEquals(listOf(100L, 200L, 400L), waits)
    }

    @Test
    fun triesAnotherSystemMixerWhenTheDefaultIsUnsupportedOrBusy() {
        for (failure in listOf(IllegalArgumentException(), LineUnavailableException())) {
            val tried = mutableListOf<Mixer.Info?>()
            val output = line { }
            assertSame(
                output,
                openAudioLine(format, 4_096, "Linux", null, { listOf(systemDefault, pulse) }, { mixer, _ ->
                    tried += mixer
                    if (mixer === systemDefault) throw failure else output
                }, { error("Fallback must not wait") }),
            )
            assertEquals<List<Mixer.Info?>>(listOf(systemDefault, pulse), tried)
        }
    }

    @Test
    fun rediscoversDevicesAndStopsAfterTheRetryBudget() {
        var discoveries = 0
        val waits = mutableListOf<Long>()
        assertFailsWith<LineUnavailableException> {
            openAudioLine(format, 4_096, "Linux", null, {
                discoveries++
                listOf(loopback, hardware)
            }, { _, _ -> error("Must not open arbitrary hardware") }, waits::add)
        }
        assertEquals(4, discoveries)
        assertEquals(listOf(100L, 200L, 400L), waits)
    }

    @Test
    fun interruptionStopsRetries() {
        assertFailsWith<InterruptedException> {
            openAudioLine(format, 4_096, "Linux", null, { emptyList() }, { _, _ -> error("No output") }, {
                throw InterruptedException()
            })
        }
    }

    private fun line(
        onClose: () -> Unit = {},
        onOpen: () -> Unit,
    ): SourceDataLine =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, _ ->
            when (method.name) {
                "open" -> onOpen()
                "close" -> onClose()
                else -> error("Unexpected line operation: ${method.name}")
            }
            null
        } as SourceDataLine

    private fun mixer(name: String): Mixer.Info = object : Mixer.Info(name, "ALSA", "PCM", "1") {}
}
