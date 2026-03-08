package com.metrolist.android.playback

import com.metrolist.android.ui.components.CanvasPlaybackRecovery
import com.metrolist.android.ui.components.CanvasPlaybackSource
import com.metrolist.android.ui.components.canvasPlaybackSources
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasPlaybackRecoveryTest {
    private val clips = listOf(YouTubeCanvasClip(20_000, 23_000))
    private val resource = PlaybackResource("song", "audio", emptyMap(), null, null, videoUrl = "https://example.com/video")
    private val stream = CanvasPlaybackSource(resource.videoUrl!!, null, clips)
    private val prepared = CanvasPlaybackSource("file:///cache/canvas-clips.media", null, emptyList())

    @Test
    fun streamFailureStillAllowsPendingPreparedLoopWithCachingEnabledOrDisabled() =
        runTest {
            for (cacheKey in listOf("prepared", null)) {
                val sources =
                    canvasPlaybackSources(
                        resource.videoUrl!!,
                        resource,
                        cacheKey,
                        clips,
                        cachedAvailable = false,
                        preparingClips = true,
                    )
                val harness = Harness(sources.first, sources.second)
                val preparation = CompletableDeferred<CanvasPlaybackSource?>()
                launch { harness.recovery.onPreparationFinished(preparation.await()) }
                harness.recovery.start()
                harness.recovery.onPlaybackError()
                runCurrent()
                assertEquals(listOf("play:${stream.url}", "stop"), harness.events)
                assertFalse(harness.recovery.failed)
                assertFalse(harness.recovery.isPlaybackActive)

                preparation.complete(prepared)
                runCurrent()
                assertEquals(listOf("play:${stream.url}", "stop", "play:${prepared.url}"), harness.events)
                assertTrue(harness.recovery.isPlaybackActive)
                assertEquals(listOf(stream, prepared), harness.sources)

                harness.recovery.onPlaybackError()
                assertTrue(harness.recovery.failed)
                assertEquals(listOf(stream, prepared), harness.sources)
                assertEquals(1, harness.events.count { it == "error" })
            }
        }

    @Test
    fun streamFailureWaitsForPreparationFailureBeforeReportingOnce() {
        val harness = Harness()
        harness.recovery.start()
        harness.recovery.onPlaybackError()
        harness.recovery.onPlaybackError()
        assertFalse(harness.recovery.failed)
        harness.recovery.onPreparationFinished(null)
        harness.recovery.onPlaybackError()
        harness.recovery.onPreparationFinished(prepared)
        assertTrue(harness.recovery.failed)
        assertEquals(listOf("play:${stream.url}", "stop", "error"), harness.events)
    }

    @Test
    fun preparationFailureKeepsWorkingStreamUntilItFails() {
        val harness = Harness()
        harness.recovery.start()
        harness.recovery.onPreparationFinished(null)
        assertTrue(harness.recovery.isPlaybackActive)
        assertEquals(listOf("play:${stream.url}"), harness.events)
        harness.recovery.onPlaybackError()
        assertEquals(listOf("play:${stream.url}", "stop", "error"), harness.events)
    }

    @Test
    fun preparedPlaybackFailureCanReturnToWorkingStreamOnlyOnce() {
        val harness = Harness()
        harness.recovery.start()
        harness.recovery.onPreparationFinished(prepared)
        harness.recovery.onPlaybackError()
        assertEquals(listOf(stream, prepared, stream), harness.sources)
        assertTrue(harness.recovery.isPlaybackActive)
        harness.recovery.onPlaybackError()
        assertTrue(harness.recovery.failed)
        assertEquals(1, harness.events.count { it == "error" })
    }

    @Test
    fun synchronousStreamPreparationFailureStillAllowsPreparedLoop() {
        val harness = Harness(rejectedSource = stream)
        harness.recovery.start()
        assertFalse(harness.recovery.failed)
        harness.recovery.onPreparationFinished(prepared)
        assertEquals(listOf(stream, prepared), harness.sources)
        assertTrue(harness.recovery.isPlaybackActive)
    }

    @Test
    fun synchronousPreparedHandoffFailureRecoversToWorkingStream() {
        val harness = Harness(rejectedSource = prepared)
        harness.recovery.start()
        harness.recovery.onPreparationFinished(prepared)
        assertEquals(listOf(stream, prepared, stream), harness.sources)
        assertTrue(harness.recovery.isPlaybackActive)
    }

    @Test
    fun playbackWithoutPendingPreparationFailsImmediately() {
        val harness = Harness(preparingClips = false)
        harness.recovery.start()
        harness.recovery.onPlaybackError()
        assertTrue(harness.recovery.failed)
        assertEquals(listOf("play:${stream.url}", "stop", "error"), harness.events)
    }

    @Test
    fun cachedLoopRetainsItsExistingStreamRecovery() {
        val harness = Harness(initial = prepared, fallback = stream, preparingClips = false)
        harness.recovery.start()
        harness.recovery.onPlaybackError()
        assertEquals(listOf(prepared, stream), harness.sources)
        assertTrue(harness.recovery.isPlaybackActive)
        harness.recovery.onPlaybackError()
        assertTrue(harness.recovery.failed)
    }

    private inner class Harness(
        initial: CanvasPlaybackSource = stream,
        fallback: CanvasPlaybackSource? = null,
        preparingClips: Boolean = true,
        rejectedSource: CanvasPlaybackSource? = null,
    ) {
        val events = mutableListOf<String>()
        val sources = mutableListOf<CanvasPlaybackSource>()
        val recovery =
            CanvasPlaybackRecovery(
                initialSource = initial,
                fallback = fallback,
                preparingClips = preparingClips,
                prepareSource = {
                    events += "play:${it.url}"
                    sources += it
                    it != rejectedSource
                },
                stopPlayback = { events += "stop" },
                onError = { events += "error" },
            )
    }
}
