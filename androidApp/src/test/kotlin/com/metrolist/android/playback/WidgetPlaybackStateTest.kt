package com.metrolist.android.playback

import com.metrolist.shared.cast.CastConnectionStatus
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WidgetPlaybackStateTest {
    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @After
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun widgetActionFollowsPlaybackRequestsDuringBufferingAndCastHandoffs() =
        runTest {
            val wantsPlayback = MutableStateFlow(false)
            val remote = MutableStateFlow(CastState())
            val requests = mutableListOf<Boolean>()
            val job = launch { widgetPlaybackRequested(wantsPlayback, remote).toList(requests) }
            runCurrent()
            wantsPlayback.value = true
            runCurrent()
            wantsPlayback.value = false
            runCurrent()
            val connected =
                CastState(
                    status = CastConnectionStatus.CONNECTED,
                    device = CastDevice("device", "Receiver", CastProtocol.FCAST, "192.0.2.1", 46899),
                    isPlaying = true,
                )
            remote.value = connected
            runCurrent()
            wantsPlayback.value = true
            runCurrent()
            remote.value = connected.copy(isPlaying = false)
            runCurrent()
            remote.value = CastState()
            runCurrent()
            assertEquals(listOf(false, true, false, true, false, true), requests)
            job.cancel()
        }

    @Test
    fun dismissalClearsMetadataArtworkAndTransportEvenBeforePlayerFlowsReset() {
        val state = playbackWidgetState(null, true, true, 200_000, 90_000, "Idle", "Choose music")
        assertEquals("Idle", state.title)
        assertEquals("Choose music", state.artist)
        assertNull(state.artworkUri)
        assertFalse(state.isPlaying)
        assertFalse(state.isLiked)
        assertEquals(0L, state.duration)
        assertEquals(0L, state.currentPosition)
    }

    @Test
    fun pausingPreservesMetadataArtworkLikeAndProgress() {
        val song = SongItem("id", "Title", "artwork", listOf(ArtistTiny(null, "Artist")), null)
        val state = playbackWidgetState(song, false, true, 200_000, 90_000, "Idle", "Choose music")
        assertEquals("Title", state.title)
        assertEquals("Artist", state.artist)
        assertEquals("artwork", state.artworkUri)
        assertFalse(state.isPlaying)
        assertEquals(true, state.isLiked)
        assertEquals(200_000L, state.duration)
        assertEquals(90_000L, state.currentPosition)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun widgetResumeWaitsForPromotionAndAbortsOnFailureOrReplacement() =
        runTest {
            val promoted = CompletableDeferred<Boolean>()
            var commands = 0
            var current = true
            val pending =
                launch {
                    runWidgetPlaybackCommand(true, { promoted.await() }, { current }) { commands++ }
                }
            runCurrent()
            assertEquals(0, commands)
            promoted.complete(true)
            pending.join()
            assertEquals(1, commands)
            runWidgetPlaybackCommand(true, { false }, { true }) { commands++ }
            assertEquals(1, commands)
            runWidgetPlaybackCommand(true, {
                current = false
                true
            }, { current }) { commands++ }
            assertEquals(1, commands)
            runWidgetPlaybackCommand(false, { error("Pause must not promote the service") }, { true }) { commands++ }
            assertEquals(2, commands)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aLaterPauseInvalidatesAResumeWaitingForForegroundPromotion() =
        runTest {
            val promoted = CompletableDeferred<Boolean>()
            var generation = 0L
            val requestedGeneration = generation
            var resumes = 0
            val pending =
                launch {
                    runWidgetPlaybackCommand(true, { promoted.await() }, { generation == requestedGeneration }) { resumes++ }
                }
            runCurrent()
            generation++
            promoted.complete(true)
            pending.join()
            assertEquals(0, resumes)
        }
}
