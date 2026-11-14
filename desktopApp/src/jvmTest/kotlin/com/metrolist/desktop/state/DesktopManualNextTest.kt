package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.DesktopQueueTransitions
import com.metrolist.desktop.state.playback.DesktopVideoLifecycle
import com.metrolist.desktop.state.playback.DesktopVideoPlaybackStatus
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.ffmpeg.AudioRenderer
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopManualNextTest {
    @Test
    fun manualPromotionReusesPreparationAndPlaybackIntentButRejectsChangedOccurrencesAndPodcasts() =
        runTest {
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            val file = Files.createTempFile("manual-next", ".wav")
            try {
                Files.write(file, silentWav())
                for (startPlaying in listOf(false, true)) {
                    for (target in listOf("same", "changed", "replaced", "podcast")) {
                        val preparations = AtomicInteger()
                        val player =
                            MusicPlayer(rendererFactory = { rate, channels ->
                                preparations.incrementAndGet()
                                testRenderer(rate, channels)
                            })
                        val first = SongItem("first", "First", null, emptyList(), null)
                        val next = first.copy(id = "next", podcastId = "podcast".takeIf { target == "podcast" })
                        val queue = MusicQueue().apply { setQueue(listOf(first, next)) }
                        val video =
                            DesktopVideoLifecycle(
                                this,
                                player,
                                queue,
                                status = { DesktopVideoPlaybackStatus(first, 0L, { false }, false, 1f, true, videoVisible = false) },
                                onUnavailable = {},
                                fetchSource = { _, _ -> null },
                            )
                        var publications = 0
                        val resource = PlaybackResource(next.id, file.toString(), emptyMap(), null, null)
                        val transitions =
                            DesktopQueueTransitions(
                                this,
                                player,
                                queue,
                                video,
                                fetch = { resource },
                                onSongChanged = { song, resolved, _ ->
                                    assertEquals(next, song)
                                    assertEquals(resource, resolved)
                                    publications++
                                },
                                onUnconsumedEof = {},
                                episodeResumePosition = { _, _ -> 0L },
                            )
                        try {
                            assertTrue(player.playPaused(first, resource.copy(videoId = first.id)))
                            transitions.nearEnd(next, gapless = true)
                            runCurrent()
                            val job = DesktopQueueTransitions::class.java.getDeclaredField("preloadJob").apply { isAccessible = true }
                            (job.get(transitions) as Job).join()
                            assertEquals(2, preparations.get())
                            if (target == "changed") queue.setQueue(listOf(first, next))
                            if (target == "replaced") queue.updateItems(mapOf(1 to next.copy(id = "replacement")))
                            var started = false
                            val promoted = transitions.promoteNext(startPlaying) { started = true }
                            assertEquals(target == "same", promoted)
                            assertEquals(promoted, started)
                            assertEquals(if (promoted) 1 else 0, publications)
                            assertEquals(if (promoted) next else first, queue.currentSong())
                            assertEquals(if (promoted) next else first, player.currentSong.value)
                            assertEquals(promoted && startPlaying, player.wantsPlayback)
                            assertEquals(2, preparations.get())
                            assertFalse(transitions.promoteNext(startPlaying) {})
                        } finally {
                            transitions.cancel()
                            video.close()
                            player.release()
                        }
                    }
                }
            } finally {
                // release() closes the decoder asynchronously; Windows refuses to delete a file it still has open.
                runCatching { Files.deleteIfExists(file) }.onFailure { file.toFile().deleteOnExit() }
                Settings.replaceSharedForTest(null)
            }
        }

    private fun silentWav(): ByteArray =
        ByteBuffer
            .allocate(44 + 160_000)
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
                putInt(160_000)
            }.array()

    private fun outputLine(): SourceDataLine {
        var format = AudioFormat(8_000f, 16, 1, true, false)
        var open = false
        return Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "open" -> {
                    format = args!![0] as AudioFormat
                    open = true
                    null
                }

                "close" -> {
                    open = false
                    null
                }

                "isOpen" -> {
                    open
                }

                "isRunning", "isActive", "isControlSupported" -> {
                    false
                }

                "available", "getFramePosition" -> {
                    0
                }

                "getBufferSize" -> {
                    4_096
                }

                "getLongFramePosition", "getMicrosecondPosition" -> {
                    0L
                }

                "getFormat" -> {
                    format
                }

                "write" -> {
                    args!![2] as Int
                }

                else -> {
                    null
                }
            }
        } as SourceDataLine
    }

    private fun testRenderer(
        rate: Int,
        channels: Int,
    ): AudioRenderer {
        val silenceSettings: () -> Pair<Boolean, Boolean> = { false to false }
        val equalizer: () -> EqualizerProfile? = { null }
        return AudioRenderer::class.java
            .getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                SourceDataLine::class.java,
                Function0::class.java,
                Function0::class.java,
            ).newInstance(rate, channels, outputLine(), silenceSettings, equalizer)
    }
}
