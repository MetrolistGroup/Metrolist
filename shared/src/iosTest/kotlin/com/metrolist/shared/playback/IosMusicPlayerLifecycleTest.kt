@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)

package com.metrolist.shared.playback

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.equalizer.ParametricEqualizer
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.LoudnessLevel
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.currentItem
import platform.Foundation.NSNotificationCenter
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosMusicPlayerLifecycleTest {
    @Test
    fun stalledStartSnapshotBelongsOnlyToTheLatestRequestAndIsEmittedOnce() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val player = MusicPlayer(activateAudioSession = {}, outputVolume = { 1f })
            try {
                player.beginPlaybackRequest(IosPlaybackRequestLog(50L, "stalled-start-test"), 0L, true)
                runCurrent()
                advanceTimeBy(10_000L)
                player.beginPlaybackRequest(IosPlaybackRequestLog(51L, "stalled-start-test"), 0L, true)
                runCurrent()
                advanceTimeBy(15_000L)
                runCurrent()
                advanceTimeBy(30_000L)
                runCurrent()
                val snapshots =
                    PlaybackEventLog.events.value.filter {
                        it.videoId == "stalled-start-test" && it.message == "iOS playback start stalled"
                    }
                assertEquals(1, snapshots.size)
                assertEquals("51", snapshots.single().details["requestGeneration"])
            } finally {
                player.release()
            }
        }

    @Test
    fun pitchFailuresAreDrainedOncePerItem() =
        runTest {
            val log = IosPlaybackRequestLog(42L, "pitch-failure-test")
            val tap = IosAudioTapState(backgroundScope, MutableStateFlow(1f), MutableStateFlow(1f), MutableStateFlow(false), log)
            try {
                runCurrent()
                tap.pitchInitStatus = -10851
                tap.pitchRenderStatus = -50
                advanceTimeBy(250L)
                runCurrent()
                advanceTimeBy(500L)
                runCurrent()
                val failures = PlaybackEventLog.events.value.filter { it.videoId == log.videoId && "failed" in it.message }
                assertEquals(2, failures.size)
                assertEquals(setOf("-10851", "-50"), failures.map { it.details["osStatus"] }.toSet())
                assertTrue(failures.all { it.details["requestGeneration"] == "42" })
            } finally {
                tap.close()
            }
        }

    @Test
    fun mediaServicesResetRecreatesThePlayerWithoutReactivatingIt() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val nativePlayer = AVPlayer()
            var activations = 0
            val player = MusicPlayer(nativePlayer, activateAudioSession = { activations++ }, outputVolume = { 1f })
            val resource =
                PlaybackResource(
                    song.id,
                    "https://example.invalid/audio.m3u8",
                    emptyMap(),
                    null,
                    null,
                    mimeType = "application/x-mpegURL",
                )
            try {
                player.play(song, resource)
                assertEquals(1, activations)
                NSNotificationCenter.defaultCenter.postNotificationName(AVAudioSessionMediaServicesWereResetNotification, null)
                assertEquals(null, nativePlayer.currentItem)
                assertEquals(song.id, player.currentSong.value?.id)
                assertFalse(player.isPlaying.value)
                assertEquals(1, activations)
                player.play(song, resource)
                assertFalse(player.isPlaying.value)
                assertEquals(1, activations)
                player.resume()
                assertEquals(2, activations)
                assertTrue(player.isPlaying.value)
            } finally {
                player.release()
            }
        }

    @Test
    fun pcmNormalizationBoostsQuietSamplesAndStillProtectsPeaks() =
        runTest {
            Settings.shared.audioBoostDb = 0f
            val gain = MutableStateFlow(playbackNormalizationGain(null, true, LoudnessLevel.BALANCED, -30.0))
            val tap =
                IosAudioTapState(
                    backgroundScope,
                    gain,
                    MutableStateFlow(1f),
                    MutableStateFlow(false),
                    IosPlaybackRequestLog(1L, song.id),
                ).apply {
                    sampleRate = 48_000
                    channelCount = 2
                    equalizer = ParametricEqualizer(sampleRate, channelCount)
                    samples = floatArrayOf(0.1f, -0.1f)
                }
            try {
                runCurrent()
                tap.process(2)
                assertTrue(tap.samples[0] > 0.14f)
                assertEquals(-tap.samples[0], tap.samples[1])
                tap.samples = floatArrayOf(0.9f, -0.9f)
                tap.process(2)
                assertTrue(tap.samples.all { abs(it) <= 0.98f })
                gain.value = 1f
                runCurrent()
                tap.equalizer = ParametricEqualizer(48_000, 2)
                tap.samples = floatArrayOf(0.1f, -0.1f)
                tap.process(2)
                assertEquals(0.1f, tap.samples[0])
            } finally {
                tap.close()
            }
        }

    @Test
    fun audioTapUsesPublishedEqualizerSettingsAndStopsObservingWhenClosed() =
        runTest {
            val gain = MutableStateFlow(1f)
            val pitch = MutableStateFlow(1f)
            val tap =
                IosAudioTapState(backgroundScope, gain, pitch, MutableStateFlow(false), IosPlaybackRequestLog(1L, song.id)).apply {
                    sampleRate = 48_000
                    channelCount = 2
                    equalizer = ParametricEqualizer(sampleRate, channelCount)
                    samples = floatArrayOf(0.1f, -0.1f)
                }
            try {
                runCurrent()
                Settings.shared.equalizerEnabled = true
                Settings.shared.audioBoostDb = 6f
                Snapshot.sendApplyNotifications()
                tap.process(2)
                assertEquals(0.1f, tap.samples[0])

                runCurrent()
                tap.samples = floatArrayOf(0.1f, -0.1f)
                tap.process(2)
                assertTrue(tap.samples[0] in 0.19f..0.21f)
                assertEquals(-tap.samples[0], tap.samples[1])

                tap.close()
                Settings.shared.equalizerEnabled = false
                Snapshot.sendApplyNotifications()
                runCurrent()
                tap.samples = floatArrayOf(0.1f, -0.1f)
                tap.process(2)
                assertTrue(tap.samples[0] in 0.19f..0.21f)
            } finally {
                tap.close()
            }
        }

    @Test
    fun tempoAndPitchRestoreIndependentlyAndRejectNonFiniteUpdates() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.playbackSpeed = 1.25f
            Settings.shared.playbackPitch = 0.75f
            val player = MusicPlayer { 1f }
            try {
                assertEquals(1.25f, player.playbackSpeed.value)
                assertEquals(0.75f, player.playbackPitch.value)
                player.setPlaybackSpeed(2f)
                assertEquals(0.75f, player.playbackPitch.value)
                player.setPlaybackPitch(0.5f)
                assertEquals(2f, player.playbackSpeed.value)
                player.setPlaybackSpeed(Float.NaN)
                player.setPlaybackPitch(Float.POSITIVE_INFINITY)
                assertEquals(2f, player.playbackSpeed.value)
                assertEquals(0.5f, player.playbackPitch.value)
                player.setPlaybackSpeed(1f)
                player.setPlaybackPitch(1f)
                assertEquals(1f, player.playbackSpeed.value)
                assertEquals(1f, player.playbackPitch.value)
            } finally {
                player.release()
            }
        }

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun muteEventsKeepAutomaticResumeWithoutPollingOrOverridingManualPause() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.pauseOnMute = true
            var reads = 0
            var volume = 0f
            val player =
                MusicPlayer {
                    reads++
                    volume
                }
            try {
                runCurrent()
                advanceTimeBy(2000)
                assertEquals(0, reads)
                player.play(
                    song,
                    PlaybackResource(song.id, "https://example.invalid/audio", emptyMap(), null, null, requireBoundedRange = false),
                )
                runCurrent()
                assertTrue(reads > 0)
                assertTrue(player.isPlaying.value)
                player.outputVolumeChanged()
                assertTrue(player.isPlaying.value)
                val playingReads = reads
                advanceTimeBy(2000)
                assertEquals(playingReads, reads)
                volume = 1f
                player.outputVolumeChanged()
                assertTrue(player.isPlaying.value)
                volume = 0f
                player.outputVolumeChanged()
                runCurrent()
                assertFalse(player.isPlaying.value)
                val mutedReads = reads
                advanceTimeBy(2000)
                assertEquals(mutedReads, reads)
                volume = 1f
                player.outputVolumeChanged()
                runCurrent()
                assertTrue(reads > mutedReads)
                assertTrue(player.isPlaying.value)
                player.pause()
                runCurrent()
                val pausedReads = reads
                player.outputVolumeChanged()
                assertFalse(player.isPlaying.value)
                advanceTimeBy(2000)
                assertEquals(pausedReads, reads)
                player.resume()
                runCurrent()
                Settings.shared.pauseOnMute = false
                Snapshot.sendApplyNotifications()
                runCurrent()
                val disabledReads = reads
                volume = 0f
                player.outputVolumeChanged()
                assertTrue(player.isPlaying.value)
                advanceTimeBy(2000)
                assertEquals(disabledReads, reads)
                Settings.shared.pauseOnMute = true
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertTrue(player.isPlaying.value)
                volume = 1f
                player.outputVolumeChanged()
                volume = 0f
                player.outputVolumeChanged()
                runCurrent()
                assertFalse(player.isPlaying.value)
                player.resume()
                runCurrent()
                player.outputVolumeChanged()
                assertTrue(player.isPlaying.value)
                volume = 1f
                player.outputVolumeChanged()
                assertTrue(player.isPlaying.value)
                volume = 0f
                player.outputVolumeChanged()
                assertFalse(player.isPlaying.value)
                player.pause()
                volume = 1f
                player.outputVolumeChanged()
                runCurrent()
                assertFalse(player.isPlaying.value)
                player.stop()
            } finally {
                player.release()
            }
        }

    @Test
    fun nativeCompletionAdvancesOnlyTheCurrentItemAndIsDeduplicated() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val nativePlayer = AVPlayer()
            val player = MusicPlayer(initialPlayer = nativePlayer) { 1f }
            var completions = 0
            backgroundScope.launch { player.onEOF.collect { completions++ } }
            try {
                player.play(song, "https://example.invalid/first")
                val previousItem = nativePlayer.currentItem
                runCurrent()
                player.play(song.copy(id = "replacement"), "https://example.invalid/replacement")
                runCurrent()
                val notifications = NSNotificationCenter.defaultCenter
                notifications.postNotificationName(AVPlayerItemDidPlayToEndTimeNotification, previousItem)
                notifications.postNotificationName(AVPlayerItemFailedToPlayToEndTimeNotification, previousItem)
                runCurrent()
                assertEquals(0, completions)
                assertTrue(player.isPlaying.value)
                assertEquals(null, player.loadError.value)

                repeat(2) {
                    notifications.postNotificationName(AVPlayerItemDidPlayToEndTimeNotification, nativePlayer.currentItem)
                }
                runCurrent()
                assertEquals(1, completions)
                assertFalse(player.isPlaying.value)
            } finally {
                player.release()
            }
        }

    @Test
    fun durationProximityDoesNotEmitEofBeforeNativeCompletionAndCompletionIsDeduplicated() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val player = MusicPlayer()
            var completions = 0
            backgroundScope.launch { player.onEOF.collect { completions++ } }
            try {
                player.play(song.copy(duration = 1L), "https://example.invalid/audio")
                runCurrent()
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(0, completions)
                assertTrue(player.isPlaying.value)
                player.notifyPlaybackEnded()
                player.notifyPlaybackEnded()
                runCurrent()
                assertEquals(1, completions)
            } finally {
                player.release()
            }
        }

    @Test
    fun queuedCompletionCannotAdvanceReplacementPlayback() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val player = MusicPlayer()
            val completions = mutableListOf<Long>()
            backgroundScope.launch { player.onEOF.collect { completions += it } }
            try {
                player.play(song, "https://example.invalid/first")
                runCurrent()
                player.notifyPlaybackEnded()
                player.play(song.copy(id = "replacement"), "https://example.invalid/replacement")
                runCurrent()
                assertEquals(1, completions.size)
                assertFalse(player.consumePendingEof(completions.single()))

                player.notifyPlaybackEnded()
                runCurrent()
                assertTrue(player.consumePendingEof(completions.last()))
                assertFalse(player.consumePendingEof(completions.last()))
            } finally {
                player.release()
            }
        }

    private val song = SongItem("test", "test", null, emptyList(), null, duration = 180L)
}
