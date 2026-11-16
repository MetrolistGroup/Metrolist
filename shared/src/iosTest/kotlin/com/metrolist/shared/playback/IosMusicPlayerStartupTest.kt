@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)

package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.rate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosMusicPlayerStartupTest {
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
    fun readyFallbackResumesAfterReplacementFailsDuringTapLoading() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = StartupPlayer()
            try {
                fixture.player.play(song, hlsResource, startPositionMs = 12_000L)
                runCurrent()
                val fallbackItem = fixture.nativePlayer.currentItem
                assertTrue(fixture.player.replacePlaybackResource(song, directResource))
                runCurrent()
                assertEquals(1, fixture.taps.size)
                fixture.player.handlePlaybackFailed()
                runCurrent()
                assertEquals(fallbackItem, fixture.nativePlayer.currentItem)
                assertTrue(
                    fixture.taps
                        .single()
                        .job.isCancelled,
                )
                assertEquals(12_000L, fixture.seeks.last().positionMs)
                fixture.seeks.last().complete(true)
                runCurrent()
                assertEquals(1, fixture.activations)
                fixture.player.pause()
                fixture.player.resume()
                assertEquals(2, fixture.activations)
                assertTrue(fixture.player.isPlaying.value)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun loadingFallbackRestartsItsCancelledTapAndResumesWhenTheGateOpens() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = StartupPlayer()
            try {
                fixture.player.play(song, directResource, startPositionMs = 12_000L)
                runCurrent()
                assertTrue(
                    fixture.player.replacePlaybackResource(song, directResource.copy(url = "https://example.invalid/replacement.m4a")),
                )
                runCurrent()
                fixture.player.handlePlaybackFailed()
                runCurrent()
                assertEquals(3, fixture.taps.size)
                assertTrue(fixture.taps.take(2).all { it.job.isCancelled })
                fixture.seeks.last().complete(true)
                runCurrent()
                assertEquals(0, fixture.activations)
                fixture.taps.last().finish(false)
                runCurrent()
                assertEquals(1, fixture.activations)
                assertTrue(fixture.player.isPlaying.value)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun savedPositionSeekAtNonzeroSpeedWaitsForTapBeforeRequestingPlayback() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            Settings.shared.playbackSpeed = 1.25f
            val fixture = StartupPlayer()
            try {
                fixture.player.play(song, directResource, startPositionMs = 12_000L)
                runCurrent()
                fixture.seeks.single().complete(true)
                runCurrent()
                assertEquals(0, fixture.activations)
                assertEquals(0f, fixture.nativePlayer.rate)
                fixture.taps.single().finish(true)
                runCurrent()
                assertEquals(1, fixture.activations)
                assertEquals(1.25f, fixture.nativePlayer.rate)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun progressAfterBackwardSeekCancelsTheResumedRequestsStallSnapshot() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = StartupPlayer()
            val log = IosPlaybackRequestLog(82L, song.id)
            try {
                fixture.player.beginPlaybackRequest(log, 120_000L, startPlaying = false)
                fixture.player.play(song, hlsResource, startPlaying = false, startPositionMs = 120_000L)
                runCurrent()
                fixture.player.resume()
                fixture.player.seekTo(0L)
                runCurrent()
                fixture.seeks.last().complete(true)
                runCurrent()
                fixture.player.observeStartupProgress(1_000L, isRendering = true)
                val progress =
                    PlaybackEventLog.events.value.filter {
                        it.videoId == song.id && it.details["requestGeneration"] == "82" &&
                            it.message == "iOS playback first position progress"
                    }
                assertEquals(1, progress.size)
                assertEquals("1000", progress.single().details["positionMs"])
                advanceTimeBy(15_000L)
                runCurrent()
                assertFalse(
                    PlaybackEventLog.events.value.any {
                        it.videoId == song.id && it.details["requestGeneration"] == "82" && it.message == "iOS playback start stalled"
                    },
                )
            } finally {
                fixture.close()
            }
        }

    private class PendingSeek(
        val positionMs: Long,
        val complete: (Boolean) -> Unit,
    )

    private class PendingTap(
        private val complete: (Boolean) -> Unit,
    ) {
        val job = Job()

        fun finish(installed: Boolean) {
            complete(installed)
            job.complete()
        }
    }

    private class StartupPlayer {
        val nativePlayer = AVPlayer()
        val seeks = mutableListOf<PendingSeek>()
        val taps = mutableListOf<PendingTap>()
        var activations = 0
        val player =
            MusicPlayer(
                initialPlayer = nativePlayer,
                activateAudioSession = { activations++ },
                seek = { _, position, complete -> seeks.add(PendingSeek(position, complete)) },
                currentAudioTapInstaller = { _, complete -> PendingTap(complete).also(taps::add).job },
                outputVolume = { 1f },
            )

        fun close() {
            player.release()
            taps.forEach { it.job.cancel() }
        }
    }

    private val song = SongItem("startup-review-test", "test", null, emptyList(), null, duration = 180L)
    private val directResource =
        PlaybackResource(
            song.id,
            "https://example.invalid/audio.m4a",
            emptyMap(),
            null,
            null,
            mimeType = "audio/mp4",
            requireBoundedRange = false,
        )
    private val hlsResource = directResource.copy(url = "https://example.invalid/audio.m3u8", mimeType = "application/x-mpegURL")
}
