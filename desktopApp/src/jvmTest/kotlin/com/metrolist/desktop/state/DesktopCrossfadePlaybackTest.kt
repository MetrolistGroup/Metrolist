package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.DesktopQueueTransitions
import com.metrolist.desktop.state.playback.DesktopVideoLifecycle
import com.metrolist.desktop.state.playback.DesktopVideoPlaybackStatus
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.ffmpeg.AudioPlayer
import com.metrolist.shared.playback.ffmpeg.AudioRenderer
import com.metrolist.shared.playback.playbackCrossfadeGains
import com.metrolist.shared.playback.playbackNormalizationGain
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCrossfadePlaybackTest {
    @Test
    fun bothPlayersRenderUntilPromotionWithComposedComplementaryGains() =
        runBlocking {
            withPlayback {
                startFade()
                assertEquals(0f, incoming.renderer.getVolume())
                for (progress in listOf(0.25f, 0.5f, 0.75f)) {
                    advanceFade(progress)
                    assertTrue(outgoing.renderer.isPlaying())
                    assertTrue(incoming.renderer.isPlaying())
                    assertTrue(outgoing.open)
                    assertEquals(first.id, player.currentSong.value?.id)
                    assertEquals(first.id, queue.currentSong()?.id)
                    assertEquals(0, promotions)
                    assertEquals(
                        1f,
                        outgoing.renderer.getVolume() / outgoingGain + incoming.renderer.getVolume() / incomingGain,
                        0.00001f,
                    )
                }
                incoming.advanceTo(1_500L)
                advanceFade(1f)
                awaitCondition { promotions == 1 && !outgoing.open }
                assertTrue(incoming.open)
                assertTrue(incoming.renderer.isPlaying())
                assertEquals(incomingGain, player.outputVolume, 0.00001f)
                assertEquals(next.id, player.currentSong.value?.id)
                assertEquals(next.id, queue.currentSong()?.id)
                assertEquals(1_500L, player.currentPosition.value)
                assertFalse(transitions.isCrossfading)
            }
        }

    @Test
    fun pauseStopsBothPlayersAndResumeContinuesTheSameFade() =
        runBlocking {
            withPlayback {
                startFade()
                advanceFade(0.5f)
                player.pause()
                withTimeout(5_000L) { player.isPlaying.first { !it } }
                assertFalse(outgoing.renderer.isPlaying())
                assertFalse(incoming.renderer.isPlaying())
                val gains = outgoing.renderer.getVolume() to incoming.renderer.getVolume()
                delay(150L)
                assertEquals(gains, outgoing.renderer.getVolume() to incoming.renderer.getVolume())
                assertEquals(0, promotions)
                assertTrue(outgoing.open)
                player.resume()
                awaitCondition { outgoing.renderer.isPlaying() && incoming.renderer.isPlaying() }
                advanceFade(0.75f)
                assertTrue(outgoing.open)
            }
        }

    @Test
    fun cancellationQueueChangesDisallowedPlaybackAndIncomingFailureRestoreTheOutgoingTrack() =
        runBlocking {
            for (abort in listOf("cancel", "queue", "disallowed", "failure")) {
                withPlayback {
                    startFade()
                    advanceFade(0.5f)
                    when (abort) {
                        "cancel" -> {
                            transitions.cancel()
                        }

                        "queue" -> {
                            queue.setQueue(listOf(first, next))
                            transitions.queueChanged()
                        }

                        "disallowed" -> {
                            allowed = false
                            transitions.queueChanged()
                        }

                        "failure" -> {
                            failIncoming()
                        }
                    }
                    awaitCondition { !transitions.isCrossfading && !incoming.open }
                    assertTrue(outgoing.open, abort)
                    assertTrue(outgoing.renderer.isPlaying(), abort)
                    assertEquals(outgoingGain, outgoing.renderer.getVolume(), 0.00001f, abort)
                    assertEquals(first.id, player.currentSong.value?.id, abort)
                    assertEquals(0, promotions, abort)
                }
            }
        }

    @Test
    fun outgoingEofPromotesBeforeTheDeclaredFadeEnd() =
        runBlocking {
            withPlayback {
                startFade()
                advanceFade(0.5f)
                val outgoingInstance = player.currentPlaybackInstanceId
                assertTrue(transitions.onEof(outgoingInstance))
                awaitCondition { promotions == 1 && !outgoing.open }
                assertEquals(next.id, player.currentSong.value?.id)
                assertTrue(incoming.renderer.isPlaying())
                assertEquals(incomingGain, player.outputVolume, 0.00001f)
                assertTrue(transitions.onEof(outgoingInstance))
                assertEquals(1, promotions)
            }
        }

    @Test
    fun incomingFailureAfterConsumedEofReturnsCompletionToTheQueue() =
        runBlocking {
            withPlayback {
                startFade()
                advanceFade(0.5f)
                assertTrue(transitions.onEof(player.currentPlaybackInstanceId))
                failIncoming()
                awaitCondition { unconsumedEofs == 1 && !incoming.open }
                assertFalse(transitions.isCrossfading)
                assertEquals(0, promotions)
                assertTrue(outgoing.open)
                assertEquals(outgoingGain, player.outputVolume, 0.00001f)
            }
        }

    @Test
    fun sameAlbumGaplessKeepsTheNextPlayerPausedUntilEof() =
        runBlocking {
            withPlayback {
                prepare(gapless = true)
                player.resume()
                withTimeout(5_000L) { player.isPlaying.first { it } }
                outgoing.advanceTo(59_000L)
                withTimeout(5_000L) { player.currentPosition.first { it == 59_000L } }
                assertFalse(transitions.isCrossfading)
                assertFalse(incoming.renderer.isPlaying())
                assertEquals(0f, incoming.renderer.getVolume())
                assertEquals(outgoingGain, outgoing.renderer.getVolume(), 0.00001f)
                assertTrue(transitions.onEof(player.currentPlaybackInstanceId))
                awaitCondition { promotions == 1 && !outgoing.open }
                assertEquals(next.id, player.currentSong.value?.id)
            }
        }

    private suspend fun CoroutineScope.withPlayback(block: suspend Playback.() -> Unit) {
        Settings.replaceSharedForTest(
            Settings(PropertiesSettings(Properties())).apply {
                crossfadeEnabled = true
                audioNormalization = true
            },
        )
        val playback = Playback(this)
        try {
            playback.block()
        } finally {
            playback.eofObserver.cancel()
            playback.transitions.cancel()
            playback.video.close()
            playback.player.release()
            awaitCondition { playback.outputs.all { !it.open } }
            Files.deleteIfExists(playback.file)
            Settings.replaceSharedForTest(null)
        }
    }

    private class Playback(
        scope: CoroutineScope,
    ) {
        val file = Files.createTempFile("desktop-crossfade", ".wav")
        val outputs = CopyOnWriteArrayList<Output>()
        val outgoing get() = outputs[0]
        val incoming get() = outputs[1]
        val player =
            MusicPlayer(rendererFactory = { rate, channels ->
                Output(rate, channels).also(outputs::add).renderer
            })
        val first = SongItem("outgoing", "Outgoing", null, emptyList(), null)
        val next = first.copy(id = "incoming")
        val queue = MusicQueue().apply { setQueue(listOf(first, next)) }
        val outgoingResource = PlaybackResource(first.id, file.toString(), emptyMap(), 0.0, null)
        val incomingResource = outgoingResource.copy(videoId = next.id, loudnessDb = -6.0)
        val outgoingGain = 0.4f * playbackNormalizationGain(outgoingResource.loudnessDb, true)
        val incomingGain = 0.4f * playbackNormalizationGain(incomingResource.loudnessDb, true)
        var allowed = true
        var promotions = 0
        var unconsumedEofs = 0
        val video =
            DesktopVideoLifecycle(
                scope,
                player,
                queue,
                status = { DesktopVideoPlaybackStatus(first, 0L, { false }, false, player.volume.value, true, videoVisible = false) },
                onUnavailable = {},
                fetchSource = { _, _ -> null },
            )
        val transitions =
            DesktopQueueTransitions(
                scope,
                player,
                queue,
                video,
                fetch = { incomingResource },
                onSongChanged = { _, _, previous ->
                    assertEquals(first.id, previous)
                    promotions++
                },
                onUnconsumedEof = { unconsumedEofs++ },
                episodeResumePosition = { _, _ -> 0L },
                allowed = { allowed },
            )
        val eofObserver = scope.launch { player.onEOFPlaybackInstance.collect { transitions.onEof(it) } }

        suspend fun prepare(gapless: Boolean = false) {
            val dataSize = 8_000 * 60 * 2
            val wav =
                ByteBuffer
                    .allocate(44 + dataSize)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply {
                        put("RIFF".toByteArray())
                        putInt(capacity() - 8)
                        put("WAVEfmt ".toByteArray())
                        putInt(16)
                        putShort(1)
                        putShort(1)
                        putInt(8_000)
                        putInt(16_000)
                        putShort(2)
                        putShort(16)
                        put("data".toByteArray())
                        putInt(dataSize)
                    }.array()
            Files.write(file, wav)
            player.setVolume(80)
            player.setSleepTimerGain(0.5f)
            assertTrue(player.playPaused(first, outgoingResource))
            transitions.nearEnd(next, gapless, fadeDurationMs = 2_000L)
            val tokenField = DesktopQueueTransitions::class.java.getDeclaredField("preloadToken").apply { isAccessible = true }
            awaitCondition { tokenField.get(transitions) != null }
        }

        suspend fun startFade() {
            prepare()
            player.resume()
            awaitCondition { outgoing.renderer.isPlaying() }
            outgoing.advanceTo(58_000L)
            awaitCondition({
                "Fade did not start: position=${player.currentPosition.value}, duration=${player.duration.value}, " +
                    "playing=${player.isPlaying.value}, crossfading=${transitions.isCrossfading}, nextFailed=${player.nextPlayerFailed}"
            }) { transitions.isCrossfading && incoming.renderer.isPlaying() }
        }

        suspend fun advanceFade(progress: Float) {
            outgoing.advanceTo(58_000L + (2_000 * progress).toLong())
            val (out, into) = playbackCrossfadeGains(progress)
            awaitCondition({
                "Fade did not reach $progress: position=${player.currentPosition.value}, " +
                    "gains=${outgoing.renderer.getVolume()},${incoming.renderer.getVolume()}, promotions=$promotions"
            }) {
                if (progress == 1f) {
                    promotions == 1
                } else {
                    abs(outgoing.renderer.getVolume() - outgoingGain * out) < 0.00001f &&
                        abs(incoming.renderer.getVolume() - incomingGain * into) < 0.00001f
                }
            }
        }

        fun failIncoming() {
            val audio =
                MusicPlayer::class.java
                    .getDeclaredField("nextPlayer")
                    .apply { isAccessible = true }
                    .get(player)
            AudioPlayer::class.java
                .declaredMethods
                .single { it.name.startsWith("reportPlaybackStall") }
                .apply { isAccessible = true }
                .invoke(audio, IOException("Incoming stream failed"))
        }
    }

    private class Output(
        rate: Int,
        channels: Int,
    ) {
        @Volatile var open = false

        @Volatile private var running = false
        private var format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        private var capacity = 0

        @Volatile private var writtenBytes = 0

        @Volatile private var playedFrames = 0L

        suspend fun advanceTo(positionMs: Long) {
            val frames = (positionMs * format.frameRate / 1_000).toLong()
            awaitCondition { writtenBytes >= frames * format.frameSize }
            playedFrames = frames
        }

        private val line =
            Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
                when (method.name) {
                    "open" -> {
                        format = args!![0] as AudioFormat
                        capacity = (format.frameRate * format.frameSize * 60).toInt()
                        open = true
                        null
                    }

                    "close" -> {
                        open = false
                        null
                    }

                    "start" -> {
                        running = true
                        null
                    }

                    "stop" -> {
                        running = false
                        null
                    }

                    "isOpen" -> {
                        open
                    }

                    "isActive", "isRunning" -> {
                        running
                    }

                    "isControlSupported" -> {
                        false
                    }

                    "available" -> {
                        capacity - writtenBytes + (playedFrames * format.frameSize).toInt()
                    }

                    "getBufferSize" -> {
                        capacity
                    }

                    "getFormat" -> {
                        format
                    }

                    "getFramePosition" -> {
                        playedFrames.toInt()
                    }

                    "getLongFramePosition" -> {
                        playedFrames
                    }

                    "getMicrosecondPosition" -> {
                        (playedFrames * 1_000_000 / format.frameRate).toLong()
                    }

                    "write" -> {
                        (args!![2] as Int).also { writtenBytes += it }
                    }

                    else -> {
                        null
                    }
                }
            } as SourceDataLine
        val renderer: AudioRenderer =
            AudioRenderer::class.java
                .getConstructor(
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    SourceDataLine::class.java,
                    Function0::class.java,
                    Function0::class.java,
                ).newInstance(rate, channels, line, { false to false }, { null as EqualizerProfile? })
    }

    companion object {
        private suspend fun awaitCondition(
            message: () -> String = { "Playback condition was not reached" },
            condition: () -> Boolean,
        ) {
            try {
                withTimeout(5_000L) {
                    while (!condition()) delay(10L)
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError(message(), timeout)
            }
        }
    }
}
