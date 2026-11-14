package com.metrolist.shared.playback.ffmpeg

import com.sun.net.httpserver.HttpServer
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioDecoderTest {
    @Test(timeout = 30_000)
    fun releaseWaitsForTheGrabbedFrameConsumer() {
        val grabbed = CountDownLatch(1)
        val finishCopy = CountDownLatch(1)
        val releaseStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val pcm = AtomicReference<ByteArray?>()
        val failure = AtomicReference<Throwable?>()
        lateinit var decoder: AudioDecoder
        decoder =
            AudioDecoder(javaClass.getResourceAsStream("/ffmpeg/opus-251.webm")!!, 0) { samples ->
                grabbed.countDown()
                check(finishCopy.await(5, TimeUnit.SECONDS))
                check(decoder.frameGrabber.started)
                copyPcmSamples(samples)
            }
        val reader =
            thread {
                runCatching { pcm.set(decoder.readPCMFrame()) }.onFailure(failure::set)
            }
        var closer: Thread? = null
        try {
            assertTrue(grabbed.await(5, TimeUnit.SECONDS))
            closer =
                thread {
                    releaseStarted.countDown()
                    decoder.close()
                    released.countDown()
                }
            assertTrue(releaseStarted.await(5, TimeUnit.SECONDS))
            assertFalse(released.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finishCopy.countDown()
            reader.join(5_000)
            closer?.join(5_000) ?: decoder.close()
        }
        assertEquals(null, failure.get())
        assertTrue(pcm.get()!!.isNotEmpty())
        assertEquals(0L, released.count)
    }

    @Test(timeout = 30_000)
    fun decodesYoutubeAudioContainersThroughStreamInput() {
        for ((name, sampleRate) in listOf("opus-251.webm" to 48_000, "aac-140.m4a" to 44_100)) {
            javaClass.getResourceAsStream("/ffmpeg/$name")!!.use { input ->
                assertPcm(name, AudioDecoder(input, maximumSize = 0), sampleRate)
            }
        }
    }

    @Test(timeout = 30_000)
    fun decodesHlsAacThroughHttpPlaylistAndSegments() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val bytes = javaClass.getResourceAsStream("/ffmpeg${exchange.requestURI.path}")!!.use { it.readBytes() }
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            assertPcm("hls.m3u8", AudioDecoder("http://127.0.0.1:${server.address.port}/hls.m3u8"), 44_100)
        } finally {
            server.stop(0)
        }
    }

    private fun assertPcm(
        name: String,
        decoder: AudioDecoder,
        sampleRate: Int,
    ) {
        try {
            assertEquals(sampleRate, decoder.sampleRate)
            assertEquals(2, decoder.audioChannels)
            var count = 0L
            var energy = 0.0
            val digest = MessageDigest.getInstance("SHA-256")
            while (true) {
                val pcm = decoder.readPCMFrame() ?: break
                assertEquals(0, pcm.size % (Short.SIZE_BYTES * decoder.audioChannels))
                digest.update(pcm)
                val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (samples.hasRemaining()) {
                    val sample = samples.get().toDouble()
                    energy += sample * sample
                    count++
                }
                assertTrue(count < sampleRate * 3L * decoder.audioChannels, "$name did not reach EOF")
            }
            val seconds = count.toDouble() / (sampleRate * decoder.audioChannels)
            assertTrue(abs(seconds - 2.0) < 0.06, "$name decoded $seconds seconds instead of 2")
            val rms = sqrt(energy / count) / Short.MAX_VALUE
            assertTrue(rms in 0.04..0.09, "$name decoded silent or corrupt PCM: RMS=$rms")
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            println("$name: samples=$count seconds=$seconds rms=$rms sha256=$sha256")
        } finally {
            decoder.close()
        }
    }
}
